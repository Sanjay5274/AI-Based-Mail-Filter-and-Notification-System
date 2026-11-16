package com.inboxiq.account.gmail.service;

import com.inboxiq.account.gmail.model.GmailMessageListPage;
import com.inboxiq.account.gmail.model.GmailProfile;
import com.inboxiq.account.gmail.model.GoogleRefreshTokenResponse;
import com.inboxiq.common.exception.GmailApiException;
import com.inboxiq.common.exception.GoogleCredentialsRevokedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Default production implementation of {@link GmailApiClient}.
 *
 * <p>Uses Spring 6 {@link RestClient} for non-blocking, structured API calls.
 * Never logs credential values or Authorization headers.
 */
@Component
public class DefaultGmailApiClient implements GmailApiClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultGmailApiClient.class);

    private static final String GMAIL_BASE_URL   = "https://gmail.googleapis.com/gmail/v1";
    private static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";

    // Gmail API enforces max 500 per list request; we cap at 500 defensively.
    static final int MAX_RESULTS_CEILING = 500;

    private final RestClient restClient;

    @Autowired
    public DefaultGmailApiClient() {
        // Suppress the default error handler so we can handle status codes ourselves
        // in translateGmailError() after inspecting the response status.
        this.restClient = RestClient.builder()
                .defaultStatusHandler(
                        code -> true,          // match all responses
                        (req, res) -> { /* no-op — we process the status ourselves */ }
                )
                .build();
    }

    // Package-private constructor for unit tests (inject mock RestClient)
    DefaultGmailApiClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public GmailProfile getProfile(String accessToken) {
        String url = GMAIL_BASE_URL + "/users/me/profile";
        try {
            var response = restClient.get()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .toEntity(GmailProfile.class);

            translateGmailError(response.getStatusCode(), "getProfile");

            if (response.getBody() == null) {
                throw new GmailApiException("Gmail profile response body was null", 502, "GMAIL_EMPTY_RESPONSE");
            }
            return response.getBody();
        } catch (GmailApiException | GoogleCredentialsRevokedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Unexpected error calling Gmail profile endpoint: {}", e.getMessage());
            throw new GmailApiException("Failed to retrieve Gmail profile: " + e.getMessage(), e);
        }
    }

    @Override
    public GmailMessageListPage listMessages(String accessToken, int maxResults, String pageToken) {
        return listMessages(accessToken, maxResults, pageToken, null);
    }

    @Override
    public GmailMessageListPage listMessages(String accessToken, int maxResults, String pageToken, String query) {
        int clampedMaxResults = Math.min(Math.max(maxResults, 1), MAX_RESULTS_CEILING);

        UriComponentsBuilder uriBuilder = UriComponentsBuilder
                .fromUriString(GMAIL_BASE_URL + "/users/me/messages")
                .queryParam("maxResults", clampedMaxResults);

        if (pageToken != null && !pageToken.isBlank()) {
            uriBuilder.queryParam("pageToken", pageToken);
        }

        if (query != null && !query.isBlank()) {
            uriBuilder.queryParam("q", query);
        }

        String uri = uriBuilder.build().toUriString();

        try {
            var response = restClient.get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .toEntity(GmailMessageListPage.class);

            translateGmailError(response.getStatusCode(), "listMessages");

            if (response.getBody() == null) {
                throw new GmailApiException("Gmail messages list response body was null", 502, "GMAIL_EMPTY_RESPONSE");
            }
            return response.getBody();
        } catch (GmailApiException | GoogleCredentialsRevokedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Unexpected error calling Gmail messages endpoint: {}", e.getMessage());
            throw new GmailApiException("Failed to list Gmail messages: " + e.getMessage(), e);
        }
    }

    @Override
    public com.inboxiq.account.gmail.model.GmailMessage getMessage(String accessToken, String messageId, String format) {
        String resolvedFormat = (format != null && !format.isBlank()) ? format : "full";

        String uri = UriComponentsBuilder
                .fromUriString(GMAIL_BASE_URL + "/users/me/messages/" + messageId)
                .queryParam("format", resolvedFormat)
                .build()
                .toUriString();

        try {
            var response = restClient.get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .toEntity(com.inboxiq.account.gmail.model.GmailMessage.class);

            translateGmailError(response.getStatusCode(), "getMessage");

            if (response.getBody() == null) {
                throw new GmailApiException("Gmail message response body was null for id: " + messageId, 502, "GMAIL_EMPTY_RESPONSE");
            }
            return response.getBody();
        } catch (GmailApiException | GoogleCredentialsRevokedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Unexpected error calling Gmail getMessage endpoint for {}: {}", messageId, e.getMessage());
            throw new GmailApiException("Failed to get Gmail message: " + e.getMessage(), e);
        }
    }

    @Override
    public GoogleRefreshTokenResponse refreshAccessToken(String refreshToken, String clientId, String clientSecret) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "refresh_token");
        formData.add("refresh_token", refreshToken);
        formData.add("client_id", clientId);
        formData.add("client_secret", clientSecret);

        try {
            var response = restClient.post()
                    .uri(GOOGLE_TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formData)
                    .retrieve()
                    .toEntity(GoogleRefreshTokenResponse.class);

            HttpStatusCode status = response.getStatusCode();
            GoogleRefreshTokenResponse body = response.getBody();

            if (status.value() == 401 || (body != null && "invalid_grant".equals(body.error()))) {
                // invalid_grant means the refresh token has been revoked or is expired
                log.warn("Google OAuth refresh token is revoked or expired (invalid_grant)");
                throw new GoogleCredentialsRevokedException(
                        "Google refresh token has been revoked. Please reconnect your Gmail account.");
            }

            if (!status.is2xxSuccessful()) {
                String errMsg = body != null ? body.errorDescription() : "HTTP " + status.value();
                throw new GmailApiException("Token refresh failed: " + errMsg, status.value(), "TOKEN_REFRESH_FAILED");
            }

            if (body == null || body.accessToken() == null) {
                throw new GmailApiException("Token refresh returned empty access token", 502, "TOKEN_REFRESH_EMPTY");
            }

            return body;
        } catch (GmailApiException | GoogleCredentialsRevokedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Unexpected error refreshing Google access token: {}", e.getMessage());
            throw new GmailApiException("Failed to refresh Google access token: " + e.getMessage(), e);
        }
    }

    /**
     * Translates Gmail HTTP error status codes to typed exceptions.
     * This is called after the response is received so we can inspect the body too.
     */
    private void translateGmailError(HttpStatusCode statusCode, String operation) {
        if (statusCode.is2xxSuccessful()) {
            return;
        }
        int code = statusCode.value();
        if (code == HttpStatus.UNAUTHORIZED.value()) {
            throw new GoogleCredentialsRevokedException(
                    "Google access token was rejected by Gmail API during " + operation +
                    ". The token may be expired or revoked.");
        }
        if (code == HttpStatus.FORBIDDEN.value()) {
            throw new GmailApiException(
                    "Gmail API access forbidden during " + operation +
                    ". Verify the connected account has gmail.readonly scope.",
                    code, "GMAIL_FORBIDDEN");
        }
        if (code == 429) {
            throw new GmailApiException(
                    "Gmail API rate limit exceeded during " + operation + ". Try again later.",
                    code, "GMAIL_RATE_LIMITED");
        }
        if (code >= 500) {
            throw new GmailApiException(
                    "Gmail API server error (" + code + ") during " + operation,
                    code, "GMAIL_SERVER_ERROR");
        }
        throw new GmailApiException(
                "Unexpected Gmail API response " + code + " during " + operation,
                code, "GMAIL_UNEXPECTED_STATUS");
    }
}
