package com.bilipai.desktop.ui

import com.android.purebilibili.core.plugin.PluginManager
import com.android.purebilibili.core.store.DesktopOriginalVideoControlSettings
import com.android.purebilibili.core.store.DesktopOriginalPlayerSectionSettings
import com.android.purebilibili.core.store.PlaybackCompletionBehavior
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.playback.session.PlaybackSessionState
import com.android.purebilibili.feature.video.player.*
import com.android.purebilibili.feature.video.viewmodel.DesktopVideoCommentRequests
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.*
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.player.cache.*
import com.bilipai.desktop.plugins.*
import com.sun.jna.Memory
import com.sun.jna.Pointer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.swing.Swing
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.awt.EventQueue
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.Proxy as NetworkProxy
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Actual complete original VM, Slot/Gate/Assembly/RequestFactory/RootMediaFactory,
 * NativeOwner, RuntimeBinding and PluginBridge. Only HTTP responses, external queue
 * and platform effects are fixture inputs. The existing memory ABI executes MPV
 * commands/ACKs without a native DLL, rendering thread, HWND or physical decoder.
 * One real Runtime is retained for this class, matching PluginManager's process
 * lifetime. All optional providers are disabled in its temporary Store before
 * registration. No fake Runtime readiness or manufactured accepted source exists. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DesktopOriginalVideoCdnSelectionAssemblyTest {
    private lateinit var runtimeDirectory: Path
    private lateinit var store: DesktopPluginStore
    private lateinit var runtime: DesktopPluginRuntime

    @BeforeAll fun createRuntime(): Unit = runBlocking {
        runtimeDirectory = Files.createTempDirectory("cdn-actual-runtime-")
        store = DesktopPluginStore(runtimeDirectory.resolve("settings"))
        val ids = listOf("sponsor_block", "danmaku_enhance", "eye_protection", "bilipai_feed_filter",
            "home_feed_anonymizer", "adfilter", "subscription_feed", "today_watch", "cdn_region", "dlna_cast", "google_cast")
        store.update("plugin_prefs", ids.associate { "plugin_enabled_$it" to JsonPrimitive(false) })
        runtime = withContext(Dispatchers.Swing) { DesktopPluginRuntime(store) }
        withTimeout(8_000) { for (id in ids) PluginManager.awaitPluginReady(id) }
        assertTrue(runtime.plugins.value.none { it.enabled })
    }

    @AfterAll fun closeRuntime(): Unit = runBlocking {
        if (::runtime.isInitialized) runtime.shutdownForRestore()
        if (::runtimeDirectory.isInitialized) deleteCreatedDirectory(runtimeDirectory, "cdn-actual-runtime-")
    }

    @Test fun sameRequestMediaPreparationUsesCommittedMetadataBeforeUiProjectionCatchesUp(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            val committed = CompletableDeferred<Unit>()
            f.beforeProjectedSuccess = {
                f.pauseUiDispatch()
                committed.complete(Unit)
            }
            f.ui { f.owner.playback.loadVideo(A, 101, cid = 104, autoPlay = false) }
            try {
                withTimeout(8_000) { committed.await() }
                // Use a separate EDT continuation, after the Store/entry commit
                // returned, while only this fixture's projection is held back.
                withContext(Dispatchers.Swing) {
                    f.assertOutsideAdmission()
                    val state = assertIs<VideoPlaybackUiState.Success>(f.owner.playback.captureDesktopPlaybackState())
                    assertIs<VideoPlaybackUiState.Loading>(f.owner.playback.uiState.value)
                    f.owner.environment.invocations.withInvocation {
                        val media = f.owner.environment.useCase.media
                        var source: PlaybackSource? = null
                        media.withPlaybackIntent(0L, false) {
                            source = media.prepareLegacyDash(backupVideo(A), null, emptyMap())
                        }
                        assertNotNull(source)
                    }.also { assertEquals(state.info.title, it.title) }
                }
            } finally { f.ui { f.resumeUiDispatch() } }
            f.awaitLoaded(A)
        }
    }

    @Test fun delayedOriginalCdnJobCannotRetargetClickedAUrlsToLaterActualB(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            f.load(A, 101, 104)
            val clicked = f.accepted(); val original = f.owner.captureLoadState()
            val held = f.holdCdnSwitch()
            assertTrue(held.job.isActive)
            assertFalse(f.captures.any { it.job === held.job })
            f.load(B, 102, 105)
            val later = f.accepted(); val state = f.state()
            assertNotSame(original.currentRequest, f.owner.captureLoadState().currentRequest)
            assertNotSame(clicked, later)
            f.release(held)
            withTimeout(8_000) { held.job.join() }
            assertTrue(held.job.isCancelled)
            val actualCapture = f.captures.single { it.job === held.job }
            assertSame(f.owner.captureLoadState().currentRequest, actualCapture.state.currentRequest)
            assertEquals(B, actualCapture.state.currentBvid)
            assertSame(later, f.accepted())
            assertEquals(state.playUrl, f.state().playUrl)
            assertEquals(B, f.state().info.bvid)
            assertFalse(f.preparations.any { it.source.videoUrl == backupVideo(A) })
            f.ui { f.actor.drain() }
            assertFalse(f.actor.native.loads.any { it.getOrNull(1) == backupVideo(A) })
            assertFalse(f.toasts.any { it.contains("已切换到线路2") })
        }
    }

    @Test fun newOriginalRequestDuringRealPreparationRejectsOldFinalAccept(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            f.load(A, 101, 104)
            val before = f.accepted()
            var observed = false
            f.beforePreparation = { preparation ->
                if (preparation.source.videoUrl == backupVideo(A)) {
                    observed = true
                    f.assertOutsideAdmission()
                    assertSame(before, f.accepted())
                    f.owner.playback.loadVideo(B, 102, cid = 105, autoPlay = false)
                    assertEquals(B, f.owner.captureLoadState().currentRequest?.bvid)
                }
            }
            val held = f.holdCdnSwitch(); f.release(held)
            withTimeout(8_000) { held.job.join() }
            assertTrue(observed); assertTrue(held.job.isCancelled)
            f.awaitLoaded(B)
            assertEquals(B, f.accepted().request.bvid)
            assertFalse(f.publications.any { it.nativeSource.source.videoUrl == backupVideo(A) })
            f.ui { f.actor.drain(); f.actor.loaded() }
            assertFalse(f.actor.native.loads.any { it.getOrNull(1) == backupVideo(A) })
            assertFalse(f.toasts.any { it.contains("已切换到线路2") })
        }
    }

    @Test fun ownSuccessorKeepsLatestRealMetadataBeforeItsAsyncRuntimeGenerationIsReady(): Unit = runBlocking {
        Fixture(holdTags = true).useSuspending { f ->
            f.load(A, 101, 104)
            val before = f.accepted(); val request = f.owner.captureLoadState()
            assertTrue(f.state().videoTags.isEmpty())
            withTimeout(8_000) { f.tagsEntered.await() }
            val held = f.holdCdnSwitch()
            f.tagsRelease.countDown()
            withTimeout(8_000) { while (f.state().videoTags.singleOrNull()?.tag_name != "Fresh metadata") delay(5) }
            val latest = f.state()
            assertSame(before, f.accepted())
            assertSame(request.currentRequest, f.owner.captureLoadState().currentRequest)
            f.release(held)
            withTimeout(8_000) { held.job.join() }
            assertFalse(held.job.isCancelled)
            val child = f.accepted()
            assertNotSame(before, child)
            assertSame(held.job, f.publicationRequestJob(child), "The held DEFAULT launch must be this actual native child's request Job")
            assertEquals(backupVideo(A), child.nativeSource.source.videoUrl)
            assertEquals(backupAudio(A), child.nativeSource.source.audioUrl)
            assertEquals(1, (f.originalState().value as VideoPlaybackUiState.Success).currentCdnIndex)
            // The original public uiState combines labels on viewModelScope;
            // joining the CDN Job does not await that separate projection.
            withTimeout(8_000) {
                while (f.state().currentCdnIndex != 1 || f.state().playUrl != backupVideo(A) ||
                    f.state().audioUrl != backupAudio(A)) delay(5)
            }
            assertEquals(1, f.state().currentCdnIndex)
            assertEquals(latest.videoTags, f.state().videoTags)
            assertEquals(latest.related, f.state().related)
            assertEquals(latest.relatedLoadState, f.state().relatedLoadState)
            assertSame(request.currentRequest, f.owner.captureLoadState().currentRequest)
            assertEquals(request.currentLoadRequestToken, f.owner.captureLoadState().currentLoadRequestToken)
            val emission = f.cdnEmissions.first { it.source === child }
            assertNull(emission.runtimeGeneration) // actual original _uiState commit precedes Main worker readiness
            assertEquals(1, emission.index)
            withTimeout(8_000) { while (f.runtimeBinding.generation(child) == null) delay(5) }
            assertTrue(f.owner.environment.plugins.isPlaybackDispatchCurrent(child))
            f.ui { f.actor.drain(); f.actor.loaded() }
            assertTrue(f.player.ownsSourceSnapshot(child.nativeSource))
            assertEquals(27.75, child.nativeSource.source.startPositionSeconds)
            assertTrue(child.nativeSource.source.startPaused)
            @Suppress("UNCHECKED_CAST") val options = f.actor.native.loads.last()[4] as Map<String, String>
            assertEquals("27.75", options["start"]); assertEquals("yes", options["pause"])
        }
    }

    @Test fun actualNetworkRetryRefreshesUrlsWhileHealthySameRequestKeepsItsSource(): Unit = runBlocking {
        Fixture(refreshExpiredUrls = true).useSuspending { f ->
            f.load(A, 101, 104, audioLang = "en")
            // The original VM's healthy gate reads this owned Section projection and error.
            // FILE_LOADED/refresh provide actual ABI codec readbacks; core-startup ready is not run here.
            assertEquals(DesktopOriginalPlaybackStates.STATE_READY, f.owner.section.playbackState)
            assertFalse(f.player.state.value.loading)
            assertEquals("h264", f.player.state.value.videoCodec)
            assertEquals("flac", f.player.state.value.audioCodec)
            val original = f.accepted(); val load = f.owner.captureLoadState(); val state = f.state()
            assertEquals("en", state.currentAudioLang)
            assertEquals(1, state.cdnCount, "This case must exercise network retry, not the prior CDN-switch policy")
            assertEquals(1, f.playRequests.size)
            assertEquals(retryVideo(1), original.nativeSource.source.videoUrl)
            assertEquals(retryAudio(1), original.nativeSource.source.audioUrl)
            f.ui {
                assertNull(f.owner.section.playerError)
                f.owner.playback.loadVideo(A, 101, cid = 104, audioLang = "en", autoPlay = false)
            }
            // The healthy same-subject path returns synchronously before starting a new request.
            assertEquals(1, f.playRequests.size)
            assertSame(original, f.accepted())
            assertSame(load.currentRequest, f.owner.captureLoadState().currentRequest)
            assertEquals(load.currentLoadRequestToken, f.owner.captureLoadState().currentLoadRequestToken)

            val failed = f.failExpiredNetwork()
            assertEquals(original.sourceVersion, failed.sourceVersion)
            withTimeout(8_000) {
                while (f.playRequests.size < 2 || f.owner.native.current() === original ||
                    (f.owner.playback.uiState.value as? VideoPlaybackUiState.Success)?.playUrl != retryVideo(2)) delay(5)
            }
            val replacement = f.accepted()
            val retryJob = f.publicationRequestJob(replacement)
            withTimeout(8_000) { retryJob.join() }
            assertFalse(retryJob.isCancelled)
            assertEquals(2, f.playRequests.size, "Exactly one fresh playurl request follows the terminal network failure")
            for (request in f.playRequests) {
                assertEquals("GET", request.method)
                assertEquals(A, request.url.queryParameter("bvid"))
                assertEquals("104", request.url.queryParameter("cid"))
                assertEquals("en", request.url.queryParameter("cur_language"))
            }
            val retried = f.owner.captureLoadState()
            assertNotSame(load.currentRequest, retried.currentRequest)
            assertTrue(retried.currentLoadRequestToken > load.currentLoadRequestToken)
            assertTrue(assertNotNull(retried.currentRequest).force)
            assertEquals("en", retried.currentRequest?.audioLang)
            assertNotSame(original, replacement)
            assertTrue(replacement.sourceVersion > original.sourceVersion)
            assertEquals(retryVideo(2), replacement.nativeSource.source.videoUrl)
            assertEquals(retryAudio(2), replacement.nativeSource.source.audioUrl)
            assertEquals("en", f.state().currentAudioLang)
            assertEquals(state.selectedAudioQuality, f.state().selectedAudioQuality)
            assertEquals(state.requestedAudioQuality, f.state().requestedAudioQuality)
            assertEquals(27.75, replacement.nativeSource.source.startPositionSeconds)
            assertTrue(replacement.nativeSource.source.startPaused)
            withTimeout(8_000) { while (f.runtimeBinding.generation(replacement) == null) delay(5) }
            f.ui {
                f.actor.drain(); f.actor.loaded()
                assertTrue(f.player.ownsSourceSnapshot(replacement.nativeSource))
                assertEquals(replacement.nativeSource.source.nativeLoadUrl, f.actor.native.loads.last()[1])
                @Suppress("UNCHECKED_CAST") val options = f.actor.native.loads.last()[4] as Map<String, String>
                assertEquals("27.75", options["start"])
                assertEquals("yes", options["pause"])
                assertEquals(27_750L, f.owner.section.currentPosition)
                assertFalse(f.owner.section.playWhenReady)
            }
        }
    }

    @Test fun ordinaryFullLoadPresentationSurvivesCompletionButRejectsCancelledOriginalCaller(): Unit = runBlocking<Unit> {
        Fixture().useSuspending { f ->
            f.load(A, 101, 104)
            val accepted = f.accepted()
            val subject = assertNotNull(accepted.fullLoadSubject)
            val receipt = assertNotNull(accepted.nativeSource.source.authorizationReceipt)
            val original = f.owner.captureLoadState()
            val caller = f.publicationRequestJob(accepted)
            assertTrue(f.captures.any { it.job === caller })
            withTimeout(8_000) { caller.join() }
            f.ui {
                assertTrue(caller.isCompleted); assertFalse(caller.isCancelled)
                assertTrue(f.gate.owns()); assertTrue(f.owner.native.isCurrent(accepted))
                assertSame(accepted, f.accepted())
                assertSame(original.currentRequest, f.owner.captureLoadState().currentRequest)
                assertEquals(original.currentLoadRequestToken, f.owner.captureLoadState().currentLoadRequestToken)
                assertTrue(subject.matchesPresentation(accepted, f.owner.captureLoadState(), receipt),
                    "Normal completion must preserve the real accepted full-load presentation")
            }
        }
        // A completed Job cannot later become cancelled. Use a second real load,
        // held by its actual request-scope related read, to exercise cancellation.
        Fixture(holdRelated = true).useSuspending { f ->
            f.ui { f.owner.playback.loadVideo(A, 101, cid = 104, autoPlay = false) }
            f.awaitLoaded(A)
            f.ui { f.actor.drain(); f.actor.loaded(); f.player.setPaused(true); f.actor.drain() }
            withTimeout(8_000) { f.relatedEntered.await() }
            val accepted = f.accepted()
            val subject = assertNotNull(accepted.fullLoadSubject)
            val receipt = assertNotNull(accepted.nativeSource.source.authorizationReceipt)
            val original = f.owner.captureLoadState()
            val caller = f.publicationRequestJob(accepted)
            assertNull(accepted.bootstrapOrigin); assertNull(accepted.pageSubject)
            assertTrue(f.captures.any { it.job === caller })
            assertTrue(caller.isActive); assertFalse(caller.isCompleted)
            assertTrue(subject.matchesSubmission(f.owner.native, accepted.request, receipt, caller),
                "The held caller must be the actual Root-captured original load Job")
            assertTrue(subject.matchesPresentation(accepted, original, receipt))
            caller.cancel(CancellationException("Cancel the actual accepted original load caller"))
            f.relatedRelease.countDown()
            withTimeout(8_000) { caller.join() }
            f.ui {
                assertTrue(caller.isCancelled)
                assertTrue(f.gate.owns()); assertTrue(f.owner.native.isCurrent(accepted))
                assertSame(accepted, f.accepted())
                assertSame(original.currentRequest, f.owner.captureLoadState().currentRequest)
                assertEquals(original.currentLoadRequestToken, f.owner.captureLoadState().currentLoadRequestToken)
                assertSame(receipt, accepted.nativeSource.source.authorizationReceipt)
                assertTrue(assertNotNull(accepted.nativeSource.source.nativePublication).admit {},
                    "Real native ACK must retain its entry/source lease independently of the cancelled caller")
                assertFalse(subject.matchesPresentation(accepted, f.owner.captureLoadState(), receipt),
                    "The same ref/token/source/receipt cannot borrow a cancelled original caller")
                assertFalse(subject.matchesSubmission(f.owner.native, accepted.request, receipt, caller))
            }
        }
    }

    @Test fun actualLastFiveSecondsOfferEnablesAndConsumesCapturedFacadeNext(): Unit = runBlocking<Unit> {
        Fixture(nextWatchMode = true).useSuspending { f ->
            f.load(A, 101, 104)
            f.setQueue(listOf(f.queueItem(A)))
            val before = f.accepted(); val original = f.owner.captureLoadState()
            assertEquals(listOf(B), f.state().related.map { it.bvid })
            f.clock(114.999)
            f.ui {
                assertNull(f.owner.nextWatch.capture())
                assertFalse(f.owner.environment.mini.onHasNextNavigationCallback?.invoke() == true)
                assertFalse(f.facade.hasNext)
            }
            f.clock(115.0)
            val five = f.awaitHint { it.remainingSeconds == 5 }
            assertSame(before, five.accepted)
            assertSame(original.currentRequest, five.originalRequest)
            assertEquals(original.currentLoadRequestToken, five.requestToken)
            assertEquals(A, five.target.sourceBvid); assertEquals(104L, five.target.sourceCid)
            assertEquals(B, five.target.bvid); assertEquals(0L, five.target.cid)
            assertNull(five.target.pageIndex); assertNull(five.target.playlistIndex)
            assertEquals("推荐观看", five.target.label)
            f.ui { assertTrue(f.facade.hasNext) }
            f.clock(119.001)
            f.awaitHint { it.remainingSeconds == 1 }
            val click = f.uiResult { assertNotNull(f.facade.captureManualNavigation()) }
            assertSame(before, click.accepted)
            assertEquals(B, assertNotNull(click.nextWatchHint).target.bvid)
            assertTrue(f.dispatchForward(click))
            f.awaitLoaded(B)
            val next = f.accepted()
            assertNotSame(before, next)
            assertEquals(B, next.request.bvid); assertEquals(105L, next.request.cid)
            assertNotSame(original.currentRequest, f.owner.captureLoadState().currentRequest)
            assertTrue(f.owner.captureLoadState().currentLoadRequestToken > original.currentLoadRequestToken)
            assertEquals(2, f.playRequests.size)
            assertNull(next.bootstrapOrigin, "The fixture's real Unknown source must remain Unknown")
        }
    }

    @Test fun capturedNoOfferDoesNotBorrowOfferFromLaterNativeClock(): Unit = runBlocking<Unit> {
        Fixture(nextWatchMode = true).useSuspending { f ->
            f.load(A, 101, 104); f.setQueue(listOf(f.queueItem(A)))
            f.clock(114.999)
            val click = f.uiResult { assertNotNull(f.facade.captureManualNavigation()) }
            assertNull(click.nextWatchHint)
            val source = f.accepted(); val original = f.owner.captureLoadState()
            f.clock(117.0); f.awaitHint { it.target.bvid == B }
            assertFalse(f.dispatchForward(click), "A no-offer click retains its original callback path")
            assertSame(source, f.accepted())
            assertSame(original.currentRequest, f.owner.captureLoadState().currentRequest)
            assertEquals(original.currentLoadRequestToken, f.owner.captureLoadState().currentLoadRequestToken)
            assertEquals(A, f.state().info.bvid); assertEquals(1, f.playRequests.size)
        }
    }

    @Test fun staleCapturedOriginalTargetRejectsWithoutSelectingNewQueueCandidate(): Unit = runBlocking<Unit> {
        Fixture(nextWatchMode = true).useSuspending { f ->
            f.load(A, 101, 104)
            f.behavior(PlaybackCompletionBehavior.PLAY_IN_ORDER)
            f.setQueue(listOf(f.queueItem(A), f.queueItem(B, "First queue target")))
            f.clock(117.0)
            val hint = f.awaitHint { it.target.playlistIndex == 1 && it.target.title == "First queue target" }
            val click = f.uiResult { assertNotNull(f.facade.captureManualNavigation()) }
            assertEquals(hint.target, assertNotNull(click.nextWatchHint).target)
            val source = f.accepted(); val original = f.owner.captureLoadState()
            f.setQueue(listOf(f.queueItem(A), f.queueItem(B, "Changed queue target")))
            f.ui { assertNotEquals(hint.target, f.owner.playback.resolveNextWatchSuggestion()) }
            assertFalse(f.dispatchForward(click))
            assertSame(source, f.accepted()); assertSame(original.currentRequest, f.owner.captureLoadState().currentRequest)
            assertEquals(original.currentLoadRequestToken, f.owner.captureLoadState().currentLoadRequestToken)
            assertEquals(0, f.realQueue.currentIndex.value); assertEquals(1, f.playRequests.size)
            f.clock(117.1)
            val fresh = f.awaitHint { it.target.title == "Changed queue target" }
            assertNotEquals(hint.target, fresh.target)
        }
    }

    @Test fun actualSameRequestNativeReplacementRejectsCapturedNextWatchSource(): Unit = runBlocking<Unit> {
        Fixture(nextWatchMode = true).useSuspending { f ->
            f.load(A, 101, 104); f.setQueue(listOf(f.queueItem(A))); f.clock(117.0)
            f.awaitHint { it.target.bvid == B }
            val click = f.uiResult { assertNotNull(f.facade.captureManualNavigation()) }
            val source = f.accepted(); val original = f.owner.captureLoadState()
            val held = f.holdCdnSwitch(); f.release(held)
            withTimeout(8_000) { held.job.join() }
            assertFalse(held.job.isCancelled)
            val replacement = f.accepted()
            assertNotSame(source, replacement)
            assertSame(original.currentRequest, f.owner.captureLoadState().currentRequest)
            assertEquals(original.currentLoadRequestToken, f.owner.captureLoadState().currentLoadRequestToken)
            assertFalse(f.dispatchForward(click), "The retired accepted object cannot borrow its same-request successor")
            assertSame(replacement, f.accepted()); assertEquals(A, f.state().info.bvid)
            assertFalse(f.publications.any { it.request.bvid == B })
            f.ui { f.actor.drain(); f.actor.loaded() }
            f.clock(117.2)
            val fresh = f.awaitHint { it.accepted === replacement }
            assertNotSame(assertNotNull(click.nextWatchHint).accepted, fresh.accepted)
        }
    }

    @Test fun originalOrderSleepAndRepeatPoliciesExcludeOrSelectActualOwnedOffer(): Unit = runBlocking<Unit> {
        Fixture(nextWatchMode = true).useSuspending { f ->
            f.load(A, 101, 104); f.setQueue(listOf(f.queueItem(A)))
            f.behavior(PlaybackCompletionBehavior.PLAY_IN_ORDER); f.clock(117.0)
            f.ui {
                assertNull(f.owner.playback.resolveNextWatchSuggestion(), "Order must not replace an exhausted queue with a recommendation")
                assertNull(f.owner.nextWatch.capture())
            }
            f.setQueue(listOf(f.queueItem(A), f.queueItem(B, "Ordered next")))
            f.clock(117.1)
            val ordered = f.awaitHint { it.target.playlistIndex == 1 }
            assertEquals(B, ordered.target.bvid); assertEquals(105L, ordered.target.cid)
            assertEquals("Ordered next", ordered.target.title); assertEquals("接下来观看", ordered.target.label)
            f.ui { f.owner.playback.setSleepTimer(1) }
            withTimeout(8_000) { f.owner.nextWatch.state.first { it == null } }
            f.ui { assertNull(f.owner.playback.resolveNextWatchSuggestion()); assertNull(f.owner.nextWatch.capture()) }
            f.ui { f.owner.playback.setSleepTimer(null) }
            f.behavior(PlaybackCompletionBehavior.REPEAT_ONE); f.clock(117.2)
            f.ui { assertNull(f.owner.playback.resolveNextWatchSuggestion()); assertNull(f.owner.nextWatch.capture()) }
            f.behavior(PlaybackCompletionBehavior.CONTINUE_CURRENT_LOGIC)
            f.setQueue(listOf(f.queueItem(A))); f.clock(120.0)
            f.ui { assertNull(f.owner.nextWatch.capture(), "Zero remaining time is not the explicit last-five-second window") }
        }
    }

    @Test fun displayedHintMatchesLaterCountdownAndActualNextUsesThatImmutableOffer(): Unit = runBlocking<Unit> {
        Fixture(nextWatchMode = true).useSuspending { f ->
            f.load(A, 101, 104); f.setQueue(listOf(f.queueItem(A))); f.clock(115.0)
            val displayed = f.awaitHint { it.remainingSeconds == 5 }
            val source = f.accepted(); val original = f.owner.captureLoadState()
            f.clock(119.001)
            val currentTick = f.awaitHint { it.remainingSeconds == 1 }
            assertNotSame(displayed, currentTick)
            assertEquals(displayed.target, currentTick.target)
            assertSame(displayed.accepted, currentTick.accepted)
            assertSame(displayed.originalRequest, currentTick.originalRequest)
            assertEquals(displayed.requestToken, currentTick.requestToken)
            val click = f.uiResult { assertNotNull(f.facade.captureNextWatchNavigation(displayed)) }
            assertSame(displayed, click.nextWatchHint, "The actual displayed immutable offer must remain the click target")
            assertSame(source, click.accepted); assertSame(f.owner, click.assembly)
            assertTrue(f.dispatchForward(click))
            f.awaitLoaded(B)
            val next = f.accepted()
            assertNotSame(source, next); assertEquals(B, next.request.bvid); assertEquals(105L, next.request.cid)
            assertEquals(displayed.target.bvid, next.request.bvid)
            assertNotSame(original.currentRequest, f.owner.captureLoadState().currentRequest)
            assertTrue(f.owner.captureLoadState().currentLoadRequestToken > original.currentLoadRequestToken)
            assertEquals(listOf(A, B), f.playRequests.map { it.url.queryParameter("bvid") })
            assertNull(next.bootstrapOrigin, "The existing real Unknown-origin route is never backfilled")
        }
    }

    @Test fun changedOriginalTargetBeforeMatchingCaptureCannotReplaceDisplayedCard(): Unit = runBlocking<Unit> {
        Fixture(nextWatchMode = true).useSuspending { f ->
            f.load(A, 101, 104); f.behavior(PlaybackCompletionBehavior.PLAY_IN_ORDER)
            f.setQueue(listOf(f.queueItem(A), f.queueItem(B, "Displayed target"))); f.clock(117.0)
            val displayed = f.awaitHint { it.target.title == "Displayed target" }
            val source = f.accepted(); val original = f.owner.captureLoadState()
            f.setQueue(listOf(f.queueItem(A), f.queueItem(B, "Later unseen target"))); f.clock(117.1)
            val latest = f.awaitHint { it.target.title == "Later unseen target" }
            assertNotEquals(displayed.target, latest.target)
            assertSame(displayed.accepted, latest.accepted)
            assertSame(displayed.originalRequest, latest.originalRequest)
            assertEquals(displayed.requestToken, latest.requestToken)
            f.ui {
                assertNull(f.facade.captureNextWatchNavigation(displayed))
                val generic = assertNotNull(f.facade.captureManualNavigation())
                assertEquals(latest.target, assertNotNull(generic.nextWatchHint).target,
                    "Generic next retains its own current-offer semantics")
            }
            assertSame(source, f.accepted()); assertSame(original.currentRequest, f.owner.captureLoadState().currentRequest)
            assertEquals(original.currentLoadRequestToken, f.owner.captureLoadState().currentLoadRequestToken)
            assertEquals(0, f.realQueue.currentIndex.value); assertEquals(1, f.playRequests.size)
        }
    }

    @Test fun capturedDisplayedClickCannotRetargetAfterCaptureBeforeActualTaskLaunch(): Unit = runBlocking<Unit> {
        Fixture(nextWatchMode = true).useSuspending { f ->
            f.load(A, 101, 104); f.behavior(PlaybackCompletionBehavior.PLAY_IN_ORDER)
            f.setQueue(listOf(f.queueItem(A), f.queueItem(B, "Shown before launch"))); f.clock(117.0)
            val displayed = f.awaitHint { it.target.title == "Shown before launch" }
            // Exact UI ordering: synchronous capture precedes the real per-click
            // child task. The displayed target changes before that task launches.
            val click = f.uiResult { assertNotNull(f.facade.captureNextWatchNavigation(displayed)) }
            assertSame(displayed, click.nextWatchHint)
            val source = f.accepted(); val original = f.owner.captureLoadState()
            f.setQueue(listOf(f.queueItem(A), f.queueItem(B, "Unseen after capture"))); f.clock(117.1)
            f.awaitHint { it.target.title == "Unseen after capture" }
            assertFalse(f.dispatchForward(click))
            assertSame(source, f.accepted()); assertEquals(A, f.state().info.bvid)
            assertSame(original.currentRequest, f.owner.captureLoadState().currentRequest)
            assertEquals(original.currentLoadRequestToken, f.owner.captureLoadState().currentLoadRequestToken)
            assertEquals(0, f.realQueue.currentIndex.value); assertEquals(1, f.playRequests.size)
            assertFalse(f.publications.any { it.request.bvid == B })
        }
    }

    @Test fun displayedMatchingRejectsActualNativeChildAndNewOriginalRequestBeforeAcceptance(): Unit = runBlocking<Unit> {
        Fixture(nextWatchMode = true).useSuspending { f ->
            f.load(A, 101, 104); f.setQueue(listOf(f.queueItem(A))); f.clock(117.0)
            val displayed = f.awaitHint { it.target.bvid == B }
            val original = f.owner.captureLoadState(); val source = f.accepted()
            val held = f.holdCdnSwitch(); f.release(held)
            withTimeout(8_000) { held.job.join() }
            assertFalse(held.job.isCancelled)
            val child = f.accepted()
            assertNotSame(source, child)
            assertSame(original.currentRequest, f.owner.captureLoadState().currentRequest)
            assertEquals(original.currentLoadRequestToken, f.owner.captureLoadState().currentLoadRequestToken)
            f.ui { assertNull(f.facade.captureNextWatchNavigation(displayed)); f.actor.drain(); f.actor.loaded() }
            f.clock(117.1)
            val childDisplayed = f.awaitHint { it.accepted === child }
            val childState = f.owner.captureLoadState()
            var checkpointCalls = 0
            f.beforePreparation = { preparation ->
                if (preparation.source.videoUrl == video(A)) {
                    f.assertOutsideAdmission(); checkpointCalls++
                    assertSame(child, f.accepted(), "The previous native source is still real at this pre-accept boundary")
                    val newRequest = f.owner.captureLoadState()
                    assertNotSame(childState.currentRequest, newRequest.currentRequest)
                    assertTrue(newRequest.currentLoadRequestToken > childState.currentLoadRequestToken)
                    assertTrue(assertNotNull(newRequest.currentRequest).force)
                    assertNull(f.facade.captureNextWatchNavigation(childDisplayed),
                        "A pending real ref/token cannot borrow the displayed predecessor")
                }
            }
            f.ui { f.owner.playback.loadVideo(A, 101, cid = 104, force = true, autoPlay = false) }
            withTimeout(8_000) {
                while (f.owner.native.current().let { it == null || it === child }) delay(5)
            }
            assertEquals(1, checkpointCalls)
            val replacement = f.accepted()
            assertNotSame(child, replacement); assertEquals(A, replacement.request.bvid)
            assertTrue(f.owner.captureLoadState().currentLoadRequestToken > childState.currentLoadRequestToken)
            f.ui { assertNull(f.facade.captureNextWatchNavigation(childDisplayed)) }
            assertFalse(f.publications.any { it.request.bvid == B })
        }
    }

    /** Real native END_FILE -> registered original VM listener -> actual request,
     * UseCase and NativeOwner. Bootstrap origin intentionally stays Unknown;
     * these are not physical RootWindow/non-null-continuation tests. */
    @Test fun actualEofPageContinuationUsesFalseAndTrueStartupPreference(): Unit = runBlocking<Unit> {
        for (autoPlay in listOf(false, true)) {
            Fixture(nextWatchMode = true, continuationTarget = ContinuationTarget.PAGES).useSuspending { f ->
                f.load(A, 101, 104); f.setQueue(listOf(f.queueItem(A))); f.startupPreference(autoPlay)
                val first = f.accepted(); val before = f.owner.captureLoadState()
                assertFalse(assertNotNull(before.currentRequest).autoPlay ?: true)
                assertNull(first.bootstrapOrigin)
                f.startForNaturalEof(); val eof = f.emitNaturalEof()
                assertEquals(first.sourceVersion, eof.sourceVersion); assertTrue(eof.playWhenReady)
                f.awaitLoadedPart(A, 106); f.assertActualLoadIntent(paused = !autoPlay)
                val next = f.accepted(); val after = f.owner.captureLoadState()
                assertNotSame(first, next); assertEquals(106L, next.request.cid)
                assertNotNull(next.pageSubject)
                assertSame(before.currentRequest, after.currentRequest,
                    "Original in-place P switch retains its real request reference")
                assertEquals(before.currentLoadRequestToken, after.currentLoadRequestToken)
                assertFalse(assertNotNull(after.currentRequest).autoPlay ?: true,
                    "A new P startup intent must not silently rewrite the original full-load request")
                assertEquals(2, f.playRequests.size)
            }
        }
    }

    @Test fun actualEofCollectionContinuationUsesFalseAndTrueStartupPreference(): Unit = runBlocking<Unit> {
        for (autoPlay in listOf(false, true)) {
            Fixture(nextWatchMode = true, continuationTarget = ContinuationTarget.COLLECTION).useSuspending { f ->
                f.load(A, 101, 104); f.setQueue(listOf(f.queueItem(A))); f.startupPreference(autoPlay)
                val first = f.accepted(); val before = f.owner.captureLoadState()
                assertEquals(listOf(A, B), f.state().info.ugc_season?.sections?.flatMap { it.episodes }?.map { it.bvid })
                f.startForNaturalEof(); f.emitNaturalEof()
                f.awaitLoadedPart(B, 105); f.assertActualLoadIntent(paused = !autoPlay)
                val next = f.accepted(); val after = f.owner.captureLoadState()
                assertNotSame(first, next); assertNull(next.bootstrapOrigin)
                assertNotSame(before.currentRequest, after.currentRequest)
                assertTrue(after.currentLoadRequestToken > before.currentLoadRequestToken)
                assertEquals(autoPlay, assertNotNull(after.currentRequest).autoPlay)
                assertEquals(B, next.request.bvid); assertEquals(105L, next.request.cid)
                assertEquals(2, f.playRequests.size)
            }
        }
    }

    @Test fun actualEofOrderedQueueUsesFalseAndTrueStartupPreference(): Unit = runBlocking<Unit> {
        for (autoPlay in listOf(false, true)) {
            Fixture(nextWatchMode = true, continuationTarget = ContinuationTarget.QUEUE).useSuspending { f ->
                f.load(A, 101, 104); f.setQueue(listOf(f.queueItem(A), f.queueItem(B)))
                f.behavior(PlaybackCompletionBehavior.PLAY_IN_ORDER); f.startupPreference(autoPlay)
                val before = f.owner.captureLoadState()
                f.startForNaturalEof(); f.emitNaturalEof()
                f.awaitLoadedPart(B, 105); f.assertActualLoadIntent(paused = !autoPlay)
                val after = f.owner.captureLoadState()
                assertNotSame(before.currentRequest, after.currentRequest)
                assertTrue(after.currentLoadRequestToken > before.currentLoadRequestToken)
                assertEquals(autoPlay, assertNotNull(after.currentRequest).autoPlay)
                assertEquals(1, f.realQueue.currentIndex.value); assertEquals(2, f.playRequests.size)
            }
        }
    }

    @Test fun actualEofPlaylistLoopFreezesDisabledStartupForTheWrappedTarget(): Unit = runBlocking<Unit> {
        Fixture(nextWatchMode = true, continuationTarget = ContinuationTarget.QUEUE).useSuspending { f ->
            f.load(A, 101, 104); f.setQueue(listOf(f.queueItem(B), f.queueItem(A)))
            f.ui { assertNotNull(f.realQueue.playAt(1)) }
            f.behavior(PlaybackCompletionBehavior.LOOP_PLAYLIST); f.startupPreference(false)
            val before = f.owner.captureLoadState()
            f.startForNaturalEof(); f.emitNaturalEof()
            f.awaitLoadedPart(B, 105); f.assertActualLoadIntent(paused = true)
            val after = f.owner.captureLoadState()
            assertNotSame(before.currentRequest, after.currentRequest)
            assertTrue(after.currentLoadRequestToken > before.currentLoadRequestToken)
            assertEquals(false, assertNotNull(after.currentRequest).autoPlay)
            assertEquals(0, f.realQueue.currentIndex.value); assertEquals(2, f.playRequests.size)
        }
    }

    @Test fun actualManualGenericQueueNextStartsDespiteDisabledStartupPreference(): Unit = runBlocking<Unit> {
        Fixture(nextWatchMode = true, continuationTarget = ContinuationTarget.QUEUE).useSuspending { f ->
            f.load(A, 101, 104); f.setQueue(listOf(f.queueItem(A), f.queueItem(B))); f.startupPreference(false)
            f.clock(114.999)
            val click = f.uiResult { assertTrue(f.facade.hasNext); assertNotNull(f.facade.captureManualNavigation()) }
            assertNull(click.nextWatchHint, "Exercise the existing generic manual callback, not the card")
            assertTrue(f.dispatchForward(click))
            f.awaitLoadedPart(B, 105); f.assertActualLoadIntent(paused = false)
            assertEquals(true, assertNotNull(f.owner.captureLoadState().currentRequest).autoPlay)
            assertEquals(2, f.playRequests.size)
        }
    }

    @Test fun actualManualNextWatchAndPageClickOverrideTheRetainedFalseLoadIntent(): Unit = runBlocking<Unit> {
        for (target in listOf(ContinuationTarget.QUEUE, ContinuationTarget.PAGES)) {
            Fixture(nextWatchMode = true, continuationTarget = target).useSuspending { f ->
                f.load(A, 101, 104); f.setQueue(listOf(f.queueItem(A))); f.startupPreference(false)
                val before = f.owner.captureLoadState()
                assertEquals(false, assertNotNull(before.currentRequest).autoPlay)
                f.clock(117.0)
                val shown = f.awaitHint { it.target.bvid == if (target == ContinuationTarget.PAGES) A else B }
                val click = f.uiResult { assertNotNull(f.facade.captureNextWatchNavigation(shown)) }
                assertSame(shown, click.nextWatchHint)
                assertTrue(f.dispatchForward(click))
                f.awaitLoadedPart(if (target == ContinuationTarget.PAGES) A else B,
                    if (target == ContinuationTarget.PAGES) 106L else 105L)
                f.assertActualLoadIntent(paused = false)
                if (target == ContinuationTarget.PAGES) {
                    assertSame(before.currentRequest, f.owner.captureLoadState().currentRequest)
                    assertEquals(before.currentLoadRequestToken, f.owner.captureLoadState().currentLoadRequestToken)
                    assertEquals(false, assertNotNull(f.owner.captureLoadState().currentRequest).autoPlay)
                    assertNotNull(f.accepted().pageSubject)
                } else assertEquals(true, assertNotNull(f.owner.captureLoadState().currentRequest).autoPlay)
                assertEquals(2, f.playRequests.size)
            }
        }
    }

    @Test fun realEntryAndAccountRetirementRejectTheExistingEofBridge(): Unit = runBlocking<Unit> {
        for (retireAccount in listOf(false, true)) {
            Fixture(nextWatchMode = true, continuationTarget = ContinuationTarget.QUEUE).useSuspending { f ->
                f.load(A, 101, 104); f.setQueue(listOf(f.queueItem(A), f.queueItem(B)))
                f.behavior(PlaybackCompletionBehavior.PLAY_IN_ORDER); f.startupPreference(true)
                f.startForNaturalEof()
                val before = f.accepted(); val requests = f.playRequests.size; val publications = f.publications.size
                f.ui {
                    if (retireAccount) f.sessions.saveAccount(
                        mapOf("SESSDATA" to "synthetic-other-session", "bili_jct" to "synthetic-other-csrf"),
                        AccountSummary(42, "Other fixture", "", isVip = false))
                    else f.gate.retire()
                    assertFalse(f.gate.owns()); assertFalse(f.owner.native.isCurrent(before))
                    f.emitNaturalEofOnEdt()
                }
                f.drainEdtCallbacks()
                assertEquals(requests, f.playRequests.size); assertEquals(publications, f.publications.size)
                assertNull(f.owner.native.current())
                assertTrue(f.player.state.value.ended)
                assertEquals(before.sourceVersion, assertNotNull(f.player.state.value.nativeEof).sourceVersion)
            }
        }
    }

    @Test fun realNewRequestBeforeQueuedEofDispatchCannotAdvanceTheOldSource(): Unit = runBlocking<Unit> {
        Fixture(nextWatchMode = true, continuationTarget = ContinuationTarget.PAGES).useSuspending { f ->
            f.load(A, 101, 104); f.setQueue(listOf(f.queueItem(A))); f.startupPreference(true)
            f.startForNaturalEof()
            val before = f.accepted(); val oldRequest = f.owner.captureLoadState()
            f.ui {
                val oldEof = f.emitNaturalEofOnEdt(); assertEquals(before.sourceVersion, oldEof.sourceVersion)
                // Both synchronous inputs precede the registered listener's real
                // Main dispatch; no listener/VM state is replaced or hand-seeded.
                f.owner.playback.loadVideo(B, cid = 105, autoPlay = false)
            }
            f.awaitLoadedPart(B, 105); f.assertActualLoadIntent(paused = true); f.drainEdtCallbacks()
            val after = f.owner.captureLoadState()
            assertNotSame(oldRequest.currentRequest, after.currentRequest)
            assertTrue(after.currentLoadRequestToken > oldRequest.currentLoadRequestToken)
            assertFalse(f.owner.native.isCurrent(before))
            assertEquals(2, f.playRequests.size)
            assertFalse(f.publications.any { it.request.bvid == A && it.request.cid == 106L })
            assertEquals(105L, f.accepted().request.cid)
        }
    }

    private enum class ContinuationTarget { PAGES, COLLECTION, QUEUE }

    private data class Capture(val state: PlaybackSessionState, val job: Job)
    private data class CdnEmission(val source: DesktopOriginalVideoAcceptedPublication, val index: Int, val runtimeGeneration: Long?)
    private data class HeldDispatch(val context: CoroutineContext, val runnable: Runnable) {
        val job get() = checkNotNull(context[Job])
    }
    /** Delay the actual DEFAULT launch before Assembly captures its SessionState.
     * Every released continuation executes on the same real EDT. This never
     * edits VM state, NativeOwner, an authorization receipt or Runtime generation. */
    private class OneDispatchHold : CoroutineDispatcher() {
        private val lock = Any()
        private var armedThread: Thread? = null
        private var held: HeldDispatch? = null
        private var paused = false
        private val pending = mutableListOf<Runnable>()
        override fun isDispatchNeeded(context: CoroutineContext) = true
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val intercepted = synchronized(lock) {
                // The click synchronously starts DEFAULT on this EDT. An unrelated
                // HTTP completion can dispatch concurrently and must never steal
                // the test's delay or become the Job that the test later joins.
                if (armedThread === Thread.currentThread() && context[Job]?.isActive == true) {
                    check(held == null); armedThread = null; held = HeldDispatch(context, block); true
                } else false
            }
            if (!intercepted) EventQueue.invokeLater {
                val deferred = synchronized(lock) { if (paused) { pending.add(block); true } else false }
                if (!deferred) block.run()
            }
        }
        fun arm() = synchronized(lock) {
            check(EventQueue.isDispatchThread() && armedThread == null && held == null)
            armedThread = Thread.currentThread()
        }
        fun take(): HeldDispatch = synchronized(lock) { checkNotNull(held) }
        fun release(expected: HeldDispatch) {
            synchronized(lock) { check(held === expected); held = null }
            EventQueue.invokeLater(expected.runnable)
        }
        fun pause() = synchronized(lock) { check(EventQueue.isDispatchThread()); paused = true }
        fun resume() {
            val waiting = synchronized(lock) { paused = false; pending.toList().also { pending.clear() } }
            waiting.forEach { EventQueue.invokeLater(it) }
        }
        fun drainOnClose() {
            resume()
            val waiting = synchronized(lock) { armedThread = null; held.also { held = null } }
            waiting?.let { EventQueue.invokeLater(it.runnable) }
        }
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader,
        arrayOf(T::class.java)) { _, method, _ -> error("Unexpected ${T::class.java.simpleName}.${method.name}") } as T

    private inner class Fixture(private val holdTags: Boolean = false, private val refreshExpiredUrls: Boolean = false,
        private val holdRelated: Boolean = false, private val nextWatchMode: Boolean = false,
        private val continuationTarget: ContinuationTarget? = null) {
        private val startupCacheBefore = store.preferences("auto_play_cache")
        private val completionSettingBefore = store.preferences("settings")["playback_completion_behavior"]
        val directory = Files.createTempDirectory("cdn-original-owner-")
        val sessions = DesktopSessionStore.temporary()
        val repository = DesktopRepository(sessions)
        var beforeProjectedSuccess: (() -> Unit)? = null
        private val parentJob = SupervisorJob()
        private val failures = CopyOnWriteArrayList<Throwable>()
        private val dispatcher = OneDispatchHold()
        private val scope = CoroutineScope(parentJob + dispatcher + CoroutineExceptionHandler { _, error -> failures += error })
        private val slot = DesktopOriginalVideoRootOwnerSlot(repository, scope, parentJob::isActive)
        lateinit var gate: DesktopOriginalVideoRootGate
        lateinit var owner: DesktopOriginalVideoOwnerAssembly
        lateinit var facade: DesktopUnifiedPlaybackFacade
        private lateinit var settingsContext: DesktopOriginalPlayerSettingsContext
        lateinit var runtimeBinding: DesktopOriginalVideoRootRuntimeBinding
        lateinit var actor: PremiumRecoveryActor
        val player = MpvPlayer()
        val captures = CopyOnWriteArrayList<Capture>()
        val publications = CopyOnWriteArrayList<DesktopOriginalVideoAcceptedPublication>()
        val preparations = CopyOnWriteArrayList<DesktopOriginalMediaCachePreparation>()
        val cdnEmissions = CopyOnWriteArrayList<CdnEmission>()
        val toasts = CopyOnWriteArrayList<String>()
        private val unexpectedRequests = CopyOnWriteArrayList<String>()
        val playRequests = CopyOnWriteArrayList<Request>()
        @Volatile private var firstUrlsExpired = false
        val tagsEntered = CompletableDeferred<Unit>()
        val tagsRelease = CountDownLatch(if (holdTags) 1 else 0)
        val relatedEntered = CompletableDeferred<Unit>()
        val relatedRelease = CountDownLatch(if (holdRelated) 1 else 0)
        var beforePreparation: ((DesktopOriginalMediaCachePreparation) -> Unit)? = null
        private val context = runtime.context
        private val privacy = DesktopSearchPreferences(directory.resolve("privacy"))
        private lateinit var cache: DesktopMediaByteCache
        private val transport: OkHttpClient
        private val subtitles: DesktopSubtitleAssets
        private val epoch: Long
        private val playlist = MutableStateFlow<List<PlaylistItem>>(emptyList())
        private val index = MutableStateFlow(0)
        private val nextWatchQueue = if (nextWatchMode) DesktopOriginalVideoPlaylistBinding(
            DesktopPluginStore(directory.resolve("next-watch-queue")), scope, parentJob::isActive,
            { action -> if (parentJob.isActive) { action(); true } else false }, failures::add,
        ) else null
        val realQueue: DesktopOriginalVideoPlaylistBinding get() = checkNotNull(nextWatchQueue)
        private val queue: DesktopOriginalVideoOwnerPlaylist = nextWatchQueue ?: object : DesktopOriginalVideoOwnerPlaylist {
            override val playlist get() = this@Fixture.playlist
            override val currentIndex get() = index
            override val playMode = MutableStateFlow(PlayMode.SEQUENTIAL)
            override val isExternalPlaylist = MutableStateFlow(false)
            override val externalPlaylistSource = MutableStateFlow(ExternalPlaylistSource.NONE)
            override fun setPlaylist(items: List<PlaylistItem>, startIndex: Int) { playlist.value = items; index.value = startIndex }
            override fun playAt(index: Int) = playlist.value.getOrNull(index).also { if (it != null) this@Fixture.index.value = index }
            override fun playNext(): PlaylistItem? = error("No queue navigation")
            override fun playPrevious(): PlaylistItem? = error("No queue navigation")
        }
        private val mini = object : DesktopOriginalVideoOwnerMini {
            override var onNavigateNextCallback: (() -> Boolean)? = null
            override var onNavigatePreviousCallback: (() -> Boolean)? = null
            override var onHasNextNavigationCallback: (() -> Boolean)? = null
            override var onHasPreviousNavigationCallback: (() -> Boolean)? = null
            override val currentBvid: String? = null
            override val currentCid: Long? = null
            override val isActive = false
            override val player: DesktopOriginalMpvSectionControl? = null
            override fun syncCurrentVideoInfo(state: VideoPlaybackUiState.Success) { check(state.info.bvid in listOf(A, B)) }
            override fun updateCachedVideoTags(bvid: String, tags: List<VideoTag>) { check(bvid in listOf(A, B)) }
        }
        private val network = object : DesktopOriginalVideoOwnerNetwork {
            override fun isMobileData() = false
            override fun isWifi() = true
            override fun relatedNetworkObservation() = DesktopOriginalVideoRelatedNetworkObservation(
                DesktopHomeNetworkObservation(true, 3, 71L, false, "cpu-memory-transport"),
                downstreamBandwidthKbps = null,
            )
            override fun cdnNetwork() = DesktopCdnNetworkObservation(true, "cpu-memory-transport")
            override fun getDefaultQualityId(context: DesktopOriginalPlayerSettingsContext) = 64
        }
        private val capabilities = object : DesktopOriginalVideoPlaybackCapabilities {
            override fun isHevcSupported() = false
            override fun isAv1Supported() = false
            override fun isHdrSupported() = false
            override fun isDolbyVisionSupported() = false
            override fun isDolbyAtmosAudioSupported() = false
            override fun isDolbySoftwareAudioDecoderRequired() = false
        }
        private val progress = object : DesktopOriginalVideoProgressPort {
            override fun getCachedPosition(bvid: String, cid: Long) = 0L
            override fun savePosition(bvid: String, cid: Long, positionMs: Long) { check(bvid in listOf(A, B)); check(cid in listOf(104L, 105L) || (continuationTarget == ContinuationTarget.PAGES && cid == 106L)) }
        }
        init {
            // The original guest fetch deliberately omits audioLang. This case verifies
            // the logged-in language-preserving route with private synthetic credentials.
            if (refreshExpiredUrls || continuationTarget != null) sessions.saveAccount(
                mapOf("SESSDATA" to "synthetic-retry-session", "bili_jct" to "synthetic-retry-csrf"),
                AccountSummary(41, "Retry fixture", "", isVip = false))
            sessions.saveSpiCookies(mapOf("buvid3" to "synthetic-visitor", "buvid4" to "synthetic-visitor4"), sessions.generation)
            epoch = repository.sessionEpoch
            store.update("quality_settings", mapOf("auto_highest_quality" to JsonPrimitive(false), "video_codec" to JsonPrimitive("avc1"),
                "video_second_codec" to JsonPrimitive("avc1"), "wifi_quality" to JsonPrimitive(64)))
            store.update("settings", mapOf("video_codec_preference" to JsonPrimitive("avc1"), "video_second_codec_preference" to JsonPrimitive("avc1")))
            store.update("video_note_settings", mapOf("video_note_enabled" to JsonPrimitive(false)))
            transport = repository.httpClient.newBuilder().proxy(NetworkProxy.NO_PROXY).retryOnConnectionFailure(false)
                .addInterceptor { chain ->
                    val request = chain.request()
                    if (!request.url.isHttps || request.url.port != 443) rejectRequest(request)
                    if (request.url.host in setOf("media.invalid", "backup.invalid")) return@addInterceptor memoryMedia(request)
                    if (request.url.host == "bvc.bilivideo.com") return@addInterceptor memoryPbp(request)
                    if (request.url.host !in setOf("api.bilibili.com", "www.bilibili.com")) rejectRequest(request)
                    val bvid = request.url.queryParameter("bvid") ?: A
                    val body = when (request.url.encodedPath) {
                        "/" -> "memory bootstrap"
                        "/x/frontend/finger/spi" -> """{"code":0,"data":{"b_3":"synthetic-visitor","b_4":"synthetic-visitor4"}}"""
                        "/x/web-interface/nav" -> """{"code":0,"data":{"isLogin":${refreshExpiredUrls || continuationTarget != null},"vip":{"status":0},"wbi_img":{"img_url":"https://fixture.invalid/0123456789abcdef0123456789abcdef.png","sub_url":"https://fixture.invalid/fedcba9876543210fedcba9876543210.png"}}}"""
                        "/x/web-interface/view" -> continuationViewJson(bvid)
                        "/x/player/wbi/playurl", "/x/player/playurl" -> {
                            val revision = synchronized(playRequests) { playRequests.add(request); playRequests.size }
                            if (refreshExpiredUrls) {
                                check(bvid == A && revision in 1..2)
                                retryPlayJson(revision)
                            } else playJson(bvid)
                        }
                        "/x/web-interface/archive/related" -> {
                            relatedEntered.complete(Unit)
                            check(relatedRelease.await(12, TimeUnit.SECONDS))
                            if (nextWatchMode && bvid == A)
                                """{"code":0,"data":[{"aid":102,"bvid":"$B","cid":105,"title":"Real related B","pic":"https://media.invalid/cover.jpg","duration":60,"owner":{"mid":52,"name":"Related UP","face":""}}]}"""
                            else """{"code":0,"data":[]}"""
                        }
                        "/x/tag/archive/tags" -> {
                            if (holdTags && bvid == A) { tagsEntered.complete(Unit); check(tagsRelease.await(8, TimeUnit.SECONDS)) }
                            """{"code":0,"data":[{"tag_id":71,"tag_name":"Fresh metadata"}]}"""
                        }
                        "/x/player/wbi/v2", "/x/player/v2" -> """{"code":0,"data":{"subtitle":{"subtitles":[]}}}"""
                        "/x/emote/user/panel/web" -> """{"code":0,"data":{"packages":[]}}"""
                        else -> """{"code":-404,"message":"No optional fixture data"}"""
                    }
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("memory")
                        .body(body.toResponseBody("application/json".toMediaType())).build()
                }.build()
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repository, transport)
            subtitles = DesktopSubtitleAssets(transport)
        }

        suspend fun initialize() {
            privacy.setPrivacyMode(true)
            cache = DesktopMediaByteCache(directory.resolve("bytes"), scope, repository)
            slot.install(epoch, { actualGate ->
                gate = actualGate
                val settings = DesktopOriginalPlayerSettingsContext(context, gate::owns, gate::commitEntry,
                    largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { false })
                settingsContext = settings
                val readbacks = DesktopOriginalVideoEntryReadbacks(repository, epoch, gate.scope, gate::owns, gate::commitEntry)
                val operations = DesktopDynamicCardOperations(repository, epoch, gate::owns)
                val comments = Proxy.newProxyInstance(DesktopVideoCommentRequests::class.java.classLoader,
                    arrayOf(DesktopVideoCommentRequests::class.java)) { _, method, _ ->
                    when (method.name) { "isOwned" -> gate.owns(); "currentMid" -> null
                        else -> error("No comment effect ${method.name}") }
                } as DesktopVideoCommentRequests
                val root = DesktopOriginalCommentRootOwner(operations, comments, gate.scope, operations.emotes, gate::owns,
                    { error("No comment feedback") })
                val effects = DesktopOriginalVideoOwnerEffects(mini, queue, unused(), network,
                    object : DesktopOriginalVideoOwnerCache {
                        override fun get(bvid: String, cid: Long): PlayUrlData? = null
                        override fun invalidate(bvid: String, cid: Long) = Unit
                    }, object : DesktopOriginalVideoOwnerAnalytics {
                        override fun logVideoPlay(videoId: String, title: String, author: String) { check(videoId in listOf(A, B)) }
                        override fun logQualityChange(videoId: String, fromQuality: Int, toQuality: Int) = error("No quality change")
                    }, DesktopOriginalVideoOwnerCrash { _, _, message -> error("Unexpected original load error $message") },
                    unused(), MutableStateFlow(false))
                val media = DesktopOriginalVideoRootMediaFactory(repository, cache, gate, { owner }, { preparation ->
                    preparations.add(preparation); beforePreparation?.invoke(preparation)
                })
                val requests = DesktopOriginalVideoOwnerRequestFactory(repository, DesktopLoginRepository(repository), epoch,
                    gate.scope, gate::owns, gate::commitEntry, { PlayerPreferences() }, capabilities,
                    { true }, { false }, { false }, subtitles, privacy,
                    { _, _ -> error("Preseeded visitor authorization must not retire") }, media::request)
                runtimeBinding = DesktopOriginalVideoRootRuntimeBinding(repository, runtime, gate) { owner.native }
                DesktopOriginalVideoOwnerAssembly.create(context, settings, repository, epoch, gate.scope,
                    gate::owns, gate::commitEntry, player, DesktopRepositoryPlaybackPublication(repository), { false },
                    runtimeBinding.sourceVersions, { expected -> publications.add(expected); runtimeBinding.accepted(expected) },
                    media::admission, captureMetadataBinding = { error("No readonly metadata recapture in this fixture") }, captureInvocation = { state, native ->
                        captures.add(Capture(state, currentCoroutineContext().job)); requests.capture(state, native, mediaPreparation = {
                            // Fixed-capability headless fixture; no physical NativeReady claim.
                            currentCoroutineContext().ensureActive()
                            if (!gate.owns()) throw CancellationException("Fixture preparation entry retired")
                        })
                    }, status = readbacks, prepareAcceptedMedia = media::accepted,
                    createPlugins = { native, invocations -> DesktopOriginalVideoOwnerPluginBridge(runtime, native, invocations,
                        runtimeBinding::generation, invocations::captureCurrentRequestAdmission, runtimeBinding::calls) },
                    confirmFollow = { error("No follow") }, feedback = {}, readHasPrimarySession = readbacks::hasPrimarySession,
                    readHasPrimaryAccessToken = readbacks::hasPrimaryAccessToken, readPrimaryMid = readbacks::primaryMid,
                    readOwnedCooldown = readbacks::cooldownRemainingMs, progress = progress, capabilities = capabilities,
                    applyPreferredVolume = {}, dashSegmentRequestsEnabled = { false }, logSeek = { _, _, _, _ -> },
                    diagnosticLoggingEnabled = { false }, resumeEndedSource = { error("No replay") }, root = root,
                    interactionAnalytics = unused(), effects = effects,
                    todayWatchFeedback = DesktopTodayWatchFeedbackWriteBinding(context, gate::owns, gate::commitEntry))
            }, {}, { runtimeBinding.close() }, {})
            ui {
                player.loadVersioned(PlaybackSource("file:///synthetic-bootstrap.avi"))
                actor = PremiumRecoveryActor(player)
                owner = slot.requireAssembly()
                owner.playback.initWithContext(DesktopOriginalPlayerSettingsContext(context, gate::owns, gate::commitEntry,
                    largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { false }))
            }
            if (nextWatchMode) {
                behavior(PlaybackCompletionBehavior.CONTINUE_CURRENT_LOGIC)
                facade = DesktopUnifiedPlaybackFacade(scope, slot.assemblies, slot::currentAssembly,
                    slot::requireAssembly, realQueue, parentJob::isActive, MutableStateFlow(false),
                    { error("No assembly retirement from this explicit NextWatch fixture") },
                    { _, _ -> error("No cast") }, { _, _ -> error("No subtitle mode") },
                    { _, _ -> error("No subtitle preference") }, { error("No cancellation UI") },
                    { _, _, _ -> error("No queue-card resolution") }, { error("Unexpected facade error $it") })
            }
            // Observe the actual original backing StateFlow without seeding,
            // replacing or mutating it. Its synchronous commit is distinct from
            // the original public combine(...).stateIn projection on Main.
            gate.scope.launch(Dispatchers.Unconfined) {
                originalState().collect { state ->
                    if (state is VideoPlaybackUiState.Success) {
                        beforeProjectedSuccess?.let { probe ->
                            beforeProjectedSuccess = null
                            probe()
                        }
                    }
                    if (state is VideoPlaybackUiState.Success && state.currentCdnIndex == 1) {
                        owner.native.current()?.let { child -> cdnEmissions.add(CdnEmission(child, state.currentCdnIndex, runtimeBinding.generation(child))) }
                    }
                }
            }
            gate.scope.launch { owner.playback.toastEvent.collect { toasts.add(it.toString()) } }
        }
        fun state() = owner.playback.uiState.value as VideoPlaybackUiState.Success
        @Suppress("UNCHECKED_CAST")
        fun originalState(): StateFlow<VideoPlaybackUiState> = owner.playback.javaClass
            .getDeclaredField("_uiState").apply { isAccessible = true }.get(owner.playback) as StateFlow<VideoPlaybackUiState>
        fun accepted() = checkNotNull(owner.native.current())
        fun publicationRequestJob(expected: DesktopOriginalVideoAcceptedPublication): Job {
            val initial = assertIs<DesktopOriginalVideoInitialPublication>(expected.nativeSource.source.nativePublication)
            // Read the exact immutable request Job of the actual production
            // publication. No Job/source/state is replaced by this observation.
            return DesktopOriginalVideoInitialPublication::class.java.getDeclaredField("requestJob")
                .apply { isAccessible = true }.get(initial) as Job
        }
        private fun rejectRequest(request: Request): Nothing {
            unexpectedRequests.add("${request.method} ${request.url.host}${request.url.encodedPath}")
            throw IOException("Unrecognized memory fixture request")
        }
        private fun memoryMedia(request: Request): Response {
            if (request.method !in setOf("HEAD", "GET") || request.url.encodedPath !in
                listOf(A, B).flatMap { listOf("/$it-video.m4s", "/$it-audio.m4s") }) rejectRequest(request)
            if (refreshExpiredUrls && firstUrlsExpired && request.url.queryParameter("retry_revision") == "1") {
                return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(403).message("expired fixture URL")
                    .body(ByteArray(0).toResponseBody("application/octet-stream".toMediaType())).build()
            }
            val content = ByteArray(4096) { (it % 251).toByte() }
            val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("memory media")
                .header("Accept-Ranges", "bytes").header("ETag", "\"synthetic-${request.url.encodedPath}\"")
            val type = "application/octet-stream".toMediaType()
            if (request.method == "HEAD") return builder.code(200).header("Content-Length", "${content.size}")
                .body(ByteArray(0).toResponseBody(type)).build()
            val range = request.header("Range")
            if (range == null) return builder.code(200).header("Content-Length", "${content.size}")
                .body(content.toResponseBody(type)).build()
            val match = Regex("bytes=([0-9]+)-([0-9]*)").matchEntire(range) ?: rejectRequest(request)
            val start = match.groupValues[1].toLongOrNull() ?: rejectRequest(request)
            val requestedEnd = match.groupValues[2].takeIf(String::isNotEmpty)?.toLongOrNull() ?: (content.size - 1L)
            if (start >= content.size || requestedEnd < start) return builder.code(416)
                .header("Content-Range", "bytes */${content.size}").body(ByteArray(0).toResponseBody(type)).build()
            val end = minOf(requestedEnd, content.size - 1L)
            val bytes = content.copyOfRange(start.toInt(), end.toInt() + 1)
            return builder.code(206).header("Content-Range", "bytes $start-$end/${content.size}")
                .header("Content-Length", "${bytes.size}").body(bytes.toResponseBody(type)).build()
        }
        private fun memoryPbp(request: Request): Response {
            val bvid = request.url.queryParameter("bvid")
            val subject = when (bvid) {
                A -> (if (continuationTarget == ContinuationTarget.PAGES) setOf("104", "106") else setOf("104")) to "101"
                B -> setOf("105") to "102"
                else -> rejectRequest(request)
            }
            if (request.method != "GET" || request.url.encodedPath != "/pbp/data" ||
                request.url.queryParameterNames != setOf("cid", "bvid", "aid") ||
                request.url.queryParameter("cid") !in subject.first || request.url.queryParameter("aid") != subject.second)
                rejectRequest(request)
            // Exact original API returns ResponseBody; its real metadata protocol
            // invokes parsePbpProgressData on top-level step_sec/events.default.
            val body = """{"code":0,"step_sec":5,"events":{"default":[0,1,3,1]}}"""
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("memory PBP")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
        fun queueItem(bvid: String, title: String = "Queue $bvid") = PlaylistItem(
            bvid, if (bvid == A) 104L else 105L, title, "https://media.invalid/cover.jpg", "Fixture UP", duration = 60L)
        suspend fun setQueue(items: List<PlaylistItem>) = ui { realQueue.setPlaylist(items, 0) }
        suspend fun behavior(value: PlaybackCompletionBehavior) {
            check(nextWatchMode)
            DesktopOriginalPlaybackPreferenceOperation.run(settingsContext, gate::owns) {
                DesktopOriginalVideoControlSettings.setPlaybackCompletionBehavior(settingsContext, value)
            }
        }
        /** Supply property-string readbacks to the existing memory ABI actor. This
         * does not replace MPV state, a Native publication or the original VM. */
        suspend fun clock(position: Double) = ui {
            check(nextWatchMode && position.isFinite())
            assertEquals(0, actor.native.mpv_set_property_string(Pointer(1L), "time-pos", position.toString()))
            assertEquals(0, actor.native.mpv_set_property_string(Pointer(1L), "duration", "120.0"))
            actor.drain(); actor.loaded()
            assertEquals(position, player.state.value.positionSeconds)
            assertEquals(120.0, player.state.value.durationSeconds)
            assertEquals(DesktopOriginalPlaybackStates.STATE_READY, owner.section.playbackState)
            assertTrue(player.ownsSourceSnapshot(accepted().nativeSource))
            assertFalse(player.state.value.ready, "Memory ABI FILE_LOADED never claims real MPV core initialization")
        }
        suspend fun awaitHint(predicate: (DesktopOriginalVideoNextWatchHint) -> Boolean): DesktopOriginalVideoNextWatchHint =
            withTimeout(8_000) { assertNotNull(owner.nextWatch.state.first { it != null && predicate(it) }) }
        suspend fun dispatchForward(click: DesktopOriginalManualPlaybackClick): Boolean = scope.async {
            val actualCaller = currentCoroutineContext().job
            assertTrue(EventQueue.isDispatchThread()); assertTrue(actualCaller.isActive)
            facade.navigateManual(click, forward = true, caller = actualCaller, uiCurrent = gate::owns)
        }.await()
        /** Only real temporary settings persistence is changed. EOF reads the
         * same synchronous getter as production; no VM intent is hand-seeded. */
        suspend fun startupPreference(autoPlay: Boolean) {
            check(continuationTarget != null)
            DesktopOriginalPlaybackPreferenceOperation.run(settingsContext, gate::owns) {
                settingsContext.getSharedPreferences("auto_play_cache", DesktopOriginalPlayerSettingsContext.MODE_PRIVATE)
                    .edit().putBoolean("click_to_play_enabled", autoPlay).putBoolean("auto_play_enabled", true).apply()
                assertEquals(autoPlay, DesktopOriginalPlayerSectionSettings.getClickToPlaySync(settingsContext))
            }
        }
        suspend fun startForNaturalEof() {
            clock(119.5)
            ui { player.setPaused(false); actor.drain(); actor.loaded(); assertTrue(owner.section.isPlaying) }
            // Observe that the registered original VM has consumed the real
            // onIsPlayingChanged event. This read never changes its private flag.
            val started = owner.playback.javaClass.getDeclaredField("hasUserStartedPlayback").apply { isAccessible = true }
            withTimeout(8_000) { while (!uiResult { started.getBoolean(owner.playback) }) delay(5) }
        }
        fun emitNaturalEofOnEdt(): PlayerNativeEof {
            check(EventQueue.isDispatchThread())
            Memory(24).use { end ->
                end.clear(); end.setInt(0, 0); end.setInt(4, 0); end.setLong(8, 11)
                PremiumRecoveryActor::class.java.getDeclaredMethod("event", Integer.TYPE, Pointer::class.java)
                    .apply { isAccessible = true }.invoke(actor, 7, end)
            }
            assertTrue(player.state.value.ended); assertNull(player.state.value.failure)
            return assertNotNull(player.state.value.nativeEof)
        }
        suspend fun emitNaturalEof(): PlayerNativeEof = uiResult { emitNaturalEofOnEdt() }
        suspend fun drainEdtCallbacks() { ui {}; ui {} }
        suspend fun awaitLoadedPart(bvid: String, cid: Long) = withTimeout(8_000) {
            while ((owner.playback.uiState.value as? VideoPlaybackUiState.Success)?.info?.let { it.bvid == bvid && it.cid == cid } != true ||
                owner.native.current()?.request?.let { it.bvid == bvid && it.cid == cid } != true) delay(5)
        }
        suspend fun assertActualLoadIntent(paused: Boolean) = ui {
            val publication = accepted()
            assertTrue(player.ownsSourceSnapshot(publication.nativeSource))
            assertEquals(paused, publication.nativeSource.source.startPaused,
                "Actual lexical UseCase/MediaIntent must stamp the accepted source")
            assertEquals(paused, assertNotNull(player.currentSourceSnapshot()).source.startPaused)
            actor.drain(); actor.loaded()
            @Suppress("UNCHECKED_CAST")
            val options = actor.native.loads.last()[4] as Map<String, String>
            assertEquals(if (paused) "yes" else "no", options["pause"], "Real memory ABI Load options")
            assertEquals(paused, player.state.value.paused); assertEquals(paused, player.state.value.nativePaused)
            assertFalse(player.state.value.ready, "These events never claim actual MPV core/hardware initialization")
        }
        private fun continuationViewJson(bvid: String): String {
            val base = viewJson(bvid)
            if (bvid != A) return base
            return when (continuationTarget) {
                ContinuationTarget.PAGES -> base.replace(
                    "\"pages\":[{\"cid\":104,\"page\":1,\"part\":\"Fixture\",\"duration\":60}]",
                    "\"pages\":[{\"cid\":104,\"page\":1,\"part\":\"Fixture\",\"duration\":60},{\"cid\":106,\"page\":2,\"part\":\"Second real P\",\"duration\":60}]"
                ).also { check(it != base) }
                ContinuationTarget.COLLECTION -> base.dropLast(2) +
                    ""","ugc_season":{"id":81,"title":"Fixture collection","sections":[{"id":82,"episodes":[{"id":91,"aid":101,"bvid":"$A","cid":104,"title":"Current collection episode"},{"id":92,"aid":102,"bvid":"$B","cid":105,"title":"Next collection episode"}]}]}}}"""
                else -> base
            }
        }
        suspend fun load(bvid: String, aid: Long, cid: Long, audioLang: String? = null) {
            ui { owner.playback.loadVideo(bvid, aid, cid = cid, audioLang = audioLang, autoPlay = false) }
            awaitLoaded(bvid)
            ui { actor.drain(); actor.loaded(); player.setPaused(true); actor.drain() }
            withTimeout(8_000) { while (runtimeBinding.generation(accepted()) == null) delay(5) }
            withTimeout(8_000) {
                while (state().relatedLoadState != VideoPlaybackUiState.RelatedLoadState.READY) delay(5)
            }
        }
        suspend fun awaitLoaded(bvid: String) = withTimeout(8_000) {
            while ((owner.playback.uiState.value as? VideoPlaybackUiState.Success)?.info?.bvid != bvid ||
                owner.native.current()?.request?.bvid != bvid) delay(5)
        }
        /** Deliver actual log-message and end-file ABI input to the existing memory MPV actor.
         * The real registered section listener and original recovery controller initiate retry;
         * this helper never calls VM.retry, replaces a VM state or fabricates a source ticket. */
        suspend fun failExpiredNetwork(): PlayerFailure = uiResult {
            check(refreshExpiredUrls)
            firstUrlsExpired = true
            fun text(value: String): Memory = value.toByteArray(Charsets.UTF_8).let { bytes ->
                Memory(bytes.size + 1L).apply { write(0, bytes, 0, bytes.size); setByte(bytes.size.toLong(), 0) }
            }
            text("ffmpeg").use { prefix -> text("error").use { level -> text("connection failed").use { message ->
                Memory(32).use { data ->
                    data.clear(); data.setPointer(0, prefix); data.setPointer(8, level); data.setPointer(16, message)
                    data.setInt(24, 20)
                    PremiumRecoveryActor::class.java.getDeclaredMethod("event", Integer.TYPE, Pointer::class.java)
                        .apply { isAccessible = true }.invoke(actor, 2, data)
                }
            } } }
            actor.fail(-13).also { failure ->
                assertSame(failure, player.state.value.failure)
                assertEquals(PlayerFailureKind.NETWORK, failure.kind)
                assertEquals(-13, failure.nativeCode)
            }
        }
        suspend fun holdCdnSwitch(): HeldDispatch = uiResult {
            assertTrue(owner.environment.plugins.isPlaybackDispatchCurrent(accepted()))
            assertTrue(state().cdnCount > 1)
            dispatcher.arm(); owner.playback.switchCdnTo(1); dispatcher.take()
        }
        fun release(held: HeldDispatch) = dispatcher.release(held)
        fun pauseUiDispatch() = dispatcher.pause()
        fun resumeUiDispatch() = dispatcher.resume()
        fun assertOutsideAdmission() {
            fun lock(type: Class<*>, instance: Any) = type.getDeclaredField("lock").apply { isAccessible = true }.get(instance)
            assertFalse(Thread.holdsLock(lock(DesktopSessionStore::class.java, sessions)))
            assertFalse(Thread.holdsLock(lock(DesktopOriginalVideoRootGate::class.java, gate)))
            assertFalse(Thread.holdsLock(lock(MpvPlayer::class.java, player)))
        }
        suspend fun ui(action: () -> Unit) = withContext(Dispatchers.Swing) { action() }
        suspend fun <T> uiResult(action: () -> T): T = withContext(Dispatchers.Swing) { action() }
        suspend fun close() {
            tagsRelease.countDown(); relatedRelease.countDown(); dispatcher.drainOnClose()
            if (::facade.isInitialized) facade.close()
            if (nextWatchMode && ::settingsContext.isInitialized && gate.owns())
                behavior(PlaybackCompletionBehavior.CONTINUE_CURRENT_LOGIC)
            slot.closeAndJoin()
            nextWatchQueue?.closeAndJoin()
            parentJob.cancelAndJoin()
            if (::runtimeBinding.isInitialized) runtimeBinding.close()
            if (::actor.isInitialized) actor.close()
            if (::cache.isInitialized) cache.close()
            subtitles.close(); player.close()
            if (continuationTarget != null) {
                // Children have joined: restore only this fixture's real temporary preference changes,
                // including when account/entry retirement rejected the owned setter during cleanup.
                store.update("auto_play_cache", startupCacheBefore, clear = true)
                store.update("settings", mapOf("playback_completion_behavior" to completionSettingBefore))
            }
            transport.dispatcher.executorService.shutdownNow(); transport.connectionPool.evictAll()
            assertTrue(failures.isEmpty(), failures.toString())
            assertTrue(unexpectedRequests.isEmpty(), unexpectedRequests.toString())
            deleteCreatedDirectory(directory, "cdn-original-owner-")
        }
        suspend fun useSuspending(block: suspend (Fixture) -> Unit) {
            var primary: Throwable? = null
            try { initialize(); withTimeout(30_000) { block(this@Fixture) } }
            catch (failure: Throwable) { primary = failure; throw failure }
            finally {
                try { withContext(NonCancellable) { close() } }
                catch (cleanup: Throwable) {
                    val failure = primary
                    if (failure == null) throw cleanup
                    if (failure !== cleanup) failure.addSuppressed(cleanup)
                }
            }
        }
    }

    companion object {
        private const val A = "BV1GJ411x7h7"
        private const val B = "BV17x411w7KC"
        private fun video(bvid: String) = "https://media.invalid/$bvid-video.m4s?synthetic=1"
        private fun backupVideo(bvid: String) = "https://backup.invalid/$bvid-video.m4s?synthetic=1"
        private fun audio(bvid: String) = "https://media.invalid/$bvid-audio.m4s?synthetic=1"
        private fun backupAudio(bvid: String) = "https://backup.invalid/$bvid-audio.m4s?synthetic=1"
        private fun viewJson(bvid: String): String {
            check(bvid in listOf(A, B)); val aid = if (bvid == A) 101 else 102; val cid = if (bvid == A) 104 else 105
            return """{"code":0,"data":{"bvid":"$bvid","aid":$aid,"cid":$cid,"title":"Fixture $bvid","pic":"https://media.invalid/cover.jpg","duration":60,"owner":{"mid":51,"name":"Fixture","face":""},"pages":[{"cid":$cid,"page":1,"part":"Fixture","duration":60}]}}"""
        }
        private fun playJson(bvid: String): String = """{"code":0,"data":{"quality":64,"timelength":60000,"accept_quality":[64],"accept_description":["720P"],"dash":{"duration":60,"video":[{"id":64,"baseUrl":"${video(bvid)}","backupUrl":["${backupVideo(bvid)}"],"bandwidth":100000,"mime_type":"video/mp4","codecs":"avc1.640028","width":1280,"height":720,"frameRate":"30","codecid":7,"segment_base":{"initialization":"0-31","index_range":"32-63"}}],"audio":[{"id":30280,"baseUrl":"${audio(bvid)}","backupUrl":["${backupAudio(bvid)}"],"bandwidth":192000,"mime_type":"audio/mp4","codecs":"mp4a.40.2","segment_base":{"initialization":"0-31","index_range":"32-63"}}]}}}"""
        private fun retryVideo(revision: Int) = "https://media.invalid/$A-video.m4s?retry_revision=$revision"
        private fun retryAudio(revision: Int) = "https://media.invalid/$A-audio.m4s?retry_revision=$revision"
        private fun retryPlayJson(revision: Int): String = """{"code":0,"data":{"quality":64,"timelength":60000,"cur_language":"en","accept_quality":[64],"accept_description":["720P"],"dash":{"duration":60,"video":[{"id":64,"baseUrl":"${retryVideo(revision)}","backupUrl":[],"bandwidth":100000,"mime_type":"video/mp4","codecs":"avc1.640028","width":1280,"height":720,"frameRate":"30","codecid":7,"segment_base":{"initialization":"0-31","index_range":"32-63"}}],"audio":[{"id":30280,"baseUrl":"${retryAudio(revision)}","backupUrl":[],"bandwidth":192000,"mime_type":"audio/mp4","codecs":"mp4a.40.2","segment_base":{"initialization":"0-31","index_range":"32-63"}}]}}}"""
        private fun deleteCreatedDirectory(directory: Path, prefix: String) {
            val absolute = directory.toAbsolutePath().normalize(); val real = directory.toRealPath()
            val temp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
            check(absolute.parent == temp && real.parent == temp.toRealPath() && absolute != temp)
            check(absolute.fileName.toString().startsWith(prefix))
            fun validate(path: Path, attrs: BasicFileAttributes) {
                val value = path.toAbsolutePath().normalize()
                check(value.startsWith(absolute) && !attrs.isSymbolicLink && !attrs.isOther)
                // The runner's temporary parent may use a Windows short-path alias.
                // Every child must still resolve to its exact location inside this real root.
                check(path.toRealPath() == real.resolve(absolute.relativize(value)).normalize())
                if (System.getProperty("os.name").startsWith("Windows"))
                    check((Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS) as Int) and 0x400 == 0)
            }
            val nodes = mutableListOf<Path>()
            Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult { validate(dir, attrs); nodes.add(dir); return FileVisitResult.CONTINUE }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { validate(file, attrs); nodes.add(file); return FileVisitResult.CONTINUE }
            })
            for (path in nodes.sortedByDescending { it.nameCount }) {
                check(directory.toRealPath() == real); validate(path, Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)); Files.delete(path)
            }
        }
    }
}
