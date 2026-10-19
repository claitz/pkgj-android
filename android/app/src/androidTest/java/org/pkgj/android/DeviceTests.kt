package org.pkgj.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.pkgj.android.catalog.*
import org.pkgj.android.compatibility.CompatibilityReport
import org.pkgj.android.compatibility.CompatibilityKey
import org.pkgj.android.data.Repository
import org.pkgj.android.data.Store
import org.pkgj.android.download.*
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater

@RunWith(AndroidJUnit4::class)
class DeviceTests {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val tree = DocumentsContract.buildTreeDocumentUri("org.pkgj.android.catalog.test.documents", "root")
    private val content = "EP0000-PCSE90001_00-0000000000000000"
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            if (android.os.Build.VERSION.SDK_INT >= 33)
                InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
            // Grant from the provider owner's test-only activity, as the system picker would.
            context.startActivity(Intent().setClassName("org.pkgj.android.catalog.test", "org.pkgj.android.FixtureGrantActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val grantDeadline = android.os.SystemClock.elapsedRealtime() + 5_000
            while (context.checkUriPermission(tree, android.os.Process.myPid(), android.os.Process.myUid(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                check(android.os.SystemClock.elapsedRealtime() < grantDeadline) { "Fixture URI grant timed out" }
                Thread.sleep(50)
            }
            val edit = context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putBoolean("licenseCatalogV2", true).putBoolean("filenameVersionV1", true)
            Category.entries.forEach { edit.putString("source_${it.name}", "local") }
            edit.apply()
            context.contentResolver.call(tree, "reset-fixtures", null, null)
            InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()
            Store(context).use { store ->
                store.writableDatabase.delete("catalog", null, null)
                store.writableDatabase.delete("update_checks", null, null)
                (1..4).forEach { store.savePatchCheck(org.pkgj.android.updates.PatchCheck("PCSE9000$it", System.currentTimeMillis(), null)) }
                val entries = listOf("Alpha" to "EU", "Beta" to "US", "Gamma" to "JP", "Omega" to "ASIA")
                val tsv = "header\n" + entries.mapIndexed { index, (name, region) ->
                    "ID\t$region\t$name\thttp://127.0.0.1/unused\t\tEP0000-PCSE9000${index + 1}_00-0000000000000000\t\t\t4"
                }.joinToString("\n")
                store.replaceCatalog(Category.GAMES, tsv.reader())
                store.replaceCatalog(Category.PSP, "header\nID\tJP\tOriginal\tPSP fixture\thttp://127.0.0.1/unused\tJP0000-NPJH90001_00-0000000000000000\t\t\t\t4".reader())
                store.replaceCatalog(Category.PSX, "header\nID\tEU\tPS1 fixture\thttp://127.0.0.1/unused\tEP0000-NPEF90001_00-0000000000000000\t\t\t4".reader())
                store.replaceCatalog(Category.PS3_GAMES, "Title ID\tRegion\tName\tURL\tRAP\tContent ID\tDate\tFilename\tSize\nID\tUS\tPS3 fixture\thttp://127.0.0.1/unused\t000102030405060708090a0b0c0d0e0f\tUP0000-NPUB90001_00-0000000000000000\t\t\t4".reader())
                store.savePatchCheck(org.pkgj.android.updates.PatchCheck("NPUB90001", System.currentTimeMillis(), null, Platform.PS3))
                store.replaceCompatibility(listOf(
                    CompatibilityReport("PCSE90001", 1, "Playable", 1),
                    CompatibilityReport("PCSE90002", 2, "Ingame + / Menu", 1),
                    CompatibilityReport("PCSE90003", 3, "Intro", 1)))
                store.replaceCompatibility(listOf(CompatibilityReport("NPUB90001", 0, "Playable", 1, Platform.PS3)), Platform.PS3)
            }
        }
        override fun after() { InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity() }
    }).around(compose)

    private fun key(code: Int) = InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
    private fun awaitJob(id: Long, predicate: (DownloadJob) -> Boolean): DownloadJob {
        val repo = Repository.get(context)
        try { compose.waitUntil(20_000) { repo.store.job(id)?.let(predicate) == true } }
        catch (e: Exception) { val job = repo.store.job(id); throw AssertionError("Job $id: ${job?.state}, ${job?.bytes} bytes, ${job?.error}", e) }
        return repo.store.job(id)!!
    }
    private fun syntheticLicense(): Pair<String, ByteArray> {
        val bytes = ByteArray(512)
        content.toByteArray().copyInto(bytes, 0x10)
        val compressor = Deflater().apply { setInput(bytes); finish() }
        val packed = ByteArray(1024)
        val count = compressor.deflate(packed); compressor.end()
        return Base64.getEncoder().encodeToString(packed.copyOf(count)) to bytes
    }
    private fun queue(server: MockWebServer): DownloadJob {
        val repo = Repository.get(context)
        repo.setExportFolder(tree)
        val item = CatalogItem(Category.GAMES, "PCSE90001", content, "EU", "Download fixture",
            server.url("/fixture.pkg").toString(), 4, syntheticLicense().first)
        repo.queue(item)
        return repo.store.jobs().first()
    }

    @Test fun compactLandscapeFitsCatalogQueueAndSettings() {
        val repo = Repository.get(context)
        val rows = "header\n" + (0..19).joinToString("\n") { index ->
            "ID\tEU\tCompact ${index.toString().padStart(2, '0')}\thttp://127.0.0.1/unused\t\tEP0000-PCSE${93000 + index}_00-0000000000000000\t\t\t4"
        }
        repo.store.replaceCatalog(Category.GAMES, rows.reader())
        val ids = (0..5).map { index ->
            val item = CatalogItem(Category.GAMES, "PCSE${94000 + index}", "compact-$index", "EU", "Queue $index", "http://127.0.0.1/unused", 100)
            repo.store.addJob(item, "compact-$index.pkg", tree.toString()).also { repo.store.update(it, state = JobState.PAUSED, bytes = 25, total = 100) }
        }
        repo.refreshJobs()
        try {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(5_000) {
                val view = compose.activity.window.decorView
                context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && view.width > view.height
            }
            compose.waitForIdle()
            compose.onNodeWithText("Compact 00").assertIsDisplayed()
            compose.onNodeWithText("Compact 04").assertIsDisplayed()
            compose.onNodeWithText("PKGj Android").assertIsDisplayed()
            key(KeyEvent.KEYCODE_BUTTON_R1)
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Queue 5").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Queue 2").assertIsDisplayed()
            key(KeyEvent.KEYCODE_BUTTON_R1)
            Platform.entries.forEach { compose.onNodeWithContentDescription("Choose ${it.label} folder").assertIsDisplayed() }
            settingsSection("Catalogs")
            compose.onNodeWithText("URL").assertIsDisplayed()
            settingsSection("About")
            compose.onNodeWithText("BSD 2-Clause").assertIsDisplayed()
        } finally { ids.forEach { repo.store.removeJob(it) }; repo.refreshJobs() }
    }

    @Test fun pagingDoesNotReadDatabaseOnUiThread() {
        val source = Repository.get(context).store.paging(CatalogFilter(category = Category.GAMES.name, regions = setOf("EU")))
        val violations = java.util.concurrent.atomic.AtomicInteger()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val previous = android.os.StrictMode.getThreadPolicy()
            android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder().detectDiskReads()
                .penaltyListener({ it.run() }) { violations.incrementAndGet() }.build())
            try {
                val page = kotlinx.coroutines.runBlocking {
                    source.load(androidx.paging.PagingSource.LoadParams.Refresh<Int>(null, 50, false))
                } as androidx.paging.PagingSource.LoadResult.Page
                assertEquals(listOf("Alpha"), page.data.map { it.title })
            } finally { android.os.StrictMode.setThreadPolicy(previous) }
        }
        assertEquals("Paging must not block the UI with SQLite reads", 0, violations.get())
    }

    @Test fun updateClientRejectsUntrustedTlsEvenWithSonyCaName() {
        val fakeCa = okhttp3.tls.HeldCertificate.Builder().certificateAuthority(0).commonName("SCEI DNAS Root 05").build()
        val leaf = okhttp3.tls.HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").signedBy(fakeCa).build()
        val certificates = okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(leaf, fakeCa.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(certificates.sslSocketFactory(), false)
            server.enqueue(MockResponse().setBody("<titlepatch/>"))
            val source = org.pkgj.android.updates.PatchSource { server.url("/manifest").toString() }
            assertThrows(javax.net.ssl.SSLHandshakeException::class.java) { source.fetch("PCSE90001") }
        }
    }

    @Test fun onDemandChecksCacheNoUpdateAndRefreshWithoutReplacingGoodCacheOnError() {
        val repo = Repository.get(context)
        repo.store.writableDatabase.delete("update_checks", "titleid=?", arrayOf("PCSE90001"))
        MockWebServer().use { server ->
            val source = org.pkgj.android.updates.PatchSource { server.url("/$it.xml").toString() }
            kotlinx.coroutines.runBlocking {
                server.enqueue(MockResponse().setResponseCode(404))
                val missing = repo.checkPatch("PCSE90001", source = source)
                assertNull(missing.patch); assertTrue(missing.checkedAt > 0)
                assertEquals(missing, repo.checkPatch("PCSE90001", source = source)); assertEquals(1, server.requestCount)
                server.enqueue(MockResponse().setBody("<titlepatch titleid=\"PCSE90001\"><tag><package version=\"01.02\" url=\"${server.url("/patch")}\"/></tag></titlepatch>"))
                val found = repo.checkPatch("PCSE90001", refresh = true, source = source)
                assertEquals("01.02", found.patch!!.version); assertEquals(2, server.requestCount)
                server.enqueue(MockResponse().setResponseCode(503))
                try { repo.checkPatch("PCSE90001", refresh = true, source = source); fail("HTTP errors must not mean no update") }
                catch (_: java.io.IOException) { }
                assertEquals(found, repo.cachedPatch("PCSE90001")); assertEquals(3, server.requestCount)
            }
        }
    }

    @Test fun updateCacheAndVersionedJobsSurviveDatabaseReopen() {
        val name = "updates-migration-fixture.db"
        val dbFile = context.getDatabasePath(name)
        context.deleteDatabase(name)
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("CREATE TABLE jobs(id INTEGER PRIMARY KEY AUTOINCREMENT,category TEXT NOT NULL,content TEXT NOT NULL,title TEXT NOT NULL,url TEXT NOT NULL,filename TEXT NOT NULL,state TEXT NOT NULL,bytes INTEGER NOT NULL DEFAULT 0,total INTEGER,etag TEXT,error TEXT,speed INTEGER NOT NULL DEFAULT 0,exported INTEGER NOT NULL DEFAULT 0,zrif TEXT NOT NULL DEFAULT '',license_exported INTEGER NOT NULL DEFAULT 0,folder_uri TEXT,package_uri TEXT,license_uri TEXT,transfer_complete INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("INSERT INTO jobs(category,content,title,url,filename,state,bytes,total,zrif,folder_uri,package_uri,transfer_complete) VALUES('GAMES','UP0000-PCSE90001_00-0000000000000000','Existing','https://example.invalid/base','base.pkg','PAUSED',123,999,'existing-rif','content://folder','content://partial',0)")
            Store(context).use { it.onUpgrade(db, 6, 8) }
            db.rawQuery("SELECT id,title,state,bytes,total,zrif,folder_uri,package_uri,type,titleid,patch_version FROM jobs", null).use {
                assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0)); assertEquals("Existing", it.getString(1))
                assertEquals("PAUSED", it.getString(2)); assertEquals(123L, it.getLong(3)); assertEquals(999L, it.getLong(4))
                assertEquals("existing-rif", it.getString(5)); assertEquals("content://folder", it.getString(6))
                assertEquals("content://partial", it.getString(7)); assertEquals("CATALOG", it.getString(8))
                assertEquals("PCSE90001", it.getString(9)); assertEquals("", it.getString(10))
            }
        }
        context.deleteDatabase(name)
        val game = CatalogItem(Category.GAMES, "PCSE90001", content, "EU", "Version fixture", "https://example.invalid/base", 4)
        val patch = org.pkgj.android.updates.GamePatch(game.titleId, "01.02", "https://example.invalid/update", 9, "3.60")
        val ids = mutableListOf<Long>()
        Store(context).use { store ->
            store.savePatchCheck(org.pkgj.android.updates.PatchCheck(game.titleId, 12345, patch))
            store.savePatchCheck(org.pkgj.android.updates.PatchCheck("PCSB90001", 54321, null))
            val item = patch.downloadItem(game)
            val first = store.addJob(item, DownloadNames.baseName(item) + ".pkg", tree.toString()); ids += first
            assertEquals(first, store.addJob(item, "duplicate.pkg", tree.toString()))
            val newer = patch.copy(version = "01.03").downloadItem(game)
            val second = store.addJob(newer, DownloadNames.baseName(newer) + ".pkg", tree.toString()); ids += second
            assertNotEquals(first, second)
        }
        Store(context).use { store ->
            assertEquals(12345L, store.patchCheck(game.titleId)!!.checkedAt)
            assertEquals(patch, store.patchCheck(game.titleId)!!.patch)
            assertNull(store.patchCheck("PCSB90001")!!.patch)
            ids.forEach { assertEquals(DownloadType.UPDATE, store.job(it)!!.downloadType); store.removeJob(it) }
        }
    }

    @Test fun downloadingGameAlsoSavesItsUpdateInTheOriginalFolder() {
        val repo = Repository.get(context)
        repo.setExportFolder(tree)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(if (request.path == "/base.pkg") "base-fixture" else "patch-fixture")
            }
            val patch = org.pkgj.android.updates.GamePatch("PCSE90001", "01.07", server.url("/patch.pkg").toString(), 13, "3.60")
            repo.store.savePatchCheck(org.pkgj.android.updates.PatchCheck(patch.titleId, System.currentTimeMillis(), patch))
            val game = CatalogItem(Category.GAMES, patch.titleId, content, "EU", "Automatic fixture", server.url("/base.pkg").toString(), 12, syntheticLicense().first)
            repo.queue(game)
            repo.clearExportFolder() // The worker must retain the game's chosen destination.
            compose.waitUntil(5_000) { repo.store.jobs().any { it.contentId == content && it.title == game.title } }
            val baseId = repo.store.jobs().first { it.contentId == content && it.title == game.title }.id
            val base = awaitJob(baseId) { it.state == JobState.DOWNLOADED }
            compose.waitUntil(5_000) { repo.store.patchJob(game.titleId, patch.version) != null }
            val update = awaitJob(repo.store.patchJob(game.titleId, patch.version)!!.id) { it.state == JobState.DOWNLOADED }
            assertEquals("DONE", base.updateCheck); assertEquals("EU", base.region)
            assertEquals(base.folderUri, update.folderUri); assertNull(update.licenseUri)
            assertEquals("[EU-PCSE90001] - Automatic fixture - Update v01.07.pkg", update.filename)
            assertArrayEquals("base-fixture".toByteArray(), context.contentResolver.openInputStream(Uri.parse(base.packageUri))!!.use { it.readBytes() })
            assertArrayEquals("patch-fixture".toByteArray(), context.contentResolver.openInputStream(Uri.parse(update.packageUri))!!.use { it.readBytes() })
            assertEquals("/base.pkg", server.takeRequest().path); assertEquals("/patch.pkg", server.takeRequest().path)
            repo.deleteCompleted(base); repo.deleteCompleted(update)
        }
    }

    @Test fun failedAutomaticCheckDoesNotBlockGameAndCanBeRetriedSeparately() {
        val repo = Repository.get(context)
        repo.setExportFolder(tree)
        repo.store.writableDatabase.delete("update_checks", "titleid=?", arrayOf("PCSE90001"))
        MockWebServer().use { server ->
            val game = CatalogItem(Category.GAMES, "PCSE90001", content, "EU", "Check retry fixture", server.url("/base.pkg").toString(), 4, syntheticLicense().first)
            val id = repo.store.addJob(game, "check-retry.pkg", tree.toString(), checkForUpdate = true)
            server.enqueue(MockResponse().setResponseCode(503))
            kotlinx.coroutines.runBlocking { repo.queueGameUpdate(repo.store.job(id)!!, org.pkgj.android.updates.PatchSource { server.url("/manifest").toString() }) }
            assertEquals("FAILED", repo.store.job(id)!!.updateCheck)
            assertEquals(JobState.QUEUED, repo.store.job(id)!!.state)
            server.enqueue(MockResponse().setBody("base"))
            repo.schedule()
            val base = awaitJob(id) { it.state == JobState.DOWNLOADED }
            assertEquals("FAILED", base.updateCheck); assertEquals("Update check HTTP 503", base.updateError)
            // Retry only discovery from a persisted failed check, without redownloading the game.
            repo.store.savePatchCheck(org.pkgj.android.updates.PatchCheck(game.titleId, System.currentTimeMillis(), null))
            repo.retryGameUpdate(base)
            compose.waitUntil(5_000) { repo.store.job(id)?.updateCheck == "DONE" }
            assertEquals("", repo.store.job(id)!!.updateError)
            assertEquals(2, server.requestCount)
            repo.deleteCompleted(repo.store.job(id)!!)
        }
    }

    @Test fun automaticDiscoverySurvivesReopenAndDoesNotResumePausedUpdate() {
        val repo = Repository.get(context)
        val game = CatalogItem(Category.GAMES, "PCSE90001", content, "EU", "Persisted auto fixture", "https://example.invalid/base", 4)
        val patch = org.pkgj.android.updates.GamePatch(game.titleId, "01.08", "https://example.invalid/patch", 4, null)
        val updateItem = patch.downloadItem(game)
        val updateId = repo.store.addJob(updateItem, "paused-patch.pkg", tree.toString())
        repo.store.update(updateId, state = JobState.PAUSED)
        val baseId = repo.store.addJob(game, "pending-base.pkg", tree.toString(), checkForUpdate = true)
        Store(context).use { reopened ->
            val pending = reopened.job(baseId)!!
            assertEquals("PENDING", pending.updateCheck); assertEquals("EU", pending.region)
        }
        repo.store.savePatchCheck(org.pkgj.android.updates.PatchCheck(game.titleId, System.currentTimeMillis(), patch))
        kotlinx.coroutines.runBlocking { repo.queueGameUpdate(repo.store.job(baseId)!!) }
        assertEquals("DONE", repo.store.job(baseId)!!.updateCheck)
        assertEquals(JobState.PAUSED, repo.store.job(updateId)!!.state)
        assertEquals(1, repo.store.jobs().count { it.downloadType == DownloadType.UPDATE && it.patchVersion == patch.version })
        repo.store.removeJob(baseId); repo.store.removeJob(updateId)
    }

    @Test fun databaseVersion7MigrationPreservesJobsWithoutAutomaticOptIn() {
        val file = context.getDatabasePath("auto-update-migration.db")
        context.deleteDatabase(file.name)
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE jobs(id INTEGER PRIMARY KEY,state TEXT,error TEXT)")
            db.execSQL("INSERT INTO jobs VALUES(99,'DOWNLOADED','Existing warning')")
            Store(context).use { it.onUpgrade(db, 7, 8) }
            db.rawQuery("SELECT id,state,error,update_check,update_error,region FROM jobs", null).use {
                assertTrue(it.moveToFirst()); assertEquals(99L, it.getLong(0)); assertEquals("DOWNLOADED", it.getString(1))
                assertEquals("Existing warning", it.getString(2)); assertEquals("", it.getString(3))
                assertEquals("", it.getString(4)); assertEquals("", it.getString(5))
            }
        }
        context.deleteDatabase(file.name)
    }

    @Test fun schema9MigrationPreservesVitaCacheAndDownloadsAndSeparatesPs3() {
        val name = "compatibility-migration.db"
        context.deleteDatabase(name)
        // Start with the real current job/catalog layout, replacing only the v9 table.
        Store(context, name).use { store ->
            val item = CatalogItem(Category.GAMES, "PCSE90001", content, "EU", "Migration fixture", "https://example.invalid/base", 100)
            val id = store.addJob(item, "fixture.pkg", tree.toString())
            store.update(id, state = JobState.PAUSED, bytes = 37, total = 100)
            val db = store.writableDatabase
            db.execSQL("DROP TABLE compatibility")
            db.execSQL("CREATE TABLE compatibility(titleid TEXT PRIMARY KEY COLLATE NOCASE,issue_id INTEGER NOT NULL,status TEXT NOT NULL,updated_at INTEGER NOT NULL)")
            db.execSQL("INSERT INTO compatibility VALUES('PCSE90001',123,'Playable',456)")
            db.version = 9
        }
        Store(context, name).use { store ->
            val vita = CompatibilityKey(Platform.VITA, "PCSE90001")
            assertEquals(123L, store.compatibilityReports().getValue(vita).issueId)
            assertEquals(456L, store.compatibilityReports().getValue(vita).updatedAt)
            val job = store.jobs().single()
            assertEquals(JobState.PAUSED, job.state); assertEquals(37L, job.bytes)
            assertEquals(tree.toString(), job.folderUri)
            // Deliberately reuse an ID to prove neither source can overwrite the other.
            store.replaceCompatibility(listOf(CompatibilityReport("PCSE90001", 0, "Ingame", 1, Platform.PS3)), Platform.PS3)
            assertEquals(2, store.compatibilityReports().size)
            assertEquals("Playable", store.compatibilityReports().getValue(vita).status)
            store.replaceCompatibility(emptyList(), Platform.PS3)
            assertEquals(setOf(vita), store.compatibilityReports().keys)
        }
        context.deleteDatabase(name)
    }

    @Test fun ps3StatusFiltersAndDetailsWorkWithController() {
        choosePlatform("PS3")
        compose.onAllNodesWithText("Playable").onLast().assertIsDisplayed()
        compose.onNodeWithContentDescription("Compatibility").performSemanticsAction(SemanticsActions.RequestFocus)
        key(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithText("Ingame").assertExists()
        compose.onNodeWithText("Loadable").assertExists()
        compose.onAllNodesWithText("Bootable").assertCountEquals(0)
        compose.onAllNodesWithText("Playable").onLast().performSemanticsAction(SemanticsActions.RequestFocus)
        key(KeyEvent.KEYCODE_BUTTON_A)
        key(KeyEvent.KEYCODE_BUTTON_B)
        compose.onNodeWithText("PS3 fixture").assertIsDisplayed()
        compose.onNodeWithText("PS3 fixture").performSemanticsAction(SemanticsActions.RequestFocus)
        key(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithText("Compatibility report").assertExists()
        compose.onAllNodesWithText("Playable").onLast().assertIsDisplayed()
        key(KeyEvent.KEYCODE_BUTTON_B)
        compose.onNodeWithText("PS3 fixture").assertIsFocused()
        Store(context).use { store ->
            assertEquals(1, store.count(CatalogFilter(platform = Platform.PS3, compatibilityStatuses = setOf("Playable"))))
            assertEquals(0, store.count(CatalogFilter(platform = Platform.PS3, compatibilityStatuses = setOf("Ingame"))))
            assertEquals(0, store.count(CatalogFilter(platform = Platform.PS3, compatibilityStatuses = setOf("Unreported"))))
            assertEquals(1, store.count(CatalogFilter(compatibilityStatuses = setOf("Unreported"))))
        }
    }

    @Test fun sharedFolderPreferencesMigrateOnceWithoutOverwritingPlatformChoices() {
        val prefs = context.getSharedPreferences("folder-migration-fixture", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("exportFolder", "content://legacy/tree/root")
            .putString("downloadFolder_PS3", "content://custom/tree/ps3").commit()
        val folders = org.pkgj.android.data.DownloadFolders(prefs)
        assertEquals("content://custom/tree/ps3", folders.read()[Platform.PS3])
        for (platform in listOf(Platform.VITA, Platform.PSP, Platform.PSX))
            assertEquals("content://legacy/tree/root", folders.read()[platform])
        assertFalse(prefs.contains("exportFolder"))
        folders.set(Platform.PSP, null)
        assertNull(org.pkgj.android.data.DownloadFolders(prefs).read()[Platform.PSP])
        assertEquals("content://legacy/tree/root", folders.read()[Platform.VITA])
        prefs.edit().clear().commit()
        assertTrue(org.pkgj.android.data.DownloadFolders(prefs).read().isEmpty())
        prefs.edit().clear().commit()
    }

    @Test fun platformDownloadsUseSeparateFoldersAndOldJobsKeepTheirDestination() {
        val repo = Repository.get(context)
        val previous = repo.downloadFolders.value
        val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, tree)!!
        val directories = listOf("vita", "psp", "ps1", "ps3")
        directories.forEach { root.createDirectory(it)!! }
        val chosen = Platform.entries.zip(directories).associate { (platform, dir) ->
            platform to DocumentsContract.buildTreeDocumentUri(tree.authority!!, dir)
        }
        val ids = mutableListOf<Long>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setBody("fixture") }
            val categories = listOf(Category.GAMES, Category.PSP, Category.PSX, Category.PS3_GAMES)
            val titleIds = listOf("PCSE90001", "NPJH90001", "NPEF90001", "NPUB90001")
            val items = categories.mapIndexed { index, category ->
                CatalogItem(category, titleIds[index], "UP0000-${titleIds[index]}_00-0000000000000000", "US",
                    "Folder ${category.platform.name}", server.url("/${category.platform.name}").toString(), 7)
            }
            // A persisted paused job retains its old root even after Vita's preference changes.
            val retained = repo.store.addJob(items.first().copy(title = "Retained destination"), "retained.pkg", tree.toString())
            repo.store.update(retained, state = JobState.PAUSED)
            ids += retained
            try {
                chosen.forEach { (platform, uri) -> repo.setExportFolder(uri, platform) }
                repo.queue(items)
                ids += items.map { item -> repo.store.jobs().first { it.title == item.title }.id }
                repo.action(repo.store.job(retained)!!, JobState.QUEUED)
                ids.forEach { id ->
                    val job = awaitJob(id) { it.state == JobState.DOWNLOADED }
                    val expected = if (id == retained) tree else chosen.getValue(job.category.platform)
                    assertEquals(expected.toString(), job.folderUri)
                    assertEquals(DocumentsContract.getTreeDocumentId(expected), DocumentsContract.getTreeDocumentId(Uri.parse(job.packageUri)))
                    assertArrayEquals("fixture".toByteArray(), context.contentResolver.openInputStream(Uri.parse(job.packageUri))!!.use { it.readBytes() })
                }
                repo.clearExportFolder(Platform.PSP)
                assertNull(repo.downloadFolder(Platform.PSP))
                assertEquals(chosen[Platform.PS3].toString(), repo.downloadFolder(Platform.PS3))
                assertEquals(chosen[Platform.VITA].toString(), repo.downloadFolder(Platform.VITA))
            } finally {
                ids.forEach { id -> repo.store.job(id)?.let { repo.discardDownloadFiles(it) }; repo.store.removeJob(id) }
                Platform.entries.forEach { platform -> previous[platform]?.let { repo.setExportFolder(Uri.parse(it), platform) } ?: repo.clearExportFolder(platform) }
                repo.refreshJobs()
            }
        }
    }

    @Test fun historyClearPreservesRetryableJobsAndPendingDiscovery() {
        val name = "history-fixture.db"
        context.deleteDatabase(name)
        Store(context, name).use { store ->
            val retained = mutableSetOf<Long>()
            var counter = 0
            fun add(state: JobState, bytes: Long = 0, updateCheck: String = "", error: String = "", keep: Boolean) {
                val item = CatalogItem(Category.GAMES, "PCSE90001", "history-${counter++}", "JP", "History", "https://example.invalid/pkg", 100)
                val id = store.addJob(item, "history-$counter.pkg", tree.toString())
                store.update(id, state = state, bytes = bytes, error = error)
                if (updateCheck.isNotEmpty()) store.setUpdateCheck(id, updateCheck)
                assertEquals(!keep, store.job(id)!!.isHistory)
                if (keep) retained += id
            }
            add(JobState.DOWNLOADED, bytes = 100, keep = false)
            add(JobState.CANCELED, keep = false)
            add(JobState.QUEUED, keep = true)
            add(JobState.RUNNING, bytes = 33, keep = true)
            add(JobState.PAUSED, bytes = 44, keep = true)
            add(JobState.FAILED, bytes = 55, keep = true)
            add(JobState.DOWNLOADED, updateCheck = "PENDING", keep = true)
            add(JobState.DOWNLOADED, updateCheck = "FAILED", keep = true)
            add(JobState.CANCELED, bytes = 10, keep = true)
            add(JobState.CANCELED, error = "Could not remove partial file", keep = true)
            val privateItem = CatalogItem(Category.PSP, "NPJH90001", "private-history", "JP", "Private file", "https://example.invalid/pkg", 100)
            val privateId = store.addJob(privateItem, "private-history.pkg")
            store.update(privateId, state = JobState.DOWNLOADED, bytes = 100)
            assertFalse(store.job(privateId)!!.isHistory)
            retained += privateId
            assertEquals(2, store.clearDownloadHistory())
            assertEquals(retained, store.jobs().map { it.id }.toSet())
            assertEquals(44L, store.jobs().single { it.state == JobState.PAUSED }.bytes)
            assertEquals(0, store.clearDownloadHistory())
        }
        context.deleteDatabase(name)
    }

    @Test fun controllerCanClearHistoryWithoutRemovingFilesOrPausedJobs() {
        val repo = Repository.get(context)
        val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, tree)!!
        val pkg = root.createFile("application/octet-stream", "history-preserved.pkg")!!
        context.contentResolver.openOutputStream(pkg.uri)!!.use { it.write("fixture".toByteArray()) }
        val item = CatalogItem(Category.PSP, "NPJH90001", "history-complete", "JP", "Saved history fixture", "http://127.0.0.1/unused", 7)
        val completed = repo.store.addJob(item, "history-preserved.pkg", tree.toString())
        repo.store.update(completed, state = JobState.DOWNLOADED, bytes = 7, packageUri = pkg.uri.toString(), transferComplete = true)
        val paused = repo.store.addJob(item.copy(contentId = "history-paused", title = "Paused history fixture"), "paused-history.pkg", tree.toString())
        repo.store.update(paused, state = JobState.PAUSED, bytes = 3, total = 7)
        repo.refreshJobs()
        try {
            key(KeyEvent.KEYCODE_BUTTON_R1)
            compose.onNodeWithText("Clear history").performSemanticsAction(SemanticsActions.RequestFocus)
            key(KeyEvent.KEYCODE_BUTTON_A)
            compose.onNodeWithText("Clear history?").assertExists()
            compose.onAllNodesWithText("Cancel").onLast().assertIsFocused()
            key(KeyEvent.KEYCODE_BUTTON_B)
            compose.onNodeWithText("Clear history").assertIsFocused()
            assertNotNull(repo.store.job(completed))
            key(KeyEvent.KEYCODE_BUTTON_A)
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            if (compose.onAllNodes(hasText("Clear") and isFocused()).fetchSemanticsNodes().isEmpty()) key(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNodeWithText("Clear").assertIsFocused()
            key(KeyEvent.KEYCODE_BUTTON_A)
            compose.waitUntil(5_000) { repo.store.job(completed) == null }
            assertEquals(JobState.PAUSED, repo.store.job(paused)!!.state)
            assertEquals(3L, repo.store.job(paused)!!.bytes)
            assertArrayEquals("fixture".toByteArray(), context.contentResolver.openInputStream(pkg.uri)!!.use { it.readBytes() })
            compose.onNodeWithText("Downloads").assertIsFocused()
        } finally { repo.store.removeJob(completed); repo.store.removeJob(paused); pkg.delete(); repo.refreshJobs() }
    }

    private fun settingsSection(name: String) {
        compose.onNodeWithText(name).performSemanticsAction(SemanticsActions.RequestFocus)
        key(KeyEvent.KEYCODE_BUTTON_A)
    }

    private fun choosePlatform(label: String) {
        compose.onNodeWithContentDescription("Platform").performSemanticsAction(SemanticsActions.RequestFocus)
        key(KeyEvent.KEYCODE_BUTTON_A)
        repeat(Platform.entries.size + 1) {
            val focused = compose.onNodeWithText(label).fetchSemanticsNode().config
                .getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Focused) { false }
            if (!focused) key(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        compose.onNodeWithText(label).assertIsFocused()
        key(KeyEvent.KEYCODE_BUTTON_A)
    }

    @Test fun controllerCanSwitchPlatformsWithoutCompatibilityLeaking() {
        choosePlatform("PSP")
        compose.onNodeWithText("PSP fixture").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Compatibility").assertCountEquals(0)
        compose.onNodeWithText("PSP fixture").performSemanticsAction(SemanticsActions.RequestFocus)
        key(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithText("PKG").assertExists()
        compose.onAllNodesWithText("Unreported").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Refresh update").assertCountEquals(0)
        key(KeyEvent.KEYCODE_BUTTON_B)
        choosePlatform("PS1")
        compose.onNodeWithText("PS1 fixture").assertIsDisplayed()
        choosePlatform("PS3")
        compose.onNodeWithText("PS3 fixture").assertIsDisplayed()
        compose.onNodeWithContentDescription("Compatibility").assertExists()
        compose.onAllNodesWithText("Playable").onLast().assertIsDisplayed()
        compose.onNodeWithText("PS3 fixture").performSemanticsAction(SemanticsActions.RequestFocus)
        key(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithText("RAP available").assertExists()
        compose.onAllNodesWithText("Playable").onLast().assertIsDisplayed()
        compose.onNodeWithText("None available").assertExists()
        compose.onAllNodesWithText("Unreported").assertCountEquals(0)
        key(KeyEvent.KEYCODE_BUTTON_B)
        key(KeyEvent.KEYCODE_BUTTON_R1)
        key(KeyEvent.KEYCODE_BUTTON_R1)
        settingsSection("Catalogs")
        choosePlatform("PS3")
        compose.onNodeWithText("URL").assertIsDisplayed()
        compose.onNodeWithText("https://nopaystation.com/tsv/PS3_GAMES.tsv").assertExists()
    }

    @Test fun pspAndPs1UseThePersistentFolderQueueWithoutLicenseFiles() {
        val repo = Repository.get(context)
        repo.setExportFolder(tree, Platform.PSP); repo.setExportFolder(tree, Platform.PSX)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody("synthetic-pkg")
            }
            val items = listOf(
                CatalogItem(Category.PSP, "NPJH90001", "JP0000-NPJH90001_00-0000000000000000", "JP", "PSP queue fixture", server.url("/psp").toString(), 13),
                CatalogItem(Category.PSX, "NPEF90001", "EP0000-NPEF90001_00-0000000000000000", "EU", "PS1 queue fixture", server.url("/ps1").toString(), 13))
            repo.queue(items)
            val ids = items.map { item -> repo.store.jobs().first { it.title == item.title }.id }
            val jobs = ids.map { awaitJob(it) { job -> job.state == JobState.DOWNLOADED } }
            jobs.forEach { job ->
                assertFalse(job.needsLicense); assertEquals("", job.updateCheck); assertNull(job.licenseUri); assertEquals("", job.error)
                assertArrayEquals("synthetic-pkg".toByteArray(), context.contentResolver.openInputStream(Uri.parse(job.packageUri))!!.use { it.readBytes() })
                repo.discardDownloadFiles(job); repo.store.removeJob(job.id)
            }
            assertEquals(listOf("/psp", "/ps1"), (0..1).map { server.takeRequest().path })
            repo.refreshJobs()
        }
    }

    @Test fun ps3GameDownloadsRapAndAllUpdatesInManifestOrder() {
        val repo = Repository.get(context)
        repo.setExportFolder(tree, Platform.PS3)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(request.path.orEmpty())
            }
            val id = "NPUB90001"
            val game = CatalogItem(Category.PS3_GAMES, id, "UP0000-${id}_00-0000000000000000", "US", "PS3 auto fixture", server.url("/base").toString(), 5,
                rap = "000102030405060708090a0b0c0d0e0f")
            val packages = listOf(
                org.pkgj.android.updates.GamePatch(id, "01.01", server.url("/first").toString(), 6, "03.5500", Platform.PS3),
                org.pkgj.android.updates.GamePatch(id, "01.02", server.url("/second").toString(), 7, "04.2000", Platform.PS3))
            repo.store.savePatchCheck(org.pkgj.android.updates.PatchCheck(id, System.currentTimeMillis(), packages.last(), Platform.PS3, packages))
            assertEquals(packages, repo.store.patchCheck(id, Platform.PS3)!!.packages)
            repo.queue(game)
            val baseId = repo.store.jobs().first { it.title == game.title && it.downloadType == DownloadType.CATALOG }.id
            val base = awaitJob(baseId) { it.state == JobState.DOWNLOADED }
            val patches = packages.map { patch ->
                compose.waitUntil(5_000) { repo.store.patchJob(id, patch.version, Category.PS3_GAMES) != null }
                awaitJob(repo.store.patchJob(id, patch.version, Category.PS3_GAMES)!!.id) { it.state == JobState.DOWNLOADED }
            }
            assertEquals("DONE", base.updateCheck); assertEquals("", base.error)
            assertTrue(base.licenseExported); assertEquals("${game.contentId}.rap", repo.licenseFile(base).name)
            assertArrayEquals(ByteArray(16) { it.toByte() }, context.contentResolver.openInputStream(Uri.parse(base.licenseUri))!!.use { it.readBytes() })
            patches.forEach { assertFalse(it.needsLicense); assertNull(it.licenseUri); assertEquals("", it.rap); assertEquals("", it.error) }
            assertEquals(listOf("/base", "/first", "/second"), (0..2).map { server.takeRequest().path })
            assertFalse(File(repo.downloadsDir, base.filename).exists())
            assertEquals(2, repo.store.patchCheck(id, Platform.PS3)!!.packages.size)
            assertNull(repo.store.patchCheck(id, Platform.VITA))
            (patches + base).forEach { repo.discardDownloadFiles(it); repo.store.removeJob(it.id) }
            repo.refreshJobs()
        }
    }

    @Test fun schema8MigrationPreservesJobsAndVitaCacheAndAllowsPs3Parts() {
        val name = "platform-migration.db"
        context.deleteDatabase(name)
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            db.execSQL("CREATE TABLE catalog(category TEXT NOT NULL,content TEXT NOT NULL,titleid TEXT NOT NULL,region TEXT NOT NULL,title TEXT NOT NULL,url TEXT NOT NULL,size INTEGER,zrif TEXT NOT NULL DEFAULT '',version TEXT NOT NULL DEFAULT '',PRIMARY KEY(category,content))")
            db.execSQL("CREATE TABLE jobs(id INTEGER PRIMARY KEY AUTOINCREMENT,category TEXT NOT NULL,content TEXT NOT NULL,title TEXT NOT NULL,url TEXT NOT NULL,filename TEXT NOT NULL,state TEXT NOT NULL,bytes INTEGER NOT NULL DEFAULT 0,total INTEGER,etag TEXT,error TEXT,speed INTEGER NOT NULL DEFAULT 0,exported INTEGER NOT NULL DEFAULT 0,zrif TEXT NOT NULL DEFAULT '',license_exported INTEGER NOT NULL DEFAULT 0,folder_uri TEXT,package_uri TEXT,license_uri TEXT,transfer_complete INTEGER NOT NULL DEFAULT 0,type TEXT NOT NULL DEFAULT 'CATALOG',titleid TEXT NOT NULL DEFAULT '',patch_version TEXT NOT NULL DEFAULT '',update_check TEXT NOT NULL DEFAULT '',update_error TEXT NOT NULL DEFAULT '',region TEXT NOT NULL DEFAULT '')")
            db.execSQL("CREATE UNIQUE INDEX jobs_update_identity ON jobs(titleid,patch_version) WHERE type='UPDATE'")
            db.execSQL("CREATE TABLE update_checks(titleid TEXT PRIMARY KEY,checked_at INTEGER NOT NULL,version TEXT,url TEXT,size INTEGER,firmware TEXT)")
            db.execSQL("INSERT INTO jobs(category,content,title,url,filename,state,bytes,type,titleid,patch_version) VALUES('GAMES','update:PCSE90001:01.02','Legacy patch','https://example.invalid/patch','legacy.pkg','PAUSED',123,'UPDATE','PCSE90001','01.02')")
            db.execSQL("INSERT INTO update_checks VALUES('PCSE90001',12345,'01.02','https://example.invalid/patch',123,'3.60')")
            db.execSQL("CREATE TABLE compatibility(titleid TEXT PRIMARY KEY COLLATE NOCASE,issue_id INTEGER NOT NULL,status TEXT NOT NULL,updated_at INTEGER NOT NULL)")
            db.version = 8
        }
        Store(context, name).use { store ->
            val old = store.jobs().single()
            assertEquals(JobState.PAUSED, old.state); assertEquals(123L, old.bytes); assertEquals("", old.rap)
            assertEquals("update:PCSE90001:01.02", old.identity)
            val cached = store.patchCheck("PCSE90001")!!
            assertEquals(12345L, cached.checkedAt); assertEquals("01.02", cached.packages.single().version)
            assertNull(store.patchCheck("PCSE90001", Platform.PS3))
            val game = CatalogItem(Category.PS3_GAMES, "PCSE90001", "UP0000-PCSE90001_00-0000000000000000", "US", "PS3 fixture", "https://example.invalid/base", 4)
            val ids = (1..2).map { part ->
                val patch = org.pkgj.android.updates.GamePatch(game.titleId, "01.02", "https://example.invalid/$part", 4, null, Platform.PS3, part).downloadItem(game)
                store.addJob(patch, "ps3-$part.pkg", tree.toString())
            }
            assertNotEquals(ids[0], ids[1]); assertEquals(3, store.jobs().size)
            assertEquals(1, store.patchJob(game.titleId, "01.02", Category.PS3_GAMES, 1)!!.patchPart)
        }
        context.deleteDatabase(name)
    }

    @Test fun controllerCanDownloadCachedPatchWithoutRifOrBaseCollision() {
        val repo = Repository.get(context)
        repo.setExportFolder(tree)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("patch-fixture"))
            val patch = org.pkgj.android.updates.GamePatch("PCSE90001", "01.02", server.url("/patch.pkg").toString(), 13, "3.65")
            repo.store.savePatchCheck(org.pkgj.android.updates.PatchCheck(patch.titleId, System.currentTimeMillis(), patch))
            // Also exercises the Android XML parser and its external-entity protection.
            val xml = "<titlepatch titleid=\"PCSE90001\"><tag><package version=\"01.02\" url=\"https://example.invalid/patch\"/></tag></titlepatch>"
            assertEquals("01.02", org.pkgj.android.updates.PatchXml.parse(xml.byteInputStream(), patch.titleId)!!.version)
            assertThrows(org.pkgj.android.updates.PatchManifestException::class.java) {
                org.pkgj.android.updates.PatchXml.parse(("<!DOCTYPE titlepatch [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>" + xml).byteInputStream(), patch.titleId)
            }
            compose.onNodeWithText("Alpha").performSemanticsAction(SemanticsActions.RequestFocus)
            key(KeyEvent.KEYCODE_BUTTON_A)
            compose.onNodeWithText("Update v01.02").assertExists()
            compose.onNodeWithText("Firmware 3.65", substring = true).assertExists()
            key(KeyEvent.KEYCODE_DPAD_UP)
            if (compose.onAllNodesWithContentDescription("Refresh update").fetchSemanticsNodes().any { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Focused) { false } })
                key(KeyEvent.KEYCODE_DPAD_LEFT)
            compose.onNodeWithText("Download update").assertIsFocused()
            key(KeyEvent.KEYCODE_BUTTON_A)
            compose.waitUntil(5_000) { repo.store.patchJob(patch.titleId, patch.version) != null }
            val job = awaitJob(repo.store.patchJob(patch.titleId, patch.version)!!.id) { it.state == JobState.DOWNLOADED }
            assertEquals(DownloadType.UPDATE, job.downloadType); assertFalse(job.needsLicense)
            assertEquals("", job.zrif); assertEquals("", job.error); assertNull(job.licenseUri)
            assertFalse(job.licenseExported); assertTrue(job.filename.endsWith(" - Update v01.02.pkg"))
            assertFalse(repo.hasCatalogLicense(job)); assertNull(repo.ensureLicense(job))
            assertArrayEquals("patch-fixture".toByteArray(), context.contentResolver.openInputStream(Uri.parse(job.packageUri))!!.use { it.readBytes() })
            assertFalse(File(repo.downloadsDir, job.filename).exists())
            repo.deleteCompleted(job)
        }
    }

    @Test fun controllerCanLeaveSearchNavigateRowsAndOpenCloseDetails() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(5_000) {
            val view = compose.activity.window.decorView
            context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && view.width > view.height
        }
        compose.waitForIdle()
        compose.onNodeWithText("Alpha").assertExists()
        compose.onNodeWithText("Search games or title ID").performSemanticsAction(SemanticsActions.RequestFocus)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Games").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Alpha").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Beta").assertIsFocused()
        key(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithText("Download").assertExists()
        compose.onNodeWithText("No RIF").assertIsDisplayed()
        key(KeyEvent.KEYCODE_BUTTON_B)
        compose.onNodeWithText("Download").assertDoesNotExist()
        compose.onNodeWithText("Beta").assertIsFocused()
    }

    @Test fun multipleRegionsAndStatusesCanBeCombined() {
        compose.onNodeWithText("All regions").performClick()
        fun choice(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isPopup()))
        choice("EU").performClick(); choice("US").performClick(); choice("Done").performClick()
        compose.onNodeWithText("Any status").performSemanticsAction(SemanticsActions.OnClick) { it() }
        choice("Playable").performClick(); choice("Ingame +").performClick(); choice("Done").performClick()
        compose.onNodeWithText("2 statuses").assertExists()
        compose.onNodeWithText("Alpha").assertExists(); compose.onNodeWithText("Beta").assertExists()
        compose.onNodeWithText("Gamma").assertDoesNotExist(); compose.onNodeWithText("Omega").assertDoesNotExist()
    }

    @Test fun controllerScrollsBeyondInitiallyVisibleRows() {
        val rows = "header\n" + (0..39).joinToString("\n") { index ->
            "ID\tEU\tEntry ${index.toString().padStart(2, '0')}\thttp://127.0.0.1/unused\t\tEP0000-PCSE${(91000 + index)}_00-0000000000000000\t\t\t4"
        }
        Repository.get(context).store.replaceCatalog(Category.GAMES, rows.reader())
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Entry 00").performSemanticsAction(SemanticsActions.RequestFocus)
        repeat(25) { key(KeyEvent.KEYCODE_DPAD_DOWN); compose.waitForIdle() }
        compose.onNodeWithText("Entry 25").assertIsFocused().assertIsDisplayed()
    }

    @Test fun cancellationCannotBeOverwrittenByWorkerCompletion() {
        val store = Repository.get(context).store
        val id = store.addJob(CatalogItem(Category.GAMES, "PCSE90001", content, "EU", "State fixture", "http://127.0.0.1/unused", 4), "state-fixture.pkg")
        assertTrue(store.transition(id, JobState.RUNNING))
        assertTrue(store.transition(id, JobState.CANCELED))
        assertFalse(store.completeJob(id, "", true))
        assertFalse(store.transition(id, JobState.RUNNING))
        assertEquals(JobState.CANCELED, store.job(id)!!.state)
    }

    @Test fun databaseUpgradePreservesExistingDownloadRecords() {
        android.database.sqlite.SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE jobs(id INTEGER PRIMARY KEY,title TEXT,state TEXT,content TEXT)")
            db.execSQL("INSERT INTO jobs VALUES(1,'Existing download','DOWNLOADED','UP0000-PCSE90001_00-0000000000000000')")
            Store(context).use { it.onUpgrade(db, 5, 8) }
            db.rawQuery("SELECT title,state,folder_uri,package_uri,license_uri,transfer_complete FROM jobs", null).use {
                assertTrue(it.moveToFirst()); assertEquals("Existing download", it.getString(0))
                assertEquals("DOWNLOADED", it.getString(1)); assertTrue(it.isNull(2)); assertEquals(0, it.getInt(5))
            }
        }
    }

    @Test fun controllerCanToggleMultipleChoicesAndDismissMenu() {
        compose.onNodeWithText("Alpha").assertExists()
        compose.onNodeWithText("All regions").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("All regions").assertIsFocused()
        key(KeyEvent.KEYCODE_BUTTON_A)
        fun choice(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isPopup()))
        choice("US").performSemanticsAction(SemanticsActions.RequestFocus)
        key(KeyEvent.KEYCODE_BUTTON_A)
        choice("Done").assertExists()
        choice("EU").performSemanticsAction(SemanticsActions.RequestFocus)
        key(KeyEvent.KEYCODE_BUTTON_A)
        key(KeyEvent.KEYCODE_BUTTON_B)
        compose.onNodeWithText("US, EU").assertExists()
        compose.onNode(isPopup()).assertDoesNotExist()
    }

    @Test fun shouldersReachEverySectionEvenFromSearchAndDetails() {
        compose.onNodeWithText("Alpha").assertExists()
        compose.onNodeWithText("Search games or title ID").performSemanticsAction(SemanticsActions.RequestFocus)
        key(KeyEvent.KEYCODE_BUTTON_R1)
        compose.onNodeWithText("Downloads").assertIsSelected()
        key(KeyEvent.KEYCODE_BUTTON_R1)
        compose.onNodeWithText("Settings").assertIsSelected()
        settingsSection("Catalogs")
        compose.onNodeWithText("Games").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("Games").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("URL").assertIsFocused()
        key(KeyEvent.KEYCODE_BUTTON_L1)
        compose.onNodeWithText("Downloads").assertIsSelected()
        key(KeyEvent.KEYCODE_BUTTON_L1)
        compose.onNodeWithText("Catalog").assertIsSelected()
        compose.onNodeWithText("Beta").performSemanticsAction(SemanticsActions.RequestFocus)
        key(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithText("Download").assertExists()
        key(KeyEvent.KEYCODE_BUTTON_R1)
        compose.onNodeWithText("Downloads").assertIsSelected()
        compose.onNodeWithText("Download").assertDoesNotExist()
    }

    @Test fun settingsAboutAndLicenseAreReachableWithControllerOnly() {
        key(KeyEvent.KEYCODE_BUTTON_L1)
        compose.onNodeWithText("Settings").assertIsSelected()
        settingsSection("About")
        repeat(4) { key(KeyEvent.KEYCODE_BUTTON_R2); compose.waitForIdle() }
        var found = false
        for (step in 0..12) {
            if (compose.onAllNodes(hasText("BSD 2-Clause") and isFocused()).fetchSemanticsNodes().isNotEmpty()) { found = true; break }
            key(KeyEvent.KEYCODE_DPAD_DOWN); compose.waitForIdle()
        }
        assertTrue("License row should be reachable after paging Settings", found)
        key(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithText("BSD 2-Clause license").assertExists()
        compose.onNodeWithText("Close").assertIsFocused()
        key(KeyEvent.KEYCODE_BUTTON_R2)
        compose.onNodeWithText("Settings").assertIsSelected()
        key(KeyEvent.KEYCODE_BUTTON_B)
        compose.onNodeWithText("BSD 2-Clause license").assertDoesNotExist()
    }

    @Test fun triggersPageCatalogAndAnalogHoldRepeatsThenStops() {
        val rows = "header\n" + (0..299).joinToString("\n") { index ->
            "ID\tEU\tEntry ${index.toString().padStart(3, '0')}\thttp://127.0.0.1/unused\t\tEP0000-PCSE${(92000 + index)}_00-0000000000000000\t\t\t4"
        }
        Repository.get(context).store.replaceCatalog(Category.GAMES, rows.reader())
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Entry 000").performSemanticsAction(SemanticsActions.RequestFocus)
        fun focusedIndex(): Int = compose.onAllNodes(isFocused()).fetchSemanticsNodes().mapNotNull {
            it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
                .firstNotNullOfOrNull { text -> Regex("Entry (\\d+)").find(text.text)?.groupValues?.get(1)?.toInt() }
        }.firstOrNull() ?: -1
        key(KeyEvent.KEYCODE_BUTTON_R2)
        compose.waitUntil(5_000) { focusedIndex() > 0 }
        key(KeyEvent.KEYCODE_BUTTON_L2)
        compose.onNodeWithText("Entry 000").assertIsFocused()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R2))
        compose.waitUntil(5_000) { focusedIndex() >= 25 }
        instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_R2))
        compose.waitForIdle()
        val digitalStopped = focusedIndex()
        Thread.sleep(350); compose.waitForIdle()
        assertEquals(digitalStopped, focusedIndex())
        fun trigger(value: Float) {
            compose.activityRule.scenario.onActivity { activity ->
                val pointer = android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_UNKNOWN }
                val coords = android.view.MotionEvent.PointerCoords().apply { setAxisValue(android.view.MotionEvent.AXIS_RTRIGGER, value) }
                val now = android.os.SystemClock.uptimeMillis()
                val event = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_MOVE, 1, arrayOf(pointer), arrayOf(coords),
                    0, 0, 1f, 1f, -1, 0, android.view.InputDevice.SOURCE_JOYSTICK, 0)
                activity.dispatchGenericMotionEvent(event); event.recycle()
            }
        }
        trigger(1f)
        compose.waitUntil(5_000) { focusedIndex() >= digitalStopped + 25 }
        trigger(0f); compose.waitForIdle()
        val stopped = focusedIndex()
        Thread.sleep(350); compose.waitForIdle()
        assertEquals(stopped, focusedIndex())
    }

    @Test fun downloadActionsAndDeleteDialogAreControllerAccessible() {
        val repo = Repository.get(context)
        compose.onNodeWithText("Alpha").assertExists()
        val item = CatalogItem(Category.GAMES, "PCSE90001", content, "EU", "Controller download fixture", "http://127.0.0.1/unused", 4)
        val id = repo.store.addJob(item, "controller-fixture.pkg", tree.toString())
        repo.store.update(id, state = JobState.DOWNLOADED, transferComplete = true)
        repo.refreshJobs()
        key(KeyEvent.KEYCODE_BUTTON_R1)
        compose.onNodeWithText("Downloads").assertIsSelected()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Clear history").assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onAllNodes(hasText("Delete") and isFocused()).assertCountEquals(1)
        key(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithText("Delete files?").assertExists()
        compose.onNodeWithText("Keep").assertIsFocused()
        key(KeyEvent.KEYCODE_BUTTON_B)
        compose.onNodeWithText("Delete files?").assertDoesNotExist()
    }

    @Test fun sqliteMatchesReportedAndUnreportedAlternativesWithinSelectedRegions() {
        Store(context).use { store ->
            val titles = store.page(CatalogFilter(regions = setOf("EU", "US", "ASIA"),
                compatibilityStatuses = setOf("Playable", "Unreported")), 0, 100).map { it.title }
            assertEquals(listOf("Alpha", "Omega"), titles)
        }
    }

    @Test fun savesPkgAndRifInFolderWithoutPrivateCopy() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("fixture"))
            val initial = queue(server)
            val job = awaitJob(initial.id) { it.state == JobState.DOWNLOADED }
            assertTrue(job.transferComplete); assertNotNull(job.packageUri); assertNotNull(job.licenseUri)
            val pkg = context.contentResolver.openInputStream(Uri.parse(job.packageUri))!!.use { it.readBytes() }
            assertArrayEquals("fixture".toByteArray(), pkg)
            val rif = context.contentResolver.openInputStream(Uri.parse(job.licenseUri))!!.use { it.readBytes() }
            assertArrayEquals(syntheticLicense().second, rif)
            assertFalse(File(Repository.get(context).downloadsDir, job.filename).exists())
            assertFalse(Repository.get(context).licenseFile(job).exists())
            assertEquals(job.filename, androidx.documentfile.provider.DocumentFile.fromSingleUri(context, Uri.parse(job.packageUri))!!.name)
        }
    }

    @Test fun retryAfterRenameFailureDoesNotDownloadAgain() {
        context.contentResolver.call(tree, "fail-next-rename", null, null)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("fixture"))
            val initial = queue(server)
            val failed = awaitJob(initial.id) { it.state == JobState.FAILED }
            assertTrue(failed.transferComplete)
            Repository.get(context).action(failed, JobState.QUEUED)
            awaitJob(initial.id) { it.state == JobState.DOWNLOADED }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun folderDownloadResumesAndCancellationDeletesPartial() {
        val body = ByteArray(128 * 1024) { (it % 251).toByte() }
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val start = request.getHeader("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
                    return MockResponse().setHeader("ETag", "fixture-v1").setHeader("Accept-Ranges", "bytes")
                        .setResponseCode(if (start > 0) 206 else 200)
                        .apply { if (start > 0) setHeader("Content-Range", "bytes $start-${body.lastIndex}/${body.size}") }
                        .setBody(Buffer().write(body, start, body.size - start)).throttleBody(1024, 20, TimeUnit.MILLISECONDS)
                }
            }
            val repo = Repository.get(context)
            val initial = queue(server)
            val running = awaitJob(initial.id) { it.state == JobState.RUNNING && it.bytes >= 4096 }
            repo.action(running, JobState.PAUSED)
            // Wait for the worker to observe the pause before resuming.
            Thread.sleep(200)
            repo.action(repo.store.job(initial.id)!!, JobState.QUEUED)
            val completed = awaitJob(initial.id) { it.state == JobState.DOWNLOADED }
            assertTrue(server.requestCount >= 2)
            assertArrayEquals(body, context.contentResolver.openInputStream(Uri.parse(completed.packageUri))!!.use { it.readBytes() })
            val next = queue(server)
            val partial = awaitJob(next.id) { it.state == JobState.RUNNING && it.bytes >= 4096 }
            repo.action(partial, JobState.CANCELED)
            val canceled = repo.store.job(next.id)!!
            assertEquals(JobState.CANCELED, canceled.state)
            assertFalse(androidx.documentfile.provider.DocumentFile.fromSingleUri(context, Uri.parse(canceled.packageUri))!!.exists())
        }
    }
}
