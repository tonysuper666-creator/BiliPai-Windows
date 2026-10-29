package android.content.res

import java.util.Locale

/**
 * Desktop stand-in for the string slice of android.content.res.Resources. Strings come from the tables generated at
 * build time from the upstream app's res/values, values-en and values-zh-rTW strings.xml (`upstream-strings/<locale>.tsv`
 * on the classpath) and follow the system language with Android's fallback: an English locale reads values-en, zh-TW
 * reads values-zh-rTW, anything else (and any string a translation lacks) reads the default Simplified Chinese values.
 * An unknown id, or a build without the tables, fails loudly instead of showing an empty label.
 */
class Resources internal constructor(private val locale: () -> Locale) {
    fun getString(id: Int): String = DesktopStringTables.lookup(id, locale())
    fun getString(id: Int, vararg formatArgs: Any?): String = String.format(locale(), getString(id), *formatArgs)
    fun getText(id: Int): CharSequence = getString(id)

    companion object {
        /** Follows the JVM default locale, which the desktop runtime takes from the system language. */
        @JvmStatic val system = Resources { Locale.getDefault() }
    }
}

internal object DesktopStringTables {
    private val tables = mutableMapOf<String, Map<Int, String>>()

    /** Android's resource fallback for the three qualifiers upstream ships. */
    fun chain(locale: Locale): List<String> = when {
        locale.language == "en" -> listOf("en", "default")
        locale.language == "zh" && (locale.country == "TW" || (locale.country.isEmpty() && locale.script == "Hant")) -> listOf("zh-TW", "default")
        else -> listOf("default")
    }

    fun lookup(id: Int, locale: Locale): String = chain(locale).firstNotNullOfOrNull { table(it)[id] }
        ?: throw IllegalArgumentException("No string resource 0x${id.toString(16)} in the generated desktop string tables")

    @Synchronized
    private fun table(name: String): Map<Int, String> = tables.getOrPut(name) {
        val stream = Resources::class.java.getResourceAsStream("/upstream-strings/$name.tsv")
            ?: throw IllegalStateException("upstream-strings/$name.tsv is missing: the desktop build generates it (generateUpstreamStringResources)")
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotEmpty() }.associate { line ->
                val tab = line.indexOf('\t')
                line.substring(0, tab).removePrefix("0x").toInt(16) to decode(line.substring(tab + 1))
            }
        }
    }

    private fun decode(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val c = text[index++]
            if (c != '\\') { out.append(c); continue }
            when (val e = text[index++]) { 'n' -> out.append('\n'); 't' -> out.append('\t'); '\\' -> out.append('\\'); else -> error("bad escape \\$e") }
        }
        return out.toString()
    }
}
