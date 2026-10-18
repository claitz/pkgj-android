package org.pkgj.android.download

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants

/** Writes into the granted document itself; no app-private package copy is created. */
class DocumentDestination(resolver: ContentResolver, uri: Uri) : DownloadDestination {
    private val descriptor: ParcelFileDescriptor = resolver.openFileDescriptor(uri, "rw")
        ?: error("Cannot open download file. Choose another folder in Settings.")
    init {
        try { seek(0) } catch (e: Exception) {
            descriptor.close()
            throw java.io.IOException("This folder does not support downloads. Choose a local folder in Settings.", e)
        }
    }
    private val fd get() = descriptor.fileDescriptor
    override val size get() = Os.fstat(fd).st_size
    override val availableBytes: Long? get() = runCatching {
        val stat = Os.fstatvfs(fd)
        (stat.f_bavail * stat.f_frsize).takeIf { stat.f_blocks > 0 }
    }.getOrNull()
    override fun seek(offset: Long) { Os.lseek(fd, offset, OsConstants.SEEK_SET) }
    override fun truncate() { Os.ftruncate(fd, 0); seek(0) }
    override fun write(bytes: ByteArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val written = Os.write(fd, bytes, offset, count - offset)
            check(written > 0) { "Could not write download" }
            offset += written
        }
    }
    override fun sync() = Os.fsync(fd)
    override fun close() = descriptor.close()
}
