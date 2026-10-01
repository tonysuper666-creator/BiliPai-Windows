from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
def ext(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return ext(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def write(p,b):ext(p).parent.mkdir(parents=True,exist_ok=True);ext(p).write_bytes(b)
vote=MAIN/'desktop/.local/stable-command-popup-hit-test-parity';download=MAIN/'desktop/.local/stable-download-notification-parity'
for lane,manifest,expected in [(vote,'handoff-final/frozen-handoff.json','8b537db86f0cf53cb60c2a1c5b279087c2798cded835d5999b6d28a9682b6228'),(download,'frozen-handoff.json','35d1c718ddca93eccbaabe95a02c3bcd1e28af0a2465f9ad2f7c754227468adc')]:
    raw=read(lane/manifest);assert sha(raw)==expected
    for row in json.loads(raw)['artifacts']:
        data=read(lane/row['path']);assert sha(data)==row['sha256Bytes'] and len(data)==row['bytes'],row['path']
plan=json.loads(read(vote/'handoff-final/install-plan.json'));assert sha(read(vote/'handoff-final/install-plan.json'))=='13a9cac83f5c138da7564f77b5ac0e188c4c197c8bad8902a57788f917fba6a0'
patches=[str(vote/r['source']) for r in plan['patches']]
for path,row in plan['sourceBaselines'].items():assert sha(read(REPO/path).decode().replace('\r\n','\n').encode())==row['baseLfSha256'],path
subprocess.run(['git','apply','--check',*patches],cwd=REPO,check=True)
for row in plan['copy']:
    data=read(vote/row['source']);assert sha(data)==row['sha256Bytes'] and not ext(REPO/row['target']).exists();write(REPO/row['target'],data)
subprocess.run(['git','apply',*patches],cwd=REPO,check=True)
for path,row in plan['sourceBaselines'].items():assert sha(read(REPO/path).decode().replace('\r\n','\n').encode())==row['desiredLfSha256'],path
path='desktop/src/main/kotlin/com/bilipai/desktop/download/DesktopDownloadNotifications.kt';data=read(download/'prepared'/path)
assert sha(data.decode().replace('\r\n','\n').encode())=='af8eb4e7664b83d1965afaa5313bab9543416cfd299b7f4e33fea37c59644815' and not ext(REPO/path).exists();write(REPO/path,data)
p=REPO/'desktop/upstream-sources.json';d=json.loads(read(p));rows={r['path']:r for r in d['sources']}
for entry in plan['sourceMerge']:
    row=rows[entry['path']];assert row['sha256']==entry['sourceSha256Lf'];row['features']=list(dict.fromkeys(row['features']+entry['featureAppend']))
write(p,(json.dumps(d,ensure_ascii=False,indent=2)+'\n').encode())
write(HERE/'native-platform-install.json',(json.dumps(dict(voteHelperCount=1,votePrecisePatches=3,downloadAdapters=1,newOriginalIdentities=0,wholeClassesPassed=False,rootMounted=False),indent=2)+'\n').encode())
print('PASS frozen vote helper/three exact patches and one notification adapter installed')
