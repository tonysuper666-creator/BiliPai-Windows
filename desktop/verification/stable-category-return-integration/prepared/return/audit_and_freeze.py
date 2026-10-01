from pathlib import Path
import hashlib,json,importlib.util,subprocess,tempfile,zipfile
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
TARGET='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def lf(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def shal(s):return hashlib.sha256(s.encode('utf-8')).hexdigest()
tool=HERE/'prepared/tools/extract-upstream-home-return-navigation.py'
spec=importlib.util.spec_from_file_location('producer',tool);producer=importlib.util.module_from_spec(spec);spec.loader.exec_module(producer)
receipt=json.loads(safe(HERE/'selected-source-receipt.json').read_text(encoding='utf-8'))
identities=json.loads(safe(HERE/'initial-inventory.json').read_text(encoding='utf-8'))[:6]
checks=[]
for row in identities:
 raw=subprocess.run(['git','show',TARGET+':'+row['path']],cwd=REPO,capture_output=True,check=True).stdout.decode('utf-8').replace('\r\n','\n')
 assert shal(raw)==row['sha256'],row['path']
 assert lf(HERE/'prepared/direct'/row['path'])==raw
 checks.append({'path':row['path'],'samePinnedGitBody':True,'sha256LF':shal(raw)})
for row in receipt['sources']:
 raw=subprocess.run(['git','show',TARGET+':'+row['path']],cwd=REPO,capture_output=True,check=True).stdout.decode('utf-8').replace('\r\n','\n')
 assert shal(raw)==row['sha256LF']
 assert lf(REPO/row['path'])==raw
 checks.append({'path':row['path'],'samePinnedGitBody':True,'sha256LF':shal(raw)})
replay=HERE/'production-replay'
producer.generate(REPO,replay)
for row in receipt['outputs']:
 path=Path(row['path']).absolute();rel=path.relative_to(HERE/'prepared/generated')
 assert sha(path)==row['sha256Bytes'] and safe(path).read_bytes()==safe(replay/rel).read_bytes()
 checks.append({'output':str(rel),'productionReplaySameBytes':True})
# Verify the four generated caller bodies invert to exactly the original selected source text.
generated=lf(HERE/'prepared/generated/com/bilipai/desktop/ui/DesktopOriginalHomeReturnCallers.kt')
for index,name in enumerate(list(receipt['selections'])[:4]):
 target=['desktopOriginalHomeSourceMetadata','desktopOriginalHomeCardSourceDirection',
 'desktopOriginalHomeTransitionSession','desktopOriginalHomePrearmOpening'][index]
 start=generated.index('internal fun '+target+'(')
 next_=generated.find('\n\ninternal fun ',start+1)
 body=generated[start:next_ if next_>=0 else len(generated)].rstrip()
 if index==0:body=body.replace('internal fun desktopOriginalHomeSourceMetadata(navigation3ReturnSession: BiliPaiReturnSessionState)','fun currentNavigation3SourceMetadata()')
 elif index==1:body=body.replace('internal fun desktopOriginalHomeCardSourceDirection(', 'fun captureCardSourceDirectionForSession(')
 elif index==2:
  body=body.replace('internal fun desktopOriginalHomeTransitionSession(', 'fun captureVideoCardTransitionSession(')
  body=body.replace('    navigationHostOriginInRoot: Offset,\n','')
  body=body.replace('desktopOriginalHomeCardSourceDirection()', 'captureCardSourceDirectionForSession()')
 else:
  body=body.replace('internal fun desktopOriginalHomePrearmOpening(\n    session: VideoCardTransitionSession,\n    sharedVideoCardTransitionEnabled: Boolean,\n    relatedVideoTransitionEnabled: Boolean,\n    systemReduceMotion: Boolean,\n    videoCardTransitionClock: VideoCardTransitionClock,\n)', 'fun prearmVideoCardOpening(session: VideoCardTransitionSession)')
 assert body==receipt['selections'][name]['originalBody'],name
 checks.append({'selectedCaller':name,'inverseToOriginalBytes':True})
registry=json.loads(lf(REPO/'desktop/upstream-sources.json'))
by_path={row['path']:row for row in registry['sources']}
rows=[]
for row in identities:
 assert row['path'] not in by_path
 rows.append({'path':row['path'],'sha256':row['sha256'],'mode':'direct',
              'features':['stable-home-return-navigation']})
for row in receipt['sources']:
 assert row['path'] in by_path
 assert by_path[row['path']]['sha256']==row['sha256LF']
 rows.append({'path':row['path'],'sha256':row['sha256LF'],
              'preserveExistingMode':True,'addFeatures':['stable-home-return-navigation']})
safe(HERE/'upstream-source-delta.json').write_text(json.dumps({'target':TARGET,'newDirectCount':6,
 'existingFeatureMergeCount':2,'rows':rows,'resources':[],'dependencies':[]},indent=2)+'\n',encoding='utf-8')
snapshot=MAIN/'desktop/.local/stable-product-snapshot-39'
assert sha(snapshot/'manifest.json')=='4840845d18a6fd1171c32a03ba397306a0f509b6510ca9544ceac935befe46ae'
assert sha(snapshot/'ordered-runtime-cp.json')=='e9b8db57c428c214b243e2ea41761d0745c3b48ff386fe8d38901d892760fd8f'
cp=json.loads(lf(snapshot/'ordered-runtime-cp.json'))
allclasses=set()
for row in cp:
 assert sha(row['path'])==row['sha256Bytes']
 with zipfile.ZipFile(safe(row['path'])) as z:allclasses.update(n for n in z.namelist() if n.endswith('.class'))
candidate={p.relative_to(HERE/'compile-02/classes').as_posix() for p in (HERE/'compile-02/classes').rglob('*.class')}
assert not candidate&allclasses
safe(HERE/'actual39-readonly-audit.json').write_text(json.dumps({'manifestSha':sha(snapshot/'manifest.json'),
 'orderedCpSha':sha(snapshot/'ordered-runtime-cp.json'),'entryCount':len(cp),'strictEveryCpSha':True,
 'candidateClasses':len(candidate),'allClassOverlap':[],
 'scope':'Read-only symbols/identity audit, not a new compile/runtime against actual39.'},indent=2)+'\n',encoding='utf-8')
safe(HERE/'source-checks.json').write_text(json.dumps({'checks':checks,'count':len(checks),'passed':True},indent=2)+'\n',encoding='utf-8')
payloads=[('prepared/tools/extract-upstream-home-return-navigation.py','desktop/tools/extract-upstream-home-return-navigation.py'),
 ('prepared/manual/com/bilipai/desktop/ui/DesktopHomeReturnNavigationOwner.kt','desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopHomeReturnNavigationOwner.kt')]
safe(HERE/'install-whitelist.json').write_text(json.dumps({'files':[{'source':s,'destination':d,'sha256LF':shal(lf(HERE/s))}for s,d in payloads],
 'neverInstall':['prepared/generated','prepared/direct','production-replay','compile-*','proof-*'],
 'directBodiesAlreadyPinnedUpstream':True},indent=2)+'\n',encoding='utf-8')
raw=[];binary=[]
for p in HERE.rglob('*'):
 if not p.is_file() or p.name=='frozen-handoff.json' or '__pycache__' in p.parts:continue
 row={'path':p.relative_to(HERE).as_posix(),'sha256Bytes':sha(p),'bytes':safe(p).stat().st_size}
 if p.suffix in ('.class','.jar','.dll'):binary.append(row)
 else:raw.append(row)
out={'scope':'Prepared source-only Home return/nav owner; no Main consumer/renderer/native admission acceptance.',
 'target':TARGET,'rawArtifacts':sorted(raw,key=lambda r:r['path']),
 'excludedRuntimeArtifacts':sorted(binary,key=lambda r:r['path']),
 'runtimeAcceptance':{'actualRoot':False,'actualWindow':False,'actualMpV':False,'actualAccountHttp':False},
 'evidence':{'compile':'compile-02','fixture':'proof-01','groups':7,'newSourceIdentities':6,'featureMerges':2}}
safe(HERE/'frozen-handoff.json').write_text(json.dumps(out,indent=2)+'\n',encoding='utf-8')
print('freeze',sha(HERE/'frozen-handoff.json'),'raw',len(raw),'excluded',len(binary),'source checks',len(checks))
