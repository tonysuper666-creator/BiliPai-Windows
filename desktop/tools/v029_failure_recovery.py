"""Fixed v029 neutral failure declarations and counted current-owner seams.

The Android SharedPlaybackSession engine is archived, never instantiated here.
Windows consumes the existing complete original recovery policy in its ONE VM.
Each incremental transform is reversible to the already audited v025 adaptation.
"""
from pathlib import Path
import hashlib
import json

COMMIT = "a4b77f894d0a2dd26c0b9fc144b8adb88ac05480"
VM = "com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt"
SLICE = "upstream-slices/v029-playback-recovery"
PINS = {
    "SharedPlaybackSession.kt": (21663, "4323002bba1f63770991956e3754b76dadf9136aea87e4d8a072c16a016ef63c"),
    "PlayerErrorRecoveryPolicy.kt": (2078, "98385054c413bda87e5948d0dcc945e42afa9abaa46379cf9a8f286429e4d28d"),
    "PlayerErrorRecoveryPolicyTest.kt": (4572, "f863de4ca8dd501834f420e4f99d45dc0fbab4f4e280952a4f1fa295326a8a0e"),
}

def _sha(value):
    return hashlib.sha256(value.encode("utf-8") if isinstance(value, str) else value).hexdigest()

def verified_sources(repo):
    root = Path(repo) / "desktop" / SLICE
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["commit"] == COMMIT
    assert {row["file"] for row in manifest["sources"]} == set(PINS)
    output = {}
    for row in manifest["sources"]:
        raw = (root / row["file"]).read_bytes()
        assert (row["bytes"], row["sha256"]) == PINS[row["file"]]
        assert (len(raw), _sha(raw)) == PINS[row["file"]], row["file"]
        output[row["file"]] = raw.decode("utf-8").replace("\r\n", "\n")
    return output

def metadata_source(repo):
    raw = verified_sources(repo)["SharedPlaybackSession.kt"]
    before = raw[:raw.index("enum class PlaybackStatus")]
    body = raw[len(before):raw.index("data class SharedPlaybackState(")]
    assert body.count("enum class ") == 2 and body.count("data class ") == 2
    # Declarations are unchanged; imports/Android engine are not Windows metadata.
    return "// Fixed v029 SharedPlaybackSession neutral declarations; no Android engine.\npackage com.bilipai.desktop.ui\n\n" + body

def emit_metadata(repo, output):
    path = "com/bilipai/desktop/ui/DesktopOriginalPlaybackFailureMetadata.kt"
    body = metadata_source(repo)
    target = Path(output) / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(body, encoding="utf-8", newline="\n")
    return dict(path=path, origin="core-player/src/main/java/com/android/purebilibili/core/player/SharedPlaybackSession.kt",
                upstreamCommit=COMMIT, sha256LF=_sha(body), mode="neutral-original-declarations", generated=True)

class _Delta:
    def __init__(self, body):
        self.original = self.body = body
        self.edits = []
    def change(self, before, after, count=1):
        assert self.body.count(before) == count, (before[:100], self.body.count(before), count)
        for _ in range(count):
            index = self.body.index(before)
            self.body = self.body[:index] + after + self.body[index + len(before):]
            self.edits.append(dict(offset=index, before=before, after=after))
    def finish(self, audit=None):
        restored = self.body
        for row in reversed(self.edits):
            index = row["offset"]
            assert restored[index:index + len(row["after"]) ] == row["after"]
            restored = restored[:index] + row["before"] + restored[index + len(row["after"]):]
        assert restored == self.original
        if audit is not None:
            audit.extend(self.edits)
        return self.body

def original_policy_test_source(repo, audit=None):
    d = _Delta(verified_sources(repo)["PlayerErrorRecoveryPolicyTest.kt"])
    d.change("import androidx.media3.common.PlaybackException", "import com.bilipai.desktop.player.platform.DesktopMedia3ErrorCodes as PlaybackException")
    d.change("class PlayerErrorRecoveryPolicyTest {", "class DesktopOriginalV029RecoveryPolicyTest {")
    d.change("PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK",
             "com.bilipai.desktop.player.platform.DesktopPremiumAudioMedia3ErrorCodes.ERROR_CODE_FAILED_RUNTIME_CHECK", 5)
    return d.finish(audit)

def typed_protocol_delta(body, audit=None):
    d = _Delta(body)
    d.change('val rawInfo = viewResp.data ?: throw Exception("视频详情为空: ${viewResp.code}")',
             '''if (viewResp.code != 0) throw com.bilipai.desktop.data.BiliApiException(viewResp.code, "视频请求失败").also {
                com.bilipai.desktop.ui.desktopVideoBootstrapTagApiFailure(it,
                    com.bilipai.desktop.ui.DesktopVideoBootstrapApiParameters.Detail(bvid, aid, requestedCid))
            }
            val rawInfo = viewResp.data ?: throw Exception("视频详情为空")''')
    d.change('''if (response.code in listOf(-404, -403, -10403, -62002)) {
            throw Exception(errorMessage)
        }''', '''if (response.code in listOf(-101, -404, -403, -10403, -62002)) {
            throw com.bilipai.desktop.data.BiliApiException(response.code, "视频请求失败").also {
                com.bilipai.desktop.ui.desktopVideoBootstrapTagApiFailure(it,
                    com.bilipai.desktop.ui.DesktopVideoBootstrapApiParameters.WebPlayUrl(bvid, cid, qn, audioLang))
            }
        }''')
    return d.finish(audit)

def usecase_delta(body, audit=None):
    d = _Delta(body)
    d.change("com.bilipai.desktop.ui.desktopWindowsVideoLoadError(e)",
             "com.bilipai.desktop.ui.desktopOriginalBootstrapVideoLoadError(e, com.bilipai.desktop.ui.desktopOriginalVideoLoadError(e))", 3)
    d.change("com.bilipai.desktop.ui.desktopWindowsVideoLoadCanRetry(e)",
             "com.bilipai.desktop.ui.desktopOriginalVideoLoadCanRetry(e)")
    return d.finish(audit)

VM_MEMBERS = '''
    private val desktopPlaybackRecovery by lazy {
        com.bilipai.desktop.ui.DesktopOriginalPlaybackRecovery(environment.scope, environment::commit, environment::assertCurrent)
    }
    internal val desktopPlaybackRecoveryState get() = desktopPlaybackRecovery.state

    private val desktopVideoBootstrapLoadFailure = java.util.concurrent.atomic.AtomicReference<com.bilipai.desktop.ui.DesktopVideoBootstrapLoadFailure?>(null)
    internal fun desktopBootstrapReadFailure(): com.bilipai.desktop.ui.DesktopVideoBootstrapLoadFailure? {
        check(java.awt.EventQueue.isDispatchThread())
        val current = desktopVideoBootstrapLoadFailure.get() ?: return null
        var result: com.bilipai.desktop.ui.DesktopVideoBootstrapLoadFailure? = null
        if (current.source.admit(false) {
            if (desktopVideoBootstrapLoadFailure.get() === current && _uiState.value === current.displayedError &&
                currentLoadRequestToken == current.source.accepted.requestToken) result = current
        } && result != null) return result
        desktopVideoBootstrapLoadFailure.compareAndSet(current, null)
        return null
    }
    private fun retainDesktopBootstrapLoadFailure(requestToken: Long, failed: VideoPlaybackUiState.Error, caller: Job,
        source: com.bilipai.desktop.ui.DesktopVideoBootstrapReadSource?) {
        source ?: return
        if (source.accepted.requestToken != requestToken || source.bodyJob() !== caller) return
        val value = com.bilipai.desktop.ui.DesktopVideoBootstrapLoadFailure(source, failed, source.mappedFor(failed.error))
        if (!source.admit(true) {
            if (currentLoadRequestToken == requestToken && _uiState.value === failed) desktopVideoBootstrapLoadFailure.set(value)
        }) return
        fun clearCancelled() {
            if (source.factoryCaller.isCancelled || caller.isCancelled) desktopVideoBootstrapLoadFailure.compareAndSet(value, null)
        }
        source.factoryCaller.invokeOnCompletion { clearCancelled() }
        caller.invokeOnCompletion { clearCancelled() }
    }

    private fun observeDesktopLoadFailure(requestToken: Long, failed: VideoPlaybackUiState.Error, requestJob: Job, bootstrapSource: com.bilipai.desktop.ui.DesktopVideoBootstrapReadSource?) {
        retainDesktopBootstrapLoadFailure(requestToken, failed, requestJob, bootstrapSource)
        fun current(): Boolean = !requestJob.isCancelled && currentLoadRequestToken == requestToken && _uiState.value === failed
        desktopPlaybackRecovery.fail(com.bilipai.desktop.ui.DesktopOriginalPlaybackRecoveryTicket(
            identity = failed, evidence = com.bilipai.desktop.ui.desktopOriginalApiFailure(failed.error),
            hasCdnAlternatives = false, positionMs = desktopLoadResumePositionMs, playWhenReady = desktopLoadPlayWhenReady,
            current = ::current, execute = { action, position, play ->
                if (!current()) false else when (action) {
                    com.android.purebilibili.feature.video.state.PlayerErrorRecoveryAction.RETRY_NETWORK,
                    com.android.purebilibili.feature.video.state.PlayerErrorRecoveryAction.RETRY_NON_NETWORK -> {
                        retry(desktopRecovering = true, desktopResumePositionMs = position, desktopAutoPlay = play); true
                    }
                    else -> false
                }
            }))
    }
    private var desktopLoadResumePositionMs = 0L
    private var desktopLoadPlayWhenReady = true
    private var desktopLoadAudioLang: String? = null
    private var desktopPremiumFallbackIdentity: Any? = null

    private fun observeDesktopNativeFailure(error: com.bilipai.desktop.ui.DesktopOriginalNativePlaybackError) {
        val failure = error.failure ?: return
        val player = exoPlayer ?: return
        val success = _uiState.value as? VideoPlaybackUiState.Success ?: return
        val accepted = environment.plugins.capturePlaybackDispatch() ?: return
        if (failure.sourceVersion != accepted.sourceVersion || player.state.value.failure !== failure) return
        val requestToken = currentLoadRequestToken
        val desktopFailure = com.bilipai.desktop.ui.DesktopOriginalNativeRecoveryTicket(accepted, failure.attemptId)
        val premiumCode = com.bilipai.desktop.player.desktopPremiumAudioFailureCode(failure)
        val premium = premiumCode != null && isPremiumAudioPlaybackFailure(premiumCode, success.selectedAudioQuality,
            "mpv/audio-output", null) && success.availableAudioQualities.any { it.preferenceId == AUDIO_QUALITY_AUTO }
        fun current(): Boolean = currentLoadRequestToken == requestToken &&
            environment.plugins.isPlaybackDispatchCurrent(accepted) &&
            com.bilipai.desktop.ui.desktopOriginalNativeFailureCurrent(player.nativePlayer, accepted, failure) &&
            (_uiState.value as? VideoPlaybackUiState.Success)?.let {
                it.info.bvid == success.info.bvid && it.info.cid == success.info.cid } == true
        desktopPlaybackRecovery.fail(com.bilipai.desktop.ui.DesktopOriginalPlaybackRecoveryTicket(
            identity = failure, evidence = com.bilipai.desktop.ui.desktopOriginalNativeFailure(failure, premium),
            hasCdnAlternatives = success.cdnCount > 1 || playbackCdnFallbackState.usesCdnRewrite,
            positionMs = player.currentPosition.coerceAtLeast(0L), playWhenReady = player.playWhenReady,
            current = ::current, execute = { action, position, play ->
                if (!current()) false else when (action) {
                    com.android.purebilibili.feature.video.state.PlayerErrorRecoveryAction.SWITCH_CDN -> { switchCdn(desktopFailure); true }
                    com.android.purebilibili.feature.video.state.PlayerErrorRecoveryAction.FALLBACK_PREMIUM_AUDIO -> {
                        fallbackFromPremiumAudioPlaybackError(desktopFailure); true
                    }
                    com.android.purebilibili.feature.video.state.PlayerErrorRecoveryAction.RETRY_DECODER_FALLBACK -> {
                        retryWithCodecFallback(desktopRecovering = true, desktopResumePositionMs = player.currentPosition.coerceAtLeast(0L),
                            desktopAutoPlay = player.playWhenReady); true
                    }
                    com.android.purebilibili.feature.video.state.PlayerErrorRecoveryAction.RETRY_NETWORK,
                    com.android.purebilibili.feature.video.state.PlayerErrorRecoveryAction.RETRY_NON_NETWORK -> {
                        retry(desktopRecovering = true, desktopResumePositionMs = player.currentPosition.coerceAtLeast(0L),
                            desktopAutoPlay = player.playWhenReady); true
                    }
                    else -> false
                }
            }))
    }
'''

def owner_delta(path, body, audit=None):
    if path != VM:
        return body
    d = _Delta(body)
    d.change('    internal fun captureDesktopPlaybackState(): VideoPlaybackUiState = _uiState.value\n',
             '    internal fun captureDesktopPlaybackState(): VideoPlaybackUiState = _uiState.value\n' + VM_MEMBERS)
    start = body.index('        override fun onPlayerError(error: com.bilipai.desktop.ui.DesktopOriginalNativePlaybackError) {')
    end = body.index('\n    }\n\n    private fun schedulePlaybackStallRecovery()', start)
    d.change(body[start:end], '''        override fun onPlayerError(error: com.bilipai.desktop.ui.DesktopOriginalNativePlaybackError) {
            if (hasDesktopBangumiPlayback()) return
            cancelPlaybackStallRecovery()
            observeDesktopNativeFailure(error)
        }''')
    d.change('''            if (playbackState == Player.STATE_READY) {
                cancelPlaybackStallRecovery()''', '''            if (playbackState == Player.STATE_READY) {
                desktopPlaybackRecovery.ready()
                cancelPlaybackStallRecovery()''')
    d.change('''        desktopExplicitStartPositionMs: Long? = null
    ) {
        require(desktopExplicitStartPositionMs == null || desktopExplicitStartPositionMs >= 0L)''', '''        desktopExplicitStartPositionMs: Long? = null,
        desktopRecovering: Boolean = false,
        desktopBootstrapSource: com.bilipai.desktop.ui.DesktopVideoBootstrapSeed? = null
    ) {
        require(desktopExplicitStartPositionMs == null || desktopExplicitStartPositionMs >= 0L)''')
    d.change('''        val loadRequestContext = playbackSessionStore.beginLoadRequest(playbackRequest)
        val requestToken = loadRequestContext.requestToken''', '''        desktopPlaybackRecovery.beginLoad(desktopRecovering)
        desktopLoadResumePositionMs = requestedStartPositionMs
        desktopLoadPlayWhenReady = autoPlay ?: true
        desktopLoadAudioLang = playbackRequest.audioLang
        val loadRequestContext = playbackSessionStore.beginLoadRequest(playbackRequest)
        val requestToken = loadRequestContext.requestToken
        desktopVideoBootstrapLoadFailure.set(null)
        val desktopBootstrapRequest = desktopBootstrapSource?.accepted(playbackRequest, requestToken, fallbackResumePositionMs)''')
    d.change('        activeLoadJob = environment.invocations.launch {',
             '        activeLoadJob = environment.invocations.launch(context = desktopBootstrapRequest ?: kotlin.coroutines.EmptyCoroutineContext) {')
    for before in ['_uiState.value = VideoPlaybackUiState.Error(loadResult.error, loadResult.canRetry)',
                   '_uiState.value = VideoPlaybackUiState.Error(VideoLoadError.Timeout)',
                   '_uiState.value = VideoPlaybackUiState.Error(VideoLoadError.UnknownError(e))']:
        d.change(before, before + '''
                val desktopFailure = _uiState.value as? VideoPlaybackUiState.Error
                if (desktopFailure != null) observeDesktopLoadFailure(requestToken, desktopFailure, checkNotNull(kotlinx.coroutines.currentCoroutineContext()[Job]), kotlinx.coroutines.currentCoroutineContext()[com.bilipai.desktop.ui.DesktopVideoBootstrapInvocationContext]?.source)''')
    d.change('    fun retry() {', '''    fun retry(desktopRecovering: Boolean = false, desktopResumePositionMs: Long? = null, desktopAutoPlay: Boolean? = null) {''')
    d.change('    fun retryWithCodecFallback() {', '''    fun retryWithCodecFallback(desktopRecovering: Boolean = false, desktopResumePositionMs: Long? = null, desktopAutoPlay: Boolean? = null) {''')
    # Only the two original retry bodies, never global load call sites.
    for name, next_name in [('    fun retry(', '    fun retryWithCodecFallback('),
                            ('    fun retryWithCodecFallback(', '    fun switchCdn()')]:
        a = d.body.index(name); b = d.body.index(next_name, a + len(name))
        before = d.body[a:b]
        after = before.replace('val fallbackResumePositionMs = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L',
                               'val fallbackResumePositionMs = desktopResumePositionMs ?: (exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L)', 1)
        after = after.replace('val resumePlaybackAfterRetry = exoPlayer?.let', 'val resumePlaybackAfterRetry = desktopAutoPlay ?: exoPlayer?.let', 1)
        after = after.replace('val currentAudioLang = (_uiState.value as? VideoPlaybackUiState.Success)?.currentAudioLang',
                              'val currentAudioLang = (_uiState.value as? VideoPlaybackUiState.Success)?.currentAudioLang\n            ?: if (desktopRecovering) desktopLoadAudioLang else null', 1)
        after = after.replace('fallbackResumePositionMs = fallbackResumePositionMs\n', '''fallbackResumePositionMs = fallbackResumePositionMs,
            desktopExplicitStartPositionMs = if (desktopRecovering) fallbackResumePositionMs else null,
            desktopRecovering = desktopRecovering
''', 1)
        after = after.replace('            retry()\n', '            retry(desktopRecovering, desktopResumePositionMs, desktopAutoPlay)\n')
        after = after.replace('        PlaybackCooldownManager.clearForVideo(bvid)',
                              '        if (!desktopRecovering) PlaybackCooldownManager.clearForVideo(bvid)')
        after = after.replace('currentState is VideoPlaybackUiState.Error &&', '!desktopRecovering && currentState is VideoPlaybackUiState.Error &&')
        d.change(before, after)
    d.change('''    override fun close() {
        retireDesktopBangumiPresenter()''', '''    override fun close() {
        desktopVideoBootstrapLoadFailure.set(null)
        desktopPlaybackRecovery.close()
        retireDesktopBangumiPresenter()''')
    d.change('''                val shouldAutoPlay = playbackRequest.autoPlay ?: appContext?.let {
                    com.android.purebilibili.core.store.DesktopOriginalPlayerSectionSettings.getClickToPlaySync(it)
                } ?: true''', '''                val shouldAutoPlay = playbackRequest.autoPlay ?: appContext?.let {
                    com.android.purebilibili.core.store.DesktopOriginalPlayerSectionSettings.getClickToPlaySync(it)
                } ?: true
                desktopLoadPlayWhenReady = shouldAutoPlay''')
    d.change('    fun switchCdn() {', '    fun switchCdn(desktopFailure: com.bilipai.desktop.ui.DesktopOriginalNativeRecoveryTicket? = null) {')
    a = d.body.index('    fun switchCdn('); b = d.body.index('    fun switchCdnTo(', a)
    before = d.body[a:b]
    after = before.replace('environment.invocations.launch {', 'environment.invocations.launch(desktopFailure = desktopFailure) {')
    d.change(before, after)
    d.change('    internal fun fallbackFromPremiumAudioPlaybackError() {',
             '    internal fun fallbackFromPremiumAudioPlaybackError(desktopFailure: com.bilipai.desktop.ui.DesktopOriginalNativeRecoveryTicket? = null) {')
    a = d.body.index('    internal fun fallbackFromPremiumAudioPlaybackError(')
    b = d.body.index('    //  相互作用', a)
    before = d.body[a:b]
    after = before.replace('environment.invocations.launch {', 'environment.invocations.launch(desktopFailure = desktopFailure) {')
    after = after.replace('''                    playWhenReady = playWhenReady
                )''', '''                    playWhenReady = playWhenReady,
                    desktopRecovering = desktopFailure != null
                )''', 1)
    after = after.replace('''        val current = _uiState.value as? VideoPlaybackUiState.Success ?: return
        val player = exoPlayer ?: return''', '''        if (desktopFailure != null && (!environment.plugins.isPlaybackDispatchCurrent(desktopFailure.source) ||
                exoPlayer?.state?.value?.failure?.attemptId != desktopFailure.failureAttemptId)) return
        val current = _uiState.value as? VideoPlaybackUiState.Success ?: return
        val player = exoPlayer ?: return''', 1)
    after = after.replace('''        premiumAudioFallbackInProgress = true''', '''        val desktopFailedIdentity = if (desktopFailure == null) null else player.state.value.failure
        val desktopFallbackIdentity = Any()
        desktopPremiumFallbackIdentity = desktopFallbackIdentity
        premiumAudioFallbackInProgress = true''', 1)
    after = after.replace('''                    _uiState.value = updated.copy(
                        requestedAudioQuality = requestedAudioQuality,
                        audioFallbackReason = AudioFallbackReason.DECODER_ERROR
                    )''', '''                    val publish: () -> Unit = {
                        _uiState.value = updated.copy(
                            requestedAudioQuality = requestedAudioQuality,
                            audioFallbackReason = AudioFallbackReason.DECODER_ERROR
                        )
                    }
                    if (desktopFailure == null) publish()
                    else environment.invocations.admitRecoveryAction(publish)''', 1)
    after = after.replace('''                    player.pause()
                    toast("Hi-Res 解码失败，AAC 回退也未能完成")''', '''                    if (desktopFailure == null) player.pause()
                    else environment.invocations.admitRecoveryAction { player.nativePlayer.setPaused(true) }
                    desktopFailedIdentity?.let(desktopPlaybackRecovery::recoveryFailed)
                    toast("Hi-Res 解码失败，AAC 回退也未能完成")''', 1)
    after = after.replace('''            } catch (error: Exception) {
                Logger.w("PlayerVM", "Hi-Res audio fallback failed: ${error.message}")
                player.pause()''', '''            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Logger.w("PlayerVM", "Hi-Res audio fallback failed: ${error.message}")
                if (desktopFailure == null) player.pause()
                else environment.invocations.admitRecoveryAction { player.nativePlayer.setPaused(true) }
                desktopFailedIdentity?.let(desktopPlaybackRecovery::recoveryFailed)''', 1)
    after = after.replace('''                premiumAudioFallbackInProgress = false''', '''                if (desktopPremiumFallbackIdentity === desktopFallbackIdentity) {
                    desktopPremiumFallbackIdentity = null
                    premiumAudioFallbackInProgress = false
                }''', 1)
    d.change(before, after)
    a = d.body.index('    private suspend fun refreshPlaybackAudioForSpeedCompatibility(')
    b = d.body.index('    //  SponsorBlock', a)
    before = d.body[a:b]
    after = before.replace('''        playWhenReady: Boolean
    ): Boolean''', '''        playWhenReady: Boolean,
        desktopRecovering: Boolean = false
    ): Boolean''', 1)
    after = after.replace('''            armPlaybackCdnFallback(cdnSelection.fallbackState, playWhenReady)''', '''            if (!desktopRecovering) armPlaybackCdnFallback(cdnSelection.fallbackState, playWhenReady)''', 1)
    after = after.replace('''        _uiState.value = current.copy(''', '''        val publish: () -> Unit = {
            if (desktopRecovering && cdnSelection.playUrl == result.videoUrl && cdnSelection.audioUrl == result.audioUrl)
                armPlaybackCdnFallback(cdnSelection.fallbackState, playWhenReady)
            _uiState.value = current.copy(''', 1)
    after = after.replace('''        )
        return true''', '''        ) }
        if (desktopRecovering) environment.invocations.admitRecoveryAction(publish) else publish()
        return true''', 1)
    d.change(before, after)
    return d.finish(audit)
