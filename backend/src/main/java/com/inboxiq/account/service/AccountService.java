package com.inboxiq.account.service;

import com.inboxiq.account.dto.AccountResponse;
import com.inboxiq.account.entity.ConnectedAccount;
import com.inboxiq.account.repository.ConnectedAccountRepository;
import com.inboxiq.common.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final ConnectedAccountRepository connectedAccountRepository;
    private final TokenEncryptionService tokenEncryptionService;
    private final GoogleOAuthClient googleOAuthClient;

    public AccountService(
            ConnectedAccountRepository connectedAccountRepository,
            TokenEncryptionService tokenEncryptionService,
            GoogleOAuthClient googleOAuthClient
    ) {
        this.connectedAccountRepository = connectedAccountRepository;
        this.tokenEncryptionService = tokenEncryptionService;
        this.googleOAuthClient = googleOAuthClient;
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> listAccounts(UUID userId) {
        return connectedAccountRepository.findAllByUserIdAndIsActiveTrue(userId)
                .stream()
                .map(AccountResponse::fromEntity)
                .toList();
    }

    @Transactional
    public DisconnectResult disconnectAccount(UUID accountId, UUID userId) {
        // Enforce strict ownership: return 404 if not found or belongs to another user
        ConnectedAccount account = connectedAccountRepository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Connected account not found with ID: " + accountId));

        boolean remoteRevoked = false;
        try {
            // Decrypt token to revoke remotely at Google
            String plainTextRefreshToken = tokenEncryptionService.decrypt(
                    account.getRefreshTokenCiphertext(),
                    account.getNonceIv(),
                    account.getAuthTag(),
                    account.getEncryptionKeyVersion(),
                    account.getId(),
                    account.getUserId()
            );

            remoteRevoked = googleOAuthClient.revokeToken(plainTextRefreshToken);
        } catch (Exception e) {
            log.warn("Could not decrypt token or revoke remotely for account {}: {}", accountId, e.getMessage());
        }

        // Deactivate locally and clear sync state to halt background synchronization
        account.setActive(false);
        account.setSyncStatus("IDLE");
        connectedAccountRepository.save(account);

        log.info("Disconnected account {}. Remote revocation: {}", accountId, remoteRevoked);
        return new DisconnectResult(remoteRevoked, true);
    }

    public record DisconnectResult(boolean remoteRevoked, boolean locallyDeactivated) {}
}

