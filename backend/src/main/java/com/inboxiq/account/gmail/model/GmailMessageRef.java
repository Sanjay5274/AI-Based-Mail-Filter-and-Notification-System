package com.inboxiq.account.gmail.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single message reference in the Gmail messages list (id + threadId only).
 * Full message details require a separate users.messages.get call.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GmailMessageRef(
        @JsonProperty("id")       String id,
        @JsonProperty("threadId") String threadId
) {}
