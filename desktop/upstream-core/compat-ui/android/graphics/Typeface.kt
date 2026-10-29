package android.graphics

import java.io.File

/** Desktop android.graphics.Typeface over a Skia typeface. createFromFile fails for a file Skia cannot read as a font,
 * as Android does, so callers that load a font to validate it keep working. */
class Typeface private constructor(internal val skia: org.jetbrains.skia.Typeface) {
    companion object {
        @JvmStatic fun createFromFile(file: File): Typeface =
            org.jetbrains.skia.FontMgr.default.makeFromFile(file.absolutePath)?.let(::Typeface)
                ?: throw RuntimeException("Font file is not readable as a font: ${file.name}")
    }
}

/** The glyph lookup slice of android.graphics.Paint, answered by the Skia typeface. */
class Paint {
    var typeface: Typeface? = null
    fun hasGlyph(string: String): Boolean {
        val face = typeface?.skia ?: org.jetbrains.skia.Typeface.makeEmpty()
        return string.codePoints().toArray().all { face.getUTF32Glyph(it) != 0.toShort() }
    }
}
