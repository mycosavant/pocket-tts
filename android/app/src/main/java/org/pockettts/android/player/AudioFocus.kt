package org.pockettts.android.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Asks the system for the right to make a sound, and gives it back.
 *
 * Nothing did this before, and the result was an app that read over the top of
 * whatever was already playing. Podcasts kept going underneath; an incoming
 * call did not pause the reading, because muting the media stream during a call
 * is at the vendor's discretion and several do not; pulling out headphones
 * carried on aloud from the phone's speaker, which is the one failure of the
 * three that is actively embarrassing.
 *
 * All platform APIs. The alternative - `MediaSessionCompat` for the same
 * behaviour plus lock-screen controls - lives in `androidx.media`, and this
 * project spends dependencies carefully.
 */
class AudioFocus(private val context: Context) {

    private val manager: AudioManager? =
        context.getSystemService(AudioManager::class.java)

    private var request: AudioFocusRequest? = null
    private var noisyReceiver: BroadcastReceiver? = null

    /**
     * Whether focus was lost for good rather than for a moment.
     *
     * A transient loss - a navigation prompt, a notification - is worth
     * resuming from. A permanent one means another app has taken over for as
     * long as it likes, and continuing to wait would leave a paused reader
     * nobody asked for.
     */
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.i(TAG, "Focus lost; stopping")
                Reader.stop()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> Reader.pause()

            AudioManager.AUDIOFOCUS_GAIN -> Reader.resume()
        }
    }

    /** @return false when the system refused, in which case nothing should play. */
    fun request(): Boolean {
        val audio = manager ?: return true
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(listener)
            // Ducking a spoken passage under something else leaves two voices
            // competing, and neither is intelligible. Pause instead.
            .setWillPauseWhenDucked(true)
            .build()
        request = focus

        registerNoisyReceiver()
        return audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun release() {
        request?.let { manager?.abandonAudioFocusRequest(it) }
        request = null
        noisyReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        noisyReceiver = null
    }

    /**
     * Headphones or Bluetooth disconnecting means the sound is about to come
     * out of the phone's own speaker, which is never what the listener wanted.
     */
    private fun registerNoisyReceiver() {
        if (noisyReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    Log.i(TAG, "Output became noisy; pausing")
                    Reader.pause()
                }
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
        )
        noisyReceiver = receiver
    }

    private companion object {
        const val TAG = "AudioFocus"
    }
}
