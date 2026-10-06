"""Source-preserving stable command overlay/grade extraction; direct UI is registry-owned."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import hashlib,importlib.util,sys,textwrap,json
import v029_command_vote as v029
import v030_command_link as v030
sys.dont_write_bytecode=True
BASE="app/src/main/java/com/android/purebilibili/"
PINS={'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/CommandDanmakuOverlay.kt': '4e57ab6372d3dfd4dc801c67f12e8c1e4263a846c06485a52ba8e91d3bd05e1f', 'app/src/main/java/com/android/purebilibili/data/repository/DanmakuRepository.kt': 'b9393e987cfabe627baaf7ac2ed3e54fa8e84d9db0079e7f8b37760422a21d5b'}
OPS_FRAGMENT='\n// STABLE_VIDEO_VOTE_GRADE_MEMBERS\n// Desktop command vote grade binding. Standard vote continues through submitVote.\nsuspend fun submitGradeDanmaku(aid: Long, cid: Long, progress: Long, gradeId: String, gradeScore: Int): Result<Unit> = result {\n    mutate { csrf ->\n        com.android.purebilibili.data.repository.DesktopVideoGradeProtocol(api)\n            .submitGradeDanmaku(aid, cid, progress, gradeId, gradeScore, csrf).getOrThrow()\n    }\n}\n'
OPS_FRAGMENT += '\nsuspend fun getGradeDanmakuSummary(cid: Long, aid: Long, gradeId: String): Result<com.android.purebilibili.data.model.response.GradeDanmakuSummary> = result {\n    read {\n        com.android.purebilibili.data.repository.DesktopVideoGradeProtocol(api)\n            .getGradeDanmakuSummary(cid, aid, gradeId).getOrThrow()\n    }\n}\n'

def generate(repo:Path,output:Path):
 spec=importlib.util.spec_from_file_location("video_vote_ast",repo/"desktop/tools/sync-upstream.py")
 parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
 def read(p):
  s=(_desktop_canonical_source(repo, p)).read_text(encoding="utf-8").replace("\r\n","\n").replace("\r","\n")
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
  target.write_text("// GENERATED from "+p+"; do not edit.\n// LF-normalized SHA-256: "+PINS[p]+"\n"+body,encoding="utf-8",newline="\n")
 p=BASE+"feature/video/ui/overlay/CommandDanmakuOverlay.kt";read(p)
 original=v030.read(repo,p);edits=[];s=v029.adapt_overlay(original,edits)
 def emit_v029(path,source_path,body):
  target=output/path;target.parent.mkdir(parents=True,exist_ok=True)
  size,raw_sha,blob=v029.PINS[source_path]
  target.write_text("// GENERATED from "+source_path+"; do not edit.\n// Fixed upstream "+v029.COMMIT+"; raw SHA-256: "+raw_sha+"; Git blob: "+blob+"\n"+body,encoding="utf-8",newline="\n")
 v030.emit(output,"com/android/purebilibili/feature/video/ui/overlay/DesktopOriginalCommandDanmakuOverlay.kt",p,s)
 v030.emit_link_callbacks(repo,output)
 for source_path in [v029.POLICY,v029.STATE]:
  emit_v029(source_path.split("/java/",1)[1],source_path,v029.read(repo,source_path))
 summary=v029.read(repo,v029.SUMMARY)
 begin="/** Statistics supplied by the grade command, never inferred from a submitted score. */"
 end="/**\n * 弹幕操作响应"
 assert summary.count(begin)==summary.count(end)==1
 selected=summary[summary.index(begin):summary.index(end)]
 header=summary[:summary.index("@Serializable")]
 emit_v029("com/android/purebilibili/data/model/response/DesktopOriginalGradeDanmakuSummary.kt",v029.SUMMARY,header+selected)
 original_body,body=v029.submission_body(v029.read(repo,v029.SECTION),submission_edits:=[])
 header="""package com.android.purebilibili.feature.video.ui.overlay
import com.android.purebilibili.feature.video.danmaku.CommandDanmakuItem
import com.android.purebilibili.feature.video.danmaku.VoteOption
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.bilipai.desktop.ui.DesktopWindowsCommandVotePlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
internal fun submitOriginalDesktopCommandVote(item: CommandDanmakuItem, option: VoteOption, optionIndex: Int,
    uiState: VideoPlaybackUiState?, commandState: CommandDanmakuOverlayState, settingsScope: CoroutineScope,
    platform: DesktopWindowsCommandVotePlatform) {
"""
 emit_v029("com/android/purebilibili/feature/video/ui/overlay/DesktopOriginalCommandVoteSubmission.kt",v029.SECTION,header+body+"\n}\n")
 (output/"v029-command-vote-proof.json").write_text(json.dumps({
  "upstreamCommit":v029.COMMIT,"overlayUpstreamCommit":v030.COMMIT,"overlaySourcePin":v030.PINS[p],"overlayFullInverse":v029.inverse(s,edits)==original,
  "submissionFullInverse":v029.inverse(body,submission_edits)==original_body,
  "overlayEdits":edits,"submissionEdits":submission_edits,
  "files":v029.PINS,"legacyModalRemovedByOriginalSource": "votePanelVoteId" not in original,
 },ensure_ascii=False,indent=2)+"\n",encoding="utf-8",newline="\n")
 p=BASE+"data/repository/DanmakuRepository.kt";read(p);s=v029.read(repo,p)
 grade_original=function(s,"suspend fun submitGradeDanmaku(");grade_edits=[]
 grade=v029.replace(grade_original,"gradeScore: Int\n    )","gradeScore: Int,\n        csrf: String\n    )",grade_edits)
 line="            val csrf = com.android.purebilibili.core.store.TokenManager.csrfCache\n"
 grade=v029.replace(grade,line,"            // Windows: CSRF is supplied by the same captured account Operations.\n",grade_edits)
 assert v029.inverse(grade,grade_edits)==grade_original
 assert grade.count("catch (e: CancellationException)")==1
 error=function(s,"internal fun mapSendDanmakuErrorMessage(").replace("internal fun","private fun",1)
 summary_resolver=function(s,"internal fun resolveGradeDanmakuSummary(")
 summary_request=function(s,"suspend fun getGradeDanmakuSummary(")
 imports="""package com.android.purebilibili.data.repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import com.android.purebilibili.data.model.response.GradeDanmakuSummary
import com.android.purebilibili.data.model.response.parseGradeDanmakuSummary
import com.android.purebilibili.danmaku.parser.DanmakuProto
"""
 body=imports+error+"\n"+summary_resolver+"\ninternal class DesktopVideoGradeProtocol(private val api: com.android.purebilibili.core.network.BilibiliApi) {\n"+textwrap.indent(grade,"    ")+"\n"+textwrap.indent(summary_request,"    ")+"\n}\n"
 emit_v029("com/android/purebilibili/data/repository/DesktopVideoGradeProtocol.kt",p,body)
 target=output/"platform/DesktopVideoGradeMembers.fragment";target.parent.mkdir(parents=True,exist_ok=True)
 target.write_text(OPS_FRAGMENT,encoding="utf-8",newline="\n")
 proof_path=output/"v029-command-vote-proof.json";proof=json.loads(proof_path.read_text(encoding="utf-8"))
 proof.update(gradeFullInverse=True,gradeEdits=grade_edits,summaryResolverWholeSha256=hashlib.sha256(summary_resolver.encode()).hexdigest(),summaryRequestWholeSha256=hashlib.sha256(summary_request.encode()).hexdigest())
 proof_path.write_text(json.dumps(proof,ensure_ascii=False,indent=2)+"\n",encoding="utf-8",newline="\n")
if __name__=="__main__":generate(Path(sys.argv[1]).resolve(),Path(sys.argv[2]).resolve())
