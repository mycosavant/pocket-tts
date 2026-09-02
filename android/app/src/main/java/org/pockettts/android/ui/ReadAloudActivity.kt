package org.pockettts.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

    private companion object {
        const val STATE_UTTERANCE = "utterance"
    }

    private lateinit var binding: ActivityReadAloudBinding

    /**
     * The read this window started.
     *
     * The reader is process-wide and its state outlives any one utterance, so
     * without this the window would act on the *previous* read's ending - which
     * meant closing itself before its own read had begun.
     */
    private var utterance: Long = 0

    private val askForNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before setContentView, so the window already carries its background
        // and blur flags the first time it is laid out.
        Glass.applyToWindow(this)
        binding = ActivityReadAloudBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { Reader.skipBack() }
        binding.forwardButton.setOnClickListener { Reader.skipForward() }
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

        // Only on a genuine start. A configuration change recreates the
        // activity with the same intent, and handling it again stopped the read
        // and began it from the first word - rotating the phone mid-sentence
        // started the paragraph over.
        if (savedInstanceState == null) {
            handleIntent(intent)
        } else {
            utterance = savedInstanceState.getLong(STATE_UTTERANCE)
            binding.preview.text = extractText(intent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Carried across so the recreated window still recognises its own read
        // among the states of a reader the whole process shares.
        outState.putLong(STATE_UTTERANCE, utterance)
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

        // Text selected inside our own scratchpad arrives here too: the
        // PROCESS_TEXT item this app registers appears in the selection toolbar
        // of its own editor. Opening a floating window over the screen that
        // already has controls for this gave two sets of controls for one
        // utterance, in two different treatments. Tag the read and step aside;
        // the scratchpad shows its own overlay.
        val fromOurselves = callingPackage == packageName
        utterance = Reader.speak(
            this,
            text,
            treatAsMarkdown = settings.treatSelectionAsMarkdown,
            source = if (fromOurselves) Reader.Source.Scratchpad else Reader.Source.Selection,
        )
        if (fromOurselves) {
            PlaybackService.start(this)
            finish()
            return
        }
        ensureNotificationsAllowed()
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

    /**
     * Asked for once the sheet is really being shown.
     *
     * Someone whose first contact with this app is selecting text in another
     * one gets a foreground service and, on Android 13+, no notification to
     * control it with. Asked here rather than at the start, so the prompt has
     * some context around it, and never on the path that hands the read
     * straight to the scratchpad.
     */
    private fun ensureNotificationsAllowed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** The text of the chunk currently being spoken, if it can be located. */
    private fun sentenceOf(state: Reader.State.Speaking): String? {
        val spoken = Reader.speakableText
        if (state.start !in 0..spoken.length || state.end !in state.start..spoken.length) return null
        return spoken.substring(state.start, state.end).trim().ifBlank { null }
    }

    private fun observeReader() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Reader.state.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: Reader.State) {
        // Anything that is not this window's own read is somebody else's news.
        if (state.utterance != utterance) return
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
                binding.status.setText(
                    if (state.audible) R.string.reading_aloud else R.string.composing,
                )
                // The sentence being spoken, not the whole passage: a preview
                // that never moves says nothing about where the reader is.
                sentenceOf(state)?.let { binding.preview.text = it }
                binding.pauseButton.isEnabled = true
                binding.pauseButton.setText(if (state.paused) R.string.resume else R.string.pause)
            }

            is Reader.State.Failed -> {
                binding.progress.visibility = View.GONE
                binding.status.text = getString(R.string.error_generic, state.message)
                binding.pauseButton.isEnabled = false
            }

            // The read is over, so the window has done its job. A failure is
            // the exception: it stays up, because it is the only place the
            // reason is shown.
            is Reader.State.Finished, is Reader.State.Stopped -> {
                binding.progress.visibility = View.GONE
                binding.pauseButton.isEnabled = false
                if (!isFinishing) finish()
            }

            Reader.State.Idle -> {
                binding.progress.visibility = View.GONE
                binding.pauseButton.isEnabled = false
            }
        }
    }
}
