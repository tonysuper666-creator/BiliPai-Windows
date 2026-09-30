from pathlib import Path
import hashlib,json,os,shutil
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[2]
def ext(p):
    value=str(p.absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
def dump(n,v):(HERE/n).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
proof=json.loads((HERE/'compile-and-run-proof.json').read_text())
run=Path(proof['acceptedRun']);result=json.loads((run/'result.json').read_text())
assert proof['proofCompleted'] and not proof['allRequestedUiFlowsPassed'] and result['actualPointerPairs']==70
assert sum(r['passed'] for r in result['normalFlows'])==4 and all(r['passed'] for r in result['initialIoFailureFlows'])
assert sha(Path(proof['compiledSources'][0]['path']))==proof['compiledSources'][0]['sha256Bytes']
snapshot=ROOT/'desktop/.local/diagnostics-main-product-snapshot/manifest.json'
assert sha(snapshot)==proof['snapshotManifestSha256Bytes']
shutil.copyfile(snapshot,HERE/'baseline-manifest.json')
rows=result['normalFlows']
table='\n'.join(f"| {r['style']} | {r['theme']} | {r['height']} | {'PASS' if r['passed'] else 'FAIL: '+r['failedStage']} | {r['actualPointerPairs']} |" for r in rows)
handoff=f'''# Immutable diagnostics UI proof — failed product matrix preserved

The original main snapshot `{proof['snapshotManifestSha256Bytes']}` is the only production code used. The fixture compiles one new package only; 0 production overrides and 0 Store/Renderer replacements. Thirteen actual class-resource digests and CodeSource origins include the requested nine core consumers. The historical 231 external dependency entries contain 226 distinct JARs and five duplicate entries; every entry is verified before and after execution.

| Renderer | Theme | Height | Complete normal flow | Actual pointer pairs |
|---|---|---:|---|---:|
{table}

All eight independent initial real IO failure flows PASS: Win32 `CreateFileW` share=0 denies reading the fixture's actual basic.log; a direct JVM read first proves IOException. The real viewer shows the safe message, removes its loading text, does not expose the path or escape a coroutine exception, and dismisses through a real topmost-dialog pointer. After unlocking, the original task file is unchanged/readable. This is not a retired-actor substitute.

There are **70 actual Press/Release pairs and 50 PNGs** in the accepted run `{run.name}`. All eight styles/themes/heights pass original consent cancel/confirm, same global settings key disk persistence and enhanced detail removal. Four Material3 flows also pass actual viewer/export/clear/dismiss. For Miuix 900, the actual exported file exists but its success result consumes the action row. For Miuix 720, the large initial log already consumes the action row, so the fixture correctly refuses to click a same-text underlay setting. The whole product matrix is **failed**, not a success gate. `failed-flow-actual.png` and `failed-flow-semantics.json` bind each defect to actual pixels and zero-size action bounds. The two 900 flows additionally preserve `after-export-result.png` / `after-export-semantics.json` and their real exported files.

Source cause: exact pinned Miuix `DialogContentLayout.kt:351` limits large-screen content to 2/3 of actual window height; original `AdaptiveDialogComponents.kt` measures its text box before its action row. DesktopLocalDiagnosticViewer fixes log height at 420dp and appends result outside that scrolling box. A narrow text-slot height cap plus weighted scroll, leaving all renderer/body/action classes unchanged, is suitable for the next immutable snapshot. The real reusable window metric is `DesktopWindowConfiguration.current.screenHeightDp`, sourced from LocalWindowInfo container pixels / LocalDensity.

Each normal case opens the actual main factory, installs both upstream logging bridges, uses the actual lifecycle to observe a **synthetic finite PlayerState flow** and drains it before retirement. This verifies neither mpv state production nor the entire Main/PluginRuntime. The fixture creates no HWND, starts no native player, reads no user data and sends no HTTP/account request. The export chooser is an explicitly fixture-supplied local Path; no native OS chooser acceptance is claimed. Fresh task-only env roots and stores retain the evidence files. Main and the frozen 18-method test file are untouched.

Earlier incomplete attempts in this directory are historical only; only the accepted run, accepted classes and pinned files in artifact-manifest.json form this frozen result. No old result will be replaced by the corrected snapshot's outcome.
'''
(HERE/'HANDOFF.md').write_text(handoff,encoding='utf-8',newline='\n')
files=[HERE/n for n in ['DiagnosticProductUiFixture.kt','compile-and-run.py','freeze-proof.py','compiler.args','runtime.args','compile.log','runtime.log','compile-and-run-proof.json','actual-class-pins.json','dependency-identities.json','toolchain-identities.json','fixture-class-identities.json','baseline-manifest.json','HANDOFF.md']]
for directory in [run,Path(proof['fixtureClasses'])]:
    prefix=str(ext(directory))
    for folder,_,names in os.walk(ext(directory)):
        for name in names:
            file=Path(folder)/name;rel=str(file)[len(prefix)+1:];files.append(directory/rel)
inventory=[dict(path=p.relative_to(HERE).as_posix(),sha256Bytes=sha(p),bytes=ext(p).stat().st_size) for p in sorted(files)]
dump('artifact-manifest.json',dict(frozen=True,allRequestedUiFlowsPassed=False,files=inventory))
dump('frozen-proof.json',dict(frozen=True,productMatrixPassed=False,normalFlowsPassed=4,normalFlowsFailed=4,initialActualIoFailureFlowsPassed=8,
 snapshotManifestSha256Bytes=proof['snapshotManifestSha256Bytes'],acceptedRun=run.name,actualPointerPairs=70,screenshots=50,
 artifactManifestSha256Bytes=sha(HERE/'artifact-manifest.json'),artifactFiles=len(inventory),handoffSha256Bytes=sha(HERE/'HANDOFF.md'),
 mainEdits=0,productionOverrides=0,wholeMainOrPluginRuntime=False,nativeOsChooserVerified=False))
print(json.dumps(dict(frozenProofSha256Bytes=sha(HERE/'frozen-proof.json'),artifactManifestSha256Bytes=sha(HERE/'artifact-manifest.json'),files=len(inventory),acceptedRun=run.name)))
