package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.android.purebilibili.core.ui.AppContentColors
import com.android.purebilibili.core.ui.AppSpacingTokens
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.components.PageAwareAsyncImage

/**
 * Windows binding of the v0.4.6 self entry of the dynamic follow strip. The upstream composable lives in the
 * Android sidebar file; this one keeps its contract: a local person glyph until the real avatar has loaded,
 * the avatar clipped to the circle, and the semantic label of the self entry.
 */
@Composable
internal fun DesktopDynamicSelfAvatar(face: String, isSelected: Boolean) {
    val faceUrl = remember(face) { resolveDesktopDynamicSelfAvatarUrl(face) }
    var avatarLoaded by remember(faceUrl) { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxSize().semantics { contentDescription = "我" },
        contentAlignment = Alignment.Center,
    ) {
        if (!avatarLoaded) {
            AppIcon(
                imageVector = Icons.Outlined.Person,
                contentDescription = null,
                tint = if (isSelected) AppContentColors.accent() else AppContentColors.secondaryText(),
                modifier = Modifier.size(AppSpacingTokens.ExtraLarge),
            )
        }
        if (faceUrl.isNotEmpty()) {
            PageAwareAsyncImage(
                model = faceUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = ContentScale.Crop,
                onSuccess = { avatarLoaded = true },
            )
        }
    }
}

/** Same normalisation as the upstream sidebar avatar URL: HTTPS, protocol-relative and bare hosts. */
internal fun resolveDesktopDynamicSelfAvatarUrl(face: String): String {
    val raw = face.trim()
    return when {
        raw.isEmpty() -> ""
        raw.startsWith("https://") -> raw
        raw.startsWith("http://") -> raw.replace("http://", "https://")
        raw.startsWith("//") -> "https:$raw"
        else -> "https://$raw"
    }
}
