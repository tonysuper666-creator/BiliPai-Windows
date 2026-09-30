package com.bilipai.desktop.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.settings.AppThemeMode
import com.android.purebilibili.feature.space.*
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class DesktopCompleteSpaceUiTest {
    @Test fun realSpaceHomePointerDispatchesSidAndKeepsTabSelectionAcrossThemes(): Unit = runBlocking {
        val repository = DesktopRepository(DesktopSessionStore.temporary())
        val social = DesktopSocialRepository(repository)
        val community = DesktopCommunityRepository(repository)
        val space = DesktopSpaceRepository(repository)
        val backend = DesktopSpaceContributionsRepository(repository)
        val directory = Path.of("build/reports/space-parity-integration")
        Files.createDirectories(directory)
        for (style in AppUiStyle.entries) for (mode in listOf(AppThemeMode.LIGHT, AppThemeMode.DARK)) for (width in listOf(640, 960)) {
            val original = SpaceAggregateData(card = SpaceAggregateCard(mid = "22", name = "原版空间", face = "fixture-avatar"),
                defaultTab = "home", audios = SpaceAggregateAudioSection(1, listOf(SpaceAudioItem(id = 501, aid = 997,
                    title = "唯一歌曲", author = "歌手", uname = "上传者", intro = "歌曲介绍"))))
            val overview = assertNotNull(DesktopSpaceMetadata(original, resolveSpaceMainTabs(original.tab2), resolveSpaceContributionTabs(original.tab2)).toOverview())
            val memory = DesktopBrowseMemory()
            val state = memory.screen(listOf("complete-up-space", repository.sessionEpoch, null, 22L)) { DesktopCompleteSpaceState() }
            state.acceptOverview(overview)
            state.collectionsAttempted = true
            state.headerExpanded = false
            var audioSid = 0L
            var videoOpens = 0
            val scene = ImageComposeScene(width, 760, Density(1f)) {
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style, themeMode = mode)) {
                    Surface {
                        CompositionLocalProvider(LocalDesktopBrowseMemory provides memory, LocalCommunityFeedMemory provides memory.feeds) {
                            DesktopCompleteSpaceScreen(22, repository, social, community, space, backend,
                                onVideo = { videoOpens++ }, onUser = {}, onArticle = {}, onDynamic = {}, onLive = {}, onBangumi = {},
                                onAudio = { audioSid = it }, onCourse = {}, onResource = {}, onCollection = { _, _, _ -> },
                                onPlaylist = {}, onLogin = {}, onExternalUrl = {})
                        }
                    }
                }
            }
            var nanos = 0L
            suspend fun settle() { repeat(5) { nanos += 30_000_000; scene.render(nanos).close(); yield() } }
            fun nodes(root: SemanticsNode): List<SemanticsNode> = listOf(root) + root.children.flatMap(::nodes)
            fun textNode(text: String) = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                .first { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { value -> value.text == text } }
            try {
                settle()
                val point = textNode("唯一歌曲").boundsInRoot.center
                assertTrue(point.x in 0f..width.toFloat() && point.y in 0f..760f)
                scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = nanos / 1_000_000, buttons = PointerButtons(isPrimaryPressed = true))
                scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = nanos / 1_000_000 + 41, buttons = PointerButtons())
                settle()
                assertEquals(501, audioSid, "$style/$mode/$width must use SID rather than related video aid")
                assertEquals(0, videoOpens)
                assertEquals(SpaceMainTab.HOME, state.selectedMain)
                assertNull(state.failure)
                scene.render(nanos).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { data ->
                    Files.write(directory.resolve("${style.name.lowercase()}-${mode.name.lowercase()}-$width.png"), data.bytes)
                } }
            } finally { scene.close() }
        }
    }
}
