package com.inboxiq.account.service;

import com.inboxiq.account.model.EncryptedTokenResult;
import com.inboxiq.common.exception.DecryptionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenEncryptionServiceTest {

    private TokenEncryptionServiceImpl encryptionService;
    private final String keyHex = "6b5970337336763979244226452948404d635166546a576e5a72347537782141"; // 256-bit key

    @BeforeEach
    void setUp() {
        encryptionService = new TokenEncryptionServiceImpl(keyHex, "v1");
    }

    @Test
    @DisplayName("Encryption & Decryption: round-trip succeeds with matching AAD")
    void testEncryptionDecryptionRoundTrip() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String plainToken = "1//04test_google_refresh_token_xyz987654321";

        EncryptedTokenResult result = encryptionService.encrypt(plainToken, accountId, userId);

        assertThat(result).isNotNull();
        assertThat(result.ciphertext()).isNotBlank();
        assertThat(result.nonceIv()).hasSize(24); // 12 bytes = 24 hex
        assertThat(result.authTag()).hasSize(32); // 16 bytes = 32 hex
        assertThat(result.keyVersion()).isEqualTo("v1");

        String decrypted = encryptionService.decrypt(
                result.ciphertext(),
                result.nonceIv(),
                result.authTag(),
                result.keyVersion(),
                accountId,
                userId
        );

        assertThat(decrypted).isEqualTo(plainToken);
    }

    @Test
    @DisplayName("Tampered Ciphertext: decryption fails with DecryptionException")
    void testTamperedCiphertextRejected() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String plainToken = "secret-token";

        EncryptedTokenResult result = encryptionService.encrypt(plainToken, accountId, userId);

        // Tamper with first byte of ciphertext
        String tamperedCiphertext = "00" + result.ciphertext().substring(2);

        assertThatThrownBy(() -> encryptionService.decrypt(
                tamperedCiphertext,
                result.nonceIv(),
                result.authTag(),
                result.keyVersion(),
                accountId,
                userId
        )).isInstanceOf(DecryptionException.class);
    }

    @Test
    @DisplayName("Tampered Auth Tag: decryption fails with DecryptionException")
    void testTamperedAuthTagRejected() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String plainToken = "secret-token";

        EncryptedTokenResult result = encryptionService.encrypt(plainToken, accountId, userId);

        String tamperedTag = "ff" + result.authTag().substring(2);

        assertThatThrownBy(() -> encryptionService.decrypt(
                result.ciphertext(),
                result.nonceIv(),
                tamperedTag,
                result.keyVersion(),
                accountId,
                userId
        )).isInstanceOf(DecryptionException.class);
    }

    @Test
    @DisplayName("Mismatched AAD: different accountId causes decryption failure")
    void testMismatchedAccountIdRejected() {
        UUID accountId = UUID.randomUUID();
        UUID differentAccountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String plainToken = "secret-token";

        EncryptedTokenResult result = encryptionService.encrypt(plainToken, accountId, userId);

        assertThatThrownBy(() -> encryptionService.decrypt(
                result.ciphertext(),
                result.nonceIv(),
                result.authTag(),
                result.keyVersion(),
                differentAccountId,
                userId
        )).isInstanceOf(DecryptionException.class);
    }

    @Test
    @DisplayName("Mismatched AAD: different userId causes decryption failure")
    void testMismatchedUserIdRejected() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID differentUserId = UUID.randomUUID();
        String plainToken = "secret-token";

        EncryptedTokenResult result = encryptionService.encrypt(plainToken, accountId, userId);

        assertThatThrownBy(() -> encryptionService.decrypt(
                result.ciphertext(),
                result.nonceIv(),
                result.authTag(),
                result.keyVersion(),
                accountId,
                differentUserId
        )).isInstanceOf(DecryptionException.class);
    }

    @Test
    @DisplayName("Mismatched Key Version: unknown key version throws DecryptionException")
    void testUnknownKeyVersionRejected() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        EncryptedTokenResult result = encryptionService.encrypt("token", accountId, userId);

        assertThatThrownBy(() -> encryptionService.decrypt(
                result.ciphertext(),
                result.nonceIv(),
                result.authTag(),
                "v99",
                accountId,
                userId
        )).isInstanceOf(DecryptionException.class);
    }

    @Test
    @DisplayName("Key Rotation: decrypting with past key version after registering new key")
    void testKeyRotationSupport() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // Encrypt with v1
        EncryptedTokenResult v1Result = encryptionService.encrypt("my-token", accountId, userId);

        // Register v2 key in key ring
        String keyV2Hex = "70337336763979244226452948404d635166546a576e5a723475377821412544";
        encryptionService.registerKeyVersion("v2", keyV2Hex);

        // Decrypt v1 record using v1 key from ring
        String decryptedV1 = encryptionService.decrypt(
                v1Result.ciphertext(),
                v1Result.nonceIv(),
                v1Result.authTag(),
                "v1",
                accountId,
                userId
        );
        assertThat(decryptedV1).isEqualTo("my-token");
    }
}

