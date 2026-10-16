package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.DesktopOriginalPortraitSettings
import com.android.purebilibili.core.store.player.DEFAULT_AUDIO_QUALITY_FOLLOW_LAST
import com.android.purebilibili.core.store.player.DesktopOriginalVideoPlayerSettings
import com.android.purebilibili.feature.video.playback.audio.AUDIO_QUALITY_AUTO
import com.android.purebilibili.feature.video.playback.audio.AUDIO_QUALITY_DOLBY
import com.android.purebilibili.feature.video.playback.audio.AUDIO_QUALITY_HI_RES
import com.android.purebilibili.feature.video.playback.audio.resolveRequestedAudioQuality
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.*

/** The production port read back through the real original setters (Settings page default audio
 * quality, PGC overlay write) and the real Store files. No fixture lambdas, native, UI or network. */
class DesktopBangumiPgcAudioPreferencesTest {
    private fun store() = DesktopPluginStore(Files.createTempDirectory("bp-pgc-audio-preferences-"))
    private fun context(store: DesktopPluginStore) = DesktopOriginalPlayerSettingsContext(
        DesktopPluginContext(store), { true }, { action -> action(); true })
    private fun withStore(block: (DesktopPluginStore) -> Unit) {
        val store = store()
        try { block(store) } finally { store.root.toFile().deleteRecursively() }
    }
    /** The exact resolution the original PGC VM applies to the two port values on every load. */
    private fun resolved(port: DesktopBangumiPgcAudioPreferences) =
        resolveRequestedAudioQuality(port.defaultQuality(), port.rememberedQuality())

    @Test fun `fresh store reads the original defaults and resolves to automatic`() = withStore { store ->
        val port = DesktopBangumiPgcAudioPreferences(context(store))
        assertEquals(DEFAULT_AUDIO_QUALITY_FOLLOW_LAST, port.defaultQuality())
        assertEquals(-1, port.rememberedQuality())
        assertEquals(AUDIO_QUALITY_AUTO, resolved(port))
    }

    @Test fun `Settings page default audio quality is what PGC resolves`() = withStore { store ->
        val context = context(store)
        val port = DesktopBangumiPgcAudioPreferences(context)
        runBlocking { DesktopOriginalVideoPlayerSettings.setDefaultAudioQuality(context, AUDIO_QUALITY_HI_RES) }
        assertEquals(AUDIO_QUALITY_HI_RES, port.defaultQuality())
        assertEquals(AUDIO_QUALITY_HI_RES, resolved(port))
        // An explicit default wins over whatever the overlay remembered.
        runBlocking { DesktopOriginalPortraitSettings.setAudioQuality(context, AUDIO_QUALITY_DOLBY) }
        assertEquals(AUDIO_QUALITY_HI_RES, resolved(port))
        runBlocking { DesktopOriginalVideoPlayerSettings.setDefaultAudioQuality(context, AUDIO_QUALITY_AUTO) }
        assertEquals(AUDIO_QUALITY_AUTO, resolved(port))
        // A stored id the original policy does not know is normalized to automatic by the VM's resolver.
        runBlocking { DesktopOriginalVideoPlayerSettings.setDefaultAudioQuality(context, 30280) }
        assertEquals(30280, port.defaultQuality())
        assertEquals(AUDIO_QUALITY_AUTO, resolved(port))
    }

    @Test fun `the PGC overlay write is read back for the next load when the default follows the last pick`() = withStore { store ->
        val context = context(store)
        val port = DesktopBangumiPgcAudioPreferences(context)
        assertEquals(AUDIO_QUALITY_AUTO, resolved(port))
        // Exactly the write the Entry's setAudioQuality lambda performs after a successful switch.
        runBlocking { DesktopOriginalPortraitSettings.setAudioQuality(context, AUDIO_QUALITY_HI_RES) }
        assertEquals(AUDIO_QUALITY_HI_RES, port.rememberedQuality())
        assertEquals(DEFAULT_AUDIO_QUALITY_FOLLOW_LAST, port.defaultQuality())
        assertEquals(AUDIO_QUALITY_HI_RES, resolved(port))
        runBlocking { DesktopOriginalPortraitSettings.setAudioQuality(context, AUDIO_QUALITY_DOLBY) }
        assertEquals(AUDIO_QUALITY_DOLBY, resolved(port))
        // The remembered pick is ignored once the default is an explicit choice.
        runBlocking { DesktopOriginalVideoPlayerSettings.setDefaultAudioQuality(context, AUDIO_QUALITY_AUTO) }
        assertEquals(AUDIO_QUALITY_DOLBY, port.rememberedQuality())
        assertEquals(AUDIO_QUALITY_AUTO, resolved(port))
    }

    @Test fun `the port reads the key family the original setters write`() = withStore { store ->
        val context = context(store)
        runBlocking {
            DesktopOriginalVideoPlayerSettings.setDefaultAudioQuality(context, AUDIO_QUALITY_DOLBY)
            DesktopOriginalPortraitSettings.setAudioQuality(context, AUDIO_QUALITY_HI_RES)
        }
        val mirror = store.preferences("quality_settings")
        assertEquals(AUDIO_QUALITY_DOLBY, mirror["default_audio_quality"]!!.jsonPrimitive.int)
        assertEquals(AUDIO_QUALITY_HI_RES, mirror["audio_quality"]!!.jsonPrimitive.int)
        val port = DesktopBangumiPgcAudioPreferences(context)
        assertEquals(AUDIO_QUALITY_DOLBY, port.defaultQuality())
        assertEquals(AUDIO_QUALITY_HI_RES, port.rememberedQuality())
    }

    @Test fun `values persist across a real cold start and a port never caches`() = withStore { store ->
        val context = context(store)
        val port = DesktopBangumiPgcAudioPreferences(context)
        assertEquals(AUDIO_QUALITY_AUTO, resolved(port))
        runBlocking { DesktopOriginalVideoPlayerSettings.setDefaultAudioQuality(context, AUDIO_QUALITY_HI_RES) }
        // The same already constructed port sees the change on its very next read.
        assertEquals(AUDIO_QUALITY_HI_RES, port.defaultQuality())
        runBlocking { DesktopOriginalPortraitSettings.setAudioQuality(context, AUDIO_QUALITY_DOLBY) }
        // A second Store over a live root shares its in-memory backing and would prove nothing.
        // Freezing the first generation (as restore does) makes the next Store re-read the file.
        store.freezeWrites()
        val cold = DesktopBangumiPgcAudioPreferences(context(DesktopPluginStore(store.root)))
        assertEquals(AUDIO_QUALITY_HI_RES, cold.defaultQuality())
        assertEquals(AUDIO_QUALITY_DOLBY, cold.rememberedQuality())
    }

    @Test fun `separate stores never share an audio preference`() {
        val left = store(); val right = store()
        try {
            val leftContext = context(left)
            runBlocking { DesktopOriginalVideoPlayerSettings.setDefaultAudioQuality(leftContext, AUDIO_QUALITY_HI_RES) }
            assertEquals(AUDIO_QUALITY_HI_RES, DesktopBangumiPgcAudioPreferences(leftContext).defaultQuality())
            assertEquals(DEFAULT_AUDIO_QUALITY_FOLLOW_LAST, DesktopBangumiPgcAudioPreferences(context(right)).defaultQuality())
        } finally {
            left.root.toFile().deleteRecursively(); right.root.toFile().deleteRecursively()
        }
    }
}
