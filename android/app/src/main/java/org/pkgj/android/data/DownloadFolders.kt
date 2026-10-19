package org.pkgj.android.data

import android.content.SharedPreferences
import org.pkgj.android.catalog.Platform

/** A selected tree may still be referenced by old jobs after a preference is cleared. */
class DownloadFolders(private val preferences: SharedPreferences) {
    init {
        if (!preferences.getBoolean("platformFoldersV1", false)) {
            val edit = preferences.edit()
            preferences.getString("exportFolder", null)?.let { legacy ->
                Platform.entries.forEach { platform ->
                    if (!preferences.contains(key(platform))) edit.putString(key(platform), legacy)
                }
            }
            edit.remove("exportFolder").putBoolean("platformFoldersV1", true).apply()
        }
    }

    fun read(): Map<Platform, String> = buildMap {
        Platform.entries.forEach { platform -> preferences.getString(key(platform), null)?.let { put(platform, it) } }
    }
    fun set(platform: Platform, uri: String?) {
        preferences.edit().apply { if (uri == null) remove(key(platform)) else putString(key(platform), uri) }.apply()
    }
    private fun key(platform: Platform) = "downloadFolder_${platform.name}"
}
