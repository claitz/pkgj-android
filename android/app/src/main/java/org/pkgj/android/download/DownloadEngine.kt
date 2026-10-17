package org.pkgj.android.download

import org.pkgj.android.catalog.Category
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

enum class JobState { QUEUED, RUNNING, PAUSED, FAILED, CANCELED, DOWNLOADED }

data class DownloadJob(val id: Long, val category: Category, val contentId: String, val title: String,
    val url: String, val filename: String, val state: JobState, val bytes: Long, val total: Long?,
    val etag: String?, val error: String?, val speed: Long, val exported: Boolean, val zrif: String = "",
    val licenseExported: Boolean = false)

object JobTransitions {
    fun canMove(from: JobState, to: JobState) = when (from) {
        JobState.QUEUED -> to in setOf(JobState.RUNNING, JobState.CANCELED, JobState.PAUSED)
        JobState.RUNNING -> to in setOf(JobState.DOWNLOADED, JobState.FAILED, JobState.CANCELED, JobState.PAUSED, JobState.QUEUED)
        JobState.PAUSED -> to in setOf(JobState.QUEUED, JobState.CANCELED)
        JobState.FAILED -> to in setOf(JobState.QUEUED, JobState.CANCELED)
        JobState.CANCELED -> to == JobState.QUEUED
        JobState.DOWNLOADED -> false
    }
}

class DownloadInterrupted(val requested: JobState) : Exception()

/** HTTP transport with explicit resume validation. No TLS overrides are installed. */
class DownloadEngine {
    data class Result(val bytes: Long, val total: Long?, val etag: String?, val rangeSupported: Boolean)

    fun transfer(url: String, target: File, oldEtag: String?,
        control: () -> JobState?, progress: (Long, Long?, String?, Long) -> Unit): Result {
        require(url.startsWith("https://") || url.startsWith("http://")) { "Unsupported URL scheme" }
        val directory = target.parentFile ?: error("No download directory")
        directory.mkdirs()
        val existing = if (target.exists()) target.length() else 0L
        var current = URL(url)
        var connection: HttpURLConnection? = null
        for (redirect in 0..5) {
            val c = current.openConnection() as? HttpURLConnection ?: error("HTTP URL required")
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000; c.readTimeout = 30_000
            c.setRequestProperty("Accept-Encoding", "identity")
            if (existing > 0 && oldEtag != null) {
                c.setRequestProperty("Range", "bytes=$existing-")
                c.setRequestProperty("If-Range", oldEtag)
            }
            val code = c.responseCode
            if (code in 300..399) {
                val location = c.getHeaderField("Location") ?: throw IllegalStateException("Redirect without Location")
                val next = URL(current, location)
                if (current.protocol == "https" && next.protocol != "https") throw IllegalStateException("Insecure redirect")
                if (next.protocol !in listOf("http", "https")) throw IllegalStateException("Unsupported redirect")
                c.disconnect(); current = next; continue
            }
            connection = c; break
        }
        val c = connection ?: throw IllegalStateException("Too many redirects")
        try {
            val code = c.responseCode
            if (code == 416 && existing > 0 && oldEtag != null) {
                c.disconnect()
                RandomAccessFile(target, "rw").use { it.setLength(0) }
                return transfer(url, target, null, control, progress)
            }
            val resume = existing > 0 && oldEtag != null && code == 206
            val contentRange = c.getHeaderField("Content-Range")
            if (resume && !validContentRange(contentRange, existing)) throw IllegalStateException("Invalid resume response")
            if (resume && c.getHeaderField("ETag") != oldEtag) throw IllegalStateException("Resume validator changed")
            if (code !in listOf(200, 206)) throw IllegalStateException("HTTP $code ${c.responseMessage}")
            if (code == 206 && !resume) throw IllegalStateException("Unexpected partial response")
            val start = if (resume) existing else 0L
            val length = c.contentLengthLong.takeIf { it >= 0 }
            if (resume && length != null) {
                val match = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(contentRange ?: "")!!
                if (length != match.groupValues[2].toLong() - existing + 1) throw IllegalStateException("Resume length mismatch")
            }
            val total = length?.plus(start)
            if (total != null && directory.usableSpace < total - start + 16L * 1024 * 1024) throw IllegalStateException("Not enough free space")
            val etag = c.getHeaderField("ETag")?.takeIf { it.isNotBlank() }
            val rangeSupported = c.getHeaderField("Accept-Ranges")?.equals("bytes", true) == true && etag != null
            val resumeEtag = if (rangeSupported) etag else null
            RandomAccessFile(target, "rw").use { file ->
                if (resume) file.seek(existing) else file.setLength(0)
                var count = start
                progress(count, total, resumeEtag, 0)
                var tick = System.nanoTime(); var last = count
                c.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        control()?.let { throw DownloadInterrupted(it) }
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n == 0) {
                            continue
                        }
                        if (directory.usableSpace < n + 1024L * 1024) throw IllegalStateException("Not enough free space")
                        file.write(buffer, 0, n)
                        count += n
                        val now = System.nanoTime()
                        if (now - tick > 250_000_000) {
                            val speed = ((count - last) * 1_000_000_000L / (now - tick)).coerceAtLeast(0)
                            progress(count, total, resumeEtag, speed); tick = now; last = count
                        }
                    }
                }
                file.fd.sync()
                if (total != null && count != total) throw IllegalStateException("Truncated transfer: $count of $total bytes")
                progress(count, total, resumeEtag, 0)
                return Result(count, total, resumeEtag, rangeSupported)
            }
        } finally { c.disconnect() }
    }

    companion object {
        fun validContentRange(value: String?, offset: Long): Boolean {
            val m = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(value ?: "") ?: return false
            val start = m.groupValues[1].toLongOrNull() ?: return false
            val end = m.groupValues[2].toLongOrNull() ?: return false
            val total = m.groupValues[3].toLongOrNull()
            return start == offset && end >= start && (total == null || end < total)
        }
        fun safeFilename(titleId: String, id: Long): String = "${titleId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(20)}-$id.pkg"
    }
}
