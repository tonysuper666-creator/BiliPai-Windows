package com.bilipai.desktop.data

import com.android.purebilibili.feature.dynamic.DynamicCommentCache
import com.android.purebilibili.feature.dynamic.DynamicCommentCacheSnapshot
import kotlinx.coroutines.CancellationException

/** Account/page admission around the complete original in-memory LRU.
 * SessionStore remains the sole account authority. This grants no network access. */
internal class DesktopDynamicCommentCacheStore(
    private val sessions: DesktopDynamicCacheSessionGuard,
) {
    private var cacheOwner: DesktopDynamicCacheOwner? = null
    private val original = DynamicCommentCache()

    fun bind(stillPageOwned: () -> Boolean,
        withOwnedPage: ((() -> Unit) -> Boolean)): DesktopDynamicCommentCacheBinding? {
        if (!stillPageOwned()) return null
        val owner = sessions.dynamicCacheOwner() ?: return null
        var binding: DesktopDynamicCommentCacheBinding? = null
        var pageAccepted = false
        val accepted = sessions.withCurrentDynamicCacheOwner(owner) {
            pageAccepted = withOwnedPage {
                if (stillPageOwned()) binding = DesktopDynamicCommentCacheBinding(
                    this, owner, stillPageOwned, withOwnedPage)
            }
        }
        return binding.takeIf { accepted && pageAccepted }
    }

    private fun <T> admitted(
        owner: DesktopDynamicCacheOwner,
        stillPageOwned: () -> Boolean,
        withOwnedPage: ((() -> Unit) -> Boolean),
        action: () -> T,
    ): T {
        var result: Result<T>? = null
        val accepted = sessions.withCurrentDynamicCacheOwner(owner) {
            val pageAccepted = withOwnedPage {
                if (!stillPageOwned()) throw CancellationException("Dynamic comment page retired")
                if (cacheOwner != owner) {
                    original.clear()
                    cacheOwner = owner
                }
                result = runCatching(action)
            }
            if (!pageAccepted) throw CancellationException("Dynamic comment page retired")
        }
        if (!accepted) throw CancellationException("Dynamic comment account owner retired")
        return checkNotNull(result).getOrThrow()
    }

    internal fun get(owner: DesktopDynamicCacheOwner, stillPageOwned: () -> Boolean,
        withOwnedPage: ((() -> Unit) -> Boolean),
        dynamicId: String, sortMode: Int): DynamicCommentCacheSnapshot? =
        admitted(owner, stillPageOwned, withOwnedPage) { original.get(dynamicId, sortMode) }

    internal fun put(owner: DesktopDynamicCacheOwner, stillPageOwned: () -> Boolean,
        withOwnedPage: ((() -> Unit) -> Boolean),
        dynamicId: String, sortMode: Int, snapshot: DynamicCommentCacheSnapshot) =
        admitted(owner, stillPageOwned, withOwnedPage) { original.put(dynamicId, sortMode, snapshot) }
}

/** Borrows one account generation; closing a page cannot transfer its binding. */
internal class DesktopDynamicCommentCacheBinding internal constructor(
    private val store: DesktopDynamicCommentCacheStore,
    private val accountOwner: DesktopDynamicCacheOwner,
    private val stillPageOwned: () -> Boolean,
    private val withOwnedPage: ((() -> Unit) -> Boolean),
) {
    fun get(dynamicId: String, sortModeApiMode: Int): DynamicCommentCacheSnapshot? =
        store.get(accountOwner, stillPageOwned, withOwnedPage, dynamicId, sortModeApiMode)

    fun put(dynamicId: String, sortModeApiMode: Int, snapshot: DynamicCommentCacheSnapshot) =
        store.put(accountOwner, stillPageOwned, withOwnedPage, dynamicId, sortModeApiMode, snapshot)
}
