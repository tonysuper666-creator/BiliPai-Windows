from pathlib import Path
import hashlib,importlib.util,json,re,sys
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;MAIN=P.parents[2];REPO=MAIN.parent/'BiliPai-v023'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def put(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(t.encode())
def sha(t):return hashlib.sha256(t.encode()).hexdigest()
spec=importlib.util.spec_from_file_location('mask',REPO/'desktop/tools/extract-upstream-dynamic-reply-protocol.py');mask=importlib.util.module_from_spec(spec);spec.loader.exec_module(mask)
ps=importlib.util.spec_from_file_location('parser',REPO/'desktop/tools/sync-upstream.py');parser=importlib.util.module_from_spec(ps);ps.loader.exec_module(parser)
ownerMap=json.loads((P/'settings-owner-map.json').read_text(encoding='utf-8'));rows=[]
for p in sorted(wide(P/'prepared/generated/com/android/purebilibili/feature/video/screen').glob('*.kt')):
 raw=p.read_text(encoding='utf-8');t=raw;edits=[]
 def change(a,b,label,required=False):
  global t
  if a not in t:assert not required,(p.name,label,a);return
  for at in reversed([m.start()for m in re.finditer(re.escape(a),t)]):
   edits.append(dict(at=at,before=a,after=b,label=label));t=t[:at]+b+t[at+len(a):]
 def expression(start,after,label):
  if start not in t:return
  a=t.index(start);m=mask.masked(t);op=m.index('(',a);end=mask.balanced(m,op,'(',')');change(t[a:end],after,label,True)
 # All platform views are captured in composition, never looked up from a suspend callback.
 m=mask.masked(t);inserts=[]
 for fm in re.finditer(r'@Composable\s+(?:(?:internal|private|public|inline|suspend)\s+)*fun\b',m):
  op=m.index('(',fm.end());end=mask.balanced(m,op,'(',')');brace=m.find('{',end)
  if brace>=0 and '\n' not in m[end:brace].strip() and '='not in m[end:brace]:inserts.append(brace+1)
 for at in reversed(inserts):
  insertion='\n    val holderPlatform = com.bilipai.desktop.ui.LocalDesktopOriginalVideoHolderPlatform.current'
  edits.append(dict(at=at,before='',after=insertion,label='Capture required same-entry platform for effect/callback lifecycle'));t=t[:at]+insertion+t[at:]
 # Map original facade methods to their already sole existing global-store owners.
 names=set(re.findall(r'(?:com\.android\.purebilibili\.core\.store\.)?SettingsManager\s*\.\s*(\w+)',t))
 for name in sorted(names):
  owners=ownerMap.get(name,[])
  if name in ['getClickToPlay','getClickToPlaySync']:owners=['com.android.purebilibili.core.store.DesktopOriginalPlayerSectionSettings']
  if name=='setCommentDefaultSortMode':owners=['com.android.purebilibili.core.store.DesktopOriginalVideoCommentSheetSettings']
  if len(owners)!=1:continue
  for hit in reversed(list(re.finditer(r'(?:com\.android\.purebilibili\.core\.store\.)?SettingsManager\s*\.\s*'+re.escape(name)+r'\b',t))):
   change(hit[0],owners[0]+'.'+name,'Reuse sole original setting family over actual Root global Store')
 for name in ['getCommentDefaultSortMode','getCommentDefaultSortModeSync','getCommentFraudDetectionEnabled','getCommentMemberDecorationsEnabled']:
  change('DesktopOriginalReplySettings.'+name+'(context)','DesktopOriginalReplySettings.'+name+'(context.pluginContext)','Existing common Reply settings consume the same PluginContext')
 change('androidx.compose.ui.platform.LocalContext.current','com.bilipai.desktop.ui.LocalDesktopOriginalPlayerSettingsContext.current','Actual same global settings context')
 change('com.android.purebilibili.feature.download.DownloadManager','holderPlatform.downloads','One existing Root download manager/task StateFlow; no copied cache')
 change('com.android.purebilibili.core.util.LogCollector.exportAndShare(context)','holderPlatform.exportAndShareLogs()','Required actual Root diagnostic export/share actor')
 expression('com.android.purebilibili.core.store.SettingsManager.setPlayerDiagnosticLoggingEnabled(', 'holderPlatform.setPlayerDiagnosticLoggingEnabled(enabled)', 'Required original diagnostic write and runtime cache actor') if False else None
 # Different callsites pass literal/current enabled names: retain that argument verbatim.
 for hit in reversed(list(re.finditer(r'(?:com\.android\.purebilibili\.core\.store\.)?SettingsManager\s*\.\s*(getDanmakuEnabled|getDanmakuBlockRulesRaw|setDanmakuBlockRulesRaw|setDanmakuEnabled|setPlayerDiagnosticLoggingEnabled)\s*\(',t))):
  a=hit.start();op=hit.end()-1;m=mask.masked(t);end=mask.balanced(m,op,'(',')');args=t[op+1:end-1];args=re.sub(r'^\s*(?:context\s*=\s*)?context\s*,?\s*','',args)
  n=hit[1]
  if n=='getDanmakuEnabled':after='holderPlatform.section.danmakuPreferences.getDanmakuSettings('+args+').map { it.enabled }'
  elif n=='getDanmakuBlockRulesRaw':after='holderPlatform.section.danmakuPreferences.blocks.getDanmakuBlockRulesRaw('+args+')'
  elif n=='setPlayerDiagnosticLoggingEnabled':after='holderPlatform.setPlayerDiagnosticLoggingEnabled('+args+')'
  else:after='holderPlatform.section.danmakuPreferences.'+n+'('+args+')'
  change(t[a:end],after,'Original scope/value mapped to sole real global settings and runtime owner')
 change('WindowInsets.statusBarsIgnoringVisibility\n                .asPaddingValues().calculateTopPadding().value','with(LocalDensity.current) { holderPlatform.section.statusBarInsetPixels.toDp().value }','Actual Windows client inset supplied by same Root Window')
 change('WindowInsets.statusBarsIgnoringVisibility\n                                .asPaddingValues().calculateTopPadding().value','with(LocalDensity.current) { holderPlatform.section.statusBarInsetPixels.toDp().value }','Actual Windows client inset supplied by same Root Window')
 change('import android.content.res.Configuration','import com.bilipai.desktop.ui.DesktopHomeCardWindowBounds as Configuration','Actual client viewport parameters, no fake Android resource Configuration')
 change('shouldAllowRuntimeShaderBackedHazeEffect(Build.VERSION.SDK_INT)','com.bilipai.desktop.ui.desktopDetailRenderEffectsSupported()','Actual Compose blur capability')
 change('Build.VERSION.SDK_INT >= Build.VERSION_CODES.S','com.bilipai.desktop.ui.desktopDetailRenderEffectsSupported()','Actual Compose render-effect capability')
 change('AndroidRenderEffect.createBlurEffect(\n                                                blurFrame.blurRadiusPx,\n                                                blurFrame.blurRadiusPx,\n                                                Shader.TileMode.CLAMP,\n                                            ).asComposeRenderEffect()','androidx.compose.ui.graphics.BlurEffect(radiusX = blurFrame.blurRadiusPx, radiusY = blurFrame.blurRadiusPx)','Preserve original blur radius using actual Skia Compose effect')
 change('LaunchedEffect(commentViewModel, context.applicationContext)','LaunchedEffect(commentViewModel, platform)','Same owned common-comment platform lifetime')
 change('navigateToRelatedVideo: (String, android.os.Bundle?) -> Unit','navigateToRelatedVideo: (String, Long, String?) -> Unit','Explicit CID/cover original navigation fields')
 change('onRelatedVideoClick: (String, android.os.Bundle?) -> Unit','onRelatedVideoClick: (String, Long, String?) -> Unit','Explicit CID/cover original navigation fields')
 change('onVideoClick: (String, android.os.Bundle?) -> Unit','onVideoClick: (String, Long, String?) -> Unit','Explicit CID/cover original navigation fields')
 change('onRelatedVideoClick ?: { _, _ -> }','onRelatedVideoClick ?: { _, _, _ -> }','Original optional fallback has the actual typed callback arity')
 change('val danmakuManager = rememberDanmakuManager(success.info.bvid)','val danmakuManager = holderPlatform.danmaku','Same source document, no another manager/parser/session')
 change('com.android.purebilibili.core.store.DesktopOriginalVideoCommentSheetSettings.setCommentDefaultSortMode(context,','com.android.purebilibili.core.store.DesktopOriginalVideoCommentSheetSettings.setCommentDefaultSortMode(context.pluginContext,','Same Root PluginContext consumed by canonical comment sort write')
 if p.name=='VideoDetailPlayerSettingsOverlayAdapter.kt':
  start='    LaunchedEffect(danmakuManager, viewModel) {'
  if start in t:
   a=t.index(start);op=t.index('{',a);end=mask.balanced(mask.masked(t),op,'{','}')
   original=t[a:end];body=original[original.index('danmakuManager.setOnDanmakuClickListener')+len('danmakuManager.setOnDanmakuClickListener'):];body=body[:body.rfind('\n    }')]
   replacement='''    androidx.compose.runtime.DisposableEffect(danmakuManager, viewModel, holderPlatform) {
        val clickLease = holderPlatform.acquireDanmakuClickListener'''+body+'''
        onDispose { clickLease.close() }
    }'''
   change(original,replacement,'Same source-specific native document click registration, release only this entry lease')
 if p.name=='VideoDetailScreenContent.kt':change('onVideoClick: (String, android.os.Bundle?) -> Unit','onVideoClick: (String, Long, String?) -> Unit','Root retains explicit CID and cover navigation')
 if p.name=='VideoDetailScreenStateHolder.kt':
  change('cid = resolvedCid, coverUrl = targetCover','targetCid = resolvedCid, coverUrl = targetCover','Exact sole navigation helper parameter name')
  change('navigateToRelatedVideo(targetVideoId, null)','navigateToRelatedVideo(targetVideoId, 0L, null)','Original absent CID/cover navigation semantics')
  change('navigateToRelatedVideo(target.videoId, null)','navigateToRelatedVideo(target.videoId, 0L, null)','Original absent CID/cover navigation semantics')
  change('navigateRelatedVideo(targetBvid, null)','navigateRelatedVideo(targetBvid, 0L, null)','Original omitted CID/cover semantics')
  change('onVideoClick(playlistItem.bvid, null)','onVideoClick(playlistItem.bvid, 0L, null)','Original omitted CID/cover semantics')
  change('context.findActivity()','platform.window.presentation','Same actual Root Window presentation owner')
  change('AppScreenshotGestureBlockState.fullscreenPlayerLocked','platform.fullscreenPlayerLocked','Same Root fullscreen lock projection used by Section, no global singleton state')
  change('com.android.purebilibili.core.store.FavoriteInteractionSettingsStore\n        .getQuickSaveDefaultFolder(context)','platform.favoritePreferences.getQuickSaveDefaultFolder()','Same canonical Favorites global settings')
  change('VideoDetailCommentFraudOverlayAdapter(\n        context = context,','VideoDetailCommentFraudOverlayAdapter(\n        platform = platform.commentsPlatform,','Original full Playback VM overload and existing Comment VM own the sent/fraud events')
  change('    val context = platform.settingsContext','    val context = platform.settingsContext\n    val coilContext = coil3.compose.LocalPlatformContext.current','Capture actual Coil Windows context before remember calculation')
  change('coil3.request.ImageRequest.Builder(context)','coil3.request.ImageRequest.Builder(coilContext)','Actual Coil Windows context preserves exact URL/cache keys')
  change('com.android.purebilibili.core.player.PlayerVolumeController\n                     .applyPreferredVolume(playerState.player)','platform.applyPreferredVolume(playerState.player)','Same native player preferred volume owner')
  start='                        val collapsedSystemBarInset = remember(view, configuration.densityDpi) {'
  if start in t:
   a=t.index(start);op=t.index('{',a);end=mask.balanced(mask.masked(t),op,'{','}')
   change(t[a:end],'''                        val collapsedSystemBarInset = with(insetDensity) {
                            platform.section.statusBarInsetPixels.toDp()
                        }''','Actual stable Root client inset; no Android resource fallback or guessed status bar')
  start='                                                val vipPlaybackCandidates = remember(errorState.error) {'
  if start in t:
   a=t.index(start);op=t.index('{',a);end=mask.balanced(mask.masked(t),op,'{','}');original=t[a:end]
   body=original[original.index('val currentPlaybackMid'):];body=body[:body.rfind('\n                                                }')]
   body=body.replace('com.android.purebilibili.core.network.NetworkModule\n                                                        .playbackAccount()?.mid','platform.accounts.getPlaybackAccountMid() ?: platform.accounts.getActiveAccountMid()')
   body=body.replace('com.android.purebilibili.core.store.AccountSessionStore\n                                                        .getAccounts(context)','platform.accounts.getAccounts()')
   body=body.replace('platform.accounts.getAccounts()','val candidates = platform.accounts.getAccounts()',1)
   replacement='''                                                val vipPlaybackCandidates by androidx.compose.runtime.produceState(
                                                    initialValue = emptyList<com.android.purebilibili.core.store.StoredAccountSession>(),
                                                    key1 = errorState.error, key2 = platform.accounts,
                                                ) {
                                                    '''+body+'''
                                                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                                    if (platform.isCurrent()) value = candidates
                                                }'''
   change(original,replacement,'Same owned encrypted Store account query; original VIP/credential/current-MID filters intact')
  original='''                                                            com.android.purebilibili.core.store.AccountSessionStore
                                                                .setPlaybackAccountMid(context, vipCandidate.mid)
                                                            android.widget.Toast.makeText(
                                                                context,
                                                                "已使用「${vipCandidate.name.ifBlank { "UID ${vipCandidate.mid}" }}」的大会员播放",
                                                                android.widget.Toast.LENGTH_SHORT
                                                            ).show()
                                                            viewModel.retry()'''
  replacement='''                                                            scope.launch {
                                                                if (platform.accounts.setPlaybackAccountMid(vipCandidate.mid)) {
                                                                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                                                    if (platform.isCurrent()) {
                                                                        platform.showFeedback("已使用「${vipCandidate.name.ifBlank { "UID ${vipCandidate.mid}" }}」的大会员播放")
                                                                        viewModel.retry()
                                                                    }
                                                                }
                                                            }'''
  change(original,replacement,'Same owned playback selection install; no stale UI receipt/retry after retirement')
  original='''isActivityInMultiWindowOrFloatingMode(
                        activity = hostActivity,
                        displayContext = displayContext,
                    )'''
  change(original,'hostActivity.isInMultiWindowMode','Actual Root Window capability')
 for hit in reversed(list(re.finditer(r'WindowInsets\.statusBarsIgnoringVisibility\s*\.asPaddingValues\(\)\s*\.calculateTopPadding\(\)\s*\.value',t))):
  change(hit[0],'with(LocalDensity.current) { holderPlatform.section.statusBarInsetPixels.toDp().value }','Actual stable Window client inset')
 change('import io.github.alexzhirkevich.cupertino.icons.outlined.*\n','','Unused Android-only icon namespace; original UI already uses sole AppIcons')
 # Remove only lexically unused imports; operator extensions are implicit language calls.
 body=re.sub(r'^import .*\n','',t,flags=re.M);tokens={x[0]for x in parser.kotlin_tokens(body)}
 for line in t.splitlines(True):
  if not line.startswith('import '):continue
  clean=line.split('//')[0].strip();alias=clean.split(' as ')[-1]if' as 'in clean else clean.split('.')[-1]
  if alias not in tokens and not re.search(r'\b'+re.escape(alias)+r'\b',body) and alias not in {'*','getValue','setValue','provideDelegate','getAccounts'}:
   change(line,'','Lexically unused import after tracked Windows platform substitutions')
 if '.map {'in t and 'import kotlinx.coroutines.flow.map\n'not in t:change('package com.android.purebilibili.feature.video.screen\n','package com.android.purebilibili.feature.video.screen\nimport kotlinx.coroutines.flow.map\n','Flow projection of actual original settings')
 if '.ensureActive()'in t and 'import kotlinx.coroutines.ensureActive\n'not in t:change('package com.android.purebilibili.feature.video.screen\n','package com.android.purebilibili.feature.video.screen\nimport kotlinx.coroutines.ensureActive\n','Actual caller coroutine cancellation before UI receipt')
 replay=raw
 for e in edits:assert replay[e['at']:e['at']+len(e['before'])]==e['before'];replay=replay[:e['at']]+e['after']+replay[e['at']+len(e['before']):]
 assert replay==t
 inverse=t
 for e in reversed(edits):assert inverse[e['at']:e['at']+len(e['after'])]==e['after'];inverse=inverse[:e['at']]+e['before']+inverse[e['at']+len(e['after']):]
 assert inverse==raw
 put(p,t);rows.append(dict(output=str(p.relative_to(wide(P/'prepared/generated'))).replace('\\','/'),beforeSHA256LF=sha(raw),afterSHA256LF=sha(t),exactInverse=True,edits=edits))
put(P/'followup-source-audit.json',json.dumps(dict(passed=True,rows=rows,productRuntimeAccepted=False),ensure_ascii=False,indent=2)+'\n')
print('Complete tracked followups',len(rows))
