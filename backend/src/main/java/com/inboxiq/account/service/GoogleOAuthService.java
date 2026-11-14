package com.inboxiq.account.service;

import com.inboxiq.account.dto.AccountResponse;
import com.inboxiq.account.dto.OAuthUrlResponse;
import com.inboxiq.account.entity.ConnectedAccount;
import com.inboxiq.account.model.EncryptedTokenResult;
import com.inboxiq.account.model.GoogleTokenResponse;
import com.inboxiq.account.model.GoogleUserInfo;
import com.inboxiq.account.repository.ConnectedAccountRepository;
import com.inboxiq.common.exception.BadRequestException;
import com.inboxiq.common.exception.ConflictException;
import com.inboxiq.common.exception.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GoogleOAuthService {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final String GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";

    private final String clientId;
    private final String redirectUri;
    private final String scope;
    private final GoogleOAuthClient googleOAuthClient;
    private final TokenEncryptionService tokenEncryptionService;
    private final ConnectedAccountRepository connectedAccountRepository;

    private final Map<String, OAuthState> stateStore = new ConcurrentHashMap<>();

    public record OAuthState(String state, UUID userId, String codeVerifier, Instant expiresAt) {
        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    public GoogleOAuthService(
            @Value("${inboxiq.google.oauth.client-id}") String clientId,
            @Value("${inboxiq.google.oauth.redirect-uri}") String redirectUri,
            @Value("${inboxiq.google.oauth.scope:https://www.googleapis.com/auth/gmail.readonly}") String scope,
            GoogleOAuthClient googleOAuthClient,
            TokenEncryptionService tokenEncryptionService,
            ConnectedAccountRepository connectedAccountRepository
    ) {
        this.clientId = clientId;
        this.redirectUri = redirectUri;
        this.scope = scope;
        this.googleOAuthClient = googleOAuthClient;
        this.tokenEncryptionService = tokenEncryptionService;
        this.connectedAccountRepository = connectedAccountRepository;
    }

    public OAuthUrlResponse createAuthorizationUrl(UUID userId) {
        cleanExpiredStates();

        String state = generateSecureRandomHex(16); // 128-bit state
        String codeVerifier = generateCodeVerifier();
        String codeChallenge = computeCodeChallenge(codeVerifier);

        stateStore.put(state, new OAuthState(state, userId, codeVerifier, Instant.now().plus(STATE_TTL)));

        String authorizationUrl = UriComponentsBuilder.fromUriString(GOOGLE_AUTH_URL)
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", scope + " https://www.googleapis.com/auth/userinfo.profile https://www.googleapis.com/auth/userinfo.email")
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent")
                .queryParam("state", state)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .build()
                .toUriString();

        return new OAuthUrlResponse(authorizationUrl);
    }

    @Transactional
    public AccountResponse handleCallback(String code, String state, UUID currentUserId) {
        if (state == null || state.trim().isEmpty()) {
            throw new UnauthorizedException("Missing OAuth state parameter");
        }
        if (code == null || code.trim().isEmpty()) {
            throw new BadRequestException("Missing OAuth authorization code");
        }

        // Replay defense: remove state immediately upon verification
        OAuthState storedState = stateStore.remove(state);
        if (storedState == null || storedState.isExpired()) {
            throw new UnauthorizedException("Invalid, expired, or already-used OAuth state");
        }

        // User binding check: state must belong to the currently authenticated user
        if (!storedState.userId().equals(currentUserId)) {
            log.warn("Security Alert: User mismatch on OAuth callback. Initiated by: {}, Callback presented by: {}",
                    storedState.userId(), currentUserId);
            throw new UnauthorizedException("OAuth initiation does not match the authenticated session user");
        }

        // Exchange authorization code for tokens
        GoogleTokenResponse tokenResponse = googleOAuthClient.exchangeCode(
                code, storedState.codeVerifier(), redirectUri
        );

        // Fetch stable Google account identity (`sub`)
        GoogleUserInfo userInfo = googleOAuthClient.getUserInfo(tokenResponse.accessToken());
        String googleSub = userInfo.sub();
        String email = userInfo.email();
        String name = userInfo.name();

        if (googleSub == null || googleSub.isEmpty()) {
            throw new BadRequestException("Google response did not contain a valid subject ID (sub)");
        }

        // Check for existing connection with this Google account
        var existingAccountOpt = connectedAccountRepository.findByProviderAndProviderAccountId("GOOGLE", googleSub);

        ConnectedAccount account;
        if (existingAccountOpt.isPresent()) {
            ConnectedAccount existing = existingAccountOpt.get();

            // Prevent cross-user Google account hijacking
            if (!existing.getUserId().equals(currentUserId)) {
                log.warn("Security Conflict: Google sub {} is already connected to user {}", googleSub, existing.getUserId());
                throw new ConflictException("This Google account is already connected to another InboxIQ user.");
            }

            account = existing;
            account.setEmailAddress(email);
            account.setDisplayName(name);
            account.setActive(true);
            account.setSyncStatus("IDLE");

            // If Google returned a refresh token on reconnect, update encrypted credentials
            if (tokenResponse.refreshToken() != null && !tokenResponse.refreshToken().isEmpty()) {
                EncryptedTokenResult encResult = tokenEncryptionService.encrypt(
                        tokenResponse.refreshToken(), account.getId(), currentUserId
                );
                account.setRefreshTokenCiphertext(encResult.ciphertext());
                account.setNonceIv(encResult.nonceIv());
                account.setAuthTag(encResult.authTag());
                account.setEncryptionKeyVersion(encResult.keyVersion());
            }
            // If Google omitted refresh token on reconnect, keep existing valid ciphertext!
        } else {
            // Initial connection requires a refresh token
            if (tokenResponse.refreshToken() == null || tokenResponse.refreshToken().isEmpty()) {
                throw new BadRequestException("Google did not provide a refresh token. Please re-initiate connection with consent.");
            }

            UUID accountId = UUID.randomUUID();
            EncryptedTokenResult encResult = tokenEncryptionService.encrypt(
                    tokenResponse.refreshToken(), accountId, currentUserId
            );

            account = new ConnectedAccount(
                    accountId,
                    currentUserId,
                    "GOOGLE",
                    googleSub,
                    email,
                    name,
                    encResult.ciphertext(),
                    encResult.keyVersion(),
                    encResult.nonceIv(),
                    encResult.authTag(),
                    new String[]{scope}
            );
        }

        ConnectedAccount saved = connectedAccountRepository.save(account);
        log.info("Successfully connected Google account {} for user {}", saved.getId(), currentUserId);
        return AccountResponse.fromEntity(saved);
    }

    private void cleanExpiredStates() {
        stateStore.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    private String generateSecureRandomHex(int byteCount) {
        byte[] bytes = new byte[byteCount];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String computeCodeChallenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
