package com.inboxiq.email;

import com.inboxiq.account.entity.ConnectedAccount;
import com.inboxiq.account.gmail.model.*;
import com.inboxiq.account.gmail.service.GmailApiClient;
import com.inboxiq.account.model.EncryptedTokenResult;
import com.inboxiq.account.repository.ConnectedAccountRepository;
import com.inboxiq.account.service.TokenEncryptionService;
import com.inboxiq.email.entity.Email;
import com.inboxiq.email.entity.EmailAttachment;
import com.inboxiq.email.repository.EmailAttachmentRepository;
import com.inboxiq.email.repository.EmailRepository;
import com.inboxiq.email.repository.EmailSyncFailureRepository;
import com.inboxiq.email.service.GmailHistoricalSyncService;
import com.inboxiq.processing.entity.ProcessingJob;
import com.inboxiq.processing.repository.ProcessingJobRepository;
import com.inboxiq.security.JwtTokenProvider;
import com.inboxiq.user.entity.User;
import com.inboxiq.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@org.springframework.test.context.TestPropertySource(properties = "inboxiq.jobs.dispatcher-enabled=false")
@DisplayName("Gmail Sync Controller & Persistence Integration Tests")
class GmailSyncControllerIntegrationTest {

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
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private TokenEncryptionService tokenEncryptionService;

    @Autowired
    private GmailHistoricalSyncService historicalSyncService;

    @MockitoBean
    private GmailApiClient gmailApiClient;

    private User testUser;
    private String jwtToken;
    private ConnectedAccount testAccount;

    private static final String FAKE_ACCESS_TOKEN = "ya29.sync_integration_token";

    @BeforeEach
    void setUp() {
        cleanDatabase();

        testUser = new User("sync_tester@inboxiq.local", "hashed_pwd", "Sync Tester", null);
        testUser = userRepository.save(testUser);
        jwtToken = jwtTokenProvider.generateAccessToken(testUser);

        UUID accountId = UUID.randomUUID();
        EncryptedTokenResult encRefresh = tokenEncryptionService.encrypt("1//refresh-token", accountId, testUser.getId());
        EncryptedTokenResult encAccess = tokenEncryptionService.encrypt(FAKE_ACCESS_TOKEN, accountId, testUser.getId());

        testAccount = new ConnectedAccount(
                accountId, testUser.getId(), "GOOGLE", "sub-sync-integration", "sync_tester@gmail.com",
                "Sync Tester", encRefresh.ciphertext(), encRefresh.keyVersion(), encRefresh.nonceIv(),
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

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    private void cleanDatabase() {
        emailSyncFailureRepository.deleteAll();
        emailAttachmentRepository.deleteAll();
        emailRepository.deleteAll();
        processingJobRepository.deleteAll();
        connectedAccountRepository.deleteAll();
        userRepository.deleteAll();
    }

    private String encodeBase64Url(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("POST /sync/historical: initiates sync asynchronously and returns 202 Accepted")
    void initiateSync_success() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{accountId}/sync/historical", testAccount.getId())
                        .header("Authorization", "Bearer " + jwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lookbackDays\": 15}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value(202))
                .andExpect(jsonPath("$.data.accountId").value(testAccount.getId().toString()))
                .andExpect(jsonPath("$.data.status").value("SYNCING"))
                .andExpect(jsonPath("$.data.lookbackDays").value(15))
                .andExpect(jsonPath("$.data.jobId").isNotEmpty());

        ConnectedAccount updated = connectedAccountRepository.findById(testAccount.getId()).orElseThrow();
        assertThat(updated.getSyncStatus()).isEqualTo("SYNCING");

        // Verify ProcessingJob record was created
        List<ProcessingJob> jobs = processingJobRepository.findAll();
        assertThat(jobs).anyMatch(j -> j.getTargetId().equals(testAccount.getId()) && j.getJobType().equals("SYNC_ACCOUNT"));
    }

    @Test
    @DisplayName("POST /sync/historical: rejects concurrent sync when account is already SYNCING with 409 Conflict")
    void initiateSync_alreadySyncing_returns409() throws Exception {
        testAccount.setSyncStatus("SYNCING");
        connectedAccountRepository.save(testAccount);

        mockMvc.perform(post("/api/v1/accounts/{accountId}/sync/historical", testAccount.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("RESOURCE_CONFLICT"));
    }

    @Test
    @DisplayName("POST /sync/historical: cross-user account access returns 404 Not Found")
    void initiateSync_crossUserAccess_returns404() throws Exception {
        User otherUser = new User("other_sync@inboxiq.local", "hash", "Other", null);
        otherUser = userRepository.save(otherUser);

        ConnectedAccount otherAccount = new ConnectedAccount(
                otherUser.getId(), "GOOGLE", "sub-other-sync", "other@gmail.com", "Other",
                "c", "v1", "n", "t", new String[]{"gmail.readonly"}
        );
        otherAccount = connectedAccountRepository.save(otherAccount);

        mockMvc.perform(post("/api/v1/accounts/{accountId}/sync/historical", otherAccount.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /sync/status: returns current synchronization status and email count")
    void getSyncStatus_returnsStatus() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}/sync/status", testAccount.getId())
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountId").value(testAccount.getId().toString()))
                .andExpect(jsonPath("$.data.syncStatus").value("IDLE"))
                .andExpect(jsonPath("$.data.totalEmailsSynced").value(0));
    }

    @Test
    @DisplayName("End-to-End Persistence: executeSync persists emails and attachments with PostgreSQL constraints")
    void executeSync_persistsEmailsAndAttachmentsToPostgres() {
        when(gmailApiClient.getProfile(FAKE_ACCESS_TOKEN))
                .thenReturn(new GmailProfile("sync_tester@gmail.com", 100L, 50L, "history-cursor-101"));

        when(gmailApiClient.listMessages(eq(FAKE_ACCESS_TOKEN), anyInt(), isNull(), anyString()))
                .thenReturn(new GmailMessageListPage(List.of(new GmailMessageRef("gmail-msg-001", "gmail-thread-001")), null, 1));

        List<GmailHeader> headers = List.of(
                new GmailHeader("Subject", "Welcome to InboxIQ"),
                new GmailHeader("From", "Team InboxIQ <welcome@inboxiq.com>"),
                new GmailHeader("To", "sync_tester@gmail.com"),
                new GmailHeader("Date", "Tue, 17 Nov 2026 08:00:00 +0000")
        );

        GmailMessagePart textPart = new GmailMessagePart(
                "0.0", "text/plain", null, List.of(),
                new GmailPartBody(null, 30, encodeBase64Url("Welcome to your new inbox intelligence.")), null
        );

        GmailMessagePart attPart = new GmailMessagePart(
                "0.1", "application/pdf", "guide.pdf",
                List.of(new GmailHeader("Content-Disposition", "attachment; filename=\"guide.pdf\"")),
                new GmailPartBody("att-id-001", 20480, null), null
        );

        GmailMessagePart payload = new GmailMessagePart(
                "0", "multipart/mixed", null, headers, null, List.of(textPart, attPart)
        );

        GmailMessage fullMessage = new GmailMessage(
                "gmail-msg-001", "gmail-thread-001", List.of("INBOX"), "Welcome snippet",
                "history-cursor-101", "1794900000000", 25000, null, payload
        );

        when(gmailApiClient.getMessage(FAKE_ACCESS_TOKEN, "gmail-msg-001", "full")).thenReturn(fullMessage);

        // Execute sync
        var result = historicalSyncService.executeSync(null, testAccount.getId(), testUser.getId(), 30);

        assertThat(result.processedCount()).isEqualTo(1);
        assertThat(result.initialHistoryId()).isEqualTo("history-cursor-101");

        // Verify PostgreSQL persistence
        Email savedEmail = emailRepository.findByConnectedAccountIdAndGmailMessageId(testAccount.getId(), "gmail-msg-001")
                .orElseThrow();

        assertThat(savedEmail.getSubject()).isEqualTo("Welcome to InboxIQ");
        assertThat(savedEmail.getSenderName()).isEqualTo("Team InboxIQ");
        assertThat(savedEmail.getSenderEmail()).isEqualTo("welcome@inboxiq.com");
        assertThat(savedEmail.getBodyText()).isEqualTo("Welcome to your new inbox intelligence.");
        assertThat(savedEmail.isHasAttachments()).isTrue();
        assertThat(savedEmail.getRecipientEmails()).containsExactly("sync_tester@gmail.com");

        // Verify attachments persisted in email_attachments
        List<EmailAttachment> attachments = emailAttachmentRepository.findAllByEmailId(savedEmail.getId());
        assertThat(attachments).hasSize(1);
        assertThat(attachments.get(0).getAttachmentId()).isEqualTo("att-id-001");
        assertThat(attachments.get(0).getFilename()).isEqualTo("guide.pdf");
        assertThat(attachments.get(0).getMimeType()).isEqualTo("application/pdf");
        assertThat(attachments.get(0).getSizeBytes()).isEqualTo(20480);

        // Verify connected_account state updated
        ConnectedAccount updatedAccount = connectedAccountRepository.findById(testAccount.getId()).orElseThrow();
        assertThat(updatedAccount.getLastHistoryId()).isEqualTo("history-cursor-101");
        assertThat(updatedAccount.getSyncStatus()).isEqualTo("IDLE");
        assertThat(updatedAccount.getLastSyncAt()).isNotNull();

        // Verify idempotency: re-running executeSync must not create duplicate emails
        var rerunResult = historicalSyncService.executeSync(null, testAccount.getId(), testUser.getId(), 30);
        assertThat(rerunResult.duplicateCount()).isEqualTo(1);
        assertThat(rerunResult.processedCount()).isEqualTo(0);
        assertThat(emailRepository.countByConnectedAccountId(testAccount.getId())).isEqualTo(1);
    }
}
