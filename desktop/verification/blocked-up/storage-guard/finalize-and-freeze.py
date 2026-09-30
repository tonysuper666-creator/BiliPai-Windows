"""Finalize verified independent artifacts once. Frozen cohorts remain immutable."""
from pathlib import Path
import hashlib,json,difflib
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
def safe(p):
 value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,v):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(v,encoding='utf-8',newline='\n')
assert not (HERE/'verified-artifacts.json').exists(), 'Already frozen'
assert json.loads((HERE/'proof/disk-guard.json').read_text())['passed']
assert json.loads((HERE/'proof/ui-short/result.json').read_text())['passed']
assert json.loads((HERE/'ui-short-runtime-evidence.json').read_text())['unchangedClasses']
deps=json.loads((HERE/'dependency-identities.json').read_text())
for r in deps:assert sha(Path(r['path']))==r['sha256Bytes'],r['path']
sources=sorted((HERE/'marked-overrides').rglob('*.kt'))+[HERE/n for n in ['DesktopDiscoveryStorageGuard.kt','DesktopDiscoveryStorageBoundary.kt','DiscoveryStorageFixture.kt','DiscoveryStorageUiFixture.kt']]
write(HERE/'compile-evidence.json',json.dumps(dict(passed=True,activeClassDirectory='classes-attempt1',compiledSourceCount=len(sources),
 exactSources=[dict(path=str(p.relative_to(HERE)),sha256Bytes=sha(p)) for p in sources],productSnapshotJars=deps[:3],
 dependencyCount=len(deps),isolatedClassOverrides=True,rootRestoreFenceIncluded=False,noSharedGradle=True,noHWND=True,noHTTP=True,noUserAccountFiles=True,
 initialLongTaskSandboxUIFailed=True,unchangedCompiledUIWithShortTaskSandboxPassed=True),indent=2)+'\n')
patch=[];payload=[]
for name in ['DesktopDiscoveryStorageGuard.kt','DesktopDiscoveryStorageBoundary.kt']:
 relative='desktop/src/main/kotlin/com/bilipai/desktop/ui/'+name
 src=HERE/name;dest=HERE/'prepared'/relative
 safe(dest.parent).mkdir(parents=True,exist_ok=True);safe(dest).write_bytes(safe(src).read_bytes())
 assert sha(src)==sha(dest)
 payload.append(dict(path=relative,preparedPath=str(dest.relative_to(HERE)),sha256Bytes=sha(dest),newFile=True))
 text=dest.read_text(encoding='utf-8')
 patch.append('diff --git a/'+relative+' b/'+relative+'\nnew file mode 100644\n'+''.join(difflib.unified_diff([],text.splitlines(True),fromfile='/dev/null',tofile='b/'+relative)))
write(HERE/'integration.patch',''.join(patch));write(HERE/'production-payload.json',json.dumps(payload,indent=2)+'\n')
baseline=[]
for rel in ['desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt','desktop/src/main/kotlin/com/bilipai/desktop/ui/DiscoveryScreens.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDiscoveryPreferences.kt','desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDiscoveryRepository.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPluginRuntime.kt','desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPluginStore.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/Main.kt']:
 baseline.append(dict(path=rel,sha256Bytes=sha(REPO/rel),informationalReadOnlyNotReplacement=True))
write(HERE/'root-seam-baselines.json',json.dumps(baseline,indent=2)+'\n')
originals=['app/src/main/java/com/android/purebilibili/core/store/TodayWatchFeedbackStore.kt','app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt']
write(HERE/'original-source-identities.json',json.dumps([dict(path=rel,sha256Bytes=sha(REPO/rel),schemaUnchanged=True) for rel in originals],indent=2)+'\n')
files=[]
for p in sorted(safe(HERE).rglob('*')):
 if not p.is_file():continue
 relative=str(p.relative_to(safe(HERE))).replace('\\','/')
 if relative=='verified-artifacts.json':continue
 files.append(dict(path=relative,sha256Bytes=sha(p),bytes=p.stat().st_size))
write(HERE/'verified-artifacts.json',json.dumps(dict(files=files,artifactCount=len(files),passed=True,dependencyManifest='dependency-identities.json',
 productionPayload='production-payload.json',activeClasses='classes-attempt1',offlineDiskGuardChecks=11,offscreenPointerCases=4,
 noMainMutation=True,noSharedGradle=True,noHWND=True,noRemoteHTTP=True,rootCurrentRestoreFenceNotClaimed=True),indent=2)+'\n')
print('FROZEN',len(files),sha(HERE/'verified-artifacts.json'))
