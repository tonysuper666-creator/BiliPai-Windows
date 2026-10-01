from pathlib import Path
import hashlib, json
H=Path(__file__).resolve().parent
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def dump(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

# Retain exact source bytes for both successful narrow compiles. Only the
# Windows registration bookkeeping changed between them; decoder test inputs
# remain identical. A historical compile is never relabelled as current source.
window=H/'manual/com/bilipai/desktop/ui/DesktopOriginalVideoWindowsWindowPort.kt'
current=window.read_text()
old=current
old=old.replace('    private var chromeSpec: VideoDetailSystemBarsApplySpec? = null\n    private var chromeSubject: Subject? = null\n    private val fullscreenLeases = linkedMapOf<Long, Subject>() // EDT only.\n    private var fullscreenRestore = false\n','')
old=old.replace('chromeLease?.close(); chromeLease = null; chromeSpec = null; chromeSubject = null','chromeLease?.close(); chromeLease = null')
old=old.replace('            if (chromeLease != null && chromeSpec == spec && chromeSubject?.accepted === subject.accepted) return@onEdt\n','')
old=old.replace('val old = chromeLease; chromeLease = next; chromeSpec = spec; chromeSubject = subject; old?.close()', 'val old = chromeLease; chromeLease = next; old?.close()')
start=old.index('    fun acquireFullscreen(): AutoCloseable {')
end=old.index('    override fun close() {',start)
old=old[:start]+'''    fun acquireFullscreen(): AutoCloseable {
        val subject = capture()
        var previous = false
        var registered = false
        onEdt { if (subject.owns()) { previous = isFullscreen(); setFullscreen(true); registered = true } }
        val released = AtomicBoolean()
        return AutoCloseable {
            if (released.compareAndSet(false, true)) onEdt {
                if (registered && subject.owns()) setFullscreen(previous)
            }
        }
    }
'''+old[end:]
old=old.replace('autoPipSubject = null; fullscreenLeases.clear(); root.removeWindowListener(event)','autoPipSubject = null; root.removeWindowListener(event)')
for run in ['capabilities-01','capabilities-02']:
    target=H/'runs'/run
    inputs=json.loads((target/'inputs.json').read_text())['inputs']
    for item in inputs:
        path=Path(item['path']); rel=path.relative_to(H)
        data=old.encode() if run=='capabilities-01' and path==window else path.read_bytes()
        assert hashlib.sha256(data).hexdigest()==item['sha256Bytes'],(run,rel)
        out=target/'source-inputs'/rel
        out.parent.mkdir(parents=True,exist_ok=True)
        if out.exists():assert out.read_bytes()==data
        else:out.write_bytes(data)

before=(H/'runs/capabilities-01/classes')
after=(H/'runs/capabilities-02/classes')
same=[]
for file in before.rglob('*.class'):
    rel=file.relative_to(before)
    if str(rel).replace('\\','/').startswith('com/bilipai/desktop/player/') or any(x in file.name for x in ['DesktopOriginalMpvPlaybackCapabilities','DesktopWindowsVideoDisplayCapabilit']):
        assert sha(file)==sha(after/rel),rel
        same.append(str(rel).replace('\\','/'))
dump(H/'native-tested-bytecode-identity.json',dict(nativeRun='native-02',compiledCohort='capabilities-01',
    currentCompile='capabilities-02',equalClasses=same,
    windowLeasesNativeAccepted=False,actualMonitorAccepted=False,MainShellAccepted=False))

manual=[dict(path=str(p.relative_to(H/'manual')).replace('\\','/'),sha256Bytes=sha(p)) for p in sorted((H/'manual').rglob('*.kt'))]
edits=json.loads((H/'mpv-exact-hunks.json').read_text())
assert len(manual)==5 and len(edits['hunks'])==5
assert json.loads((H/'runs/native-02/runtime-result.json').read_text())['exit']==0
assert json.loads((H/'runs/capabilities-02/result.json').read_text())['exit']==0
files=[p for p in sorted(H.rglob('*')) if p.is_file() and p.name!='frozen-handoff.json' and '__pycache__' not in p.parts]
manifest=dict(schema='bilipai-prepared-native-capabilities-window-v1',snapshot=73,orderedEntries=101,
    manual=manual,existingFamilies=[edits['family']],exactHunks=5,newDependencies=0,
    narrowCompile='capabilities-02',sameSessionNativeDecoderProof='native-02',nativeChecks=20,
    actualMonitorAccepted=False,windowLeaseRuntimeAccepted=False,MainShellAccepted=False,
    files=[dict(path=str(p.relative_to(H)).replace('\\','/'),size=p.stat().st_size,sha256Bytes=sha(p)) for p in files])
assert not (H/'frozen-handoff.json').exists()
dump(H/'frozen-handoff.json',manifest)
print(json.dumps(dict(files=len(files),manual=5,existingFamilies=1,hunks=5,sha256Frozen=sha(H/'frozen-handoff.json'))))
