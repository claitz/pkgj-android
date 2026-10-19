/* Android adaptation Copyright 2026. SPDX-License-Identifier: BSD-2-Clause.
 * See the repository LICENSE. */
package org.pkgj.android.compatibility

import org.json.JSONArray
import org.pkgj.android.catalog.Platform
import org.pkgj.android.catalog.Category
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedReader
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL

data class CompatibilityReport(
    val titleId: String,
    val issueId: Long,
    val status: String,
    val updatedAt: Long,
    val platform: Platform = Platform.VITA
) {
    val key get() = CompatibilityKey(platform, titleId)
}

data class CompatibilityKey(val platform: Platform, val titleId: String)
val Category.hasCompatibility get() = this == Category.GAMES || this == Category.PS3_GAMES

fun compatibilityUrl(titleId: String, platform: Platform, report: CompatibilityReport?): String {
    val id = java.net.URLEncoder.encode(titleId, "UTF-8")
    return if (platform == Platform.PS3) "https://rpcs3.net/compatibility?g=$id"
    else report?.let { "https://github.com/Vita3K/compatibility/issues/${it.issueId}" }
        ?: "https://github.com/Vita3K/compatibility/issues?q=$id"
}

data class CompatibilityDatabase(val updatedAt: String, val reports: List<CompatibilityReport>)
data class CompatibilityDownload(val database: CompatibilityDatabase, val etag: String?, val lastModified: String?)

object CompatibilityValidators {
    fun unchanged(etag: String?, lastModified: String?, cachedEtag: String?, cachedLastModified: String?): Boolean =
        (etag != null && cachedEtag != null && etag == cachedEtag) ||
            ((etag == null || cachedEtag == null) && lastModified != null && lastModified == cachedLastModified)
}

object CompatibilityXml {
    val statuses = setOf("Nothing", "Bootable", "Intro", "Menu", "Ingame -", "Ingame +", "Playable")

    fun parse(reader: Reader, labelNames: Map<String, String>): CompatibilityDatabase {
        val reports = mutableListOf<CompatibilityReport>()
        var updated = ""
        var titleId = ""
        var issueId = 0L
        var reportUpdatedAt = 0L
        var labels = mutableListOf<String>()
        var rootFound = false
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        val input = BufferedReader(reader)
        input.mark(1)
        if (input.read() != '\uFEFF'.code) input.reset()
        parser.setInput(input)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            require(event != XmlPullParser.DOCDECL) { "DOCTYPE is not allowed" }
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "compatibility" -> {
                        rootFound = true
                        updated = parser.getAttributeValue(null, "iso_db_updated_at").orEmpty()
                    }
                    "app" -> {
                        titleId = parser.getAttributeValue(null, "title_id")?.uppercase().orEmpty()
                        issueId = 0L
                        reportUpdatedAt = 0L
                        labels = mutableListOf()
                    }
                    "issue_id" -> issueId = parser.nextText().trim().toLongOrNull() ?: 0L
                    "label" -> labels += parser.nextText().trim()
                    "updated_at" -> reportUpdatedAt = parser.nextText().trim().toLongOrNull() ?: 0L
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "app") {
                val status = labels.mapNotNull(labelNames::get).filter(statuses::contains).distinct().joinToString(" / ")
                if (titleId.isNotBlank() && issueId > 0 && status.isNotBlank())
                    reports += CompatibilityReport(titleId, issueId, status, reportUpdatedAt)
            }
            event = parser.next()
        }
        require(rootFound && updated.isNotBlank() && reports.isNotEmpty()) { "Invalid compatibility database" }
        return CompatibilityDatabase(updated, reports.distinctBy { it.titleId })
    }
}

object CompatibilityDataSource {
    private const val DATABASE_URL = "https://github.com/Vita3K/compatibility/releases/download/compat_db/app_compat_db.xml"
    private const val LABELS_URL = "https://api.github.com/repos/Vita3K/compatibility/labels?per_page=100"

    fun checkForUpdates(cachedEtag: String?, cachedLastModified: String?): CompatibilityDownload? {
        val head = open(DATABASE_URL, "HEAD")
        val etag = head.getHeaderField("ETag")
        val lastModified = head.getHeaderField("Last-Modified")
        head.disconnect()
        if (CompatibilityValidators.unchanged(etag, lastModified, cachedEtag, cachedLastModified)) return null

        val labelsConnection = open(LABELS_URL, "GET")
        val labelNames = try {
            val json = JSONArray(labelsConnection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            buildMap {
                for (i in 0 until json.length()) {
                    val label = json.getJSONObject(i)
                    put(label.getLong("id").toString(), label.getString("name"))
                }
            }
        } finally {
            labelsConnection.disconnect()
        }
        val connection = open(DATABASE_URL, "GET")
        return try {
            val database = connection.inputStream.bufferedReader(Charsets.UTF_8).use { CompatibilityXml.parse(it, labelNames) }
            CompatibilityDownload(database, etag, lastModified)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(address: String, method: String): HttpURLConnection {
        var current = URL(address)
        repeat(6) {
            val connection = current.openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "PKGj-Android")
            val code = connection.responseCode
            if (code in 300..399) {
                val next = URL(current, connection.getHeaderField("Location") ?: error("Redirect without Location"))
                if (current.protocol == "https" && next.protocol != "https") error("Insecure redirect")
                connection.disconnect()
                current = next
            } else {
                if (code != HttpURLConnection.HTTP_OK) {
                    connection.disconnect()
                    error("Compatibility HTTP $code")
                }
                return connection
            }
        }
        error("Too many compatibility redirects")
    }
}
