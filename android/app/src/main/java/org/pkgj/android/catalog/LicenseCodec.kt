/* zRIF dictionary layout adapted from this repository's src/zrif.cpp.
 * Copyright 2018-2019 Philippe Daouadi; Copyright 2019-2020 Asakura Reiko.
 * Android adaptation Copyright 2026. SPDX-License-Identifier: BSD-2-Clause.
 * See the repository LICENSE. */
package org.pkgj.android.catalog

import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Decodes a PSV catalog zRIF into the 512-byte RIF accepted by Vita3K's file picker. */
object LicenseCodec {
    internal val dictionary = ByteArray(1024).apply {
        val tail = "3030303039000000000000000000000030303030363030303037303030303800303030303330303030343030303035305f30302d414444434f4e5430303030322d5043534730303030303030303030312d504353453030302d504353463030302d504353433030302d504353443030302d504353413030302d504353423030300001000100010002efcdab8967452301"
        tail.chunked(2).forEachIndexed { index, hex -> this[880 + index] = hex.toInt(16).toByte() }
    }

    fun decode(zrif: String, contentId: String): ByteArray {
        require(zrif.isNotBlank()) { "This catalog entry has no license" }
        val compressed = try { Base64.getDecoder().decode(zrif) }
            catch (_: IllegalArgumentException) { throw IllegalArgumentException("Catalog license is invalid") }
        require(compressed.size <= 2048) { "Catalog license is invalid" }
        val inflater = Inflater()
        val output = ByteArray(1025)
        try {
            inflater.setInput(compressed)
            var count = 0
            while (!inflater.finished()) {
                val n = try { inflater.inflate(output, count, output.size - count) }
                    catch (_: DataFormatException) { throw IllegalArgumentException("Catalog license is invalid") }
                count += n
                if (inflater.needsDictionary()) inflater.setDictionary(dictionary)
                else if (n == 0 || count == output.size) throw IllegalArgumentException("Catalog license is invalid")
            }
            // Upstream zRIFs may carry one or two trailing bytes after a complete
            // zlib stream. Vita PKGj accepts them; the inflater has already
            // verified the stream checksum before reaching finished().
            require(count == 512 && inflater.remaining in 0..2) { "Catalog license is invalid" }
            val id = contentId.toByteArray(Charsets.US_ASCII)
            require(id.isNotEmpty() && id.size <= 48 && (matches(output, 0x10, id) || matches(output, 0x50, id))) {
                "Catalog license does not match this package"
            }
            return output.copyOf(512)
        } finally { inflater.end() }
    }

    private fun matches(bytes: ByteArray, offset: Int, id: ByteArray): Boolean =
        id.indices.all { bytes[offset + it] == id[it] }
}
