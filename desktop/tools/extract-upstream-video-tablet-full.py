from v025_source_paths import canonical_source as _desktop_canonical_source, canonical_relative as _desktop_canonical_relative
from pathlib import Path
import hashlib,importlib.util,json,re,subprocess,sys,difflib
sys.dont_write_bytecode=True
import argparse
cli=argparse.ArgumentParser(description="Complete fixed-tag Tablet/Cinema and BottomInputBar source closure")
cli.add_argument('--repo',type=Path,required=True);cli.add_argument('--output',type=Path,required=True);cli.add_argument('--standalone',action='store_true')
args=cli.parse_args();REPO=args.repo.resolve();LANE=args.output.resolve()
FEATURE='stable-video-tablet-original'
SOURCE_PINS={'app/src/main/java/com/android/purebilibili/feature/video/screen/TabletVideoLayoutPolicy.kt': {'sha256LF': '31b95f171a3ebda26424a0ddc18d52911ec4be9181ec808cd28404bf837e0f7f', 'gitBlob': '6227d6ebee10196bbe3cf3ba2cf74b63fde5593b'}, 'app/src/main/java/com/android/purebilibili/feature/video/screen/TabletVideoInfoEntrancePolicy.kt': {'sha256LF': '1cf26d5e09804fd84ebd0e39e744e2dd025cf328983c73a26e64b0d112c24d40', 'gitBlob': '93ef25f973ea749e0c196c3e44017f3e0d1fdd66'}, 'app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt': {'sha256LF': '5799bb8802992594ae9494b48d6357ee00ecc7be03d97ed0dcb5fede7774328c', 'gitBlob': '5d24281dc2152c1e2113ab3476418f61261cd15a'}, 'app/src/main/java/com/android/purebilibili/feature/video/screen/TabletDanmakuChromeState.kt': {'sha256LF': 'b19f5f30827945f4d295330b39e6441f94be721c59f63fbdb6a123152ccc56c4', 'gitBlob': 'c8a0b76ee6e62ab1a6bd0f4741649430d9e5fe47'}, 'app/src/main/java/com/android/purebilibili/feature/video/screen/TabletVideoLayout.kt': {'sha256LF': 'f301b7b66fbced10e280c1a3777d545e3809194765f66fd724c2ed7192f215d8', 'gitBlob': '8b8bfb53a6ac38791439f39c67b547e5e80ceafe'}, 'app/src/main/java/com/android/purebilibili/feature/video/screen/TabletCinemaLayout.kt': {'sha256LF': '58c3765fdf1f86f25589b338e3f60cf3ea9a575fd660ccce9df16117713fde76', 'gitBlob': 'c78953c63b47d50a99bc55ecc23a5bbe8f20bf82'}, 'app/src/main/java/com/android/purebilibili/feature/space/SpaceViewModel.kt': {'sha256LF': 'c54a7d24b03ecd5ff177b98c9007756c7f1e87d3629218019dd34b76907212af', 'gitBlob': 'a27208c422d5b637a02a3086e546792fae125020'}, 'app/src/main/java/com/android/purebilibili/feature/video/screen/TabletOwnerUploadsPane.kt': {'sha256LF': 'a2fc7ae37cbd5dab0a9cf36fc75f5bd0803d48df810270f76b3f72807da73d1f', 'gitBlob': 'a1ea77c9cda33ddb5bd83c8463e8ce426d0d8a48'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/BottomInputBar.kt': {'sha256LF': '11bded8eb078551356ee7c392102810f6703552786074e5574a751d1d5b2f4c4', 'gitBlob': 'af110162d01f8fa600ca9f7ff62f5ed7e2b9a5a4'}}
DIRECT={p for p in SOURCE_PINS if p.endswith('TabletVideoLayoutPolicy.kt') or p.endswith('TabletVideoInfoEntrancePolicy.kt')}
manifest=json.loads((REPO/'desktop/upstream-sources.json').read_text(encoding='utf-8'))
assert manifest['upstreamCommit']=='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'

COMMIT='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40';BASE='app/src/main/java/com/android/purebilibili/'
OUT=LANE;SOURCES={};CHANGES={};OUTPUTS=[]
FRESH_V025_COMMENT_COUNTS={'com/android/purebilibili/feature/video/screen/VideoDetailScreenStateHolder.kt': {'isRepliesRefreshing': 2, 'repliesError': 2, 'onRefreshReplies': 2, 'onRefresh': 2}, 'com/android/purebilibili/feature/video/screen/DesktopOriginalVideoContentSection.kt': {'isRepliesRefreshing': 3, 'repliesError': 3, 'onRefreshReplies': 3, 'onRefresh': 0}, 'com/android/purebilibili/feature/video/screen/TabletVideoLayout.kt': {'isRepliesRefreshing': 0, 'repliesError': 3, 'onRefreshReplies': 0, 'onRefresh': 2}, 'com/android/purebilibili/feature/video/screen/TabletCinemaLayout.kt': {'isRepliesRefreshing': 0, 'repliesError': 4, 'onRefreshReplies': 0, 'onRefresh': 2}}

def validate_fresh_v025_comment_consumer(output,text):
 # Source pins/full inverse are checked before this writer. Fresh canonical
 # bodies already contain the business fragments; the old incremental helper
 # must not inject them a second time. Verify the actual canonical call counts.
 import re
 expected=FRESH_V025_COMMENT_COUNTS.get(output)
 if expected is not None:
  for field,count in expected.items():
   actual=len(re.findall(r'\b'+field+r'\s*=',text))
   if actual!=count:raise ValueError('Fresh canonical comment consumer mismatch: '+output+' '+field+' '+str(actual)+' != '+str(count))
 return text

def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
 return Path(s if s.startswith(prefix) else prefix+s)
def sha(t):return hashlib.sha256(t.encode() if isinstance(t,str) else t).hexdigest()
def write(p,t):safe(p).parent.mkdir(parents=True,exist_ok=True);safe(p).write_bytes(t.encode() if isinstance(t,str) else t)
def module(name,p):
 spec=importlib.util.spec_from_file_location(name,p);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
parser=module('tablet_tokens',REPO/'desktop/tools/sync-upstream.py')
selector=module('tablet_select',REPO/'desktop/tools/extract-appearance-platform.py')
section=module('tablet_section',REPO/'desktop/tools/extract-upstream-video-player-section-full.py')
section.parser=parser
def read(rel):
 path=_desktop_canonical_relative(REPO,BASE+rel)
 if path not in SOURCES:
  raw=subprocess.check_output(['git','show',COMMIT+':'+path],cwd=REPO)
  assert raw==safe(_desktop_canonical_source(REPO, path)).read_bytes().replace(b'\r\n',b'\n'),path
  SOURCES[path]={'text':raw.decode(),'sha256LF':sha(raw),'gitBlob':subprocess.check_output(['git','rev-parse',COMMIT+':'+path],cwd=REPO,text=True).strip()}
  assert SOURCES[path]['sha256LF']==SOURCE_PINS[path]['sha256LF'] and SOURCES[path]['gitBlob']==SOURCE_PINS[path]['gitBlob'],path
  if not args.standalone:
   rows=[r for r in manifest['sources']if r['path']==path]
   assert len(rows)==1 and rows[0]['sha256']==SOURCE_PINS[path]['sha256LF'] and FEATURE in rows[0]['features'],path

  write(LANE/'original-retained'/(path+'.txt'),raw)
 return SOURCES[path]['text']
def replace(t,before,after,label='Explicit Windows source seam',all=False):
 n=t.count(before);assert n>0,(label,n)
 if not all:assert n==1,(label,n)
 CHANGES.setdefault(CURRENT,[]).append({'label':label,'before':before,'after':after,'count':n if all else 1})
 return t.replace(before,after) if all else t.replace(before,after,1)
def between(t,start,end,after,label):
 a=t.index(start);b=t.index(end,a);return replace(t,t[a:b],after,label)
def emit(rel,t,original):
 t=validate_fresh_v025_comment_consumer("com/android/purebilibili/"+rel,t)
 if BASE+original in DIRECT and not args.standalone:return
 p=OUT/'com/android/purebilibili'/rel;write(p,t)
 OUTPUTS.append({'path':str(p),'sha256LF':sha(t),'original':BASE+original})
def common(t):
 mappings={
 'import android.content.res.Configuration':'import com.bilipai.desktop.ui.DesktopHomeCardWindowBounds as Configuration',
 'import androidx.compose.ui.platform.LocalConfiguration':'import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration',
 'import androidx.compose.ui.platform.LocalContext':'import com.bilipai.desktop.ui.LocalDesktopOriginalPlayerSettingsContext as LocalContext',
 'import com.android.purebilibili.feature.video.state.VideoPlayerState':'import com.bilipai.desktop.ui.DesktopOriginalMpvVideoPlayerState as VideoPlayerState',
 'import com.android.purebilibili.core.util.ShareUtils\n':'import com.bilipai.desktop.ui.LocalDesktopOriginalVideoContentBindings\n',
 'import com.android.purebilibili.core.store.SettingsManager':'import com.android.purebilibili.core.store.DesktopOriginalTabletAudioSettings as SettingsManager',
 'import com.android.purebilibili.core.ui.motion.rememberSystemReduceMotion':'import com.bilipai.desktop.ui.rememberDesktopDynamicReduceMotion as rememberSystemReduceMotion',
 '(String, android.os.Bundle?) -> Unit':'(String, Long, String?) -> Unit',
 'androidx.compose.ui.platform.LocalContext.current':'LocalContext.current',
 'com.android.purebilibili.core.store.SettingsManager':'SettingsManager',
 'com.android.purebilibili.core.ui.animation.MaybeDissolvableVideoCard(':'com.bilipai.desktop.ui.DesktopReplyDissolvableContainer(',
 }
 for a,b in mappings.items():
  if a in t:t=replace(t,a,b,'Explicit Windows platform/type alias: '+a,True)
 t=replace(t,'import com.android.purebilibili.feature.video.danmaku.rememberDanmakuManager\n','','Reference sole Section danmaku actor') if 'import com.android.purebilibili.feature.video.danmaku.rememberDanmakuManager\n'in t else t
 # Platform parameters are required by Local providers; preserve original default UI state values.
 if 'ShareUtils.shareText(' in t:
  t=replace(t,'    val onShareVideoNote:', '    val platform = LocalDesktopOriginalVideoContentBindings.current\n    val onShareVideoNote:', 'Capture sole share actor binding during composition')
  t=replace(t,'ShareUtils.shareText(\n            context = context,','platform.shareText(', 'Same owned native text share actor')
  t=replace(t,',\n            chooserTitle = "分享视频笔记"','', 'Android chooser caption has no separate Windows actor effect')
 return t
for name in ['TabletVideoLayoutPolicy','TabletVideoInfoEntrancePolicy']:
 CURRENT='feature/video/screen/'+name+'.kt';emit(CURRENT,read(CURRENT),CURRENT)
CURRENT='core/store/SettingsManager.kt';settings=read(CURRENT)
body,names=section.member_closure(settings[settings.index('object SettingsManager {')+len('object SettingsManager {'):settings.rfind('}')],['getTabletSecondaryDefaultTab','setTabletSecondaryDefaultTab','setCommentDefaultSortMode'])
enum=selector.declarations(parser,settings,['TabletSecondaryDefaultTab'])
emit('core/store/DesktopOriginalTabletSecondaryDefaultTab.kt','package com.android.purebilibili.core.store\n'+enum+'\n',CURRENT)
emit('core/store/DesktopOriginalTabletAudioSettings.kt','''package com.android.purebilibili.core.store
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import com.bilipai.desktop.ui.playerIntPreferencesKey as intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
object DesktopOriginalTabletAudioSettings {
'''+body+'\n}\n',CURRENT)
CURRENT='feature/video/screen/TabletDanmakuChromeState.kt';t=read(CURRENT)
t=replace(t,'import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.LocalDesktopOriginalVideoSectionPlatform')
t=replace(t,'import com.android.purebilibili.core.store.SettingsManager\n','')
t=replace(t,'import com.android.purebilibili.feature.video.danmaku.rememberDanmakuManager\n','')
t=replace(t,'    val context = LocalContext.current','    val platform = LocalDesktopOriginalVideoSectionPlatform.current\n    val context = platform.settingsContext')
t=replace(t,'val danmakuManager = rememberDanmakuManager(bvid)','val danmakuManager = platform.danmaku')
t=replace(t,'SettingsManager\n        .getDanmakuSettings(context, DanmakuSettingsScope.PORTRAIT)','platform.danmakuPreferences\n        .getDanmakuSettings(DanmakuSettingsScope.PORTRAIT)')
t=replace(t,'initialValue = DanmakuSettings()','initialValue = platform.danmakuPreferences.currentSettings(DanmakuSettingsScope.PORTRAIT)')
t=replace(t,'SettingsManager.setDanmakuEnabled(\n                    context,','platform.danmakuPreferences.setDanmakuEnabled(')
emit(CURRENT,t,CURRENT)
for name in ['TabletVideoLayout','TabletCinemaLayout']:
 CURRENT='feature/video/screen/'+name+'.kt';t=common(read(CURRENT))
 if name=='TabletVideoLayout':
  t=between(t,'    val activity = remember(context) {','    \n    AppSplitLayout(', '', 'Android Activity lookup is unused after exact navigation options platform mapping')
  t=between(t,'                                    val activity = (context as? android.app.Activity)','                                    onRelatedVideoClick(video.bvid, navOptions)', '                                    val navOptions = buildDesktopOriginalVideoNavigationOptions(targetCid = video.cid)\n', 'Original CID-only return card navigation uses same typed original CID policy')
 # Preserve every original CID/cover distinction at callback sites, including nullable omission.
 t=replace(t,'buildVideoNavigationOptions(', 'buildDesktopOriginalVideoNavigationOptions(', 'Same original CID/cover normalization in existing sole navigation policy',True)
 if ' ?: android.os.Bundle.EMPTY' in t:t=replace(t,' ?: android.os.Bundle.EMPTY','', 'Nullable original navigation omission is carried by explicit fields',True)
 t=replace(t,'onRelatedVideoClick(target.videoId, null)','onRelatedVideoClick(target.videoId, 0L, null)','Comment video links preserve original unspecified CID',True)
 for a,b in [
  ('onRelatedVideoClick(video.bvid, navOptions)','onRelatedVideoClick(video.bvid, navOptions?.first ?: 0L, navOptions?.second)'),
  ('onVideoClick = onRelatedVideoClick,','onVideoClick = onRelatedVideoClick,'),
 ]:
  if a in t and a!=b:t=replace(t,a,b,'Existing original two-field consumer bridges to required rich route callback',True)
 if 'context: android.content.Context' in t:t=replace(t,'context: android.content.Context','context: com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext','Same required global original settings context',True)
 if 'onRelatedVideoClick: (String, Long, String?) -> Unit = { _, _ -> }' in t:t=replace(t,'onRelatedVideoClick: (String, Long, String?) -> Unit = { _, _ -> }','onRelatedVideoClick: (String, Long, String?) -> Unit = { _, _, _ -> }','Original optional route callback arity follows explicit typed fields',True)
 if 'onCollectionEpisodeClick(episode.bvid, navOptions)' in t:t=replace(t,'onCollectionEpisodeClick(episode.bvid, navOptions)','onCollectionEpisodeClick(episode.bvid, navOptions?.first ?: 0L, navOptions?.second)','Collection episode route carries original normalized CID/cover',True)
 indent='                'if name=='TabletCinemaLayout'else'                    '
 t=replace(t,indent+'onBgmClick = onBgmClick,\n'+indent+'onRelatedVideoClick = onRelatedVideoClick,',indent+'onBgmClick = onBgmClick,\n'+indent+'onRelatedVideoClick = { vid, cid -> onRelatedVideoClick(vid, cid, null) },','Existing complete original title renderer carries CID only')
 # Multiline collection route calls are actual Kotlin call boundaries, not guessed episode normalization.
 pat=r'onRelatedVideoClick\(\s*(episode\.bvid),\s*buildDesktopOriginalVideoNavigationOptions\(targetCid = episode\.cid\)\s*\)'
 for m in list(re.finditer(pat,t))[::-1]:
  t=replace(t,m.group(0),'onRelatedVideoClick(episode.bvid, episode.cid.takeIf { it > 0L } ?: 0L, null)','Original collection CID policy positive value/otherwise omission')
 emit(CURRENT,t,CURRENT)
# Original uploads renderer and complete original Space UI schema are selected once.
CURRENT='feature/space/SpaceViewModel.kt';space=read(CURRENT)
spaceState=selector.declarations(parser,space,['SpaceUiState'])
emit('feature/space/DesktopOriginalSpaceUiState.kt','package com.android.purebilibili.feature.space\nimport com.android.purebilibili.data.model.response.*\n'+spaceState+'\n',CURRENT)
CURRENT='feature/video/screen/TabletOwnerUploadsPane.kt';t=read(CURRENT)
t=replace(t,'import androidx.lifecycle.viewmodel.compose.viewModel\n','')
t=replace(t,'import com.android.purebilibili.feature.space.SpaceViewModel','import com.bilipai.desktop.ui.DesktopOriginalOwnerUploadsPort as SpaceViewModel\nimport com.bilipai.desktop.ui.LocalDesktopOriginalTabletAudioPlatform')
t=replace(t,'(String, android.os.Bundle?) -> Unit','(String, Long, String?) -> Unit')
t=replace(t,'spaceViewModel: SpaceViewModel = viewModel(key = "tablet_owner_uploads_$mid")','spaceViewModel: SpaceViewModel = LocalDesktopOriginalTabletAudioPlatform.current.ownerUploads(mid)')
t=replace(t,'onVideoClick(video.bvid, null)','onVideoClick(video.bvid, 0L, null)')
emit(CURRENT,t,CURRENT)
CURRENT='feature/video/ui/components/BottomInputBar.kt';t=read(CURRENT)
for name in ['shouldUseFloatingLiquidBottomInputBar','resolveBottomInputBarContentBottomPadding']:
 selected=selector.declarations(parser,t,[name]).rstrip()
 t=replace(t,selected,'','Reference existing sole dynamic-detail-container original helper: '+name)
t=replace(t,'import androidx.compose.ui.platform.LocalContext\n','import com.bilipai.desktop.ui.LocalDesktopOriginalVideoContentBindings\n')
t=replace(t,'import com.android.purebilibili.core.store.SettingsManager\n','import com.bilipai.desktop.ui.DesktopFavoriteNavigationTypes\n')
t=between(t,'    val context = LocalContext.current','    val autoHideOnScroll', '''    val platform = LocalDesktopOriginalVideoContentBindings.current
    val homeSettings by platform.homeSettings.homeSettings.collectAsStateWithLifecycle()
    val appNavigationSettings by platform.homeSettings.navigation.collectAsStateWithLifecycle()
''','Read same original Root global settings Flow, with actual current initial values')
t=replace(t,'SettingsManager.BottomBarVisibilityMode.SCROLL_HIDE','DesktopFavoriteNavigationTypes.BottomBarVisibilityMode.SCROLL_HIDE','Reference sole original navigation enum')
emit('feature/video/ui/components/DesktopOriginalBottomInputBar.kt',t,CURRENT)
write(LANE/'source-pins.json',json.dumps({p:{k:v for k,v in d.items() if k!='text'}for p,d in SOURCES.items()},indent=2)+'\n')
write(LANE/'tablet-adaptations.json',json.dumps(CHANGES,ensure_ascii=False,indent=2)+'\n')
for out in OUTPUTS:
 original=out['original'];before=SOURCES[original]['text'];after=safe(out['path']).read_text(encoding='utf-8')
 write(LANE/'diffs'/(Path(out['path']).stem+'.diff'),''.join(difflib.unified_diff(before.splitlines(True),after.splitlines(True),fromfile=original,tofile=out['path'])))
write(LANE/'tablet-outputs.json',json.dumps(OUTPUTS,indent=2)+'\n')
print(json.dumps({'outputs':len(OUTPUTS),'sources':len(SOURCES),'changes':sum(len(v) for v in CHANGES.values())}))
