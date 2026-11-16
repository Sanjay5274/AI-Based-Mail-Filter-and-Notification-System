package com.inboxiq.email.parser;

import java.time.Instant;
import java.util.List;

public record ParsedEmail(
        String gmailMessageId,
        String gmailThreadId,
        String historyId,
        String subject,
        String senderName,
        String senderEmail,
        List<String> recipientEmails,
        Instant receivedAt,
        String bodyText,
        String bodyHtml,
        String snippet,
        List<String> labelIds,
        boolean isRead,
        Integer rawSizeEstimate,
        boolean hasAttachments,
        List<ParsedAttachment> attachments
) {}
