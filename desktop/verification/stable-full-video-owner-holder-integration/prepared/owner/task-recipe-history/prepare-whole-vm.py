"""WIP full original PlaybackVM declaration. NOT an install producer yet.
No global/Android stand-in is supplied; remaining external effects require typed
same-owner ports before compilation and final inverse-body audit.
"""
from pathlib import Path
import hashlib,json,re
H=Path(__file__).resolve().parent
original=(H/'original-stable/VideoPlaybackViewModel.kt').read_text(encoding='utf-8')
assert hashlib.sha256(original.encode()).hexdigest()=='d6a73469c219cd573563df44a5947713adea6505ee1c8228432c9b46cdd672ae'
body=original[original.index('class VideoPlaybackViewModel(application: Application)'):]
changes=[]
def swap(a,b,label):
    global body
    count=body.count(a)
    if count:
        before_hash=hashlib.sha256(body.encode()).hexdigest()
        positions=[m.start() for m in re.finditer(re.escape(a),body)]
        body=body.replace(a,b)
        after_positions=[position+i*(len(b)-len(a)) for i,position in enumerate(positions)]
        changes.append(dict(label=label,before=a,after=b,count=count,afterPositions=after_positions,beforeStateSha256LF=before_hash,afterStateSha256LF=hashlib.sha256(body.encode()).hexdigest()))
swap('class VideoPlaybackViewModel(application: Application) : AndroidViewModel(application) {', '''internal class VideoPlaybackViewModel(private val environment: com.bilipai.desktop.ui.DesktopOriginalVideoPlaybackOwnerEnvironment) : AutoCloseable {
    private val viewModelScope get() = environment.scope
    private val VideoRepository get() = environment.repository
    private val VideoNoteRepository get() = environment.notes
    private fun <T> MutableStateFlow(initial:T):MutableStateFlow<T> =
        com.bilipai.desktop.ui.DesktopHomeOwnedMutableStateFlow(initial, environment::commit)
''', 'Actual retained entry scope/owned flow, complete original class')
replacements={
 'private val playbackUseCase = VideoPlaybackUseCase()':('private val playbackUseCase = VideoPlaybackUseCase(environment.useCase)','Existing original UseCase with actual same operation facade'),
 'private val interactionUseCase = VideoInteractionUseCase()':('private val interactionUseCase = environment.interactionUseCase','One existing original engagement/action instance'),
 'viewModelScope.launch':('environment.invocations.launch','Capture inside actual original Job, preserve dispatchers/start/children'),
 'application.applicationContext':('environment.settings','Actual same global player settings'),
 'android.content.Context':('com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext','Required actual settings port instead of Android Context'),
 'val applicationContext = context.applicationContext':('val applicationContext = context','Actual same settings instance identity'),
 'playbackUseCase.initWithContext(context)':('playbackUseCase.initWithContext(context.pluginContext)','Actual sole global PluginContext'),
 'com.android.purebilibili.core.network.NetworkModule.appContext':('environment.settings','Required context, no global Android fallback'),
 'com.android.purebilibili.core.network.NetworkModule.api':('environment.repository.primaryApi','Current captured primary API'),
 'com.android.purebilibili.core.network.NetworkModule.playbackOkHttpClient':('environment.repository.playbackCalls','Existing captured transport facade, no second client'),
 'MiniPlayerManager.getInstance(applicationContext)':('environment.mini','Same actual Mini owner'),
 'MiniPlayerManager.getInstance(context)':('environment.mini','Same actual Mini owner'),
 'MiniPlayerManager.getInstance(it)':('environment.mini','Same actual Mini owner'),
 'MiniPlayerManager.getInstance(getApplication<Application>())':('environment.mini','Actual owner instead of Android Application'),
 'PlaylistManager.':('environment.playlist.','Same existing queue authority'),
 'com.android.purebilibili.core.store.SettingsManager.':('com.android.purebilibili.core.store.','Sole original same-global getter functions'),
 'SettingsManager\n        .getPlaybackCdnPreference':('com.android.purebilibili.core.store.getPlaybackCdnPreference','Sole canonical same-global CDN getter'),
 'SettingsManager.':('com.android.purebilibili.core.store.','Sole same-global getter functions'),
 'com.android.purebilibili.core.store.TokenManager.sessDataCache.isNullOrEmpty()':('!environment.account.hasSession','Admitted Boolean, no session credential projection'),
 'com.android.purebilibili.core.store.TokenManager.accessTokenCache.isNullOrEmpty()':('!environment.account.hasAccessToken','Admitted token presence, no credential projection'),
 'com.android.purebilibili.core.store.TokenManager.midCache':('environment.account.mid','Same admitted primary MID'),
 'com.android.purebilibili.core.store.TokenManager.isVipCache = true':('environment.account.updatePrimaryVip(true)','Same Store primary VIP commit'),
 'BackgroundManager.isInBackground':('environment.background.value','Actual global Window background state'),
 'com.android.purebilibili.core.util.MediaUtils.isHevcSupported()':('environment.useCase.capabilities.isHevcSupported()','Actual decoder capability port'),
 'com.android.purebilibili.core.util.MediaUtils.isAv1Supported()':('environment.useCase.capabilities.isAv1Supported()','Actual decoder capability port'),
 'NetworkUtils.isMobileData(it)':('environment.network.isMobileData()','Actual Windows WWAN query'),
 'NetworkUtils.isWifi(it)':('environment.network.isWifi()','Actual Windows WiFi query'),
 'AnalyticsHelper.':('environment.analytics.','Required same consent/capability analytics effect'),
 'CrashReporter.':('environment.crash.','Required actual local diagnostic effect'),
 'com.android.purebilibili.core.player.PlayerVolumeController.applyPreferredVolume(player)':('environment.useCase.applyPreferredVolume(player)','Existing actual preferred-volume setter'),
 'private val sponsorPlaybackPlugin = com.android.purebilibili.feature.plugin.SponsorBlockPlugin()':('private val sponsorPlaybackPlugin get() = environment.plugins.sponsorBlock','Same Runtime Sponsor provider, no new plugin instance'),
 'PluginManager.getEnabledPlayerPlugins()':('environment.plugins.enabledPlayerPlugins()','Same Runtime provider/generation views'),
 'PluginManager.getEnabledPlugins()':('environment.plugins.enabledPlugins()','Same global Manager authority'),
 'com.android.purebilibili.data.repository.ActionRepository.':('environment.actions.','Required same request-scoped original actions'),
 'com.android.purebilibili.data.repository.ViewGrpcRepository.getBgmList':('environment.repository.getBgmList','Existing sole Ops gRPC BGM protocol'),
 'com.android.purebilibili.feature.download.DownloadManager.':('environment.download.','Same actual transient task view and manager'),
 'override fun onMediaItemTransition(\n            mediaItem: androidx.media3.common.MediaItem?,\n            reason: Int\n        )':('override fun onSourceTransition(sourceVersion: Long)','Actual native sourceVersion event, no fake MediaItem'),
 'override fun onCleared() {\n        super.onCleared()':('override fun close() {','Actual Root-owned lifecycle drain'),
 '@SuppressLint("UnsafeOptInUsageError")\n        ':('','Android-only annotation removed'),
 'tracks: Tracks':('tracks: List<com.bilipai.desktop.player.PlayerTrack>','Actual native track readback'),
 'error: PlaybackException':('error: com.bilipai.desktop.ui.DesktopOriginalNativePlaybackError','Actual typed native failure'),
 '${error.errorCodeName}':('${error.failure?.kind}','Actual safe native error family'),
 'val exoPlaybackError = error as? ExoPlaybackException':('val premiumFailureCode = error.failure?.let { com.bilipai.desktop.player.desktopPremiumAudioFailureCode(it) }','Only proven AO -14 role maps audio failure'),
 'current != null &&\n                isPremiumAudioPlaybackFailure(':('current != null && premiumFailureCode != null &&\n                isPremiumAudioPlaybackFailure(','Generic/video decoder cannot masquerade as audio'),
 'errorCode = error.errorCode':('errorCode = premiumFailureCode','Proven actual audio mapping'),
 'rendererName = exoPlaybackError?.rendererName':('rendererName = "mpv/audio-output"','Explicit output initialization role'),
 'rendererSampleMimeType = exoPlaybackError?.rendererFormat?.sampleMimeType':('rendererSampleMimeType = null','No fabricated MIME'),
 'List<Uri>':('List<String>','Actual source-owned local image selection paths'),
}
for a,(b,label) in replacements.items():swap(a,b,label)
swap('com.android.purebilibili.data.repository.VideoRepository','VideoRepository','Use SAME current captured repository for every fully qualified original call')
swap('com.android.purebilibili.core.util.Logger','Logger','One actual safe logging binding')
swap('android.os.SystemClock.elapsedRealtime()','(System.nanoTime() / 1_000_000L)','Actual monotonic elapsed clock')
swap('com.android.purebilibili.core.store.player.PlayerSettingsStore','com.android.purebilibili.core.store.player.DesktopOriginalVideoPlayerSettings','Existing original same-global player preference getter authority')
swap('PluginManager','environment.plugins','One Runtime provider view')
swap('.getEnabledPlugins(','.enabledPlugins(','Same existing enabled provider list')
swap('NetworkUtils.isWifi(context)','environment.network.isWifi()','Required actual WiFi query')
swap('NetworkUtils.isMobileData(context)','environment.network.isMobileData()','Required actual WWAN query')
swap('NetworkUtils.resolveEffectiveDataSaverMode','resolveEffectiveDataSaverMode','Pure original DataSaver policy')
swap('com.android.purebilibili.core.store.SettingsManager','com.android.purebilibili.core.store.DesktopOriginalVideoOwnerSettings','Qualified selected original settings owner')
swap('com.android.purebilibili.core.util.MediaUtils.isHdrSupported(it)','environment.useCase.capabilities.isHdrSupported()','Actual HDR capability')
swap('com.android.purebilibili.core.util.MediaUtils.isHdrSupported()','environment.useCase.capabilities.isHdrSupported()','Actual HDR capability')
swap('com.android.purebilibili.core.util.MediaUtils.isDolbyVisionSupported(it)','environment.useCase.capabilities.isDolbyVisionSupported()','Actual DolbyVision capability')
swap('com.android.purebilibili.core.util.MediaUtils.isDolbyVisionSupported()','environment.useCase.capabilities.isDolbyVisionSupported()','Actual DolbyVision capability')
owner_settings=json.loads((H/'owner-settings-identities.json').read_text())['selectedOriginalFunctions']
for name in owner_settings:
 swap('com.android.purebilibili.core.store.'+name,'com.android.purebilibili.core.store.DesktopOriginalVideoOwnerSettings.'+name,'Canonical complete original '+name)
swap('com.android.purebilibili.core.store.getClickToPlaySync','com.android.purebilibili.core.store.DesktopOriginalPlayerSectionSettings.getClickToPlaySync','Reuse existing sole Section getter')
swap('com.android.purebilibili.core.store.getPlaybackCompletionBehaviorSync','com.android.purebilibili.core.store.DesktopOriginalVideoControlSettings.getPlaybackCompletionBehaviorSync','Share the original existing Flow/setter memory cache')
swap('com.android.purebilibili.core.store.DesktopOriginalVideoOwnerSettings\n                    .getPlaybackCompletionBehaviorSync','com.android.purebilibili.core.store.DesktopOriginalVideoControlSettings\n                    .getPlaybackCompletionBehaviorSync','Same sole completion Flow/setter cache for multiline original caller')
swap('com.android.purebilibili.core.store.setAudioQuality','com.android.purebilibili.core.store.DesktopOriginalPortraitSettings.setAudioQuality','Reuse child sole complete original setter')
swap('com.android.purebilibili.core.store.DataSaverMode','com.android.purebilibili.core.store.DesktopOriginalVideoOwnerSettings.DataSaverMode','Original enum in selected original settings owner')
swap('TodayWatchFeedbackStore.getSnapshot(context)','TodayWatchFeedbackStore.getSnapshot(context.pluginContext)','Same global plugin Context')
swap('TodayWatchFeedbackStore.saveSnapshot(context, snapshot)','TodayWatchFeedbackStore.saveSnapshot(context.pluginContext, snapshot)','Same global plugin Context')
swap('SponsorBlockInsightStore.appendRecord(context, record)','SponsorBlockInsightStore.appendRecord(context.pluginContext, record)','Same original Insight store authority')
swap('com.android.purebilibili.data.repository.ActionRepository','environment.actions','Current captured original action view')
swap('com.android.purebilibili.data.repository.CommentRepository','environment.comments','Existing original same owned reply operations')
swap('com.android.purebilibili.data.repository.DanmakuRepository','environment.danmaku','Existing sole raw document/server state and protocol operations')
swap('com.android.purebilibili.data.repository.ViewGrpcRepository','environment.repository','Same original gRPC BGM operation')
swap('miniPlayerManager?.player','miniPlayerManager?.player','marker') if False else None
swap('tracks.groups.any { group ->\n                group.type == C.TRACK_TYPE_AUDIO && group.isSelected\n            }','tracks.any { track -> track.type == "audio" && track.selected }','Actual selected native audio track, no fabricated Media3 group')
swap('private fun hasSelectedAudioTrack(player: Player? = exoPlayer)','private fun hasSelectedAudioTrack(player: ExoPlayer? = exoPlayer)','Required existing Section control includes actual track readback')
swap('private fun hasSelectedAudioTrack(player: Player?)','private fun hasSelectedAudioTrack(player: ExoPlayer?)','Use actual Section track readback')
swap('player?.currentTracks?.groups.orEmpty().any { group ->\n            group.type == C.TRACK_TYPE_AUDIO && group.isSelected\n        }','player?.currentTracks.orEmpty().any { track -> track.type == "audio" && track.selected }','Actual selected native audio track')
swap('context = context,\n            mid = mid,','context = context.pluginContext,\n            mid = mid,','Same global TodayWatch profile store Context')
swap('ViewGrpcRepository.getBgmList','environment.repository.getBgmList','Same captured sole gRPC Ops')
swap('val currentApi = environment.repository.primaryApi\n        environment.invocations.launch {\n            try {','environment.invocations.launch {\n            val currentApi = environment.repository.primaryApi\n            try {','Capture Relation API inside its actual launched request, never before invocation admission')
swap('''            while (true) {
                val plugins = getSessionPlayerPlugins()''','''            while (true) {
                // Same actual native lease; this also runs when all plugins are disabled.
                environment.plugins.observeInheritedPluginMute()
                val plugins = getSessionPlayerPlugins()''','Observe actual64 inherited native mute on the original position/poll observer; no second poller')
swap('sponsorPlaybackPlugin.onVideoEnd()','environment.plugins.onSponsorDisabled()','Same Runtime generation/serialization for disabled Sponsor cleanup')
swap('sponsorPlugin.onVideoLoad(bvid, cid)','environment.plugins.ensureSponsorLoaded(bvid, cid)','Same Runtime original provider; inherited matching generation must not reload/reset skipped IDs')
swap('plugin.onPositionUpdate(currentPos)','environment.plugins.onPositionUpdate(plugin, currentPos)','Dispatch through same Runtime playerMutex and captured generation')
swap('plugin.onUserSeek(positionMs)','environment.plugins.onUserSeek(plugin, positionMs)','Same Runtime generation and actual native user-seek admission')
swap('plugin.onVideoEnd()','environment.plugins.onVideoEnd(plugin)','Same Runtime generation, each real provider is serialized and retirement guarded')
swap('plugin.markAsSkipped(','environment.plugins.markSponsorSkipped(plugin,','Same Runtime guard for original skipped-ID mutation')
swap('plugin.voteOnCommunitySegment(segmentId, voteType)','environment.plugins.voteSponsorSegment(plugin, segmentId, voteType)','Actual original community vote through same generation/consent guarded effect')
swap('request.plugin.submitCommunitySegment(','environment.plugins.submitSponsorSegment(\n                plugin = request.plugin,','Actual original community submit through same generation and captured segment owner')
swap('cdnPlugin?.rewritePlaybackCandidates(rawVideoUrls, rawAudioUrls)','cdnPlugin?.let { environment.plugins.rewritePlaybackCandidates(it, rawVideoUrls, rawAudioUrls) }','Rewrite only authorized candidates of the current immutable raw request')
swap('''cdnPlugin?.buildPlaybackCdnDiagnostics(
                videoUrls = allVideoUrls,
                sources = preferredCandidates.map { it.source }
            ).orEmpty()''','''cdnPlugin?.let { environment.plugins.buildPlaybackCdnDiagnostics(
                plugin = it,
                videoUrls = allVideoUrls,
                sources = preferredCandidates.map { it.source }
            ) }.orEmpty()''','Same original CDN diagnostic algorithm, guarded provider identity')
swap('plugin.buildPlaybackCdnDiagnostics(','environment.plugins.buildPlaybackCdnDiagnostics(plugin = plugin,','Same accepted source health diagnostic effect')
swap('plugin.probePlaybackCdnCandidates(','environment.plugins.probePlaybackCdnCandidates(plugin = plugin,','Same accepted source probe, no HTTP inside Store/entry monitors')
swap('plugin.recordPlaybackCdnEvent(current.playUrl, event)','environment.plugins.recordPlaybackCdnEvent(plugin, current.playUrl, event)','Actual current source health only, through same provider state')
swap('plugin.isAdaptivePrefetchEnabled()','environment.plugins.isAdaptivePrefetchEnabled(plugin)','Same actual plugin configuration reader, required real range consumer remains explicit')
swap('''            runCatching {
                SponsorBlockInsightStore.appendRecord(context.pluginContext, record)
            }.onFailure { error ->
                Logger.w("PlayerVM", "记录空降助手跳过历史失败: ${error.message}")
            }
            getSessionPlayerPlugins()
                .filterIsInstance<com.android.purebilibili.feature.plugin.SponsorBlockPlugin>()
                .forEach { plugin ->
                    plugin.uploadViewedSegmentIfEnabled(capturedSegmentId)
                }''','''            runCatching {
                environment.plugins.recordSponsorSkip(record)
            }.onFailure { error ->
                Logger.w("PlayerVM", "记录空降助手跳过历史失败: ${error.message}")
            }''','Same Runtime history/view consent after actual native seek receipt; no second writer/upload')
swap('''    //  [新增] 播放完成监听器
    private val playbackEndListener = object : Player.Listener {''','''    // Windows admission: capture only the synchronous ticket emitted by THIS seek.
    // No latest-ticket state or position/time matching is used. An unrelated/reentrant
    // second submission fails closed. Restore the lexical slot on every exit.
    private class SponsorSeekCapture {
        var submission: com.bilipai.desktop.ui.DesktopOriginalNativeSeekSubmission? = null
        var ambiguous = false
        fun accept(value: com.bilipai.desktop.ui.DesktopOriginalNativeSeekSubmission) {
            if (submission == null) submission = value
            else if (submission != value) ambiguous = true
        }
    }
    private val sponsorSeekCapture = ThreadLocal<SponsorSeekCapture?>()
    private fun captureSponsorSeek(block: () -> Unit): com.bilipai.desktop.ui.DesktopOriginalNativeSeekSubmission? {
        val previous = sponsorSeekCapture.get()
        val capture = SponsorSeekCapture()
        sponsorSeekCapture.set(capture)
        return try {
            block()
            capture.submission.takeUnless { capture.ambiguous }
        } finally {
            if (previous == null) sponsorSeekCapture.remove() else sponsorSeekCapture.set(previous)
        }
    }

    //  [新增] 播放完成监听器
    private val playbackEndListener = object : Player.Listener {
        override fun onSeekQueued(submission: com.bilipai.desktop.ui.DesktopOriginalNativeSeekSubmission) {
            sponsorSeekCapture.get()?.accept(submission)
        }''','Bind original Sponsor actions to the actual66 canonical synchronous native seek ticket')
auto='''                                playbackUseCase.seekTo(
                                    position = resolvedTargetPositionMs,
                                    resumePlayback = shouldResumePlaybackAfterSponsorBlockSkip(
                                        playWhenReadyBeforeSkip = exoPlayer?.playWhenReady == true
                                    )
                                )
                                recordSponsorBlockSkip(
                                    snapshot = snapshot,'''
swap(auto,'''                                val submission = captureSponsorSeek {
                                    playbackUseCase.seekTo(
                                        position = resolvedTargetPositionMs,
                                        resumePlayback = shouldResumePlaybackAfterSponsorBlockSkip(
                                            playWhenReadyBeforeSkip = exoPlayer?.playWhenReady == true
                                        )
                                    )
                                }
                                recordSponsorBlockSkip(
                                    submission = submission,
                                    snapshot = snapshot,''','Auto skip records require exactly the ticket of the original resolved seek')
manual_start=body.index('    fun skipCurrentSponsorSegment() {')
manual_end=body.index('    fun notifyPluginsOfExplicitSeek(',manual_start)
manual_original=body[manual_start:manual_end]
manual_desired='''    fun skipCurrentSponsorSegment() {
        val targetPosition = _sponsorSkipUiState.value.skipToMs.takeIf { it > 0L } ?: return
        val segmentId = _sponsorSkipUiState.value.segmentId
        val snapshot = buildSponsorBlockVideoSnapshot(_uiState.value)
        var segmentCategory: String? = null
        // Preserve original mark/metadata/timestamp order. Only the Windows history
        // publication waits for the actual queued seek belonging to this action.
        val pendingRecords = mutableListOf<SponsorBlockSkipRecord>()
        getSessionPlayerPlugins().forEach { plugin ->
            if (plugin is com.android.purebilibili.feature.plugin.SponsorBlockPlugin && segmentId != null) {
                val segment = environment.plugins.markSponsorSkipped(plugin,segmentId)
                segmentCategory = segment?.category
                prepareSponsorBlockSkip(
                    snapshot = snapshot,
                    segmentId = segment?.UUID ?: segmentId,
                    segmentCategoryName = segment?.categoryName,
                    startMs = segment?.startTimeMs,
                    endMs = segment?.endTimeMs ?: targetPosition,
                    trigger = SponsorBlockSkipTrigger.MANUAL
                )?.let { pendingRecords.add(it) }
            }
        }
        val resolvedTargetPosition = resolveSponsorBlockSkipTargetPositionMs(
            requestedPositionMs = targetPosition,
            durationMs = playbackUseCase.getDuration(),
            category = segmentCategory,
        )
        val submission = captureSponsorSeek {
            playbackUseCase.seekTo(
                position = resolvedTargetPosition,
                resumePlayback = shouldResumePlaybackAfterSponsorBlockSkip(
                    playWhenReadyBeforeSkip = exoPlayer?.playWhenReady == true
                )
            )
        }
        if (submission != null) pendingRecords.forEach { publishSponsorBlockSkip(submission, it) }
        clearSponsorSkipUi()
    }

    private fun recordSponsorBlockSkip(
        submission: com.bilipai.desktop.ui.DesktopOriginalNativeSeekSubmission?,
        snapshot: SponsorBlockVideoSnapshot?,
        segmentId: String?,
        segmentCategoryName: String?,
        startMs: Long?,
        endMs: Long,
        trigger: SponsorBlockSkipTrigger
    ) {
        val ticket = submission ?: return
        val record = prepareSponsorBlockSkip(snapshot, segmentId, segmentCategoryName, startMs, endMs, trigger) ?: return
        publishSponsorBlockSkip(ticket, record)
    }

    private fun prepareSponsorBlockSkip(
        snapshot: SponsorBlockVideoSnapshot?,
        segmentId: String?,
        segmentCategoryName: String?,
        startMs: Long?,
        endMs: Long,
        trigger: SponsorBlockSkipTrigger
    ): SponsorBlockSkipRecord? {
        val context = appContext ?: return null
        val capturedSnapshot = snapshot ?: return null
        val capturedSegmentId = segmentId ?: return null
        return buildSponsorBlockSkipRecord(
            snapshot = capturedSnapshot,
            segmentId = capturedSegmentId,
            segmentCategoryName = segmentCategoryName.orEmpty().ifBlank { "可跳过片段" },
            startMs = startMs ?: 0L,
            endMs = endMs,
            trigger = trigger,
            timestampMs = System.currentTimeMillis()
        )
    }

    private fun publishSponsorBlockSkip(
        submission: com.bilipai.desktop.ui.DesktopOriginalNativeSeekSubmission,
        record: SponsorBlockSkipRecord
    ) {
        environment.invocations.launch(Dispatchers.IO) {
            runCatching {
                environment.plugins.recordSponsorSkip(submission, record)
            }.onFailure { error ->
                Logger.w("PlayerVM", "记录空降助手跳过历史失败: ${error.message}")
            }
        }
    }

'''
swap(manual_original,manual_desired,'Preserve original manual metadata and mark ordering, defer only native-success history/view publication with exact ticket')
import importlib.util
dispatch_spec=importlib.util.spec_from_file_location('whole_vm_plugin_dispatch',H/'prepare-plugin-dispatch.py')
dispatch_module=importlib.util.module_from_spec(dispatch_spec)
dispatch_spec.loader.exec_module(dispatch_module)
for before,after,label in dispatch_module.plans(body):swap(before,after,label)
swap('''            recordCurrentCdnHealthEvent(CdnHealthEvent.PLAYER_ERROR)
            fallbackFromCdnRewrite(reason = "player_error")''','''            // A proven local audio-output initialization failure cannot diagnose
            // a remote video CDN or spend its generic source-recovery budget.
            if (premiumFailureCode != null) return
            recordCurrentCdnHealthEvent(CdnHealthEvent.PLAYER_ERROR)
            fallbackFromCdnRewrite(reason = "player_error")''','Typed local AO failure outside Premium fallback never poisons video CDN health')
cdn_spec=importlib.util.spec_from_file_location('whole_vm_cdn_serialization',H/'prepare-cdn-serialization.py')
cdn_module=importlib.util.module_from_spec(cdn_spec)
cdn_spec.loader.exec_module(cdn_module)
for before,after,label in cdn_module.plans(body):swap(before,after,label)
swap('''lineDiagnostics = cdnPlugin?.let { environment.plugins.buildPlaybackCdnDiagnostics(
                plugin = it,''','''lineDiagnostics = cdnPlugin?.let { environment.plugins.buildPlaybackCdnDiagnostics(
                expected = null,
                plugin = it,''','Only original immutable new-request context may use non-accepted CDN diagnostics')
swap('''            recordCurrentCdnHealthEvent(CdnHealthEvent.PLAYER_ERROR)
            fallbackFromCdnRewrite(reason = "player_error")''','''            // A proven local audio-output initialization failure cannot diagnose
            // a remote video CDN or spend its generic source-recovery budget.
            if (premiumFailureCode != null) return
            recordCurrentCdnHealthEvent(CdnHealthEvent.PLAYER_ERROR)
            fallbackFromCdnRewrite(reason = "player_error")''','Typed local AO failure outside Premium fallback never poisons video CDN health')
swap('com.android.purebilibili.core.player.PlayerVolumeController.applyPreferredVolume(p)','environment.useCase.applyPreferredVolume(p)','Actual same preferred-volume setter')
swap('com.android.purebilibili.core.util.NetworkUtils','environment.network','Required actual Windows network view')
swap('NetworkUtils.getDefaultQualityId','environment.network.getDefaultQualityId','Same original mirror/network quality algorithm')
swap('com.android.purebilibili.core.cache.PlayUrlCache','environment.cache','Sole existing raw cache view')
swap('PlayUrlCache.','environment.cache.','Sole raw cache invalidation')
swap('CdnDashSegmentPrefetcher(\n                context = context,','CdnDashSegmentPrefetcher(\n                cache = environment.cdnRangeCache,','Required real playback range-cache consumer, no fake prefetch')
old_upload='''                    val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                    val fileName = queryDisplayName(context, uri)
                        ?: "comment_${System.currentTimeMillis()}_${index + 1}.jpg"

                    // 流式上传:空/15MB 校验在 CommentRepository 内完成,不再整文件读入内存。
                    val uploadResult = environment.comments
                        .uploadCommentImage(
                            fileName = fileName,
                            mimeType = mimeType,
                            resolver = context.contentResolver,
                            uri = uri
                        )'''
swap(old_upload,'''                    // SAME original streaming provider performs filename/MIME, empty/15MiB checks;
                    // no file byte copy, HTTP client or second reply authority is created here.
                    val uploadResult = environment.comments.uploadCommentPicture(uri, index)''','Android resolver boundary delegated to already-existing original streaming picture provider')
a=body.index('    private fun queryDisplayName(');b=body.index('    // 评论发送成功事件',a)
swap(body[a:b],'','Android Cursor filename helper belongs to existing streaming provider')
last_import=max(m.end() for m in re.finditer(r'(?m)^import .+$',original))
imports=original[original.index('package '):last_import]
discard=['BackgroundManager','SettingsManager','AnalyticsHelper','CrashReporter','NetworkUtils','VideoRepository','VideoNoteRepository','ViewGrpcRepository','MiniPlayerManager','PlaylistManager','PluginManager','PlayUrlCache']
imports='\n'.join(line for line in imports.splitlines() if not line.startswith('import android.') and not line.startswith('import androidx.lifecycle.') and not line.startswith('import androidx.media3.') and not any(line.endswith('.'+n) for n in discard))
imports=imports.replace('import com.android.purebilibili.core.util.Logger','import android.util.Log as Logger')
imports+='\nimport com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player\nimport com.bilipai.desktop.ui.DesktopOriginalMpvSectionControl as ExoPlayer\nimport com.bilipai.desktop.ui.DesktopOriginalPlaybackRate as PlaybackParameters\nimport com.android.purebilibili.feature.plugin.SponsorBlockSkipRecord\nimport com.android.purebilibili.core.store.*\n'
helpers=(H/'prepared/selected/com/android/purebilibili/feature/video/viewmodel/DesktopOriginalVideoPlaybackOwnerPolicies.kt').read_text(encoding='utf-8')
helpers=helpers[helpers.index('private const val PLAYBACK_CDN'):]
start=helpers.index('internal fun buildPlaybackAudioUrlCandidates(')
end=helpers.index('internal fun buildPlaybackVideoUrlCandidates(',start)
helpers=helpers[:start]+helpers[end:]
target=H/'prepared/whole/com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt'
target.parent.mkdir(parents=True,exist_ok=True)
target.write_text(imports+'\n'+helpers+'\n'+body,encoding='utf-8',newline='\n')
inverse=body
for change in reversed(changes):
    assert hashlib.sha256(inverse.encode()).hexdigest()==change['afterStateSha256LF'],change['label']
    for position in reversed(change['afterPositions']):
        assert inverse[position:position+len(change['after'])]==change['after'],change['label']
        inverse=inverse[:position]+change['before']+inverse[position+len(change['after']):]
    assert hashlib.sha256(inverse.encode()).hexdigest()==change['beforeStateSha256LF'],change['label']
assert inverse==original[original.index('class VideoPlaybackViewModel(application: Application)'):]
(H/'whole-vm-initial-adaptations.json').write_text(json.dumps(dict(preparedOnly=True,notInstallable=True,upstreamSha256LF=hashlib.sha256(original.encode()).hexdigest(),classOriginalLines=len(inverse.splitlines()),outputSha256Bytes=hashlib.sha256(target.read_bytes()).hexdigest(),inverseClassByteEqual=True,inverseClassSha256LF=hashlib.sha256(inverse.encode()).hexdigest(),modifications=changes,scope='Declaration compile receipts are separate per run; no full owner/product runtime acceptance.',remaining=['Same captured metadata/Notes/subtitle forwarding','Streaming image boundary and download task admission/projection','Per-provider same Runtime generation dispatch and inherited native-mute lease','Actual playback range-cache consumer capability','Full VM owner factory/mount and original Holder platform assembly']),ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
print('Prepared complete original class:',len(body.splitlines()),'lines;',len(changes),'tracked platform replacements; NOT installable')
