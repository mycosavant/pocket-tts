package org.pockettts.android.speech

import androidx.annotation.VisibleForTesting

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
        /** The chunk as it appears in the speakable text; [start] until [end] of it. */
        val text: String,
        /** Seconds of silence appended after this chunk. */
        val trailingPauseSeconds: Float,
        /** Offset of [text] in the string that was chunked, for highlighting. */
        val start: Int,
        val end: Int,
    ) {
        /**
         * What the engine is actually handed: [text], with a trailing run of
         * very short sentences joined into one. See [joinShortTail].
         */
        val speech: String = joinShortTail(text)
    }

    private const val TARGET = 200
    private const val MAX = 400

    private const val PARAGRAPH_PAUSE = 0.45f
    private const val SENTENCE_PAUSE = 0.0f

    /**
     * A sentence of this many words or fewer is "short" for [joinShortTail].
     *
     * Two rather than one because "It compiles. Yes." lost its "Yes." in one
     * run of three, and nothing with a three-word sentence before the end
     * ever lost anything.
     */
    private const val SHORT_SENTENCE_WORDS = 2

    private val SENTENCE_END = Regex("""[.!?…]["')\]]*\s""")
    private val CLAUSE_END = Regex("""[,;:]["')\]]*\s""")

    /** Sentence-final punctuation, allowing a closing quote or bracket after it. */
    private val TERMINAL_PUNCTUATION = Regex("""[.!?…]+(?=["')\]]*$)""")
    private val WORD_CHARACTER = Regex("""[\p{L}\p{N}]""")

    /**
     * Joins a run of very short sentences at the end of [text] into one
     * sentence, with commas: `One. Two. Three.` becomes `One, Two, Three.`
     *
     * The model decides for itself when it has finished speaking, and it gets
     * that wrong in one specific place: the end of a generation, when what is
     * left to say is a few one- or two-word sentences. Measured on the same
     * engine build the app ships, with the stock Alba prompt, over three
     * seeds: `... Termux. One. Two. Three.` lost "Three" in two of three,
     * `... Patching. Testing. Done.` said "done" twice in all three, and
     * `One. Two. Three. Four. Five.` on its own lost "Five" every time. The
     * same words joined with commas were read in full in every run, and so
     * were the same runs when ordinary prose followed them - it is the *end*
     * that is fragile, not the short sentences. A single short sentence at
     * the end after something longer was fine every time and is left alone.
     *
     * Not a rewrite of what the user sees: [Chunk.text] and the offsets stay
     * true to the source, and a period becoming a comma is the whole of the
     * difference in what is said. It is done here rather than in
     * `MarkdownSpeech` because it is a property of how a chunk *ends*, and
     * chunking is what decides where that is.
     *
     * The alternatives were measured too. Letting generation run on past the
     * model's end-of-speech mark does not recover the words, because after a
     * genuine ending the model repeats the last word rather than falling
     * silent. Generating each short sentence on its own costs a full voice
     * conditioning pass per word. Padding the text with leading spaces, which
     * the reference implementation does for short inputs, is stripped by
     * sherpa-onnx before it reaches the model.
     */
    @VisibleForTesting
    internal fun joinShortTail(text: String): String {
        val starts = mutableListOf(0)
        for (end in SENTENCE_END.findAll(text)) starts += end.range.last + 1
        val sentences = starts.mapIndexed { i, from ->
            text.substring(from, starts.getOrNull(i + 1) ?: text.length)
        }.filter { it.isNotBlank() }

        var head = sentences.size
        while (head > 0 && wordCount(sentences[head - 1]) <= SHORT_SENTENCE_WORDS) head--
        val tail = sentences.size - head
        if (tail < 2) return text

        return buildString {
            sentences.take(head).forEach { append(it) }
            sentences.drop(head).forEachIndexed { i, sentence ->
                if (i == tail - 1) {
                    append(sentence)
                } else {
                    // The whitespace that separated the sentences is kept, so
                    // the join is the punctuation and nothing else.
                    val body = sentence.trimEnd()
                    append(TERMINAL_PUNCTUATION.replace(body, ","))
                    append(sentence, body.length, sentence.length)
                }
            }
        }
    }

    private fun wordCount(sentence: String): Int =
        sentence.split(Regex("""\s+""")).count { WORD_CHARACTER.containsMatchIn(it) }

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
