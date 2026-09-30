from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[2]
def ext(p):
    s=str(p.absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
def write(n,obj):(HERE/n).write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
rows=json.loads((HERE/'owned-base-files.json').read_text())
for row in rows:
    assert sha(ROOT/row['path'])==row['baseSha256Bytes'],row['path']
    assert sha(HERE/row['prepared'])==row['sha256Bytes'],row['prepared']
test='desktop/src/test/kotlin/com/bilipai/desktop/backup/DesktopBackupRestoreBarrierTest.kt'
assert not (ROOT/test).exists()
rows.append(dict(path=test,baseSha256Bytes=None,prepared='prepared/'+test,sha256Bytes=sha(HERE/'prepared'/test)))
write('owned-files.json',rows)
inventory=subprocess.run(['python',str(HERE/'prepared/desktop/tools/extract-upstream-settings.py'),
   '--repo',str(ROOT),'--inventory'],capture_output=True,text=True,encoding='utf-8',check=True)
(HERE/'source-inventory.json').write_text(inventory.stdout,encoding='utf-8',newline='\n')
shell=ROOT/'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt'
dialog=ROOT/'desktop/src/main/kotlin/com/bilipai/desktop/ui/BackupSettingsDialog.kt'
evidence=json.loads((HERE/'compile-evidence.json').read_text())
assert evidence['passed'] and [r['actualAnnotatedMethods'] for r in evidence['runs']]==[2,28]
for name in ['baseline-report.json','patched-report.json']:
    result=json.loads((HERE/name).read_text());assert result['passed'] and result['junitSkippedTests']==0
artifacts=[]
for file in sorted(ext(HERE).rglob('*')):
    if not file.is_file() or '__pycache__' in file.parts or file.name in ['artifact-manifest.json','restore-contract.json']:continue
    # Per-run empty LOCALAPPDATA directories are task isolation, not shipped source artifacts.
    if any(p.startswith('appdata-') for p in file.parts):continue
    artifacts.append(dict(path=file.relative_to(ext(HERE)).as_posix(),bytes=file.stat().st_size,sha256Bytes=sha(file)))
write('artifact-manifest.json',dict(frozen=True,extendedWindowsPathTraversal=True,files=artifacts))
write('restore-contract.json',dict(frozen=True,installed=False,sharedGradleInvoked=False,mainEdited=False,
  ownsExactly=rows,sourceInventoryAdditions=0,upstreamMethodsBodyChanges=0,
  shellHooksChanged=False,rootShellReviewedSha256Bytes=sha(shell),backupDialogReviewedSha256Bytes=sha(dialog),
  baselineProductionOverrides=0,baselineActualJUnitCases=2,patchedActualJUnitCases=28,newJUnitCases=13,
  originalRegressionCases=15,pythonCases=2,junitSkipped=0,
  compileEvidenceSha256Bytes=sha(HERE/'compile-evidence.json'),
  artifactManifestSha256Bytes=sha(HERE/'artifact-manifest.json'),
  integration='INTEGRATION.md',patchFiles=['DesktopBackupArchive.kt.patch','DesktopBackupCoordinator.kt.patch','extract-upstream-settings.py.patch']))
print('contract',sha(HERE/'restore-contract.json'))
print('artifactManifest',sha(HERE/'artifact-manifest.json'),'files',len(artifacts))
for row in rows:print(row['path'],row['baseSha256Bytes'],row['sha256Bytes'])
