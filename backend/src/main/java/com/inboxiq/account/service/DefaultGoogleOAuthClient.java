package com.inboxiq.account.service;

import com.inboxiq.account.model.GoogleTokenResponse;
import com.inboxiq.account.model.GoogleUserInfo;
import com.inboxiq.common.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Component
public class DefaultGoogleOAuthClient implements GoogleOAuthClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultGoogleOAuthClient.class);

    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo";
    private static final String REVOKE_URL = "https://oauth2.googleapis.com/revoke";

    private final RestClient restClient;
    private final String clientId;
    private final String clientSecret;

    @org.springframework.beans.factory.annotation.Autowired
    public DefaultGoogleOAuthClient(
            @Value("${inboxiq.google.oauth.client-id}") String clientId,
            @Value("${inboxiq.google.oauth.client-secret}") String clientSecret
    ) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.restClient = RestClient.builder().build();
    }

    public DefaultGoogleOAuthClient(RestClient restClient, String clientId, String clientSecret) {
        this.restClient = restClient;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public GoogleTokenResponse exchangeCode(String code, String codeVerifier, String redirectUri) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("code", code);
        formData.add("client_id", clientId);
        formData.add("client_secret", clientSecret);
        formData.add("redirect_uri", redirectUri);
        formData.add("grant_type", "authorization_code");
        if (codeVerifier != null && !codeVerifier.isEmpty()) {
            formData.add("code_verifier", codeVerifier);
        }

        try {
            GoogleTokenResponse response = restClient.post()
                    .uri(TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formData)
                    .retrieve()
                    .body(GoogleTokenResponse.class);

            if (response == null || response.error() != null) {
                String error = response != null ? response.errorDescription() : "Empty token response from Google";
                log.warn("Google OAuth token exchange failed: {}", error);
                throw new BadRequestException("Google authorization code exchange failed: " + error);
            }

            return response;
        } catch (Exception e) {
            log.warn("Error calling Google token endpoint: {}", e.getMessage());
            throw new BadRequestException("Failed to exchange code with Google: " + e.getMessage());
        }
    }

    @Override
    public GoogleUserInfo getUserInfo(String accessToken) {
        try {
            GoogleUserInfo userInfo = restClient.get()
                    .uri(USERINFO_URL)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(GoogleUserInfo.class);

            if (userInfo == null || userInfo.sub() == null) {
                throw new BadRequestException("Could not retrieve user identity from Google");
            }
            return userInfo;
        } catch (Exception e) {
            log.warn("Error calling Google userinfo endpoint: {}", e.getMessage());
            throw new BadRequestException("Failed to retrieve Google user profile: " + e.getMessage());
        }
    }

    @Override
    public boolean revokeToken(String plainTextToken) {
        if (plainTextToken == null || plainTextToken.isEmpty()) {
            return false;
        }
        try {
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add("token", plainTextToken);

            restClient.post()
                    .uri(REVOKE_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formData)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Successfully revoked Google OAuth token remotely");
            return true;
        } catch (Exception e) {
            log.warn("Remote Google token revocation failed (continuing with local disconnect): {}", e.getMessage());
            return false;
        }
    }
}
