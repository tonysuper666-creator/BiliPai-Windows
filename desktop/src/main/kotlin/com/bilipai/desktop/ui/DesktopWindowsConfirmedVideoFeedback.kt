@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.motion.rememberSystemReduceMotion
import com.android.purebilibili.feature.video.viewmodel.VideoMaidAction
import java.awt.Component

/** Presentation of the original owner's confirmed receipts above its existing
 * heavyweight Canvas. No protocol work or success is manufactured here. */
@Composable
internal fun DesktopWindowsConfirmedVideoFeedback(
    binding: DesktopWindowsVideoEngagementBinding,
    surfaceSize: IntSize,
    anchorComponent: Component,
) {
    val root = LocalDesktopOriginalVideoRootWindowEnvironment.current
    if (LocalAwtWindow.current !== root.window || !root.owns() || !binding.isFeedbackOwned()) return
    val snapshot by binding.state.collectAsState()
    fun captured(kind: DesktopWindowsVideoFeedbackKind): DesktopWindowsVideoFeedbackOrigin? =
        binding.feedback(kind)?.takeIf { it === snapshot.desktopFeedbackOrigin(kind) }
    val like = captured(DesktopWindowsVideoFeedbackKind.LIKE)
    val maid = when (snapshot.maidAction) {
        VideoMaidAction.DISLIKE -> captured(DesktopWindowsVideoFeedbackKind.DISLIKE)
        VideoMaidAction.SHARE -> captured(DesktopWindowsVideoFeedbackKind.SHARE)
        VideoMaidAction.COIN -> captured(DesktopWindowsVideoFeedbackKind.COIN)
        null -> null
    }
    val triple = captured(DesktopWindowsVideoFeedbackKind.TRIPLE)
    val receipts = listOfNotNull(like, maid, triple)
    if (receipts.isEmpty()) return
    val reducedMotion = rememberSystemReduceMotion()
    key(binding) {
        // This is a local native-window readiness receipt, not animation or
        // operation state. Keep original composition after its first real ACK:
        // Root background/Lifecycle pauses elapsed time during temporary hide.
        var everAvailable by remember { mutableStateOf(false) }
        receipts.forEach { receipt ->
            key(receipt) {
                DisposableEffect(binding, receipt) {
                    onDispose { binding.cancelFeedback(receipt) }
                }
            }
        }
        fun current(): Boolean = root.owns() && binding.isFeedbackOwned() && binding.admitFeedback {}
        DesktopDecorativeVideoFeedbackPopup(surfaceSize, anchorComponent,
            sourceOwner = binding.sourceOwner, subject = binding.subject,
            ownsPresentation = ::current,
            onWindowAvailability = { _, available -> if (available) everAvailable = true },
            onWindowRejected = { receipts.forEach { binding.cancelFeedback(it) } },
        ) { available ->
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val width = maxWidth.value
                val height = maxHeight.value
                val space = width.isFinite() && height.isFinite() && width >= 96f && height >= 96f
                LaunchedEffect(binding, like, maid, triple, space) {
                    if (!space) receipts.forEach { binding.cancelFeedback(it) }
                }
                if ((available || everAvailable) && space && current()) {
                    val compact = width > height || height < 480f
                    val likeSize = minOf(height, width - 32f, 144f).coerceAtLeast(1f).dp
                    val celebrationSize = minOf(height - 32f, width - 32f,
                        if (compact) 180f else 220f).coerceAtLeast(1f).dp
                    DesktopWindowsVideoFeedbackMotionContent(binding, like, maid, triple,
                        likeSize, celebrationSize, compact, reducedMotion)
                }
            }
        }
    }
}
