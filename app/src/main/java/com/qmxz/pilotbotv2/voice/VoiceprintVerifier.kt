package com.qmxz.pilotbotv2.voice

import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Outcome of a speaker check. Unavailable means "cannot tell" (not
 * configured, network failure, or an unparseable reply) — callers fail
 * OPEN on it: a dead verification service must not mute the owner.
 */
sealed class VoiceprintCheck {
    data class Scored(val score: Float) : VoiceprintCheck()
    object Unavailable : VoiceprintCheck()
}

/**
 * Decides whether a clip of 16k mono PCM16 is the enrolled owner.
 * A null verifier in the pipeline means "no gating": every utterance
 * is accepted, exactly as before enrollment existed.
 */
interface SpeakerVerifier {
    val isEnrolled: Boolean
    fun verify(audioPcm16k: ByteArray, onResult: (VoiceprintCheck) -> Unit)
}

/**
 * iFlytek voiceprint API (new isv API, POST api.xf-yun.com/v1/private/s1aa729d0).
 *
 * Protocol (official docs):
 * - Auth: the same HMAC-SHA256 scheme as TTS/ASR ([XfyunAuth]), but the
 *   request-line is POST and the scheme is https.
 * - Body is one JSON object: header{app_id, status:3},
 *   parameter.s1aa729d0{func, groupId, ..., <func>Res{encoding:utf-8,
 *   compress:raw, format:json}}, and for audio funcs
 *   payload.resource{encoding:raw, sample_rate:16000, channels:1,
 *   bit_depth:16, status:3, audio:base64}.
 * - Response: header.code==0 is session-OK; payload.<func>Res.text is
 *   base64 JSON with the real result. 1:N search returns scoreList
 *   (best first); the vendor FAQ recommends 0.6-1 as pass.
 *
 * Audio is sent as RAW PCM16, not WAV-wrapped: the payload describes
 * sample_rate/channels/bit_depth explicitly, which a WAV header would
 * make redundant. If the server ever rejects raw audio, wrapping a
 * 44-byte WAV header is the first thing to try.
 *
 * Results arrive on the main thread. Nothing here throws.
 */
class XfyunVoiceprintVerifier(
    private val settings: VoiceSettings,
    private val client: OkHttpClient = defaultClient()
) : SpeakerVerifier {

    private val mainHandler = Handler(Looper.getMainLooper())

    override val isEnrolled: Boolean
        get() = settings.isVoiceprintEnrolled

    /**
     * 1:N search against the owner's group: one call, best score wins.
     * Every feature in the group is the owner's, so the top score IS the
     * owner-similarity — no per-feature calls, no feature id to store.
     */
    override fun verify(audioPcm16k: ByteArray, onResult: (VoiceprintCheck) -> Unit) {
        val groupId = settings.voiceprintGroupId
        if (groupId.isBlank() || !settings.isXfyunConfigured) {
            post { onResult(VoiceprintCheck.Unavailable) }
            return
        }
        val params = JSONObject().apply {
            put("func", "searchFea")
            put("groupId", groupId)
            put("topK", 1)
            put("searchFeaRes", resBlock())
        }
        call(params, audioPcm16k) { decoded, _ ->
            if (decoded == null) {
                post { onResult(VoiceprintCheck.Unavailable) }
                return@call
            }
            val list = decoded.optJSONArray("scoreList")
            val score = if (list != null && list.length() > 0) {
                list.optJSONObject(0)?.optDouble("score", 0.0) ?: 0.0
            } else {
                // No match at all: a score of zero, i.e. a reject.
                0.0
            }
            post { onResult(VoiceprintCheck.Scored(score.toFloat())) }
        }
    }

    /** Create a group; onResult(true, groupId) on success. */
    fun createGroup(groupId: String, onResult: (Boolean, String?) -> Unit) {
        if (!settings.isXfyunConfigured) {
            post { onResult(false, "未配置讯飞 Key，请在设置页填写") }
            return
        }
        val params = JSONObject().apply {
            put("func", "createGroup")
            put("groupId", groupId)
            put("groupName", "pilotbotv2")
            put("createGroupRes", resBlock())
        }
        call(params, null) { decoded, error ->
            post {
                if (decoded != null) {
                    onResult(true, decoded.optString("groupId", groupId))
                } else {
                    onResult(false, error ?: "创建声纹库失败")
                }
            }
        }
    }

    /** Add one enrollment clip to a group. */
    fun addFeature(
        groupId: String,
        featureId: String,
        audioPcm16k: ByteArray,
        onResult: (Boolean, String?) -> Unit
    ) {
        if (!settings.isXfyunConfigured) {
            post { onResult(false, "未配置讯飞 Key") }
            return
        }
        val params = JSONObject().apply {
            put("func", "createFeature")
            put("groupId", groupId)
            put("featureId", featureId)
            put("featureInfo", featureId)
            put("createFeatureRes", resBlock())
        }
        call(params, audioPcm16k) { decoded, error ->
            post {
                if (decoded != null) onResult(true, null)
                else onResult(false, error ?: "添加声纹特征失败")
            }
        }
    }

    /** Best-effort delete; always calls back (callers never block on it). */
    fun deleteGroup(groupId: String, onDone: () -> Unit) {
        if (!settings.isXfyunConfigured || groupId.isBlank()) {
            post { onDone() }
            return
        }
        val params = JSONObject().apply {
            put("func", "deleteGroup")
            put("groupId", groupId)
            put("deleteGroupRes", resBlock())
        }
        call(params, null) { _, _ -> post { onDone() } }
    }

    private fun resBlock(): JSONObject = JSONObject().apply {
        put("encoding", "utf-8")
        put("compress", "raw")
        put("format", "json")
    }

    /**
     * One API round-trip. Decodes the response envelope; on success
     * [onDone] gets the base64-decoded result JSON, else null + a
     * human-readable error. Runs on a worker thread; [onDone] is
     * invoked on that thread (callers post to main themselves).
     */
    private fun call(
        funcParams: JSONObject,
        audioPcm16k: ByteArray?,
        onDone: (JSONObject?, String?) -> Unit
    ) {
        Thread({
            try {
                val url = XfyunAuth.buildUrl(
                    HOST, PATH,
                    settings.xfyunApiKey, settings.xfyunApiSecret,
                    method = "POST", scheme = "https"
                )
                val body = JSONObject().apply {
                    put("header", JSONObject().apply {
                        put("app_id", settings.xfyunAppId)
                        put("status", 3)
                    })
                    put("parameter", JSONObject().apply {
                        put("s1aa729d0", funcParams)
                    })
                    if (audioPcm16k != null) {
                        put("payload", JSONObject().apply {
                            put("resource", JSONObject().apply {
                                put("encoding", "raw")
                                put("sample_rate", 16000)
                                put("channels", 1)
                                put("bit_depth", 16)
                                put("status", 3)
                                put(
                                    "audio",
                                    Base64.encodeToString(audioPcm16k, Base64.NO_WRAP)
                                )
                            })
                        })
                    }
                }
                val request = Request.Builder().url(url)
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                val responseText = client.newCall(request).execute().use { resp ->
                    resp.body?.string().orEmpty()
                }
                val json = JSONObject(responseText)
                val header = json.optJSONObject("header")
                val code = header?.optInt("code", -1) ?: -1
                if (code != 0) {
                    val message = header?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: "声纹服务返回错误 $code"
                    onDone(null, message)
                    return@Thread
                }
                // Each func's response carries exactly one payload.*Res
                // block; find its text (the block name differs per func).
                val payload = json.optJSONObject("payload")
                var text: String? = null
                if (payload != null) {
                    val keys = payload.keys()
                    while (keys.hasNext()) {
                        val t = payload.optJSONObject(keys.next())?.optString("text")
                        if (!t.isNullOrEmpty()) {
                            text = t
                            break
                        }
                    }
                }
                if (text == null) {
                    onDone(null, "声纹服务返回为空")
                    return@Thread
                }
                val decoded = JSONObject(
                    String(Base64.decode(text, Base64.DEFAULT), Charsets.UTF_8)
                )
                onDone(decoded, null)
            } catch (e: Exception) {
                onDone(null, "声纹服务调用失败：${e.message}")
            }
        }, "xfyun-voiceprint").also { it.start() }
    }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }

    private companion object {
        const val HOST = "api.xf-yun.com"
        const val PATH = "/v1/private/s1aa729d0"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
