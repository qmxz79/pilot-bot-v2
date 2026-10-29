package com.qmxz.pilotbotv2.voice

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * iFlytek cloud TTS over WebSocket, implementing [TtsProvider] directly so
 * the pipeline needs no change.
 *
 * Protocol (official docs, v2/tts):
 * - Auth: [XfyunAuth] signed URL, host tts-api.xfyun.cn.
 * - One WebSocket session per sentence: the pipeline speaks sentence by
 *   sentence anyway, and per-sentence sessions keep barge-in latency at
 *   one sentence instead of one paragraph. Handshake cost (~200ms) is the
 *   trade-off, accepted for v2.
 * - Request frame: common{app_id}, business{aue=raw,
 *   auf=audio/L16;rate=16000, vcn, tte=UTF8}, data{status=2, text=base64}.
 * - Response frames carry base64 16k PCM in data.audio; data.status=2 is
 *   the last frame. PCM is written to an AudioTrack as it arrives, so the
 *   first sound plays before the whole sentence is synthesised.
 *
 * Interruption: [stop] bumps the generation (late socket callbacks are
 * dropped), closes the socket, clears the chunk queue, and releases the
 * AudioTrack — speech stops immediately, never at the next sentence.
 *
 * Availability is configuration-based, not network-verified: the health
 * bar reports "已配置" rather than "已连通", because a network probe on
 * every self-check would cost a full synthesis round-trip.
 *
 * Stall behaviour: with no read timeout, a silently dead connection stalls
 * the sentence queue until the user hits interrupt (which calls [stop] and
 * frees the pipeline). That is the v2 trade-off for uninterrupted streams;
 * a watchdog would need a heartbeat the iFlytek TTS protocol does not offer.
 */
class XfyunTtsProvider(
    private val settings: VoiceSettings,
    private val client: OkHttpClient = defaultClient()
) : TtsProvider {

    @Volatile
    private var generation: Int = 0

    @Volatile
    private var currentSocket: WebSocket? = null

    @Volatile
    private var currentTrack: AudioTrack? = null

    private val chunkQueue = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var streamEnded: Boolean = false

    private val finishLock = Any()

    @Volatile
    private var finished: Boolean = false

    @Volatile
    private var started: Boolean = false

    private var playbackThread: Thread? = null

    override fun isAvailable(): Boolean = settings.isXfyunConfigured

    override fun availabilityMessage(): String {
        return if (settings.isXfyunConfigured) {
            "讯飞云语音已配置"
        } else {
            "未配置讯飞 Key，请在设置页填写"
        }
    }

    override fun speak(sentence: String, utteranceId: String, listener: TtsListener) {
        if (sentence.isBlank()) {
            listener.onDone(utteranceId)
            return
        }
        if (!settings.isXfyunConfigured) {
            listener.onError(utteranceId, "未配置讯飞 Key，请在设置页填写")
            return
        }
        // A new sentence replaces any in-flight one (same rule as the
        // system provider: the pipeline already stopped it first, but
        // never trust a half-torn-down socket to stay quiet).
        // Bump FIRST so late callbacks from the old session are dropped
        // before the new session resets shared state (queue, flags).
        val myGen = ++generation
        stopCurrent()
        synchronized(finishLock) {
            finished = false
        }
        started = false
        streamEnded = false
        chunkQueue.clear()

        val url = XfyunAuth.buildUrl(
            TTS_HOST,
            TTS_PATH,
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
                    put("aue", "raw")
                    put("auf", "audio/L16;rate=16000")
                    put("vcn", settings.xfyunVoice)
                    put("tte", "UTF8")
                })
                frame.put("data", JSONObject().apply {
                    put("status", 2)
                    put(
                        "text",
                        Base64.encodeToString(
                            sentence.toByteArray(Charsets.UTF_8),
                            Base64.NO_WRAP
                        )
                    )
                })
                webSocket.send(frame.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (myGen != generation) return
                try {
                    val json = JSONObject(text)
                    val code = json.optInt("code", 0)
                    if (code != 0) {
                        fail(
                            myGen,
                            utteranceId,
                            listener,
                            "讯飞语音合成失败：${json.optString("message")}（错误码 $code）"
                        )
                        return
                    }
                    val data = json.optJSONObject("data") ?: return
                    val audio = data.optString("audio")
                    if (audio.isNotEmpty()) {
                        try {
                            chunkQueue.offer(
                                Base64.decode(audio, Base64.DEFAULT)
                            )
                        } catch (_: Exception) {
                            // One bad chunk must not kill the sentence.
                        }
                    }
                    if (data.optInt("status", 0) == 2) {
                        streamEnded = true
                    }
                } catch (_: Exception) {
                    // A malformed frame is ignored; the stream either
                    // recovers on the next frame or ends without audio,
                    // in which case the playback thread reports done.
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (myGen != generation) return
                val reason = t.message?.takeIf { it.isNotBlank() } ?: "未知原因"
                fail(myGen, utteranceId, listener, "讯飞语音连接失败：$reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (myGen != generation) return
                // Server closes after the last frame; the playback thread
                // finishes once the queue drains. Only a close WITHOUT the
                // last frame is a real interruption.
                if (!streamEnded) {
                    fail(myGen, utteranceId, listener, "讯飞语音连接中断")
                }
            }
        }
        currentSocket = client.newWebSocket(request, socketListener)
        playbackThread = Thread({
            runPlayback(myGen, utteranceId, listener)
        }, "xfyun-tts-playback").also { it.start() }
    }

    private fun runPlayback(myGen: Int, utteranceId: String, listener: TtsListener) {
        try {
            while (myGen == generation) {
                val chunk = try {
                    chunkQueue.poll(100, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    continue
                }
                if (chunk != null) {
                    val track = ensureTrack(myGen, utteranceId, listener) ?: return
                    val written = try {
                        track.write(chunk, 0, chunk.size)
                    } catch (_: Exception) {
                        -1
                    }
                    if (written < 0) {
                        fail(myGen, utteranceId, listener, "音频播放失败")
                        return
                    }
                    if (!started && myGen == generation) {
                        started = true
                        listener.onStart(utteranceId)
                    }
                } else if (streamEnded && chunkQueue.isEmpty()) {
                    done(myGen, utteranceId, listener)
                    return
                }
            }
        } catch (_: Exception) {
            // Stopped mid-playback (track released under us): silent exit,
            // the generation check is what separates this from a real error.
        }
    }

    /** Lazily create (once per sentence) and start the AudioTrack. */
    private fun ensureTrack(
        myGen: Int,
        utteranceId: String,
        listener: TtsListener
    ): AudioTrack? {
        currentTrack?.let { return it }
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            fail(myGen, utteranceId, listener, "音频播放器初始化失败")
            return null
        }
        return try {
            val track = AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2,
                AudioTrack.MODE_STREAM
            )
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                fail(myGen, utteranceId, listener, "音频播放器初始化失败")
                return null
            }
            track.play()
            currentTrack = track
            track
        } catch (_: Exception) {
            fail(myGen, utteranceId, listener, "音频播放器初始化失败")
            null
        }
    }

    private fun done(myGen: Int, utteranceId: String, listener: TtsListener) {
        synchronized(finishLock) {
            if (finished || myGen != generation) return
            finished = true
        }
        closeSocket()
        releaseTrack()
        listener.onDone(utteranceId)
    }

    private fun fail(
        myGen: Int,
        utteranceId: String,
        listener: TtsListener,
        message: String
    ) {
        synchronized(finishLock) {
            if (finished || myGen != generation) return
            finished = true
        }
        closeSocket()
        chunkQueue.clear()
        releaseTrack()
        listener.onError(utteranceId, message)
    }

    override fun stop() {
        // Bump first: every in-flight callback and the playback thread
        // check the generation and exit silently from here on.
        generation++
        closeSocket()
        chunkQueue.clear()
        streamEnded = false
        playbackThread?.interrupt()
        playbackThread = null
        releaseTrack()
    }

    /** Same teardown as [stop] but without invalidating the next session. */
    private fun stopCurrent() {
        closeSocket()
        chunkQueue.clear()
        streamEnded = false
        playbackThread?.interrupt()
        playbackThread = null
        releaseTrack()
    }

    override fun shutdown() {
        stop()
    }

    private fun closeSocket() {
        try {
            currentSocket?.close(1000, null)
        } catch (_: Exception) {
        }
        currentSocket = null
    }

    private fun releaseTrack() {
        val track = currentTrack
        currentTrack = null
        if (track == null) return
        try {
            track.pause()
        } catch (_: Exception) {
        }
        try {
            track.flush()
        } catch (_: Exception) {
        }
        try {
            track.release()
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val TTS_HOST = "tts-api.xfyun.cn"
        const val TTS_PATH = "/v2/tts"
        const val SAMPLE_RATE = 16000

        /** No read timeout: a sentence stream may pause between frames. */
        fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }
}
