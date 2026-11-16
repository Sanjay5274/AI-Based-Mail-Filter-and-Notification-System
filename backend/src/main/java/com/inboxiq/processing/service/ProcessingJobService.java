package com.inboxiq.processing.service;

import com.inboxiq.account.entity.ConnectedAccount;
import com.inboxiq.account.repository.ConnectedAccountRepository;
import com.inboxiq.processing.entity.ProcessingAttempt;
import com.inboxiq.processing.entity.ProcessingJob;
import com.inboxiq.processing.repository.ProcessingAttemptRepository;
import com.inboxiq.processing.repository.ProcessingJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ProcessingJobService {

    private static final Logger log = LoggerFactory.getLogger(ProcessingJobService.class);

    private final ProcessingJobRepository processingJobRepository;
    private final ProcessingAttemptRepository processingAttemptRepository;
    private final ConnectedAccountRepository connectedAccountRepository;

    public ProcessingJobService(
            ProcessingJobRepository processingJobRepository,
            ProcessingAttemptRepository processingAttemptRepository,
            ConnectedAccountRepository connectedAccountRepository
    ) {
        this.processingJobRepository = processingJobRepository;
        this.processingAttemptRepository = processingAttemptRepository;
        this.connectedAccountRepository = connectedAccountRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProcessingJob createProcessingJobWithStatus(
            UUID id, String jobType, String targetType, UUID targetId,
            String idempotencyKey, int priority, Integer lookbackDays,
            String status, String lockedBy, Instant lockedUntil
    ) {
        ProcessingJob job = new ProcessingJob(id, jobType, targetType, targetId, idempotencyKey, priority);
        job.setStatus(status);
        job.setLookbackDays(lookbackDays);
        job.setLockedBy(lockedBy);
        job.setLockedUntil(lockedUntil);
        job.setNextRetryAt(Instant.now());
        return processingJobRepository.save(job);
    }

    /**
     * Finds and claims available jobs atomically using FOR UPDATE SKIP LOCKED.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ProcessingJob> claimJobs(String workerId, Duration leaseDuration, int limit) {
        Instant now = Instant.now();
        List<ProcessingJob> claimable = processingJobRepository.findClaimableJobs(now, limit);

        for (ProcessingJob job : claimable) {
            job.setStatus("PROCESSING");
            job.setLockedBy(workerId);
            job.setLockedUntil(now.plus(leaseDuration));
            if (job.getStartedAt() == null) {
                job.setStartedAt(now);
            }
            processingJobRepository.save(job);
            log.debug("Worker {} claimed job {} (type: {}) until {}",
                    workerId, job.getId(), job.getJobType(), job.getLockedUntil());
        }

        return claimable;
    }

    /**
     * Renews the lease for an actively executing job.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean renewLease(UUID jobId, String workerId, Duration extraTime) {
        Optional<ProcessingJob> jobOpt = processingJobRepository.findById(jobId);
        if (jobOpt.isPresent()) {
            ProcessingJob job = jobOpt.get();
            if (workerId.equals(job.getLockedBy()) && "PROCESSING".equals(job.getStatus())) {
                job.setLockedUntil(Instant.now().plus(extraTime));
                processingJobRepository.save(job);
                return true;
            }
        }
        return false;
    }

    /**
     * Persists pagination progress and metrics checkpoint in a short transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updatePaginationCheckpoint(
            UUID jobId,
            String paginationToken,
            int totalFoundDelta,
            int processedDelta,
            int duplicateDelta,
            int failedDelta,
            String workerId,
            Duration leaseExtension
    ) {
        processingJobRepository.findById(jobId).ifPresent(job -> {
            job.setPaginationToken(paginationToken);
            job.setTotalFound(job.getTotalFound() + totalFoundDelta);
            job.setProcessedCount(job.getProcessedCount() + processedDelta);
            job.setDuplicateCount(job.getDuplicateCount() + duplicateDelta);
            job.setFailedCount(job.getFailedCount() + failedDelta);
            if (workerId != null && workerId.equals(job.getLockedBy())) {
                job.setLockedUntil(Instant.now().plus(leaseExtension));
            }
            processingJobRepository.save(job);
        });
    }

    /**
     * Records initial history ID captured prior to historical sync.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordInitialHistoryId(UUID jobId, String initialHistoryId) {
        processingJobRepository.findById(jobId).ifPresent(job -> {
            job.setInitialHistoryId(initialHistoryId);
            processingJobRepository.save(job);
        });
    }

    /**
     * Marks a job COMPLETED and releases the lock.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeJob(UUID jobId) {
        processingJobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus("COMPLETED");
            job.setCompletedAt(Instant.now());
            job.setLockedBy(null);
            job.setLockedUntil(null);
            processingJobRepository.save(job);
        });
    }

    /**
     * Handles job failure with exponential backoff or DEAD transition.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failJob(UUID jobId, String errorMessage, boolean permanent) {
        processingJobRepository.findById(jobId).ifPresent(job -> {
            job.setLastError(errorMessage);
            job.setLockedBy(null);
            job.setLockedUntil(null);

            int currentRetries = job.getRetryCount() + 1;
            job.setRetryCount(currentRetries);

            if (permanent || currentRetries >= job.getMaxRetries()) {
                job.setStatus("DEAD");
                job.setCompletedAt(Instant.now());
                log.warn("Job {} reached DEAD status (retries: {}/{}, error: {})",
                        jobId, currentRetries, job.getMaxRetries(), errorMessage);

                // Release connected account to FAILED
                if ("CONNECTED_ACCOUNT".equals(job.getTargetType())) {
                    connectedAccountRepository.findById(job.getTargetId()).ifPresent(acc -> {
                        acc.setSyncStatus("FAILED");
                        connectedAccountRepository.save(acc);
                    });
                }
            } else {
                job.setStatus("RETRY_WAIT");
                // Exponential backoff with jitter: 2^retry * 10 seconds + random jitter
                long backoffSeconds = (long) Math.pow(2, currentRetries) * 10L;
                long jitter = ThreadLocalRandom.current().nextLong(1, 5);
                job.setNextRetryAt(Instant.now().plusSeconds(backoffSeconds + jitter));
                log.info("Job {} scheduled for retry {} at {} (backoff: {}s)",
                        jobId, currentRetries, job.getNextRetryAt(), backoffSeconds);
            }

            processingJobRepository.save(job);
        });
    }

    /**
     * Starts and persists an execution attempt record.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProcessingAttempt startAttempt(UUID jobId, int attemptNumber) {
        long existingAttempts = processingAttemptRepository.countByJobId(jobId);
        int actualAttempt = Math.max(attemptNumber, (int) existingAttempts + 1);
        ProcessingAttempt attempt = new ProcessingAttempt(
                UUID.randomUUID(),
                jobId,
                actualAttempt,
                Instant.now(),
                "RUNNING"
        );
        return processingAttemptRepository.save(attempt);
    }

    /**
     * Completes and persists an execution attempt record.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishAttempt(UUID attemptId, String finalStatus, long durationMs, String errorMessage) {
        processingAttemptRepository.findById(attemptId).ifPresent(attempt -> {
            attempt.complete(finalStatus, (int) durationMs, errorMessage);
            processingAttemptRepository.save(attempt);
        });
    }

    /**
     * Recovers jobs whose worker leases have expired.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverExpiredJobs(int limit) {
        Instant now = Instant.now();
        List<ProcessingJob> expired = processingJobRepository.findExpiredJobs(now, limit);

        for (ProcessingJob job : expired) {
            String prevWorker = job.getLockedBy();
            log.warn("Recovering expired lease for job {} (previously locked by {} until {})",
                    job.getId(), prevWorker, job.getLockedUntil());

            int newRetries = job.getRetryCount() + 1;
            job.setRetryCount(newRetries);
            job.setLockedBy(null);
            job.setLockedUntil(null);

            if (newRetries >= job.getMaxRetries()) {
                job.setStatus("DEAD");
                job.setCompletedAt(now);
                job.setLastError("Worker lease expired and max retries exceeded");
                if ("CONNECTED_ACCOUNT".equals(job.getTargetType())) {
                    connectedAccountRepository.findById(job.getTargetId()).ifPresent(acc -> {
                        acc.setSyncStatus("FAILED");
                        connectedAccountRepository.save(acc);
                    });
                }
            } else {
                job.setStatus("RETRY_WAIT");
                long backoff = (long) Math.pow(2, newRetries) * 5L;
                job.setNextRetryAt(now.plusSeconds(backoff));
                job.setLastError("Worker lease expired, scheduled for recovery retry");
            }

            processingJobRepository.save(job);
        }

        return expired.size();
    }
}
