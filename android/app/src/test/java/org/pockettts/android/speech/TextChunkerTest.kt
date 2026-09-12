package org.pockettts.android.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {

    @Test
    fun `short text is a single chunk with no trailing pause`() {
        val chunks = TextChunker.chunk("Hello world.")
        assertEquals(1, chunks.size)
        assertEquals("Hello world.", chunks[0].text)
        assertEquals(0f, chunks[0].trailingPauseSeconds, 0.001f)
    }

    @Test
    fun `paragraphs are separate chunks with a pause between them`() {
        val chunks = TextChunker.chunk("First para.\n\nSecond para.")
        assertEquals(2, chunks.size)
        assertEquals("First para.", chunks[0].text)
        assertEquals("Second para.", chunks[1].text)
        assertTrue(chunks[0].trailingPauseSeconds > 0f)
        assertEquals(0f, chunks[1].trailingPauseSeconds, 0.001f)
    }

    @Test
    fun `long paragraphs are split at sentence boundaries`() {
        val sentence = "This is a reasonably long sentence that takes a while to say. "
        val chunks = TextChunker.chunk(sentence.repeat(10).trim())
        assertTrue("expected several chunks, got ${chunks.size}", chunks.size > 1)
        // Every cut should land after a full stop, not mid-sentence.
        chunks.dropLast(1).forEach {
            assertTrue("chunk did not end a sentence: '${it.text}'", it.text.endsWith("."))
        }
    }

    @Test
    fun `no chunk exceeds the hard maximum`() {
        val text = "word ".repeat(500).trim()
        val chunks = TextChunker.chunk(text)
        chunks.forEach { assertTrue("chunk of ${it.text.length} chars", it.text.length <= 400) }
    }

    @Test
    fun `text with no sentence breaks is still split`() {
        val text = "a".repeat(1200)
        val chunks = TextChunker.chunk(text)
        assertTrue(chunks.size > 1)
        assertEquals(text.length, chunks.sumOf { it.text.length })
    }

    @Test
    fun `offsets point back at the source text`() {
        val text = "First para.\n\nSecond para."
        val chunks = TextChunker.chunk(text)
        chunks.forEach {
            assertEquals(it.text, text.substring(it.start, it.end))
        }
    }

    @Test
    fun `blank input produces no chunks`() {
        assertTrue(TextChunker.chunk("").isEmpty())
        assertTrue(TextChunker.chunk("   \n\n  ").isEmpty())
    }

    @Test
    fun `every piece of the source survives chunking`() {
        val text = "One. Two. Three.\n\nFour. Five."
        val rejoined = TextChunker.chunk(text).joinToString(" ") { it.text }
        assertEquals("One. Two. Three. Four. Five.", rejoined)
    }
}

class ShortTailTest {

    private val lead = "This is a test read sent from Termux."

    @Test
    fun `a trailing run of one-word sentences is spoken as one sentence`() {
        // The model ended "One. Two. Three." after "Two" in two runs of
        // three, and said "Done" twice for "Patching. Testing. Done." in all
        // three; joined with commas both read in full every time.
        val chunk = TextChunker.chunk("$lead One. Two. Three.").single()
        assertEquals("$lead One, Two, Three.", chunk.speech)
        assertEquals("$lead Patching, Testing, Done.", TextChunker.joinShortTail("$lead Patching. Testing. Done."))
    }

    @Test
    fun `what the user sees is not rewritten`() {
        val text = "$lead One. Two. Three."
        val chunk = TextChunker.chunk(text).single()
        assertEquals(text, chunk.text)
        assertEquals(text, text.substring(chunk.start, chunk.end))
    }

    @Test
    fun `two-word sentences count as short`() {
        // "It compiles. Yes." lost its "Yes" in one run of three.
        assertEquals("$lead It compiles, Yes.", TextChunker.joinShortTail("$lead It compiles. Yes."))
    }

    @Test
    fun `one short sentence at the end is left alone`() {
        // "... Termux. Done." was read in full in every run; there is nothing
        // to fix, and a comma there would turn a sentence into a run-on.
        assertEquals("$lead Done.", TextChunker.joinShortTail("$lead Done."))
        assertEquals("$lead All tests pass. Done.", TextChunker.joinShortTail("$lead All tests pass. Done."))
    }

    @Test
    fun `short sentences before the end are left alone`() {
        // Mid-text runs were read in full every time; it is the end of a
        // generation that is fragile.
        val text = "One. Two. Three. Four. Five. And this final ordinary sentence closes the read."
        assertEquals(text, TextChunker.joinShortTail(text))
    }

    @Test
    fun `a chunk that is nothing but short sentences is joined`() {
        assertEquals("One, Two, Three, Four, Five.", TextChunker.joinShortTail("One. Two. Three. Four. Five."))
    }

    @Test
    fun `the last sentence keeps its own punctuation`() {
        assertEquals("$lead Ready, Set, Go!", TextChunker.joinShortTail("$lead Ready. Set. Go!"))
        assertEquals("$lead Really, Yes.", TextChunker.joinShortTail("$lead Really?! Yes."))
    }

    @Test
    fun `a single sentence is never touched`() {
        assertEquals("Done.", TextChunker.joinShortTail("Done."))
        assertEquals("", TextChunker.joinShortTail(""))
    }
}
