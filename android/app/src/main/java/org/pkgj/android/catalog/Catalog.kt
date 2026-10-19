/* Copyright 2018-2019 Philippe Daouadi; Copyright 2019-2020 Asakura Reiko.
 * Android adaptation Copyright 2026. SPDX-License-Identifier: BSD-2-Clause.
 * See the repository LICENSE. */
package org.pkgj.android.catalog

import java.io.BufferedReader
import java.io.Reader
import java.util.Locale

enum class Platform(val label: String) { VITA("PS Vita"), PSP("PSP"), PSX("PS1"), PS3("PS3") }

enum class Category(val label: String, val key: String, val platform: Platform = Platform.VITA, val contentLabel: String = "Games") {
    GAMES("Vita games", "url_games"), DLCS("Vita DLC", "url_dlcs", contentLabel = "DLC"),
    DEMOS("Vita demos", "url_psv_demos", contentLabel = "Demos"), THEMES("Vita themes", "url_psv_themes", contentLabel = "Themes"),
    PSM("PS Mobile", "url_psm_games"), PSP("PSP games", "url_psp_games", Platform.PSP),
    PSP_DLC("PSP DLC", "url_psp_dlcs", Platform.PSP, "DLC"), PSX("PS1 games", "url_psx_games", Platform.PSX),
    PS3_GAMES("PS3 games", "url_ps3_games", Platform.PS3),
    PS3_DLCS("PS3 DLC", "url_ps3_dlcs", Platform.PS3, "DLC"),
    PS3_DEMOS("PS3 demos", "url_ps3_demos", Platform.PS3, "Demos"),
    PS3_THEMES("PS3 themes", "url_ps3_themes", Platform.PS3, "Themes"),
    PS3_AVATARS("PS3 avatars", "url_ps3_avatars", Platform.PS3, "Avatars");
    val vita3kRelevant get() = this in listOf(GAMES, DLCS, DEMOS, THEMES)
    val supportsUpdates get() = this == GAMES || this == PS3_GAMES
    val supported get() = this != PSM
    val hasLicense get() = vita3kRelevant || platform == Platform.PS3
    val licenseLabel get() = if (platform == Platform.PS3) "RAP" else "RIF"
    val upstreamUrl: String get() = when (this) {
        GAMES -> "https://nopaystation.com/tsv/PSV_GAMES.tsv"
        DLCS -> "https://nopaystation.com/tsv/PSV_DLCS.tsv"
        DEMOS -> "https://nopaystation.com/tsv/PSV_DEMOS.tsv"
        THEMES -> "https://nopaystation.com/tsv/PSV_THEMES.tsv"
        PSM -> "https://psmreborn.com/tsv.php"
        PSP -> "https://nopaystation.com/tsv/PSP_GAMES.tsv"
        PSP_DLC -> "https://nopaystation.com/tsv/PSP_DLCS.tsv"
        PSX -> "https://nopaystation.com/tsv/PSX_GAMES.tsv"
        else -> "https://nopaystation.com/tsv/${name}.tsv"
    }
}

val VitaCategories = Category.entries.filter { it.vita3kRelevant }
val CatalogCategories = Category.entries.filter { it.supported }
fun Platform.categories() = CatalogCategories.filter { it.platform == this }

data class CatalogItem(
    val category: Category, val titleId: String, val contentId: String,
    val region: String, val title: String, val url: String, val size: Long?, val zrif: String = "",
    val version: String = "", val downloadType: org.pkgj.android.download.DownloadType = org.pkgj.android.download.DownloadType.CATALOG,
    val rap: String = "", val patchPart: Int = 0
)

data class ParseStats(val rows: Int, val accepted: Int, val malformed: Int)

object CatalogParser {
    private data class Layout(val name: Int, val url: Int, val content: Int, val size: Int, val version: Int = -1, val region: Int = 1, val license: Int = 4)
    private fun layout(category: Category) = when (category) {
        Category.GAMES, Category.DEMOS -> Layout(2, 3, 5, 8, 11)
        Category.PS3_GAMES, Category.PS3_DLCS, Category.PS3_DEMOS, Category.PS3_THEMES, Category.PS3_AVATARS -> Layout(2, 3, 5, 8)
        Category.DLCS, Category.THEMES, Category.PSM -> Layout(2, 3, 5, 7)
        Category.PSX -> Layout(2, 3, 4, 7)
        Category.PSP -> Layout(3, 4, 5, 9)
        Category.PSP_DLC -> Layout(2, 3, 4, 8)
    }

    /** Streams lines; never retains the source TSV in memory. */
    fun parse(reader: Reader, category: Category, accept: (CatalogItem) -> Unit): ParseStats {
        var layout = layout(category)
        var rows = 0; var accepted = 0; var malformed = 0
        BufferedReader(reader).use { input ->
            val header = input.readLine()?.removePrefix("\uFEFF") ?: return ParseStats(0, 0, 0)
            if (category.platform == Platform.PS3 && '\t' !in header) return Ps3Catalog.parseCsv(header, input, category, accept)
            if (category.platform == Platform.PS3) {
                val columns = header.split('\t').map { it.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "") }
                fun column(vararg names: String, fallback: Int): Int = columns.indexOfFirst { it in names }.takeIf { it >= 0 } ?: fallback
                layout = layout.copy(name = column("name", "title", fallback = 2),
                    url = column("pkgdirectlink", "pkgurl", "url", fallback = 3),
                    content = column("contentid", "content", fallback = 5),
                    size = column("filesize", "size", fallback = 8),
                    region = column("region", fallback = 1), license = column("rap", fallback = -1),
                    version = column("appversion", "version", fallback = -1))
            }
            while (true) {
                val line = input.readLine() ?: break
                if (line.isBlank()) continue
                rows++
                val fields = line.removeSuffix("\r").split('\t')
                if (fields.size <= maxOf(layout.region, layout.name, layout.url, layout.content)) { malformed++; continue }
                val name = fields[layout.name].trim()
                val content = fields[layout.content].trim()
                val titleId = content.takeIf { it.length >= 16 }?.substring(7, 16).orEmpty()
                val url = fields[layout.url].trim()
                if (category.platform == Platform.PS3 && !Ps3License.validContentId(content)) { malformed++; continue }
                if (name.isEmpty() || titleId.isEmpty()) { malformed++; continue }
                val size = fields.getOrNull(layout.size)?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull()?.takeIf { it >= 0 }
                val zrif = if (category.vita3kRelevant) fields.getOrNull(layout.license)?.trim().orEmpty() else ""
                val version = fields.getOrNull(layout.version)?.trim().orEmpty().takeUnless { it == "0" || it.equals("MISSING", true) }.orEmpty()
                accept(CatalogItem(category, titleId, content, fields[layout.region].trim().uppercase(Locale.ROOT), name, url, size,
                    zrif.takeUnless { it.equals("MISSING", true) }.orEmpty(), version,
                    rap = if (category.platform == Platform.PS3) fields.getOrNull(layout.license)?.trim().orEmpty().takeUnless { it.equals("MISSING", true) }.orEmpty() else ""))
                accepted++
            }
        }
        return ParseStats(rows, accepted, malformed)
    }

    fun usableUrl(url: String): Boolean = (url.startsWith("https://") || url.startsWith("http://")) &&
        !url.equals("MISSING", true) && !url.equals("CART ONLY", true)
}

data class CatalogFilter(
    val search: String = "",
    val regions: Set<String> = emptySet(),
    val category: String = "All",
    val sort: String = "Title",
    val compatibilityStatuses: Set<String> = emptySet(),
    val platform: Platform = Platform.VITA
)

fun CatalogFilter.sqlWhere(): Pair<String, Array<String>> {
    val parts = mutableListOf<String>(); val args = mutableListOf<String>()
    if (search.isNotBlank()) {
        parts += "(title LIKE ? ESCAPE '\\' OR titleid LIKE ? ESCAPE '\\')"
        val q = "%" + search.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        args += q; args += q
    }
    if (regions.isNotEmpty()) {
        parts += "region IN (${regions.joinToString(",") { "?" }})"
        args += regions.sorted()
    }
    if (category != "All") { parts += "category=?"; args += category }
    // An explicit category also works for callers refreshing another platform's catalog.
    val selectedPlatform = Category.entries.firstOrNull { it.name == category }?.platform ?: platform
    val allowed = selectedPlatform.categories()
    parts += "category IN (${allowed.joinToString(",") { "?" }})"
    args += allowed.map { it.name }
    val gameCategory = when (selectedPlatform) { Platform.VITA -> Category.GAMES; Platform.PS3 -> Category.PS3_GAMES; else -> null }
    if (gameCategory != null && (category == "All" || category == gameCategory.name) && compatibilityStatuses.isNotEmpty()) {
        parts += "category='${gameCategory.name}'"
        val alternatives = mutableListOf<String>()
        val reported = (compatibilityStatuses - "Unreported").sorted()
        if (reported.isNotEmpty()) {
            alternatives += "EXISTS (SELECT 1 FROM compatibility c WHERE c.platform='${selectedPlatform.name}' AND c.titleid=catalog.titleid AND (" +
                reported.joinToString(" OR ") { "instr(' / ' || c.status || ' / ', ' / ' || ? || ' / ') > 0" } + "))"
            args += reported
        }
        if ("Unreported" in compatibilityStatuses)
            alternatives += "NOT EXISTS (SELECT 1 FROM compatibility c WHERE c.platform='${selectedPlatform.name}' AND c.titleid=catalog.titleid)"
        parts += alternatives.joinToString(" OR ", "(", ")")
    }
    return (if (parts.isEmpty()) "" else "WHERE " + parts.joinToString(" AND ")) to args.toTypedArray()
}
