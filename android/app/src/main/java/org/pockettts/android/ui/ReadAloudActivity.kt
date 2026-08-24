package org.pockettts.android.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.pockettts.android.R
import org.pockettts.android.databinding.ActivityReadAloudBinding
import org.pockettts.android.engine.Settings
import org.pockettts.android.player.PlaybackService
import org.pockettts.android.player.Reader

/**
 * The "Read aloud" entry in the text-selection toolbar lands here.
 *
 * It is a small floating window over whatever app the text was selected in:
 * reading starts immediately, and the window exists only to offer pause and
 * stop. Dismissing it does not stop playback - the foreground service carries
 * that on - because tapping outside a popup is not a request for silence.
 */
class ReadAloudActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReadAloudBinding

    /**
     * The reader starts out Idle, and Idle is also how a finished utterance
     * looks. Only the second one should close the window, so wait until the
     * reader has actually left Idle before treating it as "done".
     */
    private var readingBegan = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before setContentView, so the window already carries its background
        // and blur flags the first time it is laid out.
        Glass.applyToWindow(this)
        binding = ActivityReadAloudBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.pauseButton.setOnClickListener { Reader.togglePause() }
        binding.stopButton.setOnClickListener {
            Reader.stop()
            PlaybackService.stop(this)
            finish()
        }
        binding.sendToScratchpad.setOnClickListener {
            startActivity(
                Intent(this, ScratchpadActivity::class.java)
                    .setAction(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, extractText(intent)),
            )
            finish()
        }

        observeReader()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val text = extractText(intent)
        if (text.isBlank()) {
            Toast.makeText(this, R.string.nothing_to_read, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.preview.text = text
        val settings = Settings(this)
        Reader.speak(this, text, treatAsMarkdown = settings.treatSelectionAsMarkdown)
        PlaybackService.start(this)
    }

    private fun extractText(intent: Intent?): String {
        intent ?: return ""
        return when (intent.action) {
            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()

            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            else -> ""
        }
    }

    private fun observeReader() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Reader.state.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: Reader.State) {
        if (state !is Reader.State.Idle) readingBegan = true
        when (state) {
            is Reader.State.Preparing -> {
                binding.progress.visibility = View.VISIBLE
                if (state.fraction < 0) {
                    binding.progress.isIndeterminate = true
                    binding.status.setText(R.string.downloading_unknown)
                } else if (state.fraction == 0f) {
                    binding.progress.isIndeterminate = true
                    binding.status.setText(R.string.preparing)
                } else {
                    binding.progress.isIndeterminate = false
                    binding.progress.progress = (state.fraction * 100).toInt()
                    binding.status.text =
                        getString(R.string.downloading, (state.fraction * 100).toInt())
                }
                binding.pauseButton.isEnabled = false
            }

            is Reader.State.Speaking -> {
                binding.progress.visibility = View.GONE
                binding.status.setText(R.string.reading_aloud)
                binding.pauseButton.isEnabled = true
                binding.pauseButton.setText(if (state.paused) R.string.resume else R.string.pause)
            }

            is Reader.State.Failed -> {
                binding.progress.visibility = View.GONE
                binding.status.text = getString(R.string.error_generic, state.message)
                binding.pauseButton.isEnabled = false
            }

            Reader.State.Idle -> {
                binding.progress.visibility = View.GONE
                binding.pauseButton.isEnabled = false
                if (readingBegan && !isFinishing) finish()
            }
        }
    }
}
