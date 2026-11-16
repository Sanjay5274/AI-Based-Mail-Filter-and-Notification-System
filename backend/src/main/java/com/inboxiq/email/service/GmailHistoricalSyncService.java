package com.inboxiq.email.service;

import com.inboxiq.account.entity.ConnectedAccount;
import com.inboxiq.account.gmail.model.GmailMessage;
import com.inboxiq.account.gmail.model.GmailMessageListPage;
import com.inboxiq.account.gmail.model.GmailMessageRef;
import com.inboxiq.account.gmail.model.GmailProfile;
import com.inboxiq.account.gmail.service.GmailApiClient;
import com.inboxiq.account.gmail.service.GmailService;
import com.inboxiq.account.repository.ConnectedAccountRepository;
import com.inboxiq.common.exception.ConflictException;
import com.inboxiq.common.exception.GmailApiException;
import com.inboxiq.common.exception.GoogleCredentialsRevokedException;
import com.inboxiq.common.exception.ResourceNotFoundException;
import com.inboxiq.email.dto.HistoricalSyncResponse;
import com.inboxiq.email.dto.HistoricalSyncResult;
import com.inboxiq.email.entity.Email;
import com.inboxiq.email.entity.EmailAttachment;
import com.inboxiq.email.entity.EmailSyncFailure;
import com.inboxiq.email.parser.GmailMimeParser;
import com.inboxiq.email.parser.ParsedAttachment;
import com.inboxiq.email.parser.ParsedEmail;
import com.inboxiq.email.repository.EmailAttachmentRepository;
import com.inboxiq.email.repository.EmailRepository;
import com.inboxiq.email.repository.EmailSyncFailureRepository;
import com.inboxiq.processing.entity.ProcessingAttempt;
import com.inboxiq.processing.entity.ProcessingJob;
import com.inboxiq.processing.repository.ProcessingJobRepository;
import com.inboxiq.processing.service.ProcessingJobHandler;
import com.inboxiq.processing.service.ProcessingJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Hardened historical Gmail synchronization service.
 *
 * <p>Key Guarantees:
 * <ul>
 *   <li>Atomic concurrency protection using database transactions and unique constraints.</li>
 *   <li>Durable execution through PostgreSQL-backed processing_jobs (no in-memory queue dependency).</li>
 *   <li>External Gmail API calls executed strictly outside database transactions.</li>
 *   <li>Idempotent pagination checkpointing with invalid token recovery.</li>
 *   <li>Durable tracking of failed messages; cursor never advances if failures remain.</li>
 *   <li>Atomic email and inline attachment metadata persistence.</li>
 * </ul>
 */
@Service
public class GmailHistoricalSyncService implements ProcessingJobHandler {

    private static final Logger log = LoggerFactory.getLogger(GmailHistoricalSyncService.class);

    private final ConnectedAccountRepository connectedAccountRepository;
    private final EmailRepository emailRepository;
    private final EmailAttachmentRepository emailAttachmentRepository;
    private final EmailSyncFailureRepository emailSyncFailureRepository;
    private final EmailPersistenceService emailPersistenceService;
    private final ProcessingJobRepository processingJobRepository;
    private final ProcessingJobService processingJobService;
    private final GmailApiClient gmailApiClient;
    private final GmailService gmailService;
    private final GmailMimeParser gmailMimeParser;

    private final int defaultLookbackDays;
    private final int maxLookbackDays;
    private final int batchSize;

    public GmailHistoricalSyncService(
            ConnectedAccountRepository connectedAccountRepository,
            EmailRepository emailRepository,
            EmailAttachmentRepository emailAttachmentRepository,
            EmailSyncFailureRepository emailSyncFailureRepository,
            EmailPersistenceService emailPersistenceService,
            ProcessingJobRepository processingJobRepository,
            ProcessingJobService processingJobService,
            GmailApiClient gmailApiClient,
            GmailService gmailService,
            GmailMimeParser gmailMimeParser,
            @Value("${inboxiq.gmail.sync.default-lookback-days:30}") int defaultLookbackDays,
            @Value("${inboxiq.gmail.sync.max-lookback-days:365}") int maxLookbackDays,
            @Value("${inboxiq.gmail.sync.batch-size:50}") int batchSize
    ) {
        this.connectedAccountRepository = connectedAccountRepository;
        this.emailRepository = emailRepository;
        this.emailAttachmentRepository = emailAttachmentRepository;
        this.emailSyncFailureRepository = emailSyncFailureRepository;
        this.emailPersistenceService = emailPersistenceService;
        this.processingJobRepository = processingJobRepository;
        this.processingJobService = processingJobService;
        this.gmailApiClient = gmailApiClient;
        this.gmailService = gmailService;
        this.gmailMimeParser = gmailMimeParser;
        this.defaultLookbackDays = defaultLookbackDays;
        this.maxLookbackDays = maxLookbackDays;
        this.batchSize = batchSize;
    }

    /**
     * Atomically claims an account and enqueues a durable historical sync job within a single transaction.
     * If job creation or active constraint fails, the entire transaction rolls back, leaving the account clean.
     */
    @Transactional
    public HistoricalSyncResponse initiateSync(UUID accountId, UUID requestingUserId, Integer requestedLookbackDays) {
        ConnectedAccount account = connectedAccountRepository.findByIdAndUserIdForUpdate(accountId, requestingUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Connected account not found with ID: " + accountId));

        if (!account.isActive()) {
            throw new ConflictException("Connected account is not active: " + accountId);
        }

        // Verify no active job exists for this account
        boolean activeJobExists = processingJobRepository.existsByTargetTypeAndTargetIdAndStatusIn(
                "CONNECTED_ACCOUNT", accountId, List.of("PENDING", "PROCESSING", "RETRY_WAIT")
        );

        if (activeJobExists || "SYNCING".equalsIgnoreCase(account.getSyncStatus())) {
            log.warn("Rejected concurrent sync request for account {}", accountId);
            throw new ConflictException("Synchronization is already in progress for account: " + accountId);
        }

        int lookbackDays = (requestedLookbackDays != null && requestedLookbackDays > 0)
                ? Math.min(requestedLookbackDays, maxLookbackDays)
                : defaultLookbackDays;

        // Transition account to SYNCING
        account.setSyncStatus("SYNCING");
        connectedAccountRepository.save(account);

        // Enqueue durable ProcessingJob with stable unique run ID
        UUID jobId = UUID.randomUUID();
        String idempotencyKey = "SYNC:HISTORICAL:" + accountId + ":" + jobId;
        ProcessingJob job = new ProcessingJob(
                jobId,
                "SYNC_ACCOUNT",
                "CONNECTED_ACCOUNT",
                accountId,
                idempotencyKey,
                1
        );
        job.setStatus("PENDING");
        job.setLookbackDays(lookbackDays);
        job.setNextRetryAt(Instant.now());

        try {
            processingJobRepository.save(job);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Concurrent job insertion detected for account {}", accountId);
            throw new ConflictException("Synchronization job is already active for account: " + accountId);
        }

        log.info("Enqueued durable historical sync job {} for account {} (lookback: {} days)",
                jobId, accountId, lookbackDays);

        return new HistoricalSyncResponse(
                jobId,
                accountId,
                "SYNCING",
                lookbackDays,
                "Historical email synchronization enqueued successfully"
        );
    }

    @Override
    public void handleJob(UUID jobId, String workerId) {
        executeSyncJob(jobId, workerId);
    }

    /**
     * Executes the historical sync job durably with checkpointing, lease renewal, and failed message tracking.
     */
    public HistoricalSyncResult executeSyncJob(UUID jobId, String workerId) {
        Instant startTime = Instant.now();
        log.info("Starting historical sync job {} by worker {}", jobId, workerId);

        Optional<ProcessingJob> jobOpt = processingJobRepository.findById(jobId);
        if (jobOpt.isEmpty()) {
            log.error("Job not found: {}", jobId);
            return null;
        }

        ProcessingJob job = jobOpt.get();
        UUID accountId = job.getTargetId();
        int attemptNum = job.getRetryCount() + 1;
        ProcessingAttempt attempt = processingJobService.startAttempt(jobId, attemptNum);

        Optional<ConnectedAccount> accountOpt = connectedAccountRepository.findById(accountId);
        if (accountOpt.isEmpty() || !accountOpt.get().isActive()) {
            String errorMsg = "Account is inactive or not found: " + accountId;
            log.warn(errorMsg);
            processingJobService.failJob(jobId, errorMsg, true);
            processingJobService.finishAttempt(attempt.getId(), "FAILED",
                    Duration.between(startTime, Instant.now()).toMillis(), errorMsg);
            return null;
        }

        ConnectedAccount account = accountOpt.get();
        String accessToken;
        try {
            accessToken = gmailService.resolveValidAccessToken(account);
        } catch (GoogleCredentialsRevokedException e) {
            log.warn("Google credentials revoked during sync for account {}: {}", accountId, e.getMessage());
            account.setActive(false);
            account.setSyncStatus("FAILED");
            connectedAccountRepository.save(account);

            processingJobService.failJob(jobId, e.getMessage(), true);
            processingJobService.finishAttempt(attempt.getId(), "FAILED",
                    Duration.between(startTime, Instant.now()).toMillis(), e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Failed to resolve access token for account {}: {}", accountId, e.getMessage(), e);
            processingJobService.failJob(jobId, e.getMessage(), false);
            processingJobService.finishAttempt(attempt.getId(), "FAILED",
                    Duration.between(startTime, Instant.now()).toMillis(), e.getMessage());
            return null;
        }

        // Step 1: Ensure baseline historyId is captured and checkpointed
        String initialHistoryId = job.getInitialHistoryId();
        if (initialHistoryId == null || initialHistoryId.isBlank()) {
            try {
                GmailProfile profile = gmailApiClient.getProfile(accessToken);
                initialHistoryId = profile.historyId();
                processingJobService.recordInitialHistoryId(jobId, initialHistoryId);
                log.info("Captured baseline historyId {} for account {} prior to historical listing",
                        initialHistoryId, accountId);
            } catch (Exception e) {
                log.error("Failed to fetch mailbox profile for account {}: {}", accountId, e.getMessage());
                processingJobService.failJob(jobId, e.getMessage(), isFatalError(e));
                processingJobService.finishAttempt(attempt.getId(), "FAILED",
                        Duration.between(startTime, Instant.now()).toMillis(), e.getMessage());
                return null;
            }
        }

        // Step 2: Compute search query for lookback window
        int lookbackDays = job.getLookbackDays() != null ? job.getLookbackDays() : defaultLookbackDays;
        Instant cutoff = Instant.now().minus(Duration.ofDays(lookbackDays));
        String query = "after:" + cutoff.getEpochSecond();

        // Step 3: Resume from checkpoint pagination token if present
        String pageToken = job.getPaginationToken();
        int totalFound = job.getTotalFound();
        int processedCount = job.getProcessedCount();
        int duplicateCount = job.getDuplicateCount();
        int failedCount = job.getFailedCount();

        try {
            do {
                GmailMessageListPage page;
                try {
                    page = gmailApiClient.listMessages(accessToken, batchSize, pageToken, query);
                } catch (GmailApiException apiEx) {
                    // Check if pageToken expired or became invalid -> restart from beginning
                    if (pageToken != null && (apiEx.getHttpStatus() == 400 ||
                            (apiEx.getMessage() != null && apiEx.getMessage().toLowerCase().contains("invalid")))) {
                        log.warn("Page token '{}' invalid for job {}. Restarting pagination from beginning safely.", pageToken, jobId);
                        pageToken = null;
                        processingJobService.updatePaginationCheckpoint(jobId, null, 0, 0, 0, 0, workerId, Duration.ofMinutes(5));
                        page = gmailApiClient.listMessages(accessToken, batchSize, null, query);
                    } else {
                        throw apiEx;
                    }
                }

                if (page == null || page.messages() == null || page.messages().isEmpty()) {
                    break;
                }

                int pageFound = page.messages().size();
                int pageProcessed = 0;
                int pageDuplicates = 0;
                int pageFailed = 0;

                for (GmailMessageRef messageRef : page.messages()) {
                    String gmailMsgId = messageRef.id();

                    if (emailRepository.existsByConnectedAccountIdAndGmailMessageId(accountId, gmailMsgId)) {
                        pageDuplicates++;
                        continue;
                    }

                    // Fetch full message with bounded retries for transient failures
                    GmailMessage fullMessage = null;
                    Exception lastFetchEx = null;

                    for (int fetchAttempt = 1; fetchAttempt <= 3; fetchAttempt++) {
                        try {
                            fullMessage = gmailApiClient.getMessage(accessToken, gmailMsgId, "full");
                            break;
                        } catch (Exception ex) {
                            lastFetchEx = ex;
                            if (isTransientError(ex) && fetchAttempt < 3) {
                                try {
                                    Thread.sleep(fetchAttempt * 150L);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                            } else {
                                break;
                            }
                        }
                    }

                    if (fullMessage == null) {
                        pageFailed++;
                        recordSyncFailure(jobId, accountId, gmailMsgId, lastFetchEx);
                        continue;
                    }

                    try {
                        ParsedEmail parsed = gmailMimeParser.parse(fullMessage);
                        boolean inserted = saveEmailWithAttachments(account, parsed);
                        if (inserted) {
                            pageProcessed++;
                            markSyncFailureResolved(jobId, gmailMsgId);
                        } else {
                            pageDuplicates++;
                        }
                    } catch (Exception parseOrSaveEx) {
                        log.warn("Failed to parse or save email {} for account {}: {}",
                                gmailMsgId, accountId, parseOrSaveEx.getMessage());
                        pageFailed++;
                        recordSyncFailure(jobId, accountId, gmailMsgId, parseOrSaveEx);
                    }
                }

                totalFound += pageFound;
                processedCount += pageProcessed;
                duplicateCount += pageDuplicates;
                failedCount += pageFailed;

                // Advance checkpoint and extend lease
                pageToken = page.nextPageToken();
                processingJobService.updatePaginationCheckpoint(
                        jobId,
                        pageToken,
                        pageFound,
                        pageProcessed,
                        pageDuplicates,
                        pageFailed,
                        workerId,
                        Duration.ofMinutes(5)
                );

            } while (pageToken != null && !pageToken.isBlank());

            // Step 4: Completion Validation
            long unresolvedFailures = emailSyncFailureRepository.countByJobIdAndIsResolvedFalse(jobId);

            if (unresolvedFailures > 0) {
                String failureMsg = "Historical sync finished with " + unresolvedFailures + " unresolved message failures";
                log.warn("Job {} for account {}: {}", jobId, accountId, failureMsg);

                // Do not advance last_history_id when failures remain
                processingJobService.failJob(jobId, failureMsg, false);
                processingJobService.finishAttempt(attempt.getId(), "FAILED",
                        Duration.between(startTime, Instant.now()).toMillis(), failureMsg);

                return new HistoricalSyncResult(
                        accountId, jobId, totalFound, processedCount, duplicateCount, (int) unresolvedFailures,
                        initialHistoryId, "FAILED"
                );
            }

            // Ingestion fully complete with zero unresolved failures
            account.setLastHistoryId(initialHistoryId);
            account.setLastSyncAt(Instant.now());
            account.setSyncStatus("IDLE");
            connectedAccountRepository.save(account);

            processingJobService.completeJob(jobId);
            processingJobService.finishAttempt(attempt.getId(), "COMPLETED",
                    Duration.between(startTime, Instant.now()).toMillis(), null);

            log.info("Historical sync job {} completed successfully for account {}: found={}, processed={}, duplicates={}, failed=0",
                    jobId, accountId, totalFound, processedCount, duplicateCount);

            return new HistoricalSyncResult(
                    accountId, jobId, totalFound, processedCount, duplicateCount, 0,
                    initialHistoryId, "COMPLETED"
            );

        } catch (Exception e) {
            log.error("Fatal error during historical sync job {}: {}", jobId, e.getMessage(), e);
            boolean isFatal = isFatalError(e);
            processingJobService.failJob(jobId, e.getMessage(), isFatal);
            processingJobService.finishAttempt(attempt.getId(), "FAILED",
                    Duration.between(startTime, Instant.now()).toMillis(), e.getMessage());

            if (isFatal) {
                account.setSyncStatus("FAILED");
                connectedAccountRepository.save(account);
            }

            return new HistoricalSyncResult(
                    accountId, jobId, totalFound, processedCount, duplicateCount, failedCount,
                    initialHistoryId, "FAILED"
            );
        }
    }

    /**
     * Synchronous execution wrapper designed for unit and integration test invocation.
     */
    public HistoricalSyncResult executeSync(UUID jobId, UUID accountId, UUID requestingUserId, int lookbackDays) {
        String testWorkerId = "test-worker-" + UUID.randomUUID().toString().substring(0, 8);
        if (jobId == null) {
            jobId = UUID.randomUUID();
            processingJobService.createProcessingJobWithStatus(
                    jobId, "SYNC_ACCOUNT", "CONNECTED_ACCOUNT", accountId,
                    "SYNC:TEST:" + accountId + ":" + jobId, 1, lookbackDays,
                    "PROCESSING", testWorkerId, Instant.now().plus(Duration.ofMinutes(10))
            );
        }
        return executeSyncJob(jobId, testWorkerId);
    }

    /**
     * Persists an individual email and all its attachment metadata in an isolated database transaction.
     */
    public boolean saveEmailWithAttachments(ConnectedAccount account, ParsedEmail parsed) {
        return emailPersistenceService.saveEmailWithAttachments(account, parsed);
    }

    public void recordSyncFailure(UUID jobId, UUID accountId, String gmailMessageId, Exception ex) {
        String category = categorizeFailure(ex);
        String rawMsg = ex != null ? ex.getMessage() : "Unknown ingestion error";
        String sanitized = rawMsg != null ? rawMsg.replaceAll("Bearer\\s+[A-Za-z0-9_.-]+", "Bearer [REDACTED]") : "Unknown";
        emailPersistenceService.recordSyncFailure(jobId, accountId, gmailMessageId, category, sanitized);
    }

    public void markSyncFailureResolved(UUID jobId, String gmailMessageId) {
        emailPersistenceService.markSyncFailureResolved(jobId, gmailMessageId);
    }

    private boolean isTransientError(Exception ex) {
        if (ex instanceof GmailApiException gae) {
            int code = gae.getHttpStatus();
            return code == 429 || code == 503 || code == 500 || code == 502 || code == 504;
        }
        return ex instanceof SocketTimeoutException || ex instanceof IOException;
    }

    private boolean isFatalError(Exception ex) {
        if (ex instanceof GoogleCredentialsRevokedException) {
            return true;
        }
        if (ex instanceof GmailApiException gae) {
            return gae.getHttpStatus() == 401 || gae.getHttpStatus() == 403;
        }
        return false;
    }

    private String categorizeFailure(Exception ex) {
        if (isTransientError(ex)) {
            return "TRANSIENT";
        }
        if (ex instanceof GmailApiException gae && (gae.getHttpStatus() == 404 || gae.getHttpStatus() == 400)) {
            return "PERMANENT";
        }
        return "PARSE_ERROR";
    }
}
