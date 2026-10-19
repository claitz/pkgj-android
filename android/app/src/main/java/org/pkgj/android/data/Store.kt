package org.pkgj.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.paging.PagingSource
import androidx.paging.PagingState
import org.pkgj.android.catalog.*
import org.pkgj.android.download.DownloadType
import org.pkgj.android.updates.*
import org.pkgj.android.download.DownloadJob
import org.pkgj.android.download.JobState
import org.pkgj.android.download.JobTransitions
import org.pkgj.android.compatibility.CompatibilityReport
import org.pkgj.android.compatibility.CompatibilityKey

class Store(context: Context, databaseName: String = "pkgj_android.db") : SQLiteOpenHelper(context, databaseName, null, 10) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE catalog(category TEXT NOT NULL,content TEXT NOT NULL,titleid TEXT NOT NULL,region TEXT NOT NULL,title TEXT NOT NULL,url TEXT NOT NULL,size INTEGER,zrif TEXT NOT NULL DEFAULT '',version TEXT NOT NULL DEFAULT '',rap TEXT NOT NULL DEFAULT '',PRIMARY KEY(category,content))")
        db.execSQL("CREATE INDEX catalog_title ON catalog(title COLLATE NOCASE)")
        db.execSQL("CREATE TABLE jobs(id INTEGER PRIMARY KEY AUTOINCREMENT,category TEXT NOT NULL,content TEXT NOT NULL,title TEXT NOT NULL,url TEXT NOT NULL,filename TEXT NOT NULL,state TEXT NOT NULL,bytes INTEGER NOT NULL DEFAULT 0,total INTEGER,etag TEXT,error TEXT,speed INTEGER NOT NULL DEFAULT 0,exported INTEGER NOT NULL DEFAULT 0,zrif TEXT NOT NULL DEFAULT '',license_exported INTEGER NOT NULL DEFAULT 0,folder_uri TEXT,package_uri TEXT,license_uri TEXT,transfer_complete INTEGER NOT NULL DEFAULT 0,type TEXT NOT NULL DEFAULT 'CATALOG',titleid TEXT NOT NULL DEFAULT '',patch_version TEXT NOT NULL DEFAULT '',update_check TEXT NOT NULL DEFAULT '',update_error TEXT NOT NULL DEFAULT '',region TEXT NOT NULL DEFAULT '',rap TEXT NOT NULL DEFAULT '',patch_part INTEGER NOT NULL DEFAULT 0)")
        createCompatibility(db)
        createUpdates(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2 && newVersion >= 2) {
            db.execSQL("ALTER TABLE catalog ADD COLUMN zrif TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE jobs ADD COLUMN zrif TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 3 && newVersion >= 3) db.execSQL("ALTER TABLE jobs ADD COLUMN license_exported INTEGER NOT NULL DEFAULT 0")
        if (oldVersion < 4 && newVersion >= 4) db.execSQL("ALTER TABLE catalog ADD COLUMN version TEXT NOT NULL DEFAULT ''")
        if (oldVersion < 5 && newVersion >= 5) db.execSQL("CREATE TABLE compatibility(titleid TEXT PRIMARY KEY COLLATE NOCASE,issue_id INTEGER NOT NULL,status TEXT NOT NULL,updated_at INTEGER NOT NULL)")
        if (oldVersion < 6 && newVersion >= 6) {
            db.execSQL("ALTER TABLE jobs ADD COLUMN folder_uri TEXT")
            db.execSQL("ALTER TABLE jobs ADD COLUMN package_uri TEXT")
            db.execSQL("ALTER TABLE jobs ADD COLUMN license_uri TEXT")
            db.execSQL("ALTER TABLE jobs ADD COLUMN transfer_complete INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 7 && newVersion >= 7) {
            db.execSQL("ALTER TABLE jobs ADD COLUMN type TEXT NOT NULL DEFAULT 'CATALOG'")
            db.execSQL("ALTER TABLE jobs ADD COLUMN titleid TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE jobs ADD COLUMN patch_version TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE jobs SET titleid=substr(content,8,9) WHERE length(content)>=16")
            createUpdates(db, legacy = true)
        }
        if (oldVersion < 8 && newVersion >= 8) {
            db.execSQL("ALTER TABLE jobs ADD COLUMN update_check TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE jobs ADD COLUMN update_error TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE jobs ADD COLUMN region TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 9 && newVersion >= 9) {
            db.execSQL("ALTER TABLE catalog ADD COLUMN rap TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE jobs ADD COLUMN rap TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE jobs ADD COLUMN patch_part INTEGER NOT NULL DEFAULT 0")
            db.execSQL("DROP INDEX jobs_update_identity")
            db.execSQL("CREATE UNIQUE INDEX jobs_update_identity ON jobs(category,titleid,patch_version,patch_part) WHERE type='UPDATE'")
            db.execSQL("ALTER TABLE update_checks RENAME TO old_update_checks")
            createUpdateCache(db)
            db.execSQL("INSERT INTO update_checks SELECT 'VITA',titleid,checked_at,version,url,size,firmware FROM old_update_checks")
            db.execSQL("INSERT INTO update_packages SELECT 'VITA',titleid,0,version,url,size,firmware,0 FROM old_update_checks WHERE version IS NOT NULL")
            db.execSQL("DROP TABLE old_update_checks")
        }
        if (oldVersion < 10 && newVersion >= 10) {
            db.execSQL("ALTER TABLE compatibility RENAME TO old_compatibility")
            createCompatibility(db)
            db.execSQL("INSERT INTO compatibility SELECT 'VITA',titleid,issue_id,status,updated_at FROM old_compatibility")
            db.execSQL("DROP TABLE old_compatibility")
        }
    }

    private fun createCompatibility(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE compatibility(platform TEXT NOT NULL,titleid TEXT NOT NULL COLLATE NOCASE,issue_id INTEGER NOT NULL,status TEXT NOT NULL,updated_at INTEGER NOT NULL,PRIMARY KEY(platform,titleid))")
    }

    private fun createUpdates(db: SQLiteDatabase, legacy: Boolean = false) {
        if (legacy) {
            db.execSQL("CREATE UNIQUE INDEX jobs_update_identity ON jobs(titleid,patch_version) WHERE type='UPDATE'")
            db.execSQL("CREATE TABLE update_checks(titleid TEXT PRIMARY KEY,checked_at INTEGER NOT NULL,version TEXT,url TEXT,size INTEGER,firmware TEXT)")
        } else {
            db.execSQL("CREATE UNIQUE INDEX jobs_update_identity ON jobs(category,titleid,patch_version,patch_part) WHERE type='UPDATE'")
            createUpdateCache(db)
        }
    }
    private fun createUpdateCache(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE update_checks(platform TEXT NOT NULL,titleid TEXT NOT NULL,checked_at INTEGER NOT NULL,version TEXT,url TEXT,size INTEGER,firmware TEXT,PRIMARY KEY(platform,titleid))")
        db.execSQL("CREATE TABLE update_packages(platform TEXT NOT NULL,titleid TEXT NOT NULL,position INTEGER NOT NULL,version TEXT NOT NULL,url TEXT NOT NULL,size INTEGER,firmware TEXT,part INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(platform,titleid,position))")
    }
    fun patchCheck(titleId: String, platform: Platform = Platform.VITA): PatchCheck? {
        val args = arrayOf(platform.name, titleId)
        val checkedAt = readableDatabase.rawQuery("SELECT checked_at FROM update_checks WHERE platform=? AND titleid=?", args).use { c ->
            if (!c.moveToFirst()) return null else c.getLong(0)
        }
        val packages = mutableListOf<GamePatch>()
        readableDatabase.rawQuery("SELECT version,url,size,firmware,part FROM update_packages WHERE platform=? AND titleid=? ORDER BY position", args).use { c ->
            while (c.moveToNext()) packages += GamePatch(titleId, c.getString(0), c.getString(1), if(c.isNull(2)) null else c.getLong(2), c.getString(3), platform, c.getInt(4))
        }
        return PatchCheck(titleId, checkedAt, packages.lastOrNull(), platform, packages)
    }
    fun savePatchCheck(check: PatchCheck) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("update_checks", null, ContentValues().apply {
                put("platform", check.platform.name); put("titleid", check.titleId); put("checked_at", check.checkedAt)
                put("version", check.patch?.version); put("url", check.patch?.url)
                check.patch?.size?.let { put("size", it) } ?: putNull("size")
                put("firmware", check.patch?.requiredFirmware)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.delete("update_packages", "platform=? AND titleid=?", arrayOf(check.platform.name, check.titleId))
            check.packages.forEachIndexed { index, patch ->
                require(patch.platform == check.platform && patch.titleId == check.titleId)
                db.insertOrThrow("update_packages", null, ContentValues().apply {
                    put("platform", check.platform.name); put("titleid", check.titleId); put("position", index)
                    put("version", patch.version); put("url", patch.url); put("firmware", patch.requiredFirmware); put("part", patch.part)
                    patch.size?.let { put("size", it) } ?: putNull("size")
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun patchJob(titleId: String, version: String, category: Category = Category.GAMES, part: Int = 0): DownloadJob? = jobs().firstOrNull {
        it.downloadType == DownloadType.UPDATE && it.titleId == titleId && it.patchVersion == version && it.category == category && it.patchPart == part
    }

    fun replaceCompatibility(reports: List<CompatibilityReport>, platform: Platform = Platform.VITA) {
        require(reports.all { it.platform == platform })
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("compatibility", "platform=?", arrayOf(platform.name))
            reports.forEach { report ->
                db.insertWithOnConflict("compatibility", null, ContentValues().apply {
                    put("platform", platform.name); put("titleid", report.titleId); put("issue_id", report.issueId)
                    put("status", report.status); put("updated_at", report.updatedAt)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun compatibilityReports(): Map<CompatibilityKey, CompatibilityReport> = buildMap {
        readableDatabase.rawQuery("SELECT titleid,issue_id,status,updated_at,platform FROM compatibility", null).use { c ->
            while (c.moveToNext()) {
                val titleId = c.getString(0)
                val report = CompatibilityReport(titleId, c.getLong(1), c.getString(2), c.getLong(3), Platform.valueOf(c.getString(4)))
                put(report.key, report)
            }
        }
    }

    fun replaceCatalog(category: Category, source: java.io.Reader): ParseStats {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            db.delete("catalog", "category=?", arrayOf(category.name))
            val stats = CatalogParser.parse(source, category) { item ->
                db.insertWithOnConflict("catalog", null, ContentValues().apply {
                    put("category", item.category.name); put("content", item.contentId); put("titleid", item.titleId)
                    put("region", item.region); put("title", item.title); put("url", item.url); put("zrif", item.zrif)
                    put("version", item.version); put("rap", item.rap)
                    if (item.size != null) put("size", item.size) else putNull("size")
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            require(stats.accepted > 0) { "Catalog contains no valid entries" }
            db.setTransactionSuccessful(); stats
        } finally { db.endTransaction() }
    }

    fun count(filter: CatalogFilter): Int {
        val (where, args) = filter.sqlWhere()
        readableDatabase.rawQuery("SELECT count(*) FROM catalog $where", args).use { it.moveToFirst(); return it.getInt(0) }
    }
    fun page(filter: CatalogFilter, offset: Int, limit: Int): List<CatalogItem> {
        val (where, args) = filter.sqlWhere()
        val order = if (filter.sort == "Size") "size IS NULL, size DESC, title COLLATE NOCASE" else "title COLLATE NOCASE, titleid"
        val result = ArrayList<CatalogItem>(limit)
        readableDatabase.rawQuery("SELECT category,titleid,content,region,title,url,size,zrif,version,rap FROM catalog $where ORDER BY $order LIMIT ? OFFSET ?", args + arrayOf(limit.toString(), offset.toString())).use { c ->
            while (c.moveToNext()) result += CatalogItem(Category.valueOf(c.getString(0)), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), if (c.isNull(6)) null else c.getLong(6), c.getString(7), c.getString(8), rap = c.getString(9))
        }
        return result
    }
    fun paging(filter: CatalogFilter) = object : PagingSource<Int, CatalogItem>() {
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, CatalogItem> = withContext(Dispatchers.IO) {
            try {
                val offset = params.key ?: 0
                val data = page(filter, offset, params.loadSize)
                LoadResult.Page(data, if (offset == 0) null else maxOf(0, offset - params.loadSize), if (data.size < params.loadSize) null else offset + data.size)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { LoadResult.Error(e) }
        }
        override fun getRefreshKey(state: PagingState<Int, CatalogItem>): Int? = state.anchorPosition
    }

    fun addJob(item: CatalogItem, filename: String, folderUri: String? = null, checkForUpdate: Boolean = false): Long {
        val db = writableDatabase
        if (item.downloadType == DownloadType.UPDATE) {
            require(item.category.supportsUpdates && PatchUrls.validTitleId(item.titleId) && PatchXml.validVersion(item.version)) { "Invalid update job" }
            require(item.zrif.isEmpty() && item.rap.isEmpty() && item.contentId == GamePatch(item.titleId, item.version, item.url, item.size, null, item.category.platform, item.patchPart).identity) { "Invalid update identity" }
        }
        val id = db.insertWithOnConflict("jobs", null, ContentValues().apply {
            put("category", item.category.name); put("content", item.contentId); put("title", item.title)
            put("url", item.url); put("filename", filename); put("state", JobState.QUEUED.name); put("zrif", item.zrif); put("folder_uri", folderUri)
            put("rap", item.rap); put("patch_part", item.patchPart)
            put("type", item.downloadType.name); put("titleid", item.titleId); put("region", item.region)
            put("patch_version", if (item.downloadType == DownloadType.UPDATE) item.version else "")
            put("update_check", if (checkForUpdate && item.category.supportsUpdates && item.downloadType == DownloadType.CATALOG) "PENDING" else "")
        }, SQLiteDatabase.CONFLICT_IGNORE)
        return if (id >= 0) id else if (item.downloadType == DownloadType.UPDATE)
            patchJob(item.titleId, item.version, item.category, item.patchPart)?.id ?: error("Could not queue update") else error("Could not queue download")
    }
    fun jobs(): List<DownloadJob> {
        val out = mutableListOf<DownloadJob>()
        readableDatabase.rawQuery("SELECT id,category,content,title,url,filename,state,bytes,total,etag,error,speed,exported,zrif,license_exported,folder_uri,package_uri,license_uri,transfer_complete,type,titleid,patch_version,update_check,update_error,region,rap,patch_part FROM jobs ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) out += DownloadJob(c.getLong(0), Category.valueOf(c.getString(1)), c.getString(2), c.getString(3), c.getString(4), c.getString(5), JobState.valueOf(c.getString(6)), c.getLong(7), if(c.isNull(8)) null else c.getLong(8), c.getString(9), c.getString(10), c.getLong(11), c.getInt(12) != 0, c.getString(13), c.getInt(14) != 0, c.getString(15), c.getString(16), c.getString(17), c.getInt(18) != 0, DownloadType.valueOf(c.getString(19)), c.getString(20), c.getString(21), c.getString(22), c.getString(23), c.getString(24), c.getString(25), c.getInt(26))
        }
        return out
    }
    fun setUpdateCheck(id: Long, state: String, error: String = "") {
        require(state in setOf("PENDING", "DONE", "FAILED"))
        writableDatabase.update("jobs", ContentValues().apply {
            put("update_check", state); put("update_error", error)
        }, "id=? AND type='CATALOG' AND category IN ('GAMES','PS3_GAMES') AND state!='CANCELED'", arrayOf(id.toString()))
    }
    fun job(id: Long) = jobs().firstOrNull { it.id == id }
    fun setFilename(id: Long, filename: String) {
        writableDatabase.update("jobs", ContentValues().apply { put("filename", filename) }, "id=?", arrayOf(id.toString()))
    }
    fun filenameInUse(filename: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM jobs WHERE filename=? COLLATE NOCASE LIMIT 1", arrayOf(filename)).use { it.moveToFirst() }
    fun catalogZrif(category: Category, contentId: String): String = readableDatabase.rawQuery(
        "SELECT zrif FROM catalog WHERE category=? AND content=?", arrayOf(category.name, contentId)).use { c ->
        if (c.moveToFirst()) c.getString(0) else ""
    }
    fun catalogRap(category: Category, contentId: String): String = readableDatabase.rawQuery(
        "SELECT rap FROM catalog WHERE category=? AND content=?", arrayOf(category.name, contentId)).use { c ->
        if (c.moveToFirst()) c.getString(0) else ""
    }
    fun clearDownloadHistory(): Int = writableDatabase.delete("jobs",
        "(state='DOWNLOADED' AND folder_uri IS NOT NULL AND update_check NOT IN ('PENDING','FAILED')) OR (state='CANCELED' AND bytes=0 AND (error IS NULL OR error=''))", null)

    fun removeJob(id: Long) { writableDatabase.delete("jobs", "id=?", arrayOf(id.toString())) }
    fun transition(id: Long, to: JobState): Boolean {
        val from = JobState.entries.filter { JobTransitions.canMove(it, to) }
        if (from.isEmpty()) return false
        return writableDatabase.update("jobs", ContentValues().apply {
            put("state", to.name); put("speed", 0)
        }, "id=? AND state IN (${from.joinToString(",") { "?" }})", arrayOf(id.toString()) + from.map { it.name }) > 0
    }
    fun completeJob(id: Long, error: String, exported: Boolean) = writableDatabase.update("jobs", ContentValues().apply {
        put("state", JobState.DOWNLOADED.name); put("speed", 0); put("error", error); put("exported", if (exported) 1 else 0)
    }, "id=? AND state=?", arrayOf(id.toString(), JobState.RUNNING.name)) > 0
    fun failJob(id: Long, error: String) {
        writableDatabase.update("jobs", ContentValues().apply {
            put("state", JobState.FAILED.name); put("speed", 0); put("error", error)
        }, "id=? AND state=?", arrayOf(id.toString(), JobState.RUNNING.name))
    }
    fun update(id: Long, state: JobState? = null, bytes: Long? = null, total: Long? = null, etag: String? = null, error: String? = null, speed: Long? = null, exported: Boolean? = null, licenseExported: Boolean? = null, folderUri: String? = null, packageUri: String? = null, licenseUri: String? = null, transferComplete: Boolean? = null) {
        val v = ContentValues()
        state?.let { v.put("state", it.name) }; bytes?.let { v.put("bytes", it) }; total?.let { v.put("total", it) }
        if (bytes != null) {
            if (etag == null) v.putNull("etag") else v.put("etag", etag)
            if (bytes == 0L && total == null) v.putNull("total")
        }
        error?.let { v.put("error", it) }; speed?.let { v.put("speed", it) }; exported?.let { v.put("exported", if (it) 1 else 0) }
        licenseExported?.let { v.put("license_exported", if (it) 1 else 0) }
        folderUri?.let { v.put("folder_uri", it) }
        packageUri?.let { v.put("package_uri", it) }
        licenseUri?.let { v.put("license_uri", it) }
        transferComplete?.let { v.put("transfer_complete", if (it) 1 else 0) }
        if (v.size() > 0) writableDatabase.update("jobs", v, "id=?", arrayOf(id.toString()))
    }
    fun resetInterrupted() { writableDatabase.execSQL("UPDATE jobs SET state='QUEUED',speed=0 WHERE state='RUNNING'") }
    fun removeLegacyDemo() {
        writableDatabase.delete("catalog", "category=? AND titleid IN (?,?)",
            arrayOf(Category.GAMES.name, "DEMO00001", "DEMO00002"))
    }
}
