package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.android.purebilibili.core.plugin.feed.ParsedFeedItem
import com.android.purebilibili.core.plugin.feed.SavedArticleNote
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.serialization.json.booleanOrNull
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
    var articleOpen by mutableStateOf(false)
        private set
    private val repository get() = runtime.subscriptions
    val context get() = runtime.context
    val state get() = repository.state
    val changes: Flow<Unit> = combine(SubscriptionFeedStore.revision, runtime.plugins,
        runtime.jsPlugins.host.executionRevision) { _, _, _ -> Unit }
    val articleFontScale: Flow<Int> = runtime.store.snapshot("settings").map { snapshot ->
        snapshot[DesktopPreferenceKey("subscription_article_font_scale") { (it as? JsonPrimitive)?.intOrNull }] ?: 1
    }
    val articleNotes get() = repository.articleNotes
    val articleWallpaperEnabled: Flow<Boolean> = runtime.store.snapshot("settings").map { snapshot ->
        snapshot[DesktopPreferenceKey("subscription_article_wallpaper_enabled") { (it as? JsonPrimitive)?.booleanOrNull }] ?: false
    }
    fun isOwned(): Boolean = !closed.get() && stillOwned()
    fun commitUi(action: () -> Unit) {
        if (!isOwned()) return
        commitIfCurrent { if (isOwned()) action() }
    }
    suspend fun commitCallerUi(action: () -> Unit) {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        commitUi { caller.ensureActive(); action() }
    }
    fun articleOpenChanged(open: Boolean, callback: (Boolean) -> Unit) = commitUi {
        articleOpen = open
        callback(open)
    }
    private suspend fun <T> owned(block: suspend () -> T): T =
        DesktopSubscriptionWriteAdmission.withOwned(::isOwned, commitIfCurrent, block)

    suspend fun reload() = owned { repository.loadCached(); repository.refresh() }
    suspend fun setRead(item: ParsedFeedItem, read: Boolean) = owned { repository.setRead(item, read) }
    suspend fun saveFullBody(item: ParsedFeedItem, html: String) = owned { repository.saveFullBody(item, html) }
    suspend fun fetchArticleBody(item: ParsedFeedItem): Result<String> = owned { repository.fetchArticleBody(item) }
    suspend fun saveArticleNote(note: SavedArticleNote) = owned { repository.saveArticleNote(note) }
    suspend fun removeArticleNote(link: String): Boolean = owned { repository.removeArticleNote(link) }
    private suspend fun setting(key: String, value: JsonPrimitive) = owned {
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            runtime.store.updateOriginalFromSnapshot("settings",
                DesktopSubscriptionWriteAdmission::checkCurrentRequestOrOriginal,
                { DesktopSubscriptionWriteAdmission.acquirePreferencePermit(runtime.store) }) {
                Unit to mapOf(key to value)
            }
        }
    }
    suspend fun setArticleFontScale(value: Int) = setting("subscription_article_font_scale", JsonPrimitive(value.coerceIn(0, 2)))
    suspend fun setArticleWallpaperEnabled(value: Boolean) = setting("subscription_article_wallpaper_enabled", JsonPrimitive(value))
    fun showFeedback(message: String) = commitUi { feedback(message) }
    fun shareArticle(text: String) = commitUi { gallery.shareText(text) }
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

/** Ordinary pure-reading back uses the SAME owned NavigationEvent dispatcher as article back. */
@Composable internal fun DesktopSubscriptionBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val state = rememberNavigationEventState(NavigationEventInfo.None)
    val callback by rememberUpdatedState(onBack)
    NavigationBackHandler(state = state, isBackEnabled = enabled, onBackCompleted = { callback() })
}

/** Root supplies one retained binding; normal route invisibility never closes it. */
@Composable
internal fun DesktopSubscriptionPageHost(binding: DesktopSubscriptionPageBindings, content: @Composable () -> Unit) {
    key(binding) {
        val navigation = remember(binding) { DesktopCommentDialogNavigation() }
        val focus = remember(binding) { FocusRequester() }
        DisposableEffect(navigation) { onDispose { navigation.close() } }
        LaunchedEffect(binding.articleOpen) { if (binding.articleOpen) focus.requestFocus() }
        CompositionLocalProvider(LocalDesktopSubscriptionBindings provides binding,
            LocalDesktopDynamicCardBindings provides binding.gallery,
            LocalNavigationEventDispatcherOwner provides navigation) {
            Box(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                binding.articleOpen && navigation.onKey(event)
            }.focusRequester(focus).focusable(enabled = binding.articleOpen)) { content() }
        }
    }
}
