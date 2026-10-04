package com.bilipai.desktop.player

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class DesktopVideoEnhancementSessionTest {
    @Test fun automaticPreferenceCoversUnlabelledSourcesAndRemainsGlobalAcrossVideos() = runBlocking<Unit> {
        val enabled = MutableStateFlow(true)
        val writes = mutableListOf<Boolean>()
        MpvPlayer().use { player ->
            DesktopVideoEnhancementSession(player, enabled, MutableStateFlow(true), MutableStateFlow(false), {
                writes += it; enabled.value = it; CompletableDeferred(Unit)
            }).use { enhancement ->
                val live = player.loadVersioned(PlaybackSource("file:///C:/unlabelled-video-fixture.avi"))
                awaitState { enhancement.state.value.sourceVersion == live && enhancement.state.value.identity == "video:$live" }
                assertTrue(enhancement.state.value.requested)
                assertFalse(enhancement.state.value.active)
                assertTrue(player.videoShaderState.value.requestedFiles.isEmpty())
                assertNotNull(enhancement.setCurrentVideoEnabled(false)).join()
                awaitState { !enhancement.state.value.requested }
                val episode = player.loadVersioned(PlaybackSource("file:///C:/pgc-video-fixture.avi"))
                enhancement.bindVideoIdentity(null, episode)
                awaitState { enhancement.state.value.sourceVersion == episode }
                assertFalse(enhancement.state.value.requested)
                assertEquals(listOf(false), writes)
                assertTrue(player.videoShaderState.value.requestedFiles.isEmpty())
            }
        }
    }

    @Test fun failedOldSettingCompletionCannotPublishErrorIntoAReplacementSource() = runBlocking<Unit> {
        val submitted = CompletableDeferred<Unit>()
        val persistence = CompletableDeferred<Unit>()
        MpvPlayer().use { player ->
            DesktopVideoEnhancementSession(player, MutableStateFlow(true), MutableStateFlow(true), MutableStateFlow(false), {
                submitted.complete(Unit); persistence
            }).use { enhancement ->
                val old = player.loadVersioned(PlaybackSource("file:///C:/old-video-fixture.avi"))
                awaitState { enhancement.state.value.sourceVersion == old }
                val job = assertNotNull(enhancement.setCurrentVideoEnabled(false))
                withTimeout(3_000) { submitted.await() }
                val replacement = player.loadVersioned(PlaybackSource("file:///C:/replacement-video-fixture.avi"))
                enhancement.bindVideoIdentity(null, replacement)
                awaitState { enhancement.state.value.sourceVersion == replacement }
                persistence.completeExceptionally(IllegalStateException("private fixture persistence failure"))
                withTimeout(3_000) { job.join() }
                assertNull(enhancement.state.value.error)
                assertEquals(replacement, enhancement.state.value.sourceVersion)
            }
        }
    }

    @Test fun closingThePlayerRevokesToggleEvenIfTheNumericSourceVersionDidNotChange() = runBlocking<Unit> {
        val writes = mutableListOf<Boolean>()
        MpvPlayer().use { player ->
            DesktopVideoEnhancementSession(player, MutableStateFlow(true), MutableStateFlow(true), MutableStateFlow(false), {
                writes += it; CompletableDeferred(Unit)
            }).use { enhancement ->
                val version = player.loadVersioned(PlaybackSource("file:///C:/closing-video-fixture.avi"))
                awaitState { enhancement.state.value.sourceVersion == version }
                player.close()
                assertEquals(version, player.currentSourceVersion)
                assertNull(enhancement.setCurrentVideoEnabled(false))
                assertTrue(writes.isEmpty())
            }
        }
    }

    @Test fun anUnmountedSessionDoesNotClearAForeignNativeConfigurationWhenClosing() = runBlocking<Unit> {
        MpvPlayer().use { player ->
            val enhancement = DesktopVideoEnhancementSession(player, MutableStateFlow(false),
                MutableStateFlow(true), MutableStateFlow(false), { CompletableDeferred(Unit) })
            val version = player.loadVersioned(PlaybackSource("file:///C:/foreign-config-fixture.avi"))
            awaitState { enhancement.state.value.sourceVersion == version }
            val foreign = assertNotNull(player.setNvidiaVideoEnhancementIfSourceVersion(version, NvidiaVideoOptions(2.0)))
            enhancement.close()
            assertEquals(foreign, player.nvidiaVideoState.value.configurationVersion)
            assertNull(enhancement.setCurrentVideoEnabled(true))
            assertFalse(enhancement.state.value.active)
        }
    }

    private suspend fun awaitState(condition: () -> Boolean) = withTimeout(3_000) {
        while (!condition()) delay(10)
    }
}
