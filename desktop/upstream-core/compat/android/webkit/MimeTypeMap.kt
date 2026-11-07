package android.webkit

/** File-extension <-> MIME type table (what upstream asks of Android's MimeTypeMap for picked image files). */
class MimeTypeMap private constructor() {
    fun getMimeTypeFromExtension(extension: String?): String? = extension?.lowercase()?.let(byExtension::get)
    fun getExtensionFromMimeType(mimeType: String?): String? = mimeType?.lowercase()?.let(byMime::get)
    fun hasExtension(extension: String?): Boolean = getMimeTypeFromExtension(extension) != null
    fun hasMimeType(mimeType: String?): Boolean = getExtensionFromMimeType(mimeType) != null

    companion object {
        private val byExtension = mapOf(
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
            "webp" to "image/webp", "bmp" to "image/bmp", "heic" to "image/heic", "heif" to "image/heif",
            "avif" to "image/avif", "mp4" to "video/mp4", "webm" to "video/webm", "mkv" to "video/x-matroska",
        )
        private val byMime = byExtension.entries.reversed().associate { (ext, mime) -> mime to ext }
        private val instance = MimeTypeMap()
        @JvmStatic fun getSingleton(): MimeTypeMap = instance
        @JvmStatic fun getFileExtensionFromUrl(url: String?): String =
            url?.substringBefore('#')?.substringBefore('?')?.substringAfterLast('/')?.substringAfterLast('.', "")?.lowercase().orEmpty()
    }
}
