package com.qmxz.pilotbotv2.voice

import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs

/**
 * Lightweight voice-activity detection used ONLY while TTS is playing.
 *
 * Why it exists: barge-in. While the companion speaks, this detector watches
 * the microphone; when sustained human-level amplitude is seen, the pipeline
 * interrupts TTS and switches to ASR. It never runs at the same time as
 * SpeechRecognizer (both want the mic) — the pipeline stops it before
 * starting ASR, and restarts it only for the next utterance batch.
 *
 * Tuning notes (car cabin):
 * - Source is VOICE_COMMUNICATION so the platform's echo canceller suppresses
 *   the companion's own speaker output; without it, TTS would self-trigger.
 * - [threshold] is a peak-amplitude bar (max 32767). 2800 is a starting point
 *   for a car at speed; raise it if road noise causes false interruptions,
 *   lower it if soft speech is missed.
 * - [sustainMs] debounces: a single loud bump (pothole, door) must not
 *   interrupt; only sustained voice does.
 */
class VadDetector(
    private val threshold: Int = 2800,
    private val sustainMs: Long = 250,
    private val cooldownMs: Long = 1200
) {
    @Volatile
    private var running: Boolean = false
    private var worker: Thread? = null
    private var recorder: AudioRecord? = null

    /**
     * Starts detection. Returns false (and calls [onError]) when the mic is
     * unavailable — the caller must then continue WITHOUT barge-in (the
     * interrupt button still works) and say so, never pretend detection works.
     */
    fun start(
        onVoice: () -> Unit,
        onAmplitude: (Float) -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        if (running) return true
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            onError("打断检测不可用：设备不支持录音参数")
            return false
        }
        return try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                onError("打断检测不可用：录音器初始化失败")
                return false
            }
            recorder = record
            running = true
            worker = Thread({
                runLoop(record, onVoice, onAmplitude, onError)
            }, "vad-detector").also { it.start() }
            true
        } catch (e: SecurityException) {
            onError("打断检测不可用：没有录音权限")
            false
        } catch (e: Exception) {
            onError("打断检测启动失败：${e.message}")
            false
        }
    }

    private fun runLoop(
        record: AudioRecord,
        onVoice: () -> Unit,
        onAmplitude: (Float) -> Unit,
        onError: (String) -> Unit
    ) {
        val buffer = ShortArray(1024)
        var aboveSince: Long = 0
        var lastTrigger: Long = 0
        try {
            record.startRecording()
            while (running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                var peak = 0
                for (i in 0 until read) {
                    val v = abs(buffer[i].toInt())
                    if (v > peak) peak = v
                }
                onAmplitude((peak / 32767f).coerceIn(0f, 1f))
                val now = System.currentTimeMillis()
                if (peak > threshold) {
                    if (aboveSince == 0L) aboveSince = now
                    if (now - aboveSince >= sustainMs && now - lastTrigger >= cooldownMs) {
                        lastTrigger = now
                        aboveSince = 0L
                        onVoice()
                    }
                } else {
                    aboveSince = 0L
                }
            }
        } catch (e: Exception) {
            if (running) {
                onError("打断检测中断：${e.message}")
            }
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
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
        // The worker thread releases the recorder itself in its finally block.
        // If it is stuck, release here as well; release() is idempotent-safe
        // only when called once, so guard with a state check.
        try {
            val current = recorder
            if (current != null && current.recordingState == AudioRecord.RECORDSTATE_STOPPED) {
                current.release()
                recorder = null
            }
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16000
    }
}
