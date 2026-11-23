package com.example.rewindlive.monitor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object StreamChecker {
    suspend fun isStreamLive(streamUrl: String): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(streamUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "RewindLive/1.0")
            }
            val code = connection.responseCode
            if (code !in 200..299) return@withContext false

            if (streamUrl.contains(".m3u8", ignoreCase = true)) {
                val preview = connection.inputStream.bufferedReader().use { reader ->
                    buildString {
                        repeat(5) {
                            val line = reader.readLine() ?: return@repeat
                            appendLine(line)
                        }
                    }
                }
                return@withContext preview.contains("#EXTM3U")
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }
}
