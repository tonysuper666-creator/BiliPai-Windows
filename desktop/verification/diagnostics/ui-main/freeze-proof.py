from pathlib import Path
import hashlib,json,os,shutil
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[2]
def ext(p):
    value=str(p.absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
def dump(n,v):(HERE/n).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
proof=json.loads((HERE/'compile-and-run-proof.json').read_text())
run=Path(proof['acceptedRun']);result=json.loads((run/'result.json').read_text())
assert proof['proofCompleted'] and proof['allRequestedUiFlowsPassed'] and result['actualPointerPairs']==96
assert all(r['passed'] for key in ['normalFlows','initialIoFailureFlows','largeFontViewerFlows','largeFontIoFailureFlows'] for r in result[key])
assert sha(Path(proof['compiledSources'][0]['path']))==proof['compiledSources'][0]['sha256Bytes']
snapshot=ROOT/'desktop/.local/diagnostics-viewer-product-snapshot/manifest.json'
assert sha(snapshot)==proof['snapshotManifestSha256Bytes']
shutil.copyfile(snapshot,HERE/'baseline-manifest.json')
old=ROOT/'desktop/.local/diagnostics-product-ui-proof'
old_contract=json.loads((old/'frozen-proof.json').read_text())
assert sha(old/'frozen-proof.json')=='4fda0a50144fe9848a4ab75135044994938eab92e6b1b1ee577c48d91c7f573c'
assert sha(old/'artifact-manifest.json')==old_contract['artifactManifestSha256Bytes']
for pin in json.loads((old/'artifact-manifest.json').read_text())['files']:
    assert sha(old/pin['path'])==pin['sha256Bytes'],pin['path']
pins=json.loads((HERE/'dependency-identities.json').read_text())
for pin in pins:assert sha(Path(pin['path']))==pin['sha256Bytes']
sources=['desktop/src/main/kotlin/com/bilipai/desktop/appearance/DesktopWindowConfiguration.kt',
 'desktop/third-party/miuix5157/upstream/miuix-ui/src/commonMain/kotlin/layout/DialogContentLayout.kt',
 'design-system/src/main/java/com/android/purebilibili/core/ui/AdaptiveDialogComponents.kt']
dump('window-and-renderer-source-identities.json',[dict(path=p,sha256Bytes=sha(ROOT/p)) for p in sources])
table='\n'.join(f"| {r['style']} | {r['theme']} | {r['height']} | PASS | 9 |" for r in result['normalFlows'])
handoff=f'''# Corrected immutable diagnostics UI proof

**24 actual flows PASS** against snapshot `{proof['snapshotManifestSha256Bytes']}`: eight complete original-controls/consent/viewer flows, eight independent initial IO-denial flows, four large-font viewer/export/clear/dismiss flows, and four large-font IO-denial flows. There are **96 real Press/Release pairs and 72 actual PNGs**. These are isolated UI flows, not 24 new JUnit methods.

| Renderer | Theme | Height | Complete normal flow | Actual pointer pairs |
|---|---|---:|---|---:|
{table}

The additional fontScale=1.5 groups use the real Compose LocalDensity at finite 960×720 for both styles/themes. Their original renderer and true window-configuration consumer are unchanged. `after-export-action-bounds.json` and `after-clear-action-bounds.json` record the actual OnClick-ancestor rectangles for all three actions, assert positive width/height, and assert the entire action rectangle remains within the viewport. The four large-font viewer cases additionally record initial bounds. Long text remains in the real scrolling log box; complete bytes are verified in actual exported files. These checks do not claim every long log line is simultaneously visible, nor that the original 40dp dialog action target became 48dp.

All eight normal groups use the real main initialization factory / installed upstream Logger bridges / process lifecycle, and persist the original global `settings/enhanced_diagnostic_logging_enabled` key in actual DesktopPluginStore. Default false, original consent cancellation without setting changes, original confirmation, successful atomic disk state, detailed recording, disable-and-delete, real viewer read, fixture-selected real file export, clear and actual close are tested. A second real store facade observes the same key. The lifecycle observes a **synthetic finite PlayerState flow**, verifies that title/subtitle text is excluded, and is drained/retired. No fake Store, actor, renderer or production class is compiled.

All 12 IO-failure groups lock only their task-owned actual basic.log with Win32 `CreateFileW` share=0. A separate JVM Files.readAllBytes call proves a real IOException before the original viewer starts. The actor remains active. The viewer displays safe messages, removes loading text, neither leaks file paths nor escapes coroutine errors, and closes through a real topmost-dialog pointer. After releasing the handle, original file bytes remain readable. No retired-actor replacement is used.

The only compiler input is DiagnosticProductUiFixture.kt under its own `.proof` package. The three immutable product JARs are SHA-bound; 231 historical external dependency entries (226 distinct JARs, five duplicate entries) are verified before/after execution. Fourteen loaded production class resources are SHA-verified and their CodeSource origins must be the actual new main-kotlin.jar; nine are the requested core diagnostics/control consumers. Window metrics use the actual LocalWindowInfo / LocalDensity implementation from main. The headless ImageComposeScene is a fixture host, not a substituted production Renderer.

Accepted run: `{run.name}`. Export chooser callbacks provide paths exclusively inside each fixture case; **native OS chooser is not tested**. No HWND, mpv, account data, HTTP, full Main or PluginRuntime is started. No shared Gradle, main source, or frozen 18-method actor test was changed. Root's previous 31 shared methods remain previous test scope and are not presented as re-executed for the Viewer change.

The old snapshot's failed matrix remains separately frozen in diagnostics-product-ui-proof (`4fda0a50144fe9848a4ab75135044994938eab92e6b1b1ee577c48d91c7f573c`); all 178 artifact hashes are rechecked here. Its Miuix failure has not been overwritten. The new result proves the same producer/viewer interaction on Root's narrow total-text-height / weighted-scroll change, with original WindowDialog and action bodies.

One earlier attempt in this new directory stopped before any UI checks: the longer fixture user.home made Skiko's native ICU extraction path exceed the Win32 loader limit. Its hs_err/runtime log is preserved in runs/31e038fc and is an infrastructure failure, not a Viewer verdict. The accepted JVM uses a newly-created short task-owned bp-dg-* environment; no user's existing settings or cache is read. Native extraction files are separately hashed. Only the accepted run/classes and explicitly listed historical crash evidence form this frozen handoff.
'''
(HERE/'HANDOFF.md').write_text(handoff,encoding='utf-8',newline='\n')
files=[HERE/n for n in ['DiagnosticProductUiFixture.kt','compile-and-run.py','freeze-proof.py','compiler.args','runtime.args','compile.log','runtime.log','compile-and-run-proof.json','actual-class-pins.json','dependency-identities.json','toolchain-identities.json','fixture-class-identities.json','baseline-manifest.json','owned-env.json','window-and-renderer-source-identities.json','HANDOFF.md']]
for directory in [run,Path(proof['fixtureClasses'])]:
    prefix=str(ext(directory))
    for folder,_,names in os.walk(ext(directory)):
        for name in names:
            file=Path(folder)/name;files.append(directory/str(file)[len(prefix)+1:])
for n in ['runs/31e038fc/runtime.log','runs/31e038fc/hs_err_pid35716.log']:
    assert (HERE/n).is_file();files.append(HERE/n)
inventory=[dict(path=p.relative_to(HERE).as_posix(),sha256Bytes=sha(p),bytes=ext(p).stat().st_size) for p in sorted(files)]
environment=Path(proof['taskOwnedShortEnvironment']);owned=[]
assert json.loads((environment/'fixture-owner.json').read_text())['snapshot']==proof['snapshotManifestSha256Bytes']
for folder,_,names in os.walk(ext(environment)):
    for name in names:
        file=Path(folder)/name;owned.append(dict(path=str(file)[4:],sha256Bytes=sha(file),bytes=file.stat().st_size))
dump('artifact-manifest.json',dict(frozen=True,allRequestedUiFlowsPassed=True,files=inventory,externalTaskOwnedEnvironmentFiles=owned))
dump('frozen-proof.json',dict(frozen=True,productUiFlowsPassed=True,normalOriginalUiFlows=8,initialActualIoFailureFlows=8,
 largeFontViewerFlows=4,largeFontActualIoFailureFlows=4,actualPointerPairs=96,screenshots=72,
 snapshotManifestSha256Bytes=proof['snapshotManifestSha256Bytes'],mainKotlinJarSha256Bytes=pins[0]['sha256Bytes'],acceptedRun=run.name,
 artifactManifestSha256Bytes=sha(HERE/'artifact-manifest.json'),artifactFiles=len(inventory),externalTaskOwnedEnvFiles=len(owned),
 handoffSha256Bytes=sha(HERE/'HANDOFF.md'),previousFailedProofSha256Bytes=sha(old/'frozen-proof.json'),
 mainEdits=0,compiledProductionOverrides=0,storeOrRendererReplacements=0,wholeMainOrPluginRuntime=False,nativeWindowCreated=False,nativeOsChooserVerified=False))
print(json.dumps(dict(frozenProofSha256Bytes=sha(HERE/'frozen-proof.json'),artifactManifestSha256Bytes=sha(HERE/'artifact-manifest.json'),files=len(inventory),environmentFiles=len(owned),acceptedRun=run.name)))
