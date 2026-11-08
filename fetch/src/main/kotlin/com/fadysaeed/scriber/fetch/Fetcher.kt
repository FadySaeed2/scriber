package com.fadysaeed.scriber.fetch

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.fadysaeed.scriber.core.ModelPack
import com.fadysaeed.scriber.vault.Vault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/** The app's single entry point to downloads. */
object Fetcher {
    private const val WORK = "scriber-model-fetch"

    sealed interface Status {
        data object Idle : Status
        data object Waiting : Status
        data class Running(val pack: String?, val file: String?, val done: Long, val total: Long, val hashing: Boolean) : Status
        data object Finished : Status
        data class Failed(val reason: String) : Status
    }

    /** Starts (or keeps) the download job. Returns false when downloads are locked. */
    fun start(context: Context, packs: List<ModelPack>, wifiOnly: Boolean): Boolean {
        if (FetchLock.isLocked(Vault.get(context))) return false
        val request = OneTimeWorkRequestBuilder<FetchWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(FetchWorker.KEY_PACKS to packs.map { it.id }.toTypedArray()))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request)
        return true
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    fun status(context: Context): Flow<Status> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK).map { infos ->
            val info = infos.lastOrNull() ?: return@map Status.Idle
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> Status.Waiting
                WorkInfo.State.RUNNING -> {
                    val p = info.progress
                    Status.Running(
                        p.getString(FetchWorker.KEY_PACK), p.getString(FetchWorker.KEY_FILE),
                        p.getLong(FetchWorker.KEY_DONE, 0), p.getLong(FetchWorker.KEY_TOTAL, 0),
                        p.getBoolean(FetchWorker.KEY_HASHING, false),
                    )
                }
                WorkInfo.State.SUCCEEDED -> Status.Finished
                WorkInfo.State.FAILED -> Status.Failed(info.outputData.getString(FetchWorker.KEY_ERROR) ?: "Download failed")
                WorkInfo.State.CANCELLED -> Status.Idle
            }
        }
}
