package com.qmxz.pilotbotv2.nav

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the Amap app's navigation notification currently says. */
data class AmapNavInfo(
    /** Full notification text, for display when parsing finds nothing better. */
    val rawText: String,
    /** Next maneuver line, when the text yields one. */
    val nextInstruction: String?,
    /** Remaining-distance/time fragment, e.g. "还剩 5.2 公里 · 约 12 分钟". */
    val remaining: String?,
    val updatedAtMs: Long = System.currentTimeMillis()
)

/**
 * Collects the Amap app's navigation output through its ongoing
 * notification (decision 2026-11-03: navigation lives in the Amap app,
 * the companion only reads its progress).
 *
 * What this can and cannot do, stated plainly:
 * - It reads notification TEXT. It cannot intercept Amap's spoken
 *   announcements (there is no API for that); the text is normally the
 *   same content as the announcement.
 * - Parsing is keyword best-effort. Amap owns the wording and it changes
 *   across versions, so anything unparsed is shown raw, never guessed.
 * - Only ongoing notifications are read, so Amap's other notifications
 *   (promotions and the like) never become fake navigation state.
 * - Requires the user to enable notification access in system settings;
 *   without it this service never runs and the status simply stays empty.
 */
class AmapNavListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != AmapNavLauncher.AMAP_PACKAGE) return
        if (!sbn.isOngoing) return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val combined = listOf(title, bigText, text)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        if (combined.isBlank()) return
        AmapNavStatus.update(parse(combined))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != AmapNavLauncher.AMAP_PACKAGE) return
        if (!sbn.isOngoing) return
        // The ongoing nav notification going away is the end-of-navigation signal.
        AmapNavStatus.clear()
    }

    private fun parse(combined: String): AmapNavInfo {
        val distance = Regex("还剩\\s*([\\d.]+\\s*(公里|米|千米))")
            .find(combined)?.value
        val time = Regex("(约|大约)\\s*(\\d+\\s*分钟)")
            .find(combined)?.value
        val remaining = listOfNotNull(distance, time)
            .joinToString(" · ")
            .ifEmpty { null }
        // The first non-empty line is usually the maneuver ("前方 500 米右转").
        val firstLine = combined.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
        return AmapNavInfo(
            rawText = combined,
            nextInstruction = firstLine,
            remaining = remaining
        )
    }

    companion object {
        fun isEnabled(context: Context): Boolean {
            return NotificationManagerCompat
                .getEnabledListenerPackages(context)
                .contains(context.packageName)
        }

        fun openSettings(context: Context) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

/** Process-wide holder: the listener writes, the ViewModel reads. */
object AmapNavStatus {
    private val _info = MutableStateFlow<AmapNavInfo?>(null)
    val info: StateFlow<AmapNavInfo?> = _info.asStateFlow()

    fun update(info: AmapNavInfo) {
        _info.value = info
    }

    fun clear() {
        _info.value = null
    }
}
