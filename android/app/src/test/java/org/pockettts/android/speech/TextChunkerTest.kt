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
