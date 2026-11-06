package android.graphics

/** Desktop stand-in for android.graphics.Color's static helpers (packed ARGB ints, HSV with hue in degrees). */
object Color {
    @JvmStatic fun alpha(color: Int): Int = color ushr 24
    @JvmStatic fun red(color: Int): Int = (color shr 16) and 0xFF
    @JvmStatic fun green(color: Int): Int = (color shr 8) and 0xFF
    @JvmStatic fun blue(color: Int): Int = color and 0xFF
    @JvmStatic fun rgb(red: Int, green: Int, blue: Int): Int = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    @JvmStatic fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    @JvmStatic fun argb(alpha: Float, red: Float, green: Float, blue: Float): Int =
        argb((alpha * 255f + 0.5f).toInt(), (red * 255f + 0.5f).toInt(), (green * 255f + 0.5f).toInt(), (blue * 255f + 0.5f).toInt())

    private val colorNames = mapOf("black" to 0xFF000000.toInt(), "darkgray" to 0xFF444444.toInt(), "gray" to 0xFF888888.toInt(),
        "lightgray" to 0xFFCCCCCC.toInt(), "white" to 0xFFFFFFFF.toInt(), "red" to 0xFFFF0000.toInt(), "green" to 0xFF00FF00.toInt(),
        "blue" to 0xFF0000FF.toInt(), "yellow" to 0xFFFFFF00.toInt(), "cyan" to 0xFF00FFFF.toInt(), "magenta" to 0xFFFF00FF.toInt(),
        "aqua" to 0xFF00FFFF.toInt(), "fuchsia" to 0xFFFF00FF.toInt(), "darkgrey" to 0xFF444444.toInt(), "grey" to 0xFF888888.toInt(),
        "lightgrey" to 0xFFCCCCCC.toInt(), "lime" to 0xFF00FF00.toInt(), "maroon" to 0xFF800000.toInt(), "navy" to 0xFF000080.toInt(),
        "olive" to 0xFF808000.toInt(), "purple" to 0xFF800080.toInt(), "silver" to 0xFFC0C0C0.toInt(), "teal" to 0xFF008080.toInt())

    /** Android's `#RRGGBB` / `#AARRGGBB` and named colors; anything else throws IllegalArgumentException("Unknown color"). */
    @JvmStatic fun parseColor(colorString: String): Int {
        if (colorString.isNotEmpty() && colorString[0] == '#') {
            val color = colorString.substring(1).toLongOrNull(16) ?: throw IllegalArgumentException("Unknown color")
            return when (colorString.length) {
                7 -> (color or 0x00000000FF000000L).toInt()
                9 -> color.toInt()
                else -> throw IllegalArgumentException("Unknown color")
            }
        }
        return colorNames[colorString.lowercase(java.util.Locale.ROOT)] ?: throw IllegalArgumentException("Unknown color")
    }

    /** [hsv] receives hue in 0..360, saturation and value in 0..1, as on Android. */
    @JvmStatic fun colorToHSV(color: Int, hsv: FloatArray) {
        require(hsv.size >= 3) { "3 components required for hsv" }
        java.awt.Color.RGBtoHSB(red(color), green(color), blue(color), hsv)
        hsv[0] *= 360f
    }

    @JvmStatic fun HSVToColor(hsv: FloatArray): Int {
        require(hsv.size >= 3) { "3 components required for hsv" }
        return java.awt.Color.HSBtoRGB(((hsv[0] % 360f + 360f) % 360f) / 360f, hsv[1].coerceIn(0f, 1f), hsv[2].coerceIn(0f, 1f))
    }
}
