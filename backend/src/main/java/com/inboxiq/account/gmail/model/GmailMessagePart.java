package com.inboxiq.account.gmail.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GmailMessagePart(
        @JsonProperty("partId") String partId,
        @JsonProperty("mimeType") String mimeType,
        @JsonProperty("filename") String filename,
        @JsonProperty("headers") List<GmailHeader> headers,
        @JsonProperty("body") GmailPartBody body,
        @JsonProperty("parts") List<GmailMessagePart> parts
) {}
