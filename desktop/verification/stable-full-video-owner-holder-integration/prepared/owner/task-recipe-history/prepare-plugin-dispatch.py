"""Plan exact full-method platform edits on the already-adapted original class.
Caller records each before/after offset so all original bodies remain invertible.
Only actual accepted publication objects travel across suspend waits.
"""
from pathlib import Path
import importlib.util
H=Path(__file__).resolve().parent;R=H.parents[2].parent/'BiliPai-v023'
def module(n,p):
 s=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
sel=module('plugin_dispatch_select',R/'desktop/tools/extract-upstream-video-detail-full-units.py')
sel.parser=module('plugin_dispatch_tokens',R/'desktop/tools/sync-upstream.py')
def plans(body):
 output=[]
 def edit(name,callback):
  a,b=sel.function_range(body,name);before=body[a:b];after=callback(before)
  assert after!=before,name
  output.append((before,after,'Same accepted publication captured before suspend: '+name))
 def replace(t,a,b):
  assert t.count(a)==1,(a,t.count(a));return t.replace(a,b,1)
 def loop(t):
  t=replace(t,'                val plugins = getSessionPlayerPlugins()','''                val expected = environment.plugins.capturePlaybackDispatch()
                if (expected == null) {
                    delay(resolvePluginPollingIntervalMs(hasPlugins = false, isPlaying = false))
                    continue
                }
                val plugins = getSessionPlayerPlugins().toList()''')
  t=replace(t,'environment.plugins.onSponsorDisabled()','environment.plugins.onSponsorDisabled(expected)\n                    if (!environment.plugins.isPlaybackDispatchCurrent(expected)) continue')
  t=replace(t,'environment.plugins.ensureSponsorLoaded(bvid, cid)','environment.plugins.ensureSponsorLoaded(expected, bvid, cid)\n                            if (!environment.plugins.isPlaybackDispatchCurrent(expected)) continue')
  t=replace(t,'                delay(intervalMs)\n                if (plugins.isEmpty()) continue','                delay(intervalMs)\n                if (!environment.plugins.isPlaybackDispatchCurrent(expected)) continue\n                if (plugins.isEmpty()) continue')
  t=replace(t,'                        when (val action = environment.plugins.onPositionUpdate(plugin, currentPos)) {','''                        val action = environment.plugins.onPositionUpdate(expected, plugin, currentPos)
                        if (!environment.plugins.isPlaybackDispatchCurrent(expected)) continue
                        when (action) {''')
  t=replace(t,'                                    playbackUseCase.seekTo(\n                                        position = resolvedTargetPositionMs,','''                                    environment.plugins.admitPlaybackDispatch(expected) {
                                        playbackUseCase.seekTo(
                                        position = resolvedTargetPositionMs,''')
  t=replace(t,'''                                        )
                                    )
                                }
                                recordSponsorBlockSkip(''','''                                        )
                                        )
                                    }
                                }
                                recordSponsorBlockSkip(''')
  return replace(t,'                                    submission = submission,','                                    expected = expected,\n                                    submission = submission,')
 edit('startPluginCheck',loop)
 def dismiss(t):
  t=replace(t,'        getSessionPlayerPlugins().forEach { plugin ->','''        val expected = environment.plugins.capturePlaybackDispatch() ?: return
        val segmentId = _sponsorSkipUiState.value.segmentId
        val capturedPlugins = getSessionPlayerPlugins().toList()
        environment.invocations.launch {
            if (!environment.plugins.isPlaybackDispatchCurrent(expected)) return@launch
        capturedPlugins.forEach { plugin ->''')
  t=replace(t,'environment.plugins.markSponsorSkipped(plugin,_sponsorSkipUiState.value.segmentId ?: return@forEach)','environment.plugins.markSponsorSkipped(expected,plugin,segmentId ?: return@forEach)\n                if (!environment.plugins.isPlaybackDispatchCurrent(expected)) return@launch')
  return replace(t,'        clearSponsorSkipUi()\n    }','        if (environment.plugins.isPlaybackDispatchCurrent(expected)) clearSponsorSkipUi()\n        }\n    }')
 edit('dismissSponsorSkipButton',dismiss)
 def manual(t):
  t=replace(t,'        var segmentCategory: String? = null','''        val expected = environment.plugins.capturePlaybackDispatch() ?: return
        val capturedPlugins = getSessionPlayerPlugins().toList()
        environment.invocations.launch {
            if (!environment.plugins.isPlaybackDispatchCurrent(expected)) return@launch
        var segmentCategory: String? = null''')
  t=replace(t,'getSessionPlayerPlugins().forEach { plugin ->','capturedPlugins.forEach { plugin ->')
  t=replace(t,'environment.plugins.markSponsorSkipped(plugin,segmentId)','environment.plugins.markSponsorSkipped(expected,plugin,segmentId)\n                if (!environment.plugins.isPlaybackDispatchCurrent(expected)) return@launch')
  t=replace(t,'            playbackUseCase.seekTo(\n                position = resolvedTargetPosition,','''            environment.plugins.admitPlaybackDispatch(expected) {
                playbackUseCase.seekTo(
                position = resolvedTargetPosition,''')
  t=replace(t,'''                )
            )
        }
        if (submission != null) pendingRecords.forEach { publishSponsorBlockSkip(submission, it) }
        clearSponsorSkipUi()''','''                )
                )
            }
        }
        if (submission != null) pendingRecords.forEach { publishSponsorBlockSkip(expected, submission, it) }
        if (environment.plugins.isPlaybackDispatchCurrent(expected)) clearSponsorSkipUi()
        }''')
  return t
 edit('skipCurrentSponsorSegment',manual)
 edit('recordSponsorBlockSkip',lambda t:replace(replace(t,'        submission: com.bilipai.desktop.ui.DesktopOriginalNativeSeekSubmission?,','        expected: com.bilipai.desktop.ui.DesktopOriginalVideoAcceptedPublication,\n        submission: com.bilipai.desktop.ui.DesktopOriginalNativeSeekSubmission?,'),'publishSponsorBlockSkip(ticket, record)','publishSponsorBlockSkip(expected, ticket, record)'))
 edit('publishSponsorBlockSkip',lambda t:replace(replace(t,'        submission: com.bilipai.desktop.ui.DesktopOriginalNativeSeekSubmission,','        expected: com.bilipai.desktop.ui.DesktopOriginalVideoAcceptedPublication,\n        submission: com.bilipai.desktop.ui.DesktopOriginalNativeSeekSubmission,'),'environment.plugins.recordSponsorSkip(submission, record)','environment.plugins.recordSponsorSkip(expected, submission, record)'))
 def notify(t):
  t=replace(t,'        getSessionPlayerPlugins().forEach { plugin ->','''        val expected = environment.plugins.capturePlaybackDispatch() ?: return
        val capturedPlugins = getSessionPlayerPlugins().toList()
        environment.invocations.launch {
            if (!environment.plugins.isPlaybackDispatchCurrent(expected)) return@launch
        capturedPlugins.forEach { plugin ->''')
  t=replace(t,'environment.plugins.onUserSeek(plugin, positionMs)','environment.plugins.onUserSeek(expected, plugin, positionMs)\n            if (!environment.plugins.isPlaybackDispatchCurrent(expected)) return@launch')
  at=t.rfind('    }');t=t[:at]+'        }\n'+t[at:];return t
 edit('notifyPluginsOfExplicitSeek',notify)
 def vote(t):
  t=replace(t,'        val segmentId =','        val expected = environment.plugins.capturePlaybackDispatch() ?: return\n        val segmentId =')
  t=replace(t,'environment.plugins.voteSponsorSegment(plugin, segmentId, voteType)','environment.plugins.voteSponsorSegment(expected, plugin, segmentId, voteType)')
  t=replace(t,'.onSuccess { toast("已提交社区投票") }','.onSuccess { if (environment.plugins.isPlaybackDispatchCurrent(expected)) toast("已提交社区投票") }')
  t=replace(t,'.onFailure { error -> toast(error.message ?: "社区投票失败") }','.onFailure { error -> if (environment.plugins.isPlaybackDispatchCurrent(expected)) toast(error.message ?: "社区投票失败") }');return t
 edit('voteCurrentSponsorSegment',vote)
 def submit(t):
  t=replace(t,'        val current = _sponsorContributionUiState.value','        val expected = environment.plugins.capturePlaybackDispatch() ?: return\n        val current = _sponsorContributionUiState.value')
  t=replace(t,'environment.plugins.submitSponsorSegment(\n                plugin','environment.plugins.submitSponsorSegment(\n                expected = expected,\n                plugin')
  t=replace(t,').onSuccess {\n                _sponsorContributionUiState',').onSuccess {\n                if (!environment.plugins.isPlaybackDispatchCurrent(expected)) return@onSuccess\n                _sponsorContributionUiState')
  t=replace(t,'}.onFailure { error ->\n                _sponsorContributionUiState','}.onFailure { error ->\n                if (!environment.plugins.isPlaybackDispatchCurrent(expected)) return@onFailure\n                _sponsorContributionUiState');return t
 edit('submitSponsorContribution',submit)
 def prefetch(t):
  t=replace(t,'        playbackCdnPrefetchJob?.cancel()','        val expected = environment.plugins.capturePlaybackDispatch() ?: return\n        playbackCdnPrefetchJob?.cancel()')
  return replace(t,'environment.plugins.isAdaptivePrefetchEnabled(plugin)','environment.plugins.isAdaptivePrefetchEnabled(expected, plugin)')
 edit('scheduleCdnDashPrefetch',prefetch)
 def health(t):
  t=replace(t,'        val current =','        val expected = environment.plugins.capturePlaybackDispatch() ?: return\n        val current =')
  t=replace(t,'environment.plugins.recordPlaybackCdnEvent(plugin, current.playUrl, event)','environment.plugins.recordPlaybackCdnEvent(expected, plugin, current.playUrl, event)')
  t=replace(t,'environment.plugins.buildPlaybackCdnDiagnostics(plugin = plugin,','environment.plugins.buildPlaybackCdnDiagnostics(expected = expected, plugin = plugin,')
  return replace(t,'        if (diagnostics.isNotEmpty()) {','        if (diagnostics.isNotEmpty() && environment.plugins.isPlaybackDispatchCurrent(expected)) {')
 edit('recordCurrentCdnHealthEvent',health)
 def probe(t):
  t=replace(t,'        val current =','        val expected = environment.plugins.capturePlaybackDispatch() ?: return\n        val current =')
  t=replace(t,'environment.plugins.probePlaybackCdnCandidates(plugin = plugin,','environment.plugins.probePlaybackCdnCandidates(expected = expected, plugin = plugin,')
  t=replace(t,'            _uiState.update { state ->','            if (!environment.plugins.isPlaybackDispatchCurrent(expected)) return@launch\n            _uiState.update { state ->')
  return replace(t,'                if (state is VideoPlaybackUiState.Success) {','                if (state is VideoPlaybackUiState.Success && state.info.bvid == current.info.bvid && state.info.cid == current.info.cid && state.playUrl == current.playUrl) {')
 edit('probeCurrentCdnCandidates',probe)
 return output
