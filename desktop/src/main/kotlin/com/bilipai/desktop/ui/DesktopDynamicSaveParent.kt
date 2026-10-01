package com.bilipai.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import java.awt.Component

/** Existing Root Window for the single image/comment save chooser. */
internal val LocalDesktopDynamicSaveParent = staticCompositionLocalOf<Component?> { null }
