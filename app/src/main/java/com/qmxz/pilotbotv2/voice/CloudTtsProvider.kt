package com.qmxz.pilotbotv2.voice

/**
 * Interface for a future cloud emotional-TTS vendor (e.g. Volcano/Doubao, iFlytek).
 *
 * Deliberately NOT implemented yet: v2 ships with [SystemTtsProvider] as the
 * default so the app is usable on day one, and a cloud vendor plugs in here
 * without touching the pipeline. A cloud implementation streams audio bytes
 * (PCM/Opus) as they arrive; the caller plays them with AudioTrack and must
 * support immediate cancellation for barge-in, exactly like [TtsProvider.stop].
 *
 * Streaming contract:
 * - onChunk is called on a background thread, in arrival order.
 * - onDone is called exactly once after the last chunk (or immediately for empty text).
 * - onError is called instead of onDone on failure, with a user-readable message.
 * - cancel() must stop any in-flight request and suppress further callbacks.
 */
interface CloudTtsProvider {
    /** Display name, e.g. "火山引擎情感语音". Shown in diagnostics. */
    val providerName: String

    /** True only when base URL + key + voice id are all configured. */
    fun isConfigured(): Boolean

    /** Start streaming synthesis of [text]. */
    fun synthesize(
        text: String,
        onChunk: (ByteArray) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    )

    /** Cancel the current synthesis, if any. */
    fun cancel()
}
