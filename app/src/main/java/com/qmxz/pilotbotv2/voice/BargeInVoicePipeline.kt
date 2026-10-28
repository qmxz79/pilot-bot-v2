package com.qmxz.pilotbotv2.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.qmxz.pilotbotv2.llm.SentenceSplitter

/**
 * Callbacks from the pipeline to the UI layer. All are delivered on the main thread.
 */
interface VoicePipelineListener {
    /** A speech batch started (mic VAD armed, focus requested). */
    fun onSpeakingStarted()

    /** The current batch finished, was interrupted, or failed (see [onError]). */
    fun onSpeakingFinished()

    /** One sentence started playing. */
    fun onSentenceSpoken(sentence: String)

    /** Speech was interrupted by the user (button or voice detection). */
    fun onInterrupted()

    /** Live partial ASR transcript (display only). */
    fun onPartialResult(text: String)

    /** Final ASR transcript — the only result the app acts on. */
    fun onFinalResult(text: String)

    /** User-readable failure. Speech continues where possible; this is a note, not a crash. */
    fun onError(message: String)
}

/** Result of [BargeInVoicePipeline.selfCheck]. */
data class VoiceHealth(
    val ttsOk: Boolean,
    val ttsMessage: String,
    val asrOk: Boolean,
    val asrMessage: String
)

enum class PipelineState { IDLE, SPEAKING, LISTENING }

/**
 * The core of v2: a barge-in voice pipeline.
 *
 * v1 was half-duplex — the mic paused while the companion spoke, so the user
 * could never cut in. v2 fixes that with three mechanisms:
 *
 * 1. Sentence-level TTS queue: replies are spoken sentence by sentence, so
 *    [interrupt] stops speech within one sentence, not at the end of a paragraph.
 * 2. VAD during speech: while TTS plays, [VadDetector] watches the mic. When
 *    sustained human voice is detected, TTS is interrupted and ASR starts
 *    automatically. (Limitation: the first syllables spoken before detection
 *    may be missed — the user naturally repeats or continues; the interrupt
 *    button covers deliberate cut-ins with zero loss.)
 * 3. Fail-loud everywhere: TTS/ASR/VAD failures surface as messages, never as
 *    a companion that silently stops talking.
 *
 * Threading: every public method is expected on the main thread, and every
 * callback is re-posted to the main thread. TTS callbacks arrive on binder
 * threads and VAD callbacks on a worker thread, so all state changes funnel
 * through [mainHandler]. This makes the state machine single-threaded by
 * construction — the class of lifecycle races that crashed v1 cannot happen here.
 *
 * Mic ownership, strictly sequenced (both want the mic):
 * - SPEAKING: VAD owns the mic, ASR is stopped.
 * - LISTENING: ASR owns the mic, VAD is stopped.
 * Never both at once.
 */
class BargeInVoicePipeline(
    context: Context,
    private val tts: TtsProvider,
    private val asr: AsrProvider,
    private val focus: AudioFocusManager,
    private val vad: VadDetector
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    var listener: VoicePipelineListener? = null
    var state: PipelineState = PipelineState.IDLE
        private set

    private val queue = ArrayDeque<String>()

    /** Invalidates in-flight TTS callbacks (stop() may still deliver late ones). */
    private var batchId: Int = 0
    private var utteranceSeq: Int = 0

    /** True while an LLM stream may still deliver more sentences. */
    private var streaming: Boolean = false

    /** True when the speaker is idle only because it awaits streamed sentences. */
    private var waitingForSentences: Boolean = false

    /** VAD failure is reported once per pipeline lifetime (see beginSpeaking). */
    private var vadFailureNoted: Boolean = false

    private var released: Boolean = false

    // ------------------------------------------------------------------ speech

    /** Speak [text] immediately, replacing anything currently spoken. */
    fun speakText(text: String) {
        if (released) return
        if (state == PipelineState.LISTENING) {
            asr.stopListening()
        }
        if (state == PipelineState.SPEAKING) {
            // Silent teardown of the replaced batch (no onInterrupted: this
            // is a replacement, not a user interruption).
            batchId++
            queue.clear()
            try {
                tts.stop()
            } catch (_: Exception) {
            }
            vad.stop()
            focus.abandon()
        }
        val sentences = SentenceSplitter.split(text)
        batchId++
        queue.clear()
        streaming = false
        waitingForSentences = false
        if (sentences.isEmpty()) {
            state = PipelineState.IDLE
            listener?.onSpeakingFinished()
            return
        }
        queue.addAll(sentences)
        beginSpeaking()
        speakNext()
    }

    /**
     * Append one sentence to the current batch (LLM streaming path).
     * If nothing is playing, this starts a batch so the first streamed
     * sentence is heard immediately instead of waiting for the full reply.
     */
    fun queueSentence(sentence: String) {
        if (released) return
        val clean = SentenceSplitter.sanitizeForSpeech(sentence)
        if (clean.isBlank()) return
        queue.addLast(clean)
        when (state) {
            PipelineState.IDLE -> {
                // Streaming callers must call beginStream() first; a lone
                // queued sentence must drain and finish on its own, not hang
                // waiting for stream sentences that will never arrive.
                batchId++
                beginSpeaking()
                speakNext()
            }
            PipelineState.SPEAKING -> {
                if (waitingForSentences) {
                    waitingForSentences = false
                    speakNext()
                }
            }
            PipelineState.LISTENING -> {
                // A reply arriving while listening means the user was already
                // answered elsewhere; drop it rather than talking over them.
                queue.removeLast()
            }
        }
    }

    /** The LLM stream may still deliver sentences (see [queueSentence]). */
    fun beginStream() {
        streaming = true
    }

    /** The LLM stream ended; drain the queue and then finish normally. */
    fun endStream() {
        streaming = false
        if (state == PipelineState.SPEAKING && queue.isEmpty() && waitingForSentences) {
            finishSpeaking()
        }
    }

    private fun beginSpeaking() {
        state = PipelineState.SPEAKING
        focus.request()
        vad.start(
            onVoice = { mainHandler.post { handleVadTrigger() } },
            onAmplitude = { /* reserved for a future mic meter */ },
            onError = { message ->
                mainHandler.post {
                    // Non-fatal: speech continues, the interrupt button still
                    // works. Reported once per pipeline lifetime so a missing
                    // mic permission does not spam one note per reply.
                    if (!vadFailureNoted) {
                        vadFailureNoted = true
                        listener?.onError(message)
                    }
                }
            }
        )
        listener?.onSpeakingStarted()
    }

    private fun speakNext() {
        if (released || state != PipelineState.SPEAKING) return
        if (queue.isEmpty()) {
            if (streaming) {
                waitingForSentences = true
            } else {
                finishSpeaking()
            }
            return
        }
        waitingForSentences = false
        val sentence = queue.removeFirst()
        val batch = batchId
        val utteranceId = "u${++utteranceSeq}"
        tts.speak(sentence, utteranceId, object : TtsListener {
            override fun onStart(id: String) {
                mainHandler.post {
                    if (batch == batchId && state == PipelineState.SPEAKING) {
                        listener?.onSentenceSpoken(sentence)
                    }
                }
            }

            override fun onDone(id: String) {
                mainHandler.post {
                    if (batch == batchId) speakNext()
                }
            }

            override fun onError(id: String, message: String) {
                mainHandler.post {
                    if (batch == batchId) {
                        listener?.onError(message)
                        finishSpeaking()
                    }
                }
            }
        })
    }

    private fun finishSpeaking() {
        if (state != PipelineState.SPEAKING) return
        state = PipelineState.IDLE
        waitingForSentences = false
        streaming = false
        vad.stop()
        focus.abandon()
        listener?.onSpeakingFinished()
    }

    /** Stop speech immediately. Safe to call in any state. */
    fun interrupt() {
        if (released) return
        if (state != PipelineState.SPEAKING && queue.isEmpty()) return
        batchId++ // invalidate late TTS callbacks
        queue.clear()
        streaming = false
        waitingForSentences = false
        try {
            tts.stop()
        } catch (_: Exception) {
        }
        vad.stop()
        focus.abandon()
        state = PipelineState.IDLE
        listener?.onInterrupted()
    }

    private fun handleVadTrigger() {
        if (released || state != PipelineState.SPEAKING) return
        // User started talking over the companion: stop speech, then listen.
        // interrupt() notifies onInterrupted; startListening() follows immediately.
        interrupt()
        startListening()
    }

    // --------------------------------------------------------------- listening

    /** Start one ASR session. Interrupts speech first if necessary. */
    fun startListening() {
        if (released) return
        if (state == PipelineState.SPEAKING) {
            interrupt()
        }
        if (state == PipelineState.LISTENING) return
        state = PipelineState.LISTENING
        asr.startListening(object : AsrListener {
            override fun onPartial(text: String) {
                mainHandler.post {
                    if (state == PipelineState.LISTENING) {
                        listener?.onPartialResult(text)
                    }
                }
            }

            override fun onFinal(text: String) {
                mainHandler.post {
                    if (state == PipelineState.LISTENING) {
                        state = PipelineState.IDLE
                        listener?.onFinalResult(text)
                    }
                }
            }

            override fun onError(message: String, recoverable: Boolean) {
                mainHandler.post {
                    if (state == PipelineState.LISTENING) {
                        state = PipelineState.IDLE
                        listener?.onError(message)
                    }
                }
            }
        })
    }

    fun stopListening() {
        if (state != PipelineState.LISTENING) return
        try {
            asr.stopListening()
        } catch (_: Exception) {
        }
        state = PipelineState.IDLE
    }

    // ------------------------------------------------------------------ misc

    /** Startup self-check. Never throws; every item reports ok + a message. */
    fun selfCheck(): VoiceHealth {
        return VoiceHealth(
            ttsOk = tts.isAvailable(),
            ttsMessage = tts.availabilityMessage(),
            asrOk = asr.isAvailable(),
            asrMessage = asr.availabilityMessage()
        )
    }

    /** Release everything. Idempotent; call from onDestroy / onCleared. */
    fun release() {
        if (released) return
        released = true
        batchId++
        queue.clear()
        streaming = false
        waitingForSentences = false
        try {
            tts.stop()
        } catch (_: Exception) {
        }
        try {
            vad.stop()
        } catch (_: Exception) {
        }
        try {
            focus.abandon()
        } catch (_: Exception) {
        }
        try {
            asr.destroy()
        } catch (_: Exception) {
        }
        try {
            tts.shutdown()
        } catch (_: Exception) {
        }
        state = PipelineState.IDLE
    }
}
