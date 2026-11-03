package com.qmxz.pilotbotv2.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Records one enrollment clip: fixed-length 16k mono PCM16 from
 * VOICE_COMMUNICATION — the same source the ASR path uses, so the
 * enrolled features match runtime verification conditions.
 *
 * Runs on the calling thread (callers use a worker thread). Returns
 * null when recording failed or the clip was near-silent: a silent
 * clip would poison the voiceprint group, so it is rejected here
 * instead of uploaded.
 */
object VoiceprintRecorder {

    /** Peak amplitude below this counts as silence (16-bit scale). */
    const val SILENCE_PEAK = 800

    fun recordClip(durationMs: Long): ByteArray? {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return null
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2
            )
        } catch (_: Exception) {
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        val out = ByteArrayOutputStream()
        val shorts = ShortArray(1024)
        var peak = 0
        try {
            record.startRecording()
            val endMs = System.currentTimeMillis() + durationMs
            while (System.currentTimeMillis() < endMs) {
                val read = record.read(shorts, 0, shorts.size)
                if (read <= 0) continue
                for (i in 0 until read) {
                    val v = shorts[i].toInt()
                    if (abs(v) > peak) peak = abs(v)
                    out.write(v and 0xFF)
                    out.write((v shr 8) and 0xFF)
                }
            }
        } catch (_: Exception) {
            return null
        } finally {
            try {
                record.stop()
            } catch (_: Exception) {
            }
            try {
                record.release()
            } catch (_: Exception) {
            }
        }
        if (peak < SILENCE_PEAK) return null
        return out.toByteArray()
    }

    private const val SAMPLE_RATE = 16000
}
