from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
OUT=REPO/'desktop/verification/stable-home-data-pages-integration';assert not OUT.exists()
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
rows=[];excluded=[]
def put(name,b):
 p=wide(OUT/name);p.parent.mkdir(parents=True,exist_ok=True);assert not p.exists();p.write_bytes(b)
 rows.append(dict(path=name,sha256Bytes=sha(b),sizeBytes=len(b)))
installation=json.loads((HERE/'home-data-pages-phase1-install/installed.json').read_text(encoding='utf-8'))
lanes={k:MAIN/('desktop/.local/'+{
 'vm':'stable-home-viewmodel-parity','raw':'stable-home-request-ports-parity','owner':'stable-home-root-retained-integration',
 'partition':'stable-home-partition-parity','bangumi':'stable-home-bangumi-page-parity','global':'stable-home-global-platform-parity'}[k])
 for k in ['vm','raw','owner','partition','bangumi','global']}
lanes['live']=REPO/'desktop/.local/stable-home-live-list-parity';lanes['subscription']=MAIN/'desktop/.local/stable-home-subscription-page-parity'
pins=installation['preparedManifests'];pins['subscription']='c019ddc3538a5f96f41f35f0ffa617cfc3069983101a59e14efa000611e6137a'
for name,lane in lanes.items():
 raw=wide(lane/'frozen-handoff.json').read_bytes();assert sha(raw)==pins[name];put('prepared/'+name+'/frozen-handoff.json',raw)
 for r in json.loads(raw)['artifacts']:
  b=wide(lane/r['path']).read_bytes();assert sha(b)==r['sha256Bytes']
  if Path(r['path']).suffix in ('.jar','.class','.kotlin_module','.dll','.pyc'):
   excluded.append(dict(lane=name,path=r['path'],sha256Bytes=sha(b),sizeBytes=len(b),reason='Rebuildable compiler/fixture binary, never installed'))
  else:put('prepared/'+name+'/'+r['path'],b)
for folder in ['home-data-pages-phase1-install','home-subscription-install','home-owner-actual39']:
 for p in sorted(wide(HERE/folder).rglob('*'),key=str):
  if not p.is_file():continue
  b=p.read_bytes();name='root/'+folder+'/'+p.relative_to(wide(HERE/folder)).as_posix()
  if p.suffix in ('.jar','.class','.kotlin_module','.dll','.pyc'):excluded.append(dict(path=name,sha256Bytes=sha(b),sizeBytes=len(b),reason='Rebuildable output'))
  else:put(name,b)
for name in ['install-home-data-pages-phase1.py','repair-home-source-ownership.py','install-home-subscription.py','prove-home-actual39.py','freeze-home-data-pages39.py','jvm-method-name-audit-39.json']:
 put('root/'+name,(HERE/name).read_bytes())
snap=MAIN/'desktop/.local/stable-product-snapshot-39'
for name in ['manifest.json','ordered-runtime-cp.json']:put('root/snapshot39/'+name,(snap/name).read_bytes())
for phase in [38,39]:put(f'root/classes-{phase}.log',(REPO/f'desktop/.local/stable-build-repair/classes-{phase}.log').read_bytes())
for name in ['home-plan-conversion-proof.log','home-plan-conversion-proof-02.log']:put('root/'+name,(REPO/'desktop/.local/stable-build-repair'/name).read_bytes())
# Supplement to frozen287: the exact original converter is exposed from the complete VM producer,
# while the previous plugin selector no longer emits it. Only visibility changed; body is upstream.
paths=['desktop/tools/extract-upstream-home-viewmodel.py','desktop/tools/tests/test_extract_upstream_plugins.py']
for name in paths:put('root/sole-converter-final/'+name,(REPO/name).read_bytes())
proof=json.loads((HERE/'home-owner-actual39/result.json').read_text(encoding='utf-8'));assert proof['passed']and proof['productionOverrides']==0
report=dict(wholeClassesPhase=39,wholeClassesPassed=True,sourceIdentityCount=864,resourceCount=213,
 actualRuntimeEntries=97,actualKotlinClasses=10969,invalidJvmMethodNames=0,
 completeOriginalHomeViewModelCompiled=True,originalRawHomeProtocolsCompiled=True,
 completeOriginalEmbeddedPagesCompiled=['Partition','HomeBangumiHub','LiveList','SubscriptionFeedPage'],
 allFourActualOriginalEmbeddedPagesMounted=False,actualRetainedOwnerGateGroups=5,actualProductClassOrigins=5,productionOverrides=0,
 exactOriginalPlanConverterTestPassed=True,sourceOwnershipWholeBuildCorrection=True,
 preservedPreparedHistoricalManifests=True,sameExistingSharedHttpCookieWbiAndPluginStores=True,
 oldTodayWatchAndRuntimePreservedUntilFactoryMounted=True,homeRetainerInstalled=False,
 fullRootFactoryNavigationReturnOrAccountAccepted=False,newWindowsExeDeployed=False,
 pending=['Actual four-page aggregate/factory/root Home mounting and old planner simultaneous retirement','Original Profile and full category/live subnavigation routes','Click-time geometry/return owner and physical-window interaction acceptance','Final Windows EXE packaging and desktop deployment'])
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode())
put('root/compiled-output-exclusions.json',(json.dumps(excluded,indent=2)+'\n').encode())
raw=(json.dumps(dict(schema='raw-artifact-manifest-v1',artifacts=rows),indent=2)+'\n').encode();wide(OUT/'artifact-manifest.json').write_bytes(raw)
wide(OUT/'README.md').write_text('''# Complete original Home data and four embedded pages — installed source closure

The whole Windows candidate classes39 compiles the complete original HomeVM, raw Video/History/Live/Action/Message protocols and all four original Home embedded pages: Partition, Bangumi Hub, LiveList and Subscription. Retained-gate/factory helpers and Window/Lottie bindings compile from their sole sources. Thirty-one new original identities bring the current inventory to864/213 resources. No new external dependency, shared transport, account authority or persistent feed/planner store was added.

classes38 preserves whole-build discoveries hidden by source-only proof: two Android lifecycle bases and existing duplicate declarations. Their platform bases now use the actual retained-entry scope without Android factory imports. Full original Partition owns its class/list/resolver; full original HomeVM owns the exact Plan converter with internal visibility; existing Favorites keeps the unique grid-item skeleton. The corrected existing converter test passes against the full original body. Frozen preparation manifests remain unchanged. Subscription final writes use the existing AtomicFile/plugin backing implementation, with captured Job and actual Store-to-entry admission. Ordered source-scan hunks are accepted only when both entire baseline and desired LF digests match. PluginStore LF normalization is recorded in source receipts; its only executable addition is the current-write admission check.

Immutable actual39/97 and zero production overrides pass five real temporary SessionStore/PluginStore owner groups with five product class origins: covered-page independence, same-MID credential replacement, atomic commit against retirement, child draining and original local-first blocking failure. The fixture uses a fake BilibiliApi without a socket; no user credential store, real account mutation or network request is exercised. Its historical prepared-scope text remains inside the original fixture, while the outer actual39 receipt accurately identifies the installed product graph.

The actual Root still uses the previous entry path. The new Retainer and old TodayWatch/runtime replacement are deliberately installed together only when the real four-page aggregate, complete navigation, original Profile and return geometry can be mounted. Compilation and owner gates do not claim a visible Home window, genuine account requests, original transition pixels or deployed EXE. These remain pending.
''',encoding='utf-8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows),excludedRebuildableBinaries=len(excluded),manifestSHA256=sha(raw))))
