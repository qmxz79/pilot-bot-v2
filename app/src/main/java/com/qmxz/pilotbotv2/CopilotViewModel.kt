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
import com.qmxz.pilotbotv2.voice.SwitchableAsrProvider
import com.qmxz.pilotbotv2.voice.SwitchableTtsProvider
import com.qmxz.pilotbotv2.voice.SystemAsrProvider
import com.qmxz.pilotbotv2.voice.SystemTtsProvider
import com.qmxz.pilotbotv2.voice.VadDetector
import com.qmxz.pilotbotv2.voice.VoicePipelineListener
import com.qmxz.pilotbotv2.voice.VoiceSettings
import com.qmxz.pilotbotv2.voice.XfyunAsrProvider
import com.qmxz.pilotbotv2.voice.XfyunTtsProvider
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
    /** Live ASR partial text; empty means nothing to show. */
    val partial: String = "",
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
    val voiceSettings = VoiceSettings(application)

    /**
     * Voice engines resolve per round through the switchable routers:
     * the user can flip system/xfyun in Settings and the next round uses
     * the new engine with no restart; an xfyun choice without credentials
     * falls back to system voice automatically (see effectiveEngine).
     * The routers also feed HealthCheck, so the health bar always shows
     * the engine actually in use.
     */
    private val systemTts = SystemTtsProvider(application)
    private val xfyunTts = XfyunTtsProvider(voiceSettings)
    private val tts = SwitchableTtsProvider(
        resolve = {
            if (voiceSettings.effectiveEngine == "xfyun") xfyunTts else systemTts
        },
        all = listOf(systemTts, xfyunTts)
    )
    private val systemAsr = SystemAsrProvider(application)
    private val xfyunAsr = XfyunAsrProvider(voiceSettings)
    private val asr = SwitchableAsrProvider(
        resolve = {
            if (voiceSettings.effectiveEngine == "xfyun") xfyunAsr else systemAsr
        },
        all = listOf(systemAsr, xfyunAsr)
    )
    private val focus = AudioFocusManager(application) {
        // Another app (call, nav) took focus: stop speaking immediately.
        viewModelScope.launch { pipeline.interrupt() }
    }
    private val vad = VadDetector()
    private val pipeline: BargeInVoicePipeline = BargeInVoicePipeline(tts, asr, focus, vad)
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

    /**
     * Rolling conversation history (multi-turn context). Without it every
     * turn was a fresh world — the companion could not follow up on anything
     * said one sentence earlier. Capped so a 30-minute drive cannot blow up
     * the prompt; the system prompt (persona + memories + scene) is rebuilt
     * every turn and never stored here.
     */
    private val history = ArrayDeque<ChatMessage>()

    /** The user text of the in-flight turn, until its reply is committed. */
    private var pendingUserText: String? = null

    /** Reply text accumulated so far for the in-flight turn (interruption-safe). */
    private val assistantSoFar = StringBuilder()

    /** Fire-and-forget LLM memory extraction (cancelled on interrupt/release). */
    private var memoryJob: Job? = null

    /** Guards late LLM callbacks from a cancelled/interrupted stream. */
    private var activeStreamId: Int = 0
    private var released: Boolean = false

    private companion object {
        /** ~6 turns of context; keeps the prompt small and latency low. */
        const val MAX_HISTORY_MESSAGES = 12
    }

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

            // Live ASR partials: shown under the transcript while listening.
            override fun onPartialResult(text: String) {
                updateStatus { copy(partial = text) }
            }

            override fun onFinalResult(text: String) {
                updateStatus { copy(listening = false, partial = "") }
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
                    updateStatus { copy(speaking = false, listening = false, partial = "") }
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
            PipelineState.LISTENING -> {
                pipeline.stopListening()
                updateStatus { copy(partial = "") }
            }
            PipelineState.SPEAKING -> {
                pipeline.interrupt()
                pipeline.startListening()
                updateStatus { copy(listening = true, partial = "") }
            }
            PipelineState.IDLE -> {
                pipeline.startListening()
                updateStatus { copy(listening = true, partial = "") }
            }
        }
    }

    /** Stop everything: speech, listening, and the in-flight LLM turn. */
    fun onInterrupt() {
        activeStreamId++
        llmJob?.cancel()
        llmJob = null
        llm.cancelCurrent()
        memoryJob?.cancel()
        memoryJob = null
        // The interrupted turn still happened: keep the user's question and
        // whatever reply was already heard, so the next turn has context and
        // the transcript matches reality.
        val user = pendingUserText
        if (user != null) {
            val partial = assistantSoFar.toString().trim()
            if (partial.isNotEmpty()) {
                postTranscript(TranscriptItem(TranscriptRole.ASSISTANT, "$partial（被打断）"))
            }
            commitTurn(user, partial)
            pendingUserText = null
            assistantSoFar.clear()
        }
        updateStatus { copy(partial = "") }
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

    /** Called after the Settings dialog saves voice fields. */
    fun onVoiceSettingsSaved() {
        val note = when {
            voiceSettings.effectiveEngine == "xfyun" ->
                "语音引擎已切换为讯飞云语音，下一轮对话生效。"
            voiceSettings.engine == "xfyun" ->
                "讯飞 Key 未填完整，已自动回落系统语音。"
            else ->
                "语音引擎已切换为系统语音，下一轮对话生效。"
        }
        postTranscript(TranscriptItem(TranscriptRole.SYSTEM, note))
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
        // A new turn replaces the old one (also commits its partial reply).
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
        val chatHistory = history.toList()
        val streamId = ++activeStreamId
        pendingUserText = userText
        assistantSoFar.clear()
        llmJob = viewModelScope.launch {
            pipeline.beginStream()
            val splitter = IncrementalSplitter()
            llm.streamChat(
                messages = listOf(ChatMessage("system", systemPrompt)) +
                    chatHistory +
                    ChatMessage("user", userText),
                onToken = { token ->
                    if (streamId != activeStreamId) return@streamChat
                    assistantSoFar.append(token)
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
                    commitTurn(userText, fullText)
                    pendingUserText = null
                    assistantSoFar.clear()
                    startLlmMemoryExtraction(userText)
                },
                onError = { message ->
                    if (streamId != activeStreamId) return@streamChat
                    pipeline.endStream()
                    postTranscript(
                        TranscriptItem(TranscriptRole.SYSTEM, "对话失败：$message")
                    )
                    // Short spoken apology; the detail stays in the transcript.
                    pipeline.speakText("抱歉，刚才没连上。")
                    // Keep the question in history so the user can re-ask and
                    // the companion remembers being asked.
                    commitTurn(userText, "")
                    pendingUserText = null
                    assistantSoFar.clear()
                }
            )
        }
    }

    /** Append a finished turn to the rolling history and cap its size. */
    private fun commitTurn(userText: String, assistantText: String) {
        if (userText.isNotBlank()) history.addLast(ChatMessage("user", userText))
        if (assistantText.isNotBlank()) history.addLast(ChatMessage("assistant", assistantText))
        while (history.size > MAX_HISTORY_MESSAGES) {
            history.removeFirst()
        }
    }

    /**
     * Second memory pass: ask the LLM to extract what regex cannot catch.
     * Fire-and-forget after the reply completes; failures are silent (the
     * rule path already ran, and a lost memory must never disturb a drive).
     */
    private fun startLlmMemoryExtraction(userText: String) {
        if (settings.apiKey.isBlank() || released) return
        memoryJob?.cancel()
        memoryJob = viewModelScope.launch {
            try {
                var reply = ""
                llm.streamChat(
                    messages = listOf(
                        ChatMessage("user", MemoryExtractor.extractionPrompt(userText))
                    ),
                    temperature = 0.2,
                    onToken = { },
                    onDone = { text -> reply = text },
                    onError = { /* silent by design */ }
                )
                // streamChat returns after the stream finished; store on IO.
                val items = MemoryExtractor.parseLlmResponse(reply)
                if (items.isEmpty()) return@launch
                val stored = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    items.filter { memoryStore.add(it) }
                }
                if (stored.isNotEmpty()) {
                    val summary = stored.joinToString("；") { it.text }
                    postTranscript(TranscriptItem(TranscriptRole.SYSTEM, "已记住：$summary"))
                }
            } catch (_: Exception) {
                // Extraction is best-effort; never let it break a drive.
            }
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
        memoryJob?.cancel()
        memoryJob = null
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
