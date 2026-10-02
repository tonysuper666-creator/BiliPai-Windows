from pathlib import Path
import hashlib,json,subprocess,sys
sys.stdout.reconfigure(encoding='utf-8');sys.dont_write_bytecode=True
H=Path(__file__).resolve().parent;MAIN=H.parents[2];REPO=MAIN.parent/'BiliPai-v023'
LANE=MAIN/'desktop/.local/stable-native-video-window-capture-parity'
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix)else prefix+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def put(p,b):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b)
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip()=='66aa83614aece93ca802823b21f8425a1f156449'
assert not subprocess.check_output(['git','status','--porcelain'],cwd=REPO)
assert sha(read(LANE/'frozen-handoff.json'))=='9172cb93c4ed6837328c70e5fd5f0ca10a6d954928ee192989359b792ba34a6e'
manifest=json.loads(read(LANE/'frozen-handoff.json'))
for row in manifest['files']:assert sha(read(LANE/row['path']))==row['sha256Bytes']
families=json.loads(read(LANE/'baseline-families.json'));hunks=json.loads(read(LANE/'exact-hunks.json'))
assert len(families)==2 and len(hunks)==16
pending=[];proof=[]
for family in families:
 relative=family['path'];before=read(REPO/relative).replace(b'\r\n',b'\n');value=before
 assert sha(before)==family['beforeSha256LF'],relative
 positions=[]
 for hunk in [r for r in hunks if r['path']==relative]:
  old=hunk['before'].encode();new=hunk['after'].encode()
  assert sha(old)==hunk['beforeSnippetSha256']and sha(new)==hunk['afterSnippetSha256']
  assert value.count(old)==1;index=value.index(old);positions.append((index,old,new));value=value[:index]+new+value[index+len(old):]
 assert value==read(LANE/'prepared/existing'/relative)and sha(value)==family['afterSha256LF']
 inverse=value
 for index,old,new in reversed(positions):
  assert inverse[index:index+len(new)]==new;inverse=inverse[:index]+old+inverse[index+len(new):]
 assert inverse==before
 pending.append((relative,value));proof.append(dict(path=relative,beforeSHA256LF=sha(before),afterSHA256LF=sha(value),localHunks=len(positions),exactInverse=True))
relative='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopWindowsVideoCaptureOwner.kt'
assert not (REPO/relative).exists()
b=read(LANE/'manual/com/bilipai/desktop/ui/DesktopWindowsVideoCaptureOwner.kt')
assert sha(b)=='edd2c4225a7bec71f395fe013be61597b1048bb3de684f06004733163ba3df66'
pending.append((relative,b))
for relative,b in pending:put(REPO/relative,b)
report=dict(installed=True,frozenManifestSHA256Bytes=sha(read(LANE/'frozen-handoff.json')),
 sourceFamilies=proof,newManualPath=relative,newManualSHA256Bytes=sha(b),
 noRegistryOrDependencyChange=True,wholeMainMounted=False,nativeWindowRuntimeAccepted=False)
put(H/'installation.json',(json.dumps(report,indent=2)+'\n').encode())
print(json.dumps(dict(sourceFamilies=len(families),localHunks=len(hunks),newManual=1,installed=True)))
