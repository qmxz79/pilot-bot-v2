package com.qmxz.pilotbotv2.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Owns transient, exclusive audio focus for companion speech.
 *
 * - Requested with AUDIOFOCUS_GAIN_TRANSIENT before every utterance batch and
 *   released when the batch finishes, is interrupted, or fails — so music and
 *   navigation are never muted longer than the companion is actually speaking.
 * - AUDIOFOCUS_LOSS / LOSS_TRANSIENT means another app (call, nav) took over:
 *   the caller stops speaking immediately instead of talking over it.
 * - USAGE_MEDIA + CONTENT_TYPE_SPEECH is used deliberately: it is understood
 *   on every API level back to minSdk 26, unlike newer usages.
 */
class AudioFocusManager(
    context: Context,
    private val onFocusLost: () -> Unit
) {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onFocusLost()
                    // GAIN / LOSS_TRANSIENT_CAN_DUCK: nothing to do; we hold
                    // transient focus only while speaking, so ducking is moot.
                    else -> Unit
                }
            }
            .build()

    /** Returns true if focus was granted. Callers speak anyway on denial — better
     *  to be heard than to fail silently — but diagnostics record the denial. */
    fun request(): Boolean {
        return try {
            audioManager.requestAudioFocus(focusRequest) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    fun abandon() {
        try {
            audioManager.abandonAudioFocusRequest(focusRequest)
        } catch (_: Exception) {
        }
    }
}
