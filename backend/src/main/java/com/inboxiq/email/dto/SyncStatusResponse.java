package com.inboxiq.email.dto;

import java.time.Instant;
import java.util.UUID;

public record SyncStatusResponse(
        UUID accountId,
        String syncStatus,
        Instant lastSyncAt,
        String lastHistoryId,
        long totalEmailsSynced
) {}
