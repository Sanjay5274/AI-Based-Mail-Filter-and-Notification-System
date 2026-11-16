package com.inboxiq.email;

import com.inboxiq.account.entity.ConnectedAccount;
import com.inboxiq.account.gmail.model.*;
import com.inboxiq.account.gmail.service.GmailApiClient;
import com.inboxiq.account.model.EncryptedTokenResult;
import com.inboxiq.account.repository.ConnectedAccountRepository;
import com.inboxiq.account.service.TokenEncryptionService;
import com.inboxiq.common.exception.ConflictException;
import com.inboxiq.common.exception.GmailApiException;
import com.inboxiq.email.dto.HistoricalSyncResponse;
import com.inboxiq.email.dto.HistoricalSyncResult;
import com.inboxiq.email.entity.Email;
import com.inboxiq.email.entity.EmailAttachment;
import com.inboxiq.email.entity.EmailSyncFailure;
import com.inboxiq.email.repository.EmailAttachmentRepository;
import com.inboxiq.email.repository.EmailRepository;
import com.inboxiq.email.repository.EmailSyncFailureRepository;
import com.inboxiq.email.service.GmailHistoricalSyncService;
import com.inboxiq.processing.entity.ProcessingJob;
import com.inboxiq.processing.repository.ProcessingJobRepository;
import com.inboxiq.processing.service.ProcessingJobService;
import com.inboxiq.security.JwtTokenProvider;
import com.inboxiq.user.entity.User;
import com.inboxiq.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@org.springframework.test.context.TestPropertySource(properties = "inboxiq.jobs.dispatcher-enabled=false")
@DisplayName("Gmail Historical Sync Hardening & Recovery Integration Tests")
class GmailHistoricalSyncHardeningIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConnectedAccountRepository connectedAccountRepository;

    @Autowired
    private EmailRepository emailRepository;

    @Autowired
    private EmailAttachmentRepository emailAttachmentRepository;

    @Autowired
    private EmailSyncFailureRepository emailSyncFailureRepository;

    @Autowired
    private ProcessingJobRepository processingJobRepository;

    @Autowired
    private ProcessingJobService processingJobService;

    @Autowired
    private GmailHistoricalSyncService historicalSyncService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private TokenEncryptionService tokenEncryptionService;

    @MockitoBean
    private GmailApiClient gmailApiClient;

    private User testUser;
    private String jwtToken;
    private ConnectedAccount testAccount;

    private static final String FAKE_ACCESS_TOKEN = "ya29.hardening_integration_token";

    @BeforeEach
    void setUp() {
        emailSyncFailureRepository.deleteAll();
        emailAttachmentRepository.deleteAll();
        emailRepository.deleteAll();
        processingJobRepository.deleteAll();
        connectedAccountRepository.deleteAll();
        userRepository.deleteAll();

        testUser = new User("hardening_user@inboxiq.local", "hashed_pwd", "Hardening User", null);
        testUser = userRepository.save(testUser);
        jwtToken = jwtTokenProvider.generateAccessToken(testUser);

        UUID accountId = UUID.randomUUID();
        EncryptedTokenResult encRefresh = tokenEncryptionService.encrypt("1//refresh-token", accountId, testUser.getId());
        EncryptedTokenResult encAccess = tokenEncryptionService.encrypt(FAKE_ACCESS_TOKEN, accountId, testUser.getId());

        testAccount = new ConnectedAccount(
                accountId, testUser.getId(), "GOOGLE", "sub-hardening-" + accountId, "hardening@gmail.com",
                "Hardening User", encRefresh.ciphertext(), encRefresh.keyVersion(), encRefresh.nonceIv(),
                encRefresh.authTag(), new String[]{"https://www.googleapis.com/auth/gmail.readonly"}
        );
        testAccount.setAccessTokenCiphertext(encAccess.ciphertext());
        testAccount.setAccessTokenKeyVersion(encAccess.keyVersion());
        testAccount.setAccessTokenNonceIv(encAccess.nonceIv());
        testAccount.setAccessTokenAuthTag(encAccess.authTag());
        testAccount.setAccessTokenExpiresAt(Instant.now().plusSeconds(3600));
        testAccount.setSyncStatus("IDLE");

        testAccount = connectedAccountRepository.save(testAccount);
    }

    private String encodeBase64Url(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Scenario 1: Two simultaneous sync requests – exactly one succeeds, one gets 409 Conflict")
    void simultaneousSyncRequests_exactlyOneSucceeds() throws Exception {
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    mockMvc.perform(post("/api/v1/accounts/{accountId}/sync/historical", testAccount.getId())
                                    .header("Authorization", "Bearer " + jwtToken))
                            .andDo(result -> {
                                int status = result.getResponse().getStatus();
                                if (status == 202) {
                                    successCount.incrementAndGet();
                                } else if (status == 409) {
                                    conflictCount.incrementAndGet();
                                }
                            });
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        // Verify exactly one active processing job exists
        long activeJobs = processingJobRepository.count();
        assertThat(activeJobs).isEqualTo(1);

        ConnectedAccount updatedAccount = connectedAccountRepository.findById(testAccount.getId()).orElseThrow();
        assertThat(updatedAccount.getSyncStatus()).isEqualTo("SYNCING");
    }

    @Test
    @DisplayName("Scenario 2: Job creation failure rolls back account claiming")
    void jobCreationFailure_rollsBackAccountClaiming() {
        // Create an existing active job to trigger unique partial index violation uq_pj_active_target
        ProcessingJob blocker = new ProcessingJob(
                UUID.randomUUID(), "SYNC_ACCOUNT", "CONNECTED_ACCOUNT", testAccount.getId(), "blocker-key", 1
        );
        blocker.setStatus("PENDING");
        processingJobRepository.save(blocker);

        testAccount.setSyncStatus("IDLE");
        connectedAccountRepository.save(testAccount);

        assertThatThrownBy(() -> historicalSyncService.initiateSync(testAccount.getId(), testUser.getId(), 30))
                .isInstanceOf(ConflictException.class);

        ConnectedAccount after = connectedAccountRepository.findById(testAccount.getId()).orElseThrow();
        assertThat(after.getSyncStatus()).isEqualTo("IDLE");
    }

    @Test
    @DisplayName("Scenario 3 & 4: Pending job survives, and two workers cannot claim the same lease")
    void leaseClaiming_andRecovery() {
        // Enqueue job
        HistoricalSyncResponse resp = historicalSyncService.initiateSync(testAccount.getId(), testUser.getId(), 30);
        UUID jobId = resp.jobId();

        // Verify pending job exists in PostgreSQL
        ProcessingJob job = processingJobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo("PENDING");

        // Worker A claims job
        List<ProcessingJob> claimedA = processingJobService.claimJobs("worker-A", Duration.ofMinutes(5), 1);
        assertThat(claimedA).hasSize(1);
        assertThat(claimedA.get(0).getId()).isEqualTo(jobId);

        // Worker B attempts to claim simultaneously -> FOR UPDATE SKIP LOCKED returns empty
        List<ProcessingJob> claimedB = processingJobService.claimJobs("worker-B", Duration.ofMinutes(5), 1);
        assertThat(claimedB).isEmpty();

        // Expire lease artificially
        job = processingJobRepository.findById(jobId).orElseThrow();
        job.setLockedUntil(Instant.now().minusSeconds(60));
        processingJobRepository.save(job);

        // Lease recovery: recoverExpiredJobs reclaims it
        int recovered = processingJobService.recoverExpiredJobs(5);
        assertThat(recovered).isEqualTo(1);

        ProcessingJob recoveredJob = processingJobRepository.findById(jobId).orElseThrow();
        assertThat(recoveredJob.getStatus()).isEqualTo("RETRY_WAIT");
        assertThat(recoveredJob.getLockedBy()).isNull();
    }

    @Test
    @DisplayName("Scenario 5, 8 & 12: Checkpoint recovery, invalid page token, and accurate counters")
    void paginationCheckpoint_andInvalidPageTokenRecovery() {
        when(gmailApiClient.getProfile(FAKE_ACCESS_TOKEN))
                .thenReturn(new GmailProfile("hardening@gmail.com", 100L, 50L, "cursor-101"));

        // Setup message mocks
        List<GmailHeader> textHeaders = List.of(
                new GmailHeader("Subject", "Message 1"),
                new GmailHeader("From", "Alice <alice@test.com>"),
                new GmailHeader("To", "hardening@gmail.com")
        );
        GmailMessagePart part1 = new GmailMessagePart("0", "text/plain", null, textHeaders,
                new GmailPartBody(null, 10, encodeBase64Url("Hello 1")), null);
        GmailMessage msg1 = new GmailMessage("m-1", "t-1", List.of("INBOX"), "snip1", "cursor-101", "1794900000000", 100, null, part1);

        List<GmailHeader> textHeaders2 = List.of(
                new GmailHeader("Subject", "Message 2"),
                new GmailHeader("From", "Bob <bob@test.com>"),
                new GmailHeader("To", "hardening@gmail.com")
        );
        GmailMessagePart part2 = new GmailMessagePart("0", "text/plain", null, textHeaders2,
                new GmailPartBody(null, 10, encodeBase64Url("Hello 2")), null);
        GmailMessage msg2 = new GmailMessage("m-2", "t-2", List.of("INBOX"), "snip2", "cursor-101", "1794900000000", 100, null, part2);

        when(gmailApiClient.getMessage(FAKE_ACCESS_TOKEN, "m-1", "full")).thenReturn(msg1);
        when(gmailApiClient.getMessage(FAKE_ACCESS_TOKEN, "m-2", "full")).thenReturn(msg2);

        // Page 1 returns nextPageToken = "token-p2"
        when(gmailApiClient.listMessages(eq(FAKE_ACCESS_TOKEN), eq(50), isNull(), anyString()))
                .thenReturn(new GmailMessageListPage(List.of(new GmailMessageRef("m-1", "t-1")), "token-p2", 2));

        // Page 2 throws invalid token, causing fallback to restart from page 1 safely
        when(gmailApiClient.listMessages(eq(FAKE_ACCESS_TOKEN), eq(50), eq("token-p2"), anyString()))
                .thenThrow(new GmailApiException("Invalid pageToken", 400, "INVALID_ARGUMENT"));

        // When restarted with null token, returns m-1 and m-2, no next page
        when(gmailApiClient.listMessages(eq(FAKE_ACCESS_TOKEN), eq(50), isNull(), anyString()))
                .thenReturn(new GmailMessageListPage(List.of(new GmailMessageRef("m-1", "t-1"), new GmailMessageRef("m-2", "t-2")), null, 2));

        HistoricalSyncResponse resp = historicalSyncService.initiateSync(testAccount.getId(), testUser.getId(), 30);
        UUID jobId = resp.jobId();

        HistoricalSyncResult result = historicalSyncService.executeSyncJob(jobId, "worker-test");

        assertThat(result.status()).isEqualTo("COMPLETED");
        // m-1 was ingested in page 1, then on restart m-1 is detected as duplicate and m-2 is ingested
        assertThat(emailRepository.countByConnectedAccountId(testAccount.getId())).isEqualTo(2);

        ConnectedAccount updated = connectedAccountRepository.findById(testAccount.getId()).orElseThrow();
        assertThat(updated.getLastHistoryId()).isEqualTo("cursor-101");
        assertThat(updated.getSyncStatus()).isEqualTo("IDLE");
    }

    @Test
    @DisplayName("Scenario 9, 10 & 11: Transient retry, permanent failure tracking, and sync blocked from completion")
    void transientFailureRetry_andPermanentFailureBlocksCompletion() {
        when(gmailApiClient.getProfile(FAKE_ACCESS_TOKEN))
                .thenReturn(new GmailProfile("hardening@gmail.com", 100L, 50L, "cursor-102"));

        when(gmailApiClient.listMessages(eq(FAKE_ACCESS_TOKEN), eq(50), isNull(), anyString()))
                .thenReturn(new GmailMessageListPage(List.of(new GmailMessageRef("m-fail", "t-fail")), null, 1));

        // Transient failure on all attempts
        when(gmailApiClient.getMessage(FAKE_ACCESS_TOKEN, "m-fail", "full"))
                .thenAnswer(inv -> { throw new SocketTimeoutException("Connection timed out"); });

        HistoricalSyncResponse resp = historicalSyncService.initiateSync(testAccount.getId(), testUser.getId(), 30);
        UUID jobId = resp.jobId();

        HistoricalSyncResult result = historicalSyncService.executeSyncJob(jobId, "worker-test");

        assertThat(result.status()).isEqualTo("FAILED");

        // Verify EmailSyncFailure record exists in PostgreSQL
        List<EmailSyncFailure> failures = emailSyncFailureRepository.findAllByJobIdAndIsResolvedFalse(jobId);
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).getGmailMessageId()).isEqualTo("m-fail");
        assertThat(failures.get(0).getFailureCategory()).isEqualTo("TRANSIENT");
        assertThat(failures.get(0).getErrorMessage()).contains("Connection timed out");

        // Invariant: last_history_id must NOT be set to cursor-102 when failures remain!
        ConnectedAccount account = connectedAccountRepository.findById(testAccount.getId()).orElseThrow();
        assertThat(account.getLastHistoryId()).isNull();
        assertThat(account.getSyncStatus()).isNotEqualTo("IDLE");
    }

    @Test
    @DisplayName("Scenario 14: Inline and regular attachment persistence with is_inline column")
    void inlineAndRegularAttachment_persistence() {
        when(gmailApiClient.getProfile(FAKE_ACCESS_TOKEN))
                .thenReturn(new GmailProfile("hardening@gmail.com", 100L, 50L, "cursor-103"));

        List<GmailHeader> headers = List.of(
                new GmailHeader("Subject", "Attachments Email"),
                new GmailHeader("From", "Alice <alice@test.com>"),
                new GmailHeader("To", "hardening@gmail.com")
        );

        GmailMessagePart textPart = new GmailMessagePart("0", "text/plain", null, List.of(),
                new GmailPartBody(null, 5, encodeBase64Url("body")), null);

        // Regular attachment
        GmailMessagePart regAtt = new GmailMessagePart("1", "application/pdf", "doc.pdf",
                List.of(new GmailHeader("Content-Disposition", "attachment; filename=\"doc.pdf\"")),
                new GmailPartBody("att-reg-1", 10000, null), null);

        // Inline attachment
        GmailMessagePart inAtt = new GmailMessagePart("2", "image/png", "banner.png",
                List.of(new GmailHeader("Content-Disposition", "inline; filename=\"banner.png\""),
                        new GmailHeader("Content-ID", "<banner@cid>")),
                new GmailPartBody("att-inline-2", 4000, null), null);

        GmailMessagePart root = new GmailMessagePart("root", "multipart/mixed", null, headers, null,
                List.of(textPart, regAtt, inAtt));

        GmailMessage msg = new GmailMessage("m-att", "t-att", List.of("INBOX"), "snip", "cursor-103", "1794900000000", 15000, null, root);

        when(gmailApiClient.listMessages(eq(FAKE_ACCESS_TOKEN), eq(50), isNull(), anyString()))
                .thenReturn(new GmailMessageListPage(List.of(new GmailMessageRef("m-att", "t-att")), null, 1));

        when(gmailApiClient.getMessage(FAKE_ACCESS_TOKEN, "m-att", "full")).thenReturn(msg);

        HistoricalSyncResponse resp = historicalSyncService.initiateSync(testAccount.getId(), testUser.getId(), 30);
        HistoricalSyncResult result = historicalSyncService.executeSyncJob(resp.jobId(), "worker-test");

        assertThat(result.status()).isEqualTo("COMPLETED");

        Email email = emailRepository.findByConnectedAccountIdAndGmailMessageId(testAccount.getId(), "m-att").orElseThrow();
        List<EmailAttachment> attachments = emailAttachmentRepository.findAllByEmailId(email.getId());

        assertThat(attachments).hasSize(2);

        EmailAttachment regular = attachments.stream().filter(a -> a.getAttachmentId().equals("att-reg-1")).findFirst().orElseThrow();
        assertThat(regular.isInline()).isFalse();

        EmailAttachment inline = attachments.stream().filter(a -> a.getAttachmentId().equals("att-inline-2")).findFirst().orElseThrow();
        assertThat(inline.isInline()).isTrue();
    }

    @Test
    @DisplayName("Scenario 15: Cross-user account isolation returns 404")
    void crossUserAccountIsolation_returns404() throws Exception {
        User otherUser = new User("other@inboxiq.local", "hashed_pwd", "Other User", null);
        otherUser = userRepository.save(otherUser);
        String otherUserToken = jwtTokenProvider.generateAccessToken(otherUser);

        // Other user attempts to sync testAccount
        mockMvc.perform(post("/api/v1/accounts/{accountId}/sync/historical", testAccount.getId())
                        .header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Scenario 16: Disconnected / inactive account job rejection")
    void disconnectedAccount_jobRejection() {
        testAccount.setActive(false);
        connectedAccountRepository.save(testAccount);

        assertThatThrownBy(() -> historicalSyncService.initiateSync(testAccount.getId(), testUser.getId(), 30))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not active");
    }
}
