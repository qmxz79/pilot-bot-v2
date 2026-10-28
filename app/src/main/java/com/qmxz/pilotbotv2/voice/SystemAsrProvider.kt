package com.qmxz.pilotbotv2.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Default ASR implementation backed by Android's system SpeechRecognizer.
 *
 * Notes:
 * - RecognitionListener callbacks are delivered on the main thread by the
 *   framework, but the pipeline re-posts everything through its own handler
 *   anyway, so this class makes no threading assumptions.
 * - The recogniser is created lazily and recreated after destroy(), because a
 *   recogniser that hit ERROR_RECOGNIZER_BUSY or ERROR_CLIENT may stay broken.
 * - Some devices (notably ROMs without Google services) have no recognition
 *   service at all; [isAvailable] reports that honestly so the UI can offer
 *   the text-input fallback instead of a dead mic button.
 */
class SystemAsrProvider(context: Context) : AsrProvider, RecognitionListener {

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var listener: AsrListener? = null

    override fun isAvailable(): Boolean {
        return try {
            SpeechRecognizer.isRecognitionAvailable(appContext)
        } catch (_: Exception) {
            false
        }
    }

    override fun availabilityMessage(): String {
        return if (isAvailable()) {
            "系统语音识别可用"
        } else {
            "此设备没有语音识别服务（部分无谷歌服务的机型），可用文字输入代替"
        }
    }

    override fun startListening(listener: AsrListener) {
        if (!isAvailable()) {
            listener.onError("此设备没有语音识别服务，请用文字输入", false)
            return
        }
        this.listener = listener
        try {
            val current = recognizer ?: SpeechRecognizer.createSpeechRecognizer(appContext).also {
                it.setRecognitionListener(this)
                recognizer = it
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    Locale.SIMPLIFIED_CHINESE.toLanguageTag()
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Give the driver a little more silence time before giving up.
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    3000L
                )
            }
            current.startListening(intent)
        } catch (e: SecurityException) {
            this.listener = null
            listener.onError("没有录音权限，请在系统设置中允许录音", false)
        } catch (e: Exception) {
            this.listener = null
            listener.onError("语音识别启动失败：${e.message}", true)
        }
    }

    override fun stopListening() {
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }
        listener = null
    }

    override fun destroy() {
        stopListening()
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    // ---- RecognitionListener (only onResults/onPartialResults/onError matter) ----

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onError(error: Int) {
        val current = listener
        listener = null
        val (message, recoverable) = AsrErrorMapper.map(error)
        current?.onError(message, recoverable)
    }

    override fun onResults(results: Bundle?) {
        val current = listener
        listener = null
        val text = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (text.isNotEmpty()) {
            current?.onFinal(text)
        } else {
            current?.onError("没听清，请再说一遍", true)
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (text.isNotEmpty()) {
            listener?.onPartial(text)
        }
    }
}
