package org.pkgj.android.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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
import org.pkgj.android.updates.*
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
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
        store.jobs().filter { it.folderUri == null && it.state == JobState.DOWNLOADED && it.needsLicense &&
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
        CatalogCategories.forEach { edit.remove(it.key).putString("source_${it.name}", "remote").remove("refresh_${it.name}") }
        edit.apply()
        scope.launch { syncCatalogs() }
    }
    suspend fun syncCatalogs() {
        if (syncing.value) return
        syncing.value = true
        try {
            val now = System.currentTimeMillis()
            val pending = CatalogCategories.filter { category ->
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

    private val patchSource by lazy {
        val tls = SonyUpdateTls(context.resources.openRawResource(org.pkgj.android.R.raw.sony_update_ca))
        PatchSource(configureConnection = tls::configure)
    }
    suspend fun cachedPatch(titleId: String, platform: Platform = Platform.VITA): PatchCheck? = withContext(Dispatchers.IO) { store.patchCheck(titleId, platform) }
    suspend fun checkPatch(titleId: String, refresh: Boolean = false, source: PatchSource = patchSource, platform: Platform = Platform.VITA): PatchCheck = withContext(Dispatchers.IO) {
        require(PatchUrls.validTitleId(titleId)) { "Invalid PS Vita Title ID" }
        val cached = store.patchCheck(titleId, platform)
        val now = System.currentTimeMillis()
        if (!refresh && cached != null && now - cached.checkedAt in 0 until 24L * 60 * 60 * 1000) return@withContext cached
        val packages = source.fetchPackages(titleId, platform)
        coroutineContext.ensureActive()
        PatchCheck(titleId, System.currentTimeMillis(), packages.lastOrNull(), platform, packages).also { store.savePatchCheck(it) }
    }

    fun queue(item: CatalogItem) = queue(listOf(item))
    fun queue(items: List<CatalogItem>) {
        require(items.isNotEmpty() && items.all { it.category.supported }) { "Unsupported platform" }
        if (items.any { !CatalogParser.usableUrl(it.url) }) { status.value = "No download URL"; return }
        val folder = exportFolder.value ?: run { status.value = "Choose a download folder in Settings"; return }
        items.asReversed().forEach { enqueueDownload(it, folder) }
        refreshJobs(); schedule()
    }

    @Synchronized
    private fun enqueueDownload(item: CatalogItem, folder: String, automatic: Boolean = false): Long {
        if (item.downloadType == DownloadType.UPDATE) {
            store.patchJob(item.titleId, item.version, item.category, item.patchPart)?.let { existing ->
                if (!automatic) when (existing.state) {
                    JobState.PAUSED, JobState.FAILED, JobState.CANCELED -> action(existing, JobState.QUEUED)
                    JobState.DOWNLOADED -> status.value = "Update already saved"
                    else -> status.value = "Update already queued"
                }
                return existing.id
            }
        }
        val base = DownloadNames.baseName(item)
        var filename = "$base.pkg"
        var copy = 2
        while (store.filenameInUse(filename) || File(downloadsDir, filename).exists() ||
            File(downloadsDir, filename.removeSuffix(".pkg") + ".rif").exists()) {
            filename = "$base ($copy).pkg"
            copy++
        }
        return store.addJob(item, filename, folder, checkForUpdate = true)
    }

    /** Run by the persistent queue. Discovery errors never fail the base transfer. */
    suspend fun queueGameUpdate(job: DownloadJob, source: PatchSource? = null) {
        if (job.updateCheck != "PENDING") return
        try {
            val result = if (source == null) checkPatch(job.titleId, platform = job.category.platform) else checkPatch(job.titleId, source = source, platform = job.category.platform)
            coroutineContext.ensureActive()
            finishGameUpdateCheck(job.id, result)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { store.setUpdateCheck(job.id, "FAILED", updateCheckError(e)) }
        refreshJobs()
    }

    @Synchronized
    private fun finishGameUpdateCheck(id: Long, result: PatchCheck) {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            val job = store.job(id)
            if (job != null && job.updateCheck == "PENDING" && job.state in setOf(JobState.QUEUED, JobState.RUNNING, JobState.DOWNLOADED, JobState.FAILED)) {
                result.packages.asReversed().forEach { patch ->
                    val game = CatalogItem(job.category, job.titleId, job.contentId, job.region, job.title, job.url, job.total)
                    enqueueDownload(patch.downloadItem(game), requireNotNull(job.folderUri), automatic = true)
                }
                store.setUpdateCheck(id, "DONE")
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun retryGameUpdate(job: DownloadJob) {
        if (store.job(job.id)?.updateCheck != "FAILED") return
        store.setUpdateCheck(job.id, "PENDING")
        refreshJobs(); schedule()
    }
    fun licenseFile(job: DownloadJob) = File(downloadsDir, job.licenseName)
    fun hasCatalogLicense(job: DownloadJob): Boolean = job.needsLicense && licenseValue(job).isNotBlank()
    private fun licenseValue(job: DownloadJob) = if (job.category.platform == Platform.PS3)
        job.rap.ifBlank { store.catalogRap(job.category, job.contentId) }
        else job.zrif.ifBlank { store.catalogZrif(job.category, job.contentId) }
    private fun licenseBytes(job: DownloadJob, value: String): ByteArray = if (job.category.platform == Platform.PS3) Ps3License.decode(value) else LicenseCodec.decode(value, job.contentId)
    fun ensureLicense(job: DownloadJob): File? {
        if (!job.needsLicense) return null
        val material = licenseValue(job)
        if (material.isBlank()) return null
        val target = licenseFile(job)
        if (target.isFile && target.length() == (if (job.category.platform == Platform.PS3) 16L else 512L)) return target
        val bytes = licenseBytes(job, material)
        val temp = File(downloadsDir, target.name + ".tmp")
        try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) error("Could not save license file")
        } finally { temp.delete() }
        return target
    }
    @Synchronized
    fun prepareFolderDownload(job: DownloadJob): DocumentFile {
        if (store.job(job.id)?.state == JobState.CANCELED) throw DownloadInterrupted(JobState.CANCELED)
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(job.folderUri ?: error("No download folder")))
            ?: error("Download folder unavailable. Choose it again in Settings.")
        require(tree.canWrite()) { "Download folder is not writable. Choose it again in Settings." }
        job.packageUri?.let { uri ->
            DocumentFile.fromSingleUri(context, Uri.parse(uri))?.takeIf { it.exists() }?.let { return it }
        }
        // Document identifiers can change when a provider renames a file.
        if (job.transferComplete) {
            val file = tree.findFile(job.filename) ?: tree.findFile("${job.filename}.${job.id}.part")
                ?: error("Downloaded file is missing")
            require(file.length() == job.bytes) { "Downloaded file size changed" }
            store.update(job.id, packageUri = file.uri.toString())
            return file
        }
        tree.findFile("${job.filename}.${job.id}.part")?.let {
            store.update(job.id, packageUri = it.uri.toString()); return it
        }
        var filename = job.filename
        var copy = 2
        while (tree.findFile(filename) != null || (job.category.vita3kRelevant && tree.findFile(filename.removeSuffix(".pkg") + ".rif") != null)) {
            filename = job.filename.removeSuffix(".pkg") + " (${copy++}).pkg"
        }
        if (filename != job.filename) store.setFilename(job.id, filename)
        val file = tree.createFile("application/octet-stream", "$filename.${job.id}.part")
            ?: error("Cannot create download file")
        store.update(job.id, packageUri = file.uri.toString())
        return file
    }

    fun openFolderDownload(file: DocumentFile) = DocumentDestination(context.contentResolver, file.uri)

    /** Called only after the transport has persisted successful completion. */
    @Synchronized
    fun finishFolderDownload(job: DownloadJob): String? {
        require(job.transferComplete) { "Download is incomplete" }
        val pkg = prepareFolderDownload(job)
        require(pkg.length() == job.bytes) { "Downloaded file size changed" }
        if (pkg.name != job.filename) {
            val uri = try { DocumentsContract.renameDocument(context.contentResolver, pkg.uri, job.filename) }
                catch (e: Exception) { throw java.io.IOException("Could not rename downloaded PKG", e) }
            store.update(job.id, packageUri = (uri ?: error("Could not rename downloaded PKG")).toString())
        }
        if (!job.needsLicense) return null
        val material = licenseValue(job)
        if (material.isBlank()) return "No ${job.licenseLabel}"
        val bytes = try { licenseBytes(job, material) } catch (_: IllegalArgumentException) { return "Invalid ${job.licenseLabel}" }
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(job.folderUri!!)) ?: error("Download folder unavailable")
        val name = job.licenseName
        if (job.category.platform == Platform.PS3 && job.licenseUri == null && tree.findFile(name) != null)
            return "RAP already exists"
        val rif = job.licenseUri?.let { DocumentFile.fromSingleUri(context, Uri.parse(it)) }?.takeIf { it.exists() }
            ?: (if (job.licenseUri != null) tree.findFile(name) else null)
            ?: tree.findFile("$name.${job.id}.part")
            ?: tree.createFile("application/octet-stream", "$name.${job.id}.part") ?: error("Cannot create ${job.licenseLabel}")
        store.update(job.id, licenseUri = rif.uri.toString())
        context.contentResolver.openOutputStream(rif.uri, "wt")?.use { it.write(bytes) } ?: error("Cannot save ${job.licenseLabel}")
        val rifUri = if (rif.name == name) rif.uri else try {
            DocumentsContract.renameDocument(context.contentResolver, rif.uri, name) ?: error("Could not rename ${job.licenseLabel}")
        } catch (e: Exception) { throw java.io.IOException("Could not rename ${job.licenseLabel}", e) }
        store.update(job.id, licenseUri = rifUri.toString(), licenseExported = true)
        return null
    }

    @Synchronized
    fun discardDownloadFiles(job: DownloadJob) {
        val current = store.job(job.id) ?: job
        if (current.folderUri != null) {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(current.folderUri)) ?: error("Download folder unavailable")
            require(tree.canWrite()) { "Cannot delete files. Re-select the original download folder in Settings." }
            val names = if (current.needsLicense) listOf(current.filename, current.licenseName) else listOf(current.filename)
            (if (current.needsLicense) listOf(current.packageUri, current.licenseUri) else listOf(current.packageUri)).forEachIndexed { index, uri ->
                val document = uri?.let { DocumentFile.fromSingleUri(context, Uri.parse(it)) }?.takeIf { it.exists() }
                    ?: tree.findFile("${names[index]}.${current.id}.part")
                    ?: if (current.transferComplete && uri != null) tree.findFile(names[index]) else null
                if (document != null) require(document.delete()) { "Could not delete ${document.name}" }
            }
        } else {
            val file = File(downloadsDir, current.filename)
            if (file.exists()) require(file.delete()) { "Could not delete PKG" }
            if (current.needsLicense) {
                val license = licenseFile(current)
                if (license.exists()) require(license.delete()) { "Could not delete ${current.licenseLabel}" }
            }
        }
    }

    fun action(job: DownloadJob, action: JobState) {
        if (!store.transition(job.id, action)) return
        if (action == JobState.CANCELED) {
            runCatching { discardDownloadFiles(store.job(job.id) ?: job) }
                .onSuccess { store.update(job.id, bytes = 0, transferComplete = false, exported = false, licenseExported = false) }
                .onFailure { store.update(job.id, error = it.message ?: "Could not delete files") }
        } else if (action == JobState.QUEUED) {
            if (job.updateCheck == "FAILED") store.setUpdateCheck(job.id, "PENDING")
            schedule()
        }
        refreshJobs()
    }
    fun delete(job: DownloadJob) = action(job, JobState.CANCELED)
    fun deleteCompleted(job: DownloadJob) {
        if (store.job(job.id)?.state != JobState.DOWNLOADED) return
        scope.launch {
            runCatching { discardDownloadFiles(job) }
                .onSuccess { store.removeJob(job.id); status.value = "Deleted" }
                .onFailure { status.value = it.message ?: "Could not delete files" }
            refreshJobs()
        }
    }

    fun setExportFolder(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.edit().putString("exportFolder", uri.toString()).apply(); exportFolder.value = uri.toString()
    }
    fun clearExportFolder() { prefs.edit().remove("exportFolder").apply(); exportFolder.value = null }
    private fun copyToUri(file: File, uri: Uri) {
        context.contentResolver.openOutputStream(uri, "wt")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
    }
    private fun copyDocument(source: String, destination: Uri) {
        require(Uri.parse(source) != destination) { "Choose a different file" }
        context.contentResolver.openInputStream(Uri.parse(source))?.use { input ->
            context.contentResolver.openOutputStream(destination, "wt")?.use { input.copyTo(it) } ?: error("Cannot write selected file")
        } ?: error("Download is unavailable")
    }
    suspend fun exportTo(job: DownloadJob, uri: Uri) = withContext(Dispatchers.IO) {
        require(job.state == JobState.DOWNLOADED) { "Download is unavailable" }
        if (job.folderUri == null) copyToUri(File(downloadsDir, job.filename), uri)
        else copyDocument(job.packageUri ?: error("Download is unavailable"), uri)
        store.update(job.id, exported = true); refreshJobs()
    }
    suspend fun exportLicenseTo(job: DownloadJob, uri: Uri) = withContext(Dispatchers.IO) {
        require(job.state == JobState.DOWNLOADED) { "Download is unavailable" }
        require(job.needsLicense) { "This download has no license file" }
        if (job.folderUri == null) copyToUri(ensureLicense(job) ?: error("No ${job.licenseLabel}"), uri)
        else copyDocument(job.licenseUri ?: error("No ${job.licenseLabel}"), uri)
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
            store.update(job.id, folderUri = folder, packageUri = pkgTarget.uri.toString(), licenseUri = rifTarget?.uri?.toString(),
                transferComplete = true, exported = true, licenseExported = rif != null, error = if (rif != null || !job.needsLicense) "" else "No ${job.licenseLabel}")
            pkg.delete(); rif?.delete()
            refreshJobs()
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
