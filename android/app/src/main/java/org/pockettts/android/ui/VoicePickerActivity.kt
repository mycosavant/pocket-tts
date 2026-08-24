package org.pockettts.android.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.pockettts.android.R
import org.pockettts.android.databinding.ActivityVoicePickerBinding
import org.pockettts.android.databinding.ItemVoiceBinding
import org.pockettts.android.engine.ModelManager
import org.pockettts.android.engine.Settings
import org.pockettts.android.engine.VoiceCatalog
import java.util.Locale

/**
 * Picks the voice used for reading, and imports cloned ones.
 *
 * Also serves as the engine's settings screen: Android's text-to-speech
 * settings link here from the gear next to the engine name.
 */
class VoicePickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVoicePickerBinding
    private lateinit var settings: Settings
    private lateinit var adapter: VoiceAdapter

    private val pickWav = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { importVoice(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityVoicePickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = Settings(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        Insets.apply(top = binding.appBar, bottom = binding.root)

        adapter = VoiceAdapter(entries(), settings.voiceId) { id ->
            settings.voiceId = id
            adapter.select(id)
        }
        binding.voiceList.layoutManager = LinearLayoutManager(this)
        binding.voiceList.adapter = adapter

        binding.importButton.setOnClickListener {
            pickWav.launch(arrayOf("audio/wav", "audio/x-wav", "audio/*"))
        }
    }

    private data class Entry(val id: String, val name: String, val detail: String)

    private fun entries(): List<Entry> {
        val stock = VoiceCatalog.voices.map { voice ->
            Entry(
                voice.id,
                voice.displayName,
                Locale.forLanguageTag(voice.language).displayName,
            )
        }
        val imported = ModelManager(this).importedVoices().map { file ->
            Entry(file.nameWithoutExtension, file.nameWithoutExtension, getString(R.string.import_voice))
        }
        return stock + imported
    }

    private fun importVoice(uri: Uri) {
        val result = runCatching {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Could not open that file")
            val name = (uri.lastPathSegment ?: "voice")
                .substringAfterLast('/')
                .substringBeforeLast('.')
                .replace(Regex("""[^A-Za-z0-9_-]"""), "_")
                .ifBlank { "voice" }
            ModelManager(this).importVoice(name, bytes)
            name
        }

        result.onSuccess { name ->
            Toast.makeText(this, getString(R.string.imported_voice, name), Toast.LENGTH_SHORT).show()
            settings.voiceId = name
            adapter.replace(entries(), name)
        }.onFailure { error ->
            Toast.makeText(
                this,
                getString(R.string.voice_import_failed, error.message ?: ""),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private class VoiceAdapter(
        private var items: List<Entry>,
        private var selectedId: String,
        private val onClick: (String) -> Unit,
    ) : RecyclerView.Adapter<VoiceAdapter.Holder>() {

        class Holder(val binding: ItemVoiceBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            ItemVoiceBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.binding.name.text = item.name
            holder.binding.language.text = item.detail
            holder.binding.selected.isChecked = item.id == selectedId
            holder.binding.root.setOnClickListener { onClick(item.id) }
        }

        fun select(id: String) {
            selectedId = id
            notifyDataSetChanged()
        }

        fun replace(newItems: List<Entry>, id: String) {
            items = newItems
            selectedId = id
            notifyDataSetChanged()
        }
    }
}
