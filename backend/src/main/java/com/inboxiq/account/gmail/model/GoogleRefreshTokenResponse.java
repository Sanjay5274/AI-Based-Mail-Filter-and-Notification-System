package com.inboxiq.account.gmail.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents the Google token refresh response from POST /token (grant_type=refresh_token).
 * Note: Google does NOT return a new refresh_token in refresh responses unless
 * rotation is explicitly configured. Never log the token fields.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleRefreshTokenResponse(
        @JsonProperty("access_token")  String accessToken,
        @JsonProperty("expires_in")    Long expiresIn,
        @JsonProperty("token_type")    String tokenType,
        @JsonProperty("scope")         String scope,
        @JsonProperty("error")         String error,
        @JsonProperty("error_description") String errorDescription
) {}
