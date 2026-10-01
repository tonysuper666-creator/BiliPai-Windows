from pathlib import Path
import hashlib,json,os,zipfile
P=Path(__file__).resolve().parent; MAIN=P.parents[2]; SNAP=MAIN/'desktop/.local/stable-product-snapshot-69'
PFX=chr(92)*2+'?'+chr(92)
def wide(p):
 s=os.path.abspath(p);return Path(s if s.startswith(PFX)else PFX+s)
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
def lf(p):return hashlib.sha256(wide(p).read_text(encoding='utf8').replace('\r\n','\n').encode()).hexdigest()
def dump(p,v):wide(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf8',newline='\n')
assert not wide(P/'frozen-handoff.json').exists()
assert json.loads(wide(P/'runs/02/result.json').read_text())['passed']
cp=json.loads(wide(SNAP/'ordered-runtime-cp.json').read_text());assert len(cp)==101
for r in cp:assert sha(r['path'])==r['sha256Bytes']
manual=P/'prepared/manual/com/bilipai/desktop/ui/DesktopOriginalVideoMediaIntent.kt'
rows=json.loads(wide(P/'exact-hunks.json').read_text())
dump(P/'install-contract.json',dict(copyWhitelist=[dict(source=str(manual.relative_to(P)),target='desktop/src/main/kotlin/com/bilipai/desktop/ui/'+manual.name,sha256LF=lf(manual))],
 exactPatchOrder=[dict(target=r['target'],baseSHA256LF=r['baseSHA256LF'],desiredSHA256LF=r['desiredSHA256LF'],hunks=len(r['hunks']))for r in rows],
 compileOnlyActual69Reference='compile-reference-actual69',wholeExistingReplacementAllowed=False,
 registryDelta=[],gradleDelta=[],dependenciesDelta=[],nativeAccepted=False,rootMounted=False))
dump(P/'source-inventory.json',dict(originalCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
 originalIdentity='app/src/main/java/com/android/purebilibili/feature/video/usecase/VideoPlaybackUseCase.kt',
 originalSHA256LF='518cac4c89acb92aa97c3945b16fe5662f678bff6dcd67a5803f70165e5ae63f',
 sourceMode='existing sole Core producer; no new identity',manualSHA256LF=lf(manual),
 generatedUseCase='generated-replay/com/android/purebilibili/feature/video/usecase/DesktopOriginalVideoPlaybackUseCase.kt',
 candidateCoreSHA256LF=lf(P/'generated-replay/com/android/purebilibili/feature/video/usecase/DesktopOriginalVideoPlaybackUseCase.kt'),
 inverseReceipt='source-replay-audit.json',actual69ManifestSHA256=sha(SNAP/'manifest.json'),actual69OrderedCPSHA256=sha(SNAP/'ordered-runtime-cp.json')))
raw=[];excluded=[]
for folder,dirs,names in os.walk(wide(P)):
 dirs[:]=[d for d in dirs if d!='__pycache__']
 for name in names:
  file=Path(folder)/name;rel=str(file).removeprefix(str(wide(P))+os.sep).replace('\\','/')
  if file.suffix.lower()in{'.jar','.class','.dll','.pyc'}:excluded.append(dict(path=rel,sha256Bytes=sha(file),reason='compile/test reference; never install/archive binary'))
  elif rel not in{'frozen-handoff.json','excluded-runtime-artifacts.json'}:raw.append(dict(path=rel,sha256Bytes=sha(file),size=wide(file).stat().st_size))
dump(P/'excluded-runtime-artifacts.json',excluded)
raw.append(dict(path='excluded-runtime-artifacts.json',sha256Bytes=sha(P/'excluded-runtime-artifacts.json'),size=wide(P/'excluded-runtime-artifacts.json').stat().st_size))
dump(P/'frozen-handoff.json',dict(schema=1,scope='original Core initial MPV intent lexical platform delta',artifacts=sorted(raw,key=lambda r:r['path']),
 installContract='install-contract.json',sourceOnly=True,groups=3,assertions=13,declaredPreparedFamilies=['CoreUseCase','MediaPortEnvironment','Invocation','NativeOwner-derived-actual69'],
 zeroProductOverride=False,HTTP=False,native=False,rootMounted=False,pauseRuntimeAccepted=False,
 history=['01 compiler success; original StateFlow equality revealed overstrict candidate reference guard','02 original value/token semantics corrected; final pass']))
for r in raw:assert sha(P/r['path'])==r['sha256Bytes']
print('Frozen',len(raw),'raw; manifest',sha(P/'frozen-handoff.json'),'manualLF',lf(manual))
