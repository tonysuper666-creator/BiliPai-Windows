from pathlib import Path
import hashlib,json,re,subprocess,zipfile
P=Path(__file__).resolve().parent;MAIN=P.parents[2];REPO=MAIN.parent/'BiliPai-v023';COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
SNAP=MAIN/'desktop/.local/stable-product-snapshot-50'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def save(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes((json.dumps(v,ensure_ascii=False,indent=2)+'\n').encode())
def fixed(path):return subprocess.check_output(['git','show',COMMIT+':'+path],cwd=REPO).replace(b'\r\n',b'\n')
assert sha(read(SNAP/'manifest.json'))=='d9803560314da73036a848cf1be86c5877ff24b3993584379a5e9516165de4eb'
assert sha(read(SNAP/'ordered-runtime-cp.json'))=='c4615263c430a5b75f86085499bcf3dae27239a005a59e258cdff172bbf75680'
cp=json.loads(read(SNAP/'ordered-runtime-cp.json'));assert len(cp)==97
classes=set()
for row in cp:
 assert sha(read(row['path']))==row['sha256Bytes']
 if str(row['path']).endswith('.jar'):
  with zipfile.ZipFile(wide(row['path']))as z:classes.update(x[:-6].replace('/','.')for x in z.namelist()if x.endswith('.class'))
paths=subprocess.check_output(['git','ls-tree','-r','--name-only',COMMIT],cwd=REPO,text=True).splitlines()
production=[x for x in paths if x.endswith('.kt')and '/src/main/'in x]
names={};filepaths={}
for path in production:
 b=wide(REPO/path).read_bytes().replace(b'\r\n',b'\n');t=b.decode();m=re.search(r'(?m)^package ([\w.]+)',t)
 if not m:continue
 package=m.group(1)
 filepaths[package+'.'+Path(path).stem]=path
 # Include top-level and private names as dependency inventory only, not claims of class presence.
 for name in re.findall(r'(?m)^(?:(?:private|internal|suspend|inline|data|sealed|enum|value|annotation|expect|actual)\s+)*(?:fun|class|object|interface|val|var)\s+(?:[\w.]+\.)?([\w]+)',t):names.setdefault(package+'.'+name,set()).add(path)
principal=['app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoPlayerSection.kt','app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoPlayerSectionContracts.kt']
rows=[];references=[]
for path in principal:
 b=fixed(path);target=P/'original-stable'/path;wide(target).parent.mkdir(parents=True,exist_ok=True);wide(target).write_bytes(b)
 rows.append(dict(path=path,sha256LF=sha(b),gitBlob=subprocess.check_output(['git','rev-parse',COMMIT+':'+path],cwd=REPO,text=True).strip(),lines=len(b.splitlines())))
 for fqn in re.findall(r'(?m)^import (com\.android\.purebilibili[\w.]*)',b.decode()):
  deps=sorted(names.get(fqn,{filepaths[fqn]}if fqn in filepaths else set()))
  references.append(dict(fqn=fqn,sources=deps,actual50ExactClass=fqn in classes,ownerDecision='reference original Android player manager/state only via real same-MPV Windows bindings'if any(x in fqn for x in ['VideoPlayerState','DanmakuManager','rememberDanmakuManager','MiniPlayerManager','Anime4KGLSurfaceView','VideoPlaybackUiState','SponsorContributionUiState'])else None))
used=sorted({x for r in references for x in r['sources']})
pins=[]
for path in used:
 b=fixed(path);target=P/'dependency-sources'/path;wide(target).parent.mkdir(parents=True,exist_ok=True);wide(target).write_bytes(b)
 pins.append(dict(path=path,sha256LF=sha(b),lines=len(b.splitlines()),hasAndroidImports=bool(re.search(rb'(?m)^import (?:android\.|androidx\.media3\.)',b))))
save(P/'source-dependency-inventory.json',dict(commit=COMMIT,scope='Complete original PlayerSection dependency inventory only; no production claims',principal=rows,imports=references,dependencySourcePins=pins,actual50SnapshotPins=dict(manifest=sha(read(SNAP/'manifest.json')),orderedCP=sha(read(SNAP/'ordered-runtime-cp.json')),entries=len(cp)),parentOwnedFamilies=['VideoDetailScreenStateHolder','VideoPlaybackUiState','SponsorContributionUiState','full original data/Success extensions'],neverProduce=['FakeExoPlayer','Android DanmakuManager authority','MiniPlayerManager authority','second Canvas/Popup/native player','second Store/client/controller']))
print(json.dumps(dict(principal=rows,originalImports=len(references),dependencySources=len(pins),androidDependent=sum(r['hasAndroidImports']for r in pins))))
