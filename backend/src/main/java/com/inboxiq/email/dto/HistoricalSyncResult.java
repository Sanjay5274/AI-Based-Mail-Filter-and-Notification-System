package com.inboxiq.email.dto;

import java.util.UUID;

public record HistoricalSyncResult(
        UUID accountId,
        UUID jobId,
        int totalFound,
        int processedCount,
        int duplicateCount,
        int failedCount,
        String initialHistoryId,
        String status
) {}
