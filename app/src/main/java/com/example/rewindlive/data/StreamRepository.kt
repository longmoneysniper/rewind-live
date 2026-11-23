package com.example.rewindlive.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class StreamSource(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val streamUrl: String,
    val autoRecord: Boolean = true
)

data class RecordingItem(
    val file: File,
    val title: String,
    val sizeBytes: Long,
    val lastModified: Long
)

class StreamRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("rewind_live_prefs", Context.MODE_PRIVATE)
    private val keySources = "tracked_sources"

    fun getSources(): List<StreamSource> {
        val raw = prefs.getString(keySources, null) ?: return emptyList()
        val array = JSONArray(raw)
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                add(
                    StreamSource(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        streamUrl = obj.getString("streamUrl"),
                        autoRecord = obj.optBoolean("autoRecord", true)
                    )
                )
            }
        }
    }

    fun saveSources(sources: List<StreamSource>) {
        val array = JSONArray()
        sources.forEach { src ->
            array.put(
                JSONObject().apply {
                    put("id", src.id)
                    put("name", src.name)
                    put("streamUrl", src.streamUrl)
                    put("autoRecord", src.autoRecord)
                }
            )
        }
        prefs.edit().putString(keySources, array.toString()).apply()
    }

    fun addSource(name: String, url: String) {
        val updated = getSources() + StreamSource(name = name.trim(), streamUrl = url.trim())
        saveSources(updated)
    }

    fun toggleAutoRecord(id: String) {
        val updated = getSources().map {
            if (it.id == id) it.copy(autoRecord = !it.autoRecord) else it
        }
        saveSources(updated)
    }

    fun deleteSource(id: String) {
        saveSources(getSources().filterNot { it.id == id })
    }

    fun getRecordingsDir(): File {
        return File(context.filesDir, "recordings").apply { if (!exists()) mkdirs() }
    }

    fun getRecordings(): List<RecordingItem> {
        val files = getRecordingsDir().listFiles { f -> f.isFile && f.extension == "mp4" }
            ?: emptyArray()
        return files.sortedByDescending { it.lastModified() }.map { file ->
            RecordingItem(
                file = file,
                title = file.nameWithoutExtension,
                sizeBytes = file.length(),
                lastModified = file.lastModified()
            )
        }
    }
}
