package com.qmxz.pilotbotv2.voice

/**
 * Callbacks for one spoken sentence. Delivered on the main thread by the pipeline.
 */
interface TtsListener {
    fun onStart(utteranceId: String)
    fun onDone(utteranceId: String)
    fun onError(utteranceId: String, message: String)
}

/**
 * Minimal contract any TTS engine (system or cloud) must satisfy.
 *
 * v1 lesson: the app silently showed text when TTS was unavailable. Every
 * implementation must therefore report availability honestly, and every
 * failure must surface as a user-readable message — never silence.
 */
interface TtsProvider {
    /** True only if the engine is initialised AND can speak the target language. */
    fun isAvailable(): Boolean

    /** Human-readable availability state, e.g. "系统语音可用" or why it is not. */
    fun availabilityMessage(): String

    /** Speak one sentence. Callbacks go to [listener] with the given [utteranceId]. */
    fun speak(sentence: String, utteranceId: String, listener: TtsListener)

    /** Stop the current utterance immediately. Late callbacks after stop() are dropped. */
    fun stop()

    /** Release the engine. Safe to call repeatedly. */
    fun shutdown()
}
