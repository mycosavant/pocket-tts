package org.pockettts.android.speech

/**
 * Turns Markdown into something worth listening to.
 *
 * Feeding raw Markdown to a TTS engine gets you "hash hash Getting started",
 * "asterisk asterisk important asterisk asterisk", and forty seconds of a URL
 * being spelled out. This strips the syntax and keeps the prose, and adds the
 * sentence punctuation that headings and list items normally get away without
 * so the voice falls at the end of them instead of running on.
 *
 * This is deliberately a plain-text pipeline rather than a full CommonMark
 * parse: it has to be forgiving of the half-Markdown people actually paste into
 * a scratchpad, where a stray asterisk is far more likely to be a typo than an
 * unclosed emphasis span.
 */
object MarkdownSpeech {

    data class Options(
        /**
         * Fenced code read aloud is almost never what you want, but "almost" is
         * doing real work there - reading a short snippet back to yourself is a
         * legitimate thing to want.
         */
        val speakCodeBlocks: Boolean = false,
        /** Spoken in place of a skipped code block. Empty means say nothing. */
        val codeBlockPlaceholder: String = "",
        /** URLs are unlistenable. Spoken in place of one; empty means drop it. */
        val linkPlaceholder: String = "",
    )

    private val HTML_COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val FENCE = Regex("""^ {0,3}(`{3,}|~{3,})\s*(\S*).*$""")
    private val ATX_HEADING = Regex("""^ {0,3}(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val SETEXT_UNDERLINE = Regex("""^ {0,3}(=+|-+)\s*$""")
    private val THEMATIC_BREAK = Regex("""^ {0,3}([-*_])\s*(?:\1\s*){2,}$""")
    private val BLOCKQUOTE = Regex("""^ {0,3}(?:>\s?)+""")
    private val UNORDERED_ITEM = Regex("""^\s*[-+*]\s+""")
    private val ORDERED_ITEM = Regex("""^\s*(\d{1,9})[.)]\s+""")
    private val TASK_MARKER = Regex("""^\[[ xX]]\s+""")
    private val TABLE_DELIMITER =
        Regex("""^\s*\|?\s*:?-{2,}:?\s*(?:\|\s*:?-{2,}:?\s*)*\|?\s*$""")
    private val LINK_REFERENCE_DEF = Regex("""^ {0,3}\[[^]]+]:\s*\S+.*$""")

    /** Private-use codepoint; stands in for a backslash-escaped character. */
    private const val SENTINEL = '\uE000'

    /**
     * @return speakable plain text, paragraphs separated by a blank line so a
     *   downstream chunker can turn them into pauses.
     */
    fun toSpeakable(markdown: String, options: Options = Options()): String {
        if (markdown.isBlank()) return ""

        val withoutComments = HTML_COMMENT.replace(markdown, " ")
        val lines = withoutComments.replace("\r\n", "\n").replace('\r', '\n').split("\n")

        val blocks = mutableListOf<String>()
        val paragraph = StringBuilder()
        // Code is accumulated separately from prose: inside a fence the markup
        // characters are literal, so this text must not be run through the
        // inline stripper on its way out.
        val code = StringBuilder()

        fun emit(text: String) {
            val speakable = inline(text, options)
            if (speakable.isNotBlank()) blocks += speakable.ensureSentenceEnd()
        }

        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            val text = paragraph.toString().trim()
            paragraph.setLength(0)
            val speakable = inline(text, options)
            // A paragraph usually punctuates itself; only rescue the ones that
            // do not, so ordinary prose keeps whatever ending it had.
            if (speakable.isNotBlank()) blocks += speakable.ensureSentenceEnd()
        }

        var index = skipFrontMatter(lines)
        var fenceMarker: String? = null

        while (index < lines.size) {
            val raw = lines[index]

            // Fenced code: the closing fence has to be at least as long as the
            // opening one and made of the same character, so a ``` block that
            // contains ~~~ survives intact.
            val fence = FENCE.matchEntire(raw)
            if (fenceMarker != null) {
                val marker = fenceMarker
                val closes = fence != null &&
                    fence.groupValues[1][0] == marker[0] &&
                    fence.groupValues[1].length >= marker.length &&
                    fence.groupValues[2].isEmpty()
                if (closes) {
                    fenceMarker = null
                    if (code.isNotEmpty()) {
                        blocks += code.toString().ensureSentenceEnd()
                        code.setLength(0)
                    }
                } else if (options.speakCodeBlocks) {
                    code.appendSpaced(raw.trim())
                }
                index++
                continue
            }
            if (fence != null) {
                flushParagraph()
                fenceMarker = fence.groupValues[1]
                if (!options.speakCodeBlocks && options.codeBlockPlaceholder.isNotEmpty()) {
                    blocks += options.codeBlockPlaceholder.ensureSentenceEnd()
                }
                index++
                continue
            }

            val line = raw.replaceFirst(BLOCKQUOTE, "")

            if (line.isBlank()) {
                flushParagraph()
                index++
                continue
            }

            if (THEMATIC_BREAK.matches(line)) {
                flushParagraph()
                index++
                continue
            }

            if (LINK_REFERENCE_DEF.matches(line)) {
                index++
                continue
            }

            val heading = ATX_HEADING.matchEntire(line)
            if (heading != null) {
                flushParagraph()
                emit(heading.groupValues[2])
                index++
                continue
            }

            // A setext underline is only an underline if there is paragraph
            // text directly above it to underline.
            val next = lines.getOrNull(index + 1)
            if (next != null && SETEXT_UNDERLINE.matches(next) && !THEMATIC_BREAK.matches(next)) {
                flushParagraph()
                emit(line)
                index += 2
                continue
            }

            if (line.contains('|')) {
                if (TABLE_DELIMITER.matches(line) && line.contains('-')) {
                    index++
                    continue
                }
                if (line.trimStart().startsWith("|") && line.trimEnd().endsWith("|")) {
                    flushParagraph()
                    val cells = line.trim().trim('|').split('|')
                        .map { inline(it.trim(), options) }
                        .filter { it.isNotBlank() }
                    if (cells.isNotEmpty()) blocks += cells.joinToString(", ").ensureSentenceEnd()
                    index++
                    continue
                }
            }

            val ordered = ORDERED_ITEM.find(line)
            val unordered = if (ordered == null) UNORDERED_ITEM.find(line) else null
            val itemMatch = ordered ?: unordered
            if (itemMatch != null) {
                flushParagraph()
                val body = line.substring(itemMatch.value.length).replaceFirst(TASK_MARKER, "")
                // Keeping the ordinal is the point of an ordered list; a bullet
                // carries no information worth speaking.
                val prefix = ordered?.let { "${it.groupValues[1]}. " } ?: ""
                val speakable = inline(body, options)
                if (speakable.isNotBlank()) blocks += (prefix + speakable).ensureSentenceEnd()
                index++
                continue
            }

            paragraph.appendSpaced(line.trim())
            index++
        }
        flushParagraph()
        // An unterminated fence still has content worth speaking.
        if (code.isNotEmpty()) blocks += code.toString().ensureSentenceEnd()

        return blocks.joinToString("\n\n")
    }

    /** Strips inline Markdown from a single run of text. */
    fun inline(text: String, options: Options = Options()): String {
        if (text.isEmpty()) return text

        // Backslash escapes are resolved first and parked behind a sentinel so
        // that a literal escaped asterisk is not then read back as emphasis.
        val escapes = mutableListOf<Char>()
        var s = Regex("""\\([\\`*_{}\[\]()#+\-.!>~|])""").replace(text) { m ->
            escapes += m.groupValues[1][0]
            "$SENTINEL${escapes.size - 1}$SENTINEL"
        }

        // Inline code. The backtick run is matched greedily-innermost so that a
        // double-backtick span containing a single backtick survives.
        s = Regex("""(`{1,3})([^`]|[^`].*?[^`])\1""").replace(s) { it.groupValues[2].trim() }

        s = Regex("""!\[([^]]*)]\([^)]*\)""").replace(s) { it.groupValues[1] }
        s = Regex("""\[([^]]+)]\([^)]*\)""").replace(s) { it.groupValues[1] }
        s = Regex("""\[\^[^]]+]""").replace(s, "")
        s = Regex("""\[([^]]+)]\[[^]]*]""").replace(s) { it.groupValues[1] }
        s = Regex("""<https?://[^>\s]+>""").replace(s, options.linkPlaceholder)
        s = Regex("""(?<![\w/])https?://\S+""").replace(s, options.linkPlaceholder)

        s = Regex("""~~(?=\S)(.+?)(?<=\S)~~""").replace(s) { it.groupValues[1] }
        s = Regex("""==(?=\S)(.+?)(?<=\S)==""").replace(s) { it.groupValues[1] }
        s = Regex("""\*\*\*(?=\S)(.+?)(?<=\S)\*\*\*""").replace(s) { it.groupValues[1] }
        s = Regex("""\*\*(?=\S)(.+?)(?<=\S)\*\*""").replace(s) { it.groupValues[1] }
        s = Regex("""\*(?=\S)([^*]+?)(?<=\S)\*""").replace(s) { it.groupValues[1] }
        // Underscore emphasis only outside words, so snake_case_names survive.
        s = Regex("""(?<!\w)__(?=\S)(.+?)(?<=\S)__(?!\w)""").replace(s) { it.groupValues[1] }
        s = Regex("""(?<!\w)_(?=\S)([^_]+?)(?<=\S)_(?!\w)""").replace(s) { it.groupValues[1] }

        s = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE).replace(s, " ")
        s = Regex("""</?[A-Za-z][A-Za-z0-9-]*(?:\s[^<>]*)?/?>""").replace(s, "")

        s = s.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")

        s = Regex("""$SENTINEL(\d+)$SENTINEL""").replace(s) { m ->
            escapes.getOrElse(m.groupValues[1].toInt()) { ' ' }.toString()
        }

        return s.replace(Regex("""[ \t]+"""), " ").trim()
    }

    private fun StringBuilder.appendSpaced(text: String) {
        if (text.isEmpty()) return
        if (isNotEmpty()) append(' ')
        append(text)
    }

    private fun skipFrontMatter(lines: List<String>): Int {
        if (lines.firstOrNull()?.trim() != "---") return 0
        for (i in 1 until lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed == "---" || trimmed == "...") return i + 1
        }
        return 0
    }

    private fun String.ensureSentenceEnd(): String {
        val trimmed = trimEnd()
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed.last() in ".!?:;,…") trimmed else "$trimmed."
    }
}
