package org.pkgj.android

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.pkgj.android.catalog.*
import org.pkgj.android.download.*
import org.pkgj.android.updates.*

class UpdateTests {
    private val id = "PCSE90001"
    private fun xml(packages: String) = """<?xml version="1.0"?><titlepatch titleid="$id"><tag name="test">$packages</tag></titlepatch>"""
    private fun pkg(version: String = "01.02", extra: String = "", children: String = "") =
        """<package version="$version" url="https://example.invalid/patch.pkg" $extra>$children</package>"""
    private fun parse(value: String) = PatchXml.parse(value.byteInputStream(), id)
    private fun invalid(value: String) { assertThrows(PatchManifestException::class.java) { parse(value) } }

    @Test fun sonyCaMatchesIndependentPublishedFingerprintAndIsAValidCa() {
        val certificate = java.io.File("src/main/res/raw/sony_update_ca.pem").inputStream().use {
            java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(it) as java.security.cert.X509Certificate
        }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("51d5a7833f67f1d1c6212f75997e4b83dada0f7910ba4b5a3fa4e9b7b9bc827c", hash)
        assertTrue(certificate.basicConstraints >= 0)
        certificate.checkValidity(); certificate.verify(certificate.publicKey)
    }

    @Test fun manifestUrlsMatchOriginalPkgjHmacImplementation() {
        // Golden values produced by upstream sha256.cpp + the key in patchinfo.cpp, not the JVM implementation.
        val hashes = mapOf(
            "PCSA00007" to "86d7c3b64d554b9639c5ad69aac20e16ea34c2f513d412f38329257f4ad15782",
            "PCSE00890" to "75a2ea989c25f2e9fd25ac978b082a2e15c60a4dad04719fb51e8c0e4e7bf277",
            "PCSB00963" to "c4491a29f8b267701ebe6f2108f5a6446fd8dad05f0e9333eeda047081ec4ffe")
        hashes.forEach { (title, hash) -> assertEquals("https://gs-sec.ww.np.dl.playstation.net/pl/np/$title/$hash/$title-ver.xml", PatchUrls.forTitle(title)) }
        listOf("pcse00890", "PCSE0089", "../PCSE00890", "PCSE00890/").forEach {
            assertThrows(IllegalArgumentException::class.java) { PatchUrls.forTitle(it) }
        }
    }
    @Test fun lastPackageWinsInManifestOrderEvenWhenVersionIsLower() {
        val result = parse(xml(pkg("99.99") + pkg("01.03", "size=\"1234\" psp2_system_ver=\"${0x03650000}\"")))!!
        assertEquals("01.03", result.version); assertEquals(1234L, result.size); assertEquals("3.65", result.requiredFirmware)
    }
    @Test fun hybridOverridesOnlyUrlAndSizeFromItsOwnParent() {
        val hybrid = """<hybrid_package url="https://example.invalid/full.pkg?a=1&amp;b=2" size="9999" version="99.99" psp2_system_ver="0"/>"""
        val result = parse(xml(pkg("01.05", "size=\"12\" psp2_system_ver=\"${0x03610000}\"", hybrid)))!!
        assertEquals("01.05", result.version); assertEquals("3.61", result.requiredFirmware)
        assertEquals(9999L, result.size); assertEquals("https://example.invalid/full.pkg?a=1&b=2", result.url)
        val later = parse(xml(pkg(children = hybrid) + pkg("01.06")))!!
        assertEquals("https://example.invalid/patch.pkg", later.url); assertNull(later.size)
    }
    @Test fun missingHybridSizeIsUnknownNotIncrementalSize() {
        val p = parse(xml(pkg(extra = "size=\"12\"", children = """<hybrid_package url="https://example.invalid/full.pkg"/>""")))!!
        assertNull(p.size); assertNull(p.requiredFirmware)
    }
    @Test fun emptyValidManifestMeansNoUpdateButMissingOrMalformedXmlIsAnError() {
        assertNull(parse(xml("")))
        listOf("", "<Error>not available</Error>", "<titlepatch>", xml("<package/>") , xml(pkg()).replace(id, "PCSB90001")).forEach(::invalid)
    }
    @Test fun malformedFinalPackageCannotFallBackToAnOlderOne() {
        invalid(xml(pkg() + "<package version=\"01.03\"/>"))
        invalid(xml(pkg(extra = "size=\"-1\"")))
        invalid(xml(pkg(extra = "psp2_system_ver=\"not-a-number\"")))
        invalid(xml(pkg().replace("https://example.invalid/patch.pkg", "file:///tmp/patch.pkg")))
        invalid(xml(pkg(children = "<hybrid_package/>")))
        invalid(xml(pkg() + "<hybrid_package url=\"https://example.invalid/other.pkg\"/>"))
    }
    @Test fun doctypeAndExternalEntitiesAreRejectedWithoutNetworkAccess() {
        MockWebServer().use { server ->
            server.start()
            invalid("""<!DOCTYPE titlepatch [<!ENTITY stolen SYSTEM "${server.url("/entity")}">]><titlepatch titleid="$id"><tag>&stolen;</tag></titlepatch>""")
            invalid("""<!DOCTYPE titlepatch [<!ENTITY x "expanded">]><titlepatch><tag>&x;</tag></titlepatch>""")
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun sourceDistinguishesNoUpdateHttpAndParsingErrors() {
        MockWebServer().use { server ->
            val source = PatchSource { server.url("/$it-ver.xml").toString() }
            server.enqueue(MockResponse().setResponseCode(404)); assertNull(source.fetch(id))
            server.enqueue(MockResponse().setBody(xml(""))); assertNull(source.fetch(id))
            server.enqueue(MockResponse().setBody("")); assertThrows(PatchManifestException::class.java) { source.fetch(id) }
            server.enqueue(MockResponse().setResponseCode(503)); assertThrows(java.io.IOException::class.java) { source.fetch(id) }
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/manifest"))
            server.enqueue(MockResponse().setBody(xml(pkg()))); assertEquals("01.02", source.fetch(id)!!.version)
            assertEquals("/$id-ver.xml", server.takeRequest().path)
        }
    }
    @Test fun manifestSizeIsBoundedAndUnsafeRedirectsAreRejected() {
        MockWebServer().use { server ->
            val source = PatchSource { server.url("/manifest").toString() }
            server.enqueue(MockResponse().setBody("x".repeat(1024 * 1024 + 1)))
            assertThrows(java.io.IOException::class.java) { source.fetch(id) }
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "file:///tmp/x"))
            assertThrows(java.io.IOException::class.java) { source.fetch(id) }
        }
    }
    @Test fun updateIdentityAndFilenameAreSeparateFromGameAndOtherVersions() {
        val game = CatalogItem(Category.GAMES, id, "UP0000-${id}_00-0000000000000000", "US", "Game: test", "https://example.invalid/base.pkg", 7, "base-license", "01.02")
        val patch = GamePatch(id, "01.02", "https://example.invalid/update.pkg", 9, null)
        val request = patch.downloadItem(game)
        assertEquals("[US-$id] - Game test - Update v01.02", DownloadNames.baseName(request))
        assertNotEquals(DownloadNames.baseName(game), DownloadNames.baseName(request))
        assertEquals("", request.zrif); assertEquals(DownloadType.UPDATE, request.downloadType)
        assertNotEquals(game.contentId, request.contentId)
        assertNotEquals(patch.identity, patch.copy(version = "01.03").identity)
        assertThrows(IllegalArgumentException::class.java) { patch.downloadItem(game.copy(titleId = "PCSB90001")) }
    }
}
