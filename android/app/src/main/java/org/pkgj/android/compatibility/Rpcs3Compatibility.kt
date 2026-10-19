/* Android adaptation Copyright 2026. SPDX-License-Identifier: BSD-2-Clause.
 * Protocol reference: RPCS3/rpcs3 rpcs3qt/game_compatibility.cpp.
 * Independent implementation; no RPCS3 source is included. */
package org.pkgj.android.compatibility

import org.json.JSONObject
import org.pkgj.android.catalog.Platform
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

object Rpcs3CompatibilityJson {
    val statuses = listOf("Playable", "Ingame", "Intro", "Loadable", "Nothing")
    private val titleId = Regex("[A-Z]{4}[0-9]{5}")

    fun parse(reader: Reader, fetchedAt: Instant = Instant.now()): CompatibilityDatabase {
        // The export also contains patch metadata that we don't use. Bound the entire response.
        val text = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            require(text.length + count <= 16 * 1024 * 1024) { "Compatibility database too large" }
            text.append(buffer, 0, count)
        }
        val root = JSONObject(text.toString().removePrefix("\uFEFF"))
        require(root.has("return_code") && root.getInt("return_code") == 0) { "RPCS3 database unavailable" }
        val results = root.getJSONObject("results")
        val reports = buildList {
            for (id in results.keys()) {
                if (!titleId.matches(id)) continue
                val result = results.optJSONObject(id) ?: continue
                val status = result.optString("status")
                if (status !in statuses) continue
                val updated = runCatching {
                    LocalDate.parse(result.optString("date")).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
                }.getOrDefault(0L)
                add(CompatibilityReport(id, 0, status, updated, Platform.PS3))
            }
        }
        require(reports.isNotEmpty()) { "Invalid RPCS3 compatibility database" }
        // The API provides report dates, but no database revision date.
        return CompatibilityDatabase(fetchedAt.atOffset(ZoneOffset.UTC).toLocalDate().toString(), reports)
    }
}

class Rpcs3CompatibilityDataSource(private val address: String = DATABASE_URL) {
    companion object {
        const val DATABASE_URL = "https://rpcs3.net/compatibility?api=v1&export"
    }

    fun checkForUpdates(etag: String?, lastModified: String?): CompatibilityDownload? {
        var current = URL(address)
        repeat(6) {
            val connection = current.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 20_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "PKGj-Android")
                connection.setRequestProperty("Accept", "application/json")
                etag?.let { connection.setRequestProperty("If-None-Match", it) }
                lastModified?.let { connection.setRequestProperty("If-Modified-Since", it) }
                val code = connection.responseCode
                when {
                    code == HttpURLConnection.HTTP_NOT_MODIFIED -> {
                        require(etag != null || lastModified != null) { "Unexpected unmodified response" }
                        return null
                    }
                    code in listOf(301, 302, 303, 307, 308) -> {
                        val next = URL(current, connection.getHeaderField("Location") ?: error("Missing redirect"))
                        require(next.protocol in listOf("http", "https") && !(current.protocol == "https" && next.protocol != "https")) { "Insecure redirect" }
                        current = next
                    }
                    code == HttpURLConnection.HTTP_OK -> {
                        val database = connection.inputStream.bufferedReader(Charsets.UTF_8).use(Rpcs3CompatibilityJson::parse)
                        return CompatibilityDownload(database, connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"))
                    }
                    else -> error("RPCS3 compatibility HTTP $code")
                }
            } finally { connection.disconnect() }
        }
        error("Too many compatibility redirects")
    }
}
