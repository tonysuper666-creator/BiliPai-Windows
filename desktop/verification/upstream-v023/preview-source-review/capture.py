"""Pinned stable preview delta and current extractor anchor audit; no Main/build writes."""
from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
ROOT=next(p for p in HERE.parents if (p/'.git').exists())
def safe(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,text):
    safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(text,encoding='utf-8',newline='\n')
def save(p,obj):write(p,json.dumps(obj,ensure_ascii=False,indent=2)+'\n')
def load(name,path):
    spec=importlib.util.spec_from_file_location(name,path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
manifest=HERE/'evidence-manifest.json';assert not safe(manifest).exists()
tags=['v0.2.3-alpha.9','v0.2.3']
commits={tag:subprocess.check_output(['git','-C',str(ROOT),'rev-parse',tag+'^{commit}']).decode().strip() for tag in tags}
assert commits['v0.2.3']=='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
base='app/src/main/java/com/android/purebilibili/'
component=base+'feature/dynamic/components/'
paths=[component+n+'.kt' for n in ['ImagePreviewDialog','ImagePreviewSourceAnchor','ImagePreviewTransitionPolicy','ImagePreviewDecodePolicy','ImagePreviewFeedbackPolicy','ZoomableImage','DrawGrid','DynamicCard']]+[base+'feature/space/SpaceScreen.kt']
inputs=[];texts={}
for tag in tags:
    for path in paths:
        raw=subprocess.check_output(['git','-C',str(ROOT),'show',tag+':'+path]);text=raw.decode('utf-8').replace('\r\n','\n')
        target=HERE/'original'/tag/path;write(target,text);texts[(tag,path)]=text
        inputs.append(dict(tag=tag,commit=commits[tag],path=path,sha256Bytes=hashlib.sha256(raw).hexdigest(),sha256Lf=hashlib.sha256(text.encode()).hexdigest(),lines=len(text.splitlines())))
for path in paths:
    patch=subprocess.check_output(['git','-C',str(ROOT),'diff','--no-ext-diff',tags[0],tags[1],'--',path]).decode('utf-8')
    write(HERE/'diffs'/(Path(path).stem+'.patch'),patch)
producer=ROOT/'desktop/tools/extract-upstream-dynamic-card.py'
write(HERE/'current-main04-extract-upstream-dynamic-card.py',safe(producer).read_text(encoding='utf-8').replace('\r\n','\n'))
old=texts[(tags[0],component+'ImagePreviewDialog.kt')];stable=texts[(tags[1],component+'ImagePreviewDialog.kt')]
anchors=[]
for token in ['// 辅助数据类','data class Quad(','ImagePreviewBlurEffectCache','            val dialogView = LocalView.current','    //  获取 Activity 和 Window','    //  动画状态控制','    var pendingSaveAction','    // 当前页的图片 URL','    val backEventState =']:
    anchors.append(dict(anchor=token,alpha9Count=old.count(token),stableCount=stable.count(token)))
parser=load('stable_source_review_parser',ROOT/'desktop/tools/sync-upstream.py')
media=load('stable_source_review_function',ROOT/'desktop/tools/extract-upstream-media.py')
unchanged=[]
for name in ['saveImageToGallery','saveMotionPhotoToGallery','saveLivePhotoVideoToGallery','normalizeImageUrl','resolveImageShareMimeType']:
    a=media.function(old,name,parser);b=media.function(stable,name,parser)
    unchanged.append(dict(function=name,bodyByteEqual=a==b,alpha9Sha256=hashlib.sha256(a.encode()).hexdigest(),stableSha256=hashlib.sha256(b.encode()).hexdigest()))
assert all(row['bodyByteEqual'] for row in unchanged)
save(HERE/'review.json',dict(schema='v023-stable-preview-source-delta-review-v1',sourceOnly=True,
    tags=commits,sourceRows=inputs,currentProducerSha256Bytes=sha(producer),anchorAudit=anchors,
    unchangedSaveAndUrlBodies=unchanged,
    mustAdoptStableBehavior=[
        dict(source='ImagePreviewDialog.kt',lines='180–287,295–329,520–529,729–785',
            behavior='Source identity key, staged source transition, committed-request hide published by first overlay SideEffect, and reveal source before removal with two display-frame handoff. Request admission changes from LaunchedEffect to DisposableEffect. Source hide now favors stable URL identity over rect geometry.'),
        dict(source='ImagePreviewDialog.kt',lines='461–470,697–719,735–784,872–982',
            behavior='Freeze open display rect and dismiss source/starting progress; clip rect flight while pager counters nonuniform scaling and uses per-axis counter-scaled round corners. Reset zoom to fit before dismissal, observe it up to450ms, retain rect/no-rect content alpha behavior.'),
        dict(source='ImagePreviewDialog.kt / ZoomableImage.kt',lines='1089–1183 /49–60,94–125,238–263,318–322',
            behavior='Vertical-dismiss drag callback now Offset, ends with releaseVelocityY Float and resetZoomTrigger Int. Horizontal drift follows the finger, both axes spring back; original velocity EMA0.6/0.4 and cap±3000 are retained.'),
        dict(source='ImagePreviewTransitionPolicy.kt',lines='18–21,201–231,304–322,413–472',
            behavior='Open300ms easeOut, opaque hero, critically damped close Spring.StiffnessMediumLow with velocity, linear quantized blur policy, no-anchor dismiss fade, earlier chrome fade; counter-scale corner math and gradual3D/live-alpha policies. Retain original equations and constants.'),
        dict(source='ImagePreviewDialog.kt / ImagePreviewDecodePolicy.kt',lines='1042–1069,2201–2212 /15–18',
            behavior='New resolveImagePreviewPlaceholderCacheKey normalizes scheme while retaining sized URL suffix as source memory identity; preview uses placeholderMemoryCacheKey and crossfade(false). Original-quality cap changes8192→4608. No new allowHardware flag was observed.'),
        dict(source='DrawGrid.kt / SpaceScreen.kt / DynamicCard.kt',lines='168–213 /748–757,873–881,2804–2828 /1437,1567',
            behavior='Callers must stage transition on click and carry sourceKey (grid URL where present); thumbnail memoryCacheKey matches placeholder and crossfade(false). Space avatar rawURL/single-image/corner/save defaults remain, but click now prepares transition and thumbnail cache identity.'),
        dict(source='ImagePreviewDialog.kt',lines='1217–1240',behavior='Live photo composes only after progress0.7 and fades in by original resolveImagePreviewLivePhotoAlpha; preserve real desktop playback platform seam and do not claim native receiver behavior.')],
    immediateProducerBlockers=[
        dict(line=198,issue="s.index('// 辅助数据类') fails: marker removed in stable."),
        dict(line=203,issue="s.index('data class Quad(') fails: Quad removed."),
        dict(line=206,issue="decl(ImagePreviewBlurEffectCache) fails: class removed, replaced by pure CounterScaledCornerShape."),
        dict(line=197,issue='URL policy omits new original resolveImagePreviewPlaceholderCacheKey, referenced by new renderer and source thumbnails.'),
        dict(line=199,issue='After Android imports are filtered, new animateWindowNavigationBarColor(Window?, ValueAnimator) remains in renderer prefix; must remove this explicit Android-only helper with its already removed Activity/window lifecycle.'),
        dict(line=None,issue='New stable renderer cannot be combined with old direct ZoomableImage callback signatures or old Transition/Anchor/Decode files. Update their unique original owners; do not add duplicate FQNs.')],
    minimalSelectedClosure=['Existing unique card extractor preview+URLpolicy outputs',
        'Unique direct ImagePreviewSourceAnchor.kt','Unique direct ImagePreviewTransitionPolicy.kt',
        'Unique direct ImagePreviewDecodePolicy.kt','Unique direct ZoomableImage.kt',
        'Existing selected DrawGrid/DynamicCard callers','Original selected Space avatar caller + Root owned facade'],
    platformBoundary=['Android Window.navigationBarColor/ValueAnimator, FLAG_DIM_BEHIND and Window animations have no existing desktop equivalent in this slice; do not retain unresolved Android calls or pretend the effect ran.',
        'Existing root OverlayHost/Compose Dialog remain unique; preserve its dismissal triggers and captured owned platform. Android predictive scrub still requires an explicit platform provider and cannot be claimed by old constant0.',
        'Existing Assets/global prefs/cancellation/source staging and three save helper bodies are unchanged in stable, so their frozen proofs remain historical alpha9 input evidence until actual stable Main is recompiled and reverified.'],
    boundaries=dict(MainChanged=False,GradleInvoked=False,producerExecuted=False,compiled=False,testsExecuted=False,
        HTTP=False,HWND=False,oldMain05FixtureStarted=False,stableMainInstalled=False)))
rows=[]
for p in sorted(safe(HERE).rglob('*')):
    if p.is_file():rows.append(dict(path=str(p.relative_to(safe(HERE))).replace('\\','/'),sha256Bytes=sha(p),byteCount=p.stat().st_size))
save(manifest,dict(schema='frozen-v023-stable-preview-source-review-v1',frozen=True,sourceOnly=True,
    review='review.json',reviewSha256Bytes=sha(HERE/'review.json'),stableTag='v0.2.3',stableCommit=commits['v0.2.3'],
    artifactCount=len(rows),artifacts=rows))
for row in rows:assert sha(HERE/row['path'])==row['sha256Bytes']
print(json.dumps(dict(manifest=str(manifest),sha256Bytes=sha(manifest),artifacts=len(rows))))
