"""Complete original v037 page backdrop with one explicit native recording seam."""
from pathlib import Path
import hashlib,json
COMMIT='8b47769d22c0d5bc88ae5248cee4aa04ff10d62c'
MANIFEST_SHA='f9640476d95332a5e6d6a31afd010506779d8fedc4e0903d75b891f58087b914'
PINS=[{'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/PlayerPageBackdrop.kt', 'filename': 'PlayerPageBackdrop.kt', 'bytes': 2184, 'sha256Bytes': 'f3598ff83615ded75f55efaed5f39fa3b95f5a45bd2098ecc29b05deee88e7ec', 'gitBlob': '412d50de96fa4b6fe2c4557ebea61ad1af23fb33'}, {'path': 'app/src/test/java/com/android/purebilibili/feature/video/ui/section/PlayerPageBackdropPolicyTest.kt', 'filename': 'PlayerPageBackdropPolicyTest.kt', 'bytes': 2092, 'sha256Bytes': '825e3c3c0e0e4b2c816ff656a2946b8f395dd96f53f67766dddb532e32eb992a', 'gitBlob': '3ff9b22771b2ed5b1843c3aef12c3dff3a46b4f8'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/blur/ChromeBackdropSource.kt', 'filename': 'ChromeBackdropSource.kt', 'bytes': 2662, 'sha256Bytes': 'ae30c70ebbfc8b57d18b5b362ecd39547d42b638d90a9b2e21de5a6be63d2ae4', 'gitBlob': '3365509b681d8f207d307cdc12226cfb34351edb'}]
def digest(raw):return hashlib.sha256(raw).hexdigest()
def wide(p):
 s=str(Path(p).absolute())
 prefix=chr(92)*2+'?'+chr(92)
 return Path(s if s.startswith(prefix) else prefix+s)
def fixed_sources():
 root=Path(__file__).resolve().parent.parent/'upstream-slices/v037-player-page-backdrop'
 raw=(root/'manifest.json').read_bytes()
 assert digest(raw)==MANIFEST_SHA
 m=json.loads(raw);assert m['fixedUpstreamCommit']==COMMIT and m['files']==PINS
 result={}
 for row in PINS:
  raw=(root/row['filename']).read_bytes()
  assert len(raw)==row['bytes'] and digest(raw)==row['sha256Bytes']
  assert hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()==row['gitBlob']
  result[row['filename']]=raw.decode('utf-8')
 return result
def emit_foundation(repo,output,outputs,test_output=None):
 sources=fixed_sources();records=[]
 for row in PINS:
  if '/test/' in row['path']:
   if test_output:
    rel=row['path'].split('/test/java/',1)[1]
    p=wide(Path(test_output)/rel);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(sources[row['filename']].encode())
   continue
  original=sources[row['filename']];text=original;edits=[]
  def exact(before,after,label):
   nonlocal text
   assert text.count(before)==1,(row['filename'],label,text.count(before))
   text=text.replace(before,after,1);edits.append(dict(before=before,after=after,label=label))
  if row['filename']=='ChromeBackdropSource.kt':
   exact('import androidx.compose.runtime.remember','import androidx.compose.runtime.remember\nimport androidx.compose.runtime.getValue\nimport androidx.compose.runtime.rememberUpdatedState\nimport androidx.compose.ui.graphics.drawscope.DrawScope','Fresh owned native recording callback')
   exact('fun rememberChromeBackdropSource(): ChromeBackdropSource {','fun rememberChromeBackdropSource(recordContent: (DrawScope.() -> Unit)? = null): ChromeBackdropSource {\n    val latestRecordContent by rememberUpdatedState(recordContent)','Optional platform recording seam; all original child callers retain behavior')
   exact('contentLayer.record { this@drawWithContent.drawContent() }\n                    drawLayer(contentLayer)','contentLayer.record {\n                        val recording = latestRecordContent\n                        if (recording == null) this@drawWithContent.drawContent()\n                        else recording.invoke(this)\n                    }\n                    if (latestRecordContent == null) drawLayer(contentLayer)\n                    else this@drawWithContent.drawContent()','Record native mirror only into the backdrop; never display its thumbnail over video')
  else:
   exact('import androidx.compose.runtime.remember','import androidx.compose.runtime.remember\nimport androidx.compose.ui.graphics.drawscope.DrawScope','Required native DrawScope')
   exact('internal fun rememberPlayerPageBackdrop(): PlayerPageBackdrop {','internal fun rememberPlayerPageBackdrop(videoRecording: DrawScope.() -> Unit): PlayerPageBackdrop {','Same Root native owner records actual video')
   exact('val video = rememberChromeBackdropSource()','val video = rememberChromeBackdropSource(recordContent = videoRecording)','All original cover/danmaku/subtitle paint ordering remains')
  reverted=text
  for edit in reversed(edits):
   assert reverted.count(edit['after'])==1
   reverted=reverted.replace(edit['after'],edit['before'],1)
  assert reverted==original
  rel=row['path'].split('/main/java/',1)[1]
  p=wide(Path(output)/rel);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(text.encode())
  entry=dict(path=rel,mode='complete-original-platform-recording',generated=True,sha256LF=digest(text.encode()),source=dict(row,upstreamCommit=COMMIT),wholeOriginalInverse=True)
  outputs.append(entry);records.append(dict(entry,edits=edits))
 p=wide(Path(output)/'v037-player-page-backdrop-readback.json')
 p.write_bytes((json.dumps(dict(upstreamCommit=COMMIT,outputs=records,originalPolicyCasesPrepared=3,actualJvmCases=0,pixelsValidated=False),ensure_ascii=False,indent=2)+'\n').encode())

