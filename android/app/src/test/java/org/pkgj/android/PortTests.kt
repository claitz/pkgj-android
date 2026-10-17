package org.pkgj.android

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.pkgj.android.catalog.*
import org.pkgj.android.compatibility.CompatibilityXml
import org.pkgj.android.compatibility.CompatibilityValidators
import org.pkgj.android.download.*
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.zip.Deflater

class PortTests {
    @Test fun parsesCompatibilityXmlAndMapsOnlyVitaStatusLabels() {
        val xml = """
            ﻿<?xml version="1.0" encoding="UTF-8"?>
            <compatibility iso_db_updated_at="2026-10-17 15:59:27Z" version="1" issue_count="2">
              <app title_id="PCSE12345"><issue_id>420</issue_id><labels><label>998</label><label>997</label><label>995</label></labels><updated_at>1792252767</updated_at></app>
              <app title_id="PCSE54321"><issue_id>421</issue_id><labels><label>996</label></labels><updated_at>1792252767</updated_at></app>
            </compatibility>
        """.trimIndent()
        val parsed = CompatibilityXml.parse(xml.reader(), mapOf(
            "998" to "graphics bug", "997" to "Playable", "995" to "Intro", "996" to "android bug"))
        assertEquals("2026-10-17 15:59:27Z", parsed.updatedAt)
        assertEquals(1, parsed.reports.size)
        assertEquals("PCSE12345", parsed.reports.single().titleId)
        assertEquals(420L, parsed.reports.single().issueId)
        assertEquals("Playable / Intro", parsed.reports.single().status)
        assertEquals(1792252767L, parsed.reports.single().updatedAt)
    }

    @Test fun rejectsCompatibilityXmlDoctype() {
        val xml = "<!DOCTYPE compatibility [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><compatibility iso_db_updated_at='now'><app title_id='PCSE12345'><issue_id>1</issue_id><labels><label>&x;</label></labels></app></compatibility>"
        assertThrows(Exception::class.java) { CompatibilityXml.parse(xml.reader(), mapOf("1" to "Playable")) }
    }

    @Test fun compatibilityReleaseValidatorsDetectChangedAssets() {
        assertTrue(CompatibilityValidators.unchanged("\"a\"", "today", "\"a\"", "yesterday"))
        assertFalse(CompatibilityValidators.unchanged("\"b\"", "today", "\"a\"", "today"))
        assertTrue(CompatibilityValidators.unchanged(null, "today", "\"a\"", "today"))
        assertFalse(CompatibilityValidators.unchanged("\"b\"", "today", "\"a\"", "yesterday"))
    }

    @Test fun parsesUpstreamLayoutsAndSkipsMalformedRows() {
        val rows = mutableListOf<CatalogItem>()
        val content = "EP0000-PCSE12345_00-0000000000000000"
        val tsv = "\uFEFFID\tRegion\tName\tURL\tzRIF\tContent\tDate\tOriginal\tSize\r\n" +
            "PCSE12345\tUS\tExample\thttps://example.test/a.pkg\t\t$content\t\t\t1234\r\n" +
            "bad\tUS\n" +
            "PCSE12346\tEU\tOther\t\t\tEP0000-PCSE12346_00-0000000000000000\t\t\t\n"
        val stats = CatalogParser.parse(tsv.reader(), Category.GAMES, rows::add)
        assertEquals(3, stats.rows); assertEquals(2, stats.accepted); assertEquals(1, stats.malformed)
        assertEquals("PCSE12345", rows[0].titleId); assertEquals(1234L, rows[0].size)
        assertNull(rows[1].size); assertEquals("", rows[1].url)
        val psp = mutableListOf<CatalogItem>()
        CatalogParser.parse("header\nX\tJP\tX\tPSP title\thttps://x.test/a\t$content\t\t\t\t55\n".reader(), Category.PSP, psp::add)
        assertEquals("PSP title", psp.single().title); assertEquals(55L, psp.single().size)
        assertEquals("", psp.single().zrif)
    }

    @Test fun namesPkgAndRifFromCatalogMetadata() {
        val content = "EP0000-PCSE12345_00-0000000000000000"
        val row = listOf("PCSE12345", "US", "My Game/Deluxe", "https://example.test/game.pkg", "", content,
            "", "", "1024", "", "3.60", "1.02").joinToString("\t")
        val items = mutableListOf<CatalogItem>()
        CatalogParser.parse(("header\n$row\n").reader(), Category.GAMES, items::add)
        val item = items.single()
        assertEquals("1.02", item.version)
        val base = DownloadNames.baseName(item)
        assertEquals("[US-PCSE12345] - My Game Deluxe v1.02", base)
        assertEquals("$base.rif", "$base.pkg".removeSuffix(".pkg") + ".rif")
        assertEquals("[EU-PCSE12345] - Untitled", DownloadNames.baseName(item.copy(region = "EU", title = "../", version = "0")))
        assertTrue(DownloadNames.baseName(item.copy(title = "😀".repeat(100))).toByteArray(Charsets.UTF_8).size < 200)
    }

    @Test fun keepsCatalogLicenseAndDecodesMatchingRif() {
        val content = "EP0000-PCSE12345_00-0000000000000000"
        val license = ByteArray(512)
        content.toByteArray(Charsets.US_ASCII).copyInto(license, 0x10)
        val compressor = Deflater().apply { setDictionary(LicenseCodec.dictionary); setInput(license); finish() }
        val packed = ByteArray(2048)
        val count = compressor.deflate(packed)
        compressor.end()
        val zrif = Base64.getEncoder().encodeToString(packed.copyOf(count))
        val items = mutableListOf<CatalogItem>()
        CatalogParser.parse(("ID\tRegion\tName\tURL\tzRIF\tContent\tDate\tOriginal\tSize\n" +
            "PCSE12345\tUS\tExample\thttps://example.test/a.pkg\t$zrif\t$content\t\t\t512\n").reader(),
            Category.GAMES, items::add)
        assertEquals(zrif, items.single().zrif)
        assertArrayEquals(license, LicenseCodec.decode(items.single().zrif, content))
        assertArrayEquals(license, LicenseCodec.decode(Base64.getEncoder().encodeToString(packed.copyOf(count) + byteArrayOf(0x7a, 0x01)), content))
        assertThrows(IllegalArgumentException::class.java) {
            LicenseCodec.decode(Base64.getEncoder().encodeToString(packed.copyOf(count) + byteArrayOf(1, 2, 3)), content)
        }
        assertThrows(IllegalArgumentException::class.java) { LicenseCodec.decode(zrif, "EP0000-PCSE99999_00-0000000000000000") }
        assertThrows(IllegalArgumentException::class.java) { LicenseCodec.decode("bad!", content) }
    }

    @Test fun filteringEscapesWildcardsAndAppliesRegionCategory() {
        val (sql, args) = CatalogFilter("  game_%  ", "US", Category.GAMES.name, platform = CatalogPlatform.ALL).sqlWhere()
        assertTrue(sql.contains("title LIKE ?")); assertTrue(sql.contains("region=?")); assertTrue(sql.contains("category=?"))
        assertArrayEquals(arrayOf("%game\\_\\%%", "%game\\_\\%%", "US", "GAMES"), args)
    }

    @Test fun vitaPlatformFilterIncludesOnlyVitaCatalogs() {
        val (sql, args) = CatalogFilter(platform = CatalogPlatform.PSVITA).sqlWhere()
        assertTrue(sql.contains("category IN (?,?,?,?)"))
        assertArrayEquals(arrayOf("GAMES", "DLCS", "DEMOS", "THEMES"), args)
    }

    @Test fun compatibilityFilterMatchesOneStatusInCompoundReports() {
        val (reportedSql, reportedArgs) = CatalogFilter(compatibilityStatus = "Ingame +").sqlWhere()
        assertTrue(reportedSql.contains("EXISTS (SELECT 1 FROM compatibility"))
        assertTrue(reportedSql.contains("instr(' / ' || c.status"))
        assertArrayEquals(arrayOf("Ingame +"), reportedArgs)

        val (unreportedSql, unreportedArgs) = CatalogFilter(compatibilityStatus = "Unreported").sqlWhere()
        assertTrue(unreportedSql.contains("NOT EXISTS (SELECT 1 FROM compatibility"))
        assertEquals(0, unreportedArgs.size)
    }

    @Test fun transitionsDoNotTreatInterruptedAsComplete() {
        assertTrue(JobTransitions.canMove(JobState.RUNNING, JobState.QUEUED))
        assertTrue(JobTransitions.canMove(JobState.PAUSED, JobState.QUEUED))
        assertFalse(JobTransitions.canMove(JobState.QUEUED, JobState.DOWNLOADED))
        assertFalse(JobTransitions.canMove(JobState.FAILED, JobState.DOWNLOADED))
    }

    @Test fun resumesOnlyOnMatchingPartialResponse() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-5/6")
                .setHeader("Content-Length", "3").setHeader("ETag", "\"a\"").setHeader("Accept-Ranges", "bytes").setBody("def"))
            server.start()
            val file = tempFile("abc")
            val result = DownloadEngine().transfer(server.url("/file").toString(), file, "\"a\"", { null }) { _, _, _, _ -> }
            assertEquals("abcdef", file.readText()); assertEquals(6L, result.bytes)
            val request = server.takeRequest()
            assertEquals("bytes=3-", request.getHeader("Range")); assertEquals("\"a\"", request.getHeader("If-Range"))
        }
    }

    @Test fun fullResponseReplacesPartialAndBadRangeNeverAppends() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("new"))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 2-4/5").setBody("xyz"))
            server.start()
            val file = tempFile("old")
            DownloadEngine().transfer(server.url("/file").toString(), file, "\"a\"", { null }) { _, _, _, _ -> }
            assertEquals("new", file.readText())
            assertThrows(IllegalStateException::class.java) {
                DownloadEngine().transfer(server.url("/file").toString(), file, "\"a\"", { null }) { _, _, _, _ -> }
            }
            assertEquals("new", file.readText())
        }
    }

    @Test fun changedEtagCannotResumeIntoOldBytes() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-5/6")
                .setHeader("ETag", "\"new\"").setBody("def"))
            server.start()
            val file = tempFile("abc")
            assertThrows(IllegalStateException::class.java) {
                DownloadEngine().transfer(server.url("/file").toString(), file, "\"old\"", { null }) { _, _, _, _ -> }
            }
            assertEquals("abc", file.readText())
        }
    }

    @Test fun unsatisfiableRangeRestartsWithoutClaimingCompletion() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(416))
            server.enqueue(MockResponse().setResponseCode(200).setBody("fresh"))
            server.start()
            val file = tempFile("stale")
            val result = DownloadEngine().transfer(server.url("/file").toString(), file, "\"old\"", { null }) { _, _, _, _ -> }
            assertEquals("fresh", file.readText()); assertEquals(5L, result.bytes)
            assertEquals("bytes=5-", server.takeRequest().getHeader("Range"))
            assertNull(server.takeRequest().getHeader("Range"))
        }
    }

    private fun tempFile(text: String): File = Files.createTempDirectory("pkgj-test").resolve("file.pkg").toFile().apply { writeText(text); deleteOnExit() }
}
