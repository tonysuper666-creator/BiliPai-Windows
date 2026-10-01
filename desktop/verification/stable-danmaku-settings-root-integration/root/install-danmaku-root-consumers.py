from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent; MAIN=HERE.parents[2]; REPO=MAIN.parent/'BiliPai-v023'
LANE=REPO/'desktop/.local/stable-danmaku-root-consumers-parity'
def wide(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def lf(b):return b.decode('utf-8').replace('\r\n','\n').encode()
def pin(p,d):
    b=read(p);assert sha(b)==d,p;return b
def put(p,b):
    p=wide(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b)
raw=pin(LANE/'frozen-handoff.json','51551c0d5070f6980e7511c052816fee4e5b0a1cb517e82f4f60208dce5eb01f');m=json.loads(raw)
for r in m['evidence']:pin(r['path'],r['sha256Bytes'])
hunks=json.loads(pin(LANE/'local-hunks.json',m['localHunksSHA256']))
assert len(hunks)==33
pending={};baseline={}
for h in hunks:
    target=h['target']
    if target not in pending:baseline[target]=read(REPO/target);pending[target]=lf(baseline[target])
    before=pending[target];assert sha(before)==h['beforeLF'],h['label']
    old=h['old'].encode();assert before.count(old)==1,h['label']
    after=before.replace(old,h['new'].encode(),1);assert sha(after)==h['afterLF'],h['label']
    pending[target]=after
for r in m['payload']:
    assert not wide(REPO/r['target']).exists(),r['target']
    pending[r['target']]=pin(r['source'],r['sha256Bytes'])
assert not wide(HERE/'danmaku-root-consumers-install.json').exists()
for p,b in baseline.items():put(HERE/'danmaku-root-consumers-install-baseline'/p,b)
for p,b in pending.items():put(REPO/p,b)
report=dict(installed=True,frozenHandoffSHA256=sha(raw),exactLocalHunks=33,existingFiles=len(baseline),newPayloads=3,
            wholeReviewFilesCopied=False,existingDockOrRegistryOrOpsOrBuildOverwritten=False,
            installedFiles=[dict(path=p,sha256Bytes=sha(b)) for p,b in pending.items()],
            fullOriginalSettingsAndMenuRootSourceWired=True,wholeClassesAccepted=False,nativeRendererFullParityAccepted=False,desktopExeReplaced=False)
put(HERE/'danmaku-root-consumers-install.json',(json.dumps(report,indent=2)+'\n').encode());print(json.dumps(dict(installed=True,hunks=33,existingFiles=len(baseline))))
