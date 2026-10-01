// GENERATED from app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicInteractionPolicy.kt; do not edit.
// LF-normalized SHA-256: 0322b2835924409fbb3716524d292c040cdcdec15769b19e272e52bd7839b6f7
package com.android.purebilibili.feature.dynamic
import com.android.purebilibili.feature.video.viewmodel.CommentSortMode
internal fun shouldApplyDynamicCommentPageResult(
    activeRequestId: Long,
    requestId: Long,
    activeTarget: DynamicCommentTarget?,
    requestTarget: DynamicCommentTarget,
    activeSortMode: CommentSortMode,
    requestSortMode: CommentSortMode,
): Boolean = activeRequestId == requestId &&
    activeTarget == requestTarget &&
    activeSortMode == requestSortMode
