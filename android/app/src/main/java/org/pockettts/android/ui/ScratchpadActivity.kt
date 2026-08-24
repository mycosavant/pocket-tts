package org.pockettts.android.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
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
    private val markwon: Markwon by lazy { Markwon.create(this) }
    private var showingPreview = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScratchpadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = Settings(this)

        setSupportActionBar(binding.toolbar)

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

        binding.speakButton.setOnClickListener { speak() }
        binding.stopButton.setOnClickListener {
            Reader.stop()
            PlaybackService.stop(this)
        }
    }

    override fun onPause() {
        super.onPause()
        settings.scratchpad = binding.editor.text.toString()
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
        Reader.speak(this, text, treatAsMarkdown = true)
        PlaybackService.start(this)
    }

    private companion object {
        const val MENU_PREVIEW = 1
        const val MENU_CLEAR = 2
    }
}
