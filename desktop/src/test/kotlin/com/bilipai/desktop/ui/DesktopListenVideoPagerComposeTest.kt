package com.bilipai.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.purebilibili.feature.audio.library.ListenVideoSection
import com.android.purebilibili.feature.audio.screen.ListenVideoScreen
import com.android.purebilibili.feature.audio.viewmodel.ListenVideoUiState
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Whole compiled desktop ListenVideoScreen, using the same settings and lifecycle as a desktop page. */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class DesktopListenVideoPagerComposeTest {
    @Test
    fun entersSwitchesToAlbumsAndDisposes(): Unit = runBlocking {
        check(GraphicsEnvironment.isHeadless())
        val fixture = DesktopPageTestFixture()
        val directory = fixture.root
        val pageJob = fixture.scope.coroutineContext[Job]!!
        val visible = mutableStateOf(true)
        val selected = mutableListOf<ListenVideoSection>()
        val errors = mutableListOf<Throwable>()
        var disposed = false
        var nanos = 0L
        val scene = ImageComposeScene(800, 600, Density(1f),
            coroutineContext = coroutineContext + CoroutineExceptionHandler { _, error -> errors += error }) {
            if (visible.value) {
                DisposableEffect(Unit) { onDispose { disposed = true } }
                CompositionLocalProvider(
                    LocalDesktopHomeEnvironment provides fixture.home,
                    LocalLifecycleOwner provides fixture.lifecycleOwner,
                ) {
                    MaterialTheme {
                        ListenVideoScreen(
                            state = ListenVideoUiState(), nowPlaying = null,
                            onNowPlayingClick = { _, _ -> error("unexpected playback") },
                            onRefresh = { error("unexpected refresh") },
                            onOpenHistory = { error("unexpected history") },
                            onSectionSelected = { selected += it },
                            onPlaylistSelected = { error("unexpected playlist") },
                            onAlbumSelected = { error("unexpected album") },
                            onArtistSelected = { error("unexpected artist") },
                            onCloseDetail = { error("unexpected close detail") },
                            onRetryDetail = { error("unexpected retry detail") },
                            onRetryIndex = { error("unexpected retry index") },
                            onTrackSelected = { _, _ -> error("unexpected track") },
                            onLogin = { error("unexpected login") },
                        )
                    }
                }
            }
        }
        suspend fun frames(count: Int = 90) {
            repeat(count) { nanos += 16_000_000L; scene.render(nanos).close(); yield() }
        }
        fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        fun hasText(value: String) = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }.any {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true
        }
        try {
            frames()
            assertEquals(ListenVideoSection.PLAYLISTS, selected.last())
            assertTrue(hasText("还没有可播放的收藏夹"), "the first page is rendered")
            val albumLabel = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }.single {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "专辑" } == true
            }
            val point = albumLabel.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Move, point, timeMillis = nanos / 1_000_000)
            scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = nanos / 1_000_000 + 1, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = nanos / 1_000_000 + 41, buttons = PointerButtons())
            frames()
            assertEquals(ListenVideoSection.ALBUMS, selected.last(), "the actual pager must settle on the clicked tab")
            assertTrue(hasText("收藏夹中还没有视频合集"), "the selected page is rendered")
            visible.value = false
            frames(3)
            assertTrue(disposed, "the page left composition")
            assertFalse(hasText("收藏夹中还没有视频合集"))
            assertTrue(errors.isEmpty(), "uncaught scene errors: $errors")
        } finally {
            try { scene.close() } finally { fixture.close() }
        }
        assertEquals(Lifecycle.State.DESTROYED, fixture.lifecycleOwner.lifecycle.currentState)
        assertTrue(pageJob.isCompleted)
        assertFalse(Files.exists(directory))
    }
}
