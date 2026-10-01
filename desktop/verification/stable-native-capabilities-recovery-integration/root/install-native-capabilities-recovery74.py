from pathlib import Path
import hashlib,json,subprocess
H=Path(__file__).resolve().parent;MAIN=H.parents[2];REPO=MAIN.parent/'BiliPai-v023'
OUT=H/'native-capabilities-recovery-install74'
assert not OUT.exists()
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
 return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def lf(b):return b.replace(b'\r\n',b'\n')
def write(p,b):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b)
def dump(p,v):write(p,(json.dumps(v,ensure_ascii=False,indent=2)+'\n').encode())
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip()
assert head=='d63734162161f24536036686a9da6cb0d1efccdd'
assert not subprocess.check_output(['git','status','--porcelain'],cwd=REPO,text=True).strip()
CAP=MAIN/'desktop/.local/stable-native-capabilities-window-parity'
FAIL=MAIN/'desktop/.local/stable-native-byte-failure-recovery-parity'
capraw=read(CAP/'frozen-handoff.json');failraw=read(FAIL/'frozen-handoff.json')
assert sha(capraw)=='837b6ee79a39843c67a63304157015b34ba94952e9c6f1d767d6ca3dd989edb0'
assert sha(failraw)=='ab3eda364cdc3a85b812787ad99792e160ce18354de5808817a24d3b6b618499'
cap=json.loads(capraw);fail=json.loads(failraw)
assert len(cap['files'])==144 and len(cap['manual'])==5
assert fail['rawCount']==103
for lane,rows in [(CAP,cap['files']),(FAIL,json.loads(read(FAIL/'raw-manifest.json')))]:
 for r in rows:
  b=read(lane/r['path']);assert sha(b)==r['sha256Bytes'] and len(b)==r['size'],r['path']
for meta in ['rawManifest','excludedManifest','exactHunks','baselineFamilies','sourceAudit','integration']:
 r=fail[meta];b=read(FAIL/r['path']);assert sha(b)==r['sha256Bytes'] and len(b)==r['size']
copy=[]
for r in cap['manual']:
 b=read(CAP/'manual'/r['path']);assert sha(b)==r['sha256Bytes']
 copy.append(('desktop/src/main/kotlin/'+r['path'],b))
for r in fail['copyWhitelist']:
 path=r['path'];assert path.startswith('prepared/manual/')
 b=read(FAIL/path);assert sha(b)==r['sha256Bytes'] and len(b)==r['size']
 copy.append((path.removeprefix('prepared/manual/'),b))
assert len(copy)==6
for name,b in copy:assert not wide(REPO/name).exists(),name

capedit=json.loads(read(CAP/'mpv-exact-hunks.json'))
fails=json.loads(read(FAIL/'exact-hunks.json'));assert len(fails)==12
baselines={r['path']:r for r in json.loads(read(FAIL/'baseline-families.json'))}
assert len(baselines)==3
families={capedit['family']:capedit['hunks']}
for r in fails:families.setdefault(r['path'],[]).append(r)
assert len(families)==4
changes=[]
for name,hunks in families.items():
 before=read(REPO/name);base=lf(before)
 if name==capedit['family']:
  assert sha(before)==capedit['beforeSha256Bytes'] and sha(base)==capedit['beforeSha256LF']
  expected=capedit['afterSha256LF']
 else:
  row=baselines[name];assert sha(before)==row['wholeBytesSha256'] and sha(base)==row['wholeLfSha256']
  expected=row['candidateLfSha256']
 after=base;positions=[]
 for r in hunks:
  old=r['before'].encode();new=r['after'].encode()
  if 'beforeSnippetSha256' in r:
   assert sha(old)==r['beforeSnippetSha256'] and sha(new)==r['afterSnippetSha256']
  assert after.count(old)==1,(name,r.get('name',r.get('index')))
  at=after.index(old);positions.append((at,old,new));after=after[:at]+new+after[at+len(old):]
 assert sha(after)==expected,name
 reverse=after
 for at,old,new in reversed(positions):
  assert reverse[at:at+len(new)]==new
  reverse=reverse[:at]+old+reverse[at+len(new):]
 assert reverse==base,name
 changes.append((name,before,after,len(hunks)))
registry=read(REPO/'desktop/upstream-sources.json')
selected=json.loads(registry);assert len(selected['sources'])==1169 and len(selected['resources'])==213

# Every frozen row, full family, unique hunk and inverse is verified before
# any Candidate write. Shared existing whole files are never copied wholesale.
targets=[];changed=[]
for name,b in copy:
 write(OUT/'after'/name,b);write(REPO/name,b);targets.append(dict(path=name,sha256Bytes=sha(b)))
for name,before,after,count in changes:
 write(OUT/'before'/name,before);write(OUT/'after'/name,after);write(REPO/name,after)
 changed.append(dict(path=name,beforeSha256Bytes=sha(before),afterSha256Bytes=sha(after),
  beforeSha256LF=sha(lf(before)),afterSha256LF=sha(after),exactHunks=count,indexedInverse=True))
assert read(REPO/'desktop/upstream-sources.json')==registry
dump(OUT/'installed.json',dict(phase=74,baseCommit=head,newManual=6,existingFamilies=4,exactHunks=17,
 preparedPackets=[dict(path=str(CAP),sha256Frozen=sha(capraw),raw=144),dict(path=str(FAIL),sha256Frozen=sha(failraw),raw=103)],
 targets=targets,changedFiles=changed,newOriginalSourceIdentities=0,newResources=0,newDependencies=0,
 originalRegistryUnchanged=True,sameNativeSessionDecoderQueryInstalled=True,sameObserverByteFailureRecoveryInstalled=True,
 completeRootMountAccepted=False,actualWindowsLeaseAccepted=False,desktopExeReplaced=False))
print(json.dumps(dict(newManual=6,existingFamilies=4,exactHunks=17,registryUnchanged=True)))
