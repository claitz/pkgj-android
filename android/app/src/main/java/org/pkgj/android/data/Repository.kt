package org.pkgj.android.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pkgj.android.catalog.*
import org.pkgj.android.compatibility.*
import org.pkgj.android.download.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class Repository private constructor(private val context: Context) {
    val store = Store(context)
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _jobs = MutableStateFlow(store.jobs())
    val jobs: StateFlow<List<DownloadJob>> = _jobs
    private val _catalogVersion = MutableStateFlow(0)
    val catalogVersion: StateFlow<Int> = _catalogVersion
    private val _compatibility = MutableStateFlow(store.compatibilityReports())
    val compatibility: StateFlow<Map<String, CompatibilityReport>> = _compatibility
    private val _compatibilityDatabaseDate = MutableStateFlow(prefs.getString("compatibilityDatabaseDate", null))
    val compatibilityDatabaseDate: StateFlow<String?> = _compatibilityDatabaseDate
    val compatibilitySyncing = MutableStateFlow(false)
    val exportFolder = MutableStateFlow(prefs.getString("exportFolder", null))
    val status = MutableStateFlow<String?>(null)
    val loading = MutableStateFlow(false)
    val syncing = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val downloadsDir = File(context.filesDir, "downloads").apply { mkdirs() }
    fun licenseText(): String = context.assets.open("LICENSE").bufferedReader().use { it.readText() }

    init {
        store.removeLegacyDemo()
        if (prefs.getString("source_${Category.GAMES.name}", null) == "demo")
            prefs.edit().remove("source_${Category.GAMES.name}").remove("demo").apply()
        if (!prefs.getBoolean("licenseCatalogV2", false)) {
            val edit = prefs.edit().putBoolean("licenseCatalogV2", true)
            Category.entries.filter { it.vita3kRelevant }.forEach { edit.remove("refresh_${it.name}") }
            edit.apply()
        }
        if (!prefs.getBoolean("filenameVersionV1", false)) {
            prefs.edit().putBoolean("filenameVersionV1", true)
                .remove("refresh_${Category.GAMES.name}").remove("refresh_${Category.DEMOS.name}").apply()
        }
        store.resetInterrupted(); refreshJobs(); schedule()
        scope.launch { syncCatalogs(); repairDownloadedLicenses(); syncCompatibility() }
    }

    private fun repairDownloadedLicenses() {
        var changed = false
        store.jobs().filter { it.state == JobState.DOWNLOADED && it.category.vita3kRelevant &&
            (isLicenseError(it.error) || !licenseFile(it).isFile) }.forEach { job ->
            try {
                if (ensureLicense(job) != null) {
                    if (isLicenseError(job.error)) store.update(job.id, error = "")
                    changed = true
                }
            } catch (_: Exception) { /* Keep the downloaded PKG and its actionable error. */ }
        }
        if (changed) refreshJobs()
    }
    private fun isLicenseError(error: String?): Boolean = error?.contains("license is invalid", true) == true ||
        error?.startsWith("Invalid RIF") == true

    fun refreshJobs() { _jobs.value = store.jobs() }
    fun catalog(filter: CatalogFilter) = Pager(PagingConfig(pageSize = 50, prefetchDistance = 15)) { store.paging(filter) }.flow
    fun url(category: Category) = prefs.getString(category.key, category.upstreamUrl).orEmpty()
    fun saveUrl(category: Category, value: String) {
        prefs.edit().putString(category.key, value.trim()).putString("source_${category.name}", "remote").apply()
    }
    fun useUpstreamCatalogs() {
        val edit = prefs.edit()
        VitaCategories.forEach { edit.remove(it.key).putString("source_${it.name}", "remote").remove("refresh_${it.name}") }
        edit.apply()
        scope.launch { syncCatalogs() }
    }
    suspend fun syncCatalogs() {
        if (syncing.value) return
        syncing.value = true
        try {
            val now = System.currentTimeMillis()
            val pending = VitaCategories.filter { category ->
                val source = prefs.getString("source_${category.name}", "remote")
                val cached = store.count(CatalogFilter(category = category.name)) > 0
                source == "remote" && url(category).isNotBlank() &&
                    (!cached || now - prefs.getLong("refresh_${category.name}", 0) >= 24L * 60 * 60 * 1000)
            }
            if (pending.isEmpty()) return
            var failures = 0
            pending.forEach { category ->
                status.value = "Updating ${category.label}"
                try {
                    refresh(category)
                    prefs.edit().putLong("refresh_${category.name}", System.currentTimeMillis()).apply()
                } catch (_: Exception) { failures++ }
            }
            status.value = if (failures == 0) null else "$failures catalog updates failed. Check Settings."
        } finally { syncing.value = false }
    }

    fun refreshCompatibility() { scope.launch { syncCompatibility() } }

    private suspend fun syncCompatibility() {
        if (compatibilitySyncing.value) return
        compatibilitySyncing.value = true
        try {
            val download = CompatibilityDataSource.checkForUpdates(
                prefs.getString("compatibilityAssetEtag", null),
                prefs.getString("compatibilityAssetModified", null))
            if (download == null) return
            store.replaceCompatibility(download.database.reports)
            prefs.edit().putString("compatibilityAssetEtag", download.etag)
                .putString("compatibilityAssetModified", download.lastModified)
                .putString("compatibilityDatabaseDate", download.database.updatedAt).apply()
            _compatibility.value = store.compatibilityReports()
            _catalogVersion.value++
            _compatibilityDatabaseDate.value = download.database.updatedAt
            status.value = "Compatibility updated"
        } catch (e: Exception) {
            android.util.Log.w("PKGjCompatibility", "Compatibility update failed (${e::class.java.simpleName})")
            status.value = "Compatibility update failed"
        } finally { compatibilitySyncing.value = false }
    }

    suspend fun import(uri: Uri, category: Category): ParseStats = withContext(Dispatchers.IO) {
        loading.value = true
        try {
            val stats = context.contentResolver.openInputStream(uri)?.reader(Charsets.UTF_8)?.use { store.replaceCatalog(category, it) }
                ?: error("Cannot open selected file")
            prefs.edit().putString("source_${category.name}", "local").apply()
            _catalogVersion.value++; status.value = if (stats.malformed == 0) "Imported ${stats.accepted}" else
                "Imported ${stats.accepted}. Skipped ${stats.malformed}."
            stats
        } catch (e: Exception) { status.value = "Import failed: ${e.message}"; throw e }
        finally { loading.value = false }
    }
    suspend fun refresh(category: Category): ParseStats = withContext(Dispatchers.IO) {
        val inputUrl = url(category)
        require(inputUrl.startsWith("https://") || inputUrl.startsWith("http://")) { "Enter an HTTP or HTTPS catalog URL" }
        loading.value = true
        try {
            var current = URL(inputUrl); var connection: HttpURLConnection? = null
            for (i in 0..5) {
                val c = current.openConnection() as HttpURLConnection
                c.connectTimeout = 20_000; c.readTimeout = 30_000; c.instanceFollowRedirects = false
                if (c.responseCode in 300..399) {
                    val next = URL(current, c.getHeaderField("Location") ?: error("Redirect without Location"))
                    if (current.protocol == "https" && next.protocol != "https") error("Insecure redirect")
                    c.disconnect(); current = next
                } else { connection = c; break }
            }
            val c = connection ?: error("Too many redirects")
            try {
                require(c.responseCode == 200) { "Catalog HTTP ${c.responseCode}" }
                val stats = c.inputStream.reader(Charsets.UTF_8).use { store.replaceCatalog(category, it) }
                prefs.edit().putString("source_${category.name}", "remote").putLong("refresh_${category.name}", System.currentTimeMillis()).apply()
                _catalogVersion.value++; status.value = "Updated ${category.label}"
                stats
            } finally { c.disconnect() }
        } catch (e: Exception) { status.value = "Update failed: ${e.message}"; throw e }
        finally { loading.value = false }
    }

    fun queue(item: CatalogItem) {
        require(item.category.vita3kRelevant) { "Only PS Vita content is supported" }
        if (!CatalogParser.usableUrl(item.url)) { status.value = "No download URL"; return }
        val base = DownloadNames.baseName(item)
        var filename = "$base.pkg"
        var copy = 2
        while (store.filenameInUse(filename) || File(downloadsDir, filename).exists() ||
            File(downloadsDir, filename.removeSuffix(".pkg") + ".rif").exists()) {
            filename = "$base ($copy).pkg"
            copy++
        }
        store.addJob(item, filename); refreshJobs(); schedule()
    }
    fun licenseFile(job: DownloadJob) = File(downloadsDir, job.filename.removeSuffix(".pkg") + ".rif")
    fun hasCatalogLicense(job: DownloadJob): Boolean = job.category.vita3kRelevant &&
        (job.zrif.isNotBlank() || store.catalogZrif(job.category, job.contentId).isNotBlank())
    fun ensureLicense(job: DownloadJob): File? {
        if (!job.category.vita3kRelevant) return null
        val zrif = job.zrif.ifBlank { store.catalogZrif(job.category, job.contentId) }
        if (zrif.isBlank()) return null
        val target = licenseFile(job)
        if (target.isFile && target.length() == 512L) return target
        val bytes = LicenseCodec.decode(zrif, job.contentId)
        val temp = File(downloadsDir, target.name + ".tmp")
        try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) error("Could not save license file")
        } finally { temp.delete() }
        return target
    }
    fun action(job: DownloadJob, action: JobState) {
        if (!JobTransitions.canMove(job.state, action)) return
        if (action == JobState.CANCELED) {
            File(downloadsDir, job.filename).delete()
            licenseFile(job).delete()
            store.update(job.id, state = action, bytes = 0, speed = 0)
        } else { store.update(job.id, state = action, speed = 0); if (action == JobState.QUEUED) schedule() }
        refreshJobs()
    }
    fun delete(job: DownloadJob) {
        if (job.state == JobState.RUNNING) { action(job, JobState.CANCELED); return }
        File(downloadsDir, job.filename).delete()
        licenseFile(job).delete()
        store.update(job.id, state = JobState.CANCELED, bytes = 0, speed = 0)
        refreshJobs()
    }
    fun deleteCompleted(job: DownloadJob) {
        if (store.job(job.id)?.state != JobState.DOWNLOADED) return
        val file = File(downloadsDir, job.filename)
        val license = licenseFile(job)
        if (license.exists() && !license.delete()) { status.value = "Could not delete local license"; return }
        if (file.exists() && !file.delete()) { status.value = "Could not delete local file"; return }
        store.removeJob(job.id); refreshJobs()
        status.value = "Deleted"
    }
    fun setExportFolder(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.edit().putString("exportFolder", uri.toString()).apply(); exportFolder.value = uri.toString()
    }
    fun clearExportFolder() { prefs.edit().remove("exportFolder").apply(); exportFolder.value = null }
    private fun copyToUri(file: File, uri: Uri) {
        context.contentResolver.openOutputStream(uri, "wt")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
    }
    suspend fun exportTo(job: DownloadJob, uri: Uri) = withContext(Dispatchers.IO) {
        val file = File(downloadsDir, job.filename)
        require(job.state == JobState.DOWNLOADED && file.isFile) { "Download is unavailable" }
        copyToUri(file, uri)
        store.update(job.id, exported = true); refreshJobs()
    }
    suspend fun exportLicenseTo(job: DownloadJob, uri: Uri) = withContext(Dispatchers.IO) {
        require(job.state == JobState.DOWNLOADED) { "Download is unavailable" }
        val file = ensureLicense(job) ?: error("This catalog entry has no license")
        copyToUri(file, uri)
        store.update(job.id, licenseExported = true, error = ""); refreshJobs()
    }
    suspend fun exportToFolder(job: DownloadJob): Boolean = withContext(Dispatchers.IO) {
        val folder = exportFolder.value ?: return@withContext false
        require(job.state == JobState.DOWNLOADED) { "Download is unavailable" }
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(folder)) ?: error("Export folder unavailable")
        val pkg = File(downloadsDir, job.filename)
        require(pkg.isFile) { "Download is unavailable" }
        val rif = ensureLicense(job)
        val pkgTarget = tree.createFile("application/octet-stream", job.filename) ?: error("Cannot create export file")
        var rifTarget: DocumentFile? = null
        try {
            copyToUri(pkg, pkgTarget.uri)
            if (rif != null) {
                rifTarget = tree.createFile("application/octet-stream", rif.name) ?: error("Cannot create license file")
                copyToUri(rif, rifTarget.uri)
            }
            store.update(job.id, exported = true, licenseExported = if (rif != null) true else null,
                error = if (rif != null) "" else null); refreshJobs()
            true
        } catch (e: Exception) { rifTarget?.delete(); pkgTarget.delete(); throw e }
    }
    fun schedule() {
        WorkManager.getInstance(context).enqueueUniqueWork("download-queue", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<DownloadWorker>().build())
    }

    companion object {
        @Volatile private var instance: Repository? = null
        fun get(context: Context): Repository = instance ?: synchronized(this) {
            instance ?: Repository(context.applicationContext).also { instance = it }
        }
    }
}
