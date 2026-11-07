package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.data.model.response.FavFolder
import com.android.purebilibili.data.model.response.FavoriteData
import com.android.purebilibili.data.model.response.FavoriteResourceData
import com.android.purebilibili.data.model.response.Upper
import com.android.purebilibili.feature.audio.library.ListenVideoCollectedFoldersPage
import com.android.purebilibili.feature.audio.library.ListenVideoLibraryDataSource
import com.android.purebilibili.feature.audio.viewmodel.ListenVideoCollectionViewModel
import com.android.purebilibili.feature.settings.AppThemeMode
import com.android.purebilibili.feature.video.player.ExternalPlaylistSource
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.android.purebilibili.feature.video.player.PlaylistSession
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.awt.GraphicsEnvironment
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The collection page of a retained AudioMode destination: the upstream ListenVideoCollectionPlayer over the upstream
 * ListenVideoCollectionViewModel (both compiled from the merged root), composed by the desktop surface.
 * Two behaviours users can see are pinned here: a destination that was never in front does not load the collection (which would replace
 * the Root queue) and mounts no player; and a failed read can be retried from the page's own error state.
 */
class DesktopOriginalListenVideoCollectionSurfaceTest {
    private class FakeSource : ListenVideoLibraryDataSource {
        @Volatile var failing = false
        val folderRequests = AtomicInteger()
        override suspend fun ownedFolders(mid: Long): Result<List<FavFolder>> = Result.success(emptyList())
        override suspend fun collectedFolders(mid: Long, page: Int) =
            Result.success(ListenVideoCollectedFoldersPage(emptyList(), hasMore = false))
        override suspend fun folderPage(mediaId: Long, page: Int): Result<FavoriteResourceData> {
            folderRequests.incrementAndGet()
            if (failing) return Result.failure(IOException("网络中断"))
            return Result.success(FavoriteResourceData(medias = listOf(
                FavoriteData(id = 1, bvid = "BV1xx411c7mD", title = "第一首", upper = Upper(mid = 7L, name = "UP"))), has_more = false))
        }
        override suspend fun albumPage(seasonId: Long, page: Int): Result<FavoriteResourceData> =
            Result.failure(IllegalStateException("unused"))
    }

    private class FakeOwner(override val dataSource: ListenVideoLibraryDataSource) : DesktopOriginalListenCollectionOwner {
        override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val queues = CopyOnWriteArrayList<List<PlaylistItem>>()
        override fun assertOwned() = scope.coroutineContext[Job]!!.ensureActive()
        override fun commit(block: () -> Unit) { assertOwned(); block() }
        override fun <T> updateState(state: MutableStateFlow<T>, transform: (T) -> T) { assertOwned(); state.update(transform) }
        override fun request(context: CoroutineContext) = object : DesktopOriginalListenCollectionRequestPort {
            private val caller = checkNotNull(context[Job])
            override fun check() { caller.ensureActive(); assertOwned() }
            override fun commit(block: () -> Unit) { check(); block() }
            override fun <T> read(block: () -> T): T { check(); return block() }
            override fun <T> updateState(state: MutableStateFlow<T>, transform: (T) -> T) { check(); state.update(transform) }
            override fun setExternalPlaylist(items: List<PlaylistItem>, index: Int, source: ExternalPlaylistSource): PlaylistSession {
                check(); queues += items; return PlaylistSession(queues.size.toLong())
            }
            override fun appendPlaylist(items: List<PlaylistItem>, session: PlaylistSession): Boolean { check(); queues += items; return true }
        }
        override fun isSessionCurrent(session: PlaylistSession) = true
        override fun currentItem(): PlaylistItem? = null
        override fun capturePlaybackTarget(session: PlaylistSession, item: PlaylistItem, originalPublicationCurrent: () -> Boolean) = null
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    @Test
    fun `a destination that was never in front neither loads the collection nor mounts a player`() {
        check(GraphicsEnvironment.isHeadless()) { "CPU component tests require the existing headless test JVM" }
        val source = FakeSource()
        val owner = FakeOwner(source)
        val viewModel = ListenVideoCollectionViewModel(owner)
        var active by mutableStateOf(false)
        val scene = ImageComposeScene(900, 700, Density(1.5f)) {
            DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = AppUiStyle.MATERIAL3, themeMode = AppThemeMode.LIGHT, hapticFeedbackEnabled = false),
                systemLanguageTags = listOf("zh-CN")) {
                Box(Modifier.fillMaxSize()) {
                    DesktopOriginalListenVideoCollectionSurface(viewModel, 5L, 0L, "我的收藏", active, {}) { Text("PLAYER-SLOT") }
                }
            }
        }
        var nanos = 0L
        fun settle() = repeat(6) { nanos += 30_000_000L; scene.render(nanos).close() }
        fun hasText(text: String) = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
            .any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }
        fun await(what: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!condition()) { check(System.nanoTime() < deadline) { "Timed out waiting for $what" }; settle(); Thread.sleep(20) }
        }
        try {
            repeat(15) { settle(); Thread.sleep(20) }
            assertEquals(0, source.folderRequests.get(), "a covered destination does not read the collection")
            assertTrue(owner.queues.isEmpty(), "and does not replace the Root queue")
            assertFalse(hasText("PLAYER-SLOT"), "and mounts no player")
            assertFalse(hasText("正在读取播放列表"), "the page itself is not composed either")

            active = true
            await("the first track to mount the player slot") { hasText("PLAYER-SLOT") }
            assertEquals(1, source.folderRequests.get())
            assertEquals(1, owner.queues.size, "the queue is set once, when the destination first comes to the front")
            assertEquals(listOf("BV1xx411c7mD"), owner.queues.single().map { it.bvid })

            active = false // covered again: the page and the loader stay as they were, nothing is requested again
            repeat(15) { settle(); Thread.sleep(20) }
            assertTrue(hasText("PLAYER-SLOT"))
            assertEquals(1, source.folderRequests.get())
            assertEquals(1, owner.queues.size)
        } finally { scene.close(); owner.scope.cancel() }
    }

    @Test
    fun `a failed read shows the page's own error and retrying it succeeds`() {
        check(GraphicsEnvironment.isHeadless()) { "CPU component tests require the existing headless test JVM" }
        val source = FakeSource().also { it.failing = true }
        val owner = FakeOwner(source)
        val viewModel = ListenVideoCollectionViewModel(owner)
        val scene = ImageComposeScene(900, 700, Density(1.5f)) {
            DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = AppUiStyle.MATERIAL3, themeMode = AppThemeMode.LIGHT, hapticFeedbackEnabled = false),
                systemLanguageTags = listOf("zh-CN")) {
                Box(Modifier.fillMaxSize()) {
                    DesktopOriginalListenVideoCollectionSurface(viewModel, 5L, 0L, "我的收藏", true, {}) { Text("PLAYER-SLOT") }
                }
            }
        }
        var nanos = 0L
        fun settle() = repeat(6) { nanos += 30_000_000L; scene.render(nanos).close() }
        fun nodes() = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
        fun hasText(text: String) = nodes().any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }
        fun await(what: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!condition()) { check(System.nanoTime() < deadline) { "Timed out waiting for $what" }; settle(); Thread.sleep(20) }
        }
        try {
            await("the error page") { hasText("重试") }
            assertTrue(hasText("网络中断"), "the real failure is shown")
            assertFalse(hasText("PLAYER-SLOT"), "no player is mounted behind an error")
            assertTrue(owner.queues.isEmpty())

            source.failing = false
            val retry = nodes().filter { it.config.getOrNull(SemanticsActions.OnClick) != null }
                .first { node -> flatten(node).any { child -> child.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "重试" } == true } }
            requireNotNull(retry.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()
            await("the retried read to mount the player slot") { hasText("PLAYER-SLOT") }
            assertFalse(hasText("网络中断"))
            assertEquals(1, owner.queues.size)
            assertTrue(source.folderRequests.get() >= 2, "the failed request and the retried one")
        } finally { scene.close(); owner.scope.cancel() }
    }
}
