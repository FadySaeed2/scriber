package com.fadysaeed.scriber.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fadysaeed.scriber.audio.Importer
import com.fadysaeed.scriber.core.Ids
import com.fadysaeed.scriber.core.RunStatus
import com.fadysaeed.scriber.core.SessionSource
import com.fadysaeed.scriber.core.SessionStatus
import com.fadysaeed.scriber.data.SessionStore
import com.fadysaeed.scriber.vault.Records
import com.fadysaeed.scriber.vault.Vault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

/** What a background job is doing now, for the screens. Same process, so a plain flow is enough. */
data class JobProgress(val sessionId: String, val label: String, val fraction: Float)

@Serializable
data class Job(val id: String, val kind: String, val sessionId: String, val engine: String, val attempts: Int = 0)

@Serializable
private data class JobQueue(val jobs: List<Job> = emptyList())

/**
 * Heavy work (decoding imports, transcribing) runs one job at a time, so two speech models are never
 * loaded together. The queue is saved in the vault and a job leaves it only when it has finished,
 * so anything cut short by a crash, a force stop or a flat battery runs again on the next start.
 * It runs in Scriber's own foreground service, which keeps going with the app closed.
 */
object Jobs {
    const val IMPORT = "import"
    const val TRANSCRIBE = "transcribe"
    private const val MAX_ATTEMPTS = 3
    private val lock = Any()
    private val _progress = MutableStateFlow<Map<String, JobProgress>>(emptyMap())
    val progress: StateFlow<Map<String, JobProgress>> = _progress

    fun import(context: Context, sessionId: String, thenEngine: String) = enqueue(context, IMPORT, sessionId, thenEngine)
    fun transcribe(context: Context, sessionId: String, engine: String) = enqueue(context, TRANSCRIBE, sessionId, engine)

    private fun records(context: Context) = Records(Vault.get(context))
    private fun queue(context: Context): List<Job> = runCatching { records(context).get("jobs", JobQueue.serializer())?.jobs }.getOrNull().orEmpty()
    private fun save(context: Context, jobs: List<Job>) = records(context).put("jobs", JobQueue.serializer(), JobQueue(jobs))

    fun pending(context: Context): List<Job> = synchronized(lock) { queue(context) }

    private fun waiting(job: Job) = JobProgress(job.sessionId, if (job.kind == IMPORT) "Waiting to decode" else "Waiting to transcribe", 0f)

    private fun enqueue(context: Context, kind: String, sessionId: String, engine: String) {
        synchronized(lock) {
            val jobs = queue(context)
            if (jobs.none { it.kind == kind && it.sessionId == sessionId && it.engine == engine }) {
                val job = Job(Ids.new(), kind, sessionId, engine)
                save(context, jobs + job)
                if (_progress.value[sessionId] == null) _progress.update { it + (sessionId to waiting(job)) }
            }
        }
        ProcessingService.start(context)
    }

    /**
     * Called whenever the app comes to the front: tidies what a crash left behind and restarts the
     * queue. Runs still marked as running with nothing working on them were cut short; imports
     * whose decoding never finished go back in the queue.
     */
    fun resume(context: Context) {
        val store = SessionStore.get(Vault.get(context))
        synchronized(lock) {
            val jobs = queue(context).toMutableList()
            if (!ProcessingService.busy) {
                // A job that had started but is not running now stopped the app (or the phone died).
                // Never start it again by itself: that turns one crash into a crash on every open.
                val interrupted = jobs.filter { it.attempts >= 1 }
                jobs.removeAll(interrupted)
                for (j in interrupted) if (j.kind == IMPORT) store.update(j.sessionId) {
                    it.copy(status = SessionStatus.FAILED, problem = "Scriber stopped while decoding this file. The file is kept; Transcribe again tries once more. Details are in Settings.")
                }
                for (s in store.sessions()) {
                    for (r in store.runs(s.id)) if (r.status == RunStatus.RUNNING) {
                        runCatching { store.saveRun(r.copy(status = RunStatus.FAILED, finishedAtMillis = System.currentTimeMillis(), problem = "Scriber stopped while making this transcript. Transcribe again to retry; details are in Settings.")) }
                    }
                }
            }
            for (s in store.sessions()) {
                if (s.source == SessionSource.IMPORTED && s.status == SessionStatus.FINALIZING && jobs.none { it.kind == IMPORT && it.sessionId == s.id }) {
                    jobs += Job(Ids.new(), IMPORT, s.id, store.settings().engineFor(s.mode))
                }
            }
            save(context, jobs)
            _progress.update { current -> jobs.associate { it.sessionId to (current[it.sessionId] ?: waiting(it)) } }
        }
        runCatching { androidx.work.WorkManager.getInstance(context).cancelUniqueWork("scriber-jobs") }
        if (pending(context).isNotEmpty()) ProcessingService.start(context)
    }

    /** The next job, counted as one more attempt; jobs that keep failing are given up on. */
    internal fun next(context: Context): Job? = synchronized(lock) {
        val store = SessionStore.get(Vault.get(context))
        var jobs = queue(context)
        while (jobs.isNotEmpty() && jobs.first().attempts >= MAX_ATTEMPTS) {
            val dead = jobs.first()
            if (dead.kind == IMPORT) store.update(dead.sessionId) { it.copy(status = SessionStatus.FAILED, problem = "Decoding stopped the app $MAX_ATTEMPTS times; the file is kept") }
            jobs = jobs.drop(1)
            _progress.update { it - dead.sessionId }
        }
        val job = jobs.firstOrNull()?.let { it.copy(attempts = it.attempts + 1) }
        save(context, if (job == null) jobs else listOf(job) + jobs.drop(1))
        job
    }

    internal fun finished(context: Context, job: Job) = synchronized(lock) {
        val left = queue(context).filterNot { it.id == job.id }
        save(context, left)
        _progress.update { m -> (m - job.sessionId).let { r -> left.firstOrNull { it.sessionId == job.sessionId }?.let { r + (it.sessionId to waiting(it)) } ?: r } }
    }

    internal fun report(p: JobProgress) = _progress.update { it + (p.sessionId to p) }

    /** Runs one job. Failures are recorded on the session or the run, never thrown. */
    internal fun run(context: Context, job: Job, notify: (String) -> Unit) {
        val store = SessionStore.get(Vault.get(context))
        val id = job.sessionId
        if (store.session(id) == null) return
        when (job.kind) {
            IMPORT -> try {
                report(JobProgress(id, "Decoding", 0f)); notify("Decoding")
                var last = -1
                Importer.decode(context, id) { f ->
                    val pct = (f * 100).toInt()
                    if (pct != last) { last = pct; report(JobProgress(id, "Decoding", f)); notify("Decoding, $pct%") }
                }
                enqueueBehind(context, TRANSCRIBE, id, job.engine)
            } catch (t: Throwable) {
                store.update(id) { it.copy(status = SessionStatus.FAILED, problem = "Could not decode: ${t.message ?: t.javaClass.simpleName}") }
            }
            TRANSCRIBE -> try {
                report(JobProgress(id, "Transcribing", 0f)); notify("Transcribing")
                var last = -1
                Transcriber(context).run(id, job.engine) { f ->
                    val pct = (f * 100).toInt()
                    if (pct != last) { last = pct; report(JobProgress(id, "Transcribing", f)); notify("Transcribing, $pct%") }
                }
            } catch (_: Throwable) {
                // The failed run is saved with its reason and shows on the session.
            }
        }
    }

    /** Adds a follow-up job from inside the running service (no need to start it again). */
    private fun enqueueBehind(context: Context, kind: String, sessionId: String, engine: String) = synchronized(lock) {
        val jobs = queue(context)
        if (jobs.none { it.kind == kind && it.sessionId == sessionId && it.engine == engine }) save(context, jobs + Job(Ids.new(), kind, sessionId, engine))
    }
}

/** Scriber's own foreground service for the job queue. Stops itself when the queue is empty. */
class ProcessingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        try {
            startForeground(NOTIFICATION_ID, notification("Working"), type)
        } catch (t: Throwable) {
            // Android can refuse (for example when it restarts this service in the background after
            // a crash). The queue is saved, so stop quietly; it resumes when Scriber is next opened.
            stopSelf()
            return START_NOT_STICKY
        }
        synchronized(ProcessingService) {
            if (worker == null) {
                busy = true
                val wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "scriber:processing").apply { acquire(6 * 60 * 60 * 1000L) }
                worker = Thread({ loop(wakeLock) }, "scriber-jobs").also { it.start() }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+ limits how long this kind of service may run each day; stop cleanly when told. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun loop(wakeLock: PowerManager.WakeLock) {
        try {
            while (true) {
                val job = synchronized(ProcessingService) {
                    Jobs.next(this) ?: run { worker = null; busy = false; null }
                } ?: break
                Jobs.run(this, job) { text -> notify(text) }
                Jobs.finished(this, job)
            }
        } catch (t: Throwable) {
            synchronized(ProcessingService) { worker = null; busy = false }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            // A new job may have started a fresh worker in the meantime; only stop if none did.
            synchronized(ProcessingService) {
                if (worker == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
        }
    }

    private fun notify(text: String) = runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text)) }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Transcription", NotificationManager.IMPORTANCE_LOW))
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            android.app.PendingIntent.getActivity(this, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Scriber")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "transcription"
        private const val NOTIFICATION_ID = 61
        @Volatile private var worker: Thread? = null
        /** True while a job thread is alive in this process. */
        @Volatile var busy = false
            private set

        /** Starts the service, or does nothing if it is already working (it picks up new jobs itself). */
        fun start(context: Context) {
            if (busy) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ProcessingService::class.java)) }
            // If Android refuses to start it from the background, the job waits in the saved queue
            // and starts the next time Scriber is opened.
        }
    }
}
