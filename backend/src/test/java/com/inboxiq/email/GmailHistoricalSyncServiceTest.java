package com.inboxiq.email;

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
import com.inboxiq.email.parser.GmailMimeParser;
import com.inboxiq.email.parser.ParsedAttachment;
import com.inboxiq.email.parser.ParsedEmail;
import com.inboxiq.email.repository.EmailAttachmentRepository;
import com.inboxiq.email.repository.EmailRepository;
import com.inboxiq.email.repository.EmailSyncFailureRepository;
import com.inboxiq.email.service.EmailPersistenceService;
import com.inboxiq.email.service.GmailHistoricalSyncService;
import com.inboxiq.processing.entity.ProcessingAttempt;
import com.inboxiq.processing.entity.ProcessingJob;
import com.inboxiq.processing.repository.ProcessingJobRepository;
import com.inboxiq.processing.service.ProcessingJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("GmailHistoricalSyncService Unit Tests")
class GmailHistoricalSyncServiceTest {

    @Mock
    private ConnectedAccountRepository connectedAccountRepository;

    @Mock
    private EmailRepository emailRepository;

    @Mock
    private EmailAttachmentRepository emailAttachmentRepository;

    @Mock
    private EmailSyncFailureRepository emailSyncFailureRepository;

    @Mock
    private EmailPersistenceService emailPersistenceService;

    @Mock
    private ProcessingJobRepository processingJobRepository;

    @Mock
    private ProcessingJobService processingJobService;

    @Mock
    private GmailApiClient gmailApiClient;

    @Mock
    private GmailService gmailService;

    @Mock
    private GmailMimeParser gmailMimeParser;

    private GmailHistoricalSyncService syncService;

    private UUID userId;
    private UUID accountId;
    private ConnectedAccount activeAccount;

    private static final String FAKE_ACCESS_TOKEN = "ya29.fake-token";

    @BeforeEach
    void setUp() {
        syncService = new GmailHistoricalSyncService(
                connectedAccountRepository,
                emailRepository,
                emailAttachmentRepository,
                emailSyncFailureRepository,
                emailPersistenceService,
                processingJobRepository,
                processingJobService,
                gmailApiClient,
                gmailService,
                gmailMimeParser,
                30,
                365,
                50
        );

        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();

        activeAccount = new ConnectedAccount(
                accountId, userId, "GOOGLE", "sub-123", "sync_user@gmail.com", "Sync User",
                "ciphertext", "v1", "nonce", "tag", new String[]{"gmail.readonly"}
        );
        activeAccount.setActive(true);
        activeAccount.setSyncStatus("IDLE");
    }

    @Test
    @DisplayName("initiateSync: atomically claims account and persists durable job")
    void initiateSync_success() {
        when(connectedAccountRepository.findByIdAndUserIdForUpdate(accountId, userId)).thenReturn(Optional.of(activeAccount));
        when(processingJobRepository.existsByTargetTypeAndTargetIdAndStatusIn(eq("CONNECTED_ACCOUNT"), eq(accountId), anyList()))
                .thenReturn(false);

        HistoricalSyncResponse response = syncService.initiateSync(accountId, userId, 14);

        assertThat(response).isNotNull();
        assertThat(response.accountId()).isEqualTo(accountId);
        assertThat(response.status()).isEqualTo("SYNCING");
        assertThat(response.lookbackDays()).isEqualTo(14);
        assertThat(activeAccount.getSyncStatus()).isEqualTo("SYNCING");

        verify(connectedAccountRepository).save(activeAccount);
        verify(processingJobRepository).save(any(ProcessingJob.class));
    }

    @Test
    @DisplayName("initiateSync: throws ConflictException when account is already SYNCING or active job exists")
    void initiateSync_conflict() {
        activeAccount.setSyncStatus("SYNCING");
        when(connectedAccountRepository.findByIdAndUserIdForUpdate(accountId, userId)).thenReturn(Optional.of(activeAccount));

        assertThatThrownBy(() -> syncService.initiateSync(accountId, userId, 30))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Synchronization is already in progress");

        verify(processingJobRepository, never()).save(any());
    }

    @Test
    @DisplayName("executeSyncJob: ingests messages, commits checkpoints, and completes with baseline cursor")
    void executeSyncJob_success() {
        UUID jobId = UUID.randomUUID();
        ProcessingJob job = new ProcessingJob(jobId, "SYNC_ACCOUNT", "CONNECTED_ACCOUNT", accountId, "key-1", 1);
        job.setStatus("PROCESSING");
        job.setLookbackDays(30);

        when(processingJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(connectedAccountRepository.findById(accountId)).thenReturn(Optional.of(activeAccount));
        when(gmailService.resolveValidAccessToken(activeAccount)).thenReturn(FAKE_ACCESS_TOKEN);

        ProcessingAttempt attempt = new ProcessingAttempt(UUID.randomUUID(), jobId, 1, Instant.now(), "RUNNING");
        when(processingJobService.startAttempt(eq(jobId), anyInt())).thenReturn(attempt);

        // Baseline profile with historyId 999888
        when(gmailApiClient.getProfile(FAKE_ACCESS_TOKEN))
                .thenReturn(new GmailProfile("sync_user@gmail.com", 200L, 100L, "999888"));

        // Single page of 2 messages
        List<GmailMessageRef> messageRefs = List.of(
                new GmailMessageRef("msg-001", "t-001"),
                new GmailMessageRef("msg-002", "t-002")
        );
        when(gmailApiClient.listMessages(eq(FAKE_ACCESS_TOKEN), eq(50), isNull(), startsWith("after:")))
                .thenReturn(new GmailMessageListPage(messageRefs, null, 2));

        when(emailRepository.existsByConnectedAccountIdAndGmailMessageId(accountId, "msg-001")).thenReturn(false);
        when(emailRepository.existsByConnectedAccountIdAndGmailMessageId(accountId, "msg-002")).thenReturn(false);

        GmailMessage rawMsg1 = mock(GmailMessage.class);
        GmailMessage rawMsg2 = mock(GmailMessage.class);
        when(gmailApiClient.getMessage(FAKE_ACCESS_TOKEN, "msg-001", "full")).thenReturn(rawMsg1);
        when(gmailApiClient.getMessage(FAKE_ACCESS_TOKEN, "msg-002", "full")).thenReturn(rawMsg2);

        ParsedEmail parsed1 = new ParsedEmail(
                "msg-001", "t-001", "h-1", "Subject 1", "Sender 1", "sender1@test.com",
                List.of("to@test.com"), Instant.now(), "Body 1", null, "Snippet 1",
                List.of("INBOX"), true, 1000, false, List.of()
        );
        ParsedEmail parsed2 = new ParsedEmail(
                "msg-002", "t-002", "h-2", "Subject 2", "Sender 2", "sender2@test.com",
                List.of("to@test.com"), Instant.now(), "Body 2", null, "Snippet 2",
                List.of("INBOX"), true, 2000, true,
                List.of(new ParsedAttachment("att-1", "doc.pdf", "application/pdf", 1024, false))
        );
        when(gmailMimeParser.parse(rawMsg1)).thenReturn(parsed1);
        when(gmailMimeParser.parse(rawMsg2)).thenReturn(parsed2);
        when(emailPersistenceService.saveEmailWithAttachments(eq(activeAccount), any())).thenReturn(true);

        when(emailSyncFailureRepository.countByJobIdAndIsResolvedFalse(jobId)).thenReturn(0L);

        HistoricalSyncResult result = syncService.executeSyncJob(jobId, "worker-1");

        assertThat(result.totalFound()).isEqualTo(2);
        assertThat(result.processedCount()).isEqualTo(2);
        assertThat(result.duplicateCount()).isEqualTo(0);
        assertThat(result.failedCount()).isEqualTo(0);
        assertThat(result.initialHistoryId()).isEqualTo("999888");
        assertThat(result.status()).isEqualTo("COMPLETED");

        assertThat(activeAccount.getLastHistoryId()).isEqualTo("999888");
        assertThat(activeAccount.getSyncStatus()).isEqualTo("IDLE");

        verify(processingJobService).recordInitialHistoryId(jobId, "999888");
        verify(processingJobService).completeJob(jobId);
        verify(processingJobService).finishAttempt(eq(attempt.getId()), eq("COMPLETED"), anyLong(), isNull());
    }

    @Test
    @DisplayName("executeSyncJob: recovers safely from invalid page token and restarts from beginning")
    void executeSyncJob_invalidPageTokenRecovery() {
        UUID jobId = UUID.randomUUID();
        ProcessingJob job = new ProcessingJob(jobId, "SYNC_ACCOUNT", "CONNECTED_ACCOUNT", accountId, "key-2", 1);
        job.setStatus("PROCESSING");
        job.setPaginationToken("stale-token-xyz");
        job.setInitialHistoryId("hist-cursor-101");

        when(processingJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(connectedAccountRepository.findById(accountId)).thenReturn(Optional.of(activeAccount));
        when(gmailService.resolveValidAccessToken(activeAccount)).thenReturn(FAKE_ACCESS_TOKEN);

        ProcessingAttempt attempt = new ProcessingAttempt(UUID.randomUUID(), jobId, 1, Instant.now(), "RUNNING");
        when(processingJobService.startAttempt(eq(jobId), anyInt())).thenReturn(attempt);

        // Stale token triggers GmailApiException 400
        when(gmailApiClient.listMessages(eq(FAKE_ACCESS_TOKEN), eq(50), eq("stale-token-xyz"), anyString()))
                .thenThrow(new GmailApiException("Invalid pageToken", 400, "INVALID_ARGUMENT"));

        // Recovery query from beginning (null token)
        when(gmailApiClient.listMessages(eq(FAKE_ACCESS_TOKEN), eq(50), isNull(), anyString()))
                .thenReturn(new GmailMessageListPage(List.of(), null, 0));

        when(emailSyncFailureRepository.countByJobIdAndIsResolvedFalse(jobId)).thenReturn(0L);

        HistoricalSyncResult result = syncService.executeSyncJob(jobId, "worker-1");

        assertThat(result.status()).isEqualTo("COMPLETED");
        verify(processingJobService).updatePaginationCheckpoint(eq(jobId), isNull(), eq(0), eq(0), eq(0), eq(0), eq("worker-1"), any());
    }

    @Test
    @DisplayName("executeSyncJob: retries transient message failures with backoff and tracks unresolved permanent failures")
    void executeSyncJob_transientRetry_andPermanentFailureTracking() {
        UUID jobId = UUID.randomUUID();
        ProcessingJob job = new ProcessingJob(jobId, "SYNC_ACCOUNT", "CONNECTED_ACCOUNT", accountId, "key-3", 1);
        job.setStatus("PROCESSING");
        activeAccount.setSyncStatus("SYNCING");

        when(processingJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(connectedAccountRepository.findById(accountId)).thenReturn(Optional.of(activeAccount));
        when(gmailService.resolveValidAccessToken(activeAccount)).thenReturn(FAKE_ACCESS_TOKEN);

        ProcessingAttempt attempt = new ProcessingAttempt(UUID.randomUUID(), jobId, 1, Instant.now(), "RUNNING");
        when(processingJobService.startAttempt(eq(jobId), anyInt())).thenReturn(attempt);
        when(gmailApiClient.getProfile(FAKE_ACCESS_TOKEN)).thenReturn(new GmailProfile("user@gmail.com", 1L, 1L, "hist-1"));

        when(gmailApiClient.listMessages(eq(FAKE_ACCESS_TOKEN), eq(50), isNull(), anyString()))
                .thenReturn(new GmailMessageListPage(List.of(new GmailMessageRef("msg-timeout", "t-1")), null, 1));

        when(emailRepository.existsByConnectedAccountIdAndGmailMessageId(accountId, "msg-timeout")).thenReturn(false);

        // Message fetch throws SocketTimeoutException on all 3 attempts
        when(gmailApiClient.getMessage(FAKE_ACCESS_TOKEN, "msg-timeout", "full"))
                .thenAnswer(inv -> { throw new SocketTimeoutException("Read timed out"); });

        // Unresolved failure remains
        when(emailSyncFailureRepository.countByJobIdAndIsResolvedFalse(jobId)).thenReturn(1L);

        HistoricalSyncResult result = syncService.executeSyncJob(jobId, "worker-1");

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.failedCount()).isEqualTo(1);

        // Crucial invariant: baseline cursor is NOT advanced to IDLE when failures remain!
        assertThat(activeAccount.getSyncStatus()).isNotEqualTo("IDLE");
        verify(processingJobService).failJob(eq(jobId), contains("unresolved message failures"), eq(false));
    }

    @Test
    @DisplayName("executeSyncJob: rejects inactive account and transitions job to DEAD")
    void executeSyncJob_inactiveAccount() {
        UUID jobId = UUID.randomUUID();
        ProcessingJob job = new ProcessingJob(jobId, "SYNC_ACCOUNT", "CONNECTED_ACCOUNT", accountId, "key-4", 1);

        activeAccount.setActive(false);
        when(processingJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(connectedAccountRepository.findById(accountId)).thenReturn(Optional.of(activeAccount));

        ProcessingAttempt attempt = new ProcessingAttempt(UUID.randomUUID(), jobId, 1, Instant.now(), "RUNNING");
        when(processingJobService.startAttempt(eq(jobId), anyInt())).thenReturn(attempt);

        HistoricalSyncResult result = syncService.executeSyncJob(jobId, "worker-1");

        assertThat(result).isNull();
        verify(processingJobService).failJob(eq(jobId), contains("inactive"), eq(true));
    }

    @Test
    @DisplayName("executeSyncJob: handles revoked Google credentials by deactivating account")
    void executeSyncJob_credentialsRevoked() {
        UUID jobId = UUID.randomUUID();
        ProcessingJob job = new ProcessingJob(jobId, "SYNC_ACCOUNT", "CONNECTED_ACCOUNT", accountId, "key-5", 1);

        when(processingJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(connectedAccountRepository.findById(accountId)).thenReturn(Optional.of(activeAccount));
        when(gmailService.resolveValidAccessToken(activeAccount))
                .thenThrow(new GoogleCredentialsRevokedException("Refresh token revoked"));

        ProcessingAttempt attempt = new ProcessingAttempt(UUID.randomUUID(), jobId, 1, Instant.now(), "RUNNING");
        when(processingJobService.startAttempt(eq(jobId), anyInt())).thenReturn(attempt);

        HistoricalSyncResult result = syncService.executeSyncJob(jobId, "worker-1");

        assertThat(result).isNull();
        assertThat(activeAccount.isActive()).isFalse();
        assertThat(activeAccount.getSyncStatus()).isEqualTo("FAILED");
        verify(connectedAccountRepository).save(activeAccount);
        verify(processingJobService).failJob(eq(jobId), contains("revoked"), eq(true));
    }
}
