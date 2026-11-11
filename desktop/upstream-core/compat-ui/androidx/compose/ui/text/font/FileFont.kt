package androidx.compose.ui.text.font

import java.io.File

/** Keep Android's file-font call site while preserving desktop variable-font settings. */
fun Font(
    file: File,
    weight: FontWeight = FontWeight.Normal,
    style: FontStyle = FontStyle.Normal,
    variationSettings: FontVariation.Settings = FontVariation.Settings(),
): Font = androidx.compose.ui.text.platform.Font(file, weight, style, variationSettings)
