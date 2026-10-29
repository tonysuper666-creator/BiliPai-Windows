package androidx.compose.ui.text.font

/** Android Compose's FontFamily(android.graphics.Typeface), backed by the same Skia typeface on the desktop. */
fun FontFamily(typeface: android.graphics.Typeface): FontFamily =
    FontFamily(androidx.compose.ui.text.platform.Typeface(typeface.skia))
