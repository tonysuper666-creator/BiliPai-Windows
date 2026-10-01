from pathlib import Path
import hashlib, json, subprocess
H = Path(__file__).resolve().parent; MAIN = H.parents[2]; REPO = MAIN.parent/'BiliPai-v023'
LANE = MAIN/'desktop/.local/stable-original-progress-storage-parity'
OUT = H/'original-progress-install75'; assert not OUT.exists()

def wide(p):
    s = str(Path(p).absolute()); prefix = chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) else prefix+s)

def read(p): return wide(p).read_bytes()
def sha(b): return hashlib.sha256(b).hexdigest()
def lf(b): return b.replace(b'\r\n', b'\n')
def write(p, b):
    wide(p).parent.mkdir(parents=True, exist_ok=True); wide(p).write_bytes(b)

head = subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip()
assert head.startswith('4fb5d663')
assert not subprocess.check_output(['git','status','--porcelain'],cwd=REPO,text=True).strip()
raw = read(LANE/'frozen-handoff.json')
assert sha(raw) == 'ccb08af8a9ce7e887eb87c0634cad273d918c0b61ee2020d963e77c677f3d551'
packet = json.loads(raw); assert len(packet['artifacts']) == 34
for row in packet['artifacts']:
    b = read(LANE/row['path']); assert sha(b) == row['sha256Bytes'] and len(b) == row['bytes'], row['path']
whitelist = json.loads(read(LANE/'install-whitelist.json')); assert len(whitelist) == 3
manual = whitelist[0]; assert manual['kind'] == 'new'
manualBytes = read(LANE/manual['source']); assert sha(manualBytes) == manual['sha256Bytes']
assert not wide(REPO/manual['destination']).exists()
hunks = json.loads(read(LANE/'producer-hunks.json')); assert len(hunks['edits']) == 2
producer = hunks['target']; before = read(REPO/producer); base = lf(before)
assert sha(base) == hunks['baseSHA256LF']; after = base; positions = []
for row in hunks['edits']:
    old = row['before'].encode(); new = row['after'].encode(); assert after.count(old) == 1
    at = after.index(old); positions.append((at, old, new)); after = after[:at]+new+after[at+len(old):]
assert sha(after) == hunks['desiredSHA256LF']; inverse = after
for at, old, new in reversed(positions):
    assert inverse[at:at+len(new)] == new; inverse = inverse[:at]+old+inverse[at+len(new):]
assert inverse == base
delta = json.loads(read(LANE/'registry-delta.json')); identity = delta['newIdentity']
registryPath = 'desktop/upstream-sources.json'; oldRegistry = read(REPO/registryPath); registry = json.loads(oldRegistry)
assert registry['upstreamCommit'] == delta['pinnedTarget'] == '3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
assert len(registry['sources']) == 1169 and len(registry['resources']) == 213
assert all(row['path'] != identity['path'] for row in registry['sources'])
assert sha(lf(read(REPO/identity['path']))) == identity['sha256']
originalSources = registry['sources'][:]; originalResources = registry['resources'][:]
registry['sources'].append(identity)
assert registry['sources'][:-1] == originalSources and registry['resources'] == originalResources
newRegistry = (json.dumps(registry,ensure_ascii=False,indent=2)+'\n').encode()
changes = [(manual['destination'], None, manualBytes), (producer, before, after), (registryPath, oldRegistry, newRegistry)]
# All packet rows, pin, exact hunks and inverse are checked before any production write.
for name, previous, current in changes:
    if previous is not None: write(OUT/'before'/name, previous)
    write(OUT/'after'/name, current); write(REPO/name, current)
installed = dict(phase=75,baseCommit=head,frozenPacketSha256=sha(raw),preparedRaw=34,newManual=1,
    existingProducerFamilies=1,exactProducerHunks=2,indexedInverse=True,newSourceIdentities=1,
    sourceIdentityCount=1170,resourceCount=213,newDependencies=0,registryUnionPreservesExistingRows=True,
    targets=[dict(path=name,beforeSha256Bytes=sha(previous) if previous is not None else None,
        afterSha256Bytes=sha(current)) for name,previous,current in changes],
    originalProgressBodyInstalled=True,globalRootProgressMounted=False,wholeRootAccepted=False,desktopExeReplaced=False)
write(OUT/'installed.json',(json.dumps(installed,indent=2)+'\n').encode())
print(json.dumps(dict(newManual=1,exactProducerHunks=2,sourceIdentities=1170,resources=213)))
