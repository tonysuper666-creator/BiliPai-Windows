package com.bilipai.desktop.data

import com.android.purebilibili.data.repository.FollowStateChange
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Original success-only event, tagged by SessionStore authority. Owns no items/cache. */
internal data class DesktopOwnedFollowStateChange(
    val owner: DesktopDynamicCacheOwner,
    val change: FollowStateChange,
)

internal class DesktopFollowStateEvents(private val guard: DesktopDynamicCacheSessionGuard) {
    private val mutableChanges = MutableSharedFlow<DesktopOwnedFollowStateChange>(extraBufferCapacity = 32)
    val changes = mutableChanges.asSharedFlow()

    fun requireOwner(): DesktopDynamicCacheOwner = guard.dynamicCacheOwner()?.takeIf { it.mid > 0L }
        ?: throw BiliApiException(-101, "请先登录后查看账号内容")

    fun requireCurrent(owner: DesktopDynamicCacheOwner) {
        if (!guard.withCurrentDynamicCacheOwner(owner) {}) throw BiliApiException(-101, "账号已切换，请重新操作")
    }

    fun confirm(owner: DesktopDynamicCacheOwner, change: FollowStateChange) {
        check(change.mid > 0L)
        if (!guard.withCurrentDynamicCacheOwner(owner) {
            check(mutableChanges.tryEmit(DesktopOwnedFollowStateChange(owner, change))) { "关注事件队列已满，请重新加载动态" }
        }) throw BiliApiException(-101, "账号已切换，请重新操作")
    }
}
