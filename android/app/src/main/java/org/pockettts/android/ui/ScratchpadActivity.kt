package org.pockettts.android.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import io.noties.markwon.Markwon
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
    private val markwon: Markwon by lazy { Markwon.create(this) }
    private var showingPreview = false

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
        menu.add(0, MENU_PREVIEW, 0, R.string.preview).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, MENU_CLEAR, 1, R.string.clear)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_PREVIEW -> {
            togglePreview()
            item.setTitle(if (showingPreview) R.string.edit else R.string.preview)
            true
        }

        MENU_CLEAR -> {
            binding.editor.setText("")
            settings.scratchpad = ""
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun togglePreview() {
        showingPreview = !showingPreview
        if (showingPreview) {
            markwon.setMarkdown(binding.previewText, binding.editor.text.toString())
            binding.editor.visibility = View.GONE
            binding.previewScroll.visibility = View.VISIBLE
        } else {
            binding.previewScroll.visibility = View.GONE
            binding.editor.visibility = View.VISIBLE
        }
    }

    private fun speak() {
        val full = binding.editor.text.toString()
        // A selection in the editor means "read this bit", which is the natural
        // way to re-listen to one paragraph without deleting the rest.
        val start = binding.editor.selectionStart
        val end = binding.editor.selectionEnd
        val text = if (!showingPreview && start in 0 until end) {
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

        if (!reading) return
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

    private companion object {
        const val MENU_PREVIEW = 1
        const val MENU_CLEAR = 2
    }
}
