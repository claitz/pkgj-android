package org.pkgj.android.download

import org.pkgj.android.catalog.CatalogItem
import java.util.Locale

object DownloadNames {
    /** Both .pkg and .rif use this base name. */
    fun baseName(item: CatalogItem): String {
        val region = item.region.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "").take(8).ifEmpty { "XX" }
        val titleId = item.titleId.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "").take(16).ifEmpty { "UNKNOWN" }
        val cleanTitle = item.title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ").trim(' ', '.')
        val title = limitUtf8(cleanTitle, 120).trim(' ', '.').ifEmpty { "Untitled" }
        val version = item.version.trim().removePrefix("v").removePrefix("V")
            .takeIf { it != "0" && it.matches(Regex("[0-9]+(\\.[0-9]+)*")) }
        val suffix = if (item.downloadType == DownloadType.UPDATE) {
            requireNotNull(version) { "Update version is required" }
            " - Update v$version"
        } else version?.let { " v$it" }.orEmpty()
        return "[$region-$titleId] - $title$suffix"
    }

    private fun limitUtf8(value: String, limit: Int): String {
        val out = StringBuilder()
        var length = 0
        var index = 0
        while (index < value.length) {
            val point = Character.codePointAt(value, index)
            val segment = String(Character.toChars(point))
            val bytes = segment.toByteArray(Charsets.UTF_8).size
            if (length + bytes > limit) break
            out.append(segment)
            length += bytes
            index += Character.charCount(point)
        }
        return out.toString()
    }
}
