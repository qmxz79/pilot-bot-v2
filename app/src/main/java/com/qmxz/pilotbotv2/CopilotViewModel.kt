package com.qmxz.pilotbotv2

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qmxz.pilotbotv2.diagnostics.HealthCheck
import com.qmxz.pilotbotv2.llm.ChatMessage
import com.qmxz.pilotbotv2.llm.IncrementalSplitter
import com.qmxz.pilotbotv2.llm.LlmClient
import com.qmxz.pilotbotv2.llm.LlmSettings
import com.qmxz.pilotbotv2.memory.MemoryExtractor
import com.qmxz.pilotbotv2.memory.MemoryRetriever
import com.qmxz.pilotbotv2.memory.MemoryStore
import com.qmxz.pilotbotv2.nav.AmapNavigationProvider
import com.qmxz.pilotbotv2.nav.NaviSessionCoordinator
import com.qmxz.pilotbotv2.nav.SystemGpsFallbackProvider
import com.qmxz.pilotbotv2.persona.PersonaCard
import com.qmxz.pilotbotv2.persona.PersonaPresets
import com.qmxz.pilotbotv2.persona.SystemPromptBuilder
import com.qmxz.pilotbotv2.safety.DriveScene
import com.qmxz.pilotbotv2.safety.DrivingLoadEstimatorV2
import com.qmxz.pilotbotv2.ui.TranscriptItem
import com.qmxz.pilotbotv2.ui.TranscriptRole
import com.qmxz.pilotbotv2.voice.AudioFocusManager
import com.qmxz.pilotbotv2.voice.BargeInVoicePipeline
import com.qmxz.pilotbotv2.voice.PipelineState
import com.qmxz.pilotbotv2.voice.SystemAsrProvider
import com.qmxz.pilotbotv2.voice.SystemTtsProvider
import com.qmxz.pilotbotv2.voice.VadDetector
import com.qmxz.pilotbotv2.voice.VoicePipelineListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Everything the Activity renders. Transcript rows travel separately (see below). */
data class UiStatus(
    val speaking: Boolean = false,
    val listening: Boolean = false,
    /** False when the health check found no recognition service: mic must not invite a doomed tap. */
    val asrAvailable: Boolean = true,
    val speedKmh: Float = 0f,
    val scene: DriveScene = DriveScene.CRUISE,
    val healthSummary: String = "自检中…",
    val navSummary: String = "定位：初始化中…"
)

/**
 * The only orchestrator. MainActivity stays thin (wiring + lifecycle) and
 * every behaviour lives here or in the module it belongs to.
 *
 * One conversation turn, end to end:
 * mic/ASR final (or typed text) -> memory extraction -> prompt build
 * (persona + memories + scene + speed style + nav context) -> LLM SSE stream
 * -> sentences queued to the pipeline as they complete -> spoken aloud.
 *
 * Interruption invalidates a whole turn: the LLM job is cancelled, the HTTP
 * call is cancelled, the stream id moves on (so late main-thread callbacks
 * from the dead stream are ignored), and the pipeline drops its queue.
 */
class CopilotViewModel(application: Application) : AndroidViewModel(application) {

    val settings = LlmSettings(application)

    private val tts = SystemTtsProvider(application)
    private val asr = SystemAsrProvider(application)
    private val focus = AudioFocusManager(application) {
        // Another app (call, nav) took focus: stop speaking immediately.
        viewModelScope.launch { pipeline.interrupt() }
    }
    private val vad = VadDetector()
    private val pipeline = BargeInVoicePipeline(application, tts, asr, focus, vad)
    private val llm = LlmClient(settings)
    private val memoryStore = MemoryStore(application)
    private val coordinator = NaviSessionCoordinator(
        AmapNavigationProvider(application),
        SystemGpsFallbackProvider(application)
    )
    private val healthCheck = HealthCheck(application, tts, asr, llm, settings)

    private var persona: PersonaCard = PersonaPresets.byId(settings.personaId)
    private var currentScene: DriveScene = DriveScene.CRUISE
    private var currentSpeedKmh: Float = 0f

    private val _transcriptEvents =
        MutableSharedFlow<TranscriptItem>(extraBufferCapacity = 64)
    val transcriptEvents: SharedFlow<TranscriptItem> = _transcriptEvents.asSharedFlow()

    private val _uiStatus = MutableStateFlow(UiStatus())
    val uiStatus: StateFlow<UiStatus> = _uiStatus.asStateFlow()

    private var llmJob: Job? = null

    /** Pipeline error messages already shown this session (spam guard). */
    private val reportedPipelineErrors = HashSet<String>()

    /** Guards late LLM callbacks from a cancelled/interrupted stream. */
    private var activeStreamId: Int = 0
    private var released: Boolean = false

    init {
        pipeline.listener = object : VoicePipelineListener {
            override fun onSpeakingStarted() {
                updateStatus { copy(speaking = true, listening = false) }
            }

            override fun onSpeakingFinished() {
                updateStatus { copy(speaking = false) }
            }

            override fun onSentenceSpoken(sentence: String) = Unit

            override fun onInterrupted() {
                updateStatus { copy(speaking = false) }
            }

            // Partials are display-only in v2; the UI shows final results.
            override fun onPartialResult(text: String) = Unit

            override fun onFinalResult(text: String) {
                updateStatus { copy(listening = false) }
                sendToLlm(text)
            }

            override fun onError(message: String) {
                // Same failure (dead TTS engine, missing ASR service) would
                // otherwise post one SYSTEM row per reply. Real-device
                // finding 2026-10-28: a phone without Google voice services
                // spammed "系统语音引擎初始化失败" after every answer.
                // Report each distinct message once per session; a health
                // re-run clears the set so a fixed engine can report again.
                if (!reportedPipelineErrors.add(message)) {
                    if (pipeline.state != PipelineState.SPEAKING) {
                        updateStatus { copy(speaking = false, listening = false) }
                    }
                    return
                }
                postTranscript(TranscriptItem(TranscriptRole.SYSTEM, message))
                // While SPEAKING, onError is a non-fatal VAD warning (speech
                // continues) or a TTS error (onSpeakingFinished follows and
                // clears the flag). Only touch flags when not speaking.
                if (pipeline.state != PipelineState.SPEAKING) {
                    updateStatus { copy(speaking = false, listening = false) }
                }
            }
        }
        coordinator.start()
        viewModelScope.launch {
            coordinator.locationFlow.collect { location ->
                currentSpeedKmh = location?.speedKmh ?: 0f
                refreshNavSummary()
            }
        }
        viewModelScope.launch {
            coordinator.statusFlow.collect {
                refreshNavSummary()
            }
        }
        runHealthCheck()
    }

    // ------------------------------------------------------------ user actions

    fun onMicPressed() {
        when (pipeline.state) {
            PipelineState.LISTENING -> pipeline.stopListening()
            PipelineState.SPEAKING -> {
                pipeline.interrupt()
                pipeline.startListening()
                updateStatus { copy(listening = true) }
            }
            PipelineState.IDLE -> {
                pipeline.startListening()
                updateStatus { copy(listening = true) }
            }
        }
    }

    /** Stop everything: speech, listening, and the in-flight LLM turn. */
    fun onInterrupt() {
        activeStreamId++
        llmJob?.cancel()
        llmJob = null
        llm.cancelCurrent()
        when (pipeline.state) {
            PipelineState.SPEAKING -> pipeline.interrupt()
            PipelineState.LISTENING -> {
                pipeline.stopListening()
                updateStatus { copy(listening = false) }
            }
            PipelineState.IDLE -> Unit
        }
    }

    fun onTextInput(text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            sendToLlm(trimmed)
        }
    }

    fun onSceneSelected(scene: DriveScene) {
        currentScene = scene
        updateStatus { copy(scene = scene) }
        val line = when (scene) {
            DriveScene.JAM -> "前面堵着呢，别急，咱们慢慢聊会儿。"
            DriveScene.NIGHT -> "夜里开车辛苦了，我陪着你，困了就说一声。"
            DriveScene.FATIGUE -> "你好像有点累了，要不要到前面服务区歇一会儿？"
            DriveScene.CRUISE -> "这段路挺顺的，咱们聊点开心的吧。"
        }
        postTranscript(TranscriptItem(TranscriptRole.ASSISTANT, line))
        pipeline.speakText(line)
    }

    fun updatePersona(personaId: String) {
        settings.personaId = personaId
        persona = PersonaPresets.byId(personaId)
        postTranscript(TranscriptItem(TranscriptRole.SYSTEM, "人设已切换。"))
    }

    fun refreshHealth() {
        runHealthCheck()
    }

    /** App backgrounded: stop speech/listening/LLM so nothing talks blindly. */
    fun onPaused() {
        onInterrupt()
    }

    // -------------------------------------------------------------- internals

    private fun sendToLlm(userText: String) {
        if (userText.isBlank()) return
        // A new turn replaces the old one.
        onInterrupt()
        postTranscript(TranscriptItem(TranscriptRole.USER, userText))

        // Automatic memory: learn from what the driver just said.
        val fresh = MemoryExtractor.extract(userText)
        val newlyStored = fresh.filter { memoryStore.add(it) }
        if (newlyStored.isNotEmpty()) {
            val summary = newlyStored.joinToString("；") { it.text }
            postTranscript(TranscriptItem(TranscriptRole.SYSTEM, "已记住：$summary"))
        }

        val memories = MemoryRetriever.topK(userText, memoryStore.all())
        val style = DrivingLoadEstimatorV2.styleFor(currentSpeedKmh, currentScene)
        val navContext = coordinator.latestLocation()?.let { loc ->
            val place = loc.address ?: "未知位置"
            "车速约 ${loc.speedKmh.toInt()} km/h，位置：$place（${loc.source}）"
        }
        val systemPrompt = SystemPromptBuilder.build(
            card = persona,
            memories = memories,
            scene = currentScene,
            style = style,
            navContext = navContext
        )
        val streamId = ++activeStreamId
        llmJob = viewModelScope.launch {
            pipeline.beginStream()
            val splitter = IncrementalSplitter()
            llm.streamChat(
                messages = listOf(
                    ChatMessage("system", systemPrompt),
                    ChatMessage("user", userText)
                ),
                onToken = { token ->
                    if (streamId != activeStreamId) return@streamChat
                    splitter.feed(token).forEach { pipeline.queueSentence(it) }
                },
                onDone = { fullText ->
                    if (streamId != activeStreamId) return@streamChat
                    splitter.flush()?.let { pipeline.queueSentence(it) }
                    pipeline.endStream()
                    if (fullText.isNotBlank()) {
                        postTranscript(
                            TranscriptItem(TranscriptRole.ASSISTANT, fullText)
                        )
                    }
                },
                onError = { message ->
                    if (streamId != activeStreamId) return@streamChat
                    pipeline.endStream()
                    postTranscript(
                        TranscriptItem(TranscriptRole.SYSTEM, "对话失败：$message")
                    )
                    // Short spoken apology; the detail stays in the transcript.
                    pipeline.speakText("抱歉，刚才没连上。")
                }
            )
        }
    }

    private fun runHealthCheck() {
        // A fresh self-check re-arms error reporting: if the user just
        // installed a voice engine, its next failure deserves one new row.
        reportedPipelineErrors.clear()
        healthCheck.run { items ->
            val summary = items.joinToString(" · ") { item ->
                "${item.name}${if (item.ok) "✓" else "✗"}：${item.message}"
            }
            val asrOk = items.firstOrNull { it.name == "语音识别" }?.ok ?: true
            val ttsOk = items.firstOrNull { it.name == "语音合成" }?.ok ?: true
            updateStatus { copy(healthSummary = summary, asrAvailable = asrOk) }
            // Never "speak" the summary through a dead engine: that only
            // produced one more TTS-failure row per health run on devices
            // without voice services. The visual summary already shows it.
            if (ttsOk) {
                pipeline.speakText(healthCheck.spokenSummary(items))
            }
        }
    }

    private fun refreshNavSummary() {
        val location = coordinator.latestLocation()
        val summary = if (location != null) {
            val place = location.address?.let { " · $it" } ?: ""
            "定位正常（${location.source}）$place"
        } else {
            "定位：${coordinator.statusFlow.value.message}"
        }
        updateStatus { copy(navSummary = summary, speedKmh = currentSpeedKmh) }
    }

    private fun postTranscript(item: TranscriptItem) {
        _transcriptEvents.tryEmit(item)
    }

    private fun updateStatus(transform: UiStatus.() -> UiStatus) {
        _uiStatus.value = _uiStatus.value.transform()
    }

    /** Idempotent: called from MainActivity.onDestroy and onCleared. */
    fun release() {
        if (released) return
        released = true
        activeStreamId++
        llmJob?.cancel()
        llmJob = null
        llm.cancelCurrent()
        pipeline.release()
        coordinator.stop()
    }

    override fun onCleared() {
        release()
        super.onCleared()
    }
}

/** Factory: ViewModel needs the Application (never an Activity) for providers. */
class CopilotViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CopilotViewModel::class.java)) {
            return CopilotViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
