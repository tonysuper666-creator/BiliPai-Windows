from v025_source_paths import canonical_source as _desktop_canonical_source, canonical_relative as _desktop_canonical_relative
from pathlib import Path
import hashlib,importlib.util,json,re,sys,zipfile,subprocess
from v025_source_paths import canonical_source as _desktop_canonical_source
sys.dont_write_bytecode=True
REPO=None;OUTPUT=None;STANDALONE=False;COMMIT='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40';BASE='app/src/main/java/com/android/purebilibili/'
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

NOTE_BADGE_EDITS=[('private fun noteLevelBadgeRes(note: VideoNotePublicPreview): Int? {', 'private fun noteLevelBadgeRes(note: VideoNotePublicPreview): String? {'), ('com.android.purebilibili.R.drawable.lv6_s', '"blocked-up-badges/lv6_s.png"'), ('com.android.purebilibili.R.drawable.lv6', '"blocked-up-badges/lv6.png"'), ('com.android.purebilibili.R.drawable.lv1', '"blocked-up-badges/lv1.png"'), ('com.android.purebilibili.R.drawable.lv2', '"blocked-up-badges/lv2.png"'), ('com.android.purebilibili.R.drawable.lv3', '"blocked-up-badges/lv3.png"'), ('com.android.purebilibili.R.drawable.lv4', '"blocked-up-badges/lv4.png"'), ('com.android.purebilibili.R.drawable.lv5', '"blocked-up-badges/lv5.png"')]

def adapt_v025_note_badge(rel,text):
 if rel!='feature/video/ui/section/VideoNoteSection.kt':return text
 for before,after in NOTE_BADGE_EDITS:
  if text.count(before)!=1:raise ValueError('Pinned original note badge boundary changed: '+before)
  text=text.replace(before,after,1)
 return text

def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def write(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(t.encode()if isinstance(t,str)else t)
def sha(t):return hashlib.sha256(t.encode()if isinstance(t,str)else t).hexdigest()
def save(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def module(name,path):
 spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
SOURCE_PINS={'app/src/main/java/com/android/purebilibili/feature/video/ui/section/AiSummarySection.kt': {'sha256LF': 'b7ca333ec57ea45ad5b629e7072f79cedc896524017df838ed0f7a19d91fb534', 'gitBlob': '19eab3bf1bf4c536e2655ca06e1ce02e9f864bec'}, 'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/AiSummaryUiPolicy.kt': {'sha256LF': 'f9c45d1d837a424657b3e94fc87a3b2877bccb82957427217fd96b7d98c2d9ab', 'gitBlob': 'd4c35ad3d3039442bcf5a17992f1a0d0229009a9'}, 'app/src/main/java/com/android/purebilibili/data/repository/AiSummaryFetchPolicy.kt': {'sha256LF': '0bc2f3965f4d26dd57e590e3fe1a15ca1a771e10079b93eb5a232764009f84ca', 'gitBlob': '8591e232531e0551d68290f18d7b5f6fd904b61e'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoSupplementSection.kt': {'sha256LF': '0965ddec57aab1ce7119c53519cebb498a9dcba98a248167ebaa189c3c22bd40', 'gitBlob': '9f6498d3fd2d64ccc51decba7f29c13bd7a297d7'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoNoteSection.kt': {'sha256LF': 'eaad7169cb2c5ffb87a06015807caa4cb7d1e8eab614344a2b37f42f98b65f54', 'gitBlob': '3161f8b1928dde68563f9368c40a6828db3ab4fc'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/RelatedVideoActionSheet.kt': {'sha256LF': '825650c9d684434c14eef6ed8c34e297f8edc227a1e9f5c317d048dfd9e2a146', 'gitBlob': 'd66820ad5440e1dfd75dee851032a4e3c892a39d'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/RelatedVideoActionPolicy.kt': {'sha256LF': 'fff4998471798fca6c73a1c53eb046010f11dbaff5131399a72b955ad7515a1c', 'gitBlob': '98778473a45e7e400a65a4502c8965f096490c62'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/RelatedVideoCardLayoutPolicy.kt': {'sha256LF': '07b1b5849436163e8cc90ebdd75e0b5fada8f87628221f17dc91cf21f7379e4d', 'gitBlob': '4da5eea4ab8cebb5a38c0c5e6bbdf0545308d785'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoDetailMotionBudgetPolicy.kt': {'sha256LF': '4060700bda58e62c0bb5c2c9e0949e1ad3bfcf824d20a24f2e2a9d367255f5db', 'gitBlob': 'b94cbef2c11d59f82539c0ee83baaf9e5912eb60'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/VideoCommentBackToTopPolicy.kt': {'sha256LF': '89cd53e725343a801476a762cba314deb597dd1ee5fb5ed91dc301a8666b088f', 'gitBlob': '745b749a0642425b3403d250b6f2b97fb07095ee'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoDetailSecondaryButton.kt': {'sha256LF': 'f7385b05c3d40526141b2a2d9dfc8184d42f7fb577ac8355f87356cd65073c1f', 'gitBlob': '96a168fe0ed05389d1e845cfb39d3c2c056ec34c'}, 'app/src/main/java/com/android/purebilibili/feature/video/note/VideoNoteEditorStatePolicy.kt': {'sha256LF': '2360ad8a00455731151bd75b2c8d0d7d9dc26448b7fa866c98d9bae9ebb32adc', 'gitBlob': 'a32f059f208e84ec84783fe7497147d34484d55d'}, 'app/src/main/java/com/android/purebilibili/feature/video/note/VideoNoteEmotionPolicy.kt': {'sha256LF': 'f8afdd070bc4ac7ddf6d561975e802d1ef8571cfb08bf060b690e390728a7ae6', 'gitBlob': '8b383e08585c4a119a7b7ea00dfa1054e8b73f1a'}, 'app/src/main/java/com/android/purebilibili/feature/video/note/VideoNoteVisibilityPolicy.kt': {'sha256LF': 'dac221c56f2521a48250891b439222a088a15cc66d552b482274f135d163f273', 'gitBlob': '30a3344e7bad2e2781fe2ceb5ba037d865452aab'}, 'app/src/main/java/com/android/purebilibili/feature/video/screen/PortraitDetailPresentationPolicy.kt': {'sha256LF': '2daaffbd12cbfd62f560bbfa4c008629aa7b8aff846453cd2762de7c242711de', 'gitBlob': '8f86b499d3b2c52b930b6f41df1650c80188f64e'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/CommentSortFilterBar.kt': {'sha256LF': '8a47a92187486ced7686861df44f1ef283f790acd80284656d55751853e144e2', 'gitBlob': '30d194757554b7e26c0835e3c1ec826f752dbaed'}, 'app/src/main/java/com/android/purebilibili/feature/video/screen/VideoNavigationCidPolicy.kt': {'sha256LF': '9f4fe9aa4a4d7b37db454692a8cf5b55c778136301ce8c1874c9fd89c1c2f59a', 'gitBlob': '7d44b3fb400e4d0809c7258eac97c61b6f8f2898'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/RelatedVideoItem.kt': {'sha256LF': '00c0e0ec11fa04524c2f0c131e806a4f92e037cac322e79e434b00615e2e82f2', 'gitBlob': 'c0386b40d894687a5f15dbe2660bbc526c7acdb9'}, 'app/src/main/java/com/android/purebilibili/feature/video/screen/VideoContentSection.kt': {'sha256LF': '201e634e46b680a92a0c8219b1f3f1fd4da1cf69c58291f3b5feca39bbc7839f', 'gitBlob': '7bf6d9109dd2789a0f712d04013c3b2ebddc4ed4'}, 'app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt': {'sha256LF': '5799bb8802992594ae9494b48d6357ee00ecc7be03d97ed0dcb5fede7774328c', 'gitBlob': '5d24281dc2152c1e2113ab3476418f61261cd15a'}}
def function_range(t,name):
    matches=list(re.finditer(r'(?m)^[ \t]*(?:(?:internal|private|suspend|inline)\s+)*fun\s+(?:[\w.]+\.)?'+re.escape(name)+r'\s*\(',t));assert len(matches)==1,(name,len(matches))
    m=matches[0];tokens=parser.kotlin_tokens(t);i=next(i for i,(_,a,_)in enumerate(tokens) if a>=m.start())
    while tokens[i][0]!='(':i+=1
    depth=1
    while depth:i+=1;depth+=(tokens[i][0]=='(')-(tokens[i][0]==')')
    while tokens[i][0]!='{':i+=1
    depth=1
    while depth:i+=1;depth+=(tokens[i][0]=='{')-(tokens[i][0]=='}')
    return m.start(),tokens[i][2]

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

SOURCES={};OUTPUTS=[];ADAPT=[];REFERENCES=[]
def read(rel):
 path=_desktop_canonical_relative(REPO,BASE+rel)
 if path not in SOURCES:
  p=_desktop_canonical_source(REPO,path);assert not p.is_symlink() and p.resolve().is_relative_to(REPO.resolve()),path
  raw=wide(p).read_bytes().replace(b'\r\n',b'\n');pin=SOURCE_PINS[path];assert sha(raw)==pin['sha256LF'],path+' differs from fixed stable source'
  blob=subprocess.check_output(['git','-c','core.longpaths=true','rev-parse',COMMIT+':'+path],cwd=REPO,text=True).strip()
  current=subprocess.check_output(['git','-c','core.longpaths=true','hash-object','--path='+_desktop_canonical_relative(REPO,path),str(_desktop_canonical_source(REPO,path))],cwd=REPO,text=True).strip()
  assert current==blob==pin['gitBlob'],path+' Git identity differs'
  SOURCES[path]=dict(text=raw.decode(),**pin)
 return SOURCES[path]['text']
def emit(rel,t,origin,mode):
 t=validate_fresh_v025_comment_consumer("com/android/purebilibili/"+rel,t)
 if rel=='feature/video/ui/section/VideoNoteSection.kt':
  t=adapt_v025_note_badge(rel,t);mode='policy-extract'
 direct=mode=='direct-complete-original'
 if STANDALONE or not direct:write(OUTPUT/'com/android/purebilibili'/rel,t)
 OUTPUTS.append(dict(path='com/android/purebilibili/'+rel,origin=BASE+origin,sha256LF=sha(t),mode=mode,generated=STANDALONE or not direct))
def adapt(t,before,after,label):
 assert t.count(before)==1,(label,t.count(before));ADAPT.append(dict(label=label,before=before,after=after));return t.replace(before,after,1)
def remove_function(t,name):
 a,b=function_range(t,name)
 if t[:a].rstrip().endswith('@Composable'):a=t.rfind('@Composable',0,a)
 return t[:a]+t[b:]
def main():
 for rel in ['feature/video/ui/section/AiSummarySection.kt','feature/video/viewmodel/AiSummaryUiPolicy.kt','data/repository/AiSummaryFetchPolicy.kt','feature/video/ui/section/VideoSupplementSection.kt','feature/video/ui/section/VideoNoteSection.kt','feature/video/ui/components/RelatedVideoActionSheet.kt','feature/video/ui/components/RelatedVideoActionPolicy.kt','feature/video/ui/components/RelatedVideoCardLayoutPolicy.kt','feature/video/ui/section/VideoDetailMotionBudgetPolicy.kt','feature/video/ui/components/VideoCommentBackToTopPolicy.kt','feature/video/ui/section/VideoDetailSecondaryButton.kt','feature/video/note/VideoNoteEditorStatePolicy.kt','feature/video/note/VideoNoteEmotionPolicy.kt','feature/video/note/VideoNoteVisibilityPolicy.kt']:
  emit(rel,read(rel),rel,'direct-complete-original')
 rel='feature/video/screen/PortraitDetailPresentationPolicy.kt';t=read(rel)
 a,b=function_range(t,'isVideoDetailIntroScrollPastCollapseThreshold')
 emit('feature/video/screen/DesktopOriginalVideoContentCollapseThreshold.kt','package com.android.purebilibili.feature.video.screen\n'+t[a:b]+'\n',rel,'complete-original-collapse-threshold-function')
 rel='feature/video/ui/components/CommentSortFilterBar.kt';t=read(rel)
 cut=t.index('/**\n * 评论排序分段控件，放置在详情页顶栏')
 header=t[:t.index('internal data class CommentSortSegmentedControlSpec')]
 emit('feature/video/ui/components/DesktopOriginalCommentSortFilterBar.kt',header+t[cut:],rel,'complete-original-remaining-filter-bar-reference-sole-generic-header-and-policies')
 rel='feature/video/screen/VideoNavigationCidPolicy.kt';t=read(rel)
 a,b=function_range(t,'buildVideoNavigationOptions');body=t[a:b]
 body=body.replace('buildVideoNavigationOptions(','buildDesktopOriginalVideoNavigationOptions(').replace('base: Bundle?','base: Pair<Long, String?>?').replace('): Bundle?','): Pair<Long, String?>?')
 body=adapt(body,'''    return Bundle().apply {
        if (base != null) {
            putAll(base)
        }
        if (targetCid > 0L) {
            putLong(VIDEO_NAV_TARGET_CID_KEY, targetCid)
        }
        if (normalizedCover.isNotEmpty()) {
            putString(VIDEO_NAV_COVER_URL_KEY, normalizedCover)
        }
    }''','''    return Pair(
        if (targetCid > 0L) targetCid else base?.first ?: 0L,
        if (normalizedCover.isNotEmpty()) normalizedCover else base?.second
    )''','Original two-key Bundle omission/base merge/cover trim maps primitive typed CID+cover fields, without a second route schema')
 emit('feature/video/screen/DesktopOriginalVideoNavigationOptions.kt','package com.android.purebilibili.feature.video.screen\n'+body+'\n',rel,'complete-original-navigation-builder-two-key-platform-adapt')
 rel='feature/video/ui/components/RelatedVideoItem.kt';t=read(rel)
 t=t.replace('import android.widget.Toast\n','').replace('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration')
 t=t.replace('import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.LocalDesktopOriginalVideoContentBindings as LocalContext\nimport coil3.compose.LocalPlatformContext')
 for name in ['SettingsManager','TodayWatchFeedbackStore']:
  t=t.replace('import com.android.purebilibili.core.store.'+name+'\n','')
 for name in ['ActionRepository','BlockedUpRepository']:
  t=t.replace('import com.android.purebilibili.data.repository.'+name+'\n','')
 t=adapt(t,'internal const val RELATED_VIDEO_CARD_COVER_ASPECT_RATIO = 16f / 10f\n','','Existing BGM skeleton producer remains sole owner of the exact original cover constant')
 t=adapt(t,'''    val homeFeedCardStyle by SettingsManager
        .getHomeFeedCardStyle(context)
        .collectAsStateWithLifecycle(initialValue = HomeFeedCardStyle.BILIPAI)''','''    val homeFeedCardStyle by context.homeSettings.homeFeedCardStyle
        .collectAsStateWithLifecycle()''','Same actual global original Home preferences, with real initial value')
 t=t.replace('ImageRequest.Builder(context)','ImageRequest.Builder(LocalPlatformContext.current)')
 # Composition locals are read during composition, outside remember's noncomposable lambda.
 t=adapt(t,'    val context = LocalContext.current\n    // Keep the request stable','    val context = LocalPlatformContext.current\n    // Keep the request stable','Coil receives actual Windows platform context')
 t=t.replace('ImageRequest.Builder(LocalPlatformContext.current)','ImageRequest.Builder(context)')
 t=adapt(t,'val blockedUpRepository = remember { BlockedUpRepository.getInstance(context) }','val blockedUpRepository = context.blockedUps','Reuse the owned Root global block repository port, never a second instance')
 t=t.replace('ActionRepository.toggleWatchLater(pendingVideo.aid, true)','context.toggleWatchLater(pendingVideo.aid, true)')
 t=re.sub(r'Toast\.makeText\(\s*context,\s*([^,]+),\s*Toast\.LENGTH_SHORT\s*\)\.show\(\)',r'context.feedback(\1)',t)
 t=t.replace('context: android.content.Context','context: com.bilipai.desktop.ui.DesktopOriginalVideoContentBindings')
 t=t.replace('TodayWatchFeedbackStore.getSnapshot(context)','context.getFeedbackSnapshot()').replace('TodayWatchFeedbackStore.saveSnapshot(context, snapshot)','context.saveFeedbackSnapshot(snapshot)')
 t=adapt(t,'                                onVideoHidden?.invoke(blockRequest.video)','''                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                context.requireCurrent()
                                onVideoHidden?.invoke(blockRequest.video)''','Reject retired/canceled late block result before original UI callbacks')
 for variable in ['pendingVideo','blockRequest.video','request.video']:
  t=t.replace('onVideoHidden?.invoke('+variable+')','context.commitUi { onVideoHidden?.invoke('+variable+') }')
 start=t.index('                            scope.launch {\n                                val result = withContext')
 end=t.index('                                blockCreatorRequest = null\n',start)+len('                                blockCreatorRequest = null\n')
 before=t[start:end]
 after=before.replace('                            scope.launch {\n','                            scope.launch {\n                                try {\n',1)+'''                                } finally {
                                    context.cleanupUi {
                                        if (blockCreatorRequest === blockRequest) {
                                            isBlockingCreator = false
                                            blockCreatorRequest = null
                                        }
                                    }
                                }
'''
 t=adapt(t,before,after,'Canceled/failed block work releases only its own captured busy request while same detail owner remains alive')
 t=t.replace('import kotlinx.coroutines.Dispatchers\n','import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.ensureActive\n')
 t=adapt(t,"    onMoreClick: (() -> Unit)? = null\n","    onMoreClick: (() -> Unit)? = null,\n    onUpClick: (() -> Unit)? = null\n",'Optional Windows UP navigation at the original full card boundary; old callers retain null')
 t=adapt(t,"                            modifier = Modifier.weight(1f),\n                        )\n","                            modifier = Modifier.weight(1f)\n                                .then(onUpClick?.let { Modifier.clickable(onClick = it) } ?: Modifier),\n                        )\n",'The original UP identity slot alone receives the optional nested click')
 t=adapt(t,"    onVideoClick: (RelatedVideo) -> Unit,\n    onVideoHidden: ((RelatedVideo) -> Unit)? = null,\n","    onVideoClick: (RelatedVideo) -> Unit,\n    onUpClick: ((RelatedVideo) -> Unit)? = null,\n    showActions: Boolean = true,\n    onVideoHidden: ((RelatedVideo) -> Unit)? = null,\n",'Existing original grid receives optional UP callback and explicit action capability; old defaults unchanged')
 t=adapt(t,"                onMoreClick = { actionVideo = video }\n","                onMoreClick = if (showActions) { { actionVideo = video } } else null,\n                onUpClick = onUpClick?.let { callback -> { callback(video) } },\n",'Forward original UP identity and expose menu only when owned mutations are available')
 t=adapt(t,"    if (pendingVideo != null) {\n","    if (pendingVideo != null && showActions) {\n",'An unowned action sheet must not remain visible when action capability is removed')
 t=adapt(t,"    if (blockRequest != null) {\n","    if (blockRequest != null && showActions) {\n",'An unowned block dialog must not remain visible when action capability is removed')
 emit('feature/video/ui/components/DesktopOriginalRelatedVideoItem.kt',t,rel,'complete-original-related-card-grid-feedback-actions-owner-platform-adapt')
 rel='feature/video/screen/VideoContentSection.kt';t=read(rel);t=remove_function(t,'VideoCommentTab')
 t=t.replace('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration').replace('import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.LocalDesktopOriginalPlayerSettingsContext as LocalContext\nimport com.bilipai.desktop.ui.LocalDesktopOriginalVideoContentBindings')
 t=t.replace('import com.android.purebilibili.core.util.ShareUtils\n','').replace('import com.android.purebilibili.core.store.SettingsManager','import com.android.purebilibili.core.store.DesktopOriginalVideoContentSettings as SettingsManager')
 t=t.replace('com.android.purebilibili.core.store.SettingsManager','SettingsManager')
 t=t.replace('(String, android.os.Bundle?) -> Unit','(String, Long, String?) -> Unit')
 t=t.replace('onRelatedVideoClick: (String, Long, String?) -> Unit = { _, _ -> }','onRelatedVideoClick: (String, Long, String?) -> Unit = { _, _, _ -> }')
 t=adapt(t,'    val onShareVideoNote:','    val platform = LocalDesktopOriginalVideoContentBindings.current\n    val onShareVideoNote:','Capture existing owner bindings during composition')
 t=adapt(t,'''ShareUtils.shareText(
            context = context,
            subject = document.title.ifBlank { info.title },''','''platform.shareText(
            subject = document.title.ifBlank { info.title },''','Same Root typed native actor receives original note subject and text')
 t=adapt(t,'''),
            chooserTitle = "分享视频笔记"
        )''',''')
        )''','Android chooser caption has no separate Windows native actor parameter')
 t=adapt(t,'''                        onRelatedVideoClick(
                            episode.bvid,
                            buildVideoNavigationOptions(targetCid = episode.cid)
                        )''','''                        val navigationOptions = buildDesktopOriginalVideoNavigationOptions(targetCid = episode.cid)
                        onRelatedVideoClick(episode.bvid, navigationOptions?.first ?: 0L, navigationOptions?.second)''','Original collection callback preserves original CID omission through same typed Root fields')
 t=adapt(t,'''                        val navOptions = buildVideoNavigationOptions(
                            targetCid = video.cid,
                            coverUrl = video.pic
                        )
                        onRelatedVideoClick(video.bvid, navOptions)''','''                        val navOptions = buildDesktopOriginalVideoNavigationOptions(
                            targetCid = video.cid,
                            coverUrl = video.pic
                        )
                        onRelatedVideoClick(video.bvid, navOptions?.first ?: 0L, navOptions?.second)''','Preserve original CID omission and cover trimming through the two typed Root fields')
 t=adapt(t,'''            onRelatedVideoClick = onRelatedVideoClick,
            animateLayout = animateVideoDetailLayout,''','''            onRelatedVideoClick = { bvid, cid -> onRelatedVideoClick(bvid, cid, null) },
            animateLayout = animateVideoDetailLayout,''','Existing complete original info renderer callback consumes CID; rich recommended callback retains cover separately')
 t=t.replace('com.android.purebilibili.core.ui.animation.MaybeDissolvableVideoCard(','com.bilipai.desktop.ui.DesktopReplyDissolvableContainer(')
 emit('feature/video/screen/DesktopOriginalVideoContentSection.kt',t,rel,'complete-original-content-groups-body-all-panels-reference-sole-commenttab-platform-adapt')
 rel='core/store/SettingsManager.kt';t=read(rel);manager=t[t.index('object SettingsManager {')+len('object SettingsManager {'):t.rfind('}')]
 body,names=member_closure(manager,['getShowVideoDetailCommentCount','getVideoDetailChromeScrollHideEnabled','getVideoAiSummaryEntryEnabled','getVideoNoteEnabled'])
 body=body.replace('private val','private val')
 header='''package com.android.purebilibili.core.store
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import com.bilipai.desktop.ui.playerBooleanPreferencesKey as booleanPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
object DesktopOriginalVideoContentSettings {
'''
 emit('core/store/DesktopOriginalVideoContentSettings.kt',header+body+'\n}\n',rel,'selected-exact-original-read-getters-keys-same-global-settings')
def generate(repo,output,standalone=False):
 global REPO,OUTPUT,STANDALONE,parser,selector,SOURCES,OUTPUTS,ADAPT,REFERENCES
 REPO=Path(repo).resolve();OUTPUT=Path(output).resolve();STANDALONE=standalone
 SOURCES={};OUTPUTS=[];ADAPT=[];REFERENCES=[]
 manifest=json.loads(wide(REPO/'desktop/upstream-sources.json').read_text(encoding='utf-8'))
 assert manifest['upstreamCommit']==COMMIT
 records={r['path']:r['sha256']for r in manifest['sources']}
 for path,pin in SOURCE_PINS.items():
  if path in records:assert records[path]==pin['sha256LF'],path+' existing canonical identity differs'
 parser=module('content_tokens',REPO/'desktop/tools/sync-upstream.py')
 selector=module('content_decls',REPO/'desktop/tools/extract-appearance-platform.py')
 main()
if __name__=='__main__':generate(Path(sys.argv[1]),Path(sys.argv[2]),'--standalone' in sys.argv[3:])
