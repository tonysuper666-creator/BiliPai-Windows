from pathlib import Path
import json,hashlib
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';LANE=MAIN/'desktop/.local/stable-offline-task-player-parity';OUT=HERE/'offline-task-bridge-install48';assert not OUT.exists()
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix)else prefix+s)
raw=wide(LANE/'frozen-handoff.json').read_bytes();assert sha(raw)=='3146f18a8aa93dbabd88ea5bafda6192399db7ddf90d3226c4f093cb56cf4c23';m=json.loads(raw);assert len(m['files'])==57
for r in m['files']:assert sha(wide(LANE/r['path']).read_bytes())==r['sha256Bytes'],r['path']
raw=wide(LANE/'install-contract.json').read_bytes();assert sha(raw)=='dde2c91979a08c95246968ceb6d21c70a9d72156b4254b3dd47e37df367d10b4';contract=json.loads(raw);assert len(contract['payloads'])==2 and not contract['sharedHunks']and not contract['originalRegistryDelta']
pending=[]
for r in contract['payloads']:
 data=wide(Path(r['prepared'])).read_bytes();assert sha(data)==r['sha256Bytes'];p=REPO/r['target'];assert not p.exists();pending.append((p,data))
OUT.mkdir()
for p,data in pending:p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
r=dict(applied=True,newSourcePayloads=2,sharedHunks=0,newOriginalSourceIdentities=0,newRuntimeArtifacts=0,wholeCompilationPending=True,rootLeafMounted=False,fullOriginalOfflineUi=False,newExeDeployed=False,payloads=contract['payloads'])
(OUT/'installed.json').write_text(json.dumps(r,indent=2)+'\n',encoding='utf-8');print(json.dumps(r))
