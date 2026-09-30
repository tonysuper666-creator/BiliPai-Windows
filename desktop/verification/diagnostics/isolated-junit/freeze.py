from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[2]
def ext(p):
    s=str(p.absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
def write(n,obj):(HERE/n).write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
target='desktop/src/test/kotlin/com/bilipai/desktop/diagnostics/DesktopDiagnosticsTest.kt'
assert sha(ROOT/target)==sha(HERE/'prepared'/target)
proof=json.loads((HERE/'compile-evidence.json').read_text());assert proof['passed'] and proof['junitSucceeded']==18
rows=[]
for p in sorted(ext(HERE).rglob('*')):
    if not p.is_file() or '__pycache__' in p.parts or 'owned-env' in p.parts or p.name in ['artifact-manifest.json','test-contract.json']:continue
    rows.append(dict(path=p.relative_to(ext(HERE)).as_posix(),bytes=p.stat().st_size,sha256Bytes=sha(p)))
write('artifact-manifest.json',dict(frozen=True,extendedWindowsPathTraversal=True,files=rows))
write('test-contract.json',dict(frozen=True,mainProductionEdited=False,sharedGradleInvoked=False,
    ownsExactly=[dict(path=target,newFile=True,sha256Bytes=sha(ROOT/target))],actualJUnitDiscovered=18,actualJUnitSucceeded=18,
    actualJUnitSkipped=0,originalActorDiskGroups=14,additionalColdProcessAndWindowsBoundaryGroups=4,
    noAccountHttpNativeWindowOrUserData=True,snapshotManifestSha256Bytes=proof['snapshotManifestSha256Bytes'],
    explicitDiagnosticOverrides=proof['diagnosticOverrides'],generatedOriginalPolicies=proof['generatedOriginalPolicies'],
    compileEvidenceSha256Bytes=sha(HERE/'compile-evidence.json'),artifactManifestSha256Bytes=sha(HERE/'artifact-manifest.json'),
    rootFocusedSuite='com.bilipai.desktop.diagnostics.DesktopDiagnosticsTest',handoff='HANDOFF.md'))
print('test',sha(ROOT/target));print('contract',sha(HERE/'test-contract.json'));print('manifest',sha(HERE/'artifact-manifest.json'),'artifacts',len(rows))
