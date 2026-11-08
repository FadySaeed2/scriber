package com.fadysaeed.scriber.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.fadysaeed.scriber.audio.Importer
import com.fadysaeed.scriber.core.SessionStatus
import com.fadysaeed.scriber.data.SessionStore
import com.fadysaeed.scriber.vault.Vault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** What a background job is doing now, for the screens. Same process, so a plain flow is enough. */
data class JobProgress(val sessionId: String, val label: String, val fraction: Float)

/**
 * One queue for heavy work (decoding imports, transcribing), run one job at a time so two speech
 * models are never loaded together, as a visible foreground job that continues if the app closes.
 */
object Jobs {
    private const val QUEUE = "scriber-jobs"
    private val _progress = MutableStateFlow<Map<String, JobProgress>>(emptyMap())
    val progress: StateFlow<Map<String, JobProgress>> = _progress

    fun import(context: Context, sessionId: String, thenEngine: String) = enqueue(context, "import", sessionId, thenEngine)
    fun transcribe(context: Context, sessionId: String, engine: String) = enqueue(context, "transcribe", sessionId, engine)

    private fun enqueue(context: Context, kind: String, sessionId: String, engine: String) {
        val request = OneTimeWorkRequestBuilder<JobWorker>()
            .setInputData(workDataOf("kind" to kind, "session" to sessionId, "engine" to engine))
            .addTag("session:$sessionId")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        _progress.update { it + (sessionId to JobProgress(sessionId, if (kind == "import") "Waiting to decode" else "Waiting to transcribe", 0f)) }
    }

    internal fun report(p: JobProgress?, sessionId: String) =
        _progress.update { if (p == null) it - sessionId else it + (sessionId to p) }
}

class JobWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val kind = inputData.getString("kind") ?: return Result.failure()
        val id = inputData.getString("session") ?: return Result.failure()
        val engine = inputData.getString("engine") ?: "parakeet"
        val store = SessionStore.get(Vault.get(applicationContext))
        runCatching { setForeground(info("Working")) }
        return try {
            when (kind) {
                "import" -> {
                    Jobs.report(JobProgress(id, "Decoding", 0f), id)
                    Importer.decode(applicationContext, id) { f -> Jobs.report(JobProgress(id, "Decoding", f), id) }
                    Jobs.report(null, id)
                    Jobs.transcribe(applicationContext, id, engine)
                }
                "transcribe" -> {
                    Jobs.report(JobProgress(id, "Transcribing", 0f), id)
                    var last = -1
                    Transcriber(applicationContext).run(id, engine) { f ->
                        val pct = (f * 100).toInt()
                        if (pct != last) { last = pct; Jobs.report(JobProgress(id, "Transcribing", f), id); notify("Transcribing, $pct%") }
                    }
                    Jobs.report(null, id)
                }
            }
            Result.success()
        } catch (t: Throwable) {
            Jobs.report(null, id)
            if (kind == "import") store.update(id) { it.copy(status = SessionStatus.FAILED, problem = "Could not decode: ${t.message ?: t.javaClass.simpleName}") }
            Result.failure(workDataOf("error" to (t.message ?: t.javaClass.simpleName)))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = info("Working")

    private fun notify(text: String) = runCatching {
        applicationContext.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun info(text: String): ForegroundInfo {
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        return ForegroundInfo(NOTIFICATION_ID, notification(text), type)
    }

    private fun notification(text: String): android.app.Notification {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Transcription", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Scriber")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private companion object {
        const val CHANNEL = "transcription"
        const val NOTIFICATION_ID = 61
    }
}
