package org.pockettts.android.ui

import android.content.Intent
import android.os.Bundle
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch
import org.pockettts.android.R
import org.pockettts.android.databinding.ActivityScratchpadBinding
import org.pockettts.android.engine.Settings
import org.pockettts.android.player.PlaybackService
import org.pockettts.android.player.Reader

/**
 * A notepad that reads itself back.
 *
 * The point is to remove the round trip through a notes app or a pastebin: put
 * text here, hit speak, hear it in the voice you picked. Markdown is rendered
 * for reading and stripped for speaking, so a pasted README sounds like prose
 * rather than punctuation.
 */
class ScratchpadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScratchpadBinding
    private lateinit var settings: Settings

    /**
     * The read this screen started.
     *
     * The reader is process-wide, so this screen only claims the states of the
     * utterance it asked for; another screen's read must not drive this
     * overlay, and a previous read's ending must not dismiss it.
     */
    private var utterance: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityScratchpadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = Settings(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        Insets.apply(top = binding.appBar, bottom = binding.controls)

        val shared = intent
            ?.takeIf { it.action == Intent.ACTION_SEND }
            ?.getStringExtra(Intent.EXTRA_TEXT)

        binding.editor.setText(
            when {
                // Text shared in from another app is appended rather than
                // dropped on top of whatever was already here.
                shared.isNullOrBlank() -> settings.scratchpad
                settings.scratchpad.isBlank() -> shared
                else -> settings.scratchpad.trimEnd() + "\n\n" + shared
            },
        )

        binding.overlayBack.setOnClickListener { Reader.skipBack() }
        binding.overlayForward.setOnClickListener { Reader.skipForward() }
        binding.overlayPause.setOnClickListener { Reader.togglePause() }
        binding.overlayStop.setOnClickListener {
            Reader.stop()
            PlaybackService.stop(this)
        }
        observeReader()

        binding.speakButton.setOnClickListener { speak() }
        binding.stopButton.setOnClickListener {
            Reader.stop()
            PlaybackService.stop(this)
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the appearance screen must restyle an overlay that
        // is already on screen, not wait for the next state change to do it.
        if (binding.readingOverlay.visibility == View.VISIBLE) applyAppearance()
    }

    override fun onPause() {
        super.onPause()
        settings.scratchpad = binding.editor.text.toString()
    }

    /** The editor stays sharp; the panel frosts only what sits behind it. */
    private fun applyAppearance() {
        binding.readingOverlay.backdrop = binding.editorArea
        binding.readingOverlay.configure(
            alpha = settings.glassAlpha,
            blurDp = settings.glassBlurDp,
            dim = settings.glassDim,
            cornerDp = settings.glassCornerDp,
            surface = Glass.surfaceColour(this),
            outline = Glass.outlineColour(this),
        )
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_CLEAR, 0, R.string.clear)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_CLEAR -> {
            binding.editor.setText("")
            settings.scratchpad = ""
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun speak() {
        val full = binding.editor.text.toString()
        // A selection in the editor means "read this bit", which is the natural
        // way to re-listen to one paragraph without deleting the rest.
        val start = binding.editor.selectionStart
        val end = binding.editor.selectionEnd
        val text = if (start in 0 until end) {
            full.substring(start, end)
        } else {
            full
        }

        if (text.isBlank()) return
        utterance = Reader.speak(this, text, treatAsMarkdown = true, source = Reader.Source.Scratchpad)
        PlaybackService.start(this)
    }

    /**
     * Reading is shown as an overlay inside this screen rather than a separate
     * window, which is what makes the frosted panel possible here.
     *
     * A floating window over another app can only be blurred by the platform's
     * cross-window blur, which most Samsung devices do not implement. An overlay
     * over our own content has no such restriction: RenderEffect blurs the view
     * beneath it on any Android 12 device.
     */
    private fun observeReader() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Reader.state.collect { state -> renderReading(state) }
            }
        }
    }

    /**
     * Marks the sentence being spoken, in the editor the user is looking at.
     *
     * A span rather than a selection: moving the selection would fight the
     * cursor and the text-selection toolbar, and following along is not the
     * same act as selecting.
     */
    private fun highlightSpoken() {
        val editable = binding.editor.text ?: return
        editable.getSpans(0, editable.length, SpokenSpan::class.java)
            .forEach { editable.removeSpan(it) }

        val range = Reader.spokenRangeIn(editable.toString()) ?: return
        val start = range.first.coerceIn(0, editable.length)
        val end = (range.last + 1).coerceIn(start, editable.length)
        if (start == end) return

        editable.setSpan(
            SpokenSpan(highlightColour),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        // Only follow if the reader has moved somewhere the user is not
        // already looking; yanking the view while they are reading ahead or
        // editing is worse than not following at all.
        if (!binding.editor.hasSelection() && !binding.editor.isFocused) {
            binding.editor.bringPointIntoView(start)
        }
    }

    private fun clearHighlight() {
        val editable = binding.editor.text ?: return
        editable.getSpans(0, editable.length, SpokenSpan::class.java)
            .forEach { editable.removeSpan(it) }
    }

    private val highlightColour: Int
        get() = ColorUtils.setAlphaComponent(
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, 0),
            HIGHLIGHT_ALPHA,
        )

    private fun renderReading(state: Reader.State) {
        // The reader is process-wide, so this screen only claims the reads that
        // began here. That used to be a flag set on the Speak button and
        // cleared when the state went Idle, which meant the overlay vanished
        // whenever one utterance handed over to the next.
        val mine = state.utterance == utterance && state.source == Reader.Source.Scratchpad
        // A failure keeps the overlay up: it is the only place the reason is
        // shown. Finishing and stopping take it away.
        val reading = mine && when (state) {
            is Reader.State.Preparing, is Reader.State.Speaking, is Reader.State.Failed -> true
            is Reader.State.Finished, is Reader.State.Stopped, Reader.State.Idle -> false
        }
        binding.readingOverlay.visibility = if (reading) View.VISIBLE else View.GONE

        if (!reading) {
            clearHighlight()
            return
        }
        highlightSpoken()
        applyAppearance()
        when (state) {
            is Reader.State.Preparing -> {
                binding.overlayStatus.setText(R.string.preparing)
                binding.overlayPause.isEnabled = false
            }

            is Reader.State.Finished, is Reader.State.Stopped -> Unit

            is Reader.State.Speaking -> {
                binding.overlayStatus.setText(R.string.reading_aloud)
                binding.overlayPause.isEnabled = true
                binding.overlayPause.setText(if (state.paused) R.string.resume else R.string.pause)
            }

            is Reader.State.Failed -> {
                binding.overlayStatus.text = getString(R.string.error_generic, state.message)
                binding.overlayPause.isEnabled = false
            }

            Reader.State.Idle -> Unit
        }
    }

    /** A background span, tagged so the previous one can be found and removed. */
    private class SpokenSpan(colour: Int) : BackgroundColorSpan(colour)

    private companion object {
        const val MENU_CLEAR = 1

        /** Enough to read as a highlight, not so much that the text fights it. */
        const val HIGHLIGHT_ALPHA = 68
    }
}
