package org.pockettts.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pockettts.android.R
import org.pockettts.android.databinding.ActivityMainBinding
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
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = Settings(this)

        binding.downloadButton.setOnClickListener { startDownload() }
        binding.chooseVoiceButton.setOnClickListener {
            startActivity(Intent(this, VoicePickerActivity::class.java))
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
}
