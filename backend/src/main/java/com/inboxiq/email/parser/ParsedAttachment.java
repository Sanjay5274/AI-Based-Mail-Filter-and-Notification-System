package com.inboxiq.email.parser;

public record ParsedAttachment(
        String attachmentId,
        String filename,
        String mimeType,
        Integer sizeBytes,
        boolean isInline
) {}
