package org.pkgj.android.catalog

import java.io.BufferedReader

/** PKGi PS3's documented CSV record format. No console code is used. */
internal object Ps3Catalog {
    fun parseCsv(first: String, input: BufferedReader, category: Category, accept: (CatalogItem) -> Unit): ParseStats {
        var rows = 0; var accepted = 0; var malformed = 0
        val default = listOf("contentid", "type", "name", "description", "rap", "url", "size", "checksum")
        val firstFields = fields(first)
        val header = firstFields?.map { it.trim().lowercase() }?.takeIf { "contentid" in it && "url" in it } ?: default
        fun row(line: String) {
            if (line.isBlank()) return
            rows++
            val values = fields(line)
            if (values == null || values.size < header.size) { malformed++; return }
            fun value(name: String) = header.indexOf(name).takeIf { it >= 0 }?.let { values.getOrNull(it)?.trim() }.orEmpty()
            val id = value("contentid")
            val title = value("name")
            if (!Ps3License.validContentId(id) || title.isEmpty()) { malformed++; return }
            val type = value("type").toIntOrNull()
            val wantedType = when (category) {
                Category.PS3_GAMES -> 1; Category.PS3_DLCS -> 2; Category.PS3_THEMES -> 3
                Category.PS3_AVATARS -> 4; Category.PS3_DEMOS -> 5; else -> 0
            }
            if (type != null && type != 0 && type != wantedType) return
            val region = when (id.first()) { 'U' -> "US"; 'E' -> "EU"; 'J' -> "JP"; 'H' -> "ASIA"; else -> "INT" }
            val size = value("size").toLongOrNull()?.takeIf { it > 0 }
            accept(CatalogItem(category, id.substring(7, 16), id, region, title, value("url"), size, rap = value("rap")))
            accepted++
        }
        if (header === default) row(first)
        while (true) row(input.readLine() ?: break)
        return ParseStats(rows, accepted, malformed)
    }
    private fun fields(line: String): List<String>? {
        val result = mutableListOf<String>(); val field = StringBuilder()
        var quoted = false; var closed = false; var i = 0
        while (i < line.length) {
            val char = line[i++]
            when {
                quoted && char == '"' -> if (i < line.length && line[i] == '"') { field.append('"'); i++ } else { quoted = false; closed = true }
                quoted -> field.append(char)
                char == ',' -> { result += field.toString(); field.clear(); closed = false }
                char == '"' && field.isEmpty() && !closed -> quoted = true
                closed && !char.isWhitespace() -> return null
                char == '"' -> return null
                else -> field.append(char)
            }
        }
        if (quoted) return null
        return result + field.toString()
    }
}
