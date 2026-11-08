package com.fadysaeed.scriber.fetch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.fadysaeed.scriber.core.ModelCatalog
import com.fadysaeed.scriber.core.PackRole
import com.fadysaeed.scriber.vault.Vault
import kotlinx.coroutines.CancellationException

/**
 * Downloads the chosen packs one file at a time as a visible foreground job, so it keeps going
 * with the screen off and survives the app being closed. Interrupted files resume where they
 * stopped. When every needed pack is verified, the downloader locks itself.
 */
class FetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val vault = Vault.get(applicationContext)
        if (FetchLock.isLocked(vault)) return Result.failure(workDataOf(KEY_ERROR to "Downloads are locked"))

        val catalog = ModelCatalog.bundled()
        val ids = inputData.getStringArray(KEY_PACKS)?.toList().orEmpty()
        val packs = ids.mapNotNull { catalog.pack(it) }
        if (packs.isEmpty()) return Result.success()

        val store = ModelStore(applicationContext)
        val downloader = ModelDownloader(store)
        val total = packs.sumOf { it.totalBytes }
        setForeground(foregroundInfo("Preparing", 0, 100))

        return try {
            var before = 0L
            for (pack in packs) {
                if (store.state(pack) is PackState.Verified) { before += pack.totalBytes; continue }
                for (f in pack.files) {
                    var lastTick = -1L
                    var lastPercent = -1
                    downloader.fetch(pack, f) { bytes, hashing ->
                        // While checking, progress runs over the file a second time.
                        val overall = before + bytes
                        val tick = overall * 1000 / total
                        if (tick != lastTick) {
                            lastTick = tick
                            setProgressAsync(workDataOf(
                                KEY_PACK to pack.id, KEY_FILE to f.path, KEY_DONE to overall, KEY_TOTAL to total, KEY_HASHING to hashing,
                            ))
                        }
                        val percent = (tick / 10).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            notify(if (hashing) "Checking ${pack.title}" else "Downloading ${pack.title}", percent)
                        }
                    }
                    before += f.size
                }
                store.markVerified(pack)
            }
            if (allNeededVerified(catalog, store)) FetchLock.lock(vault)
            Result.success(workDataOf(KEY_DONE to total, KEY_TOTAL to total))
        } catch (c: CancellationException) {
            throw c
        } catch (e: IntegrityException) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Checksum failed")))
        } catch (e: BlockedHostException) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Blocked host")))
        } catch (e: Exception) {
            if (runAttemptCount < 4) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR to "${e.javaClass.simpleName}: ${e.message}"))
        }
    }

    private fun allNeededVerified(catalog: ModelCatalog, store: ModelStore): Boolean =
        catalog.packs.filter { it.required }.all { store.state(it) is PackState.Verified } &&
            catalog.byRole(PackRole.LLM).any { store.state(it) is PackState.Verified }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Preparing", 0, 100)

    private fun notify(text: String, percent: Int) {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        runCatching { nm.notify(NOTIFICATION_ID, notification(text, percent, 100)) }
    }

    private fun foregroundInfo(text: String, progress: Int, max: Int) =
        ForegroundInfo(NOTIFICATION_ID, notification(text, progress, max), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun notification(text: String, progress: Int, max: Int): android.app.Notification {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Scriber models")
            .setContentText("$text, $progress%")
            .setProgress(max, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val KEY_PACKS = "packs"
        const val KEY_PACK = "pack"
        const val KEY_FILE = "file"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_HASHING = "hashing"
        const val KEY_ERROR = "error"
        private const val CHANNEL = "model_downloads"
        private const val NOTIFICATION_ID = 41
    }
}
