from pathlib import Path
import ast,difflib,hashlib,json,sys
sys.stdout.reconfigure(encoding='utf8')
sys.dont_write_bytecode=True
root=Path(__file__).resolve().parent;main=root.parents[2];candidate=main.parent/'BiliPai-v023'
source=main/'desktop/.local/stable-original-bangumi-player-screen-root-parity'
packet=root/'packet';packet.mkdir(exist_ok=False)
def wide(p):
 s=str(p.absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
rows=[]
def copy(name,b):
 p=wide(packet/name);assert not p.exists();p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b)
 rows.append(dict(path=name,bytes=len(b),sha256Bytes=sha(b)))
installation=json.loads(read(root/'installation.json'));build=json.loads(read(root/'actual-build01.json'))
assert build['exitCode']==0 and build['junitTests']==15 and build['actualProductOverrides']==0
assert build['junitFailures']==build['junitErrors']==build['junitSkipped']==0
frozen_raw=read(source/'frozen-handoff.json');assert sha(frozen_raw)==installation['frozenHandoffSha256Bytes']
copy('agent/frozen-handoff.json',frozen_raw)
for r in json.loads(frozen_raw)['files']:
 b=read(source/r['path']);assert sha(b)==r['sha256Bytes'] and len(b)==r['bytes']
 if Path(r['path']).suffix.lower() not in ('.jar','.class','.exe','.dll','.kotlin_module'):copy('agent/'+r['path'],b)
audit=json.loads(read(source/'source-and-boundary-audit.json'))
normal_rows=[]
for family,key in [('original-bangumi-player-ui','soleUiProducerOutputs'),('original-bangumi-player','solePgcProducerOutputs')]:
 for r in audit[key]:
  b=read(candidate/'desktop/build/generated'/family/r['output']);assert sha(b)==r['sha256Bytes'],r['output']
  copy('root-normal-generated/'+family+'/'+r['output'],b)
  normal_rows.append(dict(path=family+'/'+r['output'],sha256Bytes=sha(b),frozenByteEqual=True))
assert len(normal_rows)==15
tree=ast.parse(read(candidate/'desktop/tools/extract-upstream-bangumi-player-ui.py').decode())
expr=next(n.value for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='RECIPES' for t in n.targets))
recipes=json.loads(ast.literal_eval(expr.args[0]));inverses=[]
for r in recipes:
 original=read(candidate/r['originalPath']).decode().replace('\r\n','\n')
 assert sha(original.encode())==r['originalSha256LF']
 generated=read(candidate/'desktop/build/generated/original-bangumi-player-ui'/r['output']).decode()
 assert generated.startswith(r['prefix']);adapted=generated[len(r['prefix']):]
 assert sha(adapted.encode())==r['adaptedSha256LF']
 lines=original.splitlines(keepends=True);positions=[];cursor=0;at=0
 for edit in r['edits']:
  i,j=edit['startLine'],edit['endLineExclusive'];assert i>=cursor
  before=''.join(lines[i:j]);assert before==edit['before'] and sha(before.encode())==edit['beforeSha256LF']
  unchanged=''.join(lines[cursor:i]);assert adapted[at:at+len(unchanged)]==unchanged;at+=len(unchanged)
  assert adapted[at:at+len(edit['after'])]==edit['after'];positions.append((at,edit));at+=len(edit['after']);cursor=j
 assert adapted[at:]==''.join(lines[cursor:])
 inverse=adapted
 for at,edit in reversed(positions):inverse=inverse[:at]+edit['before']+inverse[at+len(edit['after']):]
 assert inverse==original
 inverses.append(dict(source=r['originalPath'],originalSha256LF=r['originalSha256LF'],completeNormalOriginalInverse=True))
assert len(inverses)==5
# Preserve the actual Root window failure that motivated the Space binding repair.
acceptance=main/'desktop/.local/stable-space-root-integration-acceptance'
for attempt in ['actual90-01','actual90-02']:
 for name in ['RootSpaceFixture.kt','compile-result.json','compile.log','acceptance-result.json','runtime.log','pins-before.json','pins-after.json']:
  p=acceptance/'runs'/attempt/name
  if p.exists():copy('root-window-before-repair/'+attempt+'/'+name,read(p))
for name in ['install.py','prepare_evidence.py']:copy('root-review/'+name,read(root/name))
patch=[]
for r in installation['sourceTargets']:
 b=read(candidate/r['path']);assert sha(b)==r['afterSha256Bytes']
 old=read(root/'before'/r['path'])
 patch.extend(difflib.unified_diff(old.decode().replace('\r\n','\n').splitlines(keepends=True),b.decode().splitlines(keepends=True),fromfile='a/'+r['path'],tofile='b/'+r['path']))
(root/'source-diff.patch').write_text(''.join(patch),encoding='utf8',newline='\n')
summary=dict(baseCommit=installation['candidateBase'],normalMainAndTestCompilePassed=True,actualProductOverrides=0,
 sourceCount=1234,resourceCount=244,normalBuildLogSha256Bytes=build['logSha256Bytes'],junitMethods=15,
 fullOriginalUiBodies=5,sourceInverseReconstructed=5,normalGeneratedOutputs=15,generated=normal_rows,inverse=inverses,
 priorActualSpaceCompositionFailurePreserved=True,spaceBackToTopActualRootBindingRepaired=True,
 rootRuntimeAccepted=False,realAccountAccepted=False,decodedNativePlaybackAccepted=False,newEXEDeployed=False,
 v025Accepted=False,allFeaturesComplete=False)
(root/'generated-verification.json').write_text(json.dumps(summary,indent=2)+'\n',encoding='utf8')
(root/'integration-summary.json').write_text(json.dumps(summary,indent=2)+'\n',encoding='utf8')
copy('root-review/generated-verification.json',read(root/'generated-verification.json'))
(root/'README.md').write_text('''The normal Windows product now mounts the complete original v023 PGC/PUGV player UI in the existing physical Root and shares the existing native media owner, primary account, comments, downloads, Mini, progress and heartbeat services. Five complete original UI bodies were independently reconstructed from the normal generated outputs. All fifteen generated UI/PGC outputs are byte-equal to the reviewed frozen preparation.

Normal Gradle classes and fifteen DesktopOriginalSpacePagesTest JUnit methods passed with zero product overlays. The actual Space window had previously failed while composing the original BackToTop consumer: no preferences binding was supplied. The required original BackToTop binding now uses the existing global preferences, measured viewport and retained Space write admission. Both prior failed fixture attempts are preserved. A fresh actual window test remains required.

This is a v023 source integration slice. PGC/native decoded-frame acceptance, successful business HTTP, live account/course permissions, v025 migration, whole-project feature alignment and a newly deployed EXE remain unaccepted. The 59 prepared CPU assertions are separate preparation evidence.
''',encoding='utf8')
frozen=dict(rawArtifacts=rows,rawFiles=len(rows),normalBuildAttempt=1,junitMethods=15,rootRuntimeAccepted=False,v025Accepted=False)
b=(json.dumps(frozen,indent=2)+'\n').encode();(packet/'frozen-handoff.json').write_bytes(b)
print(json.dumps(dict(rawFiles=len(rows),frozenSha256Bytes=sha(b),normalOutputs=15,fullOriginalInverse=5,junitMethods=15),indent=2))
