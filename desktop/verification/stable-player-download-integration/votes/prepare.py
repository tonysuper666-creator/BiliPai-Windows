"""Stable vote UI/grade adapters. Writes only this prepared task lane."""
from pathlib import Path
import difflib,hashlib,importlib.util,json,subprocess,sys,textwrap
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2];CANDIDATE=REPO.parent/'BiliPai-v023'
TAG='v0.2.3';COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n').replace('\r','\n')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def h(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,s):
 p=safe(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
def original(path):
 s=subprocess.check_output(['git','show',f'{TAG}:{path}'],cwd=REPO).decode('utf-8').replace('\r\n','\n').replace('\r','\n')
 assert read(CANDIDATE/path)==s,'Candidate original drift: '+path
 write(HERE/'original-stable'/path,s);return s
spec=importlib.util.spec_from_file_location('vote_source_parser',CANDIDATE/'desktop/tools/sync-upstream.py')
parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
def declaration(s,anchor):
 ts=parser.kotlin_tokens(s);a=s.index(anchor);start=next(i for i,t in enumerate(ts) if t[1]>=a);end=start
 while ts[end][0]!='(':end+=1
 d=1
 while d:end+=1;d+=(ts[end][0]=='(')-(ts[end][0]==')')
 while ts[end][0]!='{':end+=1
 d=1
 while d:end+=1;d+=(ts[end][0]=='{')-(ts[end][0]=='}')
 return s[ts[start][1]:ts[end][2]]
base='app/src/main/java/com/android/purebilibili/'
paths=[base+'feature/video/ui/components/VideoCommentVoteCard.kt',base+'feature/video/ui/overlay/CommandDanmakuOverlay.kt',
 base+'feature/video/ui/overlay/CommandDanmakuOverlayState.kt',base+'data/repository/DanmakuRepository.kt',
 base+'feature/video/danmaku/CommandDanmakuPolicy.kt',base+'data/model/response/ResponseModels.kt',
 base+'feature/dynamic/components/DynamicVoteDialog.kt',base+'feature/video/ui/section/VideoPlayerSection.kt',
 base+'data/repository/DynamicVoteRepository.kt',base+'core/network/ApiClient.kt']
sources={p:original(p) for p in paths}
direct=HERE/'direct/com/android/purebilibili'
write(direct/'feature/video/ui/components/VideoCommentVoteCard.kt',sources[paths[0]])
write(direct/'feature/video/ui/overlay/CommandDanmakuOverlayState.kt',sources[paths[2]])
overlay=sources[paths[1]].replace('import androidx.media3.common.Player','import com.bilipai.desktop.player.MpvPlayer')
assert overlay.count('player: Player,')==1 and overlay.count('player.currentPosition')==2
overlay=overlay.replace('player: Player,','player: MpvPlayer,').replace('player.currentPosition','(player.state.value.positionSeconds * 1000.0).toLong()')
write(HERE/'generated/com/android/purebilibili/feature/video/ui/overlay/DesktopOriginalCommandDanmakuOverlay.kt',
 '// GENERATED from '+paths[1]+'; do not edit.\n// LF-normalized SHA-256: '+h(sources[paths[1]])+'\n'+overlay)
grade=declaration(sources[paths[3]],'suspend fun submitGradeDanmaku(')
error=declaration(sources[paths[3]],'internal fun mapSendDanmakuErrorMessage(')
adapted=grade.replace('gradeScore: Int\n    )','gradeScore: Int,\n        csrf: String\n    )',1)
assert adapted!=grade
line='            val csrf = com.android.purebilibili.core.store.TokenManager.csrfCache\n'
assert adapted.count(line)==1;adapted=adapted.replace(line,'',1)
adapted=adapted.replace('        } catch (e: Exception) {','        } catch (cancelled: kotlinx.coroutines.CancellationException) {\n            throw cancelled\n        } catch (e: Exception) {',1)
write(HERE/'generated/com/android/purebilibili/data/repository/DesktopVideoGradeProtocol.kt',
 '// GENERATED from '+paths[3]+'; do not edit.\n// LF-normalized SHA-256: '+h(sources[paths[3]])+'\n'+
 'package com.android.purebilibili.data.repository\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.withContext\n'+
 error.replace('internal fun','private fun',1)+'\ninternal class DesktopVideoGradeProtocol(private val api: com.android.purebilibili.core.network.BilibiliApi) {\n'+
 textwrap.indent(adapted,'    ')+'\n}\n')
write(HERE/'prepared/desktop/tools/extract-stable-video-votes.py', '''"""Source-preserving stable command overlay/grade extraction; direct UI is registry-owned."""
from pathlib import Path
import hashlib,importlib.util,sys,textwrap
sys.dont_write_bytecode=True
BASE="app/src/main/java/com/android/purebilibili/"
PINS='''+repr({p:h(sources[p]) for p in [paths[1],paths[3]]})+'''
def generate(repo:Path,output:Path):
 spec=importlib.util.spec_from_file_location("video_vote_ast",repo/"desktop/tools/sync-upstream.py")
 parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
 def read(p):
  s=(repo/p).read_text(encoding="utf-8").replace("\\r\\n","\\n").replace("\\r","\\n")
  assert hashlib.sha256(s.encode()).hexdigest()==PINS[p],p
  return s
 def function(s,anchor):
  ts=parser.kotlin_tokens(s);a=s.index(anchor);start=next(i for i,t in enumerate(ts) if t[1]>=a);end=start
  while ts[end][0]!="(":end+=1
  d=1
  while d:end+=1;d+=(ts[end][0]=="(")-(ts[end][0]==")")
  while ts[end][0]!="{":end+=1
  d=1
  while d:end+=1;d+=(ts[end][0]=="{")-(ts[end][0]=="}")
  return s[ts[start][1]:ts[end][2]]
 def emit(p,path,body):
  target=output/path;target.parent.mkdir(parents=True,exist_ok=True)
  target.write_text("// GENERATED from "+p+"; do not edit.\\n// LF-normalized SHA-256: "+PINS[p]+"\\n"+body,encoding="utf-8",newline="\\n")
 p=BASE+"feature/video/ui/overlay/CommandDanmakuOverlay.kt";s=read(p)
 s=s.replace("import androidx.media3.common.Player","import com.bilipai.desktop.player.MpvPlayer")
 assert s.count("player: Player,")==1 and s.count("player.currentPosition")==2
 s=s.replace("player: Player,","player: MpvPlayer,").replace("player.currentPosition","(player.state.value.positionSeconds * 1000.0).toLong()")
 emit(p,"com/android/purebilibili/feature/video/ui/overlay/DesktopOriginalCommandDanmakuOverlay.kt",s)
 p=BASE+"data/repository/DanmakuRepository.kt";s=read(p)
 grade=function(s,"suspend fun submitGradeDanmaku(")
 grade=grade.replace("gradeScore: Int\\n    )","gradeScore: Int,\\n        csrf: String\\n    )",1)
 line="            val csrf = com.android.purebilibili.core.store.TokenManager.csrfCache\\n";assert grade.count(line)==1
 grade=grade.replace(line,"",1)
 grade=grade.replace("        } catch (e: Exception) {","        } catch (cancelled: kotlinx.coroutines.CancellationException) {\\n            throw cancelled\\n        } catch (e: Exception) {",1)
 error=function(s,"internal fun mapSendDanmakuErrorMessage(").replace("internal fun","private fun",1)
 body="package com.android.purebilibili.data.repository\\nimport kotlinx.coroutines.Dispatchers\\nimport kotlinx.coroutines.withContext\\n"+error+"\\ninternal class DesktopVideoGradeProtocol(private val api: com.android.purebilibili.core.network.BilibiliApi) {\\n"+textwrap.indent(grade,"    ")+"\\n}\\n"
 emit(p,"com/android/purebilibili/data/repository/DesktopVideoGradeProtocol.kt",body)
if __name__=="__main__":generate(Path(sys.argv[1]).resolve(),Path(sys.argv[2]).resolve())
''')
opsPath='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt'
ops=read(CANDIDATE/opsPath)
fragment='''
// STABLE_VIDEO_VOTE_GRADE_MEMBERS
// Desktop command vote grade binding. Standard vote continues through submitVote.
suspend fun submitGradeDanmaku(aid: Long, cid: Long, progress: Long, gradeId: String, gradeScore: Int): Result<Unit> = result {
    mutate { csrf ->
        com.android.purebilibili.data.repository.DesktopVideoGradeProtocol(api)
            .submitGradeDanmaku(aid, cid, progress, gradeId, gradeScore, csrf).getOrThrow()
    }
}
'''
assert 'Desktop command vote grade binding' not in ops
assert ops.rstrip().endswith('}')
desired=ops[:ops.rfind('\n}')]+fragment+ops[ops.rfind('\n}'):]
write(HERE/'patches/ops-grade-member.fragment.kt',fragment)
write(HERE/'patches/ops-grade-member.patch',''.join(difflib.unified_diff(ops.splitlines(True),desired.splitlines(True),fromfile='a/'+opsPath,tofile='b/'+opsPath)))
write(HERE/'baseline/DesktopDynamicCardOperations.kt.reference.txt',ops)

# Compile-only stable schema/helper references are not another production owner.
# ResponseModels is already a direct stable Root source; Reply producer owns parsing.
models=sources[paths[5]]
def model(name):
 anchor='data class '+name+'(';ts=parser.kotlin_tokens(models);a=models.index(anchor);start=next(i for i,t in enumerate(ts) if t[1]>=a);end=start
 while ts[end][0]!='(':end+=1
 d=1
 while d:end+=1;d+=(ts[end][0]=='(')-(ts[end][0]==')')
 if name=='ReplyData':return declaration(models,anchor)
 return models[ts[start][1]:ts[end][2]]
write(HERE/'compile-references/StableReplyVoteModels.kt','package com.android.purebilibili.data.model.response\nimport kotlinx.serialization.SerialName\n'+
 '\n\n'.join(model(n) for n in ['ReplyData','ReplyVoteCard','ReplyVoteCardOption'])+'\n')
write(HERE/'compile-references/CommandDanmakuPolicy.kt',sources[paths[4]])
dialogPath=CANDIDATE/'desktop/build/generated/dynamic-full-card/com/android/purebilibili/feature/dynamic/components/DesktopOriginalDynamicVoteDialog.kt'
dialog=read(dialogPath);assert 'initialOptionIndex: Int? = null' in dialog and 'onVoteSuccess: (DynamicVoteInfo) -> Unit = {}' in dialog
write(HERE/'compile-references/DesktopOriginalDynamicVoteDialog.kt',dialog)
write(HERE/'preparation-contract.json',json.dumps(dict(preparedOnly=True,MainChanged=False,candidateChanged=False,
 originalTag=TAG,originalCommit=COMMIT,originalSourcePins=[dict(path=p,sha256Lf=h(s)) for p,s in sources.items()],
 candidateOpsBaseSha256Lf=h(ops),candidateOpsDesiredSha256Lf=h(desired),opsMemberOnlyPatch=True,
 gradeOriginalSelectedSha256Lf=h(grade),errorMapperOriginalSha256Lf=h(error),
 compileReferencesAreNotInstallPayload=True,soleDialogProducer='Existing extract-upstream-dynamic-card.py stable emitter',
 replyParsingOwner='Root/dynamic_action_review existing reply producer',noHTTP=True,noStore=True),indent=2,ensure_ascii=False))

# Existing consumers are reviewable local hunks, not full-file Main replacements.
panelsPath='desktop/src/main/kotlin/com/bilipai/desktop/ui/PlayerPanels.kt'
panels=read(CANDIDATE/panelsPath);new=panels
assert 'viewPoints: List<' in panels, 'Keep Root chapter integration baseline'
new=new.replace('import androidx.compose.ui.Modifier','import androidx.compose.ui.Modifier\nimport androidx.compose.ui.layout.onSizeChanged\nimport androidx.compose.ui.unit.IntSize',1)
anchor='    viewPoints: List<com.android.purebilibili.data.model.response.ViewPoint> = emptyList(),\n'
assert new.count(anchor)==1;new=new.replace(anchor,anchor+'    commandOverlay: (@Composable () -> Unit)? = null,\n',1)
new=new.replace('    val state by player.state.collectAsState()','    val state by player.state.collectAsState()\n    var videoSurfaceSize by remember { mutableStateOf(IntSize.Zero) }',1)
new=new.replace('Box(modifier.fillMaxSize().background(Color.Black)) {','Box(modifier.fillMaxSize().background(Color.Black).onSizeChanged { videoSurfaceSize = it }) {',1)
new=new.replace('Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {','Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black).onSizeChanged { videoSurfaceSize = it }) {',1)
anchor='            else Text("正在浮窗播放", color = Color.White, modifier = Modifier.align(Alignment.Center))\n'
assert new.count(anchor)==2;new=new.replace(anchor,anchor+'            if (renderSurface && commandOverlay != null) DesktopVideoCommandPopup(videoSurfaceSize, commandOverlay)\n')
write(HERE/'baseline/PlayerPanels.kt.reference.txt',panels)
write(HERE/'patches/player-overlay-slot.patch',''.join(difflib.unified_diff(panels.splitlines(True),new.splitlines(True),fromfile='a/'+panelsPath,tofile='b/'+panelsPath)))
write(HERE/'prepared/consumer-reference/PlayerPanels.kt',new)

engagePath='desktop/src/main/kotlin/com/bilipai/desktop/ui/VideoEngagementPanel.kt'
engage=read(CANDIDATE/engagePath);new=engage
assert new.count('VideoCommentPanel(details.aid, social, community, onUser, onLogin)')==1
new=new.replace('VideoCommentPanel(details.aid, social, community, onUser, onLogin)',
 'VideoCommentPanel(details.aid, repository, social, community, onUser, onLogin)',1)
new=new.replace('private fun VideoCommentPanel(aid: Long, social:', 'private fun VideoCommentPanel(aid: Long, repository: DesktopRepository, social:',1)
anchor='    Text("评论", style = MaterialTheme.typography.titleLarge)\n'
assert new.count(anchor)==1
new=new.replace(anchor,anchor+'''    if (root == null) DesktopVideoCommentVoteSlot(repository, aid, mode = if (sort == 1) 3 else 2,
        refreshKey = revision, onFeedback = { error = IllegalStateException(it) }, modifier = Modifier.fillMaxWidth())
''',1)
write(HERE/'baseline/VideoEngagementPanel.kt.reference.txt',engage)
write(HERE/'patches/comment-vote-consumer.patch',''.join(difflib.unified_diff(engage.splitlines(True),new.splitlines(True),fromfile='a/'+engagePath,tofile='b/'+engagePath)))
write(HERE/'prepared/consumer-reference/VideoEngagementPanel.kt',new)
write(HERE/'consumer-baselines.json',json.dumps([
 dict(path=opsPath,sha256Lf=h(ops),patch='patches/ops-grade-member.patch',existingMembersKept=True),
 dict(path=panelsPath,sha256Lf=h(panels),patch='patches/player-overlay-slot.patch',chapterFieldsKept=True),
 dict(path=engagePath,sha256Lf=h(engage),patch='patches/comment-vote-consumer.patch',legacyCommentsKept=True)
],indent=2))
print('Prepared stable original vote Card/Overlay/State + source-preserving grade protocol and one independent Ops hunk.')
