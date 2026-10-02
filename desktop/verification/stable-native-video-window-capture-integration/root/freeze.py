from pathlib import Path
import hashlib,json,sys
sys.stdout.reconfigure(encoding='utf-8');sys.dont_write_bytecode=True
H=Path(__file__).resolve().parent;MAIN=H.parents[2];REPO=MAIN.parent/'BiliPai-v023'
LANE=MAIN/'desktop/.local/stable-native-video-window-capture-parity'
S=MAIN/'desktop/.local/stable-product-snapshot-79'
OUT=REPO/'desktop/verification/stable-native-video-window-capture-integration'
assert not OUT.exists();OUT.mkdir(parents=True)
def wide(p):
    s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def put(relative,b):
    p=wide(OUT/relative);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b)
def copy(relative,p):put(relative,read(p))
assert sha(read(LANE/'frozen-handoff.json'))=='9172cb93c4ed6837328c70e5fd5f0ca10a6d954928ee192989359b792ba34a6e'
frozen=json.loads(read(LANE/'frozen-handoff.json'))
assert len(frozen['files'])==33
for row in frozen['files']:
    b=read(LANE/row['path']);assert sha(b)==row['sha256Bytes'] and len(b)==row['size']
    put('prepared/'+row['path'],b)
copy('prepared/frozen-handoff.json',LANE/'frozen-handoff.json')
assert sha(read(S/'manifest.json'))=='9f489f0e107470b79209ce8fa6a54e34050faadef335c831351afc8cc2813b8e'
assert sha(read(S/'ordered-runtime-cp.json'))=='66b099ef5a956392dbb4672f8f77ace7aee33bd73f1c434f34d23f0b6b5d0d9b'
snapshot=json.loads(read(S/'manifest.json'))
installation=json.loads(read(H/'installation.json'))
result=json.loads(read(H/'runs/01/result.json'))
assert result['passed'] and result['assertions']==30 and result['productionOverrides']==0
assert result['realOwnedHiddenHwndReadback'] and not result['completeRootMounted']
for row in installation['sourceFamilies']:
    b=read(REPO/row['path']);assert sha(b.replace(b'\r\n',b'\n'))==row['afterSHA256LF']
    pin=next(p for p in snapshot['inputs'] if p['path']==row['path']);assert sha(b)==pin['sha256Bytes']
    copy('product-source/'+Path(row['path']).name,REPO/row['path'])
relative=installation['newManualPath'];b=read(REPO/relative)
assert sha(b)==installation['newManualSHA256Bytes']
assert sha(b)==next(p for p in snapshot['inputs'] if p['path']==relative)['sha256Bytes']
copy('product-source/'+Path(relative).name,REPO/relative)
for name in ('install.py','installation.json','CaptureOwnerProof.kt','run.py','freeze.py','ROOT-INTEGRATION.md'):
    copy('root/'+name,H/name)
for file in sorted(wide(H/'runs/01').rglob('*')):
    if file.is_file():copy('root/run01/'+str(file.relative_to(wide(H/'runs/01'))).replace('\\','/'),file)
for name in ('manifest.json','ordered-runtime-cp.json'):copy('snapshot79/'+name,S/name)
audit=MAIN/'desktop/.local/upstream-v023-audit/jvm-method-name-audit-79.json'
assert json.loads(read(audit))['issueCount']==0
copy('snapshot79/'+audit.name,audit)
for name in ('classes-79.log','export-79.log'):
    copy('build/'+name,REPO/'desktop/.local/stable-build-repair'/name)
rows=[]
for file in sorted(wide(OUT).rglob('*')):
    if file.is_file():
        b=read(file);rows.append(dict(path=str(file.relative_to(wide(OUT))).replace('\\','/'),sizeBytes=len(b),sha256Bytes=sha(b)))
manifest=dict(upstreamTag='v0.2.3',upstreamCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
    sourceRegistryCount=1170,resources=213,actualSnapshot=79,runtimeEntries=101,
    productionOverrides=0,fixtureAssertions=30,fixtureClasses=7,verifiedUniqueProductClassOrigins=2,
    wholeDesktopClassesPassed=True,illegalJvmMethods=0,realWindowsDisplayAffinityApi=True,
    realOwnedHiddenHwndReadback=True,osInputInjected=False,foreignApplicationWindowsOperated=False,
    physicalCapturePixelsAccepted=False,fullWindowPortRuntimeAccepted=False,completeRootMounted=False,
    desktopExeReplaced=False,artifacts=rows)
put('artifact-manifest.json',(json.dumps(manifest,indent=2)+'\n').encode())
put('README.md',b'''# Native Windows capture owner integration

Installs the frozen Window33 popup/WindowPort contract and one Root capture owner
without changing source registry or dependencies. Actual79 complete classes and
the JVM method-name audit pass. A fixture using the 101 frozen runtime entries
and zero product overrides passes 30 assertions against real user32 display
affinity readback of three owned hidden test HWNDs. No OS input or other app's
window is used. The two exercised product class origins are uniquely verified.

This accepts the capture owner API/lifetime behavior. Complete Root/WindowPort,
user-visible popup and PiP, capture pixels, player interactions and packaged EXE
remain pending. See root/ROOT-INTEGRATION.md for the concrete mounting contract.
All 33 previously frozen raw artifacts, exact installed production sources,
compiler/classpath pins, fixture output, full build logs and actual79 metadata
are retained byte-for-byte. Initial and final independent source reviews remain
in the original frozen handoff, with their original verification limits.
''')
print(json.dumps(dict(rawArtifacts=len(rows),manifestSHA256Bytes=sha(read(OUT/'artifact-manifest.json')))))
