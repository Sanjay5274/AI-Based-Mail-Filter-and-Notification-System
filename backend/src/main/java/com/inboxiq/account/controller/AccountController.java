package com.inboxiq.account.controller;

import com.inboxiq.account.dto.AccountResponse;
import com.inboxiq.account.dto.OAuthUrlResponse;
import com.inboxiq.account.service.AccountService;
import com.inboxiq.account.service.GoogleOAuthService;
import com.inboxiq.common.dto.ApiResponse;
import com.inboxiq.security.SecurityUtils;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;
    private final GoogleOAuthService googleOAuthService;
    private final String frontendRedirectUrl;

    public AccountController(
            AccountService accountService,
            GoogleOAuthService googleOAuthService,
            @Value("${inboxiq.google.oauth.frontend-redirect-url:http://localhost:3000/settings?accountConnected=true}") String frontendRedirectUrl
    ) {
        this.accountService = accountService;
        this.googleOAuthService = googleOAuthService;
        this.frontendRedirectUrl = frontendRedirectUrl;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<AccountResponse>>> listAccounts() {
        UUID currentUserId = SecurityUtils.getCurrentUserId();
        List<AccountResponse> accounts = accountService.listAccounts(currentUserId);
        return ResponseEntity.ok(ApiResponse.ok("Connected accounts retrieved successfully", accounts));
    }

    @PostMapping("/google/connect")
    public ResponseEntity<ApiResponse<OAuthUrlResponse>> initiateGoogleConnect() {
        UUID currentUserId = SecurityUtils.getCurrentUserId();
        OAuthUrlResponse response = googleOAuthService.createAuthorizationUrl(currentUserId);
        return ResponseEntity.ok(ApiResponse.ok("Google authorization URL generated", response));
    }

    @GetMapping("/google/callback")
    public ResponseEntity<ApiResponse<AccountResponse>> handleGoogleCallback(
            @RequestParam("code") String code,
            @RequestParam("state") String state,
            @RequestHeader(value = "Accept", defaultValue = "application/json") String acceptHeader,
            HttpServletResponse response
    ) throws IOException {
        UUID currentUserId = SecurityUtils.getCurrentUserId();
        AccountResponse accountResponse = googleOAuthService.handleCallback(code, state, currentUserId);

        if (acceptHeader != null && acceptHeader.contains("text/html")) {
            response.sendRedirect(frontendRedirectUrl);
            return null;
        }

        return ResponseEntity.ok(ApiResponse.ok("Google account successfully connected", accountResponse));
    }

    @DeleteMapping("/{accountId}")
    public ResponseEntity<ApiResponse<AccountService.DisconnectResult>> disconnectAccount(@PathVariable("accountId") UUID accountId) {
        UUID currentUserId = SecurityUtils.getCurrentUserId();
        AccountService.DisconnectResult result = accountService.disconnectAccount(accountId, currentUserId);
        return ResponseEntity.ok(ApiResponse.ok("Account disconnected successfully", result));
    }
}

