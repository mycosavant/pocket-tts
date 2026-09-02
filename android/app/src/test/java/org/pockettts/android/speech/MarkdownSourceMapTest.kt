package org.pockettts.android.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a spoken sentence came from in the document the user can see.
 *
 * Highlighting the line being read needs this and nothing else, and it is
 * fiddly for one reason: the offsets a chunker produces are into the *stripped*
 * text. Stripping deletes syntax, so those offsets drift further from the
 * source with every heading, link and asterisk passed.
 *
 * The traps are all in the setup rather than the algorithm - anything that
 * rewrites the string before the lines are counted, such as normalising CRLF or
 * deleting an HTML comment, silently shifts every offset after it.
 */
class MarkdownSourceMapTest {

    private fun source(markdown: String, spoken: String): String? {
        val speakable = MarkdownSpeech.toSpeakableWithSource(markdown)
        val start = speakable.text.indexOf(spoken)
        if (start < 0) return null
        val range = speakable.sourceRange(start, start + spoken.length, markdown) ?: return null
        return markdown.substring(range.first, range.last + 1)
    }

    @Test
    fun `a plain sentence points at itself`() {
        val markdown = "The first thing. The second thing."
        assertEquals("The second thing.", source(markdown, "The second thing."))
    }

    @Test
    fun `a heading points at the heading, syntax and all`() {
        val markdown = "# Getting started\n\nSome prose here."
        // The spoken form has lost the hashes, so it is the block that answers.
        assertEquals("# Getting started", source(markdown, "Getting started."))
    }

    @Test
    fun `the second paragraph is not the first`() {
        val markdown = "First paragraph here.\n\nSecond paragraph here."
        assertEquals("Second paragraph here.", source(markdown, "Second paragraph here."))
    }

    @Test
    fun `a rewritten line falls back to the line it came from`() {
        // "important" loses its asterisks, so the sentence cannot be found
        // verbatim; the whole line is still the right thing to highlight.
        val markdown = "This is **important** text."
        val found = source(markdown, "This is important text.")
        assertEquals(markdown, found)
    }

    @Test
    fun `a link collapses to its text and still points at the whole line`() {
        val markdown = "Read [the manual](https://example.com/manual) first."
        val found = source(markdown, "Read the manual first.")
        assertEquals(markdown, found)
    }

    @Test
    fun `carriage returns do not shift every offset after them`() {
        // Normalising CRLF to LF before counting moves everything after the
        // first line one character earlier, per line.
        //
        // The emphasis matters: without it the spoken sentence is findable
        // verbatim, and the search that narrows a range to the exact sentence
        // would quietly correct the drift, leaving the test green against
        // broken offsets. Rewritten text has to rely on the block range alone.
        val markdown = "One line here.\r\nAnd another one.\r\n\r\nA **later** paragraph."
        assertEquals("A **later** paragraph.", source(markdown, "A later paragraph."))
    }

    @Test
    fun `an html comment does not shift what follows it`() {
        // Deleting the comment, or replacing it with a single space, moves
        // every offset after it by the length of the comment.
        val markdown = "<!-- a fairly long editorial note -->\n\nThe visible paragraph."
        assertEquals("The visible paragraph.", source(markdown, "The visible paragraph."))
    }

    @Test
    fun `a list item points at its own line`() {
        val markdown = "- first item\n- second item\n- third item"
        assertEquals("- second item", source(markdown, "second item."))
    }

    @Test
    fun `every span points somewhere real`() {
        val markdown = """
            ---
            title: Notes
            ---

            # Heading

            A paragraph with *emphasis* and a [link](https://example.com).

            - one
            - two

            > A quoted line.

            | a | b |
            |---|---|
            | 1 | 2 |

            ```
            code here
            ```

            The last word.
        """.trimIndent()

        val speakable = MarkdownSpeech.toSpeakableWithSource(markdown)
        assertTrue("nothing was mapped", speakable.spans.isNotEmpty())
        speakable.spans.forEach { span ->
            assertTrue(
                "span $span is outside the source (length ${markdown.length})",
                span.sourceStart in 0..markdown.length && span.sourceEnd in 0..markdown.length,
            )
            assertTrue("span $span is empty or inverted", span.sourceStart < span.sourceEnd)
            assertTrue("span $span is inverted in the speakable text", span.speakableStart < span.speakableEnd)
        }
        // Spans arrive in source order, and none overlaps the next.
        speakable.spans.zipWithNext { a, b ->
            assertTrue("spans out of order: $a then $b", a.sourceEnd <= b.sourceStart)
        }
    }

    @Test
    fun `the speakable offsets in a span really name that block`() {
        val markdown = "# Title\n\nA paragraph."
        val speakable = MarkdownSpeech.toSpeakableWithSource(markdown)
        val texts = speakable.spans.map {
            speakable.text.substring(it.speakableStart, it.speakableEnd)
        }
        assertEquals(listOf("Title.", "A paragraph."), texts)
    }

    @Test
    fun `an offset in no block at all maps nowhere`() {
        val speakable = MarkdownSpeech.toSpeakableWithSource("Only this.")
        assertNull(speakable.sourceRange(500, 520, "Only this."))
        assertNotNull(speakable.sourceRange(0, 5, "Only this."))
    }
}
