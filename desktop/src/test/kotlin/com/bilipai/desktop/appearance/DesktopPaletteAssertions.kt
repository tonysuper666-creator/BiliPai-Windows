package com.bilipai.desktop.appearance

import androidx.compose.material3.ColorScheme
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import top.yukonga.miuix.kmp.theme.Colors

/** Test-only value semantics for desktop palettes whose equals() is identity.
 * Every public Color getter returns its complete packed Long, including alpha
 * and color space. Do not compare rendered strings or reduce colors to ARGB. */
internal fun <T> assertDesktopPaletteEquals(expected: T, actual: T, message: String? = null) {
    if (expected is ColorScheme && actual is ColorScheme || expected is Colors && actual is Colors) {
        kotlin.test.assertEquals(desktopPaletteRoleValues(expected as Any), desktopPaletteRoleValues(actual as Any), message)
    } else {
        kotlin.test.assertEquals(expected, actual, message)
    }
}

private fun paletteGetters(type: Class<*>): List<Method> = type.methods.filter {
    Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) &&
        it.parameterCount == 0 && it.name.startsWith("get") && it.returnType == java.lang.Long.TYPE
}.sortedBy { it.name }.also { check(it.isNotEmpty()) { "Palette has no public Color getters: ${type.name}" } }

private val materialPaletteGetters = paletteGetters(ColorScheme::class.java)
private val miuixPaletteGetters = paletteGetters(Colors::class.java)

internal fun desktopPaletteRoleValues(value: Any): Map<String, Long> {
    val getters = when (value) {
        is ColorScheme -> materialPaletteGetters
        is Colors -> miuixPaletteGetters
        else -> error("Unsupported desktop palette: ${value.javaClass.name}")
    }
    return getters.associate { getter ->
        getter.name.removePrefix("get").substringBefore('-').replaceFirstChar { it.lowercaseChar() } to
            (getter.invoke(value) as Long)
    }.also { check(it.size == getters.size) { "Ambiguous desktop palette Color getters" } }
}
