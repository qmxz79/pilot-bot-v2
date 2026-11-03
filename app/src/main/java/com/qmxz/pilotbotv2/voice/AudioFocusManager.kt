package com.qmxz.pilotbotv2.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Owns transient, exclusive audio focus for companion speech, and yields it
 * when another app needs to be heard. This is what makes the companion
 * "cooperate" with Amap navigation instead of talking over it:
 *
 * - While the companion speaks or listens it holds AUDIOFOCUS_GAIN_TRANSIENT.
 *   When Amap starts an announcement it requests transient focus for itself,
 *   and the system delivers LOSS_TRANSIENT_CAN_DUCK (or LOSS_TRANSIENT) to
 *   us. That is [onYield]: the pipeline pauses speech and stops VAD at once,
 *   so Amap is heard cleanly and its words are not mistaken for the user.
 * - When Amap releases focus we get AUDIOFOCUS_GAIN: [onResume] replays the
 *   interrupted sentence from the start and continues the queue (or
 *   restarts listening, if the yield hit a listening turn).
 * - AUDIOFOCUS_LOSS is permanent (a call took over, music started): the
 *   pipeline stops and does not auto-resume ([onFocusLost]).
 *
 * Focus is held only while speaking or listening, never while idle, so
 * music and navigation are never muted longer than a live turn lasts.
 *
 * Known limit: callbacks only arrive while the companion holds focus. If a
 * focus request was denied (another app already holds permanent focus) the
 * companion speaks anyway — the existing fail-loud policy — and a nav
 * announcement during that window will not pause it.
 *
 * Callbacks are posted to the main thread: the pipeline is single-threaded
 * by construction and its public methods must run there.
 */
class AudioFocusManager(
    context: Context,
    private val onFocusLost: () -> Unit,
    private val onYield: () -> Unit = {},
    private val onResume: () -> Unit = {}
) {
    private val mainHandler = Handler(Looper.getMainLooper())

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
                    AudioManager.AUDIOFOCUS_LOSS ->
                        mainHandler.post { onFocusLost() }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                        mainHandler.post { onYield() }
                    AudioManager.AUDIOFOCUS_GAIN ->
                        mainHandler.post { onResume() }
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
