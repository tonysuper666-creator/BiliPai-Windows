package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.plugin.SkipAction
import com.android.purebilibili.feature.video.ui.components.SponsorContributionOverlay
import com.android.purebilibili.feature.video.ui.components.SponsorSkipButton
import com.android.purebilibili.feature.video.viewmodel.SponsorContributionPhase

/** Only small original More actions are adapted to the existing Windows menu.
 * The full ORIGINAL skip/vote/ignore and contribution review renderers are mounted below. */
@Composable internal fun DesktopWindowsSponsorMoreActions(
    assembly: DesktopOriginalVideoOwnerAssembly,
    source: DesktopOriginalVideoAcceptedPublication?,
    presentation: DesktopOriginalSponsorPresentation,
    current: (DesktopOriginalVideoAcceptedPublication?) -> Boolean,
    admission: (DesktopOriginalVideoAcceptedPublication, () -> Unit) -> Boolean,
    onOpenSegment: () -> Unit,
    dismissMenu: () -> Unit,
) {
    val expected = source ?: return
    if (!current(expected)) return
    val skip = presentation.skip?.takeIf { it.source === expected && validDesktopSponsorSegment(it) }
    if (skip != null) DropdownMenuItem(text = { Text("空降助手 · 片段评价") }, onClick = {
        admission(expected) {
            if (current(expected) && assembly.playback.desktopSponsorPresentation.value.skip === skip) {
                dismissMenu(); onOpenSegment()
            }
        }
    })
    val contribution = presentation.contribution
    val phase = contribution?.state?.phase ?: SponsorContributionPhase.HIDDEN
    val sameDraft = contribution?.source === expected
    val sponsor = assembly.environment.plugins.sponsorBlock
    val enabled = assembly.environment.plugins.enabledPlayerPlugins().any { it === sponsor } &&
        sponsor.isCommunityContributionEnabled()
    // A retired draft is never continued. A deliberate new start uses the original
    // VM's cancel/reset and marker method, including same-BV quality/recovery children.
    val canStart = enabled && phase != SponsorContributionPhase.HIDDEN &&
        (!sameDraft || contribution?.state?.showsMarkAction == true)
    if (canStart) {
        val marking = sameDraft && phase == SponsorContributionPhase.MARKING
        DropdownMenuItem(text = { Text(if (marking) "结束标记片段" else "标记片段起点") }, onClick = {
            admission(expected) {
                if (current(expected) && assembly.playback.desktopSponsorPresentation.value.contribution === contribution) {
                    dismissMenu(); assembly.playback.markSponsorContributionBoundary()
                }
            }
        })
        if (!marking) DropdownMenuItem(text = { Text("标记整段恰饭") }, onClick = {
            admission(expected) {
                if (current(expected) && assembly.playback.desktopSponsorPresentation.value.contribution === contribution) {
                    dismissMenu(); assembly.playback.markWholeVideoAsSponsor()
                }
            }
        })
        if (marking) DropdownMenuItem(text = { Text("取消片段标记") }, onClick = {
            admission(expected) {
                if (current(expected) && assembly.playback.desktopSponsorPresentation.value.contribution === contribution) {
                    dismissMenu(); assembly.playback.cancelSponsorContribution()
                }
            }
        })
    }
}

/** Caller supplies the SAME existing command viewport, accepted source and Root admission. */
@Composable internal fun BoxScope.DesktopWindowsSponsorCommunityOverlay(
    facade: DesktopUnifiedPlaybackFacade,
    assembly: DesktopOriginalVideoOwnerAssembly,
    source: DesktopOriginalVideoAcceptedPublication?,
    presentation: DesktopOriginalSponsorPresentation,
    showSegment: Boolean,
    current: (DesktopOriginalVideoAcceptedPublication?) -> Boolean,
    admission: (DesktopOriginalVideoAcceptedPublication, () -> Unit) -> Boolean,
    onCloseSegment: () -> Unit,
) {
    val expected = source ?: return
    if (!current(expected)) return
    val skip = presentation.skip?.takeIf { it.source === expected && validDesktopSponsorSegment(it) }
    if (showSegment && skip != null) {
        fun skipCurrent() = current(expected) && assembly.playback.desktopSponsorPresentation.value.skip === skip
        // Conditional mounting removes retired input immediately; it cannot survive
        // the original AnimatedVisibility exit and be re-stamped onto a new source.
        SponsorSkipButton(
            segment = skip.segment, visible = true,
            onSkip = {
                val segment = checkNotNull(skip.segment)
                val action = SkipAction.ShowButton(skip.state.skipToMs, skip.state.label ?: segment.category, segment.UUID)
                if (facade.executeManualSkip(assembly, expected, segment, action, ::skipCurrent)) onCloseSegment()
            },
            onDismiss = {
                admission(expected) { if (skipCurrent()) { assembly.playback.dismissSponsorSkipButton(); onCloseSegment() } }
            },
            onVote = { vote -> admission(expected) { if (skipCurrent()) assembly.playback.voteCurrentSponsorSegment(vote) } },
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 8.dp)
                .desktopCommandHitRegion("original-sponsor-segment"),
        )
    }
    val contribution = presentation.contribution?.takeIf { it.source === expected && it.state.showsReview } ?: return
    fun draftCurrent() = current(expected) && assembly.playback.desktopSponsorPresentation.value.contribution === contribution
    fun draftAction(action: () -> Unit) {
        admission(expected) { if (draftCurrent()) action() }
    }
    SponsorContributionOverlay(
        state = contribution.state,
        onCategoryChange = { category -> draftAction { assembly.playback.setSponsorContributionCategory(category) } },
        onActionTypeChange = { action -> draftAction { assembly.playback.setSponsorContributionActionType(action) } },
        onSubmit = { draftAction(assembly.playback::submitSponsorContribution) },
        onCancel = { draftAction(assembly.playback::cancelSponsorContribution) },
    )
}

private fun validDesktopSponsorSegment(value: DesktopOriginalSponsorSkipPresentation): Boolean {
    val segment = value.segment ?: return false
    return value.state.visible && value.state.segmentId == segment.UUID && segment.UUID.isNotBlank() &&
        value.state.skipToMs > 0L && value.state.skipToMs == segment.endTimeMs
}
