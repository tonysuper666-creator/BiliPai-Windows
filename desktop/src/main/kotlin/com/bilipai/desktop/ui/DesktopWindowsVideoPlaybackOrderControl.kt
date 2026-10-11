package com.bilipai.desktop.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.text.style.TextOverflow
import com.android.purebilibili.core.store.DesktopOriginalVideoControlSettings
import com.android.purebilibili.feature.video.ui.overlay.PlaybackOrderSelectionSheet
import com.android.purebilibili.feature.video.ui.overlay.resolvePlaybackOrderDisplayLabel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The complete original five-option UI inside the existing owned queue window.
 * This borrows the same Store and accepted publication; it does not own a queue,
 * player, completion strategy or shuffle setting.
 */
@Composable
internal fun DesktopWindowsVideoPlaybackOrderControl(
    settings: DesktopOriginalPlayerSettingsContext,
    sourceOwner: DesktopOriginalVideoAcceptedPublication,
    stillOwned: () -> Boolean,
    admit: ((() -> Unit) -> Boolean),
    feedback: (String) -> Unit,
) {
    key(settings, sourceOwner) {
        val latestOwned by rememberUpdatedState(stillOwned)
        val latestAdmission by rememberUpdatedState(admit)
        val latestFeedback by rememberUpdatedState(feedback)
        fun owned() = latestOwned() && settings.isCurrentForOriginalWrite()
        // Store's final permit rechecks the same queue/source/factory admission,
        // including the getter's original mirror healing and suspended writes.
        val context = remember(settings, sourceOwner) {
            DesktopOriginalPlayerSettingsContext(settings.pluginContext, ::owned, { action ->
                var applied = false
                owned() && latestAdmission {
                    if (owned()) { action(); applied = true }
                } && applied
            })
        }
        val completion by remember(context) {
            DesktopOriginalVideoControlSettings.getPlaybackCompletionBehavior(context)
        }.collectAsState(DesktopOriginalVideoControlSettings.getPlaybackCompletionBehaviorSync(context))
        var menu by remember { mutableStateOf<AtomicBoolean?>(null) }
        TextButton(enabled = owned(), onClick = {
            if (owned()) { menu?.set(false); menu = AtomicBoolean(true) }
        }) {
            Text("播放顺序：${resolvePlaybackOrderDisplayLabel(completion, compact = true)}",
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        menu?.takeIf { it.get() && owned() }?.let { alive -> key(alive) {
            val scope = rememberCoroutineScope()
            var writing by remember { mutableStateOf(false) }
            fun menuOwned() = alive.get() && owned()
            fun dismiss() { alive.set(false); if (menu === alive) menu = null }
            DisposableEffect(alive) { onDispose { alive.set(false) } }
            PlaybackOrderSelectionSheet(
                currentBehavior = completion,
                onSelect = { behavior ->
                    if (menuOwned() && !writing) {
                        writing = true
                        scope.launch {
                            try {
                                DesktopOriginalPlaybackPreferenceOperation.run(context, ::menuOwned) {
                                    DesktopOriginalVideoControlSettings.setPlaybackCompletionBehavior(context, behavior)
                                }
                                if (menuOwned()) dismiss()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                if (menuOwned()) latestFeedback("播放顺序未能保存，请重试")
                            } finally {
                                writing = false
                            }
                        }
                    }
                },
                onDismiss = ::dismiss,
            )
        } }
    }
}
