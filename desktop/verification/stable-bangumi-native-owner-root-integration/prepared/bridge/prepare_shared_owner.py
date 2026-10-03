from pathlib import Path
import difflib, hashlib, importlib.util, json, os, shutil, subprocess, sys
sys.dont_write_bytecode = True
sys.stdout.reconfigure(encoding='utf8')
P = Path(__file__).resolve().parent
M = P.parents[2]
C = M.parent/'BiliPai-v023'
F = M/'desktop/.local/stable-original-bangumi-player-root-parity'
HEAD = subprocess.check_output(['git', '-C', str(C), 'rev-parse', 'HEAD']).decode().strip()
UP = '3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def wide(p):
 s = os.path.abspath(str(p)); return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(s): return hashlib.sha256(s.encode('utf8')).hexdigest()
def read(p): return wide(p).read_text(encoding='utf8').replace('\r\n','\n')
def write(p, text):
 wide(p).parent.mkdir(parents=True, exist_ok=True); wide(p).write_text(text,encoding='utf8',newline='\n')
def load(path,name):
 spec=importlib.util.spec_from_file_location(name,path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
hunks=[];targets=[]
def target(path, edit):
 before=read(C/path); after=edit(before)
 assert before!=after,path
 rows=[]
 a=before.splitlines(keepends=True);b=after.splitlines(keepends=True)
 for group in difflib.SequenceMatcher(None,a,b,autojunk=False).get_grouped_opcodes(4):
  i,j,k,l=group[0][1],group[-1][2],group[0][3],group[-1][4]
  previous='';following=''
  original=''.join(a[i:j]);replacement=''.join(b[k:l])
  exact=previous+original+following; assert before.count(exact)==1,(path,i,j)
  rows.append(dict(target=path,startLine=i,endLineExclusive=j,before=original,after=replacement,
   prefix=previous,suffix=following,beforeSha256LF=sha(original),exactAnchorSha256LF=sha(exact)))
 assert rows
 replay=before
 for h in reversed(rows):
  anchor=h['prefix']+h['before']+h['suffix'];assert replay.count(anchor)==1
  replay=replay.replace(anchor,h['prefix']+h['after']+h['suffix'])
 assert replay==after
 write(P/'prepared'/path,after)
 hunks.extend(rows);targets.append(dict(path=path,beforeSha256LF=sha(before),afterSha256LF=sha(after),hunkCount=len(rows)))
 return after
def replace(text,before,after,count=1):
 assert text.count(before)==count,(before,text.count(before),count);return text.replace(before,after)

# Read frozen closure files; never rewrite them. Only this lane is prospective.
for path in ['desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalBangumiPlayerPlatform.kt',
             'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalBangumiPlayerRequestViews.kt']:
 write(P/'prepared'/path,read(F/'prepared'/path))
binding='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoRepositoryBinding.kt'
binding_hunks=json.loads(read(F/'binding-exact-hunks.json'))
def pgc_binding(text):
 for h in binding_hunks:
  assert h['path']==binding and sha(h['before'])==h['beforeSha256LF']
  text=replace(text,h['before'],h['after'])
 return text
target(binding,pgc_binding)

delta=read(P/'fragments/video-owner-delta.py.txt')
def video_tool(text):
 text=replace(text,'def generate(repo,output,standalone=False):\n',delta+'def generate(repo,output,standalone=False):\n')
 return replace(text,'  body=story_portrait_adoption_delta(recipe[\'output\'],body)\n',
  '  body=story_portrait_adoption_delta(recipe[\'output\'],body)\n  body=bangumi_shared_owner_delta(recipe[\'output\'],body)\n')
video_path='desktop/tools/extract-upstream-video-full-owner.py'
target(video_path,video_tool)

def portrait_binding(text):
 text=replace(text,'    private val onPreparation: (DesktopOriginalMediaCachePreparation) -> Unit,\n',
  '    private val onPreparation: (DesktopOriginalMediaCachePreparation) -> Unit,\n    private val prepareAcceptedMedia: (DesktopOriginalVideoAcceptedPublication, DesktopOriginalBangumiNativeSourcePlan, () -> Boolean) -> DesktopOriginalVideoMediaPort,\n')
 text=replace(text,'        var prepared: Prepared? = null\n','        var prepared: Prepared? = null\n        var bangumi: BangumiCapture? = null\n')
 text=replace(text,'        val payload: com.android.purebilibili.feature.video.usecase.VideoLoadResult.Success)\n',
  '        val payload: com.android.purebilibili.feature.video.usecase.VideoLoadResult.Success,\n        val bangumiPresenter: DesktopOriginalBangumiSharedPlaybackPresenter? = null)\n    private class BangumiCapture(val presenter: DesktopOriginalBangumiSharedPlaybackPresenter,\n        val detail: BangumiDetail, val episode: BangumiEpisode)\n')
 text=replace(text,'                request::admitCurrentMutation)\n',
  '                request::admitCurrentMutation, prepared.bangumiPresenter)\n')
 return replace(text,'    override fun captureMediaCache(request:',read(P/'fragments/portrait-pgc-methods.kt.txt')+'\n    override fun captureMediaCache(request:')
target('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalPortraitPlatformBinding.kt',portrait_binding)

def root_factory(text):
 before='    /** Overlay\'s lock-order-safe predicate:'
 after='''    /** PGC cached audio/resume borrow the exact existing accepted media path.
     * No completed Binding or newly stamped signed URL is used. */
    internal fun acceptedMedia(value: DesktopOriginalVideoOwnerAssembly,
        expected: DesktopOriginalVideoAcceptedPublication, plan: DesktopOriginalBangumiNativeSourcePlan,
        isPresenterCurrent: () -> Boolean): DesktopOriginalVideoMediaPort {
        val construction = checkNotNull(built.get()?.takeIf { it.assembly === value })
        if (!construction.gate.owns() || value.native.current() !== expected)
            throw CancellationException("PGC accepted media replaced")
        return value.native.acceptedMedia({ construction.media.accepted(it, plan) }, isPresenterCurrent)
    }

    /** Overlay's lock-order-safe predicate:'''
 return replace(text,before,after)
target('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoRootFactory.kt',root_factory)
def assembler(text):
 return replace(text,
  '            { result -> if (result is DesktopOriginalMediaCachePreparation.Direct) diagnostic("Portrait byte transport selected direct origin") }) }',
  '            { result -> if (result is DesktopOriginalMediaCachePreparation.Direct) diagnostic("Portrait byte transport selected direct origin") },\n            { expected, plan, isPresenterCurrent -> factory.acceptedMedia(owner, expected, plan, isPresenterCurrent) }) }')
target('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoRootAssembler.kt',assembler)

def root_transport(text):
 text=replace(text,'    fun accepted(expected: DesktopOriginalVideoAcceptedPublication): DesktopOriginalVideoMediaPort {\n',
 '''    fun accepted(expected: DesktopOriginalVideoAcceptedPublication): DesktopOriginalVideoMediaPort = accepted(expected, null)

    /** PGC's raw signed plan borrows the same accepted receipt/cache/transport.
     * Full DURL order survives retained resume; the ordinary overload is intact. */
    internal fun accepted(expected: DesktopOriginalVideoAcceptedPublication,
        bangumiPlan: DesktopOriginalBangumiNativeSourcePlan?): DesktopOriginalVideoMediaPort {
''')
 text=replace(text,'        val original = expected.nativeSource.source\n',
 '''        val original = expected.nativeSource.source
        bangumiPlan?.let { plan ->
            require(expected.request.bvid == plan.episode.bvid && expected.request.aid == plan.episode.aid &&
                expected.request.cid == plan.episode.cid && original.referer == plan.referer)
        }
''')
 text=replace(text,'        fun remote(video: String, audio: String?) = original.copy(videoUrl = video, audioUrl = audio,\n',
  '        fun remote(video: String, audio: String?) = bangumiPlan?.retainedSource(original, video, audio)\n            ?: original.copy(videoUrl = video, audioUrl = audio,\n')
 return replace(text,'            legacyTracks = { source, keys -> desktopOriginalLegacyByteTracks(source, emptyList(), emptyList(), keys) },\n',
  '            legacyTracks = { source, keys -> bangumiPlan?.byteTracks(source)\n                ?: desktopOriginalLegacyByteTracks(source, emptyList(), emptyList(), keys) },\n')
target('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoRootTransport.kt',root_transport)

def native_owner(text):
 before='''    fun acceptedMedia(prepare: (DesktopOriginalVideoAcceptedPublication) -> DesktopOriginalVideoMediaPort): DesktopOriginalVideoMediaPort {
'''
 after='''    fun acceptedMedia(prepare: (DesktopOriginalVideoAcceptedPublication) -> DesktopOriginalVideoMediaPort): DesktopOriginalVideoMediaPort =
        acceptedMedia(prepare) { true }

    /** Optional concrete presenter admission is evaluated in the SAME final
     * Store -> entry -> native gate. It is not a new source authority. */
    internal fun acceptedMedia(prepare: (DesktopOriginalVideoAcceptedPublication) -> DesktopOriginalVideoMediaPort,
        isPresenterCurrent: () -> Boolean): DesktopOriginalVideoMediaPort {
'''
 text=replace(text,before,after)
 text=replace(text,'        val delegate = prepare(lease)\n',
  '        if (!isPresenterCurrent()) throw CancellationException("Accepted presenter retired before preparation")\n        val delegate = prepare(lease)\n')
 text=replace(text,'        fun checkCurrent() { if (!owns(lease)) throw CancellationException("Accepted ordinary source retired") }',
  '        fun current() = owns(lease) && isPresenterCurrent()\n        fun checkCurrent() { if (!current()) throw CancellationException("Accepted ordinary source/presenter retired") }')
 text=replace(text,'                publication.admit(source, { owns(lease) }) {\n',
  '                publication.admit(source, ::current) {\n')
 text=replace(text,'                        val retained = retainedSource(source) { owns(next) }\n',
  '                        val retained = retainedSource(source) { owns(next) && isPresenterCurrent() }\n')
 text=replace(text,'                        bindAcceptedTransport(next, lease) { owns(next) }\n',
  '                        bindAcceptedTransport(next, lease) { owns(next) && isPresenterCurrent() }\n')
 return text
target('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoNativeOwner.kt',native_owner)

# Extend actual PGC UI-state metadata only with its already received raw protocol.
# Original business code/order/policies remain replayed by the frozen sole producer.
player_delta='''def bangumi_native_metadata_delta(path, body):
 if path == 'com/android/purebilibili/feature/bangumi/DesktopOriginalBangumiPlayerViewModel.kt':
  before='        val cachedDash: Dash? = null,\\n'
  assert body.count(before)==1
  body=body.replace(before,before+'        val cachedPlayData: BangumiVideoInfo? = null,\\n')
  before='                cachedDash = playData.dash,\\n'
  assert body.count(before)==1
  body=body.replace(before,before+'                cachedPlayData = playData,\\n')
  before='                    cachedDash = dash,\\n'
  assert body.count(before)==1
  body=body.replace(before,before+'                    cachedPlayData = playData,\\n')
 return body

'''
player_tool=read(F/'prepared/desktop/tools/extract-upstream-bangumi-player.py')
player_tool=replace(player_tool,'for recipe in RECIPES:\n',player_delta+'for recipe in RECIPES:\n')
player_tool=replace(player_tool," assert sha(body)==recipe['adaptedSha256LF']\n", " assert sha(body)==recipe['adaptedSha256LF']\n body=bangumi_native_metadata_delta(recipe['output'],body)\n")
write(P/'prepared/desktop/tools/extract-upstream-bangumi-player.py',player_tool)

generated=P/'generated';wide(generated).mkdir(exist_ok=True)
subprocess.run([sys.executable,str(P/'prepared/desktop/tools/extract-upstream-bangumi-player.py'),
 '--repo',str(C),'--output',str(generated)],check=True)
video=load(P/'prepared'/video_path,'prospective_video_owner')
video.generate(C,P/'generated-video',standalone=False)

def gradle(text):
 before='tasks.named("compileKotlin") { dependsOn(extractOriginalBangumiPages) }\n'
 after=before+'''
// Complete original PGC VM/Base/policies and playurl have one producer. This
// installs shared native-owner plumbing; the original Player Screen is pending.
val extractOriginalBangumiPlayer by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalBangumiPages, extractUpstreamMedia)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-bangumi-player.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-bangumi-player").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-bangumi-player.py", "tools/sync-upstream.py", "tools/extract-upstream-media.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "original-bangumi-player-full" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-bangumi-player"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-bangumi-player")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalBangumiPlayer) }
'''
 return replace(text,before,after)
target('desktop/build.gradle.kts',gradle)
registry_delta=json.loads(read(F/'prospective-registry-delta.json'))['sourceDelta']
def registry(text):
 actual=json.loads(text);sources=actual['sources'];original_sources=json.loads(json.dumps(sources))
 original_resources=json.loads(json.dumps(actual['resources']));by_path={r['path']:r for r in sources};assert len(by_path)==len(sources)
 revised_delta=[]
 for h in registry_delta:
  path=h['path']
  if h['operation']=='add-original-identity':
   assert path not in by_path,path
   raw=subprocess.check_output(['git','-C',str(C),'show',UP+':'+path]).decode('utf8').replace('\r\n','\n')
   assert sha(raw)==h['row']['sha256'];sources.append(h['row']);by_path[path]=h['row'];revised_delta.append(h)
  else:
   row=by_path[path]
   old_row=json.loads(json.dumps(row))
   assert row['sha256']==h['before']['sha256'] and row['mode']==h['before']['mode']
   assert 'original-bangumi-player-full' not in row['features'];row['features'].append('original-bangumi-player-full')
   revised_delta.append(dict(path=path,operation='feature-union',before=old_row,after=json.loads(json.dumps(row))))
 assert len(sources)==len(original_sources)+6 and actual['resources']==original_resources
 for old in original_sources:
  current=by_path[old['path']]
  if old['path']!='app/src/main/java/com/android/purebilibili/data/repository/BangumiRepository.kt':assert current==old
  else:assert current['features']==old['features']+['original-bangumi-player-full']
 evidence=dict(base=HEAD,sourcesBefore=len(original_sources),sourcesAfter=len(sources),newOriginalIdentities=6,
  existingFeatureUnions=1,resourcesBefore=len(original_resources),resourcesAfter=len(actual['resources']),
  resourceDelta=[],newDependencies=[],sourceDelta=revised_delta,fullFunctionalPlayerAccepted=False)
 write(P/'registry-delta.json',json.dumps(evidence,ensure_ascii=False,indent=2))
 return json.dumps(actual,ensure_ascii=False,indent=2)+'\n'
target('desktop/upstream-sources.json',registry)

report=dict(upstreamCommit=UP,candidateBase=HEAD,candidateWritten=False,fullPlayerScreenPrepared=False,
 targetCount=len(targets),hunkCount=len(hunks),targets=targets)
write(P/'exact-hunks.json',json.dumps(hunks,ensure_ascii=False,indent=2))
write(P/'targets.json',json.dumps(report,ensure_ascii=False,indent=2))
print('Prepared same-owner native bridge:',len(targets),'target families;',len(hunks),'exact fragments;',HEAD)
