package com.qmxz.pilotbotv2.llm

import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One chat message. Role is "system", "user" or "assistant". */
data class ChatMessage(val role: String, val content: String)

/**
 * OpenAI-compatible chat client with SSE streaming, over OkHttp.
 *
 * - base_url / api_key / model come from [LlmSettings] (user-filled, device-local).
 * - [streamChat] is a suspend function running on Dispatchers.IO; ALL callbacks
 *   are re-posted to the main thread so callers (ViewModel -> voice pipeline)
 *   can use them directly without thread hops.
 * - [cancelCurrent] cancels the in-flight HTTP call; the stream loop then
 *   exits quietly (a cancelled call is not an error to report).
 * - Every failure path calls [onError] with a human-readable message — the
 *   v1 pattern of "LLM failed, screen shows an error, voice says nothing"
 *   is replaced by the ViewModel speaking a short apology plus the detail
 *   in the transcript.
 */
class LlmClient(
    private val settings: LlmSettings,
    private val client: OkHttpClient = defaultClient()
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var currentCall: Call? = null

    /** Lightweight connectivity check: GET {base_url}/models with auth. */
    fun ping(onResult: (ok: Boolean, message: String) -> Unit) {
        val key = settings.apiKey
        if (key.isBlank()) {
            mainHandler.post { onResult(false, "未配置 LLM Key，请在设置页填写") }
            return
        }
        val request = Request.Builder()
            .url("${settings.baseUrl.trimEnd('/')}/models")
            .addHeader("Authorization", "Bearer $key")
            .get()
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { onResult(false, "LLM 连接失败：${e.message}") }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val ok = it.isSuccessful
                    val message = if (ok) {
                        "LLM 连通正常"
                    } else {
                        "LLM 返回 HTTP ${it.code}"
                    }
                    mainHandler.post { onResult(ok, message) }
                }
            }
        })
    }

    /**
     * Stream a chat completion. [onToken] fires per content delta,
     * [onDone] once with the full text, [onError] instead of onDone on failure.
     */
    suspend fun streamChat(
        messages: List<ChatMessage>,
        temperature: Double = 0.8,
        onToken: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val key = settings.apiKey
        if (key.isBlank()) {
            mainHandler.post { onError("未配置 LLM Key，请在设置页填写") }
            return
        }
        withContext(Dispatchers.IO) {
            val bodyJson = JSONObject().apply {
                put("model", settings.model)
                put("stream", true)
                put("temperature", temperature)
                val array = JSONArray()
                messages.forEach { msg ->
                    array.put(
                        JSONObject().apply {
                            put("role", msg.role)
                            put("content", msg.content)
                        }
                    )
                }
                put("messages", array)
            }
            val request = Request.Builder()
                .url("${settings.baseUrl.trimEnd('/')}/chat/completions")
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val call = client.newCall(request)
            currentCall = call
            var response: Response? = null
            try {
                response = call.execute()
                if (!response.isSuccessful) {
                    val errorBody = try {
                        response.body?.string().orEmpty()
                    } catch (_: Exception) {
                        ""
                    }
                    val detail = parseErrorMessage(errorBody) ?: "HTTP ${response.code}"
                    mainHandler.post { onError("LLM 请求失败：$detail") }
                    return@withContext
                }
                val source = response.body?.source()
                if (source == null) {
                    mainHandler.post { onError("LLM 返回空响应") }
                    return@withContext
                }
                val full = StringBuilder()
                while (true) {
                    val line = try {
                        source.readUtf8Line()
                    } catch (e: IOException) {
                        if (call.isCanceled()) return@withContext else throw e
                    } ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") {
                        if (data == "[DONE]") break else continue
                    }
                    try {
                        val delta = JSONObject(data)
                            .optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("delta")
                            ?.optString("content")
                            .orEmpty()
                        if (delta.isNotEmpty()) {
                            full.append(delta)
                            val copy = delta
                            mainHandler.post { onToken(copy) }
                        }
                    } catch (_: JSONException) {
                        // Skip one malformed chunk; the stream continues.
                    }
                }
                val result = full.toString()
                mainHandler.post { onDone(result) }
            } catch (e: IOException) {
                if (!call.isCanceled()) {
                    val message = e.message ?: "未知网络错误"
                    mainHandler.post { onError("网络错误：$message") }
                }
                // Cancelled calls exit quietly: the user interrupted on purpose.
            } catch (e: Exception) {
                if (!call.isCanceled()) {
                    mainHandler.post { onError("LLM 请求异常：${e.message}") }
                }
            } finally {
                try {
                    response?.close()
                } catch (_: Exception) {
                }
                if (currentCall === call) currentCall = null
            }
        }
    }

    /** Cancel the in-flight request, if any (user interruption). */
    fun cancelCurrent() {
        try {
            currentCall?.cancel()
        } catch (_: Exception) {
        }
    }

    private fun parseErrorMessage(body: String): String? {
        return try {
            JSONObject(body).optJSONObject("error")?.optString("message")
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                // Long read timeout: SSE streams stay open for the whole reply.
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
        }
    }
}
