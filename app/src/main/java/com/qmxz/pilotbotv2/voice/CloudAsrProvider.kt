package com.qmxz.pilotbotv2.voice

/**
 * Interface for a future cloud streaming-ASR vendor (e.g. iFlytek, Whisper).
 *
 * Not implemented yet: v2 ships with [SystemAsrProvider] as the default.
 * A cloud implementation streams microphone audio to the vendor and delivers
 * partial + final transcripts through the same [AsrListener] shape, so the
 * pipeline does not change when it lands. It must also support immediate
 * cancellation, because barge-in depends on stopping recognition instantly.
 */
interface CloudAsrProvider {
    /** Display name, e.g. "讯飞流式识别". Shown in diagnostics. */
    val providerName: String

    /** True only when base URL + key are configured. */
    fun isConfigured(): Boolean

    /**
     * Start streaming recognition. Audio is captured by the implementation
     * itself (it owns the AudioRecord session while running).
     */
    fun startStreaming(listener: AsrListener)

    /** Stop streaming recognition immediately and discard partial results. */
    fun stopStreaming()
}
