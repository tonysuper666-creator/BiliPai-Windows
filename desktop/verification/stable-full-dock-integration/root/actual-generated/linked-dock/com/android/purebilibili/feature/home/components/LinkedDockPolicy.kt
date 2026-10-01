package com.android.purebilibili.feature.home.components

import kotlin.math.roundToInt

enum class LinkedDockPhase { Expanded, Playback, Compact, Search }

/** Search owns the first tap on the compact artwork target. */
internal fun shouldExpandPlaybackFromSearch(
    phase: LinkedDockPhase,
    hasAudio: Boolean,
): Boolean = hasAudio && phase == LinkedDockPhase.Search

/** Accumulate one direction before changing chrome; tiny reversals must not cause flicker. */
internal fun accumulateDockScroll(previous: Float, delta: Float): Float =
    if (previous * delta < 0f) delta else previous + delta

internal fun resolveLinkedDockRestingPhase(
    collapseRequested: Boolean,
    hasAudio: Boolean,
): LinkedDockPhase = when {
    !collapseRequested -> LinkedDockPhase.Expanded
    hasAudio -> LinkedDockPhase.Playback
    // 无音频的收起态回到完整底栏：搜索收成小圆钮，避免动态页下滑被 Compact 撑开。
    else -> LinkedDockPhase.Expanded
}

fun resolveLinkedDockPhaseOnAudioChange(
    currentPhase: LinkedDockPhase,
    hasAudio: Boolean,
): LinkedDockPhase = when {
    !hasAudio && currentPhase == LinkedDockPhase.Playback -> LinkedDockPhase.Compact
    hasAudio && currentPhase == LinkedDockPhase.Compact -> LinkedDockPhase.Playback
    else -> currentPhase
}

fun resolveLinkedDockInitialPhase(
    currentItem: BottomNavItem,
    collapseRequested: Boolean,
    hasAudio: Boolean,
    savedPhase: LinkedDockPhase? = null,
): LinkedDockPhase {
    if (savedPhase != null) {
        return resolveLinkedDockPhaseOnAudioChange(savedPhase, hasAudio)
    }
    return if (currentItem == BottomNavItem.HOME) {
        LinkedDockPhase.Expanded
    } else {
        resolveLinkedDockRestingPhase(collapseRequested, hasAudio)
    }
}

fun shouldEnableLinkedDockBackHandler(
    phase: LinkedDockPhase,
    isTopLevelDestination: Boolean,
): Boolean = isTopLevelDestination && phase == LinkedDockPhase.Search

fun resolveLinkedDockPhaseOnSearchDismiss(
    hasAudio: Boolean,
    previousPhase: LinkedDockPhase = if (hasAudio) LinkedDockPhase.Playback else LinkedDockPhase.Expanded,
): LinkedDockPhase = resolveLinkedDockPhaseOnAudioChange(previousPhase, hasAudio)

/** 离开搜索展开态（收起/退回其它相位）时清空底栏搜索词，避免下次展开残留。 */
internal fun shouldResetLinkedDockSearchQuery(
    phase: LinkedDockPhase,
): Boolean = phase != LinkedDockPhase.Search

internal data class LinkedDockGeometry(
    val searchWidth: Int,
    val audioWidth: Int,
    val audioX: Int,
    val audioY: Int,
    val top: Int,
    val height: Int,
)

internal fun resolveLinkedDockNavigationX(
    maximumWidth: Int,
    navigationWidth: Int,
    button: Int,
    gap: Int,
    searchEnabled: Boolean,
): Int {
    val safeMaximumWidth = maximumWidth.coerceAtLeast(0)
    val searchReservation = if (searchEnabled) button.coerceAtLeast(0) + gap.coerceAtLeast(0) else 0
    val clusterWidth = (navigationWidth.coerceAtLeast(0) + searchReservation)
        .coerceAtMost(safeMaximumWidth)
    return ((safeMaximumWidth - clusterWidth) / 2).coerceAtLeast(0)
}

internal fun resolveLinkedDockSearchX(
    maximumWidth: Int,
    navigationWidth: Int,
    searchWidth: Int,
    button: Int,
    gap: Int,
    mergeProgress: Float,
    searchProgress: Float,
): Int {
    val startX = resolveLinkedDockNavigationX(
        maximumWidth = maximumWidth,
        navigationWidth = navigationWidth,
        button = button,
        gap = gap,
        searchEnabled = true,
    ) + navigationWidth.coerceAtLeast(0) + gap.coerceAtLeast(0)
    val endX = (maximumWidth - searchWidth).coerceAtLeast(0)
    val expansionProgress = maxOf(mergeProgress, searchProgress).coerceIn(0f, 1f)
    return (startX + (endX - startX) * expansionProgress)
        .roundToInt()
}

internal fun resolveLinkedDockGeometry(
    width: Int,
    button: Int,
    barHeight: Int,
    gap: Int,
    hasAudio: Boolean,
    searchEnabled: Boolean,
    mergeProgress: Float,
    searchProgress: Float,
    verticalGap: Int = gap,
    presenceProgress: Float = 1f,
): LinkedDockGeometry {
    val merge = mergeProgress.coerceIn(0f, 1f)
    val search = searchProgress.coerceIn(0f, 1f)
    val presence = presenceProgress.coerceIn(0f, 1f)
    val top = ((if (hasAudio) barHeight + verticalGap else 0) * (1f - merge)).roundToInt()
    val searchWidth = if (!searchEnabled) 0 else (
        button + (width - button * (if (hasAudio) 3 else 2) - gap * (if (hasAudio) 2 else 1)) * search
    ).roundToInt().coerceAtLeast(button).coerceAtMost((width - button).coerceAtLeast(0))
    // Both playback and search retain separate capsule surfaces.
    val playbackGap = gap
    val compactAudioWidth = (width - button - searchWidth -
        playbackGap * (if (searchEnabled) 2 else 1)).coerceAtLeast(0)
    val targetAudioWidth = if (hasAudio) {
        (width + (compactAudioWidth - width) * merge).roundToInt()
    } else {
        0
    }
    // Presence 收放以右缘为锚：出现时胶囊从右缘向左生长，消失时向右收起，
    // 避免旧实现的零宽硬切；右缘始终落在完整胶囊的右边界上。
    val audioWidth = (targetAudioWidth * presence).roundToInt()
    val audioX = ((button + playbackGap) * merge).roundToInt() +
        targetAudioWidth - audioWidth
    return LinkedDockGeometry(
        searchWidth = searchWidth,
        audioWidth = audioWidth,
        audioX = audioX,
        audioY = 0,
        top = top,
        height = top + barHeight,
    )
}
