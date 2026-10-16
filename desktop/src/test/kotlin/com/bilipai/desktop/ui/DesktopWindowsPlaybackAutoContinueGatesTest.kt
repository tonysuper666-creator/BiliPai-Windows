package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.DesktopOriginalPlaybackSettingsPreferences
import com.android.purebilibili.core.store.DesktopOriginalVideoOwnerSettings
import com.android.purebilibili.core.store.PlaybackCompletionBehavior
import com.android.purebilibili.feature.video.viewmodel.PlaybackEndAction
import com.android.purebilibili.feature.video.viewmodel.resolvePlaybackEndAction
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** The pane's 自动播放下一个 and 列表/收藏夹连续播放 switches write exactly what the Windows VM reads for 自动连播. */
class DesktopWindowsPlaybackAutoContinueGatesTest {
    private fun context(root: Path) =
        DesktopOriginalPlayerSettingsContext(DesktopPluginContext(DesktopPluginStore(root)), { true }, { it(); true })

    /** The VM's own two getters feed the original completion policy. */
    private fun endAction(context: DesktopOriginalPlayerSettingsContext, behavior: PlaybackCompletionBehavior,
        isExternalPlaylist: Boolean, hasNext: Boolean) = resolvePlaybackEndAction(behavior,
        autoPlayEnabled = DesktopOriginalVideoOwnerSettings.getAutoPlaySync(context),
        isExternalPlaylist = isExternalPlaylist,
        externalPlaylistAutoContinueEnabled = DesktopOriginalVideoOwnerSettings.getExternalPlaylistAutoContinueSync(context),
        hasNextPageOrSeasonTarget = hasNext)

    private suspend fun paneWrite(context: DesktopOriginalPlayerSettingsContext, block: suspend () -> Unit) =
        // The same journal DesktopOriginalPlaybackSettingsWriteScope.launch enters for the pane's writer.
        DesktopOriginalPlaybackPreferenceOperation.run(context, { true }, block)

    @Test fun `both gates default on so a multi-part video and a list keep going`(): Unit = runBlocking {
        val context = context(Files.createTempDirectory("bp-windows-autoplay-gates-default-"))
        assertTrue(DesktopOriginalPlaybackSettingsPreferences.getAutoPlay(context).first())
        assertTrue(DesktopOriginalPlaybackSettingsPreferences.getExternalPlaylistAutoContinue(context).first())
        assertEquals(PlaybackEndAction.AUTO_CONTINUE,
            endAction(context, PlaybackCompletionBehavior.CONTINUE_CURRENT_LOGIC, isExternalPlaylist = false, hasNext = true))
        assertEquals(PlaybackEndAction.AUTO_CONTINUE,
            endAction(context, PlaybackCompletionBehavior.CONTINUE_CURRENT_LOGIC, isExternalPlaylist = true, hasNext = false))
    }

    @Test fun `auto play next off stops a multi-part video but not a list or an explicit order`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bp-windows-autoplay-gates-next-")
        val context = context(root)
        paneWrite(context) { DesktopOriginalPlaybackSettingsPreferences.setAutoPlay(context, false) }
        assertFalse(DesktopOriginalPlaybackSettingsPreferences.getAutoPlay(context).first())
        assertFalse(DesktopOriginalVideoOwnerSettings.getAutoPlaySync(context))
        assertEquals(PlaybackEndAction.STOP,
            endAction(context, PlaybackCompletionBehavior.CONTINUE_CURRENT_LOGIC, isExternalPlaylist = false, hasNext = true))
        assertEquals(PlaybackEndAction.AUTO_CONTINUE,
            endAction(context, PlaybackCompletionBehavior.CONTINUE_CURRENT_LOGIC, isExternalPlaylist = true, hasNext = false))
        assertEquals(PlaybackEndAction.PLAY_NEXT_IN_PLAYLIST,
            endAction(context, PlaybackCompletionBehavior.PLAY_IN_ORDER, isExternalPlaylist = false, hasNext = true))
        val disk = Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject
        assertFalse(disk.getValue("settings").jsonObject.getValue("auto_play").jsonPrimitive.boolean)
        assertFalse(disk.getValue("auto_play_cache").jsonObject.getValue("auto_play_enabled").jsonPrimitive.boolean)
    }

    @Test fun `playlist continue off stops a list but not a multi-part video`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bp-windows-autoplay-gates-list-")
        val context = context(root)
        paneWrite(context) { DesktopOriginalPlaybackSettingsPreferences.setExternalPlaylistAutoContinue(context, false) }
        assertFalse(DesktopOriginalPlaybackSettingsPreferences.getExternalPlaylistAutoContinue(context).first())
        assertFalse(DesktopOriginalVideoOwnerSettings.getExternalPlaylistAutoContinueSync(context))
        assertEquals(PlaybackEndAction.STOP,
            endAction(context, PlaybackCompletionBehavior.CONTINUE_CURRENT_LOGIC, isExternalPlaylist = true, hasNext = true))
        assertEquals(PlaybackEndAction.AUTO_CONTINUE,
            endAction(context, PlaybackCompletionBehavior.CONTINUE_CURRENT_LOGIC, isExternalPlaylist = false, hasNext = true))
        assertEquals(PlaybackEndAction.STOP,
            endAction(context, PlaybackCompletionBehavior.CONTINUE_CURRENT_LOGIC, isExternalPlaylist = false, hasNext = false))
        val disk = Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject
        assertFalse(disk.getValue("settings").jsonObject.getValue("external_playlist_auto_continue").jsonPrimitive.boolean)
        assertFalse(disk.getValue("auto_play_cache").jsonObject.getValue("external_playlist_auto_continue").jsonPrimitive.boolean)
    }

    @Test fun `turning a gate back on restores the original continuation`(): Unit = runBlocking {
        val context = context(Files.createTempDirectory("bp-windows-autoplay-gates-restore-"))
        paneWrite(context) {
            DesktopOriginalPlaybackSettingsPreferences.setAutoPlay(context, false)
            DesktopOriginalPlaybackSettingsPreferences.setExternalPlaylistAutoContinue(context, false)
        }
        paneWrite(context) {
            DesktopOriginalPlaybackSettingsPreferences.setAutoPlay(context, true)
            DesktopOriginalPlaybackSettingsPreferences.setExternalPlaylistAutoContinue(context, true)
        }
        assertEquals(PlaybackEndAction.AUTO_CONTINUE,
            endAction(context, PlaybackCompletionBehavior.CONTINUE_CURRENT_LOGIC, isExternalPlaylist = false, hasNext = true))
        assertEquals(PlaybackEndAction.AUTO_CONTINUE,
            endAction(context, PlaybackCompletionBehavior.CONTINUE_CURRENT_LOGIC, isExternalPlaylist = true, hasNext = false))
    }
}
