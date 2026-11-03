package com.qmxz.pilotbotv2.voice

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

    /** A final ASR transcript was discarded: it was not the owner's voice. */
    fun onSpeakerRejected()

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
 * 4. Focus yield: while speaking or listening the pipeline holds transient
 *    audio focus. When another app (Amap navigation voice) takes transient
 *    focus, speech pauses at once — the interrupted sentence is replayed
 *    from the start when focus returns — VAD stops so nav words are never
 *    mistaken for the user, and a cut listening turn restarts hands-free.
 *    Permanent focus loss stops everything with no auto-resume.
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
    private val tts: TtsProvider,
    private val asr: AsrProvider,
    private val focus: AudioFocusManager,
    private val vad: VadDetector,
    /** Null means no speaker gating: every utterance is accepted. */
    private val speakerVerifier: SpeakerVerifier? = null
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    var listener: VoicePipelineListener? = null
    var state: PipelineState = PipelineState.IDLE
        private set

    private val queue = ArrayDeque<String>()

    /** Invalidates in-flight TTS callbacks (stop() may still deliver late ones). */
    private var batchId: Int = 0
    private var utteranceSeq: Int = 0

    /** Invalidates pending voiceprint verifications (a new session drops old ones). */
    private var verifyGen: Int = 0

    /** True while an LLM stream may still deliver more sentences. */
    private var streaming: Boolean = false

    /** True when the speaker is idle only because it awaits streamed sentences. */
    private var waitingForSentences: Boolean = false

    /** VAD failure is reported once per pipeline lifetime (see beginSpeaking). */
    private var vadFailureNoted: Boolean = false

    /** Sentence currently being spoken (already removed from [queue]). */
    private var currentSentence: String? = null

    /** Speech paused by a transient focus yield; resumed by [resumeFromYield]. */
    private var yielded: Boolean = false

    /** Listening suspended by a transient focus yield; restarted by [resumeFromYield]. */
    private var yieldedListening: Boolean = false

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
        yielded = false
        yieldedListening = false
        currentSentence = null
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
        startVad()
        listener?.onSpeakingStarted()
    }

    /** VAD arming shared by [beginSpeaking] and [resumeFromYield]. */
    private fun startVad() {
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
    }

    private fun speakNext() {
        if (released || state != PipelineState.SPEAKING || yielded) return
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
        currentSentence = sentence
        val batch = batchId
        val utteranceId = "u${++utteranceSeq}"
        tts.speak(sentence, utteranceId, object : TtsListener {
            override fun onStart(utteranceId: String) {
                mainHandler.post {
                    if (batch == batchId && state == PipelineState.SPEAKING) {
                        listener?.onSentenceSpoken(sentence)
                    }
                }
            }

            override fun onDone(utteranceId: String) {
                mainHandler.post {
                    if (batch == batchId) speakNext()
                }
            }

            override fun onError(utteranceId: String, message: String) {
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
        yielded = false
        yieldedListening = false
        currentSentence = null
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
        yielded = false
        yieldedListening = false
        currentSentence = null
        try {
            tts.stop()
        } catch (_: Exception) {
        }
        vad.stop()
        focus.abandon()
        state = PipelineState.IDLE
        listener?.onInterrupted()
    }

    // ------------------------------------------------------------ focus yield

    /**
     * Another app took transient focus (a nav announcement, a call waiting):
     * pause at once, resume on [resumeFromYield]. Silent by design — a
     * 3-second nav prompt must not spam the transcript or a toast.
     *
     * SPEAKING: the in-flight sentence is re-queued at the front, so it is
     * replayed from the start on resume, never cut in half. VAD stops too:
     * without that, the nav voice itself would trip barge-in and the
     * companion would try to answer its own navigation. Focus is NOT
     * abandoned — the request stays registered, so the system delivers
     * AUDIOFOCUS_GAIN when the other app releases.
     *
     * LISTENING: the ASR session stops (late callbacks are ignored: they
     * check state == LISTENING). [resumeFromYield] restarts listening so
     * the driver does not have to re-tap; the partial transcript is lost,
     * so the command is repeated in full.
     */
    fun yieldForFocus() {
        if (released || yielded || yieldedListening) return
        when (state) {
            PipelineState.SPEAKING -> {
                yielded = true
                batchId++ // invalidate late TTS callbacks from the stopped utterance
                val interrupted = currentSentence
                currentSentence = null
                if (interrupted != null) queue.addFirst(interrupted)
                try {
                    tts.stop()
                } catch (_: Exception) {
                }
                vad.stop()
            }
            PipelineState.LISTENING -> {
                yieldedListening = true
                try {
                    asr.stopListening()
                } catch (_: Exception) {
                }
                // Focus is NOT abandoned here either: the request must stay
                // registered or the system will never deliver the GAIN that
                // triggers the listening restart in resumeFromYield().
                state = PipelineState.IDLE
            }
            PipelineState.IDLE -> Unit
        }
    }

    /**
     * Focus returned after a yield: continue where we paused. No-op without
     * a prior yield, and flags are always cleared so a stale yield can never
     * block later speech.
     */
    fun resumeFromYield() {
        if (released) return
        val wasYielded = yielded
        val wasYieldedListening = yieldedListening
        yielded = false
        yieldedListening = false
        if (wasYielded && state == PipelineState.SPEAKING) {
            startVad()
            speakNext()
        } else if (wasYieldedListening) {
            startListening()
        }
    }

    private fun handleVadTrigger() {
        if (released || state != PipelineState.SPEAKING || yielded) return
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
        // A new session invalidates any verification still pending from
        // the previous one (its result must never act on this session).
        verifyGen++
        state = PipelineState.LISTENING
        // Hold focus while listening too: without it the system never
        // delivers the transient-loss callback, so a nav announcement
        // during listening could not pause ASR (see yieldForFocus).
        // Music ducks while the driver talks to the companion, then resumes.
        focus.request()
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
                        focus.abandon()
                        deliverFinal(text)
                    }
                }
            }

            override fun onError(message: String, recoverable: Boolean) {
                mainHandler.post {
                    if (state == PipelineState.LISTENING) {
                        state = PipelineState.IDLE
                        focus.abandon()
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
        focus.abandon()
        state = PipelineState.IDLE
    }

    /**
     * Final ASR text passes the speaker gate before anyone acts on it.
     * Unenrolled, too short, or unverifiable audio fails OPEN
     * (delivered): a dead verification service must not mute the owner.
     * A scored non-match is discarded with onSpeakerRejected — the
     * companion never answers a passenger's words, and never speaks
     * about the rejection either (a transcript note is enough).
     */
    private fun deliverFinal(text: String) {
        val verifier = speakerVerifier
        val audio = if (verifier?.isEnrolled == true) asr.lastUtteranceAudio() else null
        if (verifier == null || audio == null || audio.size < MIN_VERIFY_BYTES) {
            listener?.onFinalResult(text)
            return
        }
        val gen = verifyGen
        verifier.verify(audio) { result ->
            mainHandler.post {
                if (released || gen != verifyGen) return@post
                when (result) {
                    is VoiceprintCheck.Scored -> {
                        if (result.score >= VOICEPRINT_SCORE_THRESHOLD) {
                            listener?.onFinalResult(text)
                        } else {
                            listener?.onSpeakerRejected()
                        }
                    }
                    VoiceprintCheck.Unavailable -> listener?.onFinalResult(text)
                }
            }
        }
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
        verifyGen++
        queue.clear()
        streaming = false
        waitingForSentences = false
        yielded = false
        yieldedListening = false
        currentSentence = null
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

    private companion object {
        /**
         * Vendor FAQ recommends 0.6-1 as pass; the low end favours
         * accepting the owner over rejecting passengers. Tune on device:
         * raise it if passengers get through, lower it if the owner is
         * wrongly ignored.
         */
        const val VOICEPRINT_SCORE_THRESHOLD = 0.6f

        /** 0.5s of 16k mono PCM16: shorter clips cannot verify reliably. */
        const val MIN_VERIFY_BYTES = 16000
    }
}
