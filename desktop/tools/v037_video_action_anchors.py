"""Full original video action anchors with the existing Windows UI dispatcher."""
from pathlib import Path
import hashlib,json
from v029_comment_search import whole_proof
COMMIT='8b47769d22c0d5bc88ae5248cee4aa04ff10d62c'
MANIFEST_SHA='6078ddebf276c09405925336de408c14c3faa9ef09a3403558b0f96216826946'
PIN={'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/feedback/LikeBurstAnchor.kt', 'filename': 'LikeBurstAnchor.kt', 'bytes': 4365, 'sha256Bytes': '51a007f0af21c903cf842a2a876db916b859fe90c41e428a980a5b9d3f6960f8', 'gitBlob': 'ff44257b1ca34b98dded82a93ae1383fe931718f'}
def sha(raw):return hashlib.sha256(raw).hexdigest()
def fixed_source():
 root=Path(__file__).resolve().parent.parent/'upstream-slices/v037-video-feedback-anchor'
 raw=(root/'manifest.json').read_bytes();assert sha(raw)==MANIFEST_SHA
 manifest=json.loads(raw);assert manifest['fixedUpstreamCommit']==COMMIT and manifest['files']==[PIN]
 raw=(root/PIN['filename']).read_bytes()
 assert len(raw)==PIN['bytes'] and sha(raw)==PIN['sha256Bytes']
 assert hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()==PIN['gitBlob']
 return raw.decode('utf-8')
def emit(output,outputs):
 original=fixed_source();text=original
 old='import android.os.Handler\nimport android.os.Looper'
 new='import javax.swing.Timer'
 assert text.count(old)==1;text=text.replace(old,new,1)
 old='    private val handler = Handler(Looper.getMainLooper())\n'
 assert text.count(old)==1;text=text.replace(old,'',1)
 old='''        handler.postDelayed({
            if (generation == genAtDetach) bounds = null
        }, CLEAR_DELAY_MS)'''
 new='''        Timer(CLEAR_DELAY_MS.toInt()) {
            if (generation == genAtDetach) bounds = null
        }.apply { isRepeats = false; start() }'''
 assert text.count(old)==1;text=text.replace(old,new,1)
 rel=PIN['path'].split('/main/java/',1)[1]
 assert not any(x['path']==rel for x in outputs)
 p=Path(output)/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(text.encode())
 proof=whole_proof(original,text)
 proof.update(originalGenerationAndDeferredClearPreserved=True,windowsUiEventQueueTimer=True,
              sourceQualified=False,pixelsValidated=False)
 p=Path(output)/'v037-video-action-anchors-proof.json'
 p.write_bytes((json.dumps(proof,ensure_ascii=False,indent=2)+'\n').encode())
 outputs.append(dict(path=rel,origin=PIN['path'],fixedCommit=COMMIT,mode='complete-original-Windows-ui-scheduler',generated=True,sha256LF=sha(text.encode()),source=PIN))

