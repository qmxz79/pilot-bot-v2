package com.qmxz.pilotbotv2.voice

import android.util.Base64
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * iFlytek WebSocket auth (same scheme for TTS and ASR).
 *
 * From the official docs: the client builds an RFC1123 GMT date, signs
 * "host: <host>\ndate: <date>\nGET <path> HTTP/1.1" with HMAC-SHA256 keyed
 * by the API Secret, base64-encodes the signature into an Authorization
 * header value, then passes authorization/date/host as URL query params.
 *
 * Keys are never stored here: they arrive per call from [VoiceSettings],
 * which the user fills in the in-app Settings dialog (device-local only).
 */
object XfyunAuth {

    /** Signed WebSocket URL for one iFlytek endpoint. */
    fun buildUrl(
        host: String,
        path: String,
        apiKey: String,
        apiSecret: String
    ): String {
        val date = rfc1123Now()
        val signatureOrigin = "host: $host\ndate: $date\nGET $path HTTP/1.1"
        val signature = hmacSha256Base64(apiSecret, signatureOrigin)
        val authorizationOrigin =
            "api_key=\"$apiKey\", algorithm=\"hmac-sha256\", " +
                "headers=\"host date request-line\", signature=\"$signature\""
        val authorization = Base64.encodeToString(
            authorizationOrigin.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        return "wss://$host$path" +
            "?authorization=${urlEncode(authorization)}" +
            "&date=${urlEncode(date)}" +
            "&host=${urlEncode(host)}"
    }

    private fun rfc1123Now(): String {
        val format = SimpleDateFormat(
            "EEE, dd MMM yyyy HH:mm:ss z",
            Locale.US
        )
        format.timeZone = TimeZone.getTimeZone("GMT")
        return format.format(Date())
    }

    private fun hmacSha256Base64(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(key)
        val digest = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    private fun urlEncode(value: String): String {
        // URLEncoder turns spaces into '+'; query params want %20.
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
