package com.inboxiq.account.service;

import com.inboxiq.account.model.EncryptedTokenResult;
import com.inboxiq.common.exception.DecryptionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TokenEncryptionServiceImpl implements TokenEncryptionService {

    private static final Logger log = LoggerFactory.getLogger(TokenEncryptionServiceImpl.class);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8; // 16 bytes
    private static final int GCM_IV_LENGTH_BYTES = 12; // 96 bits

    private final SecureRandom secureRandom = new SecureRandom();
    private final String currentKeyVersion;
    private final Map<String, SecretKey> keyRing = new ConcurrentHashMap<>();

    public TokenEncryptionServiceImpl(
            @Value("${inboxiq.encryption.token-key}") String tokenKey,
            @Value("${inboxiq.encryption.current-version:v1}") String currentKeyVersion
    ) {
        this.currentKeyVersion = currentKeyVersion;
        SecretKey secretKey = parseKey(tokenKey);
        this.keyRing.put(currentKeyVersion, secretKey);
    }

    public void registerKeyVersion(String version, String keyHexOrRaw) {
        this.keyRing.put(version, parseKey(keyHexOrRaw));
    }

    private SecretKey parseKey(String keyString) {
        if (keyString == null || keyString.trim().isEmpty()) {
            throw new IllegalArgumentException("Token encryption key must not be blank");
        }

        byte[] keyBytes;
        String trimmed = keyString.trim();
        if (trimmed.length() == 64 && trimmed.matches("^[0-9a-fA-F]+$")) {
            keyBytes = HexFormat.of().parseHex(trimmed);
        } else {
            keyBytes = trimmed.getBytes(StandardCharsets.UTF_8);
        }

        if (keyBytes.length != 32) {
            throw new IllegalArgumentException("AES-256 requires exactly 256 bits (32 bytes) key. Provided: " + keyBytes.length + " bytes");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }

    @Override
    public EncryptedTokenResult encrypt(String plainTextToken, UUID accountId, UUID userId) {
        if (plainTextToken == null || plainTextToken.isEmpty()) {
            throw new IllegalArgumentException("Plaintext token must not be empty");
        }

        SecretKey key = keyRing.get(currentKeyVersion);
        if (key == null) {
            throw new IllegalStateException("Current encryption key version not loaded: " + currentKeyVersion);
        }

        byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);

        byte[] aad = buildAad(accountId, userId, currentKeyVersion);

        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec);
            cipher.updateAAD(aad);

            byte[] cipherWithTag = cipher.doFinal(plainTextToken.getBytes(StandardCharsets.UTF_8));

            int cipherLength = cipherWithTag.length - GCM_TAG_LENGTH_BYTES;
            byte[] ciphertext = new byte[cipherLength];
            byte[] authTag = new byte[GCM_TAG_LENGTH_BYTES];

            System.arraycopy(cipherWithTag, 0, ciphertext, 0, cipherLength);
            System.arraycopy(cipherWithTag, cipherLength, authTag, 0, GCM_TAG_LENGTH_BYTES);

            return new EncryptedTokenResult(
                    HexFormat.of().formatHex(ciphertext),
                    HexFormat.of().formatHex(iv),
                    HexFormat.of().formatHex(authTag),
                    currentKeyVersion
            );
        } catch (GeneralSecurityException e) {
            log.error("Failed to encrypt token for accountId: {}, userId: {}", accountId, userId);
            throw new IllegalStateException("Encryption failure", e);
        }
    }

    @Override
    public String decrypt(String ciphertextHex, String nonceIvHex, String authTagHex, String keyVersion, UUID accountId, UUID userId) {
        SecretKey key = keyRing.get(keyVersion);
        if (key == null) {
            throw new DecryptionException("No encryption key found for version: " + keyVersion);
        }

        try {
            byte[] ciphertext = HexFormat.of().parseHex(ciphertextHex);
            byte[] iv = HexFormat.of().parseHex(nonceIvHex);
            byte[] authTag = HexFormat.of().parseHex(authTagHex);

            byte[] cipherWithTag = new byte[ciphertext.length + authTag.length];
            System.arraycopy(ciphertext, 0, cipherWithTag, 0, ciphertext.length);
            System.arraycopy(authTag, 0, cipherWithTag, ciphertext.length, authTag.length);

            byte[] aad = buildAad(accountId, userId, keyVersion);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec);
            cipher.updateAAD(aad);

            byte[] decrypted = cipher.doFinal(cipherWithTag);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Decryption integrity verification failed for accountId: {}, userId: {}", accountId, userId);
            throw new DecryptionException("Decryption failed: integrity verification or AAD mismatch", e);
        }
    }

    private byte[] buildAad(UUID accountId, UUID userId, String keyVersion) {
        String aadStr = accountId.toString() + ":" + userId.toString() + ":" + keyVersion;
        return aadStr.getBytes(StandardCharsets.UTF_8);
    }
}

