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
import org.pkgj.android.download.DownloadJob
import org.pkgj.android.download.JobState
import org.pkgj.android.download.JobTransitions
import org.pkgj.android.compatibility.CompatibilityReport

class Store(context: Context) : SQLiteOpenHelper(context, "pkgj_android.db", null, 6) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE catalog(category TEXT NOT NULL,content TEXT NOT NULL,titleid TEXT NOT NULL,region TEXT NOT NULL,title TEXT NOT NULL,url TEXT NOT NULL,size INTEGER,zrif TEXT NOT NULL DEFAULT '',version TEXT NOT NULL DEFAULT '',PRIMARY KEY(category,content))")
        db.execSQL("CREATE INDEX catalog_title ON catalog(title COLLATE NOCASE)")
        db.execSQL("CREATE TABLE jobs(id INTEGER PRIMARY KEY AUTOINCREMENT,category TEXT NOT NULL,content TEXT NOT NULL,title TEXT NOT NULL,url TEXT NOT NULL,filename TEXT NOT NULL,state TEXT NOT NULL,bytes INTEGER NOT NULL DEFAULT 0,total INTEGER,etag TEXT,error TEXT,speed INTEGER NOT NULL DEFAULT 0,exported INTEGER NOT NULL DEFAULT 0,zrif TEXT NOT NULL DEFAULT '',license_exported INTEGER NOT NULL DEFAULT 0,folder_uri TEXT,package_uri TEXT,license_uri TEXT,transfer_complete INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE compatibility(titleid TEXT PRIMARY KEY COLLATE NOCASE,issue_id INTEGER NOT NULL,status TEXT NOT NULL,updated_at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE catalog ADD COLUMN zrif TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE jobs ADD COLUMN zrif TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 3) db.execSQL("ALTER TABLE jobs ADD COLUMN license_exported INTEGER NOT NULL DEFAULT 0")
        if (oldVersion < 4) db.execSQL("ALTER TABLE catalog ADD COLUMN version TEXT NOT NULL DEFAULT ''")
        if (oldVersion < 5) db.execSQL("CREATE TABLE compatibility(titleid TEXT PRIMARY KEY COLLATE NOCASE,issue_id INTEGER NOT NULL,status TEXT NOT NULL,updated_at INTEGER NOT NULL)")
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE jobs ADD COLUMN folder_uri TEXT")
            db.execSQL("ALTER TABLE jobs ADD COLUMN package_uri TEXT")
            db.execSQL("ALTER TABLE jobs ADD COLUMN license_uri TEXT")
            db.execSQL("ALTER TABLE jobs ADD COLUMN transfer_complete INTEGER NOT NULL DEFAULT 0")
        }
    }

    fun replaceCompatibility(reports: List<CompatibilityReport>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("compatibility", null, null)
            reports.forEach { report ->
                db.insertWithOnConflict("compatibility", null, ContentValues().apply {
                    put("titleid", report.titleId); put("issue_id", report.issueId)
                    put("status", report.status); put("updated_at", report.updatedAt)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun compatibilityReports(): Map<String, CompatibilityReport> = buildMap {
        readableDatabase.rawQuery("SELECT titleid,issue_id,status,updated_at FROM compatibility", null).use { c ->
            while (c.moveToNext()) {
                val titleId = c.getString(0)
                put(titleId, CompatibilityReport(titleId, c.getLong(1), c.getString(2), c.getLong(3)))
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
                    put("version", item.version)
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
        readableDatabase.rawQuery("SELECT category,titleid,content,region,title,url,size,zrif,version FROM catalog $where ORDER BY $order LIMIT ? OFFSET ?", args + arrayOf(limit.toString(), offset.toString())).use { c ->
            while (c.moveToNext()) result += CatalogItem(Category.valueOf(c.getString(0)), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), if (c.isNull(6)) null else c.getLong(6), c.getString(7), c.getString(8))
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

    fun addJob(item: CatalogItem, filename: String, folderUri: String? = null): Long {
        val db = writableDatabase
        return db.insert("jobs", null, ContentValues().apply {
            put("category", item.category.name); put("content", item.contentId); put("title", item.title)
            put("url", item.url); put("filename", filename); put("state", JobState.QUEUED.name); put("zrif", item.zrif); put("folder_uri", folderUri)
        })
    }
    fun jobs(): List<DownloadJob> {
        val out = mutableListOf<DownloadJob>()
        readableDatabase.rawQuery("SELECT id,category,content,title,url,filename,state,bytes,total,etag,error,speed,exported,zrif,license_exported,folder_uri,package_uri,license_uri,transfer_complete FROM jobs ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) out += DownloadJob(c.getLong(0), Category.valueOf(c.getString(1)), c.getString(2), c.getString(3), c.getString(4), c.getString(5), JobState.valueOf(c.getString(6)), c.getLong(7), if(c.isNull(8)) null else c.getLong(8), c.getString(9), c.getString(10), c.getLong(11), c.getInt(12) != 0, c.getString(13), c.getInt(14) != 0, c.getString(15), c.getString(16), c.getString(17), c.getInt(18) != 0)
        }
        return out
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
