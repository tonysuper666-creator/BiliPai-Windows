package com.bilipai.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.android.purebilibili.feature.space.SpaceUiState
import kotlinx.coroutines.flow.StateFlow

/** Required view of the same original Space entry owner. The full original UI state
 * is selected without changing its schema. This port constructs no Store/HTTP/VM.
 * Root must cache the mid-specific owner under the current video entry child scope
 * and reject its completion after account/entry retirement.
 */
internal interface DesktopOriginalOwnerUploadsPort {
    val uiState: StateFlow<SpaceUiState>
    fun loadSpaceInfo(mid: Long)
}

internal interface DesktopOriginalTabletAudioPlatform {
    fun ownerUploads(mid: Long): DesktopOriginalOwnerUploadsPort
}

internal val LocalDesktopOriginalTabletAudioPlatform =
    staticCompositionLocalOf<DesktopOriginalTabletAudioPlatform> {
        error("The complete original Tablet/Audio renderer needs its same-entry Root owners")
    }
