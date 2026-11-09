package android.net

import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class Uri private constructor(private val raw: String) {
    val scheme: String? get() = runCatching { URI(raw).scheme }.getOrNull()
    val host: String? get() = runCatching { URI(raw).host }.getOrNull()
    val path: String? get() = runCatching { URI(raw).path }.getOrNull()
    val lastPathSegment: String? get() = path?.trimEnd('/')?.substringAfterLast('/')
    val isHierarchical: Boolean get() = runCatching { !URI(raw).isOpaque }.getOrDefault(false)

    fun getQueryParameter(key: String): String? {
        if (!isHierarchical) throw UnsupportedOperationException("This isn't a hierarchical URI.")
        val query = URI(raw).rawQuery ?: return null
        val encodedKey = encode(key)
        val parameter = query.splitToSequence('&').firstOrNull {
            it.substringBefore('=') == encodedKey
        } ?: return null
        // Decode only the selected value, so escaped '&' and '=' remain inside it.
        return URLDecoder.decode(parameter.substringAfter('=', ""), Charsets.UTF_8)
    }

    fun toFile(): File? = if (scheme == "file" || scheme == null) File(runCatching { URI(raw).path }.getOrNull() ?: raw) else null
    override fun toString() = raw
    override fun equals(other: Any?) = other is Uri && other.raw == raw
    override fun hashCode() = raw.hashCode()
    companion object {
        @JvmStatic fun parse(s: String) = Uri(s)
        @JvmStatic fun fromFile(f: File) = Uri(f.toURI().toString())
        @JvmStatic fun encode(s: String?): String? = s?.let { URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }
    }
}
