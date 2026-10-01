from pathlib import Path
import importlib.util,json,subprocess,sys,re
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;MAIN=P.parents[2];REPO=MAIN.parent/'BiliPai-v023';BASE='app/src/main/java/com/android/purebilibili/';COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def module(n,p):
 s=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
f=module('extras_edits',P/'prepare-fullscreen.py');pager=module('extra_audit',P/'prepare-pager.py')
def source(rel):
 b=subprocess.check_output(['git','show',COMMIT+':'+BASE+rel],cwd=REPO).replace(b'\r\n',b'\n');f.write(P/'original-stable'/BASE/rel,b);return b.decode()
def emit(rel,t,original):
 f.write(P/'prepared/generated/com/android/purebilibili'/rel,t);pager.audit(rel,original,t,f.EDITS)
def main():
 rel='feature/video/ui/pager/PortraitVideoLoadPolicy.kt';original=source(rel);t=original;f.EDITS=[]
 for expression in ['MediaUtils.isHevcSupported()','MediaUtils.isAv1Supported()','MediaUtils.isDolbyAtmosAudioSupported()','MediaUtils.isDolbySoftwareAudioDecoderRequired()']:
  t=f.exact(t,'Boolean = '+expression,'Boolean','Required same-native codec capabilities')
 t=f.exact(t,'    context: Context,\n    streamUrls:', '    context: DesktopOriginalPortraitPlatform,\n    streamUrls:','Existing owner/cache prefetch capability')
 t=f.exact(t,'com.android.purebilibili.core.util.NetworkUtils.isWifi(context)','context.isWifi()','Actual Windows connection capability')
 t=f.between(t,'        val upstreamFactory = OkHttpDataSource.Factory','        buildList {','','Existing transport supplies the original HTTP headers')
 t=f.between(t,'                val uri = Uri.parse(url)','            }\n        }.awaitAll()', '                context.prefetchRange(url, buildPortraitPlaybackHttpHeaders(), length)\n','Existing bounded cache transport; no new factory/cache')
 t=f.between(t,'@UnstableApi\ninternal fun buildPortraitCachedMediaSourceFactory','', '', 'unused') if False else t
 a=t.index('@UnstableApi\ninternal fun buildPortraitCachedMediaSourceFactory');before=t[a:]
 t=f.exact(t,before,'internal fun buildPortraitCachedMediaSourceFactory(context: DesktopOriginalPortraitPlatform): DesktopOriginalPortraitMediaFactory = context.mediaFactory\n','Required native factory references same source/cache owner')
 for line in t.splitlines(True):
  if line.startswith('import ') and any(x in line for x in ['android.content','android.net','androidx.media3','NetworkModule','PlaybackMediaCache','buildPlaybackCacheKey','MediaUtils']):t=f.exact(t,line,'','Mapped Android media/cache import')
 t=f.exact(t,'@UnstableApi\n','','Android annotation')
 t=f.exact(t,'import kotlinx.coroutines.Dispatchers\n','import kotlinx.coroutines.Dispatchers\nimport com.bilipai.desktop.ui.DesktopOriginalPortraitPlatform\nimport com.bilipai.desktop.ui.DesktopOriginalPortraitMediaFactory\n','Required same media ports')
 emit(rel,t,original)
 for rel in ['feature/video/ui/overlay/PortraitFullscreenOverlay.kt','feature/video/ui/overlay/PortraitFullscreenOverlayLayoutPolicy.kt','feature/video/ui/overlay/PortraitSubtitleOverlay.kt','feature/video/ui/components/VideoCommentSheetHost.kt','feature/video/ui/overlay/PortraitInteractionBar.kt','feature/video/ui/overlay/PortraitInteractionBarLayoutPolicy.kt','feature/video/ui/overlay/PortraitBottomInputBar.kt','feature/video/ui/overlay/PortraitBottomInputBarLayoutPolicy.kt','feature/video/ui/components/UpPreviewSheet.kt','feature/video/ui/components/UpPreviewSheetPolicy.kt','feature/video/ui/components/CoinDialog.kt']:
  original=source(rel);t=original;f.EDITS=[]
  if 'androidx.compose.ui.platform.LocalConfiguration' in t:t=f.each(t,'androidx.compose.ui.platform.LocalConfiguration','com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration','Actual root window metrics')
  if 'import androidx.media3.exoplayer.ExoPlayer' in t:t=f.exact(t,'import androidx.media3.exoplayer.ExoPlayer','import com.bilipai.desktop.ui.DesktopOriginalMpvSectionControl as ExoPlayer','Same actual native control')
  if rel.endswith('PortraitSubtitleOverlay.kt'):
   t=f.exact(t,'import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.LocalDesktopOriginalPortraitPlatform','Same settings/entry platform')
   t=f.each(t,'val context = LocalContext.current','val context = LocalDesktopOriginalPortraitPlatform.current.section.settingsContext','Same global original settings context')
   for before in sorted(set(m.group()for m in re.finditer(r'SettingsManager\s*\.\s*(\w+)',t))):
    name=re.search(r'(\w+)$',before).group(1);owner='DesktopOriginalPlayerSectionSettings'if name=='setSubtitlePositionLocked'else'DesktopOriginalPortraitSettings'
    t=f.each(t,before,'com.android.purebilibili.core.store.'+owner+'.'+name,'Sole original subtitle setters/getters')
   t=f.exact(t,'import com.android.purebilibili.core.store.SettingsManager\n','','Mapped original settings family')
  if rel.endswith('UpPreviewSheet.kt'):
   t=f.exact(t,'    val configuration = LocalConfiguration.current','    val platform = com.bilipai.desktop.ui.LocalDesktopOriginalPortraitPlatform.current\n    val configuration = LocalConfiguration.current','Same full page/entry owned request ports')
   t=f.each(t,'NetworkModule.api.','platform.requests.api.','Same Repository-owned BilibiliApi')
   t=f.each(t,'NetworkModule.spaceApi.','platform.requests.spaceApi.','Same Repository-owned SpaceApi')
   t=f.exact(t,'import com.android.purebilibili.core.network.NetworkModule\n','','No static client or new transport')
  if rel.endswith('VideoCommentSheetHost.kt'):
   selector=module('sheet_existing_policy_selector',MAIN/'desktop/.local/stable-video-detail-full-ui-parity/prepare.py')
   a=t.index('internal fun resolveCommentThreadPredictiveBackOffsetY(');_,b=selector.function_range(t,'resolveCommentThreadCoveredBlurProgress')
   t=f.exact(t,t[a:b],'','REFERENCE actual55 sole two DesktopOriginalDetailThreadPolicies declarations')
   t=f.exact(t,'fun VideoCommentSheetHost(','internal fun VideoCommentSheetHost(','Actual same-owner comment VM visibility')
   for line in ['import android.graphics.RenderEffect as AndroidRenderEffect\n','import android.graphics.Shader\n','import android.os.Build\n','import android.widget.Toast\n','import androidx.compose.ui.graphics.asComposeRenderEffect\n','import com.android.purebilibili.core.store.SettingsManager\n','import androidx.compose.ui.platform.LocalContext\n']:
    t=f.exact(t,line,'','Windows render/feedback/global settings binding')
   t=f.each(t,'val context = LocalContext.current','val platform = com.bilipai.desktop.ui.LocalDesktopCommentBindings.current\n    val context = platform.context','Reuse sole comment page owner, gallery and global settings')
   for before in sorted(set(m.group()for m in re.finditer(r'(?:com\.android\.purebilibili\.core\.store\.)?SettingsManager\s*\.\s*(\w+)',t))):
    name=re.search(r'(\w+)$',before).group(1);owner='DesktopOriginalVideoCommentSheetSettings'if name=='setCommentDefaultSortMode'else'DesktopOriginalReplySettings'
    t=f.each(t,before,'com.android.purebilibili.core.store.'+owner+'.'+name,'Same canonical original comment settings')
   t=f.exact(t,'Toast.makeText(context, lightMessage, Toast.LENGTH_SHORT).show()','platform.showFeedback(lightMessage)','Root owned feedback preserves original message')
   t=f.exact(t,'Build.VERSION.SDK_INT >= Build.VERSION_CODES.S','com.bilipai.desktop.ui.desktopDetailRenderEffectsSupported()','Actual Compose rendering capability')
   t=f.balanced(t,'AndroidRenderEffect.createBlurEffect(', 'androidx.compose.ui.graphics.BlurEffect(\n                                                    radiusX = blurFrame.blurRadiusPx,\n                                                    radiusY = blurFrame.blurRadiusPx,\n                                                )','Actual Compose/Skia blur, original predictive radius')
   t=f.exact(t,').asComposeRenderEffect()',')','Compose effect already typed')
  emit(rel,t,original)
 # Complete existing original pure helper and skeleton declaration; dependencies already actual.
 util=module('extra_function_selector',MAIN/'desktop/.local/stable-video-detail-full-ui-parity/prepare.py')
 for rel,name,target,prefix in [('feature/video/viewmodel/VideoPlaybackViewModel.kt','buildPlaybackAudioUrlCandidates','feature/video/viewmodel/DesktopOriginalPortraitAudioUrlCandidates.kt','package com.android.purebilibili.feature.video.viewmodel\nimport com.android.purebilibili.data.model.response.DashAudio\n'),('core/ui/skeleton/ContentLoadingSkeletons.kt','CommentListSkeleton','core/ui/skeleton/DesktopOriginalVideoCommentListSkeleton.kt','package com.android.purebilibili.core.ui.skeleton\nimport androidx.compose.runtime.Composable\nimport androidx.compose.ui.Modifier\nimport androidx.compose.ui.unit.dp\nimport androidx.compose.foundation.layout.*\nimport androidx.compose.foundation.lazy.LazyColumn\nimport androidx.compose.foundation.lazy.items as lazyListItems\n')]:
  raw=source(rel);a,b=util.function_range(raw,name);chosen=raw[a:b]
  f.write(P/'prepared/generated/com/android/purebilibili'/target,prefix+('@Composable\n'if name=='CommentListSkeleton'else'')+chosen+'\n')
  f.write(P/('adaptations/'+Path(target).name+'.json'),json.dumps(dict(originalPath=BASE+rel,originalSHA256LF=f.sha(raw),declaration=name,originalDeclarationSHA256LF=f.sha(chosen),selectedWholeDeclaration=True),indent=2)+'\n')
 # Same existing settings Store; only the original missing sort setter is selected.
 libs=module('extra_setting_tokens',P/'prepare-libraries.py');raw=source('core/store/SettingsManager.kt')
 chosen,names=libs.c.member_closure(libs.body(raw,'SettingsManager'),['setCommentDefaultSortMode'])
 prefix='package com.android.purebilibili.core.store\nimport com.bilipai.desktop.plugins.DesktopPluginContext as Context\nimport com.bilipai.desktop.settings.dynamicIntPreferencesKey as intPreferencesKey\nimport com.bilipai.desktop.settings.dynamicSettingsDataStore as settingsDataStore\ninternal object DesktopOriginalVideoCommentSheetSettings {\n'
 f.write(P/'prepared/generated/com/android/purebilibili/core/store/DesktopOriginalVideoCommentSheetSettings.kt',prefix+chosen+'\n}\n')
 print('Whole Portrait policies/Overlay/Subtitle/CommentSheetHost selected')
if __name__=='__main__':main()
