/* Update discovery adapted from PKGj src/patchinfo.cpp.
 * Copyright 2018-2019 Philippe Daouadi; Copyright 2019-2020 Asakura Reiko.
 * SPDX-License-Identifier: BSD-2-Clause. See LICENSE.
 */
package org.pkgj.android.updates

import org.pkgj.android.catalog.CatalogItem
import org.pkgj.android.catalog.Category
import org.pkgj.android.download.DownloadType
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.DefaultHandler2
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.SAXParserFactory

object PatchUrls {
    fun validTitleId(titleId: String) = titleId.matches(Regex("[A-Z]{4}[0-9]{5}"))
    fun forTitle(titleId: String): String {
        require(validTitleId(titleId)) { "Invalid PS Vita Title ID" }
        val key = "e5e278aa1ee34082a088279c83f9bbc806821c52f2ab5d2b4abd995450355114"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val digest = mac.doFinal("np_$titleId".toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return "https://gs-sec.ww.np.dl.playstation.net/pl/np/$titleId/$digest/$titleId-ver.xml"
    }
}

data class GamePatch(val titleId: String, val version: String, val url: String,
    val size: Long?, val requiredFirmware: String?) {
    val identity get() = "update:$titleId:$version"
    fun downloadItem(game: CatalogItem): CatalogItem {
        require(game.category == Category.GAMES && game.titleId == titleId) { "Update Title ID does not match game" }
        require(PatchUrls.validTitleId(titleId) && PatchXml.validVersion(version)) { "Invalid update identity" }
        return game.copy(contentId = identity, url = url, size = size, zrif = "", version = version,
            downloadType = DownloadType.UPDATE)
    }
}

data class PatchCheck(val titleId: String, val checkedAt: Long, val patch: GamePatch?)
class PatchManifestException(cause: Exception) : IOException("Invalid update manifest", cause)

/** The last package in document order is authoritative, not the numerically highest version.
 * A hybrid belongs to its enclosing package and replaces its URL/size, not its version/firmware.
 */
object PatchXml {
    fun validVersion(value: String) = value.matches(Regex("[0-9]{1,4}(\\.[0-9]{1,4}){1,3}"))
    fun parse(input: InputStream, titleId: String): GamePatch? {
        require(PatchUrls.validTitleId(titleId)) { "Invalid PS Vita Title ID" }
        try {
            data class Package(val version: String, val firmware: String?, val url: String?, val size: String?,
                var hybridUrl: String? = null, var hybridSize: String? = null, var hybrid: Boolean = false)
            val path = mutableListOf<String>()
            var selected: Package? = null
            var current: Package? = null
            var rootSeen = false
            val handler = object : DefaultHandler2() {
                override fun startDTD(name: String?, publicId: String?, systemId: String?) { throw SAXException("DOCTYPE is not allowed") }
                override fun resolveEntity(publicId: String?, systemId: String?): InputSource { throw SAXException("External entities are not allowed") }
                override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource { throw SAXException("External entities are not allowed") }
                override fun error(e: SAXParseException) { throw e }
                override fun fatalError(e: SAXParseException) { throw e }
                override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
                    val name = localName.ifEmpty { qName }
                    path += name
                    if (path.size == 1) {
                        require(name == "titlepatch" && uri.isEmpty()) { "Unexpected manifest root" }
                        attributes.getValue("titleid")?.let { require(it == titleId) { "Manifest Title ID mismatch" } }
                        attributes.getValue("status")?.let { require(it == "alive") { "Unexpected manifest status" } }
                        rootSeen = true
                    }
                    require(!name.equals("error", true)) { "Manifest contains an error" }
                    if (name == "package") {
                        require(path == listOf("titlepatch", "tag", "package")) { "Unexpected package structure" }
                        current = Package(attributes.getValue("version").orEmpty(), attributes.getValue("psp2_system_ver"),
                            attributes.getValue("url"), attributes.getValue("size"))
                    } else if (name == "hybrid_package") {
                        require(path == listOf("titlepatch", "tag", "package", "hybrid_package")) { "Orphan hybrid package" }
                        val parent = requireNotNull(current)
                        require(!parent.hybrid) { "Ambiguous hybrid packages" }
                        parent.hybrid = true
                        parent.hybridUrl = attributes.getValue("url")
                        parent.hybridSize = attributes.getValue("size")
                    }
                }
                override fun endElement(uri: String, localName: String, qName: String) {
                    if (path.last() == "package") { selected = current; current = null }
                    path.removeAt(path.lastIndex)
                }
            }
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            val reader = factory.newSAXParser().xmlReader
            reader.contentHandler = handler; reader.entityResolver = handler; reader.errorHandler = handler
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
            reader.parse(InputSource(input))
            require(rootSeen) { "Missing manifest root" }
            val p = selected ?: return null
            require(validVersion(p.version)) { "Invalid patch version" }
            val url = (if (p.hybrid) p.hybridUrl else p.url)?.trim().orEmpty()
            val parsed = URI(url)
            require(parsed.scheme in listOf("http", "https") && !parsed.host.isNullOrEmpty() && parsed.rawUserInfo == null && parsed.fragment == null) { "Invalid patch URL" }
            val rawSize = if (p.hybrid) p.hybridSize else p.size
            val size = rawSize?.let { requireNotNull(it.toLongOrNull()?.takeIf { size -> size >= 0 }) { "Invalid patch size" } }
            val firmware = p.firmware?.let {
                val value = requireNotNull(it.toLongOrNull()?.takeIf { number -> number in 0..0xffffffffL }) { "Invalid firmware version" }
                "%x.%02x".format(value ushr 24, (value ushr 16) and 255)
            }
            return GamePatch(titleId, p.version, url, size, firmware)
        } catch (e: Exception) { throw PatchManifestException(e) }
    }
}

class PatchSource(private val configureConnection: (HttpURLConnection) -> Unit = {},
    private val manifestUrl: (String) -> String = PatchUrls::forTitle) {
    fun fetch(titleId: String): GamePatch? {
        require(PatchUrls.validTitleId(titleId)) { "Invalid PS Vita Title ID" }
        var url = URL(manifestUrl(titleId))
        for (redirect in 0..5) {
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 20_000; c.readTimeout = 20_000
            c.setRequestProperty("Accept-Encoding", "identity")
            try {
                configureConnection(c)
                when (val code = c.responseCode) {
                    404 -> return null
                    301, 302, 303, 307, 308 -> {
                        val next = URL(url, c.getHeaderField("Location") ?: throw IOException("Update redirect has no location"))
                        if (next.protocol !in listOf("http", "https") || (url.protocol == "https" && next.protocol != "https"))
                            throw IOException("Insecure update redirect")
                        url = next
                    }
                    200 -> {
                        val bytes = c.inputStream.use { it.readNBytesCompat(1024 * 1024) }
                        return PatchXml.parse(ByteArrayInputStream(bytes), titleId)
                    }
                    else -> throw IOException("Update check HTTP $code")
                }
            } finally { c.disconnect() }
        }
        throw IOException("Too many update redirects")
    }
    private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw IOException("Update manifest is too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

fun updateCheckError(error: Exception): String = when (error) {
    is javax.net.ssl.SSLException -> "Could not verify update server certificate"
    is java.net.SocketTimeoutException -> "Update check timed out"
    is java.net.UnknownHostException -> "Could not reach update server"
    else -> error.message ?: "Update check failed"
}
