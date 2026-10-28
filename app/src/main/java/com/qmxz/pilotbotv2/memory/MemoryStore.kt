package com.qmxz.pilotbotv2.memory

import android.content.Context
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

enum class MemoryKind { FACT, PREFERENCE, ROUTINE }

/**
 * One thing the companion learned about the driver.
 * [text] is a plain sentence, e.g. "每周五走百色到南宁这条路回家".
 */
data class MemoryItem(
    val id: String,
    val kind: MemoryKind,
    val text: String,
    val createdAt: Long
)

/**
 * Thread-safe JSON-file memory store in the app's private filesDir.
 *
 * v1 lesson: memory was a form the user filled in by hand, so nothing was
 * ever remembered from conversation. v2 writes here automatically from
 * [MemoryExtractor] output. Storage is a single JSON array file — no
 * database dependency, no server, and the file never leaves the device.
 *
 * All methods are synchronized and all I/O failures degrade to "no memories"
 * rather than crashing: losing a memory is acceptable, crashing is not.
 */
class MemoryStore(context: Context) {

    private val file: File = File(context.applicationContext.filesDir, "memory.json")
    private val lock = Any()

    fun all(): List<MemoryItem> {
        synchronized(lock) {
            return try {
                if (!file.exists()) return emptyList()
                val array = JSONArray(file.readText())
                val out = ArrayList<MemoryItem>()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val kindName = obj.optString("kind", "FACT")
                    val kind = try {
                        MemoryKind.valueOf(kindName)
                    } catch (_: IllegalArgumentException) {
                        MemoryKind.FACT
                    }
                    out.add(
                        MemoryItem(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            kind = kind,
                            text = obj.optString("text", ""),
                            createdAt = obj.optLong("createdAt", 0L)
                        )
                    )
                }
                out.filter { it.text.isNotBlank() }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /**
     * Adds [item] unless an equivalent text is already stored.
     * Returns true if the store actually grew (the UI uses this to announce
     * "已记住…" only for genuinely new memories).
     */
    fun add(item: MemoryItem): Boolean {
        synchronized(lock) {
            val current = all()
            val normalized = normalize(item.text)
            if (current.any { normalize(it.text) == normalized }) return false
            val updated = current + item
            return save(updated)
        }
    }

    fun clear(): Boolean {
        synchronized(lock) {
            return try {
                if (file.exists()) file.delete()
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun save(items: List<MemoryItem>): Boolean {
        return try {
            val array = JSONArray()
            items.forEach { item ->
                val obj = JSONObject()
                obj.put("id", item.id)
                obj.put("kind", item.kind.name)
                obj.put("text", item.text)
                obj.put("createdAt", item.createdAt)
                array.put(obj)
            }
            file.writeText(array.toString())
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun normalize(text: String): String {
        return text.trim().lowercase().filterNot { it.isWhitespace() }
    }

    companion object {
        fun newItem(kind: MemoryKind, text: String): MemoryItem {
            return MemoryItem(
                id = UUID.randomUUID().toString(),
                kind = kind,
                text = text.trim(),
                createdAt = System.currentTimeMillis()
            )
        }
    }
}
