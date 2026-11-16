package com.inboxiq.account.gmail.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Raw response from GET /gmail/v1/users/me/profile.
 * Never exposed directly in API responses — mapped to GmailProfileResponse DTO.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GmailProfile(
        @JsonProperty("emailAddress")  String emailAddress,
        @JsonProperty("messagesTotal") Long messagesTotal,
        @JsonProperty("threadsTotal")  Long threadsTotal,
        @JsonProperty("historyId")     String historyId
) {}
