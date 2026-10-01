package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.core.plugin.feed.ParsedFeedItem
import com.android.purebilibili.core.plugin.feed.SubscriptionFeedStore
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** One retained Home owner, delegating all reading IO to the runtime's existing repository. */
internal class DesktopSubscriptionPageBindings(
    val runtime: DesktopPluginRuntime,
    val gallery: DesktopDynamicCardPlatform,
    private val stillOwned: () -> Boolean,
    private val commitIfCurrent: ((() -> Unit) -> Boolean),
    private val clipboard: (String) -> Unit,
    private val feedback: (String) -> Unit,
    private val openExternal: (String) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val repository get() = runtime.subscriptions
    val context get() = runtime.context
    val state get() = repository.state
    val changes: Flow<Unit> = combine(SubscriptionFeedStore.revision, runtime.plugins,
        runtime.jsPlugins.host.executionRevision) { _, _, _ -> Unit }
    val articleFontScale: Flow<Int> = runtime.store.snapshot("settings").map { snapshot ->
        snapshot[DesktopPreferenceKey("subscription_article_font_scale") { (it as? JsonPrimitive)?.intOrNull }] ?: 1
    }
    fun isOwned(): Boolean = !closed.get() && stillOwned()
    fun commitUi(action: () -> Unit) {
        if (!isOwned()) return
        commitIfCurrent { if (isOwned()) action() }
    }
    private suspend fun <T> owned(block: suspend () -> T): T =
        DesktopSubscriptionWriteAdmission.withOwned(::isOwned, commitIfCurrent, block)

    suspend fun reload() = owned { repository.loadCached(); repository.refresh() }
    suspend fun setRead(item: ParsedFeedItem, read: Boolean) = owned { repository.setRead(item, read) }
    suspend fun saveFullBody(item: ParsedFeedItem, html: String) = owned { repository.saveFullBody(item, html) }
    suspend fun fetchArticleBody(item: ParsedFeedItem): Result<String> = owned { repository.fetchArticleBody(item) }
    suspend fun setArticleFontScale(value: Int) = owned {
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            // Root Store -> retained entry -> original plugin backing. No reverse Root-lock acquisition.
            DesktopSubscriptionWriteAdmission.commitOrOriginal {
                runtime.store.update("settings", mapOf("subscription_article_font_scale" to JsonPrimitive(value.coerceIn(0, 2))))
            }
        }
    }
    fun copyText(text: String) = commitUi {
        try { clipboard(text); feedback("已复制正文") }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { feedback(error.message ?: "正文复制失败") }
    }
    fun openExternalUrl(url: String) = commitUi { openExternal(url) }
    fun reportFailure(error: Throwable, action: () -> Unit) {
        if (error is CancellationException) throw error
        commitUi(action)
    }
    override fun close() { closed.set(true) }
}

internal val LocalDesktopSubscriptionBindings = staticCompositionLocalOf<DesktopSubscriptionPageBindings> {
    error("The retained Root Subscription bindings are required")
}

/** Root supplies one retained binding; normal route invisibility never closes it. */
@Composable
internal fun DesktopSubscriptionPageHost(binding: DesktopSubscriptionPageBindings, content: @Composable () -> Unit) {
    key(binding) {
        CompositionLocalProvider(LocalDesktopSubscriptionBindings provides binding,
            LocalDesktopDynamicCardBindings provides binding.gallery) {
            DesktopCommentDialogNavigationHost(content)
        }
    }
}
