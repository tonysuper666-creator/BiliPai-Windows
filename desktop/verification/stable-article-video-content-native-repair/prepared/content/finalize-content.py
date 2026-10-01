from pathlib import Path
import hashlib,json,subprocess
P=Path(__file__).resolve().parent;REPO=P.parents[2].parent/'BiliPai-v023'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def digest(b):return hashlib.sha256(b).hexdigest()
def save(p,value):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes((json.dumps(value,ensure_ascii=False,indent=2)+'\n').encode())
inv=json.loads(read(P/'content-source-selection.json'))
assert len(inv['sources'])==len(inv['outputs'])==20
compiled=json.loads(read(P/'content-runs/06/compile-result.json'));assert compiled['passed'] and compiled['sources']==22
symbols=json.loads(read(P/'content-runs/06/symbol-audit.json'));assert symbols['passed']
production=json.loads(read(P/'content-production-proof-03/result.json'));assert production['passed']
registry=REPO/'desktop/upstream-sources.json';manifest=json.loads(read(registry));existing={r['path']:r for r in manifest['sources']}
delta=[]
for source in inv['sources']:
 raw=subprocess.check_output(['git','show',COMMIT+':'+source['path']],cwd=REPO).replace(b'\r\n',b'\n')
 assert digest(raw)==source['sha256LF']
 rows=[r for r in inv['outputs'] if r['origin']==source['path']]
 current=existing.get(source['path']);direct=all(r['mode']=='direct-complete-original'for r in rows)
 if current:assert current['sha256']==source['sha256LF']
 delta.append(dict(path=source['path'],sha256=source['sha256LF'],gitBlob=source['gitBlob'],feature='stable-video-content-full',proposedMode=current['mode']if current else('direct'if direct else'policy-extract'),existingMode=current['mode']if current else None,outputs=[r['path']for r in rows],mergeOnly=True))
save(P/'content-registry-delta.json',dict(commit=COMMIT,identities=len(delta),mergeExistingIdentityFeatureUnion=True,doNotReplaceRegistry=True,observedRegistrySha256Bytes=digest(read(registry)),sources=delta))
tools=[dict(path=str(p.relative_to(wide(P))).replace('\\','/'),sha256LF=digest(read(p).replace(b'\r\n',b'\n')))for p in sorted(wide(P/'prepared/desktop/tools').glob('*.py'))]
manual=[dict(path=str(p.relative_to(wide(P))).replace('\\','/'),sha256LF=digest(read(p).replace(b'\r\n',b'\n')))for p in sorted(wide(P/'prepared/manual').rglob('*.kt'))]
direct=[r for r in inv['outputs']if r['mode']=='direct-complete-original'];selected=[r for r in inv['outputs']if r not in direct]
save(P/'CONTENT-INSTALL-RECIPE.json',dict(stage='Complete original VideoContentSection/related actions/AI/note UI closure; original PlayerSection/StateHolder assembly remains next',commit=COMMIT,baseline='immutable actual47/97 plus explicitly prospective frozen Stage1/Stage2; rich editor official JVM artifact is an additional compiler-only dependency',generator='generate(repo,output,standalone=False)',soleTools=tools,generatedDirectory='build/generated/original-video-content-full',directCopyOnce=direct,selectedOutputs=selected,manualPayloads=manual,registryDelta='content-registry-delta.json',compilerEvidence='content-runs/06/compile-result.json',symbolEvidence='content-runs/06/symbol-audit.json',sourceProof='content-production-proof-03/result.json',rootContract='CONTENT-ROOT-CONTRACT.md',dependency=dict(originalCoordinate='com.mohamedrejeb.richeditor:richeditor-compose:1.0.0-rc14',jvmArtifact='com.mohamedrejeb.richeditor:richeditor-compose-desktop:1.0.0-rc14',jarSha256Bytes='c4d49d810294afa86d4335b058e7ee9cc4ddb875ec08355354bc77f8c917c8bb',runtimeGraphMustBeResolvedByRoot=True),noWholeControllerOpsListenShellPayload=True,acceptance='SOURCE-ONLY; no runtime/UI/native/page/account/HTTP acceptance'))
rows=[];excluded=[]
for p in sorted(wide(P).rglob('*')):
 if not p.is_file():continue
 rel=str(p.relative_to(wide(P))).replace('\\','/')
 if rel=='frozen-content.json':continue
 b=read(p)
 row=dict(path=rel,bytes=len(b),sha256Bytes=digest(b))
 if p.suffix.lower()in['.jar','.class','.pyc']or'__pycache__/'in rel:excluded.append(dict(**row,reason='Binary/compiler artifact not installed or formally copied as raw source evidence'))
 else:rows.append(row)
save(P/'frozen-content.json',dict(frozen=True,scope='Full original content/related/AI/note source closure only',commit=COMMIT,rawArtifacts=rows,excludedArtifacts=excluded,identities=20,standaloneOutputs=20,directOutputs=14,productionSelectedOutputs=6,manualSources=2,compiledSources=22,compilePassed=True,candidateClasses=102,classOverlaps=0,topLevelMethodIntersections=0,illegalNonLocalReturnClasses=0,productionDefaultStandaloneByteEqual=True,completeOriginalFilesInverseAudited=16,explicitRuntimeAcceptance=False,remainingFullPage=['Original VideoPlayerSection5838','VideoDetailScreenStateHolder5464','ordinary/landscape/portrait parent assembly','actual Root/fullscreen/HWND interaction']))
print(json.dumps(dict(frozenManifest=str(P/'frozen-content.json'),sha256Bytes=digest(read(P/'frozen-content.json')),rawArtifacts=len(rows),excludedArtifacts=len(excluded),recipeSha256Bytes=digest(read(P/'CONTENT-INSTALL-RECIPE.json')))))
