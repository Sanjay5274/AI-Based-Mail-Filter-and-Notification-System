package com.inboxiq.email.parser;

import com.inboxiq.account.gmail.model.GmailHeader;
import com.inboxiq.account.gmail.model.GmailMessage;
import com.inboxiq.account.gmail.model.GmailMessagePart;
import com.inboxiq.account.gmail.model.GmailPartBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GmailMimeParser Unit Tests")
class GmailMimeParserTest {

    private GmailMimeParser parser;

    @BeforeEach
    void setUp() {
        parser = new GmailMimeParser();
    }

    private String encodeBase64Url(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Parse simple plain-text email with standard headers")
    void parse_simplePlainTextEmail() {
        List<GmailHeader> headers = List.of(
                new GmailHeader("Subject", "Weekly Architecture Review"),
                new GmailHeader("From", "Alice Smith <alice@example.com>"),
                new GmailHeader("To", "bob@inboxiq.local, carol@inboxiq.local"),
                new GmailHeader("Date", "Tue, 17 Nov 2026 10:15:30 +0000")
        );

        GmailPartBody body = new GmailPartBody(null, 45, encodeBase64Url("Hello team, here is the weekly review update."));
        GmailMessagePart payload = new GmailMessagePart("0", "text/plain", null, headers, body, null);

        GmailMessage message = new GmailMessage(
                "msg-001",
                "thread-001",
                List.of("INBOX", "IMPORTANT"),
                "Hello team, here is the weekly review...",
                "hist-101",
                "1794900930000",
                2048,
                null,
                payload
        );

        ParsedEmail parsed = parser.parse(message);

        assertThat(parsed.gmailMessageId()).isEqualTo("msg-001");
        assertThat(parsed.gmailThreadId()).isEqualTo("thread-001");
        assertThat(parsed.subject()).isEqualTo("Weekly Architecture Review");
        assertThat(parsed.senderName()).isEqualTo("Alice Smith");
        assertThat(parsed.senderEmail()).isEqualTo("alice@example.com");
        assertThat(parsed.recipientEmails()).containsExactlyInAnyOrder("bob@inboxiq.local", "carol@inboxiq.local");
        assertThat(parsed.bodyText()).isEqualTo("Hello team, here is the weekly review update.");
        assertThat(parsed.bodyHtml()).isNull();
        assertThat(parsed.isRead()).isTrue();
        assertThat(parsed.hasAttachments()).isFalse();
        assertThat(parsed.attachments()).isEmpty();
        assertThat(parsed.receivedAt()).isEqualTo(Instant.parse("2026-11-17T10:15:30Z"));
    }

    @Test
    @DisplayName("Parse HTML-only email and synthesize clean plain text")
    void parse_htmlOnlyEmail_synthesizesPlainText() {
        List<GmailHeader> headers = List.of(
                new GmailHeader("Subject", "Newsletter Issue #42"),
                new GmailHeader("From", "news@techdigest.com"),
                new GmailHeader("To", "subscriber@inboxiq.local")
        );

        String htmlContent = "<html><head><style>.btn { color: red; }</style></head>" +
                "<body><h1>Monthly Tech Digest</h1>" +
                "<p>Welcome to our <b>latest issue</b> &amp; updates!</p>" +
                "<br/><div>Visit <a href='https://example.com'>here</a> for more.</div>" +
                "</body></html>";

        GmailPartBody body = new GmailPartBody(null, htmlContent.length(), encodeBase64Url(htmlContent));
        GmailMessagePart payload = new GmailMessagePart("0", "text/html", null, headers, body, null);

        GmailMessage message = new GmailMessage(
                "msg-002", "thread-002", List.of("UNREAD"), "Monthly Tech Digest...", "hist-102", "1794901000000", 3500, null, payload
        );

        ParsedEmail parsed = parser.parse(message);

        assertThat(parsed.isRead()).isFalse(); // UNREAD label present
        assertThat(parsed.bodyHtml()).contains("Monthly Tech Digest");
        assertThat(parsed.bodyText())
                .doesNotContain("<html", "<style", "<p", "<b>")
                .contains("Monthly Tech Digest")
                .contains("Welcome to our latest issue & updates!")
                .contains("Visit here for more.");
        assertThat(parsed.senderName()).isNull();
        assertThat(parsed.senderEmail()).isEqualTo("news@techdigest.com");
    }

    @Test
    @DisplayName("Parse multipart/alternative email with both text and HTML parts")
    void parse_multipartAlternative() {
        List<GmailHeader> headers = List.of(
                new GmailHeader("Subject", "Account Verification"),
                new GmailHeader("From", "\"Security Team\" <security@inboxiq.local>"),
                new GmailHeader("To", "user@gmail.com")
        );

        GmailMessagePart textPart = new GmailMessagePart(
                "0.0", "text/plain", null, List.of(), new GmailPartBody(null, 25, encodeBase64Url("Your code is 123456.")), null
        );
        GmailMessagePart htmlPart = new GmailMessagePart(
                "0.1", "text/html", null, List.of(), new GmailPartBody(null, 50, encodeBase64Url("<p>Your code is <b>123456</b>.</p>")), null
        );

        GmailMessagePart payload = new GmailMessagePart(
                "0", "multipart/alternative", null, headers, null, List.of(textPart, htmlPart)
        );

        GmailMessage message = new GmailMessage(
                "msg-003", "thread-003", List.of(), "Your code is 123456.", "hist-103", "1794902000000", 1200, null, payload
        );

        ParsedEmail parsed = parser.parse(message);

        assertThat(parsed.senderName()).isEqualTo("Security Team");
        assertThat(parsed.senderEmail()).isEqualTo("security@inboxiq.local");
        assertThat(parsed.bodyText()).isEqualTo("Your code is 123456.");
        assertThat(parsed.bodyHtml()).isEqualTo("<p>Your code is <b>123456</b>.</p>");
        assertThat(parsed.hasAttachments()).isFalse();
    }

    @Test
    @DisplayName("Parse nested multipart/mixed structure with attachments")
    void parse_nestedMultipartWithAttachment() {
        List<GmailHeader> headers = List.of(
                new GmailHeader("Subject", "Invoice Q3 Attached"),
                new GmailHeader("From", "Accounting <billing@corp.com>"),
                new GmailHeader("To", "client@corp.com")
        );

        // Body alternative
        GmailMessagePart textPart = new GmailMessagePart("0.0.0", "text/plain", null, List.of(),
                new GmailPartBody(null, 30, encodeBase64Url("Please find attached Q3 invoice.")), null);
        GmailMessagePart alternativePart = new GmailMessagePart("0.0", "multipart/alternative", null, List.of(), null, List.of(textPart));

        // Attachment part
        List<GmailHeader> attHeaders = List.of(
                new GmailHeader("Content-Disposition", "attachment; filename=\"invoice_q3.pdf\"")
        );
        GmailMessagePart attPart = new GmailMessagePart(
                "0.1", "application/pdf", "invoice_q3.pdf", attHeaders,
                new GmailPartBody("att-pdf-999", 1048576, null), null
        );

        GmailMessagePart payload = new GmailMessagePart("0", "multipart/mixed", null, headers, null, List.of(alternativePart, attPart));

        GmailMessage message = new GmailMessage("msg-004", "thread-004", List.of(), "Invoice snippet", "hist-104", "1794903000000", 1100000, null, payload);

        ParsedEmail parsed = parser.parse(message);

        assertThat(parsed.hasAttachments()).isTrue();
        assertThat(parsed.attachments()).hasSize(1);
        ParsedAttachment att = parsed.attachments().get(0);
        assertThat(att.attachmentId()).isEqualTo("att-pdf-999");
        assertThat(att.filename()).isEqualTo("invoice_q3.pdf");
        assertThat(att.mimeType()).isEqualTo("application/pdf");
        assertThat(att.sizeBytes()).isEqualTo(1048576);
        assertThat(att.isInline()).isFalse();
        assertThat(parsed.bodyText()).isEqualTo("Please find attached Q3 invoice.");
    }

    @Test
    @DisplayName("Decode RFC 2047 encoded Base64 and Quoted-Printable headers")
    void parse_rfc2047EncodedHeaders() {
        // Base64 encoded: "=?UTF-8?B?UHLDvGZ1bmc=?=" -> "Prüfung"
        // Quoted-Printable: "=?ISO-8859-1?Q?Andr=E9_Smith?=" -> "André Smith"
        List<GmailHeader> headers = List.of(
                new GmailHeader("Subject", "=?UTF-8?B?UHLDvGZ1bmc=?= Notification"),
                new GmailHeader("From", "=?ISO-8859-1?Q?Andr=E9_Smith?= <andre@example.de>"),
                new GmailHeader("To", "recipient@inboxiq.local")
        );

        GmailMessagePart payload = new GmailMessagePart("0", "text/plain", null, headers,
                new GmailPartBody(null, 10, encodeBase64Url("Test body")), null);

        GmailMessage message = new GmailMessage("msg-005", "thread-005", List.of(), "snippet", "hist-105", "1794904000000", 500, null, payload);

        ParsedEmail parsed = parser.parse(message);

        assertThat(parsed.subject()).isEqualTo("Prüfung Notification");
        assertThat(parsed.senderName()).isEqualTo("André Smith");
        assertThat(parsed.senderEmail()).isEqualTo("andre@example.de");
    }

    @Test
    @DisplayName("Handle missing headers and fall back to internalDate")
    void parse_missingHeadersFallback() {
        // No Date, No Subject, No From
        GmailMessagePart payload = new GmailMessagePart("0", "text/plain", null, List.of(),
                new GmailPartBody(null, 5, encodeBase64Url("hello")), null);

        GmailMessage message = new GmailMessage("msg-006", "thread-006", List.of(), "hello", "hist-106", "1700000000000", 100, null, payload);

        ParsedEmail parsed = parser.parse(message);

        assertThat(parsed.subject()).isNull();
        assertThat(parsed.senderName()).isNull();
        assertThat(parsed.senderEmail()).isEqualTo("unknown@inboxiq.local");
        assertThat(parsed.recipientEmails()).isEmpty();
        assertThat(parsed.receivedAt()).isEqualTo(Instant.ofEpochMilli(1700000000000L));
    }

    @Test
    @DisplayName("Extract multiple recipients from To, Cc, and Bcc with deduplication")
    void parse_multipleRecipients() {
        List<GmailHeader> headers = List.of(
                new GmailHeader("To", "alice@example.com, Bob Jones <bob@example.com>"),
                new GmailHeader("Cc", "carol@example.com, ALICE@example.com"), // duplicate alice
                new GmailHeader("Bcc", "dan@example.com")
        );

        GmailMessagePart payload = new GmailMessagePart("0", "text/plain", null, headers,
                new GmailPartBody(null, 5, encodeBase64Url("text")), null);

        GmailMessage message = new GmailMessage("msg-007", "thread-007", List.of(), "", "hist-107", "1794905000000", 200, null, payload);

        ParsedEmail parsed = parser.parse(message);

        assertThat(parsed.recipientEmails())
                .containsExactlyInAnyOrder("alice@example.com", "bob@example.com", "carol@example.com", "dan@example.com");
    }

    @Test
    @DisplayName("Handle malformed Base64 body safely without throwing exception")
    void parse_malformedBase64() {
        GmailMessagePart payload = new GmailMessagePart("0", "text/plain", null, List.of(),
                new GmailPartBody(null, 15, "!!!not-valid-base64&&"), null);

        GmailMessage message = new GmailMessage("msg-008", "thread-008", List.of(), "", "hist-108", "1794906000000", 150, null, payload);

        ParsedEmail parsed = parser.parse(message);

        assertThat(parsed.bodyText()).isEqualTo("!!!not-valid-base64&&");
    }

    @Test
    @DisplayName("Handle large HTML input safely with size limits")
    void parse_largeHtml_boundedSize() {
        GmailMimeParser cappedParser = new GmailMimeParser(1024); // 1 KB limit
        String largeHtml = "<html><body><p>" + "A".repeat(5000) + "</p></body></html>";

        String text = cappedParser.convertHtmlToPlainText(largeHtml);

        assertThat(text).isNotNull();
        assertThat(text.length()).isLessThanOrEqualTo(1024);
    }

    @Test
    @DisplayName("Handle malformed HTML safely without throwing exception")
    void parse_malformedHtml() {
        String malformedHtml = "<div><p>Unclosed paragraph <b>bold text <i>italic";
        String text = parser.convertHtmlToPlainText(malformedHtml);

        assertThat(text).contains("Unclosed paragraph");
        assertThat(text).contains("bold text");
        assertThat(text).contains("italic");
    }

    @Test
    @DisplayName("Handle ISO-8859-1 and Windows-1252 body charset decoding")
    void parse_charsets_iso88591_and_windows1252() {
        // ISO-8859-1 with French accented characters
        String isoText = "Café & Crème brûlée";
        byte[] isoBytes = isoText.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        String encodedIso = Base64.getUrlEncoder().withoutPadding().encodeToString(isoBytes);

        List<GmailHeader> isoHeaders = List.of(
                new GmailHeader("Content-Type", "text/plain; charset=\"ISO-8859-1\"")
        );
        GmailMessagePart isoPart = new GmailMessagePart("0", "text/plain", null, isoHeaders,
                new GmailPartBody(null, isoBytes.length, encodedIso), null);
        GmailMessage isoMsg = new GmailMessage("msg-iso", "t-iso", List.of(), "", "h-iso", "1794907000000", 100, null, isoPart);

        ParsedEmail parsedIso = parser.parse(isoMsg);
        assertThat(parsedIso.bodyText()).isEqualTo(isoText);

        // Windows-1252 with Euro symbol (€ is 0x80 in Windows-1252, invalid in ISO-8859-1)
        byte[] winBytes = new byte[]{(byte) 0x80, '1', '0', '0'}; // €100
        String encodedWin = Base64.getUrlEncoder().withoutPadding().encodeToString(winBytes);

        List<GmailHeader> winHeaders = List.of(
                new GmailHeader("Content-Type", "text/plain; charset=windows-1252")
        );
        GmailMessagePart winPart = new GmailMessagePart("1", "text/plain", null, winHeaders,
                new GmailPartBody(null, winBytes.length, encodedWin), null);
        GmailMessage winMsg = new GmailMessage("msg-win", "t-win", List.of(), "", "h-win", "1794908000000", 100, null, winPart);

        ParsedEmail parsedWin = parser.parse(winMsg);
        assertThat(parsedWin.bodyText()).isEqualTo("€100");
    }

    @Test
    @DisplayName("Distinguish inline vs regular attachments and honor Content-Disposition")
    void parse_attachmentDispositions_inlineAndExplicit() {
        // 1. Regular explicit attachment without filename
        List<GmailHeader> attHeaders1 = List.of(
                new GmailHeader("Content-Disposition", "attachment"),
                new GmailHeader("Content-Type", "application/pdf")
        );
        GmailMessagePart attPart1 = new GmailMessagePart("att-1", "application/pdf", null, attHeaders1,
                new GmailPartBody("blob-id-1", 5000, null), null);

        // 2. Inline attachment with Content-ID
        List<GmailHeader> attHeaders2 = List.of(
                new GmailHeader("Content-Disposition", "inline; filename=\"logo.png\""),
                new GmailHeader("Content-ID", "<logo@company.com>")
        );
        GmailMessagePart attPart2 = new GmailMessagePart("att-2", "image/png", "logo.png", attHeaders2,
                new GmailPartBody("blob-id-2", 2048, null), null);

        GmailMessagePart root = new GmailMessagePart("root", "multipart/mixed", null, List.of(), null,
                List.of(attPart1, attPart2));

        GmailMessage message = new GmailMessage("msg-att", "t-att", List.of(), "", "h-att", "1794909000000", 7000, null, root);

        ParsedEmail parsed = parser.parse(message);

        assertThat(parsed.hasAttachments()).isTrue();
        assertThat(parsed.attachments()).hasSize(2);

        ParsedAttachment regular = parsed.attachments().get(0);
        assertThat(regular.attachmentId()).isEqualTo("blob-id-1");
        assertThat(regular.isInline()).isFalse();

        ParsedAttachment inline = parsed.attachments().get(1);
        assertThat(inline.filename()).isEqualTo("logo.png");
        assertThat(inline.isInline()).isTrue();
    }
}
