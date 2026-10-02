from pathlib import Path
import hashlib,json,os,subprocess,sys
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;M=P.parents[2];C=M.parent/'BiliPai-v023';S=M/'desktop/.local/stable-product-snapshot-85'
def wide(path):
 value=os.path.abspath(str(path));return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(path):return hashlib.sha256(wide(path).read_bytes()).hexdigest()
def load(name):return json.loads((P/name).read_text(encoding='utf8'))
def write(name,value):(P/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
assert load('source-inverse-audit.json')['passed']
assert load('runs/05/result.json')['passed'] and load('proof-runs/02/result.json')['passed']
assert sha(S/'manifest.json')=='417663152a99ecf23e6887e2940b15c7c1cfd06c6c3f0b2b1925430ed4ca4012'
assert sha(S/'ordered-runtime-cp.json')=='6398174d444cf02365bc66480c1c37bb00800a520e681c4649100067af68b419'
entries=json.loads(wide(S/'ordered-runtime-cp.json').read_text(encoding='utf8'));assert len(entries)==101
for row in entries:assert sha(row['path'])==row['sha256Bytes']
contract=load('install-contract.json');assert len(contract['copyWhitelist'])==2
for row in contract['copyWhitelist']:assert sha(P/row['source'])==row['sha256Bytes']
for row in load('runs/05/result.json')['explicitProspectiveSources']:assert sha(row['path'])==row['sha256Bytes']
head=subprocess.check_output(['git','-C',str(C),'rev-parse','HEAD'],text=True).strip()
status=subprocess.check_output(['git','-C',str(C),'status','--porcelain'],text=True)
artifact=[];excluded=[]
for f in sorted(wide(P).rglob('*')):
 if not f.is_file():continue
 rel=f.relative_to(wide(P)).as_posix()
 if rel=='frozen-handoff.json':continue
 row=dict(path=rel,sha256Bytes=sha(f),sizeBytes=f.stat().st_size)
 (excluded if f.suffix in ['.class','.jar','.kotlin_module','.pyc'] else artifact).append(row)
value=dict(candidateBase=contract['candidateHead'],candidateHeadAtFreeze=head,candidateStatusAtFreeze=status,
 productionWrites=0,copyWhitelist=contract['copyWhitelist'],rawArtifactCount=len(artifact),rawArtifacts=artifact,
 binaryExclusions=excluded,installContractSha256Bytes=sha(P/'install-contract.json'),exactHunksSha256Bytes=sha(P/'exact-hunks.json'),
 targetsSha256Bytes=sha(P/'targets.json'),sourceInverseSha256Bytes=sha(P/'source-inverse-audit.json'),
 finalCompile='runs/05',finalFocusedProof='proof-runs/02',passedFocusedAssertions=34,
 baseSnapshot=dict(phase=85,manifestSha256Bytes=sha(S/'manifest.json'),orderedCpSha256Bytes=sha(S/'ordered-runtime-cp.json'),entryCount=101),
 independentCatalogDetailReviewPrepared=True,originalTimelineComponentPrepared=True,
 exactOriginalTimelineNavKeyExists=False,playerAccepted=False,wholeProductBuild=False,actualRootRuntime=False,nativePlayer=False,realAccount=False)
write('frozen-handoff.json',value)
print('FROZEN 2 copies /4 target families /9 exact hunks /8 source identities /0 resources /34 assertions')
for name in ['frozen-handoff.json','install-contract.json','exact-hunks.json','source-inverse-audit.json','targets.json']:
 print(name,sha(P/name))
