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

    /**
     * Splits [speakable] at sentence ends.
     *
     * Chunks are the unit of *playback* - big enough that the model is not
     * restarted every few words. Sentences are the unit of *generation*:
     * sherpa-onnx re-splits whatever it is handed on sentence-ending
     * punctuation and generates each one independently, drawing a speaker per
     * sentence. Anything that wants to influence one generation from the last
     * has to work at this granularity, because a chunk boundary is only ever
     * every third or fourth sentence boundary - which is precisely why the
     * chunk-level attempt at voice continuity could not have worked.
     *
     * The same [SENTENCE_END] the chunker cuts on, so the two agree about what
     * a sentence is.
     */
    fun sentences(speakable: String): List<String> {
        val cut = mutableListOf<String>()
        var cursor = 0
        for (match in SENTENCE_END.findAll(speakable)) {
            val end = match.range.last + 1
            speakable.substring(cursor, end).trim().takeIf { it.isNotEmpty() }?.let { cut += it }
            cursor = end
        }
        // The last sentence usually has no trailing whitespace, so it never
        // matches; it is whatever is left.
        speakable.substring(cursor).trim().takeIf { it.isNotEmpty() }?.let { cut += it }
        return joinSilentPieces(cut)
    }

    /**
     * Folds pieces with nothing to say into their neighbours.
     *
     * `. . . .` - an ellipsis typed as spaced periods, which is ordinary in
     * scripture and older prose - is four sentence ends in a row by the rule
     * above, so cutting on it yields three pieces that are a single full stop
     * and nothing else. Handed to the model one at a time those are a request
     * to speak silence, and what comes back is breathing, sniffing and a loop
     * of noise. It was never reachable before, because sherpa-onnx sees a whole
     * chunk and does its own thing with punctuation it cannot pronounce; it is
     * reachable now only because this splits first.
     *
     * A piece with no letter or digit in it belongs to the sentence it trails,
     * so it is appended there and the model is never asked to say nothing.
     */
    private fun joinSilentPieces(pieces: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (piece in pieces) {
            if (out.isNotEmpty() && !hasSomethingToSay(piece)) {
                out[out.lastIndex] = out.last() + " " + piece
            } else {
                out += piece
            }
        }
        // Punctuation at the very start has no preceding sentence to join, so
        // it goes forward onto the one that follows it instead.
        if (out.size > 1 && !hasSomethingToSay(out.first())) {
            val stray = out.removeAt(0)
            out[0] = stray + " " + out[0]
        }
        // A passage that is punctuation all the way down is not a sentence at
        // all, and generating it is the failure above with extra steps.
        return if (out.size == 1 && !hasSomethingToSay(out.single())) emptyList() else out
    }

    private fun hasSomethingToSay(piece: String): Boolean = piece.any { it.isLetterOrDigit() }

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
