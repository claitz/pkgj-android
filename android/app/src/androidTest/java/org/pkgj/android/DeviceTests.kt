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
            InstrumentationRegistry.getInstrumentation().uiAutomation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
            val edit = context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putBoolean("licenseCatalogV2", true).putBoolean("filenameVersionV1", true)
            Category.entries.forEach { edit.putString("source_${it.name}", "local") }
            edit.apply()
            context.grantUriPermission(context.packageName, tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            context.contentResolver.call(tree, "reset-fixtures", null, null)
            InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()
            Store(context).use { store ->
                store.writableDatabase.delete("catalog", null, null)
                val entries = listOf("Alpha" to "EU", "Beta" to "US", "Gamma" to "JP", "Omega" to "ASIA")
                val tsv = "header\n" + entries.mapIndexed { index, (name, region) ->
                    "ID\t$region\t$name\thttp://127.0.0.1/unused\t\tEP0000-PCSE9000${index + 1}_00-0000000000000000\t\t\t4"
                }.joinToString("\n")
                store.replaceCatalog(Category.GAMES, tsv.reader())
                store.replaceCompatibility(listOf(
                    CompatibilityReport("PCSE90001", 1, "Playable", 1),
                    CompatibilityReport("PCSE90002", 2, "Ingame + / Menu", 1),
                    CompatibilityReport("PCSE90003", 3, "Intro", 1)))
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
            compose.onNodeWithText("URL").assertIsDisplayed()
            compose.onNodeWithText("Choose folder").assertIsDisplayed()
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
        compose.onNodeWithText("Any status").performClick()
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
            db.execSQL("CREATE TABLE jobs(id INTEGER PRIMARY KEY,title TEXT,state TEXT)")
            db.execSQL("INSERT INTO jobs VALUES(1,'Existing download','DOWNLOADED')")
            Store(context).use { it.onUpgrade(db, 5, 6) }
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
        key(KeyEvent.KEYCODE_DPAD_DOWN)
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
        repeat(4) { key(KeyEvent.KEYCODE_BUTTON_R2); compose.waitForIdle() }
        var found = false
        for (step in 0..8) {
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
