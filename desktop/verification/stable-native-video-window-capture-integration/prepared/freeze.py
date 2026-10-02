from pathlib import Path
import hashlib, json
H = Path(__file__).resolve().parent; MAIN = H.parents[2]
REVIEW = MAIN/'desktop/.local/stable-native-video-window-capture-review'

def wide(p):
    s = str(Path(p).absolute()); prefix = chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) else prefix+s)

def sha(b): return hashlib.sha256(b).hexdigest()
def read(p): return wide(p).read_bytes()
def put(p,b): wide(p).parent.mkdir(parents=True,exist_ok=True); wide(p).write_bytes(b)
assert not (H/'frozen-handoff.json').exists()
initial = read(REVIEW/'receipt.json'); final = read(REVIEW/'postfix-receipt.json')
assert sha(initial) == '3e69b515ac9adfb5f32d467ba9d4168d6f64c3d4ddbda1524ea34a384b7d3d06'
assert sha(final) == '94571c21b3a5680ea8a762dd89d4377251a985e67c3195fb0a4d0e4401abef10'
put(H/'review/receipt.json',initial); put(H/'review/postfix-receipt.json',final)
result = json.loads(read(H/'runs/03/result.json'))
assert result['exit'] == 0 and result['classCount'] == 23 and result['sourceCount'] == 3
inputs = json.loads(read(H/'runs/03/inputs.json'))
for row in inputs['inputs']:
    assert sha(read(row['path'])) == row['sha256Bytes'] == sha(read(row['copy']))
S = MAIN/'desktop/.local/stable-product-snapshot-74'
for name, digest in [('manifest.json','6e6f2ca97fc5e98f00f3806ad7ffeceff271466a20cc90d68479e37e38ffe210'),
        ('ordered-runtime-cp.json','e5e3a3375a6d5a85fab9d51343af496bb16b46eff71aadeb98e227b766dbf2cd')]:
    b = read(S/name); assert sha(b) == digest; put(H/'reference-actual74'/name,b)
for row in json.loads(read(S/'ordered-runtime-cp.json')): assert sha(read(row['path'])) == row['sha256Bytes']
families = json.loads(read(H/'baseline-families.json')); hunks = json.loads(read(H/'exact-hunks.json'))
assert len(families) == 2 and len(hunks) == 16
for row in families:
    value = read(H/'baseline'/row['path']).replace(b'\r\n',b'\n'); base = value; positions = []
    assert sha(value) == row['beforeSha256LF']
    for edit in [e for e in hunks if e['path'] == row['path']]:
        old = edit['before'].encode(); new = edit['after'].encode()
        assert sha(old) == edit['beforeSnippetSha256'] and sha(new) == edit['afterSnippetSha256']
        assert value.count(old) == 1; at = value.index(old); positions.append((at,old,new))
        value = value[:at]+new+value[at+len(old):]
    assert value == read(H/'prepared/existing'/row['path']) and sha(value) == row['afterSha256LF']
    for at,old,new in reversed(positions):
        assert value[at:at+len(new)] == new; value = value[:at]+old+value[at+len(new):]
    assert value == base
integration = '''Copy only manual/com/bilipai/desktop/ui/DesktopWindowsVideoCaptureOwner.kt to the same desktop/src/main/kotlin package. Apply the sixteen exact literal hunks in exact-hunks.json to the two existing source families, after baseline/inverse verification; do not replace either whole existing family. No original identity, resource, dependency or actor is added.

Root creates ONE shared CaptureOwner for its actual Root Window and app lifetime. Pass the same instance to the required WindowsWindowPort captureProtection argument. Section's required acquireScreenshotOrientationLock(Boolean) delegates to that exact WindowPort. Protection follows the original fullscreen && screenLocked intent and current UI entry, including same-entry part/quality/recovery changes. It is not native-command admission. Root refreshes the shared capture owner on actual entry retirement/switch, and closes entry WindowPorts before Root capture owner/window teardown. All EDT/native calls remain outside Store/entry/native gates.

Use the new Popup overload(surfaceSize, anchor, onOpaque, onNative, presented, content). The actual Window callback is a separate typed Window argument; never cast the existing opaque Any identity. The older three/four/five-argument composable APIs remain wrappers with presented=true. PiP passes false, hides the same popup and preserves its independent original Section composition; it does not dispose/recreate/move the whole Section between Recomposer instances. Keep the Root anchor/container mounted and its existing nonzero geometry. Only the actual player.surface is moved by the existing PiP owner.

Connect onNativeForegroundWindowAvailability to the shared CaptureOwner.onNativeWindowAvailability. Only actual Root-owned windows are admitted. Capture state reports actual API request status and unavailable reasons. SetWindowDisplayAffinity is a Windows capture policy, not DRM, physical screen-capture pixel validation, or a universal capture guarantee. The shared Root setFullscreen callback must also reject mode changes while CaptureOwner.orientationLocked; guarding only WindowPort.requestOrientation does not cover F11/Escape/direct fullscreen leases.

Acquire/close uses own-token removal and remaining-current-request reconciliation. The acquire-return/map-registration versus close race is repaired by a post-registration same-entry ownership check that removes/closes its own lease. Initial independent review bytes and post-fix review are retained separately. Deferred exit/playback operations retain their stricter exact native snapshot checks.

Latest prospective compile runs/03: three inputs, 23 classes, two declared existing-family overrides over immutable actual74/101 with the matching Compose compiler; no whole VM/Holder/native replacement. Runs/02 retains the earlier compiled source; runs/01 records a Windows input-copy path preflight failure before compiler launch. No Window/API/physical GUI runtime, full Root mount or desktop EXE deployment is accepted by this source packet.
'''
put(H/'ROOT-INTEGRATION.md',integration.encode())
raw = []; excluded = []
for file in sorted(wide(H).rglob('*')):
    if not file.is_file() or file.name == 'frozen-handoff.json': continue
    rel = file.relative_to(wide(H)).as_posix(); b = file.read_bytes()
    row = dict(path=rel,size=len(b),sha256Bytes=sha(b))
    if '/classes/' in rel or '/__pycache__/' in rel or file.suffix == '.pyc': excluded.append(row)
    else: raw.append(row)
manual = [dict(source='manual/'+p.relative_to(H/'manual').as_posix(),destination='desktop/src/main/kotlin/'+p.relative_to(H/'manual').as_posix(),
    sha256Bytes=sha(read(p))) for p in (H/'manual').rglob('*.kt')]
packet = dict(schemaVersion=1,task='stable-native-video-window-capture-parity',files=raw,excludedRebuildable=excluded,
    copyWhitelist=manual,existingFamilies=2,exactHunks=16,sourceOnly=True,
    prospectiveCompiledSnapshot=74,runtimeEntries=101,declaredProductionFamilyOverrides=2,
    nativeWindowRuntimeAccepted=False,physicalCaptureAccepted=False,wholeRootAccepted=False,desktopExeReplaced=False)
data = (json.dumps(packet,indent=2)+'\n').encode(); put(H/'frozen-handoff.json',data)
print(json.dumps(dict(raw=len(raw),manual=len(manual),existingFamilies=2,exactHunks=16,manifestSha256=sha(data))))
