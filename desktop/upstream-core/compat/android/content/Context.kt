package android.content

import java.io.File

/**
 * Desktop stand-in for the slice of android.content.Context used by upstream shared code. There is no
 * default storage: the desktop adapter (com.bilipai.desktop.plugins.asAndroidContext) decides where
 * preferences live.
 */
abstract class Context(val dataDir: File) {
    open val applicationContext: Context get() = this
    open val filesDir: File get() = File(dataDir, "files").apply { mkdirs() }
    open val cacheDir: File get() = File(dataDir, "cache").apply { mkdirs() }
    open val contentResolver: ContentResolver = ContentResolver()
    abstract fun getSharedPreferences(name: String, mode: Int): SharedPreferences
    companion object { const val MODE_PRIVATE = 0 }
}

/** Local files only (what the desktop file pickers hand out). Other schemes fail loudly instead of reading as "no data". */
class ContentResolver {
    fun openInputStream(uri: android.net.Uri): java.io.InputStream = localFile(uri).inputStream()
    fun getType(uri: android.net.Uri): String? = java.nio.file.Files.probeContentType(localFile(uri).toPath())
    @Suppress("UNUSED_PARAMETER")
    fun query(uri: android.net.Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): android.database.Cursor =
        android.database.Cursor.ofFile(localFile(uri))
    private fun localFile(uri: android.net.Uri): File = uri.toFile()
        ?: throw com.bilipai.desktop.compat.UnsupportedDesktopCapability("Content URI $uri", "the desktop only reads local file URIs")
}
