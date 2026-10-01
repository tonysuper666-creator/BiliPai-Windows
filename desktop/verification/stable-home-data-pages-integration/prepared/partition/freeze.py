from pathlib import Path
import json,hashlib,re,subprocess
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2].parent/'BiliPai-v023'
def sha(b):return hashlib.sha256(b).hexdigest()
def lf(b):return b.replace(b'\r\n',b'\n')
assert not (HERE/'frozen-handoff.json').exists()
inventory=json.loads((HERE/'generated/partition-producer-inventory.json').read_text(encoding='utf-8'))
assert inventory['completePartitionInverseByteEqual']
assert json.loads((HERE/'compile-03/result.json').read_text(encoding='utf-8'))['exitCode']==0
proof=json.loads((HERE/'proof-02/runtime.log').read_text(encoding='utf-8'));assert proof['passed'] and proof['preparedOwnerGates']==7
registry=json.loads((REPO/'desktop/upstream-sources.json').read_text(encoding='utf-8'));index={r['path']:r for r in registry['sources']};new=[];merged=[]
for p,h in inventory['pins'].items():
 assert sha(lf((REPO/p).read_bytes()))==h
 if p in index:
  assert index[p]['sha256']==h;merged.append(dict(path=p,addFeatures=['home-partition'],preserveModeAndExistingFeatures=True))
 else:new.append(dict(path=p,sha256=h,mode='extracted',features=['home-partition']))
(HERE/'registry-recipe.json').write_text(json.dumps(dict(newRows=new,mergeOnly=merged),indent=2)+'\n',encoding='utf-8')
whitelist=dict(newSources=[dict(prepared='prepared/tools/extract-upstream-home-partition.py',destination='desktop/tools/extract-upstream-home-partition.py'),
 dict(prepared='prepared/manual/com/bilipai/desktop/ui/DesktopOriginalPartitionRoot.kt',destination='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalPartitionRoot.kt')],
 localPatches=[dict(patch='shared-tabs-indicator.patch',baseline='shared-tabs-indicator-base.json')],
 doNotInstall=['generated/**','reference-only/**','compile-*/**','proof-*/**'],
 sourceDir='generated/home-partition',taskName='extractOriginalHomePartition',dependency='prepareUpstreamSources',runtimeOwnerRequired=True)
(HERE/'install-whitelist.json').write_text(json.dumps(whitelist,indent=2)+'\n',encoding='utf-8')
(HERE/'ROOT-INTEGRATION.md').write_text('''# Complete original Partition page closure

Only install the sole page producer and required platform Root adapter from install-whitelist.json. Add Exec extractOriginalHomePartition in the standard original-source task pattern, dependsOn prepareUpstreamSources, --repo/--output=generated/home-partition, inputs producer/sync/manifest/all home-partition paths, Kotlin sourceDir and compile dependency. Merge original identities by path preserving current mode/features. Apply shared-tabs-indicator.patch only at exact LF baseline; it appends one COMPLETE ORIGINAL BottomBarMatchedLiquidIndicator to the EXISTING shared-liquid-tabs producer. Never install reference-only/DesktopPartitionIndicatorReference.kt or generated compiler products: their declaration exists only for narrow proof.

Retain PartitionFeedViewModel(DesktopPartitionEnvironment(binding.ports.video, capturedOwnerChildScope, isCurrent, commitIfCurrent)) at the same immutable Home epoch ENTRY, outside Home/video/Favorites/listen visibility branches. VM construction needs no UI/required embedded pages: all real raw popular/region request ports already come from the original Home binding. The original state/pagination/select/reset/refresh generation algorithm remains complete; only its scope and the existing generic owned flow inject real Root SessionStore→entry atomic publication. Close/join the same entry scope at true owner retirement. Do not create another list cache, Store, flattened DiscoveryPage or client.

DesktopHomeEmbeddedPages.PartitionContent delegates to DesktopOriginalPartitionContent(environment, actualHomeEnvironment, retainedVM, contentPadding, guardedActualVideoCallback, guardedActualBangumiCallback, scrollToTopRequestId, actualGlobalHazeState). Root must supply real lifecycle/theme/required HomePlatform/global card transition locals. The source reads the same persisted Home settings and original DesktopOriginalHomeCardVisualSettings mapper on the actual global PluginStore; defaults, badges, duration/publish-time behavior, PGC routing, side rail/drag/liquid indicator, list skeleton and original single-column cards remain. The original full PartitionScreen is also retained for later standalone route binding.

Source-only compile03 passes six sources on actual33/97 graph plus explicit prepared VM287 classes and one non-installed original indicator reference. Inverse transforms reproduce all1240 lines of PartitionScreen.kt exactly; all five source pins match the exact stable Git commit. Existing shared skeleton pulse/block declarations keep one producer; only three missing original media-list declarations are selected. Full video lazy-key and replace-refresh policy sources are retained.

Sixteen original policy/structure methods, adapted to standalone check assertions, pass117 assertions. The stable source and its OWN upstream test disagree: test hardcodes showUpBadge=false while stable PartitionVideoList reads the original global showUpBadges. Only that fixture expectation is corrected to actual original source, adding its persisted getter assertion; failed proof01 and unchanged original test remain. Seven prepared VM ownership/paging gates pass: popular first/append, next-page replace refresh, empty refresh preservation/reset, raw region ID, old partition-generation response rejection and retired entry late-flow refusal. These use scripted API responses and a fixture owner, not actual Store/network/Root UI acceptance. Full installed graph/native window/actual account remains pending.
''',encoding='utf-8',newline='\n')
for p in inventory['pins']:
 q=HERE/'original-source'/p;q.parent.mkdir(parents=True,exist_ok=True);q.write_bytes((REPO/p).read_bytes())
artifacts=[];excluded=[]
for p in sorted(HERE.rglob('*'),key=str):
 if not p.is_file() or p.name=='frozen-handoff.json':continue
 if p.suffix in ('.class','.kotlin_module','.jar','.pyc'):
  excluded.append(dict(path=p.relative_to(HERE).as_posix(),sha256Bytes=sha(p.read_bytes())));continue
 artifacts.append(dict(path=p.relative_to(HERE).as_posix(),sha256Bytes=sha(p.read_bytes()),sizeBytes=p.stat().st_size))
report=dict(status='FROZEN_COMPLETE_ORIGINAL_PARTITION_SOURCE_ONLY',upstreamCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
 sourceIdentityCount=5,completeOriginalPartitionInverseByteEqual=True,compileSourceCount=6,narrowCompilePassed=True,
 adaptedOriginalPolicyMethods=16,adaptedOriginalAssertions=117,preparedOwnerGates=7,
 originalTestStaleExpectationCorrectionOnly=True,referenceIndicatorInstalled=False,
 actualFullRootOrAccountAccepted=False,artifacts=artifacts,excludedRebuildableBinaries=excluded)
(HERE/'frozen-handoff.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(artifacts=len(artifacts),sourceRegistryNew=len(new),sourceRegistryMerges=len(merged),manifestSHA256=sha((HERE/'frozen-handoff.json').read_bytes()))))
