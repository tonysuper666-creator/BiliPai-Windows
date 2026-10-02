from pathlib import Path
import os
P=Path(__file__).resolve().parent
def wide(p):return Path('\\\\?\\'+os.path.abspath(p))
t=wide(P.parent/'stable-full-video-root-media-actual-proof82/RootMediaFixture.kt').read_text(encoding='utf8')
a=t.index('    private suspend fun exercise() {');b=t.index('        } catch (failure: Throwable)',a)
t=t[:a]+'''    private suspend fun exercise() {
        var error: Throwable? = null
        try {
            val shell = awaitReference("actual product Shell") { refs.find<DesktopOriginalVideoShellOwner>() }
            awaitCondition("actual factory install") { shell.navigationReady() }
            val owner = withContext(Dispatchers.Main) { shell.slot.requireAssembly() }
            val platforms = awaitReference("actual remembered Window platforms") {
                if (shell.slot.currentAssembly() === owner) runCatching { shell.requireWindows() }.getOrNull() else null
            }
            withTimeout(20_000) { platforms.awaitNativeInitialization() }
            val portrait = platforms.portrait
            val fixtureJob = checkNotNull(currentCoroutineContext()[Job])
            fun baseline(binding: DesktopOriginalVideoRepositoryBinding): Long {
                val map = readField(portrait, "captures") as java.util.IdentityHashMap<*,*>
                return readField(checkNotNull(map[binding]), "nativeBaseline") as Long
            }
            fun token(binding: DesktopOriginalVideoRepositoryBinding): Long {
                val map = readField(portrait, "captures") as java.util.IdentityHashMap<*,*>
                return readField(checkNotNull(map[binding]), "requestToken") as Long
            }
            suspend fun capture(bvid:String,cid:Long) = withContext(Dispatchers.Main) {
                portrait.capturePageRequest(bvid, 42L, cid)
            }
            // Capture and publish stay in the SAME original caller coroutine.
            // The clip is explicit local native media, never a fake detail API.
            suspend fun actualFrame(value: DesktopOriginalVideoAcceptedPublication) {
                withTimeout(20_000) { player.state.first { state ->
                    if(state.error!=null) error("Native error: "+state.error)
                    owner.native.isCurrent(value) && state.firstVideoFrameReady && state.videoCodec!=null && !state.loading
                } }
            }
            val clip=root.resolve("fixture-clip.mp4").toUri().toString()
            val firstBinding=portrait.capturePageRequest("BV1xx411c7mD",42L,101L)
            val firstBaseline=baseline(firstBinding)
            val firstToken=token(firstBinding)
            val firstSource=firstBinding.authorized(com.bilipai.desktop.player.PlaybackSource(clip,title="explicit local first"))
            val first=owner.native.publish(com.android.purebilibili.feature.video.playback.loader.PlaybackRequest.create("BV1xx411c7mD",42L,101L),
                firstSource,firstBaseline,fixtureJob) { !fixtureJob.isCancelled && owner.captureLoadState().currentLoadRequestToken==firstToken }
            actualFrame(first)
            checkThat("Initial exact captured Binding reaches native ACK and first frame",first.sourceVersion==firstBaseline+1L)
            val secondBinding=portrait.capturePageRequest("BV1yy411c7mD",42L,202L)
            val secondBaseline=baseline(secondBinding)
            val secondToken=token(secondBinding)
            val secondSession=owner.captureLoadState()
            println("CAPTURE_SECOND token="+secondToken+" previous="+firstToken+" bvid="+secondSession.currentBvid+" requestedCid="+secondSession.currentRequest?.cid+" resolvedCid="+secondSession.currentCid)
            checkThat("Original Session token and requested CID advance before stop/capture",secondToken>firstToken && secondSession.currentBvid=="BV1yy411c7mD" && secondSession.currentRequest?.cid==202L)
            checkThat("Same-player stop synchronously advances version BEFORE capture",secondBaseline==first.sourceVersion+1L && player.currentSourceVersion==secondBaseline && player.currentSourceSnapshot()==null)
            val secondSource=secondBinding.authorized(com.bilipai.desktop.player.PlaybackSource(clip,title="explicit local replacement"))
            val second=owner.native.publish(com.android.purebilibili.feature.video.playback.loader.PlaybackRequest.create("BV1yy411c7mD",42L,202L),
                secondSource,secondBaseline,fixtureJob) { !fixtureJob.isCancelled && owner.captureLoadState().currentLoadRequestToken==secondToken }
            actualFrame(second)
            checkThat("FIFO actual Stop then replacement Load reaches ACK without self rejection",second.sourceVersion==secondBaseline+1L && player.firstActualReadback(second))
            val thirdBinding=portrait.capturePageRequest("BV1zz411c7mD",42L,303L)
            val thirdBaseline=baseline(thirdBinding)
            val thirdToken=token(thirdBinding)
            val thirdSource=thirdBinding.authorized(com.bilipai.desktop.player.PlaybackSource(clip,title="explicit local competing publication"))
            val third=owner.native.publish(com.android.purebilibili.feature.video.playback.loader.PlaybackRequest.create("BV1zz411c7mD",42L,303L),
                thirdSource,thirdBaseline,fixtureJob) { !fixtureJob.isCancelled && owner.captureLoadState().currentLoadRequestToken==thirdToken }
            actualFrame(third)
            val bindingPlatform=portrait as DesktopOriginalPortraitPlatformBinding
            val cancelledBeforeClear=Job().also { it.cancel() }
            val cancelledClear=runCatching { bindingPlatform.clearCapturedPagePlayback(thirdToken,cancelledBeforeClear) }
            checkThat("Caller cancelled before clear cannot stop the accepted native source",cancelledClear.exceptionOrNull() is CancellationException && owner.native.current()===third && player.currentSourceVersion==third.sourceVersion)
            val stalePageToken=owner.playback.beginDesktopPortraitLoad(com.android.purebilibili.feature.video.playback.loader.PlaybackRequest.create("BV155411c7mD",42L,505L))
            val nextPageToken=owner.playback.beginDesktopPortraitLoad(com.android.purebilibili.feature.video.playback.loader.PlaybackRequest.create("BV166411c7mD",42L,606L))
            val replacedClear=runCatching { bindingPlatform.clearCapturedPagePlayback(stalePageToken,fixtureJob) }
            checkThat("Replaced real Session token before clear cannot stop another source",nextPageToken>stalePageToken && replacedClear.exceptionOrNull() is CancellationException && owner.native.current()===third && player.currentSourceVersion==third.sourceVersion)
            val stale=runCatching { owner.native.publish(com.android.purebilibili.feature.video.playback.loader.PlaybackRequest.create("BV1zz411c7mD",42L,303L),
                thirdSource,thirdBaseline,fixtureJob) { true } }
            checkThat("Captured baseline does not retag over a later native publication",stale.exceptionOrNull() is CancellationException && owner.native.current()===third)
            data class CancelCapture(val binding:DesktopOriginalVideoRepositoryBinding,val baseline:Long,val source:com.bilipai.desktop.player.PlaybackSource,val caller:Job)
            val ready=CompletableDeferred<CancelCapture>()
            val cancelled=CoroutineScope(currentCoroutineContext()).launch {
                val binding=portrait.capturePageRequest("BV144411c7mD",42L,404L)
                ready.complete(CancelCapture(binding,baseline(binding),binding.authorized(com.bilipai.desktop.player.PlaybackSource(clip,title="cancelled source")),checkNotNull(currentCoroutineContext()[Job])))
                awaitCancellation()
            }
            val cancelledCapture=ready.await();cancelled.cancelAndJoin()
            val rejected=runCatching { owner.native.publish(com.android.purebilibili.feature.video.playback.loader.PlaybackRequest.create("BV144411c7mD",42L,404L),
                cancelledCapture.source,cancelledCapture.baseline,cancelledCapture.caller) { true } }
            checkThat("Cancelled original caller remains rejected after baseline repair",rejected.exceptionOrNull() is CancellationException && player.currentSourceVersion==cancelledCapture.baseline)
            withContext(Dispatchers.Main) {
                checkThat("One actual Assembly/Section/MPV/Canvas remains",shell.slot.currentAssembly()===owner && owner.section.nativePlayer===player && countCanvas(checkNotNull(windowRef.get()),checkNotNull(findCanvas(player.surface)))==1)
                checkThat("No original VideoDetail Success was seeded or asserted",owner.playback.uiState.value !is VideoPlaybackUiState.Success)
            }
            closeProduct()
            checkThat("Root shutdown drains actual assembly and native session",shell.slot.assemblies.value==null && !owner.owns() && player.decoderCapabilities.value==null)
''' +t[b:]
t=t.replace('PASS_ACTUAL_GUEST_MEDIA_ROOT','PASS_PROSPECTIVE_STORY_BASELINE_NATIVE').replace('put("productionOverrides", 0)','put("productionOverrides", "explicit frozen Story89/review plus two baseline source edits")')
t=t.replace('DesktopOriginalVideoRootWindowPlatformsImpl::class.java, MpvPlayer::class.java)', 'DesktopOriginalVideoRootWindowPlatformsImpl::class.java, MpvPlayer::class.java, DesktopOriginalPortraitPlatformBinding::class.java, com.android.purebilibili.feature.video.viewmodel.VideoPlaybackViewModel::class.java)')
wide(P/'RootMediaFixture.kt').write_text(t,encoding='utf8',newline='\n')
print('Prepared one explicit local native source sequence; no API/Success seed, no production write')
