from pathlib import Path
import hashlib,json,os,subprocess,sys,zipfile
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;M=P.parents[2];C=M.parent/'BiliPai-v023'
def wide(p):
 s=str(p);return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+os.path.abspath(s))
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
def read(p):return wide(p).read_text(encoding='utf8')
def write(p,value):wide(p).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
head=subprocess.check_output(['git','-C',str(C),'rev-parse','HEAD'],text=True).strip()
assert head=='a5ba7265ffcc0157cd9cdab768e0a211fa9e4478'
assert not subprocess.check_output(['git','-C',str(C),'status','--porcelain'],text=True).strip()
assert json.loads(read(P/'runs/03/result.json'))['passed']
assert json.loads(read(P/'proof-runs/05/result.json'))['passed']
assert 'PASS 29 focused assertions' in read(P/'proof-runs/05/run.log')
for row in json.loads(read(P/'runs/03/result.json'))['explicitProspectiveSources']:
 assert sha(row['path'])==row['sha256Bytes']
S=M/'desktop/.local/stable-product-snapshot-84';entries=json.loads(read(S/'ordered-runtime-cp.json'))
assert sha(S/'manifest.json')=='f70a306e3dc42df93c00d536163ff4a1f1953cbc94d36586c9500281983351b2'
assert sha(S/'ordered-runtime-cp.json')=='4b1de9cce18526162113e1336c0252b37b3113d1f0b8832b68953fc3ec5df8d9'
assert len(entries)==101
for row in entries:assert sha(row['path'])==row['sha256Bytes']
hunks=json.loads(read(P/'exact-hunks.json'));targets=json.loads(read(P/'targets.json'))
assert len(hunks)==16 and len(targets)==6
for row in targets:
 t=read(C/row['path']).replace('\r\n','\n');assert hashlib.sha256(t.encode()).hexdigest()==row['beforeSha256LF']
 for h in [h for h in hunks if h['path']==row['path']]:
  assert t.count(h['before'])==1;t=t.replace(h['before'],h['after'])
 assert hashlib.sha256(t.encode()).hexdigest()==row['afterSha256LF']
manuals=list(wide(P/'prepared/manual').rglob('*.kt'));assert len(manuals)==2
copy=[]
for f in manuals:
 rel=f.relative_to(wide(P/'prepared/manual')).as_posix()
 assert not wide(C/rel).exists(),rel
 copy.append(dict(source=f.relative_to(wide(P)).as_posix(),target=rel,sha256Bytes=sha(f)))
contract=dict(candidateHead=head,snapshot=84,copyWhitelist=copy,exactHunks='exact-hunks.json',targets='targets.json',patchOrder=[row['path']for row in targets],newOriginalIdentities=0,registryEdits=[],gradleEdits=[],newDependencies=[],newResources=[],doNotInstall=['prepared/existing/** whole files','baseline/**','reference/**','replay/**','prepared/existing/desktop/tools/extract-upstream-dynamic-reply.py','prepared/existing/desktop/tools/extract-upstream-dynamic-reply-protocol.py','runs/** compiled classes/JARs','proof-runs/** compiled classes'],soleProducer='desktop/tools/extract-upstream-bgm-detail.py',regenerate='existing BGM task; full canonical Screen/VM only changed, no new task',runtimeAcceptance=False)
write(P/'install-contract.json',contract)
binary=[];raw=[]
for f in sorted(wide(P).rglob('*')):
 if not f.is_file():continue
 rel=f.relative_to(wide(P)).as_posix()
 if rel=='frozen-handoff.json':continue
 item=dict(path=rel,sha256Bytes=sha(f),size=f.stat().st_size)
 if f.suffix in ['.jar','.class','.kotlin_module','.pyc']:binary.append(item)
 else:raw.append(item)
write(P/'frozen-handoff.json',dict(candidateHead=head,copyWhitelist=copy,rawArtifactCount=len(raw),rawArtifacts=raw,binaryExclusions=binary,installContractSha256Bytes=sha(P/'install-contract.json'),exactHunksSha256Bytes=sha(P/'exact-hunks.json'),baseSnapshot=dict(phase=84,manifestSha256Bytes=sha(S/'manifest.json'),orderedCpSha256Bytes=sha(S/'ordered-runtime-cp.json'),entryCount=101),finalCompile='runs/03',finalFocusedProof='proof-runs/05',originalInverse='source-inverse-audit.json',passedFocusedAssertions=29,wholeBuild=False,rootHTTP=False,rootWindow=False,productionWrites=0))
print('FROZEN',len(raw),'raw /2manuals/6families/16hunks',sha(P/'frozen-handoff.json'))
print('contract',sha(P/'install-contract.json'))
print('hunks',sha(P/'exact-hunks.json'))
