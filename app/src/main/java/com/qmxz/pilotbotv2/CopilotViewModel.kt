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
import com.qmxz.pilotbotv2.nav.AmapNavLauncher
import com.qmxz.pilotbotv2.nav.AmapNavListener
import com.qmxz.pilotbotv2.nav.AmapNavResult
import com.qmxz.pilotbotv2.nav.AmapNavStatus
import com.qmxz.pilotbotv2.nav.AmapNavTarget
import com.qmxz.pilotbotv2.nav.NavCommandParser
import com.qmxz.pilotbotv2.nav.AmapNavigationProvider
import com.qmxz.pilotbotv2.nav.NaviSessionCoordinator
import com.qmxz.pilotbotv2.service.CompanionService
import com.qmxz.pilotbotv2.nav.SystemGpsFallbackProvider
import com.qmxz.pilotbotv2.persona.PersonaCard
import com.qmxz.pilotbotv2.persona.PersonaPresets
import com.qmxz.pilotbotv2.persona.SystemPromptBuilder
import com.qmxz.pilotbotv2.safety.DriveScene
import com.qmxz.pilotbotv2.safety.DrivingLoadEstimatorV2
import com.qmxz.pilotbotv2.safety.SceneAutoDetector
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
import com.qmxz.pilotbotv2.voice.VoiceprintRecorder
import com.qmxz.pilotbotv2.voice.XfyunAsrProvider
import com.qmxz.pilotbotv2.voice.XfyunVoiceprintVerifier
import com.qmxz.pilotbotv2.voice.XfyunTtsProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val navSummary: String = "定位：初始化中…",
    val routeSummary: String = "高德导航未启动"
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
    private val focus = AudioFocusManager(
        application,
        onFocusLost = {
            // Permanent loss (a call took over, music started): stop
            // speaking and do not auto-resume.
            viewModelScope.launch { pipeline.interrupt() }
        },
        onYield = {
            // Transient loss (a nav announcement): pause at once; the
            // pipeline replays the cut sentence when focus returns.
            viewModelScope.launch { pipeline.yieldForFocus() }
        },
        onResume = {
            viewModelScope.launch { pipeline.resumeFromYield() }
        }
    )
    private val vad = VadDetector()
    private val voiceprintVerifier = XfyunVoiceprintVerifier(voiceSettings)
    private val pipeline: BargeInVoicePipeline =
        BargeInVoicePipeline(tts, asr, focus, vad, voiceprintVerifier)
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

    /**
     * M3 safety auto-trigger. The detector is pure logic; this ViewModel
     * owns the policy around it: manual scene picks suppress auto-switches
     * for 30 min, auto-switches only fire when the pipeline is IDLE (never
     * cutting off a reply) and the app is foregrounded (never talking to
     * an empty car), and a location older than 60 s counts as unknown
     * speed rather than a stale number.
     */
    private val sceneDetector = SceneAutoDetector()
    private var manualOverrideUntilMs: Long = 0
    private var foreground: Boolean = true
    private var lastLocationMs: Long = 0

    /** True while a voiceprint enrollment is recording (safety triggers pause). */
    private var voiceprintEnrolling: Boolean = false

    /**
     * Navigation lives in the Amap app (decision 2026-11-03): no in-app
     * routing, no step announcements, no re-planning. The companion only
     * launches Amap and reads its notification progress (AmapNavStatus).
     * Positioning for scene detection still comes from the coordinator.
     */
    private var amapNavActive = false

    /** True while the foreground companion service runs (Amap-nav session). */
    private var companionKeepAlive = false

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

        /** Manual scene picks suppress auto-switches for this long. */
        const val MANUAL_OVERRIDE_MS = 30 * 60_000L

        /** Backup auto-scene tick (location updates also evaluate). */
        const val AUTO_EVAL_INTERVAL_MS = 30_000L

        /** Location older than this counts as unknown speed. */
        const val SPEED_STALE_MS = 60_000L

        /** Location older than this is too stale for a nearby search. */
        const val LOCATION_STALE_MS = 5 * 60_000L

        /** Voiceprint enrollment: clips per enrollment, ms per clip. */
        const val ENROLL_CLIPS = 3
        const val ENROLL_CLIP_MS = 4000L
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

            override fun onSpeakerRejected() {
                // Not the owner's voice: never answer it, never speak
                // about it — a transcript note is the whole reaction.
                postTranscript(
                    TranscriptItem(TranscriptRole.SYSTEM, "已忽略非车主语音。")
                )
            }

            override fun onFinalResult(text: String) {
                updateStatus { copy(listening = false, partial = "") }
                // Voice navigation orders never reach the LLM: acting on
                // "导航到市政府" beats chatting about it.
                val navCommand = NavCommandParser.parse(text)
                if (navCommand != null) {
                    handleVoiceNavigation(text, navCommand.destination)
                    return
                }
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
        viewModelScope.launch {
            AmapNavStatus.info.collect { info ->
                if (info != null) {
                    amapNavActive = true
                    val detail = info.nextInstruction
                        ?: info.rawText.lineSequence().firstOrNull().orEmpty()
                    val remaining = info.remaining?.let { " · $it" } ?: ""
                    updateStatus { copy(routeSummary = "高德导航：$detail$remaining") }
                } else if (amapNavActive) {
                    amapNavActive = false
                    updateStatus { copy(routeSummary = "高德导航未启动") }
                    postTranscript(
                        TranscriptItem(TranscriptRole.SYSTEM, "高德导航已结束。")
                    )
                }
            }
        }
        coordinator.start()
        viewModelScope.launch {
            coordinator.locationFlow.collect { location ->
                currentSpeedKmh = location?.speedKmh ?: 0f
                if (location != null) {
                    lastLocationMs = System.currentTimeMillis()
                    evaluateAutoScene()
                }
                refreshNavSummary()
            }
        }
        // Periodic backup for time-based rules (night boundary, fatigue)
        // when location updates stop arriving.
        viewModelScope.launch {
            while (true) {
                delay(AUTO_EVAL_INTERVAL_MS)
                evaluateAutoScene()
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

    /**
     * Manual scene picks (the chips) set a 30 min auto-suppression window;
     * auto-detected switches pass [auto] = true and do not.
     */
    fun onSceneSelected(scene: DriveScene, auto: Boolean = false) {
        if (!auto) {
            manualOverrideUntilMs =
                System.currentTimeMillis() + MANUAL_OVERRIDE_MS
        }
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

    /** True when a voiceprint group is enrolled (speaker gate active). */
    val isVoiceprintEnrolled: Boolean
        get() = voiceSettings.isVoiceprintEnrolled

    /**
     * Voiceprint enrollment: ENROLL_CLIPS clips of ENROLL_CLIP_MS in a
     * quiet room, then createGroup + one feature per clip on iFlytek.
     * Saves immediately (like the crash-log button, independent of the
     * Settings OK/Cancel). Any failure deletes the new group and keeps
     * the old enrollment (if any) working. Callbacks arrive on the main
     * thread; the early guards report without touching the enrolling flag.
     */
    fun startVoiceprintEnrollment(
        onProgress: (String) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        val mainHandler =
            android.os.Handler(android.os.Looper.getMainLooper())
        fun progress(msg: String) = mainHandler.post { onProgress(msg) }
        // Terminal path of THIS run: clears the flag, then reports.
        fun done(ok: Boolean, msg: String) = mainHandler.post {
            voiceprintEnrolling = false
            onDone(ok, msg)
        }
        if (voiceprintEnrolling) {
            mainHandler.post { onDone(false, "正在注册声纹，请稍候。") }
            return
        }
        if (pipeline.state != PipelineState.IDLE) {
            mainHandler.post { onDone(false, "请先结束当前对话再注册声纹。") }
            return
        }
        if (!voiceSettings.isXfyunConfigured) {
            mainHandler.post { onDone(false, "请先在设置页填好讯飞 Key 再注册声纹。") }
            return
        }
        voiceprintEnrolling = true
        Thread({
            try {
                val clips = mutableListOf<ByteArray>()
                for (i in 1..ENROLL_CLIPS) {
                    progress("正在录第 $i/$ENROLL_CLIPS 段，请在安静环境说一句话（4 秒）…")
                    val clip = VoiceprintRecorder.recordClip(ENROLL_CLIP_MS)
                    if (clip == null) {
                        done(false, "第 $i 段没录到声音，请在安静环境重录。")
                        return@Thread
                    }
                    clips.add(clip)
                }
                val oldGroupId = voiceSettings.voiceprintGroupId
                val groupId = "pb_" + System.currentTimeMillis()
                progress("正在上传声纹特征…")
                voiceprintVerifier.createGroup(groupId) { created, createError ->
                    if (!created) {
                        done(false, "创建声纹库失败：$createError")
                        return@createGroup
                    }
                    uploadVoiceprintFeatures(groupId, clips, 0) { ok, featureError ->
                        if (!ok) {
                            voiceprintVerifier.deleteGroup(groupId) {}
                            done(false, "上传声纹特征失败：$featureError")
                            return@uploadVoiceprintFeatures
                        }
                        voiceSettings.voiceprintGroupId = groupId
                        if (oldGroupId.isNotBlank() && oldGroupId != groupId) {
                            voiceprintVerifier.deleteGroup(oldGroupId) {}
                        }
                        done(true, "声纹注册成功，门禁已开启：只有你的声音会被响应。")
                    }
                }
            } catch (e: Exception) {
                done(false, "声纹注册失败：${e.message}")
            }
        }, "voiceprint-enroll").also { it.start() }
    }

    private fun uploadVoiceprintFeatures(
        groupId: String,
        clips: List<ByteArray>,
        index: Int,
        onDone: (Boolean, String?) -> Unit
    ) {
        if (index >= clips.size) {
            onDone(true, null)
            return
        }
        voiceprintVerifier.addFeature(
            groupId, "feat${index + 1}", clips[index]
        ) { ok, error ->
            if (!ok) onDone(false, error)
            else uploadVoiceprintFeatures(groupId, clips, index + 1, onDone)
        }
    }

    /**
     * Clears local enrollment and deletes the cloud group (best-effort:
     * a failed cloud delete leaves an orphaned group, but gating is
     * already off locally, which is what matters).
     */
    fun clearVoiceprint(onDone: (String) -> Unit) {
        val groupId = voiceSettings.voiceprintGroupId
        voiceSettings.voiceprintGroupId = ""
        if (groupId.isBlank()) {
            onDone("声纹本来就没注册。")
            return
        }
        voiceprintVerifier.deleteGroup(groupId) {
            onDone("声纹已清除，门禁已关闭。")
        }
    }

    fun refreshHealth() {
        runHealthCheck()
    }

    /**
     * Re-run location after a permission result. The coordinator starts
     * once at launch, so a grant that arrives later otherwise never takes
     * effect until the app restarts (seen 2026-11-02: health said location
     * ready while the nav line still said no permission).
     */
    fun refreshLocation() {
        coordinator.stop()
        coordinator.start()
    }

    /**
     * Navigation via the Amap app (decision 2026-11-03): resolve the
     * destination name to GCJ-02 over plain HTTP (no SDK), jump to Amap,
     * and start the foreground companion service so voice keeps working
     * while Amap is in front. Progress comes back through AmapNavListener,
     * not from in-app routing. The companion never repeats maneuvers;
     * it only uses progress as conversation context.
     */
    fun startAmapNavigation(destination: String) {
        val name = destination.trim()
        if (name.isEmpty()) return
        if (!AmapNavLauncher.isAmapInstalled(getApplication<Application>())) {
            postTranscript(
                TranscriptItem(
                    TranscriptRole.SYSTEM,
                    "未安装高德地图，请先安装高德地图 App 再导航。"
                )
            )
            return
        }
        if (!AmapNavListener.isEnabled(getApplication<Application>())) {
            postTranscript(
                TranscriptItem(
                    TranscriptRole.SYSTEM,
                    "要读高德导航进度，需要先开通知使用权，正在打开系统设置…"
                )
            )
            AmapNavListener.openSettings(getApplication<Application>())
            return
        }
        postTranscript(
            TranscriptItem(TranscriptRole.SYSTEM, "正在为“$name”找位置…")
        )
        AmapNavLauncher.geocode(name) { result ->
            when (result) {
                is AmapNavResult.Failed -> postTranscript(
                    TranscriptItem(TranscriptRole.SYSTEM, "导航失败：${result.message}")
                )
                is AmapNavResult.Ok -> {
                    launchAmapNav(result.target)
                    postTranscript(
                        TranscriptItem(
                            TranscriptRole.SYSTEM,
                            "已为你打开高德导航，前往${result.target.name}。"
                        )
                    )
                }
            }
        }
    }

    /** Shared tail of every Amap jump: launch, keep voice alive, show route. */
    private fun launchAmapNav(target: AmapNavTarget) {
        getApplication<Application>().startActivity(
            AmapNavLauncher.buildNaviIntent(target)
        )
        companionKeepAlive = true
        CompanionService.start(getApplication<Application>())
        updateStatus {
            copy(routeSummary = "高德导航：前往${target.name}（高德 App 内导航）")
        }
    }

    /**
     * Voice navigation order ("导航到市政府"): search around the current
     * fix, announce the match, and jump to Amap — no taps. Failures are
     * spoken, never silently dropped, and never guessed: no fix, no key
     * or no nearby match means no launch, not a wrong-city launch.
     *
     * Unlike the typed dialog, this path never blocks on the
     * notification-listener gate: opening system settings mid-drive is
     * worse than navigating without progress display (noted in the
     * transcript instead).
     */
    private fun handleVoiceNavigation(userText: String, destination: String) {
        postTranscript(TranscriptItem(TranscriptRole.USER, userText))
        if (destination == "家" || destination == "公司" || destination == "单位") {
            val message =
                "还没存${destination}的地址，先说个具体地名，比如导航到市政府，我马上帮你导航。"
            postTranscript(TranscriptItem(TranscriptRole.SYSTEM, message))
            pipeline.speakText(message)
            return
        }
        if (!AmapNavLauncher.isAmapInstalled(getApplication<Application>())) {
            val message = "没装高德地图，先装上高德地图 App 再说导航。"
            postTranscript(TranscriptItem(TranscriptRole.SYSTEM, message))
            pipeline.speakText(message)
            return
        }
        val location = coordinator.latestLocation()
        val now = System.currentTimeMillis()
        if (location == null || now - lastLocationMs > LOCATION_STALE_MS) {
            val message = "暂时还没拿到定位，等定位出来再说一次导航。"
            postTranscript(TranscriptItem(TranscriptRole.SYSTEM, message))
            pipeline.speakText(message)
            return
        }
        postTranscript(
            TranscriptItem(TranscriptRole.SYSTEM, "正在为“$destination”找附近的位置…")
        )
        AmapNavLauncher.searchNearby(
            location.latGcj02, location.lngGcj02, destination
        ) { result ->
            when (result) {
                is AmapNavResult.Failed -> {
                    val message = "导航失败：${result.message}"
                    postTranscript(TranscriptItem(TranscriptRole.SYSTEM, message))
                    // The key-missing detail is long; speech gets the short form.
                    val spoken = if (result.message.startsWith("未配置高德 Key")) {
                        "还没配置高德 Key，搜不了地点，配好 Key 重新编译后就能语音导航。"
                    } else {
                        message
                    }
                    pipeline.speakText(spoken)
                }
                is AmapNavResult.Ok -> {
                    val message = "正在为你导航到${result.target.name}。"
                    postTranscript(TranscriptItem(TranscriptRole.SYSTEM, message))
                    pipeline.speakText(message)
                    launchAmapNav(result.target)
                    if (!AmapNavListener.isEnabled(getApplication<Application>())) {
                        postTranscript(
                            TranscriptItem(
                                TranscriptRole.SYSTEM,
                                "还没开通知使用权，副驾读不到导航进度；停车后在设置里打开，进度就能实时显示。"
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * Stops the foreground companion service. Navigation itself ends in
     * the Amap app; this only ends background companionship.
     */
    fun stopCompanion() {
        companionKeepAlive = false
        CompanionService.stop(getApplication<Application>())
        postTranscript(
            TranscriptItem(TranscriptRole.SYSTEM, "已结束后台陪伴。")
        )
    }

    /** The service's notification button ended the companion without going through here. */
    fun onCompanionStopped() {
        companionKeepAlive = false
    }

    /**
     * App backgrounded. Default rule: stop speech/listening/LLM so
     * nothing talks blindly. Exception: during an Amap-nav session the
     * companion service is keeping voice alive, so backgrounding to
     * Amap must not cut speech — the driver is still there, listening.
     */
    fun onPaused() {
        foreground = false
        if (!companionKeepAlive) {
            onInterrupt()
        }
    }

    /** App foregrounded again: auto scene switches may fire. */
    fun onResumed() {
        foreground = true
    }

    /**
     * One auto-scene evaluation. Fires at most one switch, only when the
     * pipeline is IDLE and the app is foregrounded (or the companion
     * service is keeping voice alive during Amap navigation — the
     * driver is still there, so safety speech stays allowed); otherwise
     * this tick is skipped and a later one retries (the detector's
     * sustained durations make a missed tick harmless).
     */
    private fun evaluateAutoScene() {
        if (released || (!foreground && !companionKeepAlive)) return
        if (pipeline.state != PipelineState.IDLE) return
        // Enrollment clips must stay clean: no companion speech over them.
        if (voiceprintEnrolling) return
        val now = System.currentTimeMillis()
        val speed = if (now - lastLocationMs > SPEED_STALE_MS) {
            null
        } else {
            currentSpeedKmh
        }
        val hour = java.util.Calendar.getInstance()
            .get(java.util.Calendar.HOUR_OF_DAY)
        val target = sceneDetector.evaluate(
            now,
            hour,
            speed,
            currentScene,
            manualOverrideUntilMs
        ) ?: return
        onSceneSelected(target, auto = true)
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
