from pathlib import Path
import json,hashlib
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2].parent/'BiliPai-v023'
sha=lambda b:hashlib.sha256(b).hexdigest()
assert not (HERE/'frozen-handoff.json').exists()
inventory=json.loads((HERE/'generated/bangumi-page-producer-inventory.json').read_text(encoding='utf-8'))
assert inventory['originalInverseTransformsVerified']
assert json.loads((HERE/'compile-02/result.json').read_text(encoding='utf-8'))['exitCode']==0
proof=json.loads((HERE/'proof-02/runtime.log').read_text(encoding='utf-8'));assert proof['passed']
registry=json.loads((REPO/'desktop/upstream-sources.json').read_text(encoding='utf-8'));index={r['path']:r for r in registry['sources']};new=[];merged=[]
for p,h in inventory['pins'].items():
 assert sha((REPO/p).read_bytes().replace(b'\r\n',b'\n'))==h
 if p in index:
  assert index[p]['sha256']==h;merged.append(dict(path=p,addFeatures=['home-bangumi-page'],preserveModeAndExistingFeatures=True))
 else:new.append(dict(path=p,sha256=h,mode='extracted',features=['home-bangumi-page']))
(HERE/'registry-recipe.json').write_text(json.dumps(dict(newRows=new,mergeOnly=merged),indent=2)+'\n',encoding='utf-8')
whitelist=dict(newSources=[dict(prepared='prepared/tools/extract-upstream-home-bangumi-page.py',destination='desktop/tools/extract-upstream-home-bangumi-page.py'),
 dict(prepared='prepared/manual/com/bilipai/desktop/ui/DesktopOriginalBangumiHubRoot.kt',destination='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalBangumiHubRoot.kt')],
 localPatches=[dict(patch='shared-skeleton-grid.patch',baseline='shared-skeleton-base.json',requiresInstalledPartitionLane=True)],
 doNotInstall=['generated/**','reference-only/**','shared-reference-only/**','compile-*/**','proof-*/**'],
 sourceDir='generated/home-bangumi-page',taskName='extractOriginalHomeBangumiPage',dependency='prepareUpstreamSources',runtimeOwnerRequired=True)
(HERE/'install-whitelist.json').write_text(json.dumps(whitelist,indent=2)+'\n',encoding='utf-8')
(HERE/'ROOT-INTEGRATION.md').write_text('''# Complete original Home Bangumi Hub page

Install only the sole original-source producer and DesktopOriginalBangumiHubRoot from install-whitelist. The original HomeBangumiTabPage, all Hub ViewModel/UI/skeleton/blur/channel/index/filter/paging/search/follow reducers and complete MyFollow lazy-key policy remain upstream. The badge selects complete original BangumiBadge and cover color policy; unrelated Android player orientation is not emitted or claimed here. Ten source identities match stable Git 3d5d19a2f994daccd0e2f8b5f522b6d82f43d589; full-file adaptations invert to exact original LF bytes, and all eight selected repository methods invert independently to exact originals.

Add extractOriginalHomeBangumiPage with the standard Exec task pattern, dependsOn prepareUpstreamSources, original repo/output generated/home-bangumi-page, input producer/sync/manifest/all feature paths, sourceDir and compile dependency. Merge by original path preserving existing mode/features. AFTER installing Partition, apply only shared-skeleton-grid.patch at exact base: append complete original ContentVideoGridSkeleton and ContentVideoGridItemSkeleton to its existing sole media-skeleton producer. No second skeleton pulse/block producer. All reference-only or shared-reference-only files are compiler witnesses and never installed.

Retain DesktopOriginalBangumiHubRepository(api, navApi, searchApi, csrf, mid) and BangumiHubViewModel(DesktopBangumiHubEnvironment(repository, capturedEntryScope, actualIsLoggedIn, isCurrent, commitIfCurrent)) at the same immutable Home epoch outside visible page branches. Use the EXISTING ownedHomeService(BangumiApi/BilibiliApi/SearchApi, original base URL, captured epoch, same entry gate) and actual SessionStore admitted CSRF/MID getters. No new OkHttp, Retrofit transport, account cache, flat DesktopMediaModels or independent mutations. The search method's original getNavInfo/WBI signing is retained; no invented WBI cache. HTTP/session admission and original generic owned flow must reject retired owners atomically; close/join entry scope outside Store locks.

DesktopHomeEmbeddedPages.HomeBangumiTabPage delegates to DesktopOriginalHomeBangumiContent with the SAME actual Home environment, retained VM, original padding and scroll request plus required guarded Root season/episode navigation. Existing original Home settings projection supplies persisted showPgcTimeline; actual image-save effect supplies saveCover. Required global platform/lifecycle/theme/window globals come from the same Root.

Independent source-only compile02 passes 12 inputs on immutable actual36/97 plus explicitly prepared HomeVM287 and shared skeleton compiler witness. Original HubPolicy/HubBlurPolicy/MyFollowPolicy tests pass 28 methods/68 assertions after removing only JUnit imports/annotations and adding standalone check adapters; original method bodies remain exact. This proves source closure and policies, not actual Main/Root mounting, actual session/network/account writes, return navigation, physical window rendering or final EXE. Those await serial product integration.
''',encoding='utf-8',newline='\n')
for p in inventory['pins']:
 q=HERE/'original-source'/p;q.parent.mkdir(parents=True,exist_ok=True);q.write_bytes((REPO/p).read_bytes())
artifacts=[];excluded=[]
for p in sorted(HERE.rglob('*'),key=str):
 if not p.is_file() or p.name=='frozen-handoff.json':continue
 row=dict(path=p.relative_to(HERE).as_posix(),sha256Bytes=sha(p.read_bytes()),sizeBytes=p.stat().st_size)
 if p.suffix in ('.class','.kotlin_module','.jar','.pyc'):excluded.append(row)
 else:artifacts.append(row)
report=dict(status='FROZEN_COMPLETE_ORIGINAL_HOME_BANGUMI_SOURCE_ONLY',upstreamCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
 sourceIdentityCount=10,completeHubOriginalInverseByteEqual=True,selectedOriginalRepositoryMethods=8,
 compileSourceCount=12,narrowCompilePassed=True,adaptedOriginalPolicyMethods=28,adaptedOriginalAssertions=68,
 sharedSkeletonHasOneExistingProducer=True,actualFullRootOrAccountAccepted=False,artifacts=artifacts,excludedRebuildableBinaries=excluded)
(HERE/'frozen-handoff.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(artifacts=len(artifacts),sourceRegistryNew=len(new),sourceRegistryMerges=len(merged),manifestSHA256=sha((HERE/'frozen-handoff.json').read_bytes()))))
