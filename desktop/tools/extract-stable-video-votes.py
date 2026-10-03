"""Source-preserving stable command overlay/grade extraction; direct UI is registry-owned."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import hashlib,importlib.util,sys,textwrap
sys.dont_write_bytecode=True
BASE="app/src/main/java/com/android/purebilibili/"
PINS={'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/CommandDanmakuOverlay.kt': '4e57ab6372d3dfd4dc801c67f12e8c1e4263a846c06485a52ba8e91d3bd05e1f', 'app/src/main/java/com/android/purebilibili/data/repository/DanmakuRepository.kt': 'b9393e987cfabe627baaf7ac2ed3e54fa8e84d9db0079e7f8b37760422a21d5b'}
OPS_FRAGMENT='\n// STABLE_VIDEO_VOTE_GRADE_MEMBERS\n// Desktop command vote grade binding. Standard vote continues through submitVote.\nsuspend fun submitGradeDanmaku(aid: Long, cid: Long, progress: Long, gradeId: String, gradeScore: Int): Result<Unit> = result {\n    mutate { csrf ->\n        com.android.purebilibili.data.repository.DesktopVideoGradeProtocol(api)\n            .submitGradeDanmaku(aid, cid, progress, gradeId, gradeScore, csrf).getOrThrow()\n    }\n}\n'
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
 p=BASE+"feature/video/ui/overlay/CommandDanmakuOverlay.kt";s=read(p)
 s=s.replace("import androidx.media3.common.Player","import com.bilipai.desktop.player.MpvPlayer")
 assert s.count("player: Player,")==1 and s.count("player.currentPosition")==2
 s=s.replace("player: Player,","player: MpvPlayer,").replace("player.currentPosition","(player.state.value.positionSeconds * 1000.0).toLong()")
 # Windows-only measured native hit region. Original timing/card/dialog body stays intact.
 s=s.replace("import androidx.compose.ui.Modifier\n", "import androidx.compose.ui.Modifier\nimport com.bilipai.desktop.ui.desktopCommandHitRegion\nimport com.bilipai.desktop.ui.DesktopCommandModalRegion\n", 1)
 measured="            .onSizeChanged { measuredCardHeightPx = it.height }\n"
 assert s.count(measured)==1
 s=s.replace(measured, measured+"            .desktopCommandHitRegion(item.id)\n", 1)
 modal="    var votePanelInitialOptionIndex by remember { mutableIntStateOf(-1) }\n"
 assert s.count(modal)==1
 s=s.replace(modal,modal+"    DesktopCommandModalRegion(votePanelVoteId != null)\n",1)
 emit(p,"com/android/purebilibili/feature/video/ui/overlay/DesktopOriginalCommandDanmakuOverlay.kt",s)
 p=BASE+"data/repository/DanmakuRepository.kt";s=read(p)
 grade=function(s,"suspend fun submitGradeDanmaku(")
 grade=grade.replace("gradeScore: Int\n    )","gradeScore: Int,\n        csrf: String\n    )",1)
 line="            val csrf = com.android.purebilibili.core.store.TokenManager.csrfCache\n";assert grade.count(line)==1
 grade=grade.replace(line,"",1)
 grade=grade.replace("        } catch (e: Exception) {","        } catch (cancelled: kotlinx.coroutines.CancellationException) {\n            throw cancelled\n        } catch (e: Exception) {",1)
 error=function(s,"internal fun mapSendDanmakuErrorMessage(").replace("internal fun","private fun",1)
 body="package com.android.purebilibili.data.repository\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.withContext\n"+error+"\ninternal class DesktopVideoGradeProtocol(private val api: com.android.purebilibili.core.network.BilibiliApi) {\n"+textwrap.indent(grade,"    ")+"\n}\n"
 emit(p,"com/android/purebilibili/data/repository/DesktopVideoGradeProtocol.kt",body)
 target=output/"platform/DesktopVideoGradeMembers.fragment";target.parent.mkdir(parents=True,exist_ok=True)
 target.write_text(OPS_FRAGMENT,encoding="utf-8",newline="\n")
if __name__=="__main__":generate(Path(sys.argv[1]).resolve(),Path(sys.argv[2]).resolve())
