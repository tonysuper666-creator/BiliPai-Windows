from pathlib import Path
import hashlib,json,subprocess,zipfile,os
P=Path(__file__).resolve().parent
MAIN=P.parents[2]
C=MAIN.parent/'BiliPai-v023'
UPSTREAM='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
BASE='app/src/main/java/com/android/purebilibili/'
SNAP=MAIN/'desktop/.local/stable-product-snapshot-84'
PINS={'manifest.json':'f70a306e3dc42df93c00d536163ff4a1f1953cbc94d36586c9500281983351b2',
 'ordered-runtime-cp.json':'4b1de9cce18526162113e1336c0252b37b3113d1f0b8832b68953fc3ec5df8d9',
 'main-kotlin.jar':'9c2981b40f2daecd090f23992871af522f6044cf6e06b4c65b303bde316d6139',
 'main-java.jar':'e0929361bd1be79e4fe9d6fee2e98e08cc54aa2af6772ba8b101db85ee2371fa',
 'main-resources.jar':'f15807dfa86ba838449ea34db04c9f606c17b3a6c3106e9ab6e94d1504812def'}
def sha(b):return hashlib.sha256(b).hexdigest()
def wide(p):
 value=os.path.abspath(str(p));return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
for n,h in PINS.items():assert sha((SNAP/n).read_bytes())==h,n
cp=json.loads((SNAP/'ordered-runtime-cp.json').read_text(encoding='utf-8'))
assert len(cp)==101
for entry in cp:assert sha(wide(entry['path']).read_bytes())==entry['sha256Bytes']
paths=subprocess.check_output(['git','-C',str(C),'ls-tree','-r','--name-only',UPSTREAM,
 BASE+'feature/bangumi',BASE+'core/player/BasePlayerViewModel.kt',BASE+'data/repository/BangumiRepository.kt',
 BASE+'data/repository/BangumiReviewRepository.kt',BASE+'navigation/AppNavigation.kt',
 BASE+'core/ui/skeleton/ContentLoadingSkeletons.kt'],text=True).splitlines()
rows=[]
for rel in paths:
 data=subprocess.check_output(['git','-C',str(C),'show',UPSTREAM+':'+rel])
 out=P/'original'/rel
 out.parent.mkdir(parents=True,exist_ok=True)
 out.write_bytes(data)
 text=data.decode('utf-8')
 rows.append(dict(path=rel,sha256LF=sha(data.replace(b'\r\n',b'\n')),lines=len(text.splitlines()),
     androidImports=[l for l in text.splitlines() if l.startswith('import ') and any(x in l for x in ('android.','media3.','LocalContext','LocalConfiguration','LocalView'))]))
metadata=dict(upstreamCommit=UPSTREAM,candidateBase='8c1970119ffeb5625c7bc7ed5d7a77c1558e52fa',
 rootIssuedSnapshot=84,snapshotPins=PINS,runtimeEntryCount=len(cp),originalSources=rows,
 candidateWrites=0,buildsExecuted=0,playerOwner='existing OriginalVideoAssembly/Mpv/Section/Carrier/SessionStore only',
 completeOriginalBodiesRequired=True,newHttpClientOrStoreOrMpvPermitted=False)
(P/'input-inventory.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
product=next(e['path'] for e in cp if e.get('source')=='desktop/build/classes/kotlin/main')
with zipfile.ZipFile(product) as z:
 names=[n for n in z.namelist() if any(x in n for x in ('Bangumi','BasePlayerViewModel','MpvSectionControl','ProgressManager')) and '$' not in n]
(P/'existing-product-types.json').write_text(json.dumps(names,indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(inputCount=len(rows),snapshotEntries=101,inputsPinned=True,
 sourceLines=sum(r['lines'] for r in rows),types=names),ensure_ascii=False))
