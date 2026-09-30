package com.bilipai.desktop.appearance

import androidx.compose.runtime.staticCompositionLocalOf
import com.android.purebilibili.feature.settings.AppLanguage
import com.android.purebilibili.feature.settings.resolveAppLanguageLocaleTags
import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Original Android string resources, selected through the original AppLanguage policy. */
class DesktopStrings(val languageTag: String) {
    private val translated: Map<String, String> = tables[languageTag] ?: tables.getValue("zh-CN")
    operator fun get(key: String): String = translated[key] ?: tables.getValue("zh-CN")[key]
        ?: error("Unknown upstream string resource: $key")
    fun format(key: String, vararg arguments: Any): String = String.format(Locale.forLanguageTag(languageTag), get(key), *arguments)
    fun contains(key: String): Boolean = key in tables.getValue("zh-CN")
    companion object {
        private val tables by lazy {
            listOf("zh-CN", "zh-TW", "en").associateWith { language ->
                val builder = DocumentBuilderFactory.newInstance().apply {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                    setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                    setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                    isXIncludeAware = false
                }.newDocumentBuilder()
                val stream = DesktopStrings::class.java.getResourceAsStream("/bilipai-strings/$language/strings.xml")
                    ?: error("Missing original $language string resources")
                val nodes = stream.use { builder.parse(it) }.getElementsByTagName("string")
                buildMap {
                    repeat(nodes.length) { index ->
                        val node = nodes.item(index)
                        val name = node.attributes.getNamedItem("name").nodeValue
                        check(name !in this) { "Duplicate string resource: $name" }
                        put(name, decodeAndroidString(node.textContent))
                    }
                }
            }
        }
        fun forLanguage(language: AppLanguage, systemTags: List<String> = WindowsUiLanguage.preferredTags()): DesktopStrings =
            DesktopStrings(resolveDesktopResourceLanguage(language, systemTags))
    }
}

val LocalDesktopStrings = staticCompositionLocalOf { DesktopStrings.forLanguage(AppLanguage.FOLLOW_SYSTEM) }

internal fun resolveDesktopResourceLanguage(language: AppLanguage, systemTags: List<String>): String {
    val explicit = resolveAppLanguageLocaleTags(language)
    for (tag in explicit.ifEmpty { systemTags }) {
        val locale = Locale.forLanguageTag(tag)
        if (locale.language == "en") return "en"
        if (locale.language == "zh") return if (locale.script.equals("Hant", true) || locale.country in listOf("TW", "HK", "MO")) "zh-TW" else "zh-CN"
    }
    // Android's default values/ resource is Simplified Chinese, even for unsupported system languages.
    return "zh-CN"
}

internal fun decodeAndroidString(raw: String): String {
    val output = StringBuilder(); var index = 0
    while (index < raw.length) {
        val ch = raw[index++]
        if (ch == '\\' && index < raw.length) {
            output.append(when (val escaped = raw[index++]) { 'n' -> '\n'; 't' -> '\t'; '\'' -> '\''; '"' -> '"'; '\\' -> '\\'; else -> escaped })
        } else output.append(ch)
    }
    return output.toString()
}

object WindowsUiLanguage {
    private interface Kernel32Languages : StdCallLibrary {
        fun GetUserPreferredUILanguages(flags: Int, number: IntByReference, buffer: CharArray?, characters: IntByReference): Boolean
    }
    fun preferredTags(): List<String> {
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            try {
                val api = Native.load("kernel32", Kernel32Languages::class.java)
                val count = IntByReference(); val size = IntByReference()
                if (api.GetUserPreferredUILanguages(8, count, null, size) && size.value in 2..32768) {
                    val buffer = CharArray(size.value)
                    if (api.GetUserPreferredUILanguages(8, count, buffer, size)) {
                        val result = String(buffer).split('\u0000').filter { it.isNotBlank() }
                        if (result.isNotEmpty()) return result
                    }
                }
            } catch (_: UnsatisfiedLinkError) { }
            catch (_: RuntimeException) { }
        }
        return listOf(Locale.getDefault(Locale.Category.DISPLAY).toLanguageTag())
    }
}

/** App-local language state; no change to Windows regional/date settings. */
object DesktopLanguageRuntime {
    val requested = MutableStateFlow(AppLanguage.FOLLOW_SYSTEM)
    fun apply(language: AppLanguage) { requested.value = language }
}
