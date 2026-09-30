package com.bilipai.desktop.enhancementfixture

import com.android.purebilibili.feature.anime4k.*
import com.bilipai.desktop.player.*
import com.bilipai.desktop.plugins.DesktopVideoShaderResources
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.*

/** Unmounted actual MpvPlayer ownership tokens; no HWND, DLL initialization or synthetic GPU success. */
class SessionProof {
    private class Fixture(val initiallyEnabled: Boolean = false, val guarded: suspend ((() -> Boolean), Fixture) -> Unit = { owned, f -> if (owned()) f.enabled.value = true }) : AutoCloseable {
        val root = Files.createTempDirectory("bilipai-enhancement-owner-")
        val player = MpvPlayer()
        val config = MutableStateFlow(Anime4KConfig())
        val enabled = MutableStateFlow(initiallyEnabled)
        val remembered = mutableListOf<Boolean>()
        val pip = MutableStateFlow(false)
        val started = MutableStateFlow(true)
        var epoch = 10L
        var enableCalls = 0
        val session = DesktopVideoEnhancementSession(player, config, enabled,
            DesktopVideoShaderResources(root) { error("Offline fixture does not have a GPU frame") }, root,
            pip, started, { error("Root must use the guarded callback") },
            remembered::add, { epoch }, { stillOwned -> enableCalls++; guarded(stillOwned, this) })
        fun bind(bv: String, file: String = bv): Long = player.loadVersioned(PlaybackSource("file:///C:/$file.avi")).also { session.bindVideoIdentity(bv, it) }
        fun gateLaunch(): GatedDispatcher {
            val field = DesktopVideoEnhancementSession::class.java.getDeclaredField("scope").apply { isAccessible = true }
            (field.get(session) as CoroutineScope).cancel()
            return GatedDispatcher().also { field.set(session, CoroutineScope(SupervisorJob() + it)) }
        }
        @Suppress("UNCHECKED_CAST") fun failed(): MutableStateFlow<Boolean> =
            DesktopVideoEnhancementSession::class.java.getDeclaredField("pipelineFailed").apply { isAccessible = true }.get(session) as MutableStateFlow<Boolean>
        override fun close() { session.close(); player.close() }
    }

    @Test fun delayedToggleEntryCannotEnableOrRememberAfterAnotherBvOwnsThePlayer(): Unit = runBlocking {
        Fixture().use { f ->
            val gate = f.gateLaunch(); f.bind("BV-old")
            val toggle = assertNotNull(f.session.setCurrentVideoEnabled(true))
            assertEquals(0, f.enableCalls)
            f.bind("BV-new")
            gate.finish { toggle.isCompleted }
            assertEquals(0, f.enableCalls); assertFalse(f.enabled.value); assertTrue(f.remembered.isEmpty())
        }
    }
    @Test fun delayedToggleEntryRejectsForeignNativeOwnerEvenBeforeUiIdentityRebind(): Unit = runBlocking {
        Fixture().use { f ->
            val gate = f.gateLaunch(); f.bind("BV-old")
            val toggle = assertNotNull(f.session.setCurrentVideoEnabled(true))
            f.player.loadVersioned(PlaybackSource("file:///C:/foreign.avi"))
            gate.finish { toggle.isCompleted }
            assertEquals(0, f.enableCalls); assertTrue(f.remembered.isEmpty())
        }
    }
    @Test fun delayedToggleEntryRejectsSameMidCredentialEpochChangeWithoutANewNativeToken(): Unit = runBlocking {
        Fixture().use { f ->
            val gate = f.gateLaunch(); val source = f.bind("BV-same")
            val toggle = assertNotNull(f.session.setCurrentVideoEnabled(true)); f.epoch++
            assertTrue(f.player.ownsSourceVersion(source))
            gate.finish { toggle.isCompleted }
            assertEquals(0, f.enableCalls); assertTrue(f.remembered.isEmpty())
        }
    }
    @Test fun disablingSameVideoWhileProviderEnableIsSuspendedRetiresTheActualPassedGuard(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        Fixture(guarded = { owned, f -> started.complete(Unit); resume.await(); if (owned()) f.enabled.value = true }).use { f ->
            f.bind("BV-toggle")
            val enable = assertNotNull(f.session.setCurrentVideoEnabled(true))
            withTimeout(3_000) { started.await() }
            assertNotNull(f.session.setCurrentVideoEnabled(false)).join()
            resume.complete(Unit); withTimeout(3_000) { enable.join() }
            assertFalse(f.enabled.value); assertEquals(listOf(false), f.remembered)
        }
    }
    @Test fun suspendedEnableRejectsSameMidEpochChangeAndNewBvBeforeItsSideEffect(): Unit = runBlocking {
        for (change in listOf("epoch", "bv", "foreign")) {
            val started = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
            Fixture(guarded = { owned, f -> started.complete(Unit); resume.await(); if (owned()) f.enabled.value = true }).use { f ->
                f.bind("BV-old")
                val enable = assertNotNull(f.session.setCurrentVideoEnabled(true)); withTimeout(3_000) { started.await() }
                when (change) { "epoch" -> f.epoch++; "bv" -> f.bind("BV-new"); else -> f.player.loadVersioned(PlaybackSource("file:///C:/foreign.avi")) }
                resume.complete(Unit); withTimeout(3_000) { enable.join() }
                assertFalse(f.enabled.value, change); assertTrue(f.remembered.isEmpty(), change)
            }
        }
    }
    @Test fun oneBvFailureDoesNotDisableTheNextBvAndSameBvPartsKeepTheirOverride(): Unit = runBlocking {
        Fixture(initiallyEnabled = true).use { f ->
            val gate = f.gateLaunch(); f.bind("BV-one")
            val toggle = assertNotNull(f.session.setCurrentVideoEnabled(true)); gate.finish { toggle.isCompleted }
            f.failed().value = true
            val part = f.bind("BV-one", "BV-one-second-part")
            assertTrue(f.failed().value)
            assertNotNull(f.session.setCurrentVideoEnabled(false)).also { gate.finish { it.isCompleted } }
            assertTrue(f.player.ownsSourceVersion(part))
            f.failed().value = true
            f.bind("BV-two")
            assertFalse(f.failed().value)
            val identity = DesktopVideoEnhancementSession::class.java.getDeclaredField("identity").apply { isAccessible = true }.get(f.session) as MutableStateFlow<*>
            assertTrue(identity.value.toString().contains("override=null"))
        }
    }
    @Test fun epochRebindResetsTheOldFailureAndManualOverrideButSameOwnerPartDoesNot(): Unit = runBlocking {
        Fixture(initiallyEnabled = true).use { f ->
            val gate = f.gateLaunch(); val source = f.bind("BV-same")
            val toggle = assertNotNull(f.session.setCurrentVideoEnabled(true)); gate.finish { toggle.isCompleted }
            f.failed().value = true; f.epoch++; f.session.bindVideoIdentity("BV-same", source)
            assertFalse(f.failed().value)
            val identity = DesktopVideoEnhancementSession::class.java.getDeclaredField("identity").apply { isAccessible = true }.get(f.session) as MutableStateFlow<*>
            assertTrue(identity.value.toString().contains("override=null"))
        }
    }
    @Test fun closeCancelsPendingToggleAndLateComposeBindIsAnIdempotentNoOp(): Unit = runBlocking {
        Fixture().use { f ->
            val gate = f.gateLaunch(); val source = f.bind("BV-close")
            val toggle = assertNotNull(f.session.setCurrentVideoEnabled(true)); f.session.close()
            f.session.bindVideoIdentity(null, source); f.session.bindVideoIdentity("BV-after-close", source + 1)
            gate.finish { toggle.isCompleted }
            assertEquals(0, f.enableCalls); assertTrue(f.remembered.isEmpty())
            assertTrue(f.player.ownsSourceVersion(source), "Session close must not stop another subsystem's owned media")
        }
    }
    @Test fun actualEnableExceptionBecomesSafeStateAndANewBvRetiresItsFailure(): Unit = runBlocking {
        Fixture(guarded = { _, _ -> throw IllegalStateException("Fixture failure") }).use { f ->
            val gate = f.gateLaunch(); f.bind("BV-failed")
            val toggle = assertNotNull(f.session.setCurrentVideoEnabled(true)); gate.finish { toggle.isCompleted }
            assertTrue(f.failed().value)
            assertEquals("启用画质增强失败，请重试", f.session.state.value.error)
            assertFalse(f.enabled.value); assertTrue(f.remembered.isEmpty())
            f.bind("BV-next"); assertFalse(f.failed().value)
        }
    }
    @Test fun sourceEpochPipVisibilityAudioAndHdrFlowsUseTheOriginalOutputPolicyWithoutInstallingShaders(): Unit = runBlocking {
        Fixture(initiallyEnabled = true).use { f ->
            f.config.value = Anime4KConfig(rememberAcrossVideos = true, rememberedEnabled = true)
            val source = f.bind("BV-policy")
            @Suppress("UNCHECKED_CAST")
            val output = MpvPlayer::class.java.getDeclaredField("mutableVideoOutput").apply { isAccessible = true }.get(f.player) as MutableStateFlow<PlayerVideoOutputState>
            // Fixture input only, never claimed as an observed GPU. Zero dimensions and an
            // unmounted/unready player prevent any shader read/install or DLL initialization.
            output.value = PlayerVideoOutputState(sourceVersion = source, maximumTextureDimension = 8192)
            suspend fun reason(expected: Anime4KBypassReason) = waitUntil { f.session.state.value.bypassReason == expected }
            reason(Anime4KBypassReason.NONE)
            f.pip.value = true; reason(Anime4KBypassReason.PICTURE_IN_PICTURE)
            f.pip.value = false; f.started.value = false; reason(Anime4KBypassReason.HOST_NOT_STARTED)
            f.started.value = true; f.player.setAudioOnly(true); reason(Anime4KBypassReason.AUDIO_ONLY)
            f.player.setAudioOnly(false); output.value = output.value.copy(gamma = "pq"); reason(Anime4KBypassReason.HDR_OR_DOLBY_VISION)
            output.value = output.value.copy(gamma = "hlg"); reason(Anime4KBypassReason.HDR_OR_DOLBY_VISION)
            output.value = output.value.copy(gamma = null, dolbyVisionProfile = 5); reason(Anime4KBypassReason.HDR_OR_DOLBY_VISION)
            output.value = output.value.copy(dolbyVisionProfile = null); reason(Anime4KBypassReason.NONE)
            f.epoch++; f.session.bindVideoIdentity("BV-policy", source)
            assertTrue(f.player.ownsSourceVersion(source)); reason(Anime4KBypassReason.NONE)
            f.player.loadVersioned(PlaybackSource("file:///C:/foreign-policy.avi")); reason(Anime4KBypassReason.DISABLED)
            assertTrue(f.player.videoShaderState.value.requestedFiles.isEmpty())
            assertFalse(f.session.state.value.active); assertFalse(f.player.state.value.ready)
        }
    }
}
