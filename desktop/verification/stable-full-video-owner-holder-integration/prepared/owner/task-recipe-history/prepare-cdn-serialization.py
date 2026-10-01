"""Exact coroutine adapters for the original CDN mutation/read sequence.
The complete before/after method blocks are recorded by prepare-whole-vm.swap.
No background Runtime mutation pretends to be a synchronous completed effect.
"""
from pathlib import Path
import importlib.util
H=Path(__file__).resolve().parent;R=H.parents[2].parent/'BiliPai-v023'
def module(n,p):
 s=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
sel=module('cdn_serial_select',R/'desktop/tools/extract-upstream-video-detail-full-units.py')
sel.parser=module('cdn_serial_tokens',R/'desktop/tools/sync-upstream.py')
def plans(body):
 output=[]
 def replace(t,a,b):
  assert t.count(a)==1,(a,t.count(a));return t.replace(a,b,1)
 def edit(name,callback):
  selectable=body.replace('override fun','private  fun') if name.startswith('on') else body
  assert len(selectable)==len(body)
  a,b=sel.function_range(selectable,name);before=body[a:b];after=callback(before)
  assert before!=after,name;output.append((before,after,'Await same Runtime CDN mutex outside native callback admission: '+name))
 for name in ['resolvePlaybackCdnCandidateSelection','applyHdrUpgrade']:
  edit(name,lambda t,n=name:replace(t,'private fun '+n+'(','private suspend fun '+n+'('))
 def health(t):
  t=replace(t,'private fun recordCurrentCdnHealthEvent(event: CdnHealthEvent) {','''private suspend fun recordCurrentCdnHealthEvent(
        event: CdnHealthEvent,
        expected: com.bilipai.desktop.ui.DesktopOriginalVideoAcceptedPublication
    ) {''')
  t=replace(t,'        val expected = environment.plugins.capturePlaybackDispatch() ?: return\n','        if (!environment.plugins.isPlaybackDispatchCurrent(expected)) return\n')
  return replace(t,'            _uiState.value = current.copy(cdnLineDiagnostics = diagnostics)','''            _uiState.update { latest ->
                if (latest is VideoPlaybackUiState.Success && latest.info.bvid == current.info.bvid &&
                    latest.info.cid == current.info.cid && latest.playUrl == current.playUrl)
                    latest.copy(cdnLineDiagnostics = diagnostics) else latest
            }''')
 edit('recordCurrentCdnHealthEvent',health)
 def ready(t):
  t=replace(t,'private fun markPlaybackCdnReadyIfMediaReady() {','''private suspend fun markPlaybackCdnReadyIfMediaReady(
        expected: com.bilipai.desktop.ui.DesktopOriginalVideoAcceptedPublication
    ) {
        if (!environment.plugins.isPlaybackDispatchCurrent(expected)) return''')
  return replace(t,'recordCurrentCdnHealthEvent(CdnHealthEvent.AUDIO_TRACK_MISSING)','recordCurrentCdnHealthEvent(CdnHealthEvent.AUDIO_TRACK_MISSING, expected)')
 edit('markPlaybackCdnReadyIfMediaReady',ready)
 def fallback(t):
  t=replace(t,'private fun fallbackFromCdnRewrite(reason: String) {','''private suspend fun fallbackFromCdnRewrite(
        reason: String,
        expected: com.bilipai.desktop.ui.DesktopOriginalVideoAcceptedPublication =
            environment.plugins.capturePlaybackDispatch() ?: return
    ) {''')
  # Kotlin does not permit a return in a default expression. Capture inside the
  # synchronous coroutine call; native callbacks pass the earlier immutable ref.
  t=replace(t,'''expected: com.bilipai.desktop.ui.DesktopOriginalVideoAcceptedPublication =
            environment.plugins.capturePlaybackDispatch() ?: return''','''expectedSource: com.bilipai.desktop.ui.DesktopOriginalVideoAcceptedPublication? = null''')
  t=replace(t,'        val state = playbackCdnFallbackState','''        val expected = expectedSource ?: environment.plugins.capturePlaybackDispatch() ?: return
        if (!environment.plugins.isPlaybackDispatchCurrent(expected)) return
        val state = playbackCdnFallbackState''')
  return replace(t,'        recordCurrentCdnHealthEvent(event)','''        recordCurrentCdnHealthEvent(event, expected)
        if (!environment.plugins.isPlaybackDispatchCurrent(expected)) return''')
 edit('fallbackFromCdnRewrite',fallback)
 def schedule(t):
  t=replace(t,'        if (!environment.plugins.isAdaptivePrefetchEnabled(expected, plugin)) return\n','')
  return replace(t,'        playbackCdnPrefetchJob = environment.invocations.launch {','''        playbackCdnPrefetchJob = environment.invocations.launch {
            if (!environment.plugins.isAdaptivePrefetchEnabled(expected, plugin)) return@launch
            if (!environment.plugins.isPlaybackDispatchCurrent(expected)) return@launch''')
 edit('scheduleCdnDashPrefetch',schedule)
 def state(t):
  t=replace(t,'''                markPlaybackCdnReadyIfMediaReady()
                recordCurrentCdnHealthEvent(CdnHealthEvent.PLAYBACK_READY)
                scheduleCdnDashPrefetch()''','''                val expected = environment.plugins.capturePlaybackDispatch()
                if (expected != null) environment.invocations.launch {
                    markPlaybackCdnReadyIfMediaReady(expected)
                    recordCurrentCdnHealthEvent(CdnHealthEvent.PLAYBACK_READY, expected)
                    if (environment.plugins.isPlaybackDispatchCurrent(expected)) scheduleCdnDashPrefetch()
                }''')
  return replace(t,'                recordCurrentCdnHealthEvent(CdnHealthEvent.BUFFERING)','''                val expected = environment.plugins.capturePlaybackDispatch()
                if (expected != null) environment.invocations.launch {
                    recordCurrentCdnHealthEvent(CdnHealthEvent.BUFFERING, expected)
                }''')
 edit('onPlaybackStateChanged',state)
 edit('onTracksChanged',lambda t:replace(t,'            markPlaybackCdnReadyIfMediaReady()','''            val expected = environment.plugins.capturePlaybackDispatch() ?: return
            environment.invocations.launch { markPlaybackCdnReadyIfMediaReady(expected) }'''))
 edit('onPlayerError',lambda t:replace(t,'''            recordCurrentCdnHealthEvent(CdnHealthEvent.PLAYER_ERROR)
            fallbackFromCdnRewrite(reason = "player_error")''','''            val expected = environment.plugins.capturePlaybackDispatch() ?: return
            environment.invocations.launch {
                recordCurrentCdnHealthEvent(CdnHealthEvent.PLAYER_ERROR, expected)
                if (environment.plugins.isPlaybackDispatchCurrent(expected))
                    fallbackFromCdnRewrite(reason = "player_error", expectedSource = expected)
            }'''))
 edit('switchCdn',lambda t:replace(t,'            fallbackFromCdnRewrite(reason = "player_error")','''            val expected = environment.plugins.capturePlaybackDispatch() ?: return
            environment.invocations.launch {
                fallbackFromCdnRewrite(reason = "player_error", expectedSource = expected)
            }'''))
 return output
