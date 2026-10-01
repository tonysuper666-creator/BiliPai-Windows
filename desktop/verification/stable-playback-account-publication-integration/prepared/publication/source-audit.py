from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent; MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';BACK=MAIN/'desktop/.local/stable-playback-account-protocol-parity';SNAP=MAIN/'desktop/.local/stable-product-snapshot-43'
def safe(p):return Path('\\\\?\\'+str(Path(p).absolute()))
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def shab(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def save(p,x):write(p,json.dumps(x,ensure_ascii=False,indent=2)+'\n')
assert shab(BACK/'frozen-handoff.json')=='b990563ad4ec9cf4eca8ea495377fe0496073209684df8d8e922cbe8a08322a3'
backend=json.loads(read(BACK/'frozen-handoff.json'))
for row in backend['artifacts']:assert shab(BACK/row['path'])==row['sha256Bytes']
local=json.loads(read(HERE/'local-hunks.json'));checks=[];grouped={}
for row in local['changes']:
 if row.get('producerRequired'):continue
 grouped.setdefault(row['path'],[]).append(row)
for rel,rows in grouped.items():
 current=read(HERE/'proof-only'/rel);assert sha(current)==rows[-1]['candidateLF']
 for row in reversed(rows):
  assert sha(current)==row['candidateLF']
  for h in reversed(row['hunks']):
   offsets=h['proofOffsetsBefore'];assert len(offsets)==h['occurrences']
   for i in reversed(range(len(offsets))):
    position=offsets[i]+i*(len(h['after'])-len(h['before']))
    assert current[position:position+len(h['after'])]==h['after'],(rel,position,h['after'][:70])
    current=current[:position]+h['before']+current[position+len(h['after']):]
  assert sha(current)==row['baseLF']
 write(HERE/'source-baselines'/rel,current)
 checks.append(dict(path=rel,groups=len(rows),hunks=sum(len(r['hunks']) for r in rows),baseLF=rows[0]['baseLF'],candidateLF=rows[-1]['candidateLF'],exactInverse=True,backend84AppliedFirst=rows[0]['backend84AppliedFirst']))
# Exact original transport-body inverse from the fixed stable commit.
for path,file,steps in [
 ('app/src/main/java/com/android/purebilibili/feature/download/ResumableAssetDownloader.kt','com/android/purebilibili/feature/download/ResumableAssetDownloader.kt',[dict(before='private val client: OkHttpClient',after='private val client: okhttp3.Call.Factory')]),
 ('app/src/main/java/com/android/purebilibili/feature/cast/LocalProxyServer.kt','com/android/purebilibili/feature/cast/LocalProxyServer.kt',json.loads(read(HERE/'cast-generated-required.json'))['hunks'])]:
 git=subprocess.run(['git','show','3d5d19a2f994daccd0e2f8b5f522b6d82f43d589:'+path],cwd=REPO,capture_output=True,check=True).stdout.decode('utf-8').replace('\r\n','\n')
 assert git==read(REPO/path);write(HERE/'original-stable'/path,git)
 current=read(HERE/'prepared/generated'/file)
 for h in reversed(steps):assert current.count(h['after'])==1;current=current.replace(h['after'],h['before'])
 expected=git if 'download/' in file else read(HERE/'producer-audit/cast-baseline'/file)
 assert current==expected
 checks.append(dict(path=path,originalSourceLF=sha(git),selectedTransportBodyInverse=True,wholeOriginalDownloaderRetained='download/' in file))
originalRegistry=json.loads(read(REPO/'desktop/upstream-sources.json'));raw='app/src/main/java/com/android/purebilibili/feature/download/ResumableAssetDownloader.kt'
entry=next(s for s in originalRegistry['sources'] if s['path']==raw);assert entry['mode']=='direct'
delta=dict(entry);delta['mode']='platform-rewrite';delta['features']=sorted(set(entry['features'])|{'offline-download'});assert delta['sha256']==sha(read(REPO/raw))
save(HERE/'registry-delta.json',dict(mergeOnlySameIdentity=True,replaceExistingMode='direct',sources=[delta],newOriginalIdentities=0,preserveAllExistingFeatures=True,directSyncRemovesOldCopy=True))
# Exact build registration is proposed only; no shared Gradle file is modified or executed.
gradle='desktop/build.gradle.kts';before=read(REPO/gradle)
anchor='tasks.named("compileKotlin") { dependsOn(extractUpstreamStoryTopic, extractPlaybackSettings, extractSubtitleLoadPolicy) }'
addition='''

val extractUpstreamDownloadTransport by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-download-transport.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/download-transport").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-download-transport.py")
    inputs.file(File(repositoryRoot, "app/src/main/java/com/android/purebilibili/feature/download/ResumableAssetDownloader.kt"))
    outputs.dir(layout.buildDirectory.dir("generated/download-transport"))
}
sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/download-transport")) }
tasks.named("compileKotlin") { dependsOn(extractUpstreamDownloadTransport) }
'''
assert before.count(anchor)==1
save(HERE/'build-registration-hunks.json',[dict(path=gradle,baseLF=sha(before),candidateLF=sha(before.replace(anchor,anchor+addition)),hunks=[dict(before=anchor,after=anchor+addition,occurrences=1)],sourceOnlyProposal=True,GradleExecuted=False)])
# Preserve the exact original archive/dependency/attribution. Refresh ONLY declared fork records.
prov='desktop/third-party/google-cast-v2/SOURCES.json';before=read(REPO/prov);obj=json.loads(before)
channel='desktop/src/main/java/su/litvak/chromecast/api/v2/Channel.java';record=next(r for r in obj['records'] if r['path']=='src/main/java/su/litvak/chromecast/api/v2/Channel.java')
assert record['sha256']==sha(read(REPO/channel))
old=json.dumps(record,ensure_ascii=False,indent=2);old='    '+old.replace('\n','\n    ')
record=dict(record,sha256=sha(read(HERE/'proof-only'/channel)),bytes=len(read(HERE/'proof-only'/channel).encode()))
new=json.dumps(record,ensure_ascii=False,indent=2);new='    '+new.replace('\n','\n    ')
for leaf in ['DesktopCastPublication.java','DesktopCastWriter.java']:
 rel='desktop/src/main/java/su/litvak/chromecast/api/v2/'+leaf;p=HERE/'prepared'/rel
 row=dict(path=rel.removeprefix('desktop/'),sha256=sha(read(p)),bytes=len(read(p).encode()))
 new+=',\n    '+json.dumps(row,ensure_ascii=False,indent=2).replace('\n','\n    ')
assert before.count(old)==1
save(HERE/'cast-provenance-hunks.json',[dict(path=prov,baseLF=sha(before),candidateLF=sha(before.replace(old,new)),hunks=[dict(before=old,after=new,occurrences=1)],originalCoordinateArchiveRootsDependenciesUnchanged=True,modifiedOriginalFilesRemain3=True,existingSoleForkRecordsOnly=True)])
write(HERE/'source-baselines'/prov,before)
save(HERE/'synthetic-migration/prepare-01-failure.json',dict(status='HISTORICAL_PREPARE_FAILURE',recordedFromToolOutput=True,error="AssertionError: ('desktop/src/test/kotlin/com/bilipai/desktop/download/DesktopDownloadManagerTest.kt', 'DesktopDownloadManager')",cause='Two original restored constructors have identical source text; the first producer incorrectly required one occurrence. Corrected exact grouped occurrence counts; original assertions stay unchanged.',runtimeExecuted=False))
snapshot=json.loads(read(SNAP/'manifest.json'));snapSources={r['path']:r['sha256Bytes'] for r in snapshot['inputs']}
compat=[]
for rel,rows in grouped.items():
 rawBase=read(HERE/'source-baselines'/rel)
 if rows[0]['backend84AppliedFirst']:
  backRows=json.loads(read(BACK/'local-hunks.json'));backRows=backRows['changes'] if isinstance(backRows,dict) else backRows
  br=next(r for r in backRows if r['path']==rel)
  for h in reversed(br['hunks']):rawBase=rawBase.replace(h['after'],h['before'])
 actualInput=snapSources.get(rel)
 rawCurrent=safe(REPO/rel).read_bytes()
 rawMatch=hashlib.sha256(rawCurrent).hexdigest()==actualInput
 if not rel.endswith('/DesktopShell.kt'):
  assert rawMatch and rawBase==rawCurrent.decode('utf-8').replace('\r\n','\n')
  safe(HERE/'source-baselines-raw'/rel).parent.mkdir(parents=True,exist_ok=True);safe(HERE/'source-baselines-raw'/rel).write_bytes(rawCurrent)
 compat.append(dict(path=rel,actual43InputRawSHA=actualInput,baselineLF=sha(rawBase),liveRawMatches43Input=rawMatch,LFNormalization='CRLF to LF only; snapshot source inputs pin raw bytes',exception='Shell exact42 pre-retainer baseline; four Root43 lifetime hunks and later Root changes must be retained via semantic hunk merge' if rel.endswith('/DesktopShell.kt') else None))
save(HERE/'source-checks.json',dict(status='PASS',exactInverseChecks=checks,backend84AllArtifactsUnchanged=len(backend['artifacts']),producerAuditSHA=shab(HERE/'producer-audit/result.json'),actual43SourceCompatibility=compat,newOriginalSchema=False,newClient=False,newCache=False,sharedSourceEdited=False,HTTP=False,HWND=False,Gradle=False))
print(json.dumps(dict(status='PASS',existingManualFamilies=len(grouped),exactInverseChecks=len(checks),backendFrozenArtifactsUnchanged=len(backend['artifacts']))))
