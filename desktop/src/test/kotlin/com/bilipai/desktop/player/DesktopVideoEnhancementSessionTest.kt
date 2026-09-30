package com.bilipai.desktop.player

import com.android.purebilibili.feature.anime4k.*
import com.bilipai.desktop.plugins.DesktopVideoShaderResources
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Files
import kotlin.test.*

class DesktopVideoEnhancementSessionTest {
    @Test fun manualVideoSwitchPersistsAcrossPartsOfTheSameBvAndResetsForTheNextBv() = runBlocking<Unit> {
        val cache = Files.createTempDirectory("bilipai-enhancement-session-fixture-")
        val configurations = MutableStateFlow(Anime4KConfig())
        val enabled = MutableStateFlow(true)
        val remembered = mutableListOf<Boolean>()
        try {
            MpvPlayer().use { player ->
                DesktopVideoEnhancementSession(player, configurations, enabled,
                    DesktopVideoShaderResources(cache) { error("No GPU/frame exists in this offline fixture.") }, cache,
                    MutableStateFlow(false), MutableStateFlow(true), {}, remembered::add).use { enhancement ->
                    val first = player.loadVersioned(PlaybackSource("file:///C:/same-bv-first-part.avi"))
                    enhancement.bindVideoIdentity("BV-fixture-one", first)
                    assertNotNull(enhancement.setCurrentVideoEnabled(true)).join()
                    awaitState { enhancement.state.value.sourceVersion == first && enhancement.state.value.requested }
                    val nextPart = player.loadVersioned(PlaybackSource("file:///C:/same-bv-second-part.avi"))
                    enhancement.bindVideoIdentity("BV-fixture-one", nextPart)
                    awaitState { enhancement.state.value.sourceVersion == nextPart && enhancement.state.value.requested }
                    val nextVideo = player.loadVersioned(PlaybackSource("file:///C:/another-bv.avi"))
                    enhancement.bindVideoIdentity("BV-fixture-two", nextVideo)
                    awaitState { enhancement.state.value.sourceVersion == nextVideo && !enhancement.state.value.requested }
                    assertEquals(listOf(true), remembered)
                    assertFalse(enhancement.state.value.available)
                    assertEquals(Anime4KBypassReason.DISABLED, enhancement.state.value.bypassReason)
                    assertTrue(player.videoShaderState.value.requestedFiles.isEmpty())
                }
            }
        } finally { Files.deleteIfExists(cache) }
    }

    @Test fun aLateEnableCompletionCannotRememberTheSwitchForAReplacedNativeOwner() = runBlocking<Unit> {
        val cache = Files.createTempDirectory("bilipai-enhancement-cancel-fixture-")
        val enableStarted = CompletableDeferred<Unit>()
        val allowEnable = CompletableDeferred<Unit>()
        val enabled = MutableStateFlow(false)
        val remembered = mutableListOf<Boolean>()
        try {
            MpvPlayer().use { player ->
                DesktopVideoEnhancementSession(player, MutableStateFlow(Anime4KConfig()), enabled,
                    DesktopVideoShaderResources(cache) { error("No GPU/frame exists in this offline fixture.") }, cache,
                    MutableStateFlow(false), MutableStateFlow(true), {
                        enableStarted.complete(Unit)
                        allowEnable.await()
                        enabled.value = true
                    }, remembered::add).use { enhancement ->
                    val first = player.loadVersioned(PlaybackSource("file:///C:/owned.avi"))
                    enhancement.bindVideoIdentity("BV-old", first)
                    val request = assertNotNull(enhancement.setCurrentVideoEnabled(true))
                    withTimeout(3_000) { enableStarted.await() }
                    val replacement = player.loadVersioned(PlaybackSource("file:///C:/foreign.avi"))
                    allowEnable.complete(Unit)
                    withTimeout(3_000) { request.join() }
                    assertTrue(remembered.isEmpty())
                    assertNull(enhancement.setCurrentVideoEnabled(false))
                    assertNotEquals(first, replacement)
                }
            }
        } finally { allowEnable.complete(Unit); Files.deleteIfExists(cache) }
    }

    private suspend fun awaitState(condition: () -> Boolean) = withTimeout(3_000) {
        while (!condition()) delay(10)
    }

    @Test fun closingAnUnmountedNativePlayerRevokesItsOwnerEvenWhenItsNumericVersionDidNotChange() = runBlocking<Unit> {
        val cache = Files.createTempDirectory("bilipai-enhancement-close-fixture-")
        val enableStarted = CompletableDeferred<Unit>()
        val allowEnable = CompletableDeferred<Unit>()
        val enabled = MutableStateFlow(false)
        val remembered = mutableListOf<Boolean>()
        try {
            MpvPlayer().use { player ->
                DesktopVideoEnhancementSession(player, MutableStateFlow(Anime4KConfig()), enabled,
                    DesktopVideoShaderResources(cache) { error("No GPU/frame exists in this offline fixture.") }, cache,
                    MutableStateFlow(false), MutableStateFlow(true), {
                        enableStarted.complete(Unit)
                        allowEnable.await()
                        enabled.value = true
                    }, remembered::add).use { enhancement ->
                    val owner = player.loadVersioned(PlaybackSource("file:///C:/closed-owner.avi"))
                    enhancement.bindVideoIdentity("BV-closing", owner)
                    val request = assertNotNull(enhancement.setCurrentVideoEnabled(true))
                    withTimeout(3_000) { enableStarted.await() }
                    assertTrue(player.ownsSourceVersion(owner))
                    player.close()
                    assertEquals(owner, player.currentSourceVersion)
                    assertFalse(player.ownsSourceVersion(owner))
                    allowEnable.complete(Unit)
                    withTimeout(3_000) { request.join() }
                    assertTrue(remembered.isEmpty())
                    assertNull(enhancement.setCurrentVideoEnabled(false))
                }
            }
        } finally { allowEnable.complete(Unit); Files.deleteIfExists(cache) }
    }
}
