package org.pkgj.android

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.pkgj.android.catalog.*
import org.pkgj.android.compatibility.*
import java.time.Instant

class Rpcs3CompatibilityTests {
    private val fixture = """{"return_code":0,"results":{
        "NPUB90001":{"status":"Playable","date":"2026-10-19","update":"01.04","patchsets":[{"packages":[]}]},
        "NPEB90001":{"status":"Ingame","date":""},
        "BLES90001":{"status":"Loadable","date":"bad"},
        "NPUB90002":{"status":"Intro","date":"2025-01-01"},
        "NPUB90003":{"status":"Nothing","date":"2025-01-02"},
        "NPUB90004":{"status":"Future status"},"bad/id":{"status":"Playable"},"NPUB90005":false
    }}"""

    @Test fun parsesOfficialShapeWithExactRegionalIdsAndKnownStatuses() {
        val db = Rpcs3CompatibilityJson.parse(("\uFEFF" + fixture).reader(), Instant.parse("2026-10-20T01:00:00Z"))
        assertEquals("2026-10-20", db.updatedAt)
        assertEquals(Rpcs3CompatibilityJson.statuses.toSet(), db.reports.map { it.status }.toSet())
        val us = db.reports.single { it.titleId == "NPUB90001" }
        assertEquals(Instant.parse("2026-10-19T00:00:00Z").epochSecond, us.updatedAt)
        assertTrue(db.reports.all { it.platform == Platform.PS3 && it.issueId == 0L })
        assertEquals("Ingame", db.reports.single { it.titleId == "NPEB90001" }.status)
        assertEquals(0L, db.reports.single { it.titleId == "BLES90001" }.updatedAt)
    }

    @Test fun rejectsUnavailableMalformedAndEmptyDatabases() {
        listOf("<html>Unavailable</html>", "{}", "{\"return_code\":-2,\"results\":{}}",
            "{\"return_code\":0,\"results\":[]}", "{\"return_code\":0,\"results\":{}}",
            "{\"return_code\":0,\"results\":{\"NPUB90001\":{\"status\":\"Unknown\"}}}").forEach {
            assertThrows(Exception::class.java) { Rpcs3CompatibilityJson.parse(it.reader()) }
        }
    }

    @Test fun boundsResponseSize() {
        val input = object : java.io.Reader() {
            override fun close() {}
            override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                buffer.fill(' ', offset, offset + length); return length
            }
        }
        assertThrows(IllegalArgumentException::class.java) { Rpcs3CompatibilityJson.parse(input) }
    }

    @Test fun followsRedirectsAndUsesConditionalGet() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/export"))
            server.enqueue(MockResponse().setBody(fixture).setHeader("ETag", "\"revision1\""))
            server.enqueue(MockResponse().setResponseCode(304))
            val source = Rpcs3CompatibilityDataSource(server.url("/compatibility?api=v1&export").toString())
            val downloaded = source.checkForUpdates(null, null)!!
            assertEquals(5, downloaded.database.reports.size)
            assertNull(source.checkForUpdates(downloaded.etag, downloaded.lastModified))
            assertEquals("/compatibility?api=v1&export", server.takeRequest().path)
            assertEquals("/export", server.takeRequest().path)
            assertEquals("\"revision1\"", server.takeRequest().getHeader("If-None-Match"))
        }
    }

    @Test fun httpAndParsingFailuresAreNotReportedAsUnchanged() {
        MockWebServer().use { server ->
            val source = Rpcs3CompatibilityDataSource(server.url("/").toString())
            for (response in listOf(MockResponse().setResponseCode(503), MockResponse().setBody("broken"), MockResponse().setResponseCode(304))) {
                server.enqueue(response)
                assertThrows(Exception::class.java) { source.checkForUpdates(null, null) }
            }
        }
    }

    @Test fun filtersAreScopedToSourceAndOnlyGameCategories() {
        val (sql, args) = CatalogFilter(platform = Platform.PS3, compatibilityStatuses = setOf("Playable", "Unreported")).sqlWhere()
        assertTrue(sql.contains("c.platform='PS3'")); assertTrue(sql.contains("category='PS3_GAMES'"))
        assertTrue(sql.contains("NOT EXISTS")); assertEquals("Playable", args.last())
        assertFalse(CatalogFilter(category = Category.PS3_DLCS.name, compatibilityStatuses = setOf("Playable")).sqlWhere().first.contains("compatibility c"))
        assertNotEquals(CompatibilityKey(Platform.VITA, "TEST90001"), CompatibilityKey(Platform.PS3, "TEST90001"))
    }

    @Test fun linksUseOfficialExactTitleSearchWithoutInventedReportIds() {
        assertEquals("https://rpcs3.net/compatibility?g=NPUB90001", compatibilityUrl("NPUB90001", Platform.PS3, null))
        assertEquals("https://github.com/Vita3K/compatibility/issues/123", compatibilityUrl("PCSE90001", Platform.VITA, CompatibilityReport("PCSE90001", 123, "Playable", 1)))
    }
}
