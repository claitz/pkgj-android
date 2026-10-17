package org.pkgj.android.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.pkgj.android.data.Repository
import java.io.File

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repository = Repository.get(applicationContext)
        val store = repository.store
        store.resetInterrupted()
        repository.refreshJobs()
        var active: DownloadJob? = null
        try {
            while (true) {
                val job = store.jobs().firstOrNull { it.state == JobState.QUEUED } ?: break
                active = job
                setForeground(notification(job.title, 0, null))
                store.update(job.id, state = JobState.RUNNING, error = "", speed = 0)
                repository.refreshJobs()
                val target = File(repository.downloadsDir, job.filename)
                try {
                    var notificationAt = 0L
                    val result = DownloadEngine().transfer(job.url, target, job.etag,
                        control = {
                            if (isStopped) JobState.QUEUED else store.job(job.id)?.state?.takeIf { it != JobState.RUNNING }
                        }, progress = { bytes, total, etag, speed ->
                            store.update(job.id, bytes = bytes, total = total, etag = etag, speed = speed)
                            repository.refreshJobs()
                            val now = System.currentTimeMillis()
                            if (now - notificationAt >= 1000) {
                                val info = notification(job.title, bytes, total)
                                applicationContext.getSystemService(NotificationManager::class.java).notify(42, info.notification)
                                notificationAt = now
                            }
                        })
                    val licenseError = if (job.category.vita3kRelevant) try {
                        if (repository.ensureLicense(job) == null) "No RIF"
                        else null
                    } catch (_: Exception) { "Invalid RIF" } else null
                    store.update(job.id, state = JobState.DOWNLOADED, bytes = result.bytes,
                        total = result.total, etag = result.etag, speed = 0, error = licenseError ?: "")
                    repository.refreshJobs()
                    if (repository.exportFolder.value != null) try { repository.exportToFolder(store.job(job.id)!!) }
                    catch (e: Exception) {
                        store.update(job.id, error = "Copy failed: ${e.message}"); repository.refreshJobs()
                    }
                } catch (e: DownloadInterrupted) {
                    if (e.requested == JobState.CANCELED) { target.delete(); store.update(job.id, bytes = 0, speed = 0) }
                    if (e.requested == JobState.PAUSED && store.job(job.id)?.etag == null) {
                        target.delete(); store.update(job.id, bytes = 0, speed = 0)
                    }
                    if (e.requested == JobState.QUEUED) store.update(job.id, state = JobState.QUEUED, speed = 0)
                    repository.refreshJobs()
                    if (isStopped) return@withContext Result.retry()
                } catch (e: Exception) {
                    val latest = store.job(job.id)
                    val currentState = latest?.state
                    if (currentState == JobState.CANCELED || currentState == JobState.PAUSED) {
                        if (currentState == JobState.CANCELED || latest?.etag == null) {
                            target.delete(); store.update(job.id, bytes = 0, speed = 0)
                        }
                    } else {
                        val invalidResume = e.message?.contains("resume", true) == true
                        if (latest?.etag == null || invalidResume) {
                            target.delete(); store.update(job.id, bytes = 0, speed = 0)
                        }
                        store.update(job.id, state = JobState.FAILED, error = e.message ?: e.javaClass.simpleName, speed = 0)
                    }
                    repository.refreshJobs()
                }
                active = null
            }
            Result.success()
        } catch (e: CancellationException) {
            active?.let { if (store.job(it.id)?.state == JobState.RUNNING) store.update(it.id, state = JobState.QUEUED, speed = 0) }
            repository.refreshJobs()
            throw e
        }
    }

    private fun notification(title: String, bytes: Long, total: Long?): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("transfers", "Downloads", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, "transfers")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("PKGj download")
            .setContentText(title)
            .setOngoing(true)
            .setProgress(100, if (total != null && total > 0) (bytes * 100 / total).toInt().coerceIn(0, 100) else 0, total == null)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(42, n)
    }
}
