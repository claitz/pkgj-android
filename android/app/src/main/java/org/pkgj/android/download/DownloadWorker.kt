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
                if (!store.transition(job.id, JobState.RUNNING)) continue
                store.update(job.id, error = "", speed = 0)
                repository.refreshJobs()
                try {
                    var current = store.job(job.id) ?: continue
                    if (!current.transferComplete) {
                        var notificationAt = 0L
                        val control = {
                            if (isStopped) JobState.QUEUED else store.job(job.id)?.state?.takeIf { it != JobState.RUNNING }
                        }
                        val progress: (Long, Long?, String?, Long) -> Unit = { bytes, total, etag, speed ->
                            if (store.job(job.id)?.state == JobState.RUNNING) {
                                store.update(job.id, bytes = bytes, total = total, etag = etag, speed = speed)
                                repository.refreshJobs()
                                val now = System.currentTimeMillis()
                                if (now - notificationAt >= 1000) {
                                    val info = notification(job.title, bytes, total)
                                    applicationContext.getSystemService(NotificationManager::class.java).notify(42, info.notification)
                                    notificationAt = now
                                }
                            }
                        }
                        val result = if (current.folderUri != null) {
                            val document = repository.prepareFolderDownload(current)
                            repository.openFolderDownload(document).use { destination ->
                                DownloadEngine().transfer(current.url, destination, current.etag, control, progress)
                            }
                        } else DownloadEngine().transfer(current.url, File(repository.downloadsDir, current.filename), current.etag, control, progress)
                        control()?.let { throw DownloadInterrupted(it) }
                        store.update(job.id, bytes = result.bytes, total = result.total, etag = result.etag, transferComplete = true)
                    }
                    current = store.job(job.id) ?: continue
                    if (current.state != JobState.RUNNING) throw DownloadInterrupted(current.state)
                    val licenseError = if (current.folderUri != null) repository.finishFolderDownload(current)
                        else if (current.category.vita3kRelevant) try {
                            if (repository.ensureLicense(current) == null) "No RIF" else null
                        } catch (_: IllegalArgumentException) { "Invalid RIF" } else null
                    if (store.job(job.id)?.state != JobState.RUNNING) throw DownloadInterrupted(store.job(job.id)?.state ?: JobState.CANCELED)
                    if (!store.completeJob(job.id, licenseError ?: "", current.folderUri != null))
                        throw DownloadInterrupted(store.job(job.id)?.state ?: JobState.CANCELED)
                    repository.refreshJobs()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DownloadInterrupted) {
                    val latest = store.job(job.id)
                    if (e.requested == JobState.PAUSED && latest != null && latest.etag == null && !latest.transferComplete) {
                        repository.discardDownloadFiles(latest)
                        store.update(job.id, bytes = 0, speed = 0)
                    }
                    if (e.requested == JobState.QUEUED && store.job(job.id)?.state == JobState.RUNNING)
                        store.update(job.id, state = JobState.QUEUED, speed = 0)
                    repository.refreshJobs()
                    if (isStopped) return@withContext Result.retry()
                } catch (e: Exception) {
                    val latest = store.job(job.id)
                    if (latest != null && latest.state !in listOf(JobState.CANCELED, JobState.PAUSED)) {
                        val invalidResume = e.message?.contains("resume", true) == true
                        if (!latest.transferComplete && (latest.etag == null || invalidResume)) {
                            runCatching { repository.discardDownloadFiles(latest) }
                            store.update(job.id, bytes = 0, speed = 0)
                        }
                        store.failJob(job.id, e.message ?: "Download failed")
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
