package com.example.ai

import com.example.data.PrivacyShieldReport
import com.example.data.RedactionEntry

/**
 * 100% on-device local Privacy Shield & Slack message cleaner.
 * Cleans Slack mrkdwn/link syntax first, then scrubs emails, phone numbers,
 * payment cards, API tokens, and account IDs synchronously on-device so raw PII
 * never leaves the handset.
 */
object LocalPrivacyShield {

    // Slack formatting patterns
    private val slackMailtoRegex = Regex("""<mailto:([^|>]+)(?:\|([^>]+))?>""", RegexOption.IGNORE_CASE)
    private val slackTelRegex = Regex("""<tel:([^|>]+)(?:\|([^>]+))?>""", RegexOption.IGNORE_CASE)
    private val slackLinkWithLabelRegex = Regex("""<(https?://[^|>]+)\|([^>]+)>""", RegexOption.IGNORE_CASE)
    private val slackBareLinkRegex = Regex("""<(https?://[^>]+)>""", RegexOption.IGNORE_CASE)
    private val slackChannelMentionRegex = Regex("""<#[A-Z0-9]+\|([^>]+)>""")
    private val slackUserMentionRegex = Regex("""<@[A-Z0-9]+(?:\|([^>]+))?>""")
    private val slackSpecialMentionRegex = Regex("""<!(here|channel|everyone)(?:\|[^>]+)?>""", RegexOption.IGNORE_CASE)
    private val slackDividerLineRegex = Regex("""(?m)^\s*[-=_*]{4,}\s*$""")
    private val slackBoldRegex = Regex("""\*([^*\n]+)\*""")
    private val slackItalicRegex = Regex("""(?<!\w)_([^_\n]+)_(?!\w)""")
    private val slackInlineCodeRegex = Regex("""`([^`\n]+)`""")
    private val multiBlankLinesRegex = Regex("""\n{3,}""")

    // PII patterns
    private val emailRegex = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val accountRegex = Regex(
        """\b(?:ACCT|ACC|CUST|USR|ORD|INV|CARD|TENANT|SHIP|FLT|CUS|SUB|INV|TXN)[-_][A-Z0-9]{3,18}\b""",
        RegexOption.IGNORE_CASE
    )
    private val cardDigitsRegex = Regex("""\b(?:\d{4}[\s\-]?){3}\d{4}\b""")
    private val phoneRegex = Regex(
        """(?<!\w)(?:\+\d{1,3}[\s\-.]?)?(?:\(?\d{2,4}\)?[\s\-.]?){2,4}\d{3,4}(?!\w)"""
    )
    private val tokenRegex = Regex(
        """\b(?:sk_live|pk_live|xoxb|xoxp|xapp|tok|api_key|bearer)[-_][A-Za-z0-9\-]{8,64}\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Cleans Slack mrkdwn & entity encoding into readable plain text while preserving
     * raw email addresses and phone numbers so the Privacy Shield can redact them cleanly.
     */
    fun cleanSlackFormatting(raw: String): String {
        if (raw.isBlank()) return ""
        var cleaned = raw
            .replace("\r\n", "\n")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

        // Unwrap <mailto:alice@co.com|alice@co.com> -> alice@co.com
        cleaned = slackMailtoRegex.replace(cleaned) { match ->
            match.groupValues[1].trim()
        }
        // Unwrap <tel:+14155550194|415-555-0194> -> +14155550194
        cleaned = slackTelRegex.replace(cleaned) { match ->
            match.groupValues[2].ifBlank { match.groupValues[1] }.trim()
        }
        // Unwrap <https://example.com|View ticket> -> View ticket (https://example.com) or just label if url already same
        cleaned = slackLinkWithLabelRegex.replace(cleaned) { match ->
            val url = match.groupValues[1].trim()
            val label = match.groupValues[2].trim()
            if (label.equals(url, ignoreCase = true) || label.startsWith("http", ignoreCase = true)) {
                url
            } else {
                label
            }
        }
        cleaned = slackBareLinkRegex.replace(cleaned) { match ->
            match.groupValues[1].trim()
        }
        cleaned = slackChannelMentionRegex.replace(cleaned) { match ->
            "#${match.groupValues[1].trim()}"
        }
        cleaned = slackUserMentionRegex.replace(cleaned) { match ->
            val name = match.groupValues[1].trim()
            if (name.isNotBlank()) "@$name" else "@teammate"
        }
        cleaned = slackSpecialMentionRegex.replace(cleaned) { match ->
            "@${match.groupValues[1].lowercase()}"
        }

        // Remove divider lines like ----- or =====
        cleaned = slackDividerLineRegex.replace(cleaned, "")
        // Strip inline bold/italic/code markers
        cleaned = slackBoldRegex.replace(cleaned) { it.groupValues[1] }
        cleaned = slackItalicRegex.replace(cleaned) { it.groupValues[1] }
        cleaned = slackInlineCodeRegex.replace(cleaned) { it.groupValues[1] }

        // Collapse excessive blank lines and trim
        cleaned = multiBlankLinesRegex.replace(cleaned, "\n\n").trim()
        return cleaned
    }

    /**
     * Derives a concise, clean headline from a Slack message if no explicit title was provided.
     */
    fun deriveCleanTitle(explicitTitle: String?, rawMessage: String, channel: String): String {
        val cleanedExplicit = cleanSlackFormatting(explicitTitle.orEmpty())
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
        if (!cleanedExplicit.isNullOrBlank()) {
            return cleanedExplicit.take(120)
        }
        val cleanedBody = cleanSlackFormatting(rawMessage)
        val firstLine = cleanedBody
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        return if (firstLine.isNotBlank()) {
            firstLine.take(110)
        } else {
            "New message in $channel"
        }
    }

    fun redactLocally(rawMessage: String): PrivacyShieldReport {
        val cleanedRaw = cleanSlackFormatting(rawMessage)
        var scrubbed = cleanedRaw
        val entries = mutableListOf<RedactionEntry>()

        fun applyRule(
            regex: Regex,
            category: String,
            placeholder: String,
            predicate: (String) -> Boolean = { true }
        ) {
            val matches = regex.findAll(scrubbed)
                .map { it.value }
                .filter(predicate)
                .distinct()
                .toList()
            for (value in matches) {
                entries.add(
                    RedactionEntry(
                        category = category,
                        originalValue = value,
                        replacementToken = placeholder
                    )
                )
                scrubbed = scrubbed.replace(value, placeholder)
            }
        }

        applyRule(emailRegex, "Email Address", "[Protected Email]")
        applyRule(tokenRegex, "API Token", "[Protected Token]")
        applyRule(cardDigitsRegex, "Payment Card", "[Protected Account]")
        applyRule(accountRegex, "Account / Reference ID", "[Protected Account]")
        applyRule(phoneRegex, "Phone Number", "[Protected Phone]") { candidate ->
            val digitsOnly = candidate.count { it.isDigit() }
            // Avoid redacting short numbers, years, or timestamps; require 9..15 digits with a separator or leading +
            digitsOnly in 9..15 && (candidate.startsWith("+") || candidate.any { it == '-' || it == ' ' || it == '(' || it == '.' })
        }

        return PrivacyShieldReport(
            rawText = cleanedRaw,
            scrubbedText = scrubbed,
            redactions = entries
        )
    }
}
