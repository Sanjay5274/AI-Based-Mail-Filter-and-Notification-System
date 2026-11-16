package com.inboxiq.email.controller;

import com.inboxiq.account.entity.ConnectedAccount;
import com.inboxiq.account.repository.ConnectedAccountRepository;
import com.inboxiq.common.dto.ApiResponse;
import com.inboxiq.common.exception.ResourceNotFoundException;
import com.inboxiq.email.dto.HistoricalSyncRequest;
import com.inboxiq.email.dto.HistoricalSyncResponse;
import com.inboxiq.email.dto.SyncStatusResponse;
import com.inboxiq.email.repository.EmailRepository;
import com.inboxiq.email.service.GmailHistoricalSyncService;
import com.inboxiq.security.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}/sync")
public class GmailSyncController {

    private final GmailHistoricalSyncService historicalSyncService;
    private final ConnectedAccountRepository connectedAccountRepository;
    private final EmailRepository emailRepository;

    public GmailSyncController(
            GmailHistoricalSyncService historicalSyncService,
            ConnectedAccountRepository connectedAccountRepository,
            EmailRepository emailRepository
    ) {
        this.historicalSyncService = historicalSyncService;
        this.connectedAccountRepository = connectedAccountRepository;
        this.emailRepository = emailRepository;
    }

    /**
     * POST /api/v1/accounts/{accountId}/sync/historical
     *
     * <p>Initiates historical synchronization asynchronously.
     * Enforces ownership, prevents duplicate running syncs on the account, and returns immediately with 202 Accepted.
     */
    @PostMapping("/historical")
    public ResponseEntity<ApiResponse<HistoricalSyncResponse>> initiateHistoricalSync(
            @PathVariable UUID accountId,
            @Valid @RequestBody(required = false) HistoricalSyncRequest request
    ) {
        UUID currentUserId = SecurityUtils.getCurrentUserId();
        Integer lookbackDays = request != null ? request.lookbackDays() : null;

        HistoricalSyncResponse response = historicalSyncService.initiateSync(accountId, currentUserId, lookbackDays);
        ApiResponse<HistoricalSyncResponse> body = new ApiResponse<>(
                HttpStatus.ACCEPTED.value(),
                "Historical email synchronization initiated successfully",
                response
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    /**
     * GET /api/v1/accounts/{accountId}/sync/status
     *
     * <p>Returns the current synchronization status, cursor, and total message count for the account.
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<SyncStatusResponse>> getSyncStatus(
            @PathVariable UUID accountId
    ) {
        UUID currentUserId = SecurityUtils.getCurrentUserId();
        ConnectedAccount account = connectedAccountRepository.findByIdAndUserId(accountId, currentUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Connected account not found with ID: " + accountId));

        long count = emailRepository.countByConnectedAccountId(accountId);

        SyncStatusResponse response = new SyncStatusResponse(
                account.getId(),
                account.getSyncStatus(),
                account.getLastSyncAt(),
                account.getLastHistoryId(),
                count
        );

        return ResponseEntity.ok(ApiResponse.ok("Sync status retrieved successfully", response));
    }
}
