package com.inboxiq.account.dto;

import com.inboxiq.account.entity.ConnectedAccount;

import java.time.Instant;
import java.util.UUID;

public class AccountResponse {

    private UUID id;
    private String provider;
    private String emailAddress;
    private String displayName;
    private String syncStatus;
    private Instant lastSyncAt;
    private boolean isActive;
    private Instant createdAt;

    public AccountResponse() {
    }

    public AccountResponse(UUID id, String provider, String emailAddress, String displayName, String syncStatus, Instant lastSyncAt, boolean isActive, Instant createdAt) {
        this.id = id;
        this.provider = provider;
        this.emailAddress = emailAddress;
        this.displayName = displayName;
        this.syncStatus = syncStatus;
        this.lastSyncAt = lastSyncAt;
        this.isActive = isActive;
        this.createdAt = createdAt;
    }

    public static AccountResponse fromEntity(ConnectedAccount account) {
        return new AccountResponse(
                account.getId(),
                account.getProvider(),
                account.getEmailAddress(),
                account.getDisplayName(),
                account.getSyncStatus(),
                account.getLastSyncAt(),
                account.isActive(),
                account.getCreatedAt()
        );
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
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
}

