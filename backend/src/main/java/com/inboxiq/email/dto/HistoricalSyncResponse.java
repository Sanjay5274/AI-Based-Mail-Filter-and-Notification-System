package com.inboxiq.email.dto;

import java.util.UUID;

public record HistoricalSyncResponse(
        UUID jobId,
        UUID accountId,
        String status,
        int lookbackDays,
        String message
) {}
