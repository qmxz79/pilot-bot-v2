package com.qmxz.pilotbotv2.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/**
 * The ONLY visual identity of the companion: a minimal voice waveform.
 *
 * v1 lesson: the 3D avatar was stiff, tiny, and pulled the driver's eyes to
 * the screen — the opposite of what a driving companion should do. v2 draws
 * 24 bars on a Canvas (no GL, no model files, no extra dependency):
 * - speaking: tall, fast animation;
 * - listening: gentle, slow pulse;
 * - idle: a flat dim line.
 *
 * Animation is time-based and self-invalidating only while active, so an
 * idle view costs nothing.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB300")
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
    }

    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5D4037")
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    private var speaking: Boolean = false
    private var listening: Boolean = false

    fun setSpeaking(active: Boolean) {
        if (speaking != active) {
            speaking = active
            invalidate()
        }
    }

    fun setListening(active: Boolean) {
        if (listening != active) {
            listening = active
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerY = height / 2f
        if (!speaking && !listening) {
            canvas.drawLine(
                paddingLeft.toFloat(),
                centerY,
                (width - paddingRight).toFloat(),
                centerY,
                idlePaint
            )
            return
        }
        val barCount = 24
        val spacing = width.toFloat() / (barCount + 1)
        val maxHeight = height * 0.42f
        val time = System.currentTimeMillis() / 120.0
        for (i in 0 until barCount) {
            val phase = time + i * 0.55
            val amplitude = if (speaking) {
                0.35 + 0.65 * abs(sin(phase))
            } else {
                0.2 + 0.3 * abs(sin(phase * 0.6))
            }
            val barHeight = (maxHeight * amplitude).toFloat()
            val x = spacing * (i + 1)
            canvas.drawLine(x, centerY - barHeight, x, centerY + barHeight, barPaint)
        }
        // Keep animating only while active.
        postInvalidateOnAnimation()
    }
}
