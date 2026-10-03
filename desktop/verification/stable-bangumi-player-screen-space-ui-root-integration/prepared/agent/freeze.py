from pathlib import Path
import hashlib,json,os,sys
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent
def wide(p):
 s=os.path.abspath(str(p));prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix)else prefix+s)
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
out=P/'frozen-handoff.json';assert not wide(out).exists()
contract=json.loads(wide(P/'install-contract.json').read_text(encoding='utf8'))
assert contract['immutableSnapshot']==90 and contract['installableFullPlayerSourceClosure'] and not contract['completeOriginalPlayerAccepted']
for field in ['existingExactHunks','existingTargets','registryDelta','sourceInverseAudit','narrowCompile','cpuProof']:
 r=contract[field];assert sha(r['path'])==r['sha256Bytes']
for r in contract['newFileCopyWhitelist']:assert sha(r['source'])==r['sha256Bytes']
files=[]
for f in wide(P).rglob('*'):
 if not f.is_file():continue
 rel=f.relative_to(wide(P));parts=rel.parts
 if 'classes'in parts or f.name=='frozen-handoff.json':continue
 files.append(dict(path=rel.as_posix(),sha256Bytes=sha(f),bytes=f.stat().st_size))
value=dict(candidateBase=contract['candidateBase'],upstreamCommit=contract['upstreamCommit'],immutableSnapshot=90,scope='Installable complete original v023 PGC Player UI/Root source closure on the already installed same native bridge; normal product/native/account/runtime acceptance remains pending',installContract='install-contract.json',normalProductCompile=False,completeOriginalPlayerAccepted=False,files=sorted(files,key=lambda r:r['path']))
wide(out).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf8',newline='\n')
print('Frozen',len(files),'files; manifestSHA',sha(out),'contractSHA',sha(P/'install-contract.json'))
