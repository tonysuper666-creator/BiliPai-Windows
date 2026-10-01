from pathlib import Path
import hashlib,importlib.util,json,re,subprocess,sys,zipfile
sys.dont_write_bytecode=True
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589';BASE='app/src/main/java/com/android/purebilibili/'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def write(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(t.encode()if isinstance(t,str)else t)
def sha(t):return hashlib.sha256(t.encode()if isinstance(t,str)else t).hexdigest()
def save(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def module(name,path):
 spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
parser=module('controls_tokens',REPO/'desktop/tools/sync-upstream.py');selector=module('controls_decls',REPO/'desktop/tools/extract-appearance-platform.py')
full=module('frozen_stage1_extractor',MAIN/'desktop/.local/stable-video-detail-full-ui-parity/prepare.py')
SOURCES={};OUTPUTS=[];ADAPT=[];REFERENCES=[]
with zipfile.ZipFile(MAIN/'desktop/.local/stable-product-snapshot-47/main-kotlin.jar')as z:ACTUAL=set(z.namelist())
def read(rel):
 path=BASE+rel
 if path not in SOURCES:
  raw=subprocess.check_output(['git','show',COMMIT+':'+path],cwd=REPO).replace(b'\r\n',b'\n')
  SOURCES[path]=dict(text=raw.decode(),sha256LF=sha(raw),gitBlob=subprocess.check_output(['git','rev-parse',COMMIT+':'+path],cwd=REPO,text=True).strip())
  write(LANE/'original-stable'/path,raw)
 return SOURCES[path]['text']
def emit(rel,t,origin,mode):
 write(LANE/'generated/com/android/purebilibili'/rel,t);OUTPUTS.append(dict(path='com/android/purebilibili/'+rel,origin=BASE+origin,sha256LF=sha(t),mode=mode,lines=len(t.splitlines())))
def adapt(t,before,after,label):
 assert t.count(before)==1,(label,t.count(before));ADAPT.append(dict(label=label,before=before,after=after));return t.replace(before,after,1)
def full_direct(rel,className=None):
 t=read(rel)
 if className and any(e.rsplit('/',1)[-1]==className+'.class'for e in ACTUAL):
  REFERENCES.append(dict(source=BASE+rel,existingClass=className,mode='sole-existing-reference'));return
 emit(rel,t,rel,'direct-complete-original')
def member_closure(source,seeds):
 tokens=parser.kotlin_tokens(source);depth=parens=brackets=0;starts=[]
 for i,(word,a,b)in enumerate(tokens):
  if depth==parens==brackets==0 and word in ['fun','val','var','class','object','interface']:
   name=tokens[i+1][0];line=source.rfind('\n',0,a)+1
   while line>0:
    prior=source.rfind('\n',0,line-1)+1
    if source[prior:line].strip().startswith('@'):line=prior
    else:break
   starts.append((name,line))
  depth+=(word=='{')-(word=='}');parens+=(word=='(')-(word==')');brackets+=(word=='[')-(word==']')
 chunks={name:source[a:starts[i+1][1]if i+1<len(starts)else len(source)]for i,(name,a)in enumerate(starts)}
 duplicates={name for name,_ in starts if sum(n==name for n,_ in starts)>1}
 assert not set(seeds)&duplicates,(set(seeds)&duplicates)
 chosen=set(seeds);assert chosen<=chunks.keys(),chosen-chunks.keys()
 while True:
  more={word for name in chosen for word,_,_ in parser.kotlin_tokens(chunks[name])if word in chunks and word not in duplicates}
  if more<=chosen:break
  chosen|=more
 return '\n'.join(chunks[name]for name,_ in starts if name in chosen),sorted(chosen)
def main():
 rel='feature/video/ui/overlay/BottomControlBar.kt';t=read(rel)
 t=t.replace('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration')
 t=t.replace('import androidx.compose.ui.semantics.testTagsAsResourceId\n','')
 t=t.replace('.semantics { testTagsAsResourceId = true }','.semantics { }')
 t=re.sub(r'(?m)^\s*decorFitsSystemWindows = false,?\s*\n','',t)
 t=t.replace('import com.android.purebilibili.core.plugin.skin.UiSkinAnimatedAsset','import com.bilipai.desktop.ui.DesktopOriginalPlayerSkinAsset as UiSkinAnimatedAsset')
 emit(rel,t,rel,'complete-original-bottom-controls-and-menus-platform-adapt')
 rel='feature/video/ui/overlay/TopControlBar.kt';t=read(rel)
 for name in ['android.content.Context','android.content.Intent','android.content.IntentFilter','android.os.BatteryManager','androidx.compose.ui.platform.LocalContext']:
  t=t.replace('import '+name+'\n','')
 t=t.replace('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration')
 t+='\n'
 t=t.replace('val context = LocalContext.current','val context = LocalDesktopOriginalPlayerControlsPlatform.current')
 t=t.replace('private fun resolveBatteryLevelPercent(context: Context)','private fun resolveBatteryLevelPercent(context: DesktopOriginalPlayerControlsPlatform)')
 a,b=full.function_range(t,'resolveBatteryLevelPercent')
 old=t[a:b];new='''private fun resolveBatteryLevelPercent(context: DesktopOriginalPlayerControlsPlatform): Int? {
    return context.readBatteryPercent()
}'''
 ADAPT.append(dict(label='Android sticky battery broadcast maps required actual Windows power query',before=old,after=new));t=t[:a]+new+t[b:]
 t=t.replace('import com.android.purebilibili.core.ui.components.AppText\n','import com.android.purebilibili.core.ui.components.AppText\nimport com.bilipai.desktop.ui.DesktopOriginalPlayerControlsPlatform\nimport com.bilipai.desktop.ui.LocalDesktopOriginalPlayerControlsPlatform\n',1)
 emit(rel,t,rel,'complete-original-top-controls-status-platform-adapt')
 for rel,model in [('feature/video/ui/overlay/BottomControlBarLayoutPolicy.kt','BottomControlBarLayoutPolicy'),('feature/video/ui/overlay/TopControlBarLayoutPolicy.kt','TopControlBarLayoutPolicy'),('feature/video/ui/overlay/SubtitleControlModel.kt','SubtitleControlUiState'),('feature/video/subtitle/BiliSubtitlePolicy.kt','SubtitleTrackOption'),('feature/video/progress/PbpProgressPolicy.kt','PbpRidgeSample'),('feature/anime4k/Anime4KConfig.kt','Anime4KConfig'),('feature/video/ui/components/NativeDanmakuToggleButton.kt','NativeDanmakuToggleButtonKt')]:
  full_direct(rel,model)
 rel='core/store/SettingsManager.kt';t=read(rel)
 if 'com/android/purebilibili/core/store/PlayerProgressPlacement.class'not in ACTUAL:
  emit('core/store/DesktopOriginalPlayerProgressPlacement.kt','package com.android.purebilibili.core.store\n'+selector.declarations(parser,t,['PlayerProgressPlacement'])+'\n',rel,'complete-original-enum')
 emit('core/store/DesktopOriginalPlayerProgressBehaviorModels.kt','package com.android.purebilibili.core.store\n'+selector.declarations(parser,t,['BottomProgressBehavior','PlaybackCompletionBehavior'])+'\n',rel,'complete-original-progress-completion-enums')
 rel='feature/video/ui/components/VideoAspectRatio.kt';t=read(rel)
 t=t.replace('import androidx.media3.ui.AspectRatioFrameLayout','import com.bilipai.desktop.ui.DesktopOriginalMediaResizeModes as AspectRatioFrameLayout')
 t=t.replace('@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)\n','')
 t=t.replace('androidx.media3.ui.PlayerView','com.bilipai.desktop.ui.DesktopOriginalPlayerViewportPort')
 t=adapt(t,'    playerView.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_content_frame)\n        ?.requestLayout()','    playerView.requestContentLayout()','PlayerView internal content remeasure uses same Windows native surface viewport port')
 t=t.replace('playerView.postOnAnimation {','playerView.postOnFrame {')
 emit(rel,t,rel,'complete-original-aspect-and-menu-platform-integer-adapt')
 rel='feature/video/ui/components/SeekPreviewBubble.kt';t=read(rel)
 t=t.replace('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration').replace('import androidx.compose.ui.platform.LocalContext','import coil3.compose.LocalPlatformContext as LocalContext')
 t=t.replace('import androidx.compose.ui.graphics.asImageBitmap','import androidx.compose.ui.graphics.toComposeImageBitmap').replace('bitmap.asImageBitmap()','bitmap.toComposeImageBitmap()')
 t=t.replace('import androidx.compose.runtime.remember\n','import androidx.compose.runtime.remember\nimport androidx.compose.runtime.DisposableEffect\n')
 t=adapt(t,'''        is AsyncImagePainter.State.Success -> {
            Canvas(modifier = modifier) {
                val bitmap = (painterState.result.image as? coil3.BitmapImage)?.bitmap ?: return@Canvas''','''        is AsyncImagePainter.State.Success -> {
            val bitmap = (painterState.result.image as? coil3.BitmapImage)?.bitmap
            val spriteImage = remember(bitmap) { bitmap?.let(org.jetbrains.skia.Image::makeFromBitmap) }
            DisposableEffect(spriteImage) { onDispose { spriteImage?.close() } }
            Canvas(modifier = modifier) {
                val bitmap = bitmap ?: return@Canvas
                val spriteImage = spriteImage ?: return@Canvas''','Coil Skia bitmap converted to an owned Image; never close Coil shared bitmap')
 t=t.replace('image = bitmap.toComposeImageBitmap(),','image = spriteImage.toComposeImageBitmap(),')
 emit(rel,t,rel,'complete-original-seek-preview-platform-context-adapt')
 rel='feature/anime4k/gl/Anime4KRenderConfig.kt'
 # The native GL source stays reference-only; only its original pure display enum is needed by the menu.
 paths=subprocess.check_output(['git','ls-tree','-r','--name-only',COMMIT,BASE+'feature/anime4k/gl'],cwd=REPO,text=True).splitlines()
 matching=[]
 for path in paths:
  text=subprocess.check_output(['git','show',COMMIT+':'+path],cwd=REPO).decode()
  if re.search(r'enum class Anime4KDisplayScaleMode',text):matching.append(path)
 assert len(matching)==1
 source=matching[0][len(BASE):];t=read(source)
 if 'com/android/purebilibili/feature/anime4k/gl/Anime4KDisplayScaleMode.class'not in ACTUAL:
  emit('feature/anime4k/gl/DesktopOriginalAnime4KDisplayScaleMode.kt','package com.android.purebilibili.feature.anime4k.gl\n'+selector.declarations(parser,t,['Anime4KDisplayScaleMode'])+'\n',source,'complete-original-pure-display-enum')
 for rel in ['feature/video/ui/components/PlayerMiuixListPopup.kt','feature/video/ui/components/AudioQualitySelectionMenu.kt','feature/video/ui/components/DanmakuComposerPolicy.kt','feature/video/ui/overlay/PlaybackButtonComponents.kt','feature/video/playback/policy/PlaybackTransitionPolicy.kt']:
  full_direct(rel)
 rel='feature/video/ui/overlay/PlayerOverlayModels.kt';t=read(rel)
 t=t.replace('android.graphics.Color.parseColor','com.bilipai.desktop.plugins.DesktopPluginColor.parseColor')
 t=t.replace('import com.android.purebilibili.core.store.player.PlayerSettingsStore','import com.android.purebilibili.core.store.player.DesktopOriginalVideoPlayerSettings as PlayerSettingsStore')
 t=t.replace('androidx.media3.common.Player.STATE_','com.bilipai.desktop.ui.DesktopOriginalPlaybackStates.STATE_')
 emit(rel,t,rel,'complete-original-overlay-display-models-platform-color')
 rel='core/store/player/PlayerSettingsStore.kt';t=read(rel)
 t=t[t.index('{',t.index('object PlayerSettingsStore'))+1:t.rfind('}')]
 # Preserve the complete canonical object, including original speed options,
 # volume rounding, mirror caches and insight migration. Long-press read remains
 # owned by the frozen Offline producer and is forwarded without a second algorithm.
 body=t
 a,b=full.function_range(body,'getLongPressSpeed')
 body=body[:a]+'''    fun getLongPressSpeed(context: Context): Flow<Float> =
        DesktopOriginalLongPressSpeedSettings.getLongPressSpeed(context.pluginContext)
'''+body[b:]
 keys=selector.declarations(parser,read(rel),['defaultAudioQualityPreferenceKey','longPressSpeedPreferenceKey','playbackSpeedOptionsPreferenceKey'])
 # The last selected top-level val would include the following whole object;
 # use its exact single line instead of the generic last-declaration selector.
 keys='\n'.join(line.replace('internal val','private val')for line in read(rel).splitlines()if re.match(r'internal val (defaultAudioQualityPreferenceKey|longPressSpeedPreferenceKey|playbackSpeedOptionsPreferenceKey) =',line))
 adapted='''package com.android.purebilibili.core.store.player
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import com.bilipai.desktop.ui.DesktopOriginalPlayerPreferenceValues as Preferences
import com.bilipai.desktop.ui.DesktopOriginalPlayerPreferenceValues as MutablePreferences
import com.bilipai.desktop.ui.playerStringPreferencesKey as stringPreferencesKey
import com.bilipai.desktop.ui.playerBooleanPreferencesKey as booleanPreferencesKey
import com.bilipai.desktop.ui.playerFloatPreferencesKey as floatPreferencesKey
import com.bilipai.desktop.ui.playerIntPreferencesKey as intPreferencesKey
import com.android.purebilibili.core.store.resolvePreferredPlaybackSpeed as resolvePreferredPlaybackSpeedPolicy
import com.android.purebilibili.core.store.DEFAULT_LONG_PRESS_SPEED
import com.android.purebilibili.core.store.nearestPlaybackSpeed
import com.android.purebilibili.core.store.normalizeLongPressSpeed
import com.android.purebilibili.core.store.normalizePlaybackSpeedOptions
import com.android.purebilibili.core.store.resolvePlaybackSpeedOptions
import com.android.purebilibili.core.store.normalizePlaybackSpeed as normalizePlaybackSpeedPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
'''+keys+'''\nobject DesktopOriginalVideoPlayerSettings {
'''+body+'\n}\n'
 emit('core/store/player/DesktopOriginalVideoPlayerSettings.kt',adapted,rel,'complete-original-player-settings-object-canonical-longpress-reference-owned-global-context')
 # Whole menu/sheet declarations; no branch is replaced by a settings shortcut.
 for rel in ['feature/video/ui/components/SpeedSelectionPanel.kt','feature/video/ui/components/ChapterListPanel.kt','feature/video/ui/components/LandscapeSidePanel.kt','feature/video/ui/components/PagesSelectorLayoutPolicy.kt','feature/video/ui/gesture/TwoFingerSpeedGesturePolicy.kt','feature/video/ui/components/VideoSettingsPanelActionPolicy.kt','feature/video/ui/components/ModalChildScroll.kt']:
  full_direct(rel)
 for rel in ['feature/video/ui/components/QualityMenu.kt','feature/video/ui/components/VideoSettingsPanel.kt','feature/video/ui/components/PagesSelector.kt']:
  t=read(rel)
  t=t.replace('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration')
  t=t.replace('import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.LocalDesktopOriginalPlayerSettingsContext as LocalContext')
  t=t.replace('androidx.compose.ui.platform.LocalContext.current','com.bilipai.desktop.ui.LocalDesktopOriginalPlayerSettingsContext.current')
  t=t.replace('androidx.compose.ui.platform.LocalConfiguration.current','com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics.current')
  t=t.replace('import com.android.purebilibili.core.store.SettingsManager','import com.android.purebilibili.core.store.DesktopOriginalVideoControlSettings as SettingsManager')
  t=t.replace('com.android.purebilibili.core.store.SettingsManager','com.android.purebilibili.core.store.DesktopOriginalVideoControlSettings')
  t=t.replace('import android.content.res.Configuration\n','').replace('Configuration.ORIENTATION_LANDSCAPE','2')
  t=t.replace('configuration.orientation','(if (configuration.screenWidthDp > configuration.screenHeightDp) 2 else 1)')
  emit(rel,t,rel,'complete-original-menu-sheet-global-context-adapt')
 rel='core/ui/components/PlaybackSpeedPreferenceControl.kt';t=read(rel)
 # formatPlaybackSpeed is already sole-owned by existing actual playback settings.
 if 'com/android/purebilibili/core/ui/components/PlaybackSpeedPreferenceControlKt.class' in ACTUAL:
  REFERENCES.append(dict(source=BASE+rel,existingClass='PlaybackSpeedPreferenceControlKt',mode='sole-existing-reference'))
 else:
  # Existing original format function lives in another Kt wrapper. It must not be emitted twice.
  # Complete original format function is not defined in actual47.
  emit(rel,t,rel,'complete-original-speed-preference-controls-reference-existing-format')
 rel='core/store/SettingsManager.kt';t=read(rel);obj=t[t.index('{',t.index('object SettingsManager'))+1:t.rfind('}')]
 names=['getDoubleTapSeekEnabled','setDoubleTapSeekEnabled','getSeekForwardSeconds','setSeekForwardSeconds','getSeekBackwardSeconds','setSeekBackwardSeconds','getLongPressSpeed','setLongPressSpeed','getLongPressSpeedLockEnabled','setLongPressSpeedLockEnabled','setLongPressSpeedLockHintShown','getTwoFingerVerticalSpeedEnabled','setTwoFingerVerticalSpeedEnabled','getTwoFingerHorizontalSpeedEnabled','setTwoFingerHorizontalSpeedEnabled','getPlaybackSpeedOptions','getDefaultPlaybackSpeed','setDefaultPlaybackSpeed','getRememberLastPlaybackSpeed','setRememberLastPlaybackSpeed','getProgressPeakDanmakuEnabled','setProgressPeakDanmakuEnabled']
 names+=['getShowFullscreenLockButton','getShowFullscreenScreenshotButton','getShowFullscreenBatteryLevel','getShowFullscreenTime','getShowFullscreenActionItems','getShowOnlineCount','getBottomProgressBehavior','getPlayerControlVisibilitySettings','getPlayerProgressPlacement','getPlaybackCompletionBehavior','setPlaybackCompletionBehavior','getHideVideoPageStatusBar','getHideVideoPageStatusBarSync','getCardAnimationEnabled','setLastPlaybackSpeed']
 body,closure=member_closure(obj,names);keys=''
 imports='''package com.android.purebilibili.core.store
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import com.bilipai.desktop.ui.playerBooleanPreferencesKey as booleanPreferencesKey
import com.bilipai.desktop.ui.playerIntPreferencesKey as intPreferencesKey
import com.android.purebilibili.core.store.player.DesktopOriginalVideoPlayerSettings as PlayerSettingsStore
import com.android.purebilibili.feature.video.ui.gesture.TwoFingerSpeedToggleState
import com.android.purebilibili.feature.video.ui.gesture.applyHorizontalTwoFingerSpeedToggle
import com.android.purebilibili.feature.video.ui.gesture.applyVerticalTwoFingerSpeedToggle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.distinctUntilChanged
object DesktopOriginalVideoControlSettings {
'''
 emit('core/store/DesktopOriginalVideoControlSettings.kt',imports+keys+'\n'+body+'\n}\n',rel,'original-complete-control-settings-getters-setters-keys-same-global')
 for rel in ['feature/video/ui/overlay/VideoPlayerOverlayVisualPolicy.kt','feature/video/ui/overlay/LandscapeEndDrawerLayoutPolicy.kt','feature/video/ui/overlay/PlaybackOrderDisplayPolicy.kt','feature/video/ui/overlay/PlaybackOrderSelectionSheetPolicy.kt','feature/video/ui/components/LandscapeDanmakuComposer.kt','feature/video/ui/overlay/PortraitTopBarLayoutPolicy.kt','feature/video/ui/overlay/ImmersiveStatusBarBackdrop.kt','feature/video/ui/overlay/PersistentProgressBar.kt','core/store/PlaybackCompletionBehaviorSyncPolicy.kt']:
  full_direct(rel)
 for rel in ['feature/video/ui/overlay/PlaybackOrderSelectionSheet.kt','feature/video/ui/components/DanmakuSendDialog.kt']:
  t=read(rel).replace('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration')
  t=t.replace('import android.content.res.Configuration\n','').replace('Configuration.ORIENTATION_LANDSCAPE','2').replace('configuration.orientation','(if (configuration.screenWidthDp > configuration.screenHeightDp) 2 else 1)')
  t=re.sub(r'(?m)^\s*decorFitsSystemWindows = false,?\s*\n','',t)
  emit(rel,t,rel,'complete-original-sheet-input-color-ui-window-config-adapt')
 rel='core/ui/performance/PanelFrameRateOverridePolicy.kt';t=read(rel)
 imports='''package com.android.purebilibili.core.ui.performance
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import com.bilipai.desktop.ui.LocalDesktopOriginalPlayerSettingsContext
'''
 body=selector.declarations(parser,t,['resolvePanelFrameRateOverrideLabel','PANEL_FRAME_RATE_SAMPLE_INTERVAL_MS','rememberPanelFrameRateLabel'])
 body=body.replace('LocalContext.current.findActivity()','LocalDesktopOriginalPlayerSettingsContext.current.videoOverlay')
 body=body.replace('resolvePanelDisplayRefreshRate(activity)','activity.readPanelRefreshRate() ?: 0f')
 emit('core/ui/performance/DesktopOriginalPlayerPanelFrameRate.kt',imports+body,rel,'original-label-and-500ms-composition-sampler-required-actual-host-monitor-read')
 # Full diagnostic action model/tracker: one original weak-key tracker over the actual MPV facade.
 rel='feature/video/playback/session/PlaybackUserActionTracker.kt';t=read(rel)
 t=t.replace('import androidx.media3.common.Player','import com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player')
 t=t.replace('import com.android.purebilibili.core.store.PlayerSettingsCache\n','')
 t=t.replace('PlayerSettingsCache.isPlayerDiagnosticLoggingEnabled()','player.diagnosticLoggingEnabled')
 emit(rel,t,rel,'complete-original-pending-user-action-model-tracker-real-mpv-facade')
 rel='feature/video/usecase/VideoPlaybackUseCase.kt';t=read(rel)
 names=['shouldPreparePlayerBeforeExplicitPlay','playPlayerFromUserAction','pausePlayerFromUserAction','applyPlaybackButtonUserAction','playPlayerForUserIntent','shouldResumePlaybackAfterUserSeek','seekPlayerFromUserAction','togglePlayerPlaybackFromUserAction','shouldPauseForPlaybackToggle']
 pieces=selector.declarations(parser,t,names)
 pieces=adapt(pieces,'PlaybackMediaCache.logSeek(','player.logSeek(','Original seek diagnostic keeps all four position fields; Android SimpleCache byte counters require an explicit Windows capability boundary, no parallel cache')
 read('core/player/PlaybackMediaCache.kt')
 emit('feature/video/usecase/DesktopOriginalOverlayPlaybackActions.kt','''package com.android.purebilibili.feature.video.usecase
import com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player
import com.android.purebilibili.core.util.Logger
import com.android.purebilibili.feature.video.playback.session.PlaybackUserActionTracker
import com.android.purebilibili.feature.video.ui.overlay.PlaybackUserActionType
'''+pieces,rel,'complete-original-explicit-user-play-pause-seek-actions-mpv-facade')
 rel='feature/video/ui/overlay/VideoPlayerOverlay.kt';t=read(rel)
 for name in ['android.content.ClipData','android.content.Context','android.os.Build','com.android.purebilibili.data.repository.VideoRepository','com.android.purebilibili.feature.cast.LocalProxyServer','com.android.purebilibili.core.util.NetworkUtils']:
  t=t.replace('import '+name+'\n','')
 t=t.replace('import androidx.media3.common.Player','import com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player')
 t=t.replace('import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.LocalDesktopOriginalPlayerSettingsContext as LocalContext\nimport com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context')
 t=t.replace('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration')
 t=t.replace('import com.android.purebilibili.core.store.SettingsManager','import com.android.purebilibili.core.store.DesktopOriginalVideoControlSettings as SettingsManager')
 t=t.replace('import com.android.purebilibili.core.store.player.PlayerSettingsStore','import com.android.purebilibili.core.store.player.DesktopOriginalVideoPlayerSettings as PlayerSettingsStore')
 t=t.replace('import com.android.purebilibili.core.plugin.skin.UiSkinAnimatedAsset','import com.bilipai.desktop.ui.DesktopOriginalPlayerSkinAsset as UiSkinAnimatedAsset')
 t=t.replace('import com.android.purebilibili.feature.cast.DeviceListDialog','import com.bilipai.desktop.ui.DesktopOriginalPlayerDeviceListDialog as DeviceListDialog')
 t=t.replace('import com.android.purebilibili.feature.video.share.VideoShareSheetHost','import com.bilipai.desktop.ui.DesktopOriginalPlayerVideoShareSheetHost as VideoShareSheetHost')
 t=t.replace('player.playbackParameters.speed','player.playbackSpeed')
 t=t.replace('onDrawerVideoClick(target.nextBvid, null)','onDrawerVideoClick(target.nextBvid, 0L)')
 t=t.replace('import com.android.purebilibili.core.ui.blur.shouldAllowRuntimeShaderBackedHazeEffect\n','')
 t=t.replace('shouldAllowRuntimeShaderBackedHazeEffect(Build.VERSION.SDK_INT)','com.bilipai.desktop.ui.desktopDetailRenderEffectsSupported()')
 t=t.replace('''Logger.exportPlayerDiagnostic(
                                    context = context,
                                    content = exportDiagnosticReport(signal)
                                )''','context.videoOverlay.exportPlayerDiagnostic(exportDiagnosticReport(signal))')
 t=t.replace('NetworkUtils.getNetworkTypeLabel(context)','context.videoOverlay.networkTypeLabel()')
 t=t.replace('android.net.Uri.parse(currentVideoUrl).host.orEmpty()','java.net.URI(currentVideoUrl).host.orEmpty()')
 t=t.replace('VideoRepository.getTvCastPlayData','context.videoOverlay.getTvCastPlayData')
 t=t.replace('LocalProxyServer.getProxyUrl(context, ','context.videoOverlay.castProxyUrl(')
 t=t.replace('LocalProxyServer.registerDashManifest(context, ','context.videoOverlay.registerCastDashManifest(')
 t=t.replace('LocalProxyServer.DASH_CONTENT_TYPE','context.videoOverlay.dashContentType').replace('LocalProxyServer.ensureStarted()','context.videoOverlay.ensureCastProxyStarted()')
 t=t.replace('plugin.cast(context, route,','plugin.cast(context.pluginContext, route,')
 resolverA,resolverB=full.function_range(t,'resolveCastPlayUrl')
 resolver=t[resolverA:resolverB]
 resolver=adapt(resolver,'withContext(Dispatchers.IO) {','''withContext(Dispatchers.IO) {
    kotlinx.coroutines.currentCoroutineContext().ensureActive()
    context.requireCurrent()''','Reject canceled/retired cast resolution before existing transport')
 assert resolver.count('}.getOrNull()')==2
 resolver=resolver.replace('}.getOrNull()','''}.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()''')
 resolver=adapt(resolver,'    if (tvData != null) {','''    kotlinx.coroutines.currentCoroutineContext().ensureActive()
    context.requireCurrent()
    if (tvData != null) {''','Reject canceled/retired result before cast proxy or direct URL publication')
 ADAPT.append(dict(label='Cancellation never converts to cast fallback',before=t[resolverA:resolverB],after=resolver))
 t=t[:resolverA]+resolver+t[resolverB:]
 t=t.replace('import kotlinx.coroutines.Dispatchers\n','import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.ensureActive\n')
 t=t.replace('android.widget.Toast','com.bilipai.desktop.ui.DesktopOriginalPlayerFeedback')
 t=t.replace('(String, android.os.Bundle?) -> Unit','(String, Long) -> Unit').replace('onVideoClick(video.bvid, null)','onVideoClick(video.bvid, video.cid)')
 t=adapt(t,'''com.android.purebilibili.feature.video.screen.buildVideoNavigationOptions(
                                                        targetCid = episode.cid
                                                    )''','episode.cid','Original drawer target CID transferred through typed existing Root navigation port')
 t=adapt(t,'''player.playerError?.let { error ->
                listOf(error.errorCodeName, error.message.orEmpty())
                    .filter { it.isNotBlank() }
                    .joinToString(": ")
            }''','player.playerErrorMessage','MPV actual error text replaces Media3-specific error enum')
 t=adapt(t,'''player.playerError?.let { error ->
                    listOf(error.errorCodeName, error.message.orEmpty())
                        .filter { it.isNotBlank() }
                        .joinToString(": ")
                }''','player.playerErrorMessage','MPV actual live error text in unchanged diagnostic report')
 t=t.replace('player.playerError','player.playerErrorMessage')
 # Undo the generic receiver replacement on the two already-adapted scalar reads.
 t=t.replace('player.playerErrorMessageMessage','player.playerErrorMessage')
 before='''    DisposableEffect(player) {
        currentSpeed = player.playbackSpeed
        val speedListener = object : Player.Listener {
            override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                currentSpeed = playbackParameters.speed
            }
        }
        player.addListener(speedListener)
        onDispose { player.removeListener(speedListener) }
    }'''
 t=adapt(t,before,'''    LaunchedEffect(player) {
        player.state.collect { if (player.isOwned()) currentSpeed = player.playbackSpeed }
    }''','Original speed listener consumes same actual MPV StateFlow; collection canceled with original composition')
 a=t.index('                        val clipboard = context.getSystemService');b=t.index('                        com.bilipai.desktop.ui.DesktopOriginalPlayerFeedback.makeText',a)
 old=t[a:b];new='''                        context.videoOverlay.copyText("BiliPai Player Diagnostics", exportDiagnosticReport(null))
''';ADAPT.append(dict(label='Same Root actual clipboard ownership',before=old,after=new));t=t[:a]+new+t[b:]
 emit(rel,t,rel,'complete-original-overlay-all-menus-cast-reload-diagnostic-drawer-real-mpv-platform-adapt')
 rel='feature/video/screen/VideoDetailPlatformPolicy.kt';t=read(rel)
 emit('feature/video/screen/DesktopOriginalPlayerSystemBarInsetPolicy.kt','package com.android.purebilibili.feature.video.screen\n'+selector.declarations(parser,t,['shouldApplyStatusBarPaddingToVideoPlayerChrome','VideoDetailSystemBarsVisibilityPolicy','resolveVideoDetailSystemBarsVisibilityPolicy'])+'\n',rel,'complete-original-inset-policy')
 save(LANE/'source-inventory.json',dict(commit=COMMIT,sources=[dict(path=p,**{k:v for k,v in r.items()if k!='text'})for p,r in SOURCES.items()],outputs=OUTPUTS,adaptations=ADAPT,existingReferences=REFERENCES,scope='Whole BottomControlBar/TopControlBar and required complete helper/model units; full Overlay/player-page next',noActualAcceptance=True))
 print(json.dumps(dict(sources=len(SOURCES),outputs=len(OUTPUTS),existingReferences=REFERENCES)))
if __name__=='__main__':main()
