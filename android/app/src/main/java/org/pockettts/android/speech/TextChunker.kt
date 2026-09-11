package org.pockettts.android.speech

/**
 * Splits speakable text into chunks that are synthesised one at a time.
 *
 * Chunking is what makes the difference between tapping "read aloud" and
 * waiting for a wall of text to synthesise, and hearing the first sentence
 * immediately. It also bounds how long a "stop" takes to take effect, since a
 * chunk in flight has to finish before the run can be abandoned.
 *
 * Chunks are cut at sentence ends where possible, at clause boundaries when a
 * sentence is very long, and mid-phrase only as a last resort.
 */
object TextChunker {

    /** A run of text to synthesise, plus the silence that should follow it. */
    data class Chunk(
        val text: String,
        /** Seconds of silence appended after this chunk. */
        val trailingPauseSeconds: Float,
        /** Offset of [text] in the string that was chunked, for highlighting. */
        val start: Int,
        val end: Int,
    )

    private const val TARGET = 200
    private const val MAX = 400

    private const val PARAGRAPH_PAUSE = 0.45f
    private const val SENTENCE_PAUSE = 0.0f

    private val SENTENCE_END = Regex("""[.!?…]["')\]]*\s""")
    private val CLAUSE_END = Regex("""[,;:]["')\]]*\s""")

    fun chunk(speakable: String, target: Int = TARGET, max: Int = MAX): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var paragraphStart = 0

        // Paragraphs were separated by a blank line upstream; each becomes its
        // own run so the pause between them is real silence rather than the
        // model's guess at how a period sounds.
        val paragraphs = Regex("""\n\s*\n""").split(speakable)
        for ((paragraphIndex, paragraph) in paragraphs.withIndex()) {
            val offset = speakable.indexOf(paragraph, paragraphStart).let {
                if (it >= 0) it else paragraphStart
            }
            paragraphStart = offset + paragraph.length

            val trimmed = paragraph.trim()
            if (trimmed.isEmpty()) continue
            val lead = paragraph.indexOf(trimmed.first())

            val pieces = splitParagraph(trimmed, target, max)
            for ((pieceIndex, piece) in pieces.withIndex()) {
                val isLastOfParagraph = pieceIndex == pieces.lastIndex
                val isLastOverall = isLastOfParagraph && paragraphIndex == paragraphs.lastIndex
                val pause = when {
                    isLastOverall -> 0f
                    isLastOfParagraph -> PARAGRAPH_PAUSE
                    else -> SENTENCE_PAUSE
                }
                val start = offset + lead + piece.offset
                chunks += Chunk(
                    text = piece.text,
                    trailingPauseSeconds = pause,
                    start = start,
                    end = start + piece.text.length,
                )
            }
        }
        return chunks
    }

    private data class Piece(val text: String, val offset: Int)

    private fun splitParagraph(paragraph: String, target: Int, max: Int): List<Piece> {
        val pieces = mutableListOf<Piece>()
        var cursor = 0
        while (cursor < paragraph.length) {
            val remaining = paragraph.length - cursor
            if (remaining <= max) {
                pieces += Piece(paragraph.substring(cursor).trim(), cursor)
                break
            }
            val window = paragraph.substring(cursor, cursor + max)
            val cut = lastBoundary(window, SENTENCE_END, target)
                ?: lastBoundary(window, CLAUSE_END, target)
                ?: window.lastIndexOf(' ').takeIf { it > 0 }
                ?: max
            val text = paragraph.substring(cursor, cursor + cut).trim()
            if (text.isNotEmpty()) pieces += Piece(text, cursor)
            cursor += cut
            while (cursor < paragraph.length && paragraph[cursor].isWhitespace()) cursor++
        }
        return pieces.filter { it.text.isNotEmpty() }
    }

    /**
     * Finds the last boundary in [window] at or after [minimum]. Preferring a
     * late boundary keeps chunks near the target size; requiring one past the
     * minimum stops a paragraph of "Yes. No. Maybe." becoming one chunk each.
     */
    private fun lastBoundary(window: String, pattern: Regex, minimum: Int): Int? {
        var best: Int? = null
        for (match in pattern.findAll(window)) {
            val end = match.range.last + 1
            if (end >= minimum) best = end
        }
        return best
    }
}
