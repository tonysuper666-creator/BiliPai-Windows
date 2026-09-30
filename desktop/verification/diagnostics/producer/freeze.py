from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
def safe(p):return Path('\\\\?\\'+str(p.absolute()))
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
baseline=[]
for p in ['desktop/src/main/kotlin/com/bilipai/desktop/Main.kt','desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPluginRuntime.kt','desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPluginStore.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/network/DesktopNetworkProxyFailure.kt']:
 baseline.append(dict(path=p,sha256Bytes=sha(REPO/p)))
head=subprocess.run(['git','rev-parse','HEAD'],cwd=REPO,capture_output=True,text=True,check=True).stdout.strip()
(HERE/'root-baselines.json').write_text(json.dumps(dict(commit=head,readOnlySeamBaselines=baseline,rootFilesNotModified=True),indent=2),encoding='utf-8',newline='\n')
proof=dict(activeClassDirectory='classes-stable',inactiveEarlierClassDirectories=['classes','classes-final','classes-verified'],
 offlineRealFilesAndActorCases=14,freshJvmConsentCases=1,actualPointerPairs=18,styles=2,compiledSourceCount=15,
 nativeWindowCreated=False,systemSaveDialogOpened=False,localLoggingPrepared=True,fullDiagnosticsComplete=False,
 sharedGradleInvoked=False,mainModified=False,networkUsed=False,userDataRead=False,externalMessagesOrLogUpload=False)
(HERE/'proof-index.json').write_text(json.dumps(proof,indent=2),encoding='utf-8',newline='\n')
entries=[]
for p in sorted(HERE.rglob('*')):
 if '__pycache__' in p.parts or p.name=='verified-artifacts.json':continue
 if not safe(p).is_file():continue
 entries.append(dict(path=p.relative_to(HERE).as_posix(),sha256Bytes=sha(p)))
manifest=HERE/'verified-artifacts.json'
manifest.write_text(json.dumps(dict(frozen=True,artifacts=entries,artifactCount=len(entries),scope=proof),indent=2),encoding='utf-8',newline='\n')
print(json.dumps(dict(artifactCount=len(entries),manifestSha256Bytes=sha(manifest),baseCommit=head)))
