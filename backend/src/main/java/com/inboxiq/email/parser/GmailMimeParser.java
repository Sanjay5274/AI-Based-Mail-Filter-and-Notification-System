package com.inboxiq.email.parser;

import com.inboxiq.account.gmail.model.GmailHeader;
import com.inboxiq.account.gmail.model.GmailMessage;
import com.inboxiq.account.gmail.model.GmailMessagePart;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Robust, production-grade parser for Gmail messages and MIME tree structures.
 *
 * <p>Extracts canonical email fields, decodes RFC 2047 encoded headers, traverses
 * complex nested multipart payloads, decodes Base64URL bodies with charset detection,
 * generates secure plain-text fallbacks using Jsoup with length bounds, and collects
 * attachment metadata without downloading binary payloads.
 */
@Component
public class GmailMimeParser {

    private static final Logger log = LoggerFactory.getLogger(GmailMimeParser.class);

    private static final Pattern RFC2047_PATTERN = Pattern.compile("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=");
    private static final Pattern EMAIL_ADDRESS_PATTERN = Pattern.compile("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
    private static final Pattern SENDER_NAME_PATTERN = Pattern.compile("^\\s*\"?([^\"<]+)\"?\\s*<.*>$");
    private static final Pattern CHARSET_PATTERN = Pattern.compile("(?i)charset=\\s*\"?([^;\"'\\s]+)");

    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ISO_ZONED_DATE_TIME,
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss z", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss z", Locale.ENGLISH)
    );

    private final int maxHtmlSizeBytes;

    public GmailMimeParser(
            @Value("${inboxiq.parser.max-html-size-bytes:1048576}") int maxHtmlSizeBytes
    ) {
        this.maxHtmlSizeBytes = maxHtmlSizeBytes;
    }

    public GmailMimeParser() {
        this(1048576); // Default 1 MB
    }

    public ParsedEmail parse(GmailMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("GmailMessage cannot be null");
        }

        String messageId = message.id();
        String threadId = message.threadId();
        String historyId = message.historyId();
        Integer sizeEstimate = message.sizeEstimate();
        String snippet = message.snippet();
        List<String> labelIds = message.labelIds() != null ? new ArrayList<>(message.labelIds()) : Collections.emptyList();
        boolean isRead = !labelIds.contains("UNREAD");

        List<GmailHeader> headers = message.payload() != null && message.payload().headers() != null
                ? message.payload().headers()
                : Collections.emptyList();

        // 1. Subject
        String rawSubject = getHeaderValue(headers, "Subject");
        String subject = decodeRfc2047(rawSubject);

        // 2. Sender (From)
        String rawFrom = getHeaderValue(headers, "From");
        SenderInfo sender = parseSender(rawFrom);

        // 3. Recipients (To, Cc, Bcc)
        List<String> recipientEmails = extractRecipients(headers);

        // 4. Received Timestamp
        Instant receivedAt = parseReceivedAt(getHeaderValue(headers, "Date"), message.internalDate());

        // 5. Body & Attachment extraction
        StringBuilder bodyTextBuilder = new StringBuilder();
        StringBuilder bodyHtmlBuilder = new StringBuilder();
        List<ParsedAttachment> attachments = new ArrayList<>();

        if (message.payload() != null) {
            traverseParts(message.payload(), bodyTextBuilder, bodyHtmlBuilder, attachments);
        }

        String bodyText = bodyTextBuilder.toString().trim();
        String bodyHtml = bodyHtmlBuilder.toString().trim();

        // If bodyText is missing but bodyHtml is present, synthesize plain text from HTML
        if (bodyText.isEmpty() && !bodyHtml.isEmpty()) {
            bodyText = convertHtmlToPlainText(bodyHtml);
        }

        boolean hasAttachments = !attachments.isEmpty();

        return new ParsedEmail(
                messageId,
                threadId,
                historyId,
                subject,
                sender.name(),
                sender.email(),
                recipientEmails,
                receivedAt,
                bodyText.isEmpty() ? null : bodyText,
                bodyHtml.isEmpty() ? null : bodyHtml,
                snippet,
                labelIds,
                isRead,
                sizeEstimate,
                hasAttachments,
                attachments
        );
    }

    /**
     * Recursively traverses MIME parts to collect text bodies and attachment metadata.
     */
    private void traverseParts(
            GmailMessagePart part,
            StringBuilder bodyText,
            StringBuilder bodyHtml,
            List<ParsedAttachment> attachments
    ) {
        if (part == null) {
            return;
        }

        String mimeType = part.mimeType() != null ? part.mimeType().toLowerCase(Locale.ROOT) : "";
        String filename = part.filename();
        String attachmentId = part.body() != null ? part.body().attachmentId() : null;
        Integer sizeBytes = part.body() != null ? part.body().size() : null;
        String data = part.body() != null ? part.body().data() : null;

        // Check if this part is an attachment
        boolean hasAttachmentId = attachmentId != null && !attachmentId.isBlank();
        boolean hasFilename = filename != null && !filename.isBlank();
        boolean isExplicitAttachment = isAttachmentDisposition(part.headers());

        boolean isAttachment = hasAttachmentId
                || isExplicitAttachment
                || (hasFilename && !mimeType.startsWith("text/plain") && !mimeType.startsWith("text/html"));

        if (isAttachment) {
            String resolvedFilename = decodeRfc2047(hasFilename ? filename : "attachment");
            String resolvedAttachmentId = hasAttachmentId ? attachmentId : (part.partId() != null ? part.partId() : UUID.randomUUID().toString());
            boolean isInline = isInlineDisposition(part.headers());

            attachments.add(new ParsedAttachment(
                    resolvedAttachmentId,
                    resolvedFilename,
                    mimeType.isEmpty() ? "application/octet-stream" : mimeType,
                    sizeBytes != null ? sizeBytes : 0,
                    isInline
            ));
            return;
        }

        // Body text / html extraction
        if (data != null && !data.isBlank()) {
            Charset charset = resolveCharset(getHeaderValue(part.headers(), "Content-Type"));
            String decodedContent = decodeBase64Url(data, charset);
            if (mimeType.startsWith("text/plain")) {
                if (!bodyText.isEmpty()) {
                    bodyText.append("\n\n");
                }
                bodyText.append(decodedContent);
            } else if (mimeType.startsWith("text/html")) {
                if (!bodyHtml.isEmpty()) {
                    bodyHtml.append("<br/><br/>");
                }
                bodyHtml.append(decodedContent);
            }
        }

        // Recursively inspect nested multipart structures (e.g. multipart/alternative, multipart/mixed)
        if (part.parts() != null && !part.parts().isEmpty()) {
            for (GmailMessagePart nestedPart : part.parts()) {
                traverseParts(nestedPart, bodyText, bodyHtml, attachments);
            }
        }
    }

    private boolean isAttachmentDisposition(List<GmailHeader> headers) {
        String disposition = getHeaderValue(headers, "Content-Disposition");
        return disposition != null && disposition.toLowerCase(Locale.ROOT).contains("attachment");
    }

    private boolean isInlineDisposition(List<GmailHeader> headers) {
        String disposition = getHeaderValue(headers, "Content-Disposition");
        String contentId = getHeaderValue(headers, "Content-ID");
        return (disposition != null && disposition.toLowerCase(Locale.ROOT).contains("inline")) || contentId != null;
    }

    private String getHeaderValue(List<GmailHeader> headers, String targetName) {
        if (headers == null || targetName == null) {
            return null;
        }
        for (GmailHeader header : headers) {
            if (targetName.equalsIgnoreCase(header.name())) {
                return header.value();
            }
        }
        return null;
    }

    private Charset resolveCharset(String contentTypeHeader) {
        if (contentTypeHeader == null || contentTypeHeader.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        Matcher matcher = CHARSET_PATTERN.matcher(contentTypeHeader);
        if (matcher.find()) {
            String charsetName = matcher.group(1).trim().replaceAll("^[\"']|[\"']$", "");
            try {
                return Charset.forName(charsetName);
            } catch (Exception e) {
                log.debug("Unsupported or invalid charset '{}', falling back to UTF-8", charsetName);
            }
        }
        return StandardCharsets.UTF_8;
    }

    private SenderInfo parseSender(String rawFrom) {
        if (rawFrom == null || rawFrom.isBlank()) {
            return new SenderInfo(null, "unknown@inboxiq.local");
        }

        String decoded = decodeRfc2047(rawFrom).trim();
        String name = null;
        String email = null;

        Matcher nameMatcher = SENDER_NAME_PATTERN.matcher(decoded);
        if (nameMatcher.find()) {
            name = nameMatcher.group(1).trim();
            if (name.startsWith("\"") && name.endsWith("\"") && name.length() > 1) {
                name = name.substring(1, name.length() - 1).trim();
            }
        }

        Matcher emailMatcher = EMAIL_ADDRESS_PATTERN.matcher(decoded);
        if (emailMatcher.find()) {
            email = emailMatcher.group().toLowerCase(Locale.ROOT);
        }

        if (email == null) {
            email = decoded.replaceAll("[<>]", "").trim().toLowerCase(Locale.ROOT);
        }

        if (name == null && email != null && !decoded.equalsIgnoreCase(email)) {
            String potentialName = decoded.replace("<" + email + ">", "").replace(email, "").trim();
            if (!potentialName.isBlank()) {
                name = potentialName;
            }
        }

        return new SenderInfo(name != null && !name.isBlank() ? name : null, email);
    }

    private List<String> extractRecipients(List<GmailHeader> headers) {
        Set<String> recipients = new LinkedHashSet<>();
        extractAddressesFromHeader(getHeaderValue(headers, "To"), recipients);
        extractAddressesFromHeader(getHeaderValue(headers, "Cc"), recipients);
        extractAddressesFromHeader(getHeaderValue(headers, "Bcc"), recipients);
        return new ArrayList<>(recipients);
    }

    private void extractAddressesFromHeader(String headerValue, Set<String> targetSet) {
        if (headerValue == null || headerValue.isBlank()) {
            return;
        }
        Matcher matcher = EMAIL_ADDRESS_PATTERN.matcher(headerValue);
        while (matcher.find()) {
            targetSet.add(matcher.group().toLowerCase(Locale.ROOT));
        }
    }

    private Instant parseReceivedAt(String dateHeader, String internalDate) {
        if (dateHeader != null && !dateHeader.isBlank()) {
            String sanitizedDate = dateHeader.trim().replaceAll("\\s*\\([^)]*\\)\\s*$", "");
            for (DateTimeFormatter formatter : DATE_FORMATTERS) {
                try {
                    return OffsetDateTime.parse(sanitizedDate, formatter).toInstant();
                } catch (DateTimeParseException ignored) {
                }
            }
        }

        if (internalDate != null && !internalDate.isBlank()) {
            try {
                long epochMillis = Long.parseLong(internalDate.trim());
                return Instant.ofEpochMilli(epochMillis);
            } catch (NumberFormatException ignored) {
            }
        }

        return Instant.now();
    }

    /**
     * Decodes Base64URL string (RFC 4648 §5) as produced by Gmail API using UTF-8.
     */
    public String decodeBase64Url(String base64UrlData) {
        return decodeBase64Url(base64UrlData, StandardCharsets.UTF_8);
    }

    /**
     * Decodes Base64URL string using a specific charset.
     */
    public String decodeBase64Url(String base64UrlData, Charset charset) {
        if (base64UrlData == null || base64UrlData.isBlank()) {
            return "";
        }
        Charset targetCharset = charset != null ? charset : StandardCharsets.UTF_8;
        String cleanData = base64UrlData.trim();
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(cleanData);
            return new String(bytes, targetCharset);
        } catch (IllegalArgumentException e) {
            try {
                byte[] bytes = Base64.getDecoder().decode(cleanData);
                return new String(bytes, targetCharset);
            } catch (IllegalArgumentException ex) {
                log.warn("Failed to decode base64 body: {}", ex.getMessage());
                return cleanData;
            }
        }
    }

    /**
     * Decodes RFC 2047 encoded words in email headers (e.g., =?UTF-8?B?...?= or =?ISO-8859-1?Q?...?=).
     */
    public String decodeRfc2047(String text) {
        if (text == null || text.isBlank() || !text.contains("=?")) {
            return text;
        }

        Matcher matcher = RFC2047_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String charsetName = matcher.group(1);
            String encoding = matcher.group(2).toUpperCase(Locale.ROOT);
            String encodedContent = matcher.group(3);

            try {
                Charset charset = Charset.forName(charsetName);
                String decoded;
                if ("B".equals(encoding)) {
                    byte[] bytes = Base64.getDecoder().decode(encodedContent);
                    decoded = new String(bytes, charset);
                } else if ("Q".equals(encoding)) {
                    decoded = decodeQuotedPrintable(encodedContent, charset);
                } else {
                    decoded = matcher.group(0);
                }
                matcher.appendReplacement(sb, Matcher.quoteReplacement(decoded));
            } catch (Exception e) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String decodeQuotedPrintable(String content, Charset charset) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int len = content.length();
        for (int i = 0; i < len; i++) {
            char c = content.charAt(i);
            if (c == '_') {
                buffer.write(' ');
            } else if (c == '=' && i + 2 < len) {
                String hex = content.substring(i + 1, i + 3);
                try {
                    int b = Integer.parseInt(hex, 16);
                    buffer.write(b);
                    i += 2;
                } catch (NumberFormatException e) {
                    buffer.write(c);
                }
            } else {
                buffer.write((byte) c);
            }
        }
        return buffer.toString(charset);
    }

    /**
     * Converts HTML email content into clean, readable plain text safely using Jsoup.
     */
    public String convertHtmlToPlainText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }

        // Enforce input size limit to prevent memory exhaustion
        String input = html.length() > maxHtmlSizeBytes ? html.substring(0, maxHtmlSizeBytes) : html;

        try {
            Document doc = Jsoup.parse(input);
            doc.select("script, style, noscript, svg").remove();

            // Insert linebreaks for block and line elements before text extraction
            doc.select("br").append("\\n");
            doc.select("p, div, tr, li, h1, h2, h3, h4, h5, h6").prepend("\\n");

            String rawText = doc.text().replace("\\n", "\n");

            // Normalize whitespace
            String normalized = rawText.replaceAll("[ \\t]+", " ");
            normalized = normalized.replaceAll("(?m)^\\s+$", "");
            normalized = normalized.replaceAll("\\n{3,}", "\n\n");

            return normalized.trim();
        } catch (Exception e) {
            log.warn("Jsoup HTML extraction failed, falling back to basic strip: {}", e.getMessage());
            return input.replaceAll("<[^>]+>", " ").trim();
        }
    }

    public record SenderInfo(String name, String email) {}
}
