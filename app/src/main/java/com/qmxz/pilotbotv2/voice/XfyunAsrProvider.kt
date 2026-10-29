package com.qmxz.pilotbotv2.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * iFlytek cloud streaming ASR over WebSocket, implementing [AsrProvider].
 *
 * Protocol (official docs, v2/iat):
 * - Auth: [XfyunAuth] signed URL, host iat-api.xfyun.cn.
 * - First frame: common{app_id}, business{language=zh_cn, domain=iat,
 *   accent=mandarin}, data{status=0, format=audio/L16;rate=16000,
 *   encoding=raw}. Then mic frames (status=1, 1280 bytes = 40ms of 16k
 *   mono PCM16 each), and a final empty frame (status=2) on stop.
 * - Interim results arrive per frame; data.result.ws[].cw[].w is the text,
 *   data.result.sn numbers the sentence, data.result.pgs is "apd"
 *   (append) or "rpl" (replace sentences rg[0]..rg[1]), and
 *   data.result.ls=true marks the last sentence of the utterance.
 *
 * Interim text is accumulated with pgs handling (not naive append, which
 * would duplicate words whenever the server revises a sentence) and
 * delivered as [AsrListener.onPartial]; the session finalises on ls=true
 * or, after stop, on socket close with whatever was heard.
 *
 * Mic ownership follows the pipeline's rule (LISTENING only): this provider
 * owns AudioRecord while a session runs and releases it on every exit path,
 * so VAD can take the mic back for the next spoken reply.
 */
class XfyunAsrProvider(
    private val settings: VoiceSettings,
    private val client: OkHttpClient = defaultClient()
) : AsrProvider {

    @Volatile
    private var generation: Int = 0

    @Volatile
    private var currentSocket: WebSocket? = null

    @Volatile
    private var recorder: AudioRecord? = null

    @Volatile
    private var micRunning: Boolean = false

    private var micThread: Thread? = null

    /** Sentence-numbered interim text, so "rpl" revisions replace, not duplicate. */
    private val sentences = TreeMap<Int, String>()

    private val finishLock = Any()

    @Volatile
    private var finalSent: Boolean = false

    @Volatile
    private var status2Sent: Boolean = false

    @Volatile
    private var sessionActive: Boolean = false

    override fun isAvailable(): Boolean = settings.isXfyunConfigured

    override fun availabilityMessage(): String {
        return if (settings.isXfyunConfigured) {
            "讯飞云识别已配置"
        } else {
            "未配置讯飞 Key，请在设置页填写"
        }
    }

    override fun startListening(listener: AsrListener) {
        if (!settings.isXfyunConfigured) {
            listener.onError("未配置讯飞 Key，请在设置页填写", false)
            return
        }
        // Bump FIRST so late callbacks from the old session are dropped
        // before the new session resets shared state (sentence map, flags).
        val myGen = ++generation
        stopCurrent()
        synchronized(finishLock) {
            finalSent = false
        }
        status2Sent = false
        sentences.clear()
        sessionActive = true

        val url = XfyunAuth.buildUrl(
            ASR_HOST,
            ASR_PATH,
            settings.xfyunApiKey,
            settings.xfyunApiSecret
        )
        val request = Request.Builder().url(url).build()
        val socketListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (myGen != generation) return
                val frame = JSONObject()
                frame.put(
                    "common",
                    JSONObject().put("app_id", settings.xfyunAppId)
                )
                frame.put("business", JSONObject().apply {
                    put("language", "zh_cn")
                    put("domain", "iat")
                    put("accent", "mandarin")
                })
                frame.put("data", JSONObject().apply {
                    put("status", 0)
                    put("format", "audio/L16;rate=16000")
                    put("encoding", "raw")
                })
                if (webSocket.send(frame.toString())) {
                    startMic(myGen, webSocket, listener)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (myGen != generation) return
                try {
                    val json = JSONObject(text)
                    val code = json.optInt("code", 0)
                    if (code != 0) {
                        errorOut(
                            myGen,
                            listener,
                            "讯飞识别失败：${json.optString("message")}（错误码 $code）",
                            true
                        )
                        return
                    }
                    val result = json.optJSONObject("data")
                        ?.optJSONObject("result") ?: return
                    val ws = result.optJSONArray("ws") ?: JSONArray()
                    val textBuilder = StringBuilder()
                    for (i in 0 until ws.length()) {
                        val cw = ws.optJSONObject(i)?.optJSONArray("cw")
                        textBuilder.append(cw?.optJSONObject(0)?.optString("w") ?: "")
                    }
                    val sentenceText = textBuilder.toString()
                    if (sentenceText.isEmpty()) return
                    val sn = result.optInt("sn", 0)
                    if (result.optString("pgs") == "rpl") {
                        val rg = result.optJSONArray("rg")
                        if (rg != null && rg.length() >= 2) {
                            val from = rg.optInt(0)
                            val to = rg.optInt(1)
                            sentences.keys.filter { it in from..to }
                                .forEach { sentences.remove(it) }
                        }
                    }
                    sentences[sn] = sentenceText
                    val full = sentences.values.joinToString("")
                    if (full.isNotEmpty()) {
                        listener.onPartial(full)
                    }
                    if (result.optBoolean("ls", false)) {
                        finishSession(myGen, listener, full)
                    }
                } catch (_: Exception) {
                    // A malformed interim frame is ignored; later frames
                    // still carry the sentence (or ls) the session needs.
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (myGen != generation) return
                val reason = t.message?.takeIf { it.isNotBlank() } ?: "未知原因"
                errorOut(myGen, listener, "讯飞识别连接失败：$reason", true)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (myGen != generation) return
                if (status2Sent) {
                    // We ended the utterance ourselves: whatever was heard
                    // is the result (empty means "didn't catch it").
                    finishSession(
                        myGen,
                        listener,
                        sentences.values.joinToString("")
                    )
                } else {
                    errorOut(myGen, listener, "讯飞识别连接中断", true)
                }
            }
        }
        currentSocket = client.newWebSocket(request, socketListener)
    }

    private fun startMic(myGen: Int, socket: WebSocket, listener: AsrListener) {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            errorOut(myGen, listener, "录音器初始化失败", false)
            return
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2
            )
        } catch (e: SecurityException) {
            errorOut(myGen, listener, "没有录音权限，请在系统设置中允许录音", false)
            return
        } catch (e: Exception) {
            errorOut(myGen, listener, "录音器初始化失败：${e.message}", false)
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            errorOut(myGen, listener, "录音器初始化失败", false)
            return
        }
        recorder = record
        micRunning = true
        micThread = Thread({
            val shorts = ShortArray(FRAME_SHORTS)
            val bytes = ByteArray(FRAME_SHORTS * 2)
            try {
                record.startRecording()
                while (micRunning && myGen == generation) {
                    val read = try {
                        record.read(shorts, 0, shorts.size)
                    } catch (_: SecurityException) {
                        errorOut(
                            myGen,
                            listener,
                            "没有录音权限，请在系统设置中允许录音",
                            false
                        )
                        return@Thread
                    } catch (_: Exception) {
                        break
                    }
                    if (read <= 0) continue
                    for (i in 0 until read) {
                        val v = shorts[i].toInt()
                        bytes[i * 2] = (v and 0xFF).toByte()
                        bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                    }
                    val frame = JSONObject()
                    frame.put("data", JSONObject().apply {
                        put("status", 1)
                        put("format", "audio/L16;rate=16000")
                        put("encoding", "raw")
                        put(
                            "audio",
                            Base64.encodeToString(bytes, 0, read * 2, Base64.NO_WRAP)
                        )
                    })
                    if (!socket.send(frame.toString())) {
                        break
                    }
                }
            } catch (_: Exception) {
                // Mic died mid-session: the socket close / watchdog ends
                // the session; never crash the drive over a dead mic.
            } finally {
                try {
                    record.stop()
                } catch (_: Exception) {
                }
                try {
                    record.release()
                } catch (_: Exception) {
                }
                if (recorder === record) recorder = null
            }
        }, "xfyun-asr-mic").also { it.start() }
    }

    private fun finishSession(myGen: Int, listener: AsrListener, text: String) {
        synchronized(finishLock) {
            if (finalSent || myGen != generation) return
            finalSent = true
        }
        sessionActive = false
        stopMic()
        closeSocket(currentSocket)
        if (text.isNotEmpty()) {
            listener.onFinal(text)
        } else {
            listener.onError("没听清，请再说一遍", true)
        }
    }

    private fun errorOut(
        myGen: Int,
        listener: AsrListener,
        message: String,
        recoverable: Boolean
    ) {
        synchronized(finishLock) {
            if (finalSent || myGen != generation) return
            finalSent = true
        }
        sessionActive = false
        stopMic()
        closeSocket(currentSocket)
        listener.onError(message, recoverable)
    }

    override fun stopListening() {
        if (!sessionActive) return
        val socket = currentSocket
        val myGen = generation
        stopMic()
        if (socket != null) {
            val frame = JSONObject()
            frame.put("data", JSONObject().apply {
                put("status", 2)
                put("format", "audio/L16;rate=16000")
                put("encoding", "raw")
                put("audio", "")
            })
            socket.send(frame.toString())
            status2Sent = true
            // The server answers the final frame, then closes; if it
            // never does, close ourselves so the session cannot hang.
            Thread({
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                }
                if (myGen == generation) {
                    synchronized(finishLock) {
                        if (!finalSent) {
                            closeSocket(socket)
                        }
                    }
                }
            }, "xfyun-asr-close-watchdog").also { it.start() }
        } else {
            sessionActive = false
        }
    }

    override fun destroy() {
        generation++
        sessionActive = false
        stopMic()
        closeSocket(currentSocket)
    }

    /** Same teardown as [destroy] but without invalidating the next session. */
    private fun stopCurrent() {
        sessionActive = false
        stopMic()
        closeSocket(currentSocket)
    }

    private fun stopMic() {
        micRunning = false
        micThread?.interrupt()
        micThread = null
        val record = recorder
        recorder = null
        if (record == null) return
        try {
            record.stop()
        } catch (_: Exception) {
        }
        try {
            record.release()
        } catch (_: Exception) {
        }
    }

    private fun closeSocket(target: WebSocket?) {
        if (target == null) return
        try {
            target.close(1000, null)
        } catch (_: Exception) {
        }
        if (currentSocket === target) currentSocket = null
    }

    private companion object {
        const val ASR_HOST = "iat-api.xfyun.cn"
        const val ASR_PATH = "/v2/iat"
        const val SAMPLE_RATE = 16000

        /** 640 shorts = 1280 bytes = 40ms of 16k mono PCM16, per frame. */
        const val FRAME_SHORTS = 640

        /** No read timeout: dictation sessions pause between utterances. */
        fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }
}
