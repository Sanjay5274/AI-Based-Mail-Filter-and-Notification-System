package com.inboxiq.account.entity;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "connected_accounts")
public class ConnectedAccount implements Persistable<UUID> {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Transient
    private boolean isNewEntity = true;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "provider", nullable = false, length = 50)
    private String provider = "GOOGLE";

    @Column(name = "provider_account_id", nullable = false)
    private String providerAccountId;

    @Column(name = "email_address", nullable = false)
    private String emailAddress;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "refresh_token_ciphertext", nullable = false, columnDefinition = "TEXT")
    private String refreshTokenCiphertext;

    @Column(name = "encryption_key_version", nullable = false, length = 50)
    private String encryptionKeyVersion = "v1";

    @Column(name = "nonce_iv", nullable = false, length = 64)
    private String nonceIv;

    @Column(name = "auth_tag", nullable = false, length = 64)
    private String authTag;

    @Column(name = "scopes", columnDefinition = "text[]")
    private String[] scopes = new String[]{"https://www.googleapis.com/auth/gmail.readonly"};

    @Column(name = "sync_status", nullable = false, length = 50)
    private String syncStatus = "IDLE";

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(name = "last_history_id", length = 100)
    private String lastHistoryId;

    // --- Encrypted access token (nullable: populated on first Gmail API use / refresh) ---
    @Column(name = "access_token_ciphertext", columnDefinition = "TEXT")
    private String accessTokenCiphertext;

    @Column(name = "access_token_key_version", length = 50)
    private String accessTokenKeyVersion;

    @Column(name = "access_token_nonce_iv", length = 64)
    private String accessTokenNonceIv;

    @Column(name = "access_token_auth_tag", length = 64)
    private String accessTokenAuthTag;

    @Column(name = "access_token_expires_at")
    private Instant accessTokenExpiresAt;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ConnectedAccount() {
        this.id = UUID.randomUUID();
    }

    public ConnectedAccount(
            UUID id,
            UUID userId,
            String provider,
            String providerAccountId,
            String emailAddress,
            String displayName,
            String refreshTokenCiphertext,
            String encryptionKeyVersion,
            String nonceIv,
            String authTag,
            String[] scopes
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.userId = userId;
        this.provider = provider;
        this.providerAccountId = providerAccountId;
        this.emailAddress = emailAddress;
        this.displayName = displayName;
        this.refreshTokenCiphertext = refreshTokenCiphertext;
        this.encryptionKeyVersion = encryptionKeyVersion;
        this.nonceIv = nonceIv;
        this.authTag = authTag;
        this.scopes = scopes != null ? scopes : new String[]{"https://www.googleapis.com/auth/gmail.readonly"};
        this.syncStatus = "IDLE";
        this.isActive = true;
    }

    public ConnectedAccount(
            UUID userId,
            String provider,
            String providerAccountId,
            String emailAddress,
            String displayName,
            String refreshTokenCiphertext,
            String encryptionKeyVersion,
            String nonceIv,
            String authTag,
            String[] scopes
    ) {
        this(UUID.randomUUID(), userId, provider, providerAccountId, emailAddress, displayName,
                refreshTokenCiphertext, encryptionKeyVersion, nonceIv, authTag, scopes);
    }

    @Override
    public boolean isNew() {
        return isNewEntity;
    }

    @PostPersist
    @PostLoad
    protected void markNotNew() {
        this.isNewEntity = false;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getProviderAccountId() {
        return providerAccountId;
    }

    public void setProviderAccountId(String providerAccountId) {
        this.providerAccountId = providerAccountId;
    }

    public String getEmailAddress() {
        return emailAddress;
    }

    public void setEmailAddress(String emailAddress) {
        this.emailAddress = emailAddress;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getRefreshTokenCiphertext() {
        return refreshTokenCiphertext;
    }

    public void setRefreshTokenCiphertext(String refreshTokenCiphertext) {
        this.refreshTokenCiphertext = refreshTokenCiphertext;
    }

    public String getEncryptionKeyVersion() {
        return encryptionKeyVersion;
    }

    public void setEncryptionKeyVersion(String encryptionKeyVersion) {
        this.encryptionKeyVersion = encryptionKeyVersion;
    }

    public String getNonceIv() {
        return nonceIv;
    }

    public void setNonceIv(String nonceIv) {
        this.nonceIv = nonceIv;
    }

    public String getAuthTag() {
        return authTag;
    }

    public void setAuthTag(String authTag) {
        this.authTag = authTag;
    }

    public String[] getScopes() {
        return scopes;
    }

    public void setScopes(String[] scopes) {
        this.scopes = scopes;
    }

    public String getSyncStatus() {
        return syncStatus;
    }

    public void setSyncStatus(String syncStatus) {
        this.syncStatus = syncStatus;
    }

    public Instant getLastSyncAt() {
        return lastSyncAt;
    }

    public void setLastSyncAt(Instant lastSyncAt) {
        this.lastSyncAt = lastSyncAt;
    }

    public String getLastHistoryId() {
        return lastHistoryId;
    }

    public void setLastHistoryId(String lastHistoryId) {
        this.lastHistoryId = lastHistoryId;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getAccessTokenCiphertext() {
        return accessTokenCiphertext;
    }

    public void setAccessTokenCiphertext(String accessTokenCiphertext) {
        this.accessTokenCiphertext = accessTokenCiphertext;
    }

    public String getAccessTokenKeyVersion() {
        return accessTokenKeyVersion;
    }

    public void setAccessTokenKeyVersion(String accessTokenKeyVersion) {
        this.accessTokenKeyVersion = accessTokenKeyVersion;
    }

    public String getAccessTokenNonceIv() {
        return accessTokenNonceIv;
    }

    public void setAccessTokenNonceIv(String accessTokenNonceIv) {
        this.accessTokenNonceIv = accessTokenNonceIv;
    }

    public String getAccessTokenAuthTag() {
        return accessTokenAuthTag;
    }

    public void setAccessTokenAuthTag(String accessTokenAuthTag) {
        this.accessTokenAuthTag = accessTokenAuthTag;
    }

    public Instant getAccessTokenExpiresAt() {
        return accessTokenExpiresAt;
    }

    public void setAccessTokenExpiresAt(Instant accessTokenExpiresAt) {
        this.accessTokenExpiresAt = accessTokenExpiresAt;
    }

    /**
     * Returns true if the stored access token is absent or expires within the given leeway.
     */
    public boolean isAccessTokenExpired(java.time.Duration leeway) {
        if (accessTokenExpiresAt == null || accessTokenCiphertext == null) {
            return true;
        }
        return Instant.now().isAfter(accessTokenExpiresAt.minus(leeway));
    }
}
