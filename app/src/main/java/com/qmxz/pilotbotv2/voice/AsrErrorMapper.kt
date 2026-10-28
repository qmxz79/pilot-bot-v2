package com.qmxz.pilotbotv2.voice

import android.speech.SpeechRecognizer

/**
 * Maps SpeechRecognizer error codes to messages a driver can understand.
 *
 * The first element is the message, the second is whether tapping the mic
 * and trying again is reasonable. Soft failures (no match, timeout) are
 * recoverable; permission/service problems are not — the UI must say so
 * plainly instead of looping retries.
 */
object AsrErrorMapper {

    fun map(errorCode: Int): Pair<String, Boolean> = when (errorCode) {
        SpeechRecognizer.ERROR_AUDIO ->
            "麦克风被占用或不可用" to false
        SpeechRecognizer.ERROR_CLIENT ->
            "识别器内部错误，请重试" to true
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "没有录音权限，请在系统设置中允许录音" to false
        SpeechRecognizer.ERROR_NETWORK ->
            "网络不可用，语音识别需要联网" to true
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "网络超时，请重试" to true
        SpeechRecognizer.ERROR_NO_MATCH ->
            "没听清，请再说一遍" to true
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "识别器正忙，请稍后再试" to true
        SpeechRecognizer.ERROR_SERVER ->
            "识别服务出错，请稍后再试" to true
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "没听到说话，请再说一遍" to true
        else ->
            "语音识别失败（错误码 $errorCode）" to true
    }
}
