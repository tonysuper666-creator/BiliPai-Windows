from pathlib import Path
import hashlib,json,os,sys
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent
def wide(p):
 s=os.path.abspath(str(p));return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def record(f):return dict(path=f.relative_to(wide(P)).as_posix(),sha256Bytes=hashlib.sha256(f.read_bytes()).hexdigest(),bytes=f.stat().st_size)
out=P/'frozen-handoff.json';assert not out.exists()
contract=json.loads((P/'install-contract.json').read_text(encoding='utf8'))
files=[]
for f in wide(P).rglob('*'):
 if not f.is_file():continue
 parts=f.relative_to(wide(P)).parts
 if 'classes'in parts or parts[0]=='actual-video-replay' or f.name=='frozen-handoff.json':continue
 files.append(record(f))
value=dict(candidateBase=contract['candidateBase'],upstreamCommit=contract['upstreamCommit'],
 scope='Installable same native-owner bridge only; full original Player Screen/PUGV comment/download/account/native runtime unaccepted',
 installContract='install-contract.json',normalProductCompile=False,completeOriginalPlayerAccepted=False,files=sorted(files,key=lambda r:r['path']))
wide(out).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf8',newline='\n')
print('Frozen',len(files),'files;',hashlib.sha256(wide(out).read_bytes()).hexdigest())
