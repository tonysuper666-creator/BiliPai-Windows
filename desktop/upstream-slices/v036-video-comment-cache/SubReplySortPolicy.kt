package com.android.purebilibili.feature.video.viewmodel

import kotlinx.collections.immutable.persistentListOf

// 排序档位与楼中楼末页判定已下沉 core-data；原包入口保留，手机各调用方不感知迁移。
typealias SubReplySortMode = com.android.purebilibili.data.repository.SubReplySortMode

internal fun SubReplyUiState.resetForSort(mode: SubReplySortMode): SubReplyUiState = copy(
    sortMode = mode,
    items = persistentListOf(),
    baseItems = persistentListOf(),
    page = 1,
    basePage = 1,
    isEnd = false,
    baseIsEnd = false,
    grpcNextOffset = null,
    baseGrpcNextOffset = null,
    conversationAnchor = null,
    targetReplyId = 0L,
    isLoading = true,
    isRefreshing = false,
    error = null,
)

internal fun isSortedSubReplyPageEnd(cursorIsEnd: Boolean, nextOffset: String?): Boolean =
    com.android.purebilibili.data.repository.isSortedSubReplyPageEnd(cursorIsEnd, nextOffset)
