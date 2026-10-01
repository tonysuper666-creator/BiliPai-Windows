from pathlib import Path
import hashlib,importlib.util,json,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent; MAIN=HERE.parents[2]; REPO=MAIN.parent/'BiliPai-v023'
def safe(p):return Path('\\\\?\\'+str(Path(p).absolute()))
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
spec=importlib.util.spec_from_file_location('parser',REPO/'desktop/tools/sync-upstream.py');parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
rows=[]
def calls(s,name):
 tokens=parser.kotlin_tokens(s); result=[]
 for i,t in enumerate(tokens[:-1]):
  if t[0]!=name or tokens[i+1][0]!='(':continue
  begin=i+1;depth=1;end=begin
  while depth:
   end+=1;depth+=(tokens[end][0]=='(')-(tokens[end][0]==')')
  result.append((t[1],tokens[end][2]))
 return result
scope='''com.bilipai.desktop.player.DesktopLocalPlaybackPublication(
                { scope.coroutineContext[kotlinx.coroutines.Job]?.isActive == true },
                { admitted -> synchronized(scope) {
                    if (scope.coroutineContext[kotlinx.coroutines.Job]?.isActive != true) false else { admitted(); true }
                } })'''
alive='''com.bilipai.desktop.player.DesktopLocalPlaybackPublication(
                    { synchronized(publicationGate) { publicationAlive } },
                    { admitted -> synchronized(publicationGate) { if (!publicationAlive) false else { admitted(); true } } })'''
root='desktop/src/test/kotlin/com/bilipai/desktop/'
controllers=['DesktopCheckpointFailureTest.kt','DesktopPlaybackControllerTest.kt','DesktopPlaybackQueueOwnershipTest.kt',
 'player/DesktopPremiumAudioControllerTest.kt','player/PlaybackSettingsIntegrationFixtures.kt','ui/DesktopStoryInitialFailureTest.kt','ui/PlaybackSettingsDraftMemoryTest.kt']
listens=['audio/DesktopMusicRootIntegrationTest.kt','audio/DesktopNativeMusicIntegrationTest.kt','audio/ListenAudioLifecycleTest.kt','audio/ListenWriterBarrierTest.kt']
paths=[root+p for p in controllers+listens]+[root+'download/DesktopDownloadManagerTest.kt','desktop/src/main/kotlin/com/bilipai/desktop/player/DesktopControllerNativeSmoke.kt']
for rel in paths:
 s=read(REPO/rel);original=s;hunks=[]
 if 'DesktopDownloadManagerTest.kt' in rel:
  typename='DesktopDownloadManager';pub='localDownloadPublication()'
 elif rel.startswith(root) and rel[len(root):] in listens:typename='ListenAudioSession';pub=alive
 else:typename='DesktopPlaybackController';pub=scope
 count=0
 originalCalls=list(dict.fromkeys(original[b:e] for b,e in reversed(calls(original,typename))))
 for before in originalCalls:
  if typename=='DesktopPlaybackController' and 'dataSource =' not in before:continue
  if typename=='ListenAudioSession' and 'playbackDataSource =' not in before:continue
  after=before[:-1]+', publication = '+pub+')'
  occurrences=s.count(before);assert occurrences>=1,(rel,typename)
  s=s.replace(before,after);hunks.append(dict(before=before,after=after,occurrences=occurrences));count+=occurrences
 if typename=='ListenAudioSession':
  needle='    lateinit var session: ListenAudioSession';assert s.count(needle)==1
  replacement='    private val publicationGate = Any()\n    private var publicationAlive = true\n'+needle
  s=s.replace(needle,replacement);hunks.append(dict(before=needle,after=replacement,occurrences=1))
  needle='override fun close() {';assert s.count(needle)==1
  replacement=needle+' synchronized(publicationGate) { publicationAlive = false };'
  s=s.replace(needle,replacement);hunks.append(dict(before=needle,after=replacement,occurrences=1))
 if typename=='DesktopDownloadManager':
  helper='''
/** Explicit memory-only fixture lease; never adopts an account receipt. Original assertions stay unchanged. */
private suspend fun localDownloadPublication(): com.bilipai.desktop.player.DesktopPlaybackPublication {
    val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        ?: error("Download fixture requires an actual test Job")
    val gate = Any()
    return com.bilipai.desktop.player.DesktopLocalPlaybackPublication({ job.isActive }, { admitted ->
        synchronized(gate) { if (!job.isActive) false else { admitted(); true } }
    })
}
'''
  s+=helper;hunks.append(dict(before=original[-100:] if False else s[:-len(helper)][-100:],after=s[:-len(helper)][-100:]+helper,occurrences=1))
 write(HERE/'synthetic-migration/proof-only'/rel,s)
 if rel.startswith('desktop/src/main/'):
  write(HERE/'proof-only'/rel,s)
 rows.append(dict(path=rel,baseLF=sha(original),candidateLF=sha(s),hunks=hunks,explicitLocalContracts=count,originalBusinessAssertionsUnchanged=True,syntheticAccountOwnerAcceptance=False))
 # Verify exact sequential patch and inverse before freezing.
 check=original
 for h in hunks:
  assert check.count(h['before'])==h['occurrences'];check=check.replace(h['before'],h['after'])
 assert check==s
 for h in reversed(hunks):assert check.count(h['after'])==h['occurrences'];check=check.replace(h['after'],h['before'])
 assert check==original
write(HERE/'synthetic-migration/local-hunks.json',json.dumps(rows,ensure_ascii=False,indent=2)+'\n')
write(HERE/'synthetic-migration/result.json',json.dumps(dict(status='SOURCE_PATCH_PASS',controllerHarnessFiles=7,listenHarnessFiles=4,downloadCalls=next(r['explicitLocalContracts'] for r in rows if 'DesktopDownloadManagerTest' in r['path']),nativeSmokeCalls=2,existingTestBusinessAssertionsUnchanged=True,requiresRootCompileTestKotlin=True,compiledOrExecutedOldSuite=False),indent=2)+'\n')
print(json.dumps(dict(files=len(rows),calls=sum(r['explicitLocalContracts'] for r in rows))))
