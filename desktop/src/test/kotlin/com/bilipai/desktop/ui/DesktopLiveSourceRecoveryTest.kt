package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.LivePlayUrlResponse
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.*
import com.bilipai.desktop.player.PlaybackSource as NativePlaybackSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlin.test.*

/** Original resolver -> retained live memory -> actual MPV source-CAS. No HWND/native core/network. */
class DesktopLiveSourceRecoveryTest {
    private val room = LiveRoomDetails(123, "Fixture", "", "Synthetic", "", 1, "生活", 1)
    private fun stream(host: String = "cdn.example", backups: Boolean = true, second: Boolean = true,
        requested: Int = 10000): LivePlaybackInfo {
        val urls = if (backups) """,{"host":"https://backup.example","extra":"?backup"}""" else ""
        val alternate = if (second) """,{"codec_name":"hevc","current_qn":400,"accept_qn":[400],"base_url":"/alternate.m3u8","url_info":[{"host":"https://alternate.example","extra":"?alt"}]}""" else ""
        val response = Json { ignoreUnknownKeys = true }.decodeFromString<LivePlayUrlResponse>(
            """{"code":0,"data":{"playurl_info":{"playurl":{"g_qn_desc":[{"qn":10000,"desc":"原画"},{"qn":400,"desc":"蓝光"},{"qn":150,"desc":"高清"}],"stream":[{"protocol_name":"http_hls","format":[{"format_name":"fmp4","codec":[{"codec_name":"avc","current_qn":150,"accept_qn":[10000,150],"base_url":"/live.m3u8","url_info":[{"host":"https://$host","extra":"?first"}$urls]}$alternate]}]}]}}}}""")
        return requireNotNull(DesktopMediaRepository.selectLive(requireNotNull(response.data), room, requested))
    }

    private inner class Fixture(parent: CoroutineScope, val initial: LivePlaybackInfo = stream()) : AutoCloseable {
        val player = MpvPlayer()
        val memory = DesktopLivePageMemory(parent, player)
        var accountCurrent = true
        var beforeAdmission: (() -> Unit)? = null
        val reloadRequests = mutableListOf<Triple<Long, Int, Boolean>>()
        var reload: suspend (LiveRoomDetails, Int, Boolean, () -> Boolean) -> LivePlaybackInfo = { _, _, _, _ -> initial }
        val ports = object : DesktopLiveRecoveryPorts {
            override fun isAccountCurrent() = accountCurrent
            override fun admit(action: () -> Unit): Boolean {
                if (!accountCurrent) return false
                beforeAdmission?.also { beforeAdmission = null }?.invoke()
                if (!accountCurrent) return false
                action(); return true
            }
            override suspend fun reload(room: LiveRoomDetails, quality: Int, onlyAudio: Boolean, current: () -> Boolean): LivePlaybackInfo {
                check(current()); reloadRequests += Triple(room.roomId, quality, onlyAudio)
                return this@Fixture.reload(room, quality, onlyAudio, current)
            }
        }
        init {
            memory.room = room
            player.loadVersioned(initial.source.toNativePlayback())
            memory.installLivePlayback(initial, requireNotNull(player.currentSourceSnapshot()), ports)
            memory.loaded = true
        }
        fun binding() = requireNotNull(memory.captureLiveSourceBinding())
        fun fail(attempt: Long, kind: PlayerFailureKind = PlayerFailureKind.NETWORK, http: Int? = 403): PlayerFailure {
            val failure = PlayerFailure(kind, -13, "Synthetic live failure", sourceVersion = player.currentSourceVersion,
                attemptId = attempt, httpStatus = http)
            state(player).value = player.state.value.copy(failure = failure, error = failure.safeMessage)
            return failure
        }
        override fun close() { memory.close(); player.close() }
    }
    private suspend fun settle() { repeat(8) { yield() } }

    @Test fun actualFailureConsumerAdvancesUrlThenCandidateWithOwnQualityAndRetainedPause(): Unit = runBlocking {
        withTimeout(5_000) {
            Fixture(this).use { f ->
                val version = f.player.currentSourceVersion
                f.player.setPaused(true)
                f.fail(1); settle()
                assertEquals("https://backup.example/live.m3u8?backup", f.memory.stream?.source?.videoUrl)
                assertEquals(1, f.memory.stream?.urlIndex)
                assertEquals(150, f.memory.stream?.source?.quality)
                f.fail(2); settle()
                assertEquals("https://alternate.example/alternate.m3u8?alt", f.memory.stream?.source?.videoUrl)
                assertEquals(1, f.memory.stream?.candidateIndex)
                assertEquals(listOf(400), f.memory.stream?.qualities?.map { it.id })
                assertEquals(400, f.memory.stream?.source?.quality)
                assertEquals(10000, f.memory.quality)
                assertEquals(version, f.player.currentSourceVersion)
                assertTrue(f.player.state.value.paused)
                assertTrue(f.memory.loaded); assertNull(f.memory.error)
                assertNull(f.player.state.value.failure)
                assertTrue(f.reloadRequests.isEmpty())
            }
        }
    }

    @Test fun exhaustedSourcesReloadOnceWithOriginalRequestedQualityAndOnlyAudio(): Unit = runBlocking {
        withTimeout(5_000) {
            Fixture(this, stream(backups = false, second = false, requested = 400)).use { f ->
                f.memory.quality = 400; f.memory.onlyAudio = true
                f.reload = { _, quality, audio, current ->
                    assertEquals(400, quality); assertTrue(audio); assertTrue(current())
                    stream("fresh.example", backups = false, second = false, requested = quality)
                }
                f.fail(1); settle()
                assertEquals(listOf(Triple(123L, 400, true)), f.reloadRequests)
                assertEquals("https://fresh.example/live.m3u8?first", f.memory.stream?.source?.videoUrl)
                assertEquals(0, f.memory.remainingLiveReloadAttempts)
                f.fail(2); settle()
                assertEquals(1, f.reloadRequests.size)
                assertEquals("直播流恢复失败，请稍后重试", f.memory.error)
                assertEquals(400, f.memory.quality)
            }
        }
    }

    @Test fun duplicateFailureDoesNotSpendAnotherReloadOrReplay(): Unit = runBlocking {
        withTimeout(5_000) {
            Fixture(this, stream(backups = false, second = false)).use { f ->
                val result = CompletableDeferred<LivePlaybackInfo>()
                f.reload = { _, _, _, _ -> result.await() }
                val binding = f.binding(); val failure = f.fail(1)
                binding.recover(failure); binding.recover(failure); settle()
                assertEquals(1, f.reloadRequests.size)
                assertEquals(0, f.memory.remainingLiveReloadAttempts)
                result.complete(stream("fresh.example", backups = false, second = false)); settle()
                assertEquals("https://fresh.example/live.m3u8?first", f.memory.stream?.source?.videoUrl)
            }
        }
    }

    @Test fun normalEndedAndUnknownFailureNeverAdvanceOrReload(): Unit = runBlocking {
        withTimeout(5_000) {
            Fixture(this).use { f ->
                val source = requireNotNull(f.player.currentSourceSnapshot())
                state(f.player).value = f.player.state.value.copy(ended = true)
                settle()
                assertTrue(f.player.ownsSourceSnapshot(source)); assertTrue(f.reloadRequests.isEmpty())
                state(f.player).value = f.player.state.value.copy(ended = false)
                f.fail(1, PlayerFailureKind.UNKNOWN, null); settle()
                assertSame(f.initial, f.memory.stream)
                assertTrue(f.player.ownsSourceSnapshot(source)); assertTrue(f.reloadRequests.isEmpty())
            }
        }
    }

    @Test fun manualSelectionConsumesActualCandidateMetadataAndResetsBoundedBudget(): Unit = runBlocking {
        withTimeout(5_000) {
            Fixture(this).use { f ->
                val selection = DesktopLiveSourceSelection(f.binding())
                f.memory.remainingLiveReloadAttempts = 0
                assertFalse(selection.switch(20, 0)); assertFalse(selection.switch(0, -1))
                assertTrue(selection.switch(1, 0))
                assertEquals(400, f.memory.stream?.source?.quality)
                assertEquals(listOf(400), f.memory.stream?.qualities?.map { it.id })
                assertEquals(1, f.memory.remainingLiveReloadAttempts)
                assertEquals(10000, f.memory.quality)
                assertFalse(selection.current()) // Receipt was replaced, even with same numeric source version.
                assertFalse(selection.switch(0, 0))
            }
        }
    }

    @Test fun closingSelectorRejectsQueuedClickIncludingFinalAdmission(): Unit = runBlocking {
        withTimeout(5_000) {
            Fixture(this).use { f ->
                val old = DesktopLiveSourceSelection(f.binding()); old.close()
                assertFalse(old.switch(0, 1))
                val live = DesktopLiveSourceSelection(f.binding())
                f.beforeAdmission = { live.close() }
                assertFalse(live.switch(0, 1)); assertSame(f.initial, f.memory.stream)
            }
        }
    }

    @Test fun fullSourceReplacementAtFinalAdmissionRejectsOldManualAndFailure(): Unit = runBlocking {
        withTimeout(5_000) {
            Fixture(this).use { f ->
                val selection = DesktopLiveSourceSelection(f.binding())
                f.beforeAdmission = { f.player.loadVersioned(NativePlaybackSource("https://new-owner.example/replacement")) }
                assertFalse(selection.switch(0, 1))
                assertEquals("https://new-owner.example/replacement", f.player.currentSourceSnapshot()?.source?.videoUrl)
                assertSame(f.initial, f.memory.stream)
                val version = f.player.currentSourceVersion
                f.fail(1); settle()
                assertEquals(version, f.player.currentSourceVersion); assertTrue(f.reloadRequests.isEmpty())
            }
        }
    }

    @Test fun sameValueNewReceiptAndAccountRetirementCannotReviveOldCallbacks(): Unit = runBlocking {
        withTimeout(5_000) {
            Fixture(this).use { f ->
                val old = DesktopLiveSourceSelection(f.binding())
                f.memory.installLivePlayback(f.initial.copy(), requireNotNull(f.player.currentSourceSnapshot()), f.ports)
                assertFalse(old.switch(0, 1))
                val fresh = DesktopLiveSourceSelection(f.binding())
                f.beforeAdmission = { f.accountCurrent = false }
                assertFalse(fresh.switch(0, 1)); assertTrue(f.reloadRequests.isEmpty())
            }
        }
    }

    @Test fun manualSwitchCancelsPendingReloadAndItsLateResultCannotReplaceSuccessor(): Unit = runBlocking {
        withTimeout(5_000) {
            Fixture(this, stream(backups = false, second = false)).use { f ->
                val waiting = CompletableDeferred<Unit>(); val result = CompletableDeferred<LivePlaybackInfo>()
                f.reload = { _, _, _, _ -> withContext(NonCancellable) { waiting.complete(Unit); result.await() } }
                f.fail(1); waiting.await()
                val pending = requireNotNull(f.memory.playJob)
                assertTrue(DesktopLiveSourceSelection(f.binding()).switch(0, 0))
                val successor = f.memory.stream
                result.complete(stream("late.example", backups = false, second = false)); pending.join(); settle()
                assertTrue(pending.isCancelled); assertSame(successor, f.memory.stream)
                assertEquals("https://cdn.example/live.m3u8?first", f.player.currentSourceSnapshot()?.source?.videoUrl)
                assertFalse(f.memory.opening)
            }
        }
    }

    @Test fun stopAndRoomReplacementCancelPendingObserverWithoutStoppingForeignSource(): Unit = runBlocking {
        withTimeout(5_000) {
            Fixture(this, stream(backups = false, second = false)).use { f ->
                val waiting = CompletableDeferred<Unit>(); val result = CompletableDeferred<LivePlaybackInfo>()
                f.reload = { _, _, _, _ -> withContext(NonCancellable) { waiting.complete(Unit); result.await() } }
                f.fail(1); waiting.await()
                val observer = requireNotNull(f.memory.recoveryObserver); val pending = requireNotNull(f.memory.playJob)
                f.player.loadVersioned(NativePlaybackSource("https://foreign.example/video"))
                f.memory.stopPlayback(); f.memory.room = room.copy(roomId = 456)
                result.complete(stream("late.example")); pending.join(); observer.join()
                assertTrue(pending.isCancelled); assertTrue(observer.isCancelled)
                assertNull(f.memory.liveSourceSnapshot); assertNull(f.memory.recoveryPorts)
                assertEquals("https://foreign.example/video", f.player.currentSourceSnapshot()?.source?.videoUrl)
            }
        }
    }

    @Test fun accountRetiredDuringAwaitRejectsLateReloadAndCanInstallFreshOwner(): Unit = runBlocking {
        withTimeout(5_000) {
            Fixture(this, stream(backups = false, second = false)).use { f ->
                val waiting = CompletableDeferred<Unit>(); val result = CompletableDeferred<LivePlaybackInfo>()
                f.reload = { _, _, _, _ -> waiting.complete(Unit); result.await() }
                f.fail(1); waiting.await(); f.accountCurrent = false
                result.complete(stream("late.example")); requireNotNull(f.memory.playJob).join(); settle()
                assertSame(f.initial, f.memory.stream)
                assertEquals("https://cdn.example/live.m3u8?first", f.player.currentSourceSnapshot()?.source?.videoUrl)
                f.accountCurrent = true
                val fresh = stream("new-account.example")
                f.player.loadVersioned(fresh.source.toNativePlayback())
                f.memory.installLivePlayback(fresh, requireNotNull(f.player.currentSourceSnapshot()), f.ports)
                assertNotNull(f.memory.captureLiveSourceBinding())
            }
        }
    }

    @Test fun safeCandidateFilteringKeepsEachOwnMetadataAndDoesNotPromoteUnsafeUrls(): Unit {
        val filtered = stream(host = "invalid host", backups = false)
        assertEquals("https://alternate.example/alternate.m3u8?alt", filtered.source.videoUrl)
        assertEquals(400, filtered.source.quality)
        assertEquals(listOf(400), filtered.qualities.map { it.id })
        assertEquals(1, filtered.resolvedPlayback?.candidates?.size)
        assertEquals(0, filtered.candidateIndex)
        assertEquals(listOf(400), filtered.resolvedPlayback?.candidates?.single()?.qualityList?.map { it.qn })
    }

    @Test fun rejectedClosedSelectorCannotCancelAlreadyPendingCurrentReload(): Unit = runBlocking {
        withTimeout(5_000) {
            Fixture(this, stream(backups = false, second = false)).use { f ->
                val waiting = CompletableDeferred<Unit>(); val result = CompletableDeferred<LivePlaybackInfo>()
                f.reload = { _, _, _, _ -> waiting.complete(Unit); result.await() }
                f.fail(1); waiting.await()
                val pending = requireNotNull(f.memory.playJob)
                val selection = DesktopLiveSourceSelection(f.binding())
                f.beforeAdmission = { selection.close() }
                assertFalse(selection.switch(0, 0))
                assertTrue(pending.isActive)
                result.complete(stream("current-reload.example", backups = false, second = false)); pending.join()
                assertEquals("https://current-reload.example/live.m3u8?first", f.memory.stream?.source?.videoUrl)
            }
        }
    }

    @Test fun queuedReloadRetiresExactFailureCallerBeforeAnotherRequestOrCommit(): Unit = runBlocking {
        withTimeout(5_000) {
            Fixture(this, stream(backups = false, second = false)).use { f ->
                val waiting = CompletableDeferred<Unit>(); val continueRequest = CompletableDeferred<Unit>()
                var secondRequests = 0
                f.reload = { _, _, _, current ->
                    waiting.complete(Unit); continueRequest.await()
                    if (!current()) throw CancellationException("Actual failure attempt retired")
                    secondRequests++; stream("late.example", backups = false, second = false)
                }
                f.fail(1); waiting.await()
                val pending = requireNotNull(f.memory.playJob)
                f.fail(2, PlayerFailureKind.UNKNOWN, null) // Same full source, different actual terminal attempt.
                continueRequest.complete(Unit); pending.join(); settle()
                assertEquals(0, secondRequests)
                assertSame(f.initial, f.memory.stream)
                assertEquals("https://cdn.example/live.m3u8?first", f.player.currentSourceSnapshot()?.source?.videoUrl)
            }
        }
    }

    @Test fun actualCurrentVideoOutputReplenishesBudgetButQueuedOrRetainedClockDoesNot(): Unit = runBlocking {
        withTimeout(5_000) {
            Fixture(this, stream(backups = false, second = false)).use { f ->
                f.reload = { _, _, _, _ -> stream("fresh.example", backups = false, second = false) }
                f.fail(1); settle()
                assertEquals(0, f.memory.remainingLiveReloadAttempts)
                state(f.player).value = f.player.state.value.copy(ready = true, loading = false,
                    nativePaused = false, positionSeconds = 20.0)
                settle()
                assertEquals(0, f.memory.remainingLiveReloadAttempts) // retained clock without output
                state(f.player).value = f.player.state.value.copy(firstVideoFrameReady = true)
                settle()
                assertEquals(1, f.memory.remainingLiveReloadAttempts)
                f.fail(2); settle()
                assertEquals(2, f.reloadRequests.size)
                assertEquals(0, f.memory.remainingLiveReloadAttempts)
                f.fail(3); settle()
                assertEquals(2, f.reloadRequests.size) // failure before fresh output still exhausts
            }
        }
    }

    @Test fun actualCurrentAudioOutputRequiresNativeUnpausedClockAndNeverBorrowedSource(): Unit = runBlocking {
        withTimeout(5_000) {
            Fixture(this, stream(backups = false, second = false)).use { f ->
                f.memory.onlyAudio = true
                f.fail(1); settle()
                assertEquals(0, f.memory.remainingLiveReloadAttempts)
                state(f.player).value = f.player.state.value.copy(ready = true, loading = false,
                    audioCodec = "aac", positionSeconds = 1.0, nativePaused = null)
                settle()
                assertEquals(0, f.memory.remainingLiveReloadAttempts)
                state(f.player).value = f.player.state.value.copy(nativePaused = false)
                settle()
                assertEquals(1, f.memory.remainingLiveReloadAttempts)
                f.fail(2); settle()
                assertEquals(0, f.memory.remainingLiveReloadAttempts)
                f.player.loadVersioned(stream("foreign.example").source.toNativePlayback())
                state(f.player).value = f.player.state.value.copy(ready = true, loading = false,
                    audioCodec = "aac", positionSeconds = 2.0, nativePaused = false)
                settle()
                assertEquals(0, f.memory.remainingLiveReloadAttempts)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun state(player: MpvPlayer) = MpvPlayer::class.java.getDeclaredField("mutableState")
        .apply { isAccessible = true }.get(player) as MutableStateFlow<PlayerState>
}
