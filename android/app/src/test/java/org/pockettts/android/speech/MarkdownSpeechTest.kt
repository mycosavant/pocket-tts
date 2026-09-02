package org.pockettts.android.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownSpeechTest {

    private fun speak(markdown: String) = MarkdownSpeech.toSpeakable(markdown)

    @Test
    fun `strips heading markers and terminates the sentence`() {
        assertEquals("Getting started.", speak("## Getting started"))
        assertEquals("Getting started.", speak("## Getting started ##"))
    }

    @Test
    fun `keeps existing heading punctuation`() {
        assertEquals("Ready?", speak("# Ready?"))
    }

    @Test
    fun `strips emphasis without eating the words`() {
        assertEquals("This is important.", speak("This is **important**."))
        assertEquals("This is important.", speak("This is *important*."))
        assertEquals("This is important.", speak("This is ***important***."))
        assertEquals("This is important.", speak("This is __important__."))
        assertEquals("This is important.", speak("This is _important_."))
        assertEquals("This is gone.", speak("This is ~~gone~~."))
    }

    @Test
    fun `leaves snake case identifiers alone`() {
        assertEquals("Call get_state_for_audio_prompt now.", speak("Call get_state_for_audio_prompt now."))
    }

    @Test
    fun `reads link text and drops the url`() {
        assertEquals("See the docs.", speak("See [the docs](https://example.com/a/b)."))
        assertEquals("Alt text.", speak("![Alt text](https://example.com/i.png)"))
        assertEquals("See the docs.", speak("See [the docs][ref]."))
    }

    @Test
    fun `drops bare urls rather than spelling them out`() {
        val spoken = speak("Read https://example.com/some/very/long/path for details.")
        assertFalse(spoken.contains("example.com"))
        assertTrue(spoken.contains("Read"))
        assertTrue(spoken.contains("for details."))
    }

    @Test
    fun `can be told to say something in place of a link`() {
        val spoken = MarkdownSpeech.toSpeakable(
            "Read https://example.com now.",
            MarkdownSpeech.Options(linkPlaceholder = "a link"),
        )
        assertEquals("Read a link now.", spoken)
    }

    @Test
    fun `skips fenced code by default`() {
        val markdown = """
            Here is how:

            ```kotlin
            val x = 1
            println(x)
            ```

            That is all.
        """.trimIndent()
        val spoken = speak(markdown)
        assertFalse(spoken.contains("println"))
        assertTrue(spoken.contains("Here is how:"))
        assertTrue(spoken.contains("That is all."))
    }

    @Test
    fun `reads fenced code when asked`() {
        val markdown = "```\nval x = 1\n```"
        val spoken = MarkdownSpeech.toSpeakable(
            markdown,
            MarkdownSpeech.Options(speakCodeBlocks = true),
        )
        assertTrue(spoken.contains("val x = 1"))
    }

    @Test
    fun `a backtick fence does not close a tilde block`() {
        val markdown = "~~~\nline one\n```\nline two\n~~~\n\nAfter."
        assertEquals("After.", speak(markdown))
    }

    @Test
    fun `code read aloud is not itself parsed as markdown`() {
        val markdown = "~~~\nsome ``code`` here\n~~~"
        val spoken = MarkdownSpeech.toSpeakable(
            markdown,
            MarkdownSpeech.Options(speakCodeBlocks = true),
        )
        assertTrue(spoken, spoken.contains("some ``code`` here"))
    }

    @Test
    fun `an unterminated fence still gets read`() {
        val spoken = MarkdownSpeech.toSpeakable(
            "```\nval x = 1",
            MarkdownSpeech.Options(speakCodeBlocks = true),
        )
        assertTrue(spoken, spoken.contains("val x = 1"))
    }

    @Test
    fun `inline code keeps its contents`() {
        assertEquals("Run pip install pocket-tts first.", speak("Run `pip install pocket-tts` first."))
    }

    @Test
    fun `bullets become sentences and ordinals are kept`() {
        assertEquals("Runs on CPU.\n\nSmall model.", speak("- Runs on CPU\n- Small model"))
        assertEquals("1. First.\n\n2. Second.", speak("1. First\n2. Second"))
    }

    @Test
    fun `task list markers are dropped`() {
        assertEquals("Buy milk.\n\nWalk dog.", speak("- [ ] Buy milk\n- [x] Walk dog"))
    }

    @Test
    fun `blockquote markers are dropped`() {
        assertEquals("To be or not to be.", speak("> To be or not to be."))
    }

    @Test
    fun `table rows are read as comma separated cells`() {
        val markdown = """
            | Voice | Language |
            | ----- | -------- |
            | alba  | English  |
        """.trimIndent()
        val spoken = speak(markdown)
        assertEquals("Voice, Language.\n\nalba, English.", spoken)
    }

    @Test
    fun `thematic breaks and front matter disappear`() {
        assertEquals("After.", speak("---\ntitle: x\n---\n\nAfter."))
        assertEquals("Before.\n\nAfter.", speak("Before.\n\n***\n\nAfter."))
    }

    @Test
    fun `setext headings are recognised`() {
        assertEquals("Title.\n\nBody.", speak("Title\n=====\n\nBody."))
    }

    @Test
    fun `escaped characters are spoken literally and not re-parsed`() {
        assertEquals("A literal * star.", speak("""A literal \* star."""))
        assertEquals("5 * 3 = 15.", speak("""5 \* 3 = 15."""))
    }

    @Test
    fun `html comments and tags are removed`() {
        assertEquals("Visible.", speak("<!-- hidden -->Visible."))
        assertEquals("Hello world.", speak("Hello <span>world</span>."))
        assertEquals("One two.", speak("One<br>two."))
    }

    @Test
    fun `entities are decoded`() {
        assertEquals("Tom & Jerry.", speak("Tom &amp; Jerry."))
    }

    @Test
    fun `paragraphs are separated by a blank line`() {
        assertEquals("One.\n\nTwo.", speak("One.\n\nTwo."))
    }

    @Test
    fun `soft wrapped lines join into one paragraph`() {
        assertEquals("One two three.", speak("One\ntwo\nthree."))
    }

    @Test
    fun `empty and whitespace input produce nothing`() {
        assertEquals("", speak(""))
        assertEquals("", speak("   \n\n  "))
        assertEquals("", speak("```\ncode\n```"))
    }

    @Test
    fun `plain prose is left alone`() {
        val prose = "The quick brown fox jumps over the lazy dog."
        assertEquals(prose, speak(prose))
    }

    @Test
    fun `link reference definitions are not read`() {
        val spoken = speak("See the docs.\n\n[ref]: https://example.com/x")
        assertEquals("See the docs.", spoken)
    }
}
