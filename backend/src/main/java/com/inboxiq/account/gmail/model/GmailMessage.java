package com.inboxiq.account.gmail.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GmailMessage(
        @JsonProperty("id") String id,
        @JsonProperty("threadId") String threadId,
        @JsonProperty("labelIds") List<String> labelIds,
        @JsonProperty("snippet") String snippet,
        @JsonProperty("historyId") String historyId,
        @JsonProperty("internalDate") String internalDate,
        @JsonProperty("sizeEstimate") Integer sizeEstimate,
        @JsonProperty("raw") String raw,
        @JsonProperty("payload") GmailMessagePart payload
) {}
