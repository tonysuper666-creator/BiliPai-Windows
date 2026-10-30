package com.bilipai.desktop.backup

import okhttp3.ResponseBody

/**
 * Desktop platform interface of the upstream WebDAV backup service. Upstream packs Android DataStore and
 * SharedPreferences files through a Context; the desktop packs and restores its own settings documents.
 * [DesktopBackupArchive] is the real implementation.
 */
interface WebDavBackupArchivePort {
    fun create(nowEpochMs: Long = System.currentTimeMillis()): ByteArray
    fun restore(bytes: ByteArray, onSuccessfulRestore: () -> Unit = {}): Int
    fun requireSameOrigin(base: String, download: String)
    fun readDownload(body: ResponseBody): ByteArray
}
