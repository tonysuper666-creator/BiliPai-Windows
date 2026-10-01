from pathlib import Path
import hashlib,importlib.util,json,sys,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('tools',HERE/'compile.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
def main():
 target=HERE/'frozen-handoff.json';assert not c.safe(target).exists();checks=[]
 def check(name,value):assert value,name;checks.append(dict(name=name,pass_=True))
 plan=json.loads(c.safe(HERE/'patch-plan.json').read_text(encoding='utf-8'))
 for row in plan['files']:
  before=c.safe(row['baseCopy']).read_text(encoding='utf-8');after=c.safe(row['candidate']).read_text(encoding='utf-8')
  for h in row['hunks']:
   check('unique hunk '+row['path']+' '+h['before'][:32],before.count(h['before'])==1);before=before.replace(h['before'],h['after'],1)
  check('exact only declared delta '+row['path'],before==after)
  if row['install']:
   check('exact compiler bytes '+row['path'],c.sha(HERE/'compile-01/source-inputs'/row['path'])==row['candidateSha256LF'])
 check('BGM hunk is review only',not next(x for x in plan['files'] if x['path'].endswith('DesktopBgmDetailRoot.kt'))['install'])
 helper=HERE/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopTextShareBindings.kt'
 check('exact helper compiler bytes',c.sha(helper)==c.sha(HERE/'compile-01/source-inputs/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopTextShareBindings.kt'))
 candidate=HERE/'compile-01/prepared-text-share-bindings.jar'
 with zipfile.ZipFile(c.safe(candidate)) as z:own={x for x in z.namelist() if x.endswith('.class')}
 with zipfile.ZipFile(c.safe(c.SNAP/'main-kotlin.jar')) as z:actual={x for x in z.namelist() if x.endswith('.class')}
 overlaps=sorted(own&actual)
 allowed=['DesktopOriginalDynamicCardHostKt','DesktopDynamicCommentPlatform','CommunityDynamicScreensKt','DesktopSpaceImagePreviewsKt','DesktopSpaceAvatarPlatform','ComposableSingletons$CommunityDynamicScreensKt','ComposableSingletons$DesktopOriginalDynamicCardHostKt']
 check('only declared platform/caller families overlap',all(any(x.split('/')[-1].startswith(y) for y in allowed) for x in overlaps))
 check('no native actor/Operations/store/model/cache override',all(not x.startswith(('com/bilipai/desktop/diagnostics/','com/bilipai/desktop/data/','com/bilipai/desktop/plugins/','com/android/')) for x in own))
 c.save(HERE/'compile-01/class-overlap-review.json',dict(status='PASS',compilerLogSha256Bytes=c.sha(HERE/'compile-01/compiler.log'),artifactSha256Bytes=c.sha(candidate),declaredOverlapCount=len(overlaps),declaredOverlaps=overlaps,undeclaredOverlaps=[],history='Kotlin compilation PASS. First checker omitted two generated ComposableSingletons classes belonging exactly to declared source overrides; this independent byte audit corrects metadata without recompile or changing jar/source inputs.'))
 proof=json.loads(c.safe(HERE/'proof-01/accepted-result.json').read_text(encoding='utf-8'));raw=json.loads(c.safe(HERE/'proof-01/scratch/result.json').read_text(encoding='utf-8'))
 check('focused dispatcher proof passed',proof['status']=='PASS' and proof['caseCount']==5 and proof['assertions']==15)
 for row in raw['actualCodeSources']:
  jar=c.SNAP/'main-kotlin.jar' if row['class'].endswith('DesktopNativeTextShare') else candidate
  with zipfile.ZipFile(c.safe(jar)) as z:bytes_=z.read(row['class'].replace('.','/')+'.class')
  check('actual class bytes '+row['class'],hashlib.sha256(bytes_).hexdigest()==row['sha256ClassBytes'])
 c.save(HERE/'source-audit.json',dict(status='PASS',checkCount=len(checks),checks=checks,sourceInventorySha256Bytes=c.sha(HERE/'source-inventory.json'),patchPlanSha256Bytes=c.sha(HERE/'patch-plan.json'),classOverlapReviewSha256Bytes=c.sha(HERE/'compile-01/class-overlap-review.json'),acceptedProofSha256Bytes=c.sha(HERE/'proof-01/accepted-result.json')))
 rows=[]
 for p in sorted(c.safe(HERE).rglob('*')):
  if p.is_file():rows.append(dict(path=p.relative_to(c.safe(HERE)).as_posix(),sha256Bytes=c.sha(p),bytes=p.stat().st_size))
 install=[x['path'] for x in plan['files'] if x['install']]+['desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopTextShareBindings.kt']
 c.save(target,dict(frozen=True,artifactCount=len(rows),artifacts=rows,installPayload=install,applyAsMinimalHunks=True,noWholeShellOverwrite=True,bgmVideoCommonCallersOwnedByVideoAgent=True,actualMain15ManifestSha256Bytes=c.sha(c.SNAP/'manifest.json'),actualCp92Sha256Bytes=c.sha(c.SNAP/'ordered-runtime-cp.json'),proof=dict(cases=5,assertions=15,codeSources=3,nativeActorInvoked=False,nativePaneOpened=False,noAccountReads=True,noHTTP=True,noHWND=True,noMainEdits=True,noSharedGradle=True),existingActorBoundary='Existing successful Show watcher has no page-owner recheck. This slice preserves admission/awaiting cancellation and makes no post-Show immediate-retirement claim. Root informed.'))
 print(json.dumps(dict(path=str(target),artifacts=len(rows),sha256Bytes=c.sha(target),auditSha256Bytes=c.sha(HERE/'source-audit.json'))))
if __name__=='__main__':main()
