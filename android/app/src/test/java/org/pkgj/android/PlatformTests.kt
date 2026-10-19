package org.pkgj.android

import org.junit.Assert.*
import org.junit.Test
import org.pkgj.android.catalog.*
import org.pkgj.android.download.*
import org.pkgj.android.updates.*

class PlatformTests {
    private val id = "NPUB90001"
    private val content = "UP0000-${id}_00-0000000000000000"
    private val rap = "000102030405060708090a0b0c0d0e0f"
    private fun parse(text: String, category: Category) = mutableListOf<CatalogItem>().also { CatalogParser.parse(text.reader(), category, it::add) }
    private fun manifest(packages: String) = "<titlepatch titleid='$id'><tag>$packages</tag></titlepatch>"
    private fun pkg(v: String, extra: String = "") = "<package version='$v' url='https://example.invalid/$v.pkg' size='12' ps3_system_ver='04.2000' $extra/>"

    @Test fun restoresPspAndPs1LayoutsWithoutVitaLicenses() {
        val psp = parse("header\nID\tJP\tOriginal\tPSP fixture\thttps://example.invalid/psp.pkg\tJP0000-NPJH90001_00-0000000000000000\t\t\t\t123", Category.PSP).single()
        val ps1 = parse("header\nID\tEU\tPS1 fixture\thttps://example.invalid/ps1.pkg\tEP0000-NPEF90001_00-0000000000000000\t\t\t456", Category.PSX).single()
        assertEquals("NPJH90001", psp.titleId); assertEquals(123L, psp.size)
        assertEquals("NPEF90001", ps1.titleId); assertEquals(456L, ps1.size)
        assertEquals("", ps1.zrif); assertFalse(Category.PSX.supportsUpdates); assertFalse(Category.PSP.supportsUpdates)
    }
    @Test fun parsesPs3TsvAndPkgiCsvIncludingTheFirstDataRow() {
        val tsv = "\uFEFFTitle ID\tRegion\tName\tPKG direct link\tRAP\tContent ID\tDate\tFilename\tSize\r\n$id\tUS\tPS3 fixture\thttps://example.invalid/game.pkg\t$rap\t$content\t\t\t123\r\n"
        val item = parse(tsv, Category.PS3_GAMES).single()
        val reordered = parse("Name\tContent ID\tRegion\tURL\tSize\nReordered\t$content\tEU\thttps://example.invalid/game.pkg\t12", Category.PS3_GAMES).single()
        assertEquals("Reordered", reordered.title); assertEquals(12L, reordered.size); assertEquals("", reordered.rap)
        assertEquals(id, item.titleId); assertEquals(123L, item.size); assertEquals(rap, item.rap); assertEquals("", item.zrif)
        val csv = "$content,1,\"Title, deluxe\",Description,$rap,https://example.invalid/game.pkg,123,\n"
        assertEquals("Title, deluxe", parse(csv, Category.PS3_GAMES).single().title)
        assertEquals(rap, parse("contentid,type,name,description,rap,url,size,checksum\n$csv", Category.PS3_GAMES).single().rap)
        assertTrue(parse(csv.replace(",1,", ",2,"), Category.PS3_GAMES).isEmpty())
        assertTrue(parse(csv.replace(content, "../../bad"), Category.PS3_GAMES).isEmpty())
        assertTrue(parse(csv.replace("\"Title, deluxe\"", "\"unfinished"), Category.PS3_GAMES).isEmpty())
    }
    @Test fun platformFiltersKeepCompatibilityExclusiveToVitaGames() {
        Platform.entries.forEach { platform ->
            val (sql, args) = CatalogFilter(platform = platform, regions = setOf("EU", "US"), compatibilityStatuses = setOf("Playable")).sqlWhere()
            assertTrue(sql.contains("region IN (?,?)"))
            assertTrue(args.toList().containsAll(platform.categories().map { it.name }))
            assertEquals(platform == Platform.VITA, sql.contains("compatibility c"))
        }
        val (_, args) = CatalogFilter(category = Category.PSP.name).sqlWhere()
        assertArrayEquals(arrayOf("PSP", "PSP", "PSP_DLC"), args)
    }
    @Test fun ps3ManifestUsesItsOwnUrlAndKeepsTheWholeOrderedChain() {
        assertEquals("https://a0.ww.np.dl.playstation.net/tpl/np/$id/$id-ver.xml", PatchUrls.forTitle(id, Platform.PS3))
        assertThrows(IllegalArgumentException::class.java) { PatchUrls.forTitle(id, Platform.PSX) }
        val result = PatchXml.parsePackages(manifest(pkg("01.09") + pkg("01.02")).byteInputStream(), id, Platform.PS3)
        assertEquals(listOf("01.09", "01.02"), result.map { it.version })
        assertTrue(result.all { it.platform == Platform.PS3 && it.requiredFirmware == "04.2000" })
        assertEquals(24L, PatchCheck(id, 1, result.last(), Platform.PS3, result).totalSize)
    }
    @Test fun ps3DuplicateVersionsHaveSeparatePartsAndNoLicenseSidecars() {
        val result = PatchXml.parsePackages(manifest(pkg("01.02") + pkg("01.02").replace("01.02.pkg", "other.pkg")).byteInputStream(), id, Platform.PS3)
        assertEquals(listOf(1, 2), result.map { it.part })
        val game = CatalogItem(Category.PS3_GAMES, id, content, "US", "Fixture", "https://example.invalid/base", 4, rap = rap)
        val first = result[0].downloadItem(game); val second = result[1].downloadItem(game)
        assertEquals("", first.rap); assertEquals("", first.zrif)
        assertNotEquals(first.contentId, second.contentId)
        assertNotEquals(DownloadNames.baseName(first), DownloadNames.baseName(second))
        assertTrue(DownloadNames.baseName(first).endsWith("Update v01.02 - Part 01"))
        assertNotEquals(result[0].identity, result[0].copy(platform = Platform.VITA).identity)
    }
    @Test fun malformedPs3ManifestsNeverOfferAPartialChain() {
        listOf(manifest(pkg("01.01") + "<package version='01.02'/>"),
            manifest(pkg("01.02").replace("04.2000", "bad")),
            "<!DOCTYPE titlepatch [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>" + manifest("&x;")).forEach {
            assertThrows(PatchManifestException::class.java) { PatchXml.parsePackages(it.byteInputStream(), id, Platform.PS3) }
        }
        assertTrue(PatchXml.parsePackages("<titlepatch/>".byteInputStream(), id, Platform.PS3).isEmpty())
    }
    @Test fun rapIsValidatedRawBytesWithAContentIdFilename() {
        assertArrayEquals(ByteArray(16) { it.toByte() }, Ps3License.decode(rap))
        assertEquals("$content.rap", Ps3License.filename(content))
        listOf("", "00", rap + "00", "g".repeat(32)).forEach { bad -> assertThrows(IllegalArgumentException::class.java) { Ps3License.decode(bad) } }
        assertThrows(IllegalArgumentException::class.java) { Ps3License.filename("../../escape") }
    }
}
