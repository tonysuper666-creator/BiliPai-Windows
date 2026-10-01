// Original source app/src/main/java/com/android/purebilibili/core/ui/wallpaper/WallpaperMedia.kt
// LF SHA256 8f6f22e673a0b8d11455b2c4762ec72ce98ea5ff68eb270d4f021748c289324b
package com.android.purebilibili.core.ui.wallpaper
import androidx.compose.runtime.*

internal fun isVideoWallpaper(uri: String): Boolean =
    uri.substringBefore('?').substringBefore('#').substringAfterLast('.').lowercase() in
        setOf("mp4", "m4v", "webm", "mkv", "mov", "3gp", "video")

/** Shared image/GIF/video renderer. Video uses a TextureView so Compose clipping works. */
