from pathlib import Path
import hashlib,json,importlib.util,sys,tempfile,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
MAIN=HERE.parents[2];CANDIDATE=MAIN.parent/'BiliPai-v023'
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
def digest(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def text(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n').replace('\r','\n')
def lfsha(p):return hashlib.sha256(text(p).encode()).hexdigest()
def write(p,s):
 p=safe(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
def out(p,v):write(HERE/p,json.dumps(v,indent=2,ensure_ascii=False))
pins=json.loads(text(HERE/'source-pins.json'));checks=[]
for p,h in pins.items():assert lfsha(CANDIDATE/p)==h;checks.append('stable LF source identity: '+p)
spec=importlib.util.spec_from_file_location('weekly_repro',HERE/'prepared/desktop/tools/extract-stable-weekly-series.py')
g=importlib.util.module_from_spec(spec);spec.loader.exec_module(g)
with tempfile.TemporaryDirectory(prefix='bilipai-weekly-generator-task-') as t:
 g.generate(CANDIDATE,Path(t))
 for p in sorted((HERE/'generated').rglob('*.kt')):
  assert digest(p)==digest(Path(t)/p.relative_to(HERE/'generated'));checks.append('sole producer reproducible bytes: '+str(p.relative_to(HERE)))
screen=text(HERE/'original/WeeklySeriesScreen.kt')
prepared=text(HERE/'generated/com/android/purebilibili/feature/home/DesktopOriginalWeeklySeriesScreen.kt')
body=screen[screen.index('@OptIn(ExperimentalMaterial3Api::class)'):]
assert prepared.endswith(body)
checks.append('complete original WeeklySeriesContent body is unchanged, including real grid and actual period dialog')
vm=text(HERE/'original/WeeklySeriesViewModel.kt');generatedVm=text(HERE/'generated/com/android/purebilibili/feature/home/DesktopOriginalWeeklySeriesViewModel.kt')
state=vm[vm.index('internal data class WeeklySeriesUiState'):vm.index('internal class WeeklySeriesViewModel')]
assert state in generatedVm
checks.append('original state schema and resolver body unchanged')
expectedConsumer=text(HERE/'references/DiscoveryScreens.kt')
assert expectedConsumer.index('if (mode == DiscoverySection.WEEKLY)')<expectedConsumer.index('LaunchedEffect(mode, periodsRevision)')
assert 'videos.map(::discoveryVideoCard), discoveryVideoCard(video)' in expectedConsumer
checks.append('single WEEKLY loader returns before legacy effects, current raw queue uses sole existing original conversion')
registry=json.loads(text(CANDIDATE/'desktop/upstream-sources.json'))
records=registry['sources'] if isinstance(registry,dict) else registry
new=[];existing=[]
for p,h in pins.items():
 row=dict(path=p,sha256=h,mode='extracted',features=['stable-weekly-series-parity','discovery','weekly'],
  producer='extract-stable-weekly-series.py')
 if any(r['path']==p for r in records):row['operation']='merge-existing';existing.append(p)
 else:row['operation']='append-new';new.append(p)
 if 'rows' not in locals():rows=[]
 rows.append(row)
assert len(new)==2 and existing==['app/src/main/java/com/android/purebilibili/data/repository/VideoRepository.kt']
checks.append('only Weekly Screen/ViewModel add identities; existing VideoRepository identity feature merges')
out('source-inventory-delta.json',dict(upstreamTag='v0.2.3',upstreamCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
 actualCandidateRegistryCount=len(records),records=rows,
 referencesOnly=[
  'app/src/main/java/com/android/purebilibili/data/model/response/ListModels.kt',
  'app/src/main/java/com/android/purebilibili/core/network/ApiClient.kt',
  'app/src/main/java/com/android/purebilibili/feature/home/components/cards/VideoCard.kt',
  'design-system/src/main/java/com/android/purebilibili/core/ui/AppScaffold.kt'],
 resourcesAdded=[],dependenciesAdded=[],copyExistingDirectModelsOrLeafComponents=False))
out('source-audit.json',dict(passed=True,checks=checks,pins=pins,
 generatedFiles=[dict(path=str(p.relative_to(HERE)),sha256Bytes=digest(p)) for p in sorted((HERE/'generated').rglob('*.kt'))]))
payloads=[str(p.relative_to(HERE)) for p in sorted((HERE/'prepared/desktop').rglob('*')) if p.is_file()]
patches=[str(p.relative_to(HERE)) for p in sorted((HERE/'patches').glob('*.patch'))]
out('install-plan.json',dict(copyPayload=[dict(path=p,target=p.removeprefix('prepared/'),sha256Bytes=digest(HERE/p)) for p in payloads],
 patches=[dict(path=p,sha256Bytes=digest(HERE/p)) for p in patches],
 generatedOutputs=[dict(path=str(p.relative_to(HERE/'generated')),sha256Bytes=digest(p)) for p in sorted((HERE/'generated').rglob('*.kt'))],
 registryDelta='source-inventory-delta.json',wholeRegistryReplacement=False,compiledBinariesArePayload=False,
 fragment=dict(path='prepared/fragments/DesktopDiscoveryWeeklyMembers.fragment',sha256Bytes=digest(HERE/'prepared/fragments/DesktopDiscoveryWeeklyMembers.fragment'))))
write(HERE/'ROOT-INTEGRATION.md','''This prepared slice targets stable v0.2.3 (3d5d19a2f994daccd0e2f8b5f522b6d82f43d589).

Copy only the three payload files listed in install-plan.json: the sole generator,
DesktopWeeklySeriesPlatform.kt (original SavedStateHandle route number + a read-only requests port),
and DesktopWeeklySeriesScreen.kt (public Windows lifecycle host). Add one Gradle generator task:

    python tools/extract-stable-weekly-series.py <root project directory> <build/generated/upstream/weekly-series>

Make prepareUpstreamSources/compileKotlin depend on it, and add its output directory to Kotlin source sets.
Merge two new extracted source rows for WeeklySeriesScreen/ViewModel; merge only the feature tag on the
existing VideoRepository row. Do not copy/generate original Card, App*, API or response models again.
No new dependencies or resources. Registry count in this handoff is observed, not a replacement plan.

Apply three narrow patches after matching the exact LF baselines in candidate-baselines.json:
1. repository.patch inserts the read-only requests delegate immediately before weeklyPeriods().
   It reuses DesktopDiscoveryRepository.api and DesktopRepository.ensureSession, with cancellation
   propagation and Result error binding. Original getWeeklyPeriods/getWeeklyPeriod bodies are selected
   byte-exact into DesktopWeeklySeriesProtocol. There is no new Retrofit/client/cache/store.
2. discovery-consumer.patch adds a nullable compatible onWeeklyBack tail argument and the dedicated
   WEEKLY early-return. Both Root WEEKLY routing and inline Discovery WEEKLY chip use this same branch.
   It returns before legacy weekly list/feed effects: no competing list or fabricated period 1.
   The existing Root storage/feedback boundary remains mounted, using the same repository/global store.
   Current weekly raw VideoItem list is converted only through existing discoveryVideoCard, preserving CID.
3. root-routing.patch supplies the Root back callback (example: navigate(POPULAR)). Root may substitute
   its existing return-route policy, while preserving the injected original onBack event.

Public call contract:
    DesktopWeeklySeriesScreen(requests: DesktopWeeklySeriesRequests,
        repository: DesktopRepository, initialNumber: Int? = null,
        onBack: () -> Unit, onVideoClick: (VideoItem, List<VideoItem>) -> Unit,
        modifier: Modifier = Modifier, isClosing: () -> Boolean = { false })
    discovery.weeklySeriesRequests(): DesktopWeeklySeriesRequests // retained delegate, same API

Owner contract: route disposal closes the original loader; immutable captured MID + session epoch +
Root closing guard reject old results and click callbacks. Same-MID credentials replace the owner.
Only the original weeklyNumber route value is retained in existing DesktopBrowseMemory; no videos cache,
global preference or account store is introduced. InitialNumber supports historical routes; Root may wire
its deep-link number if available. No new URL/deep-link parser is supplied here.

Evidence: one Kotlin2.4/Compose2.4 compile against actual immutable whole stable classes11 (92 CP).
The two prepared consumer families DiscoveryRepository and DiscoveryScreens are explicit overrides,
listed in compile-01-evidence.json. All Card/App*/theme/models/API leaves come from the actual three JARs.
Fresh JVM fake API: 5 original protocol, 9 state/ownership and 10 actual offscreen UI gates; 8 real
press/release pairs across M3 and Miuix. Four PNGs record original period dialog and historical page.
The fixture warms only test visitor readiness, uses persistent=false guest sessions and task temp backing;
no socket/account/real service request executes. CandidateWeekly classes are not current Main acceptance.

Outside this acceptance: Shell route actually mounted in candidate product, real Bilibili service,
native HWND/focus/scroll performance/EXE, deep-link dispatch. Existing normalized legacy weekly members
remain for compatibility but are unreachable in the dedicated UI branch; removal is optional cleanup,
not required for this source/consumer installation. No statement of overall feature parity follows from
this slice or its test count.
''')
artifacts=[];excluded=[]
for p in sorted(HERE.rglob('*')):
 if not p.is_file() or p.name=='frozen-handoff.json':continue
 row=dict(path=str(p.relative_to(HERE)),sha256Bytes=digest(p),bytes=p.stat().st_size)
 if p.suffix in {'.jar','.class','.dll'}:excluded.append(row)
 else:artifacts.append(row)
out('frozen-handoff.json',dict(frozen=True,preparedOnly=True,upstreamTag='v0.2.3',
 candidateApplicationRuntimeAccepted=False,sourceAudit='source-audit.json',installPlan='install-plan.json',
 rawArtifacts=artifacts,excludedRuntimeArtifacts=excluded,
 actualImmutableBase=dict(path='desktop/.local/stable-product-snapshot-11/manifest.json',sha256Bytes=digest(MAIN/'desktop/.local/stable-product-snapshot-11/manifest.json')),
 preservation='No Main/Candidate/shared Gradle/other frozen lanes were edited.'))
print(json.dumps(dict(rawArtifacts=len(artifacts),excludedRuntimeArtifacts=len(excluded),manifestSHA=digest(HERE/'frozen-handoff.json'),checks=len(checks)),indent=2))
