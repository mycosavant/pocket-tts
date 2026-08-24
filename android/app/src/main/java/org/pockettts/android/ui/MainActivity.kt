package org.pockettts.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pockettts.android.R
import org.pockettts.android.databinding.ActivityMainBinding
import org.pockettts.android.debug.CrashLog
import org.pockettts.android.debug.ExitReasons
import org.pockettts.android.engine.ModelManager
import org.pockettts.android.engine.Settings
import org.pockettts.android.engine.VoiceCatalog

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: Settings
    private var download: Job? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        Insets.apply(top = binding.appBar, bottom = binding.content)
        settings = Settings(this)

        binding.downloadButton.setOnClickListener { startDownload() }
        binding.chooseVoiceButton.setOnClickListener {
            startActivity(Intent(this, VoicePickerActivity::class.java))
        }
        binding.appearanceButton.setOnClickListener {
            startActivity(Intent(this, AppearanceActivity::class.java))
        }
        binding.scratchpadButton.setOnClickListener {
            startActivity(Intent(this, ScratchpadActivity::class.java))
        }
        binding.ttsSettingsButton.setOnClickListener {
            // No public constant for this; it is the settings screen where a
            // user picks their preferred engine.
            runCatching {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            }.onFailure {
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        binding.speedSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                settings.speed = value
                binding.speedLabel.text = getString(R.string.speed, value)
            }
        }
        binding.markdownSwitch.setOnCheckedChangeListener { _, checked ->
            settings.treatSelectionAsMarkdown = checked
        }
        binding.codeBlocksSwitch.setOnCheckedChangeListener { _, checked ->
            settings.speakCodeBlocks = checked
        }

        requestNotificationPermission()
    }

    /**
     * Without this the playback service still runs, but its notification - and
     * with it the only pause and stop controls once the floating window is
     * gone - is silently dropped on Android 13 and later.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        offerLastCrash()
    }

    /**
     * A sideloaded app that dies has no logcat anyone can reach, so the evidence
     * is offered here instead. Shown once and then cleared.
     *
     * Two sources, because they see different failures. `CrashLog` catches
     * exceptions that unwind through the JVM; `ExitReasons` reads the platform's
     * own record, which is the only thing that sees a native crash, an ANR or a
     * low-memory kill. The platform record wins when both exist - it names the
     * kind of death, which is the part that decides what to do next.
     */
    private fun offerLastCrash() {
        val exit = ExitReasons.lastInterestingExit(this)
        val crash = CrashLog.lastCrash(this)
        val report = when {
            exit != null && crash != null -> "${exit.detail}\n\n--- java stack trace ---\n$crash"
            exit != null -> exit.detail
            crash != null -> crash
            else -> return
        }
        val title = if (exit != null) R.string.exit_title else R.string.crash_title

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(report.lineSequence().take(CRASH_PREVIEW_LINES).joinToString("\n"))
            .setPositiveButton(R.string.crash_share) { _, _ ->
                CrashLog.clear(this)
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.crash_title))
                            .putExtra(Intent.EXTRA_TEXT, report),
                        getString(R.string.crash_share),
                    ),
                )
            }
            .setNegativeButton(R.string.crash_dismiss) { _, _ -> CrashLog.clear(this) }
            .show()
    }

    private fun refresh() {
        val voice = VoiceCatalog.byId(settings.voiceId)
        binding.voiceLabel.text = voice?.displayName ?: settings.voiceId
        binding.speedSlider.value = settings.speed
        binding.speedLabel.text = getString(R.string.speed, settings.speed)
        binding.markdownSwitch.isChecked = settings.treatSelectionAsMarkdown
        binding.codeBlocksSwitch.isChecked = settings.speakCodeBlocks

        val installed = ModelManager(this).isModelInstalled
        binding.modelStatus.setText(if (installed) R.string.model_ready else R.string.model_missing)
        binding.downloadButton.isEnabled = !installed && download?.isActive != true
    }

    private fun startDownload() {
        if (download?.isActive == true) return
        binding.downloadButton.isEnabled = false
        binding.downloadProgress.visibility = android.view.View.VISIBLE

        download = lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    ModelManager(this@MainActivity).ensureModel { fraction ->
                        lifecycleScope.launch {
                            if (fraction < 0) {
                                binding.downloadProgress.isIndeterminate = true
                                binding.modelStatus.setText(R.string.downloading_unknown)
                            } else {
                                binding.downloadProgress.isIndeterminate = false
                                binding.downloadProgress.progress = (fraction * 100).toInt()
                                binding.modelStatus.text =
                                    getString(R.string.downloading, (fraction * 100).toInt())
                            }
                        }
                    }
                }
            }
            binding.downloadProgress.visibility = android.view.View.GONE
            result.onFailure {
                binding.modelStatus.text =
                    getString(R.string.error_generic, it.message ?: it.javaClass.simpleName)
            }
            refresh()
        }
    }

    private companion object {
        /** Enough of the report to recognise the failure without a wall of text. */
        const val CRASH_PREVIEW_LINES = 14
    }
}
