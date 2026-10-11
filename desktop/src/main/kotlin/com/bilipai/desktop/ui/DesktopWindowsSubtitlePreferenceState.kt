package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.android.purebilibili.feature.video.subtitle.SubtitleDisplayMode
import com.android.purebilibili.feature.video.subtitle.SubtitlePreferenceSession
import com.android.purebilibili.feature.video.subtitle.resolveSubtitleDisplayModePreference

/** The original Holder's two saveable presentation states, in the real Nav entry registry.
 * The assembly token limits restoration to that retained owner. It grants no playback,
 * account or command authority; the existing leaf/bridge guards remain responsible for that.
 */
@Composable
internal fun rememberDesktopWindowsSubtitleMode(
    ownerSaveableKey: String,
    session: SubtitlePreferenceSession,
): MutableState<SubtitleDisplayMode> {
    val keySaver = remember(ownerSaveableKey) {
        Saver<MutableState<String?>, List<String?>>(
            save = { listOf(ownerSaveableKey, it.value) },
            restore = { saved ->
                if (saved.size == 2 && saved[0] == ownerSaveableKey) mutableStateOf(saved[1]) else null
            },
        )
    }
    val modeSaver = remember(ownerSaveableKey) {
        Saver<MutableState<SubtitleDisplayMode>, List<String>>(
            save = { listOf(ownerSaveableKey, it.value.name) },
            restore = { saved ->
                if (saved.size == 2 && saved[0] == ownerSaveableKey) {
                    SubtitleDisplayMode.entries.firstOrNull { it.name == saved[1] }?.let { mutableStateOf(it) }
                } else null
            },
        )
    }
    var sessionKey by rememberSaveable(ownerSaveableKey, saver = keySaver) { mutableStateOf<String?>(null) }
    val mode = rememberSaveable(ownerSaveableKey, saver = modeSaver) { mutableStateOf(SubtitleDisplayMode.OFF) }
    LaunchedEffect(ownerSaveableKey, session.key) {
        mode.value = resolveSubtitleDisplayModePreference(
            previousSessionKey = sessionKey,
            nextSessionKey = session.key,
            previousMode = mode.value,
            nextInitialMode = session.initialMode,
        )
        sessionKey = session.key
    }
    return mode
}

/** Original Section's per-subtitle-session large-text toggle in the same registry.
 * Saved presentation state may return only to the retained assembly and session. */
@Composable
internal fun rememberDesktopWindowsSubtitleLargeText(
    ownerSaveableKey: String,
    session: SubtitlePreferenceSession,
): MutableState<Boolean> {
    val saver = remember(ownerSaveableKey, session.key) {
        Saver<MutableState<Boolean>, List<String>>(
            save = { listOf(ownerSaveableKey, session.key, it.value.toString()) },
            restore = { saved ->
                if (saved.size == 3 && saved[0] == ownerSaveableKey && saved[1] == session.key) {
                    saved[2].toBooleanStrictOrNull()?.let { mutableStateOf(it) }
                } else null
            },
        )
    }
    return rememberSaveable(ownerSaveableKey, session.key, saver = saver) { mutableStateOf(false) }
}
