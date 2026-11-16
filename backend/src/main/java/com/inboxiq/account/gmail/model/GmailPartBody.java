package com.inboxiq.account.gmail.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GmailPartBody(
        @JsonProperty("attachmentId") String attachmentId,
        @JsonProperty("size") Integer size,
        @JsonProperty("data") String data
) {}
