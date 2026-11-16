package com.inboxiq.account.gmail.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Raw response from GET /gmail/v1/users/me/messages.
 * Never exposed directly in API responses — mapped to GmailMessageListResponse DTO.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GmailMessageListPage(
        @JsonProperty("messages")          List<GmailMessageRef> messages,
        @JsonProperty("nextPageToken")     String nextPageToken,
        @JsonProperty("resultSizeEstimate") Integer resultSizeEstimate
) {}
