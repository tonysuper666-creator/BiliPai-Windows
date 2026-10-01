package com.bilipai.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.android.purebilibili.data.model.response.DynamicItem
import com.android.purebilibili.feature.dynamic.applyDynamicForwardCountIncrement
import com.android.purebilibili.feature.dynamic.applyDynamicLikeCountChange
import com.android.purebilibili.feature.dynamic.components.unfoldRelatedDynamicItems
import com.bilipai.desktop.data.DesktopDynamicCache
import com.bilipai.desktop.data.DesktopDynamicCacheSessionGuard
import com.bilipai.desktop.data.DesktopOwnedFollowStateChange
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** References to existing page models; this registry owns no items or cache. */
internal interface DesktopDynamicCardItemsOwner {
    fun mutateDynamicItems(transform: (List<DynamicItem>) -> List<DynamicItem>)
}

internal class DesktopDynamicCardStateRegistry(
    private val guard: DesktopDynamicCacheSessionGuard,
    private val cache: DesktopDynamicCache,
    expectedEpoch: Long,
    private val stillOwned: () -> Boolean = { true },
) : AutoCloseable {
    private val owner = guard.dynamicCacheOwner()?.takeIf { it.epoch == expectedEpoch }
    private val alive = AtomicBoolean(true)
    private val models = mutableListOf<WeakReference<DesktopDynamicCardItemsOwner>>()
    private var currentAll: WeakReference<DesktopDynamicTimelineState>? = null

    val bindings = DesktopDynamicCardMutationBindings(
        markNotInterested = { id ->
            check(alive.get() && stillOwned()) { "动态页面已关闭" }
            val current = cache.openCurrent()
            check(current != null && current.owner == owner) { "账号已切换，请重新加载" }
            check(alive.get() && stillOwned()) { "动态页面已关闭" }
            current.markNotInterested(id)
        },
        likeConfirmed = { id, liked -> mutate { applyDynamicLikeCountChange(it, id, liked) } },
        repostConfirmed = { id -> mutate { applyDynamicForwardCountIncrement(it, id) } },
        removed = { id -> mutate { rows -> rows.filterNot { it.id_str == id } } },
        unfoldRelated = { id -> mutate { unfoldRelatedDynamicItems(it, id) } },
    )

    fun register(model: DesktopDynamicCardItemsOwner) {
        synchronized(models) {
            models.removeAll { it.get() == null }
            if (models.none { it.get() === model }) models += WeakReference(model)
            if (model is DesktopDynamicTimelineState && model.isAllTimeline) currentAll = WeakReference(model)
        }
    }
    /** Read only: the same registered All timeline remains the sole pagination authority. */
    internal fun currentAllUpdateBaseline(): String = synchronized(models) {
        if (alive.get() && stillOwned()) currentAll?.get()?.currentUpdateBaseline().orEmpty() else ""
    }
    fun isCurrentAll(model: DesktopDynamicTimelineState): Boolean = synchronized(models) {
        alive.get() && stillOwned() && currentAll?.get() === model
    }

    /** Original home ViewModel scope only; Space/Topic/detail owners are intentionally excluded. */
    fun applyFollowStateChange(event: DesktopOwnedFollowStateChange) {
        val captured = owner ?: return
        if (event.owner != captured || event.change.mid <= 0L) return
        guard.withCurrentDynamicCacheOwner(captured) {
            if (!alive.get() || !stillOwned()) return@withCurrentDynamicCacheOwner
            val active = synchronized(models) {
                models.removeAll { it.get() == null }
                models.mapNotNull { it.get() }
            }
            if (event.change.isFollowing) {
                active.filterIsInstance<DesktopDynamicUsersState>().forEach { it.applyFollowingConfirmed() }
            } else {
                active.filterIsInstance<DesktopDynamicTimelineState>().forEach { it.applyAuthorUnfollow(event.change.mid) }
                active.filterIsInstance<DesktopDynamicUsersState>().forEach { it.applyAuthorUnfollow(event.change.mid) }
                // Original ViewModel saves even an empty All page. The sole Root cache actor still writes it.
                synchronized(models) { currentAll?.get() }?.persistCurrentItems()
            }
        }
    }

    private fun mutate(transform: (List<DynamicItem>) -> List<DynamicItem>) {
        val captured = owner ?: return
        guard.withCurrentDynamicCacheOwner(captured) {
            if (!alive.get() || !stillOwned()) return@withCurrentDynamicCacheOwner
            val active = synchronized(models) {
                models.removeAll { it.get() == null }
                models.mapNotNull { it.get() }
            }
            val all = synchronized(models) { currentAll?.get() }
            val previousAllItems = all?.page?.items
            active.forEach { it.mutateDynamicItems(transform) }
            if (all != null && previousAllItems != all.page.items) all.persistCurrentItems()
        }
    }

    override fun close() { alive.set(false); synchronized(models) { models.clear(); currentAll = null } }
}

internal val LocalDesktopDynamicCardStateRegistry = staticCompositionLocalOf<DesktopDynamicCardStateRegistry?> { null }
