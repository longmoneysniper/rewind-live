package com.example.rewindlive.monitor

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.rewindlive.data.StreamRepository
import com.example.rewindlive.recorder.StreamRecorderService
import java.util.concurrent.TimeUnit

class LiveMonitorWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = StreamRepository(appContext)
        val autoSources = repo.getSources().filter { it.autoRecord }

        for (source in autoSources) {
            if (!StreamRecorderService.isRecording(source.id) &&
                StreamChecker.isStreamLive(source.streamUrl)
            ) {
                StreamRecorderService.startRecording(
                    context = appContext,
                    sourceId = source.id,
                    channelName = source.name,
                    streamUrl = source.streamUrl
                )
            }
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "rewind_live_monitor"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<LiveMonitorWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
