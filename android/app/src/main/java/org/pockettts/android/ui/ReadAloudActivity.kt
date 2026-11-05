package org.pockettts.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch
import org.pockettts.android.R
import org.pockettts.android.databinding.ActivityReadAloudBinding
import org.pockettts.android.engine.Settings
import org.pockettts.android.player.PlaybackService
import org.pockettts.android.player.Reader

/**
 * The "Read aloud" entry in the text-selection toolbar lands here.
 *
 * It is a sheet about a keyboard's height over whatever app the text was
 * selected in: reading starts immediately, the passage fills the sheet with
 * the sentence being spoken highlighted, and a row of controls sits below. Dismissing it does not stop playback - the foreground service carries
 * that on - because tapping outside a popup is not a request for silence.
 */
class ReadAloudActivity : AppCompatActivity() {

    private companion object {
        const val STATE_UTTERANCE = "utterance"
        const val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

        // Text already read, text still to come, and the tint behind the
        // sentence being spoken.
        const val READ_ALPHA = 0x59
        const val AHEAD_ALPHA = 0x99
        const val HIGHLIGHT_ALPHA = 0x2E
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

    /** The range last highlighted, so a pause or resume does not rebuild the text. */
    private var highlighted: IntRange? = null

    private val voiceName by lazy { Settings(this).voice().displayName }

    private val askForNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before setContentView, so the window already carries its background
        // and blur flags the first time it is laid out.
        Glass.applyToWindow(this)
        binding = ActivityReadAloudBinding.inflate(layoutInflater)
        // The height is passed here because an inflated root without a parent
        // loses its own, and the window then stretched the sheet to half the
        // screen.
        setContentView(
            binding.root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.read_aloud_sheet_height),
            ),
        )
        extendBehindNavigationBar()

        binding.backButton.setOnClickListener { Reader.skipBack() }
        binding.forwardButton.setOnClickListener { Reader.skipForward() }
        binding.pauseButton.setOnClickListener { Reader.togglePause() }
        binding.settingsButton.setOnClickListener {
            // Into the app's own task, not the host app's this sheet sits in.
            // The read carries on; the notification has the controls.
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            finish()
        }
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

    /**
     * Runs the sheet down behind the navigation bar.
     *
     * A floating window stops at the bar by default, which left a strip of the
     * dimmed app showing between the sheet and the bottom of the screen. The
     * sheet grows by the bar's height and pads its controls up out of the way.
     */
    private fun extendBehindNavigationBar() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        window.attributes = window.attributes.apply {
            fitInsetsTypes = fitInsetsTypes and WindowInsets.Type.navigationBars().inv()
        }
        // Read from the window metrics rather than an insets listener: a
        // floating window's decor never passed the bar's inset to the content,
        // and the controls ended up under the gesture handle.
        val bar = windowManager.currentWindowMetrics.windowInsets
            .getInsets(WindowInsets.Type.navigationBars()).bottom
        binding.root.updatePadding(bottom = binding.root.paddingBottom + bar)
        binding.root.updateLayoutParams { height += bar }
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

    /**
     * The whole passage, with the sentence being spoken picked out and scrolled
     * into view.
     *
     * The text is the reader's speakable text rather than the selection as it
     * arrived, because the chunk ranges index into that: Markdown stripped,
     * code blocks dropped.
     */
    private fun highlight(state: Reader.State.Speaking) {
        val spoken = Reader.speakableText
        if (state.start !in 0..spoken.length || state.end !in state.start..spoken.length) return
        var start = state.start
        var end = state.end
        while (start < end && spoken[start].isWhitespace()) start++
        while (end > start && spoken[end - 1].isWhitespace()) end--
        if (highlighted == start until end) return
        highlighted = start until end

        val onSurface = MaterialColors.getColor(binding.preview, com.google.android.material.R.attr.colorOnSurface)
        val primary = MaterialColors.getColor(binding.preview, androidx.appcompat.R.attr.colorPrimary)
        val text = SpannableString(spoken)
        text.setSpan(ForegroundColorSpan(ColorUtils.setAlphaComponent(onSurface, READ_ALPHA)), 0, start, SPAN)
        text.setSpan(ForegroundColorSpan(ColorUtils.setAlphaComponent(onSurface, AHEAD_ALPHA)), end, spoken.length, SPAN)
        text.setSpan(BackgroundColorSpan(ColorUtils.setAlphaComponent(primary, HIGHLIGHT_ALPHA)), start, end, SPAN)
        binding.preview.text = text

        // After layout, so the line positions are for this text. One line of
        // what was just read stays above the sentence, for context.
        binding.preview.post {
            val layout = binding.preview.layout ?: return@post
            val line = (layout.getLineForOffset(start) - 1).coerceAtLeast(0)
            binding.previewScroll.smoothScrollTo(0, layout.getLineTop(line))
        }
    }

    /**
     * Material refuses to turn an indicator indeterminate while it is shown, and
     * a skip takes this one from a sentence count back to preparing.
     */
    private fun LinearProgressIndicator.showIndeterminate() {
        if (isIndeterminate && visibility == View.VISIBLE) return
        visibility = View.INVISIBLE
        isIndeterminate = true
        visibility = View.VISIBLE
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
                if (state.fraction < 0) {
                    binding.progress.showIndeterminate()
                    binding.status.setText(R.string.downloading_unknown)
                } else if (state.fraction == 0f) {
                    binding.progress.showIndeterminate()
                    binding.status.setText(R.string.preparing)
                } else {
                    binding.progress.visibility = View.VISIBLE
                    binding.progress.isIndeterminate = false
                    binding.progress.progress = (state.fraction * 100).toInt()
                    binding.status.text =
                        getString(R.string.downloading, (state.fraction * 100).toInt())
                }
                binding.pauseButton.isEnabled = false
            }

            is Reader.State.Speaking -> {
                if (state.audible) {
                    binding.progress.visibility = View.VISIBLE
                    binding.progress.isIndeterminate = false
                    binding.progress.progress = (state.chunkIndex + 1) * 100 / state.chunkCount.coerceAtLeast(1)
                } else {
                    binding.progress.showIndeterminate()
                }
                binding.status.text = when {
                    !state.audible -> getString(R.string.composing)
                    state.paused -> getString(R.string.voice_paused, voiceName)
                    else -> getString(R.string.voice_and_sentence, voiceName, state.chunkIndex + 1, state.chunkCount)
                }
                highlight(state)
                binding.pauseButton.isEnabled = true
                binding.pauseButton.setImageResource(if (state.paused) R.drawable.ic_play else R.drawable.ic_pause)
                binding.pauseButton.contentDescription =
                    getString(if (state.paused) R.string.resume else R.string.pause)
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
