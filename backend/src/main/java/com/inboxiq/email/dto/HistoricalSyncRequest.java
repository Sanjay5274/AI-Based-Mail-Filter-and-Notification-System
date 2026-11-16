package com.inboxiq.email.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record HistoricalSyncRequest(
        @Min(value = 1, message = "Lookback days must be at least 1")
        @Max(value = 365, message = "Lookback days cannot exceed 365")
        Integer lookbackDays
) {
    public static final int DEFAULT_LOOKBACK_DAYS = 30;

    public int resolvedLookbackDays() {
        return (lookbackDays != null && lookbackDays > 0) ? lookbackDays : DEFAULT_LOOKBACK_DAYS;
    }
}
