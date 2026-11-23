package com.example.rewindlive.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.rewindlive.MainActivity
import com.example.rewindlive.data.StreamRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class StreamRecorderService : Service() {

    private val activeSessions = ConcurrentHashMap<String, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val sourceId = intent.getStringExtra(EXTRA_SOURCE_ID) ?: return START_NOT_STICKY
                val name = intent.getStringExtra(EXTRA_NAME) ?: "Live Stream"
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY

                promoteToForeground("Recording $name...")
                sendLiveAlertNotification(name)
                beginRecording(sourceId, name, url)
            }
            ACTION_STOP -> {
                val sourceId = intent.getStringExtra(EXTRA_SOURCE_ID)
                if (sourceId != null) {
                    activeSessions.remove(sourceId)?.let { FFmpegKit.cancel(it) }
                    updateActiveFlow()
                }
                if (activeSessions.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun beginRecording(sourceId: String, name: String, streamUrl: String) {
        if (activeSessions.containsKey(sourceId)) return

        val repo = StreamRepository(this)
        val safeName = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(repo.getRecordingsDir(), "${safeName}_$timestamp.mp4")

        val command = listOf(
            "-y",
            "-reconnect", "1",
            "-reconnect_streamed", "1",
            "-reconnect_delay_max", "5",
            "-i", "\"$streamUrl\"",
            "-c", "copy",
            "-bsf:a", "aac_adtstoasc",
            "-movflags", "+frag_keyframe+empty_moov",
            "\"${outFile.absolutePath}\""
        ).joinToString(" ")

        val session = FFmpegKit.executeAsync(command) { completed ->
            activeSessions.remove(sourceId)
            updateActiveFlow()

            if (!ReturnCode.isSuccess(completed.returnCode) && outFile.exists() && outFile.length() < 1024) {
                outFile.delete()
            }

            if (activeSessions.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                promoteToForeground("Recording ${activeSessions.size} live stream(s)")
            }
        }

        activeSessions[sourceId] = session.sessionId
        updateActiveFlow()
    }

    private fun updateActiveFlow() {
        _recordingIds.value = activeSessions.keys.toSet()
    }

    private fun promoteToForeground(statusText: String) {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_RECORDER)
            .setContentTitle("Rewind Live Recorder")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID_RECORDER, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID_RECORDER, notification)
        }
    }

    private fun sendLiveAlertNotification(channelName: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val alert = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setContentTitle("$channelName is LIVE!")
            .setContentText("Auto-recording started in the background.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setAutoCancel(true)
            .build()
        nm.notify(channelName.hashCode(), alert)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDER, "Active Recordings", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Live Stream Alerts", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    override fun onDestroy() {
        activeSessions.values.forEach { FFmpegKit.cancel(it) }
        activeSessions.clear()
        updateActiveFlow()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.example.rewindlive.START"
        private const val ACTION_STOP = "com.example.rewindlive.STOP"
        private const val EXTRA_SOURCE_ID = "source_id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_URL = "url"
        private const val CHANNEL_RECORDER = "rewind_recorder_channel"
        private const val CHANNEL_ALERTS = "rewind_alerts_channel"
        private const val NOTIF_ID_RECORDER = 1001

        private val _recordingIds = MutableStateFlow<Set<String>>(emptySet())
        val recordingIds: StateFlow<Set<String>> = _recordingIds

        fun isRecording(sourceId: String): Boolean = _recordingIds.value.contains(sourceId)

        fun startRecording(context: Context, sourceId: String, channelName: String, streamUrl: String) {
            val intent = Intent(context, StreamRecorderService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SOURCE_ID, sourceId)
                putExtra(EXTRA_NAME, channelName)
                putExtra(EXTRA_URL, streamUrl)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopRecording(context: Context, sourceId: String) {
            val intent = Intent(context, StreamRecorderService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_SOURCE_ID, sourceId)
            }
            context.startService(intent)
        }
    }
}
