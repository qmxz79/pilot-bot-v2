package com.qmxz.pilotbotv2.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Default TTS implementation backed by Android's system TextToSpeech.
 *
 * Handles the three classic system-TTS traps:
 * 1. Init is asynchronous — speak() calls made before onInit are queued, not dropped.
 * 2. The device may lack Chinese voice data — language support is checked and reported.
 * 3. stop() may still deliver a late callback — listeners are removed on stop(),
 *    and the pipeline additionally guards with a batch id.
 *
 * This is the "always works" fallback. Cloud emotional TTS plugs in later behind
 * the same [TtsProvider] interface (see [CloudTtsProvider]).
 */
class SystemTtsProvider(context: Context) : TtsProvider, TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext

    private companion object {
        const val STATE_PENDING = 0
        const val STATE_OK = 1
        const val STATE_FAILED = 2
    }

    @Volatile
    private var initState: Int = STATE_PENDING

    @Volatile
    private var languageOk: Boolean = false

    @Volatile
    private var initError: String = "语音引擎初始化中"

    private var tts: TextToSpeech? = null
    private val listeners = ConcurrentHashMap<String, TtsListener>()
    private val pending = ArrayDeque<Triple<String, String, TtsListener>>()
    private val lock = Any()

    init {
        // Constructor never throws for a missing engine; failure arrives via onInit.
        tts = TextToSpeech(appContext, this)
    }

    override fun onInit(status: Int) {
        synchronized(lock) {
            if (status != TextToSpeech.SUCCESS) {
                initState = STATE_FAILED
                initError = "系统语音引擎初始化失败（可能未安装语音服务）"
                drainPendingWithError()
                return
            }
            val engine = tts
            if (engine == null) {
                initState = STATE_FAILED
                initError = "系统语音引擎不可用"
                drainPendingWithError()
                return
            }
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {
                    listeners[utteranceId]?.onStart(utteranceId)
                }

                override fun onDone(utteranceId: String) {
                    listeners.remove(utteranceId)?.onDone(utteranceId)
                }

                // Base class declares the one-arg form abstract; dispatch there too.
                @Deprecated("deprecated in the platform API", ReplaceWith("onError(utteranceId, -1)"))
                override fun onError(utteranceId: String) {
                    onError(utteranceId, -1)
                }

                // Two-arg form is dispatched on API 23+ (minSdk is 26).
                override fun onError(utteranceId: String, errorCode: Int) {
                    listeners.remove(utteranceId)?.onError(
                        utteranceId,
                        "语音合成失败（错误码 $errorCode）"
                    )
                }
            })
            val langResult = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
            languageOk = langResult == TextToSpeech.LANG_AVAILABLE ||
                langResult == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                langResult == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            if (!languageOk) {
                initState = STATE_FAILED
                initError = "设备缺少中文语音数据，请在系统设置中下载中文语音"
                drainPendingWithError()
                return
            }
            engine.setSpeechRate(1.0f)
            engine.setPitch(1.0f)
            initState = STATE_OK
            drainPending()
        }
    }

    private fun drainPending() {
        while (pending.isNotEmpty()) {
            val (sentence, id, listener) = pending.removeFirst()
            speakNow(sentence, id, listener)
        }
    }

    private fun drainPendingWithError() {
        while (pending.isNotEmpty()) {
            val (_, id, listener) = pending.removeFirst()
            listener.onError(id, initError)
        }
    }

    override fun isAvailable(): Boolean = initState == STATE_OK && languageOk

    override fun availabilityMessage(): String = when {
        initState == STATE_OK && languageOk -> "系统语音可用"
        initState == STATE_PENDING -> "语音引擎初始化中"
        else -> initError
    }

    override fun speak(sentence: String, utteranceId: String, listener: TtsListener) {
        synchronized(lock) {
            when (initState) {
                STATE_PENDING -> {
                    pending.addLast(Triple(sentence, utteranceId, listener))
                    return
                }
                STATE_FAILED -> {
                    listener.onError(utteranceId, initError)
                    return
                }
                else -> speakNow(sentence, utteranceId, listener)
            }
        }
    }

    private fun speakNow(sentence: String, utteranceId: String, listener: TtsListener) {
        val engine = tts
        if (engine == null) {
            listener.onError(utteranceId, "系统语音引擎已释放")
            return
        }
        listeners[utteranceId] = listener
        val result = engine.speak(sentence, TextToSpeech.QUEUE_ADD, null, utteranceId)
        if (result == TextToSpeech.ERROR) {
            listeners.remove(utteranceId)
            listener.onError(utteranceId, "语音合成请求被拒绝")
        }
    }

    override fun stop() {
        synchronized(lock) {
            // Drop listeners first so late callbacks from stop() are ignored.
            listeners.clear()
            pending.clear()
        }
        try {
            tts?.stop()
        } catch (_: Exception) {
            // stop() on a dead engine must never crash the app.
        }
    }

    override fun shutdown() {
        stop()
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
    }
}
