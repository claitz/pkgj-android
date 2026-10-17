/* Copyright 2018-2019 Philippe Daouadi; Copyright 2019-2020 Asakura Reiko.
 * Android adaptation Copyright 2026. SPDX-License-Identifier: BSD-2-Clause.
 * See the repository LICENSE. */
package org.pkgj.android.catalog

import java.io.BufferedReader
import java.io.Reader
import java.util.Locale

enum class Category(val label: String, val key: String) {
    GAMES("Vita games", "url_games"), DLCS("Vita DLC", "url_dlcs"),
    DEMOS("Vita demos", "url_psv_demos"), THEMES("Vita themes", "url_psv_themes"),
    PSM("PS Mobile", "url_psm_games"), PSP("PSP games", "url_psp_games"),
    PSP_DLC("PSP DLC", "url_psp_dlcs"), PSX("PS1 games", "url_psx_games");
    val vita3kRelevant get() = this == GAMES || this == DLCS || this == DEMOS || this == THEMES
    val upstreamUrl: String get() = when (this) {
        GAMES -> "https://nopaystation.com/tsv/PSV_GAMES.tsv"
        DLCS -> "https://nopaystation.com/tsv/PSV_DLCS.tsv"
        DEMOS -> "https://nopaystation.com/tsv/PSV_DEMOS.tsv"
        THEMES -> "https://nopaystation.com/tsv/PSV_THEMES.tsv"
        PSM -> "https://psmreborn.com/tsv.php"
        PSP -> "https://nopaystation.com/tsv/PSP_GAMES.tsv"
        PSP_DLC -> "https://nopaystation.com/tsv/PSP_DLCS.tsv"
        PSX -> "https://nopaystation.com/tsv/PSX_GAMES.tsv"
    }
}

// Legacy categories remain readable for existing download records.
val VitaCategories = Category.entries.filter { it.vita3kRelevant }

data class CatalogItem(
    val category: Category, val titleId: String, val contentId: String,
    val region: String, val title: String, val url: String, val size: Long?, val zrif: String = "",
    val version: String = ""
)

data class ParseStats(val rows: Int, val accepted: Int, val malformed: Int)

object CatalogParser {
    private data class Layout(val name: Int, val url: Int, val content: Int, val size: Int, val version: Int = -1)
    private fun layout(category: Category) = when (category) {
        Category.GAMES, Category.DEMOS -> Layout(2, 3, 5, 8, 11)
        Category.DLCS, Category.THEMES, Category.PSM -> Layout(2, 3, 5, 7)
        Category.PSX -> Layout(2, 3, 4, 7)
        Category.PSP -> Layout(3, 4, 5, 9)
        Category.PSP_DLC -> Layout(2, 3, 4, 8)
    }

    /** Streams lines; never retains the source TSV in memory. */
    fun parse(reader: Reader, category: Category, accept: (CatalogItem) -> Unit): ParseStats {
        val layout = layout(category)
        var rows = 0; var accepted = 0; var malformed = 0
        BufferedReader(reader).use { input ->
            input.readLine() // upstream always has a header
            while (true) {
                val line = input.readLine() ?: break
                if (line.isBlank()) continue
                rows++
                val fields = line.removeSuffix("\r").split('\t')
                if (fields.size <= maxOf(1, layout.name, layout.url, layout.content)) { malformed++; continue }
                val name = fields[layout.name].trim()
                val content = fields[layout.content].trim()
                val titleId = content.takeIf { it.length >= 16 }?.substring(7, 16).orEmpty()
                val url = fields[layout.url].trim()
                if (name.isEmpty() || titleId.isEmpty()) { malformed++; continue }
                val size = fields.getOrNull(layout.size)?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull()?.takeIf { it >= 0 }
                val zrif = if (category.vita3kRelevant) fields.getOrNull(4)?.trim().orEmpty() else ""
                val version = fields.getOrNull(layout.version)?.trim().orEmpty().takeUnless { it == "0" || it.equals("MISSING", true) }.orEmpty()
                accept(CatalogItem(category, titleId, content, fields[1].trim().uppercase(Locale.ROOT), name, url, size,
                    zrif.takeUnless { it.equals("MISSING", true) }.orEmpty(), version))
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
    val region: String = "All",
    val category: String = "All",
    val sort: String = "Title",
    val compatibilityStatus: String = "All"
)

fun CatalogFilter.sqlWhere(): Pair<String, Array<String>> {
    val parts = mutableListOf<String>(); val args = mutableListOf<String>()
    if (search.isNotBlank()) {
        parts += "(title LIKE ? ESCAPE '\\' OR titleid LIKE ? ESCAPE '\\')"
        val q = "%" + search.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        args += q; args += q
    }
    if (region != "All") { parts += "region=?"; args += region }
    if (category != "All") { parts += "category=?"; args += category }
    parts += "category IN (${VitaCategories.joinToString(",") { "?" }})"
    args += VitaCategories.map { it.name }
    when (compatibilityStatus) {
        "All" -> Unit
        "Unreported" -> parts += "NOT EXISTS (SELECT 1 FROM compatibility c WHERE c.titleid=catalog.titleid)"
        else -> {
            parts += "EXISTS (SELECT 1 FROM compatibility c WHERE c.titleid=catalog.titleid AND instr(' / ' || c.status || ' / ', ' / ' || ? || ' / ') > 0)"
            args += compatibilityStatus
        }
    }
    return (if (parts.isEmpty()) "" else "WHERE " + parts.joinToString(" AND ")) to args.toTypedArray()
}
