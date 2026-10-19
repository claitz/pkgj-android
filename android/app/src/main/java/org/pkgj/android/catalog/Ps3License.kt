package org.pkgj.android.catalog

/** A supplied PS3 RAP is exactly 16 bytes; no console-specific RIF is generated. */
object Ps3License {
    fun validContentId(value: String) = value.matches(Regex("[A-Z]{2}[0-9]{4}-[A-Z0-9]{9}_[0-9]{2}-[A-Z0-9]{16}"))
    fun filename(contentId: String): String {
        require(validContentId(contentId)) { "Invalid PS3 content ID" }
        return "$contentId.rap"
    }
    fun decode(hex: String): ByteArray {
        require(hex.matches(Regex("[0-9a-fA-F]{32}"))) { "Invalid RAP" }
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
