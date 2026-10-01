"""Migrate only the unique card extractor's stable preview+URL-policy slice.
The probe executes this same retained producer block against pinned Git blobs;
it is not a second hand-maintained renderer implementation.
"""
from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,difflib
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
ROOT=next(p for p in HERE.parents if (p/'.git').exists())
REVIEW=ROOT/'desktop/.local/stable-image-preview-source-review'
COMP='app/src/main/java/com/android/purebilibili/feature/dynamic/components/'
def safe(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,text):
    safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(text,encoding='utf-8',newline='\n')
def save(p,obj):write(p,json.dumps(obj,ensure_ascii=False,indent=2)+'\n')
def load(name,path):
    spec=importlib.util.spec_from_file_location(name,path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
assert sha(REVIEW/'evidence-manifest.json')=='aad91096ddab1ff993737dab50caa3fdf4b9a46bde46da1b0fe0a738f395dc51'
baseline=safe(REVIEW/'current-main04-extract-upstream-dynamic-card.py').read_text(encoding='utf-8')
candidate=baseline
old=" emit(p,'package com.android.purebilibili.feature.dynamic.components\\n'+fun(s,'normalizeImageUrl')+'\\n'+fun(s,'resolveImageShareMimeType')+'\\n','DesktopOriginalImageUrlPolicy.kt')"
new=" emit(p,'package com.android.purebilibili.feature.dynamic.components\\n'+fun(s,'normalizeImageUrl')+'\\n'+fun(s,'resolveImagePreviewPlaceholderCacheKey')+'\\n'+fun(s,'resolveImageShareMimeType')+'\\n','DesktopOriginalImageUrlPolicy.kt')"
assert candidate.count(old)==1;candidate=candidate.replace(old,new)
old=" body=s[:s.index('// 辅助数据类')]"
new=""" # Stable removes the obsolete Quad footer. Select the complete renderer prefix
 # before the URL-policy functions, retaining every original preview declaration.
 body=s[:s.index('/**\\n *  规范化图片 URL')]
 # Android navigation-bar animation has no existing desktop equivalent. Its
 # Activity/window lifecycle is removed below, so remove only this new helper.
 body=replace(body,fun(s,'animateWindowNavigationBarColor'),'')"""
assert candidate.count(old)==1;candidate=candidate.replace(old,new)
old=" quad_start=s.index('data class Quad(');quad_end=s.index('\\n\\n',quad_start)\n body+='\\n'+s[quad_start:quad_end]+'\\n'\n"
assert candidate.count(old)==1;candidate=candidate.replace(old,'')
old=""" body=replace(body,decl(body,'ImagePreviewBlurEffectCache'),'''private class ImagePreviewBlurEffectCache {
    private val effects = mutableMapOf<Int, androidx.compose.ui.graphics.RenderEffect>()
    fun resolve(radiusPx: Float): androidx.compose.ui.graphics.RenderEffect? {
        if (radiusPx <= 0.01f) return null
        val radiusKey=radiusPx.toInt().coerceAtLeast(1)
        return effects.getOrPut(radiusKey){androidx.compose.ui.graphics.BlurEffect(radiusKey.toFloat(),radiusKey.toFloat(),androidx.compose.ui.graphics.TileMode.Clamp)}
    }
}''')
"""
assert candidate.count(old)==1;candidate=candidate.replace(old,'')
destination=HERE/'prepared/desktop/tools/extract-upstream-dynamic-card.py'
write(destination,candidate)
write(HERE/'producer.patch',''.join(difflib.unified_diff(baseline.splitlines(True),candidate.splitlines(True),
    fromfile='a/desktop/tools/extract-upstream-dynamic-card.py',tofile='b/desktop/tools/extract-upstream-dynamic-card.py')))
start=baseline.index(" p=COMP+'ImagePreviewDialog.kt';s=read(repo,p)")
end=baseline.index(' # HapticType is now already produced',start)
other_before=baseline[:start];other_after=baseline[end:]
new_start=candidate.index(" p=COMP+'ImagePreviewDialog.kt';s=read(repo,p)")
new_end=candidate.index(' # HapticType is now already produced',new_start)
assert other_before==candidate[:new_start] and other_after==candidate[new_end:]

module=load('stable_preview_unique_candidate',destination)
originals={}
def pinned_read(repo,path):
    if path not in originals:
        raw=subprocess.check_output(['git','-C',str(ROOT),'show','v0.2.3:'+path])
        text=raw.decode('utf-8').replace('\r\n','\n');originals[path]=text
        write(HERE/'original-v023'/path,text)
    return originals[path]
module.read=pinned_read
# Replay the exact shared prelude and exact preview block from the candidate's
# unique generate function. Other card selections are Root's separate rebase.
function_start=candidate.index('def generate(repo,output,standalone=False,shared_closure=False):')
prelude_end=candidate.index(' for p in DIRECT:',function_start)
probe_source=candidate[function_start:prelude_end]+candidate[new_start:new_end]+' return files\n'
probe_source=probe_source.replace('def generate(repo,output,standalone=False,shared_closure=False):',
    'def preview_probe(repo,output,standalone=False,shared_closure=False):',1)
write(HERE/'probe-same-producer-block.py',probe_source)
exec(compile(probe_source,str(HERE/'probe-same-producer-block.py'),'exec'),module.__dict__)
module.preview_probe(ROOT,HERE/'generated')
for name in ['ImagePreviewSourceAnchor','ImagePreviewTransitionPolicy','ImagePreviewDecodePolicy','ZoomableImage']:
    path=COMP+name+'.kt';write(HERE/'direct-original'/path,pinned_read(ROOT,path))
save(HERE/'preparation-contract.json',dict(schema='prepared-v023-unique-preview-producer-delta-v1',preparedOnly=True,
    stableTag='v0.2.3',stableCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
    sourceReviewManifestSha256Bytes=sha(REVIEW/'evidence-manifest.json'),
    originalMain04ProducerSha256Lf=hashlib.sha256(baseline.encode()).hexdigest(),candidateProducerSha256Bytes=sha(destination),
    producerChangesOnlyWithinExistingPreviewSelection=True,allOtherCardProducerTextByteEqual=True,
    narrowProbeUsesExactCandidatePreludeAndPreviewBlock=True,
    newOriginalDeclarations=['resolveImagePreviewPlaceholderCacheKey'],
    removedObsoleteAlpha9Selections=['Quad footer marker/type','ImagePreviewBlurEffectCache platform replacement'],
    declaredAndroidBoundary=['Original animateWindowNavigationBarColor(Window?,ValueAnimator) removed; existing Activity/navigation/window block already excluded.',
        'Existing desktop OverlayHost remains unique; Android predictive scrub provider remains explicitly unavailable in this slice.'],
    stableOriginals=[dict(path=p,sha256Lf=hashlib.sha256(s.encode()).hexdigest()) for p,s in sorted(originals.items())],
    selectedDirectDependencies=['ImagePreviewSourceAnchor','ImagePreviewTransitionPolicy','ImagePreviewDecodePolicy','ZoomableImage'],
    probeBoundary='Preview/URL-policy source closure only. Root migrates all other full-card/caller extractors and recompiles their existing owners.',
    MainChanged=False,sourceRegistryChanged=False,sharedGradle=False,HTTP=False,HWND=False))
print(json.dumps(dict(producer=str(destination),producerSha256Bytes=sha(destination),originals=len(originals))))
