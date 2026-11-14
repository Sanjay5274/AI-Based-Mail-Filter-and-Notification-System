package com.inboxiq.account.model;

public record EncryptedTokenResult(
        String ciphertext,
        String nonceIv,
        String authTag,
        String keyVersion
) {
}

