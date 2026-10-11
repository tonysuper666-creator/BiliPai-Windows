package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.SponsorSegment
import com.android.purebilibili.feature.video.viewmodel.SponsorContributionUiState
import com.android.purebilibili.feature.video.viewmodel.SponsorSkipUiState

/** Immutable UI receipts of values actually published by the retained original VM.
 * They grant no authority: NativeOwner, Runtime, Store and entry still admit every action.
 * Windows consumes one complete publication, never separate source/state Flow values. */
internal data class DesktopOriginalSponsorSkipPresentation(
    val source: DesktopOriginalVideoAcceptedPublication,
    val state: SponsorSkipUiState,
    val segment: SponsorSegment?,
)

internal data class DesktopOriginalSponsorContributionPresentation(
    val source: DesktopOriginalVideoAcceptedPublication?,
    val state: SponsorContributionUiState,
)

internal data class DesktopOriginalSponsorPresentation(
    val skip: DesktopOriginalSponsorSkipPresentation? = null,
    val contribution: DesktopOriginalSponsorContributionPresentation? = null,
)
