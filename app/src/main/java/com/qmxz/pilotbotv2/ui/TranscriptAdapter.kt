package com.qmxz.pilotbotv2.ui

import android.graphics.Color
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

enum class TranscriptRole { USER, ASSISTANT, SYSTEM }

/** One transcript row. [timeMs] is informational only (no timestamps shown). */
data class TranscriptItem(
    val role: TranscriptRole,
    val text: String,
    val timeMs: Long = System.currentTimeMillis()
)

/**
 * Append-only transcript renderer.
 *
 * Deliberately NOT a RecyclerView.Adapter: the transcript is short-lived and
 * append-only, and this project keeps its dependency list minimal (no
 * recyclerview artifact). Rows are plain TextViews in the container
 * LinearLayout, capped at [MAX_ROWS] so a long drive cannot grow the view
 * tree without bound. Auto-scroll is the Activity's job (it owns the
 * ScrollView); this class only appends rows.
 */
class TranscriptAdapter(private val container: LinearLayout) {

    fun add(item: TranscriptItem) {
        val context = container.context
        val view = TextView(context).apply {
            text = when (item.role) {
                TranscriptRole.USER -> "你：${item.text}"
                TranscriptRole.ASSISTANT -> "小搭：${item.text}"
                TranscriptRole.SYSTEM -> "系统：${item.text}"
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(
                when (item.role) {
                    TranscriptRole.USER -> Color.parseColor("#FFFFFF")
                    TranscriptRole.ASSISTANT -> Color.parseColor("#FFB300")
                    TranscriptRole.SYSTEM -> Color.parseColor("#9E9E9E")
                }
            )
            setPadding(0, 8, 0, 8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(view)
        while (container.childCount > MAX_ROWS) {
            container.removeViewAt(0)
        }
    }

    fun clear() {
        container.removeAllViews()
    }

    private companion object {
        const val MAX_ROWS = 200
    }
}
