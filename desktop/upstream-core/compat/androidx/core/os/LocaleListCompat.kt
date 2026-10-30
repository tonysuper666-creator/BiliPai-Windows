package androidx.core.os

/**
 * Pure value stand-in for androidx.core.os.LocaleListCompat: an ordered list of BCP 47 language tags.
 * It carries no platform state; applying a list to the app language is the desktop language runtime's job.
 */
class LocaleListCompat private constructor(private val tags: List<String>) {
    val isEmpty: Boolean get() = tags.isEmpty()

    fun toLanguageTags(): String = tags.joinToString(",")

    override fun equals(other: Any?): Boolean = other is LocaleListCompat && other.tags == tags

    override fun hashCode(): Int = tags.hashCode()

    override fun toString(): String = "[" + toLanguageTags() + "]"

    companion object {
        @JvmStatic
        fun getEmptyLocaleList(): LocaleListCompat = LocaleListCompat(emptyList())

        @JvmStatic
        fun forLanguageTags(languageTags: String?): LocaleListCompat =
            LocaleListCompat(languageTags.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() })
    }
}
