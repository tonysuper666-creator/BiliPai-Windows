package com.bilipai.desktop.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.feature.video.ui.components.VideoAspectRatio
import com.android.purebilibili.feature.video.ui.overlay.*
import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class, coil3.annotation.DelicateCoilApi::class)
class DesktopPortraitBufferingCompositionTest {
    @Test fun completePortraitOverlayShowsAndRemovesTheRealRecoveryAnimation() {
        val scheduler=TestCoroutineScheduler()
        val errors=mutableListOf<Throwable>()
        val scope=CoroutineScope(SupervisorJob()+StandardTestDispatcher(scheduler)+CoroutineExceptionHandler { _,e -> errors+=e })
        val folder=java.nio.file.Files.createTempDirectory("portrait-buffering")
        val previous=coil3.SingletonImageLoader.get(coil3.PlatformContext.INSTANCE)
        coil3.SingletonImageLoader.reset()
        val images=DesktopApplicationImageLoader(com.bilipai.desktop.data.DesktopRepository(com.bilipai.desktop.data.DesktopSessionStore.temporary()),folder.resolve("images"),{true})
        var recovering by mutableStateOf(true)
        val noAction: () -> Unit = { error("Drawing must not dispatch a business action") }
        val scene=ImageComposeScene(480,800,Density(1f),coroutineContext=scope.coroutineContext) {
            MaterialTheme { CompositionLocalProvider(LocalDesktopApplicationImageLoader provides images,
                LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled=false), LocalSystemReduceMotion provides true) {
                PortraitFullscreenOverlay(title="Fixture video", isPlaying=false, progress=PlayerProgress(10_000,90_000,30_000),
                    isLiked=false,likeStatusKnown=true,isCoined=false,isFavorited=false,onLikeClick=noAction,onCoinClick=noAction,
                    onFavoriteClick=noAction,followStatusKnown=true,currentSpeed=1f,currentQualityLabel="1080P",currentAudioQualityLabel="默认",
                    isHiResAudioSelected=false,isDolbyAudioSelected=false,currentRatio=VideoAspectRatio.FIT,danmakuEnabled=false,
                    isStatusBarHidden=false,showControls=true,isPlaybackRecovering=recovering,onBack=noAction,onPlayPause=noAction,
                    onSeek={error("No seek")},onSpeedClick=noAction,onQualityClick=noAction,onAudioQualityClick=noAction,onRatioClick=noAction,
                    onDanmakuToggle=noAction,onDanmakuInputClick=noAction,onToggleStatusBar=noAction,onRotateToLandscape=noAction)
            } }
        }
        fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n)+n.children.flatMap(::nodes)
        fun all()=scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        fun loading()=all().filter { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("加载中")==true }
        fun frame() { repeat(60) { Snapshot.sendApplyNotifications();scheduler.runCurrent();scene.render(scheduler.currentTime*1_000_000).close();scheduler.advanceTimeBy(16) };scheduler.runCurrent();assertTrue(errors.isEmpty(),errors.toString()) }
        try {
            frame();val indicator=loading().single(); assertEquals(32f,indicator.boundsInRoot.width);assertEquals(32f,indicator.boundsInRoot.height)
            assertTrue(all().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text=="正在恢复播放..." }==true })
            recovering=false;frame();assertTrue(loading().isEmpty())
            recovering=true;frame();assertEquals(1,loading().size)
        } finally { scene.close();scope.cancel();scheduler.runCurrent();images.close();coil3.SingletonImageLoader.setUnsafe(previous);folder.toFile().deleteRecursively() }
    }
}
