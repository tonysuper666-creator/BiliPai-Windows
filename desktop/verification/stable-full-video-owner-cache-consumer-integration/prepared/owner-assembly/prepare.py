from pathlib import Path
import hashlib, importlib.util, json, os, subprocess
P=Path(__file__).resolve().parent;MAIN=P.parents[2];REPO=MAIN.parent/'BiliPai-v023'
PFX=chr(92)*2+'?'+chr(92)
def wide(p):
 s=os.path.abspath(p);return Path(s if s.startswith(PFX)else PFX+s)
def sha(t):return hashlib.sha256(t.encode()).hexdigest()
def load(p):return wide(p).read_text(encoding='utf8').replace('\r\n','\n')
def write(p,s):
 wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(s,encoding='utf8',newline='\n')
def save(p,v):write(p,json.dumps(v,indent=2)+'\n')
path='app/src/main/java/com/android/purebilibili/data/repository/ActionRepository.kt'
raw=load(REPO/path)
assert sha(raw)=='d6a59bbf30b9c2058f352e299399713f9af13fd44262eeae6473e1447769087f'
names=['checkFavoriteStatus','checkLikeStatus','checkDislikeStatus','checkCoinStatus','checkWatchLaterStatus']
functions=[]
for name in names:
 # Exact pinned declaration boundaries use the original closing indentation.
 # Pinning the complete source and each selected body makes this a fixed recipe,
 # not a generic brace scanner (strings/interpolation do not determine bounds).
 i=raw.index('    suspend fun '+name+'(');j=raw.index('\n    }',i)+len('\n    }')
 functions.append((name,raw[i:j]+'\n'))
i=raw.index('internal fun isWatchLaterAid(');j=raw.index('\n}',i)+2
helper=raw[i:j]+'\n'
body='''// ORIGINAL %s
// LF-normalized SHA-256: %s
package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.WatchLaterItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

%s
internal class DesktopOriginalVideoActionStatus(
    private val api: BilibiliApi,
    private val primarySessData: () -> String?,
) {
%s}
'''%(path,sha(raw),helper,'\n'.join(s for _,s in functions))
body=body.replace('TokenManager.sessDataCache','primarySessData()')
out='com/android/purebilibili/data/repository/DesktopOriginalVideoActionStatus.kt'
write(P/'generated'/out,body)
recipe=dict(originalPath=path,originalSha256LF=sha(raw),gitBlob=subprocess.check_output(['git','rev-parse','3d5d19a2f994daccd0e2f8b5f522b6d82f43d589:'+path],cwd=REPO,text=True).strip(),
 output=out,outputSha256LF=sha(body),functions=[dict(name=n,sha256LF=sha(s),body=s)for n,s in functions],helper=helper,
 helperSha256LF=sha(helper),platformSubstitution={'TokenManager.sessDataCache':'primarySessData()'},existingFollowStatus='DesktopOriginalCreatorStatus.checkFollowStatus')
save(P/'action-status-selection.json',recipe)
producerPath='desktop/tools/extract-upstream-video-full-owner.py'
before=load(REPO/producerPath)
helperFn='''
def generate_original_video_action_status(repo,output):
 recipe=json.loads(%r)
 raw=wide(Path(repo)/recipe['originalPath']).read_text(encoding='utf-8').replace('\\r\\n','\\n')
 assert sha(raw)==recipe['originalSha256LF']
 for selected in recipe['functions']:
  assert raw.count(selected['body'])==1 and sha(selected['body'])==selected['sha256LF']
 assert raw.count(recipe['helper'])==1 and sha(recipe['helper'])==recipe['helperSha256LF']
 body=%r
 assert sha(body)==recipe['outputSha256LF']
 target=wide(Path(output)/recipe['output']);target.parent.mkdir(parents=True,exist_ok=True)
 target.write_text(body,encoding='utf-8',newline='\\n')
 return dict(path=recipe['output'],origin=recipe['originalPath'],sha256LF=sha(body),mode='policy-extract',generated=True)

'''%(json.dumps(recipe),body)
anchor='def generate(repo,output,standalone=False):\n'
assert before.count(anchor)==1
after=before.replace(anchor,helperFn+anchor,1)
anchor=' return outputs\n'
assert after.count(anchor)==1
after=after.replace(anchor," outputs.append(generate_original_video_action_status(repo,output))\n"+anchor,1)
anchor=' return body.replace(before,after,1)\n'
replacement=''' body=body.replace(before,after,1)
 anchor='    // Internal state\\n'
 assert body.count(anchor)==1,'original SessionState projection anchor'
 projection='    internal fun captureDesktopLoadState(): com.android.purebilibili.feature.video.playback.session.PlaybackSessionState = playbackSessionState\\n\\n'
 return body.replace(anchor,projection+anchor,1)
'''
assert after.count(anchor)==1;after=after.replace(anchor,replacement,1)
write(P/'prepared/existing'/producerPath,after)
bindingPath='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoRepositoryBinding.kt'
bindingBefore=load(REPO/bindingPath)
anchor='    fun hasPrimarySession(): Boolean = read {\n'
append='''    // Read through THIS request's existing Store/receipt admission. No credential cache.
    fun primarySessData(): String? = read {
        repository.ownedHomeCookie("SESSDATA", receipt.accountEpoch, ::entryCurrent)
    }
    fun primaryAccessToken(): String? = read {
        repository.ownedHomeAccessToken(receipt.accountEpoch, ::entryCurrent)
    }
'''
assert bindingBefore.count(anchor)==1
bindingAfter=bindingBefore.replace(anchor,append+anchor,1)
write(P/'prepared/existing'/bindingPath,bindingAfter)
save(P/'exact-hunks.json',[dict(target=producerPath,baseSha256LF=sha(before),desiredSha256LF=sha(after),hunks=[
 dict(before='def generate(repo,output,standalone=False):\n',after=helperFn+'def generate(repo,output,standalone=False):\n'),
 dict(before=' return outputs\n',after=' outputs.append(generate_original_video_action_status(repo,output))\n return outputs\n'),
 dict(before=' return body.replace(before,after,1)\n',after=replacement)]),
 dict(target=bindingPath,baseSha256LF=sha(bindingBefore),desiredSha256LF=sha(bindingAfter),hunks=[dict(before=anchor,after=append+anchor)])])
spec=importlib.util.spec_from_file_location('p',P/'prepared/existing'/producerPath)
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
rows=module.generate(REPO,P/'generated-replay',standalone=False)
vm=next(r for r in rows if r['path'].endswith('/VideoPlaybackViewModel.kt'))
write(P/'compile-reference/VideoPlaybackViewModel.kt',load(P/'generated-replay'/vm['path']))
save(P/'source-replay-audit.json',dict(passed=True,sourceTarget=module.COMMIT,selectedOriginalMethods=5,originalHelper=1,
 generatedStatusSHA256LF=sha(body),replayStatusSHA256LF=sha(load(P/'generated-replay'/out)),VMReadonlyStateProjection=True,
 originalAlgorithmsUnchanged=True,newStore=False,newModel=False,newOriginalVM=False,
 productionOutputs=rows,existingActionRepositoryFeatureUnion=['desktop-original-video-owner-assembly']))
print('Prepared status5 + sole WatchLater helper + readonly original SessionState projection')

repositoryPath='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopRepository.kt'
repositoryBefore=load(REPO/repositoryPath)
anchor='    internal val playbackAuthorizationRevision: StateFlow<Long> get() = sessions.playbackAuthorizationRevision\n'
append='''    /** Read-only UI cooldown projection of the existing protocol monitor. Never
     * retain a completed request Binding or manufacture a fallback cooldown. */
    internal fun originalVideoCooldownForEntry(expectedEpoch:Long,stillOwned:()->Boolean,
        commitIfEntryCurrent:((()->Unit)->Boolean),nowMs:Long):Long =
        withPrimaryPlaybackAdmission(expectedEpoch,stillOwned) {
            var result:Long? = null
            if (!commitIfEntryCurrent {
                if (!stillOwned()) throw CancellationException("Original entry cooldown retired")
                result = synchronized(playbackProtocolMonitor) {
                    resetVideoProtocolGenerationLocked(expectedEpoch)
                    (appApiCooldownUntilMs-nowMs).coerceAtLeast(0L)
                }
            }) throw CancellationException("Original entry cooldown admission retired")
            checkNotNull(result)
        }

'''
assert repositoryBefore.count(anchor)==1
repositoryAfter=repositoryBefore.replace(anchor,append+anchor,1)
write(P/'prepared/existing'/repositoryPath,repositoryAfter)
hunks=json.loads(load(P/'exact-hunks.json'))
hunks.append(dict(target=repositoryPath,baseSha256LF=sha(repositoryBefore),desiredSha256LF=sha(repositoryAfter),
 hunks=[dict(before=anchor,after=append+anchor)]))
save(P/'exact-hunks.json',hunks)
