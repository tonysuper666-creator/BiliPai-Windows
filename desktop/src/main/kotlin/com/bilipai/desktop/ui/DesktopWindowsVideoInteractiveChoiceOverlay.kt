package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.android.purebilibili.feature.video.interaction.InteractiveChoicePanelUiState
import com.android.purebilibili.feature.video.ui.components.InteractiveChoiceOverlay
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState

/** Keep the source/metadata permission in the real Leaf, outside the conditional
 * choice UI and its native carrier. Original selection hides the panel BEFORE
 * HTTP; visible/remainingMs must never retire that still legitimate permission.
 * The original VM owns branch requests, native acceptance and delayed child ACK. */
@Composable internal fun rememberDesktopWindowsVideoInteractiveChoiceSource(
    assembly: DesktopOriginalVideoOwnerAssembly,
    success: VideoPlaybackUiState.Success?,
    sourceOwner: DesktopOriginalVideoAcceptedPublication?,
    panel: InteractiveChoicePanelUiState,
    stillOwned: () -> Boolean,
    sourceAdmission: (DesktopOriginalVideoAcceptedPublication, () -> Unit) -> Boolean,
): DesktopOriginalInteractiveChoiceSource? {
    val latestOwned by rememberUpdatedState(stillOwned)
    val latestAdmission by rememberUpdatedState(sourceAdmission)
    if (success == null || sourceOwner == null) return null
    val info = success.info
    val load = assembly.playback.captureDesktopLoadState()
    if (load.currentRequest == null || load.currentBvid.isBlank() || load.currentCid <= 0L ||
        !desktopWindowsVideoMetadataMatchesSource(info, sourceOwner.request, load, load.currentLoadRequestToken)) return null

    return key(assembly, sourceOwner, DesktopWindowsInteractiveChoiceIdentity(load.currentRequest), load.currentLoadRequestToken) {
        val original = remember { load }
        val lease = remember {
            DesktopWindowsVideoMetadataLease {
                latestOwned() && assembly.owns() && assembly.native.isCurrent(sourceOwner) &&
                    (assembly.playback.captureDesktopPlaybackState() as? VideoPlaybackUiState.Success)?.let { current ->
                        val actual = assembly.playback.captureDesktopLoadState()
                        !current.isQualitySwitching && actual.currentRequest === original.currentRequest &&
                            desktopWindowsVideoMetadataMatchesSource(current.info, sourceOwner.request,
                                actual, original.currentLoadRequestToken)
                    } == true
            }
        }
        DisposableEffect(lease) { onDispose { lease.close() } }
        // Countdown copies and the original hide operation preserve these exact
        // question fields. A later same-CID question has its own choices identity.
        // Do not capture interactiveCurrentEdgeId: the VM changes it pre-submit.
        remember(lease, DesktopWindowsInteractiveChoiceIdentity(panel.choices), panel.questionId, panel.edgeId, panel.questionType) {
            val choices = panel.choices
            val questionId = panel.questionId
            val edgeId = panel.edgeId
            val questionType = panel.questionType
            DesktopOriginalInteractiveChoiceSource(sourceOwner, original,
                captureLoadState = assembly.playback::captureDesktopLoadState,
                stillOwned = {
                    val actual = assembly.playback.interactiveChoicePanel.value
                    lease.isOwned() && actual.choices === choices && actual.questionId == questionId &&
                        actual.edgeId == edgeId && actual.questionType == questionType
                },
                admission = { action -> latestAdmission(sourceOwner, action) },
            )
        }
    }
}

/** Mount the complete unchanged original coordinate/list overlay in the existing
 * player viewport carrier. Its real laid-out viewport supplies the native hit
 * region; no modal focus request, new dialog, branch repository or UI timer. */
@Composable internal fun DesktopWindowsVideoInteractiveChoiceOverlay(
    assembly: DesktopOriginalVideoOwnerAssembly,
    panel: InteractiveChoicePanelUiState,
    source: DesktopOriginalInteractiveChoiceSource?,
) {
    if (source == null || !source.isCurrent()) return
    InteractiveChoiceOverlay(
        state = panel,
        onSelectChoice = { edgeId, cid ->
            // The source-aware original entry performs its own short final commit
            // then starts its real lazy invocation OUTSIDE native admission.
            // Never wrap launch/start in an outer source.commit.
            if (source.isCurrent()) assembly.playback.selectDesktopInteractiveChoice(edgeId, cid, source)
        },
        onDismiss = { source.commit { assembly.playback.dismissInteractiveChoicePanel() } },
        modifier = Modifier.desktopCommandHitRegion("original-interactive-choice"),
    )
}

/** Compose keys use equals; this permission deliberately requires the original
 * request/choices object identity, including equal-valued replacement lists. */
private class DesktopWindowsInteractiveChoiceIdentity(private val value: Any?) {
    override fun equals(other: Any?): Boolean =
        other is DesktopWindowsInteractiveChoiceIdentity && value === other.value
    override fun hashCode(): Int = System.identityHashCode(value)
}
