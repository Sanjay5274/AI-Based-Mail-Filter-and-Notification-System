package com.inboxiq.account.service;

import com.inboxiq.account.model.EncryptedTokenResult;

import java.util.UUID;

public interface TokenEncryptionService {

    /**
     * Encrypts plaintext refresh token using AES-256-GCM with a random 96-bit IV.
     * Binds AAD (accountId + ":" + userId + ":" + keyVersion) to prevent token transplantation.
     */
    EncryptedTokenResult encrypt(String plainTextToken, UUID accountId, UUID userId);

    /**
     * Decrypts ciphertext verifying the 128-bit authentication tag and matching AAD.
     */
    String decrypt(String ciphertext, String nonceIv, String authTag, String keyVersion, UUID accountId, UUID userId);
}

