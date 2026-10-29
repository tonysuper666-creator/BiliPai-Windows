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
