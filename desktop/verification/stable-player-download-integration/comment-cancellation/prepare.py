"""Matching same-owner cancelled submission completion only; previous109 frozen."""
from pathlib import Path
import hashlib,importlib.util,json,difflib,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
MAIN=next(p for p in HERE.parents if (p/'.git').exists())
BASE=MAIN.parent/'BiliPai-v023'
FIRST=MAIN/'desktop/.local/stable-dynamic-reply-image-composer-parity'
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def one(s,a,b):assert s.count(a)==1,(s.count(a),a[:100]);return s.replace(a,b,1)
def load(name,p):
 spec=importlib.util.spec_from_file_location(name,p);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def main():
 assert hashlib.sha256(safe(FIRST/'evidence-manifest.json').read_bytes()).hexdigest()=='a91a58a41674adb28b77fb39e9edd56208865536501a1ed9e1fa6720d907902f'
 rows=[];patch=[]
 def emit(path,old,new):
  write(HERE/'base-inputs'/path,old);write(HERE/'prepared'/path,new)
  rows.append(dict(path=path,baseSha256LF=sha(old),candidateSha256LF=sha(new)))
  patch.extend(difflib.unified_diff(old.splitlines(True),new.splitlines(True),fromfile='a/'+path,tofile='b/'+path))
 detail='desktop/tools/extract-upstream-dynamic-detail.py';old=read(FIRST/'prepared'/detail)
 source=old
 anchor="    body = body[:post_start] + post + body[post_end:]"
 block='''    # Windows-only completion of the exact admitted submission. Refresh/sort may
    # retire its read generation while retaining the same mounted composer.
    # Only cancellation releases busy; this seam emits no business receipt/toast.
    post = post.replace('        onResult: (Boolean, String) -> Unit,',
        '        onSubmissionCancelled: () -> Unit = {},\\n        onResult: (Boolean, String) -> Unit,', 1)
    post = post.replace('onResult(', 'deliverSubmissionResult(')
    post = post.replace('        val replyTarget = _commentReplyTarget.value\\n', '''+repr('''        val replyTarget = _commentReplyTarget.value
        val submissionId = commentSubmissionSequence.incrementAndGet()
        val resultDelivered = AtomicBoolean(false)
        fun deliverSubmissionResult(success: Boolean, message: String) {
            if (isOwned() && commentSubmissionSequence.get() == submissionId && resultDelivered.compareAndSet(false, true)) {
                onResult(success, message)
            }
        }
''')+''', 1)
    post = post.replace('        launchOwned {', '        val submissionJob = launchOwned {', 1)
    completion_marker = '        }\\n    }\\n\\n    private suspend fun uploadCommentPictures'
    assert post.count(completion_marker) == 1
    post = post.replace(completion_marker, '''+repr('''        }
        submissionJob.invokeOnCompletion { failure ->
            if (failure is CancellationException && isOwned() && commentSubmissionSequence.get() == submissionId &&
                resultDelivered.compareAndSet(false, true)) {
                onSubmissionCancelled()
            }
        }
    }

    private suspend fun uploadCommentPictures''')+''', 1)
'''+anchor
 source=one(source,anchor,block)
 source=one(source,'import java.util.concurrent.atomic.AtomicBoolean\n','import java.util.concurrent.atomic.AtomicBoolean\nimport java.util.concurrent.atomic.AtomicLong\n')
 source=one(source,'    private val replyMutationHolders = ConcurrentHashMap<Long, Any>()','    private val commentSubmissionSequence = AtomicLong(0L)\n    private val replyMutationHolders = ConcurrentHashMap<Long, Any>()')
 emit(detail,old,source)
 reply='desktop/tools/extract-upstream-dynamic-reply.py';old=read(FIRST/'prepared'/reply);source=old
 anchor="    declarations = declarations.replace('val keyboardController = LocalSoftwareKeyboardController.current',"
 block='''    # Keep the source's selection/send/success flow. A retired submit callback
    # must never clear the replacement submit's busy/selected-image state.
    declarations = replace_once(declarations,
        '    var isSending by remember { mutableStateOf(false) }',
        '    var isSending by remember { mutableStateOf(false) }\\n    var activeSubmission by remember { mutableStateOf<Any?>(null) }')
    declarations = replace_once(declarations,
        '        isSending = true\\n        onSubmit(value.trim(), selectedImages) { success ->',
        '        isSending = true\\n        val submission = Any()\\n        activeSubmission = submission\\n        onSubmit(value.trim(), selectedImages) { success ->\\n            if (!platform.isOwned() || activeSubmission !== submission) return@onSubmit\\n            activeSubmission = null')
'''+anchor
 source=one(source,anchor,block)
 # Panel is one of the two actual original consumers of the same inline composer.
 anchor="    body = body.replace('interactionViewModel', 'session').replace('state.item', 'item')"
 source=one(source,anchor,anchor+"\n    body = replace_once(body, 'session.postComment(item.id_str, message, images) {',\n        'session.postComment(item.id_str, message, images, onSubmissionCancelled = { onResult(false) }) {')")
 emit(reply,old,source)
 container='desktop/tools/extract-upstream-dynamic-detail-container.py';old=read(BASE/container);source=old
 anchor="    success=re.sub(r'android\\.widget\\.Toast\\.makeText\\(context,\\s*(\\w+),\\s*android\\.widget\\.Toast\\.LENGTH_SHORT\\)\\.show\\(\\)',r'if (platform.isOwned()) platform.showFeedback(\\1)',success)"
 assert anchor in source
 source=one(source,anchor,anchor+"\n    assert success.count('session.postComment(state.item.id_str, message, images) {') == 1\n    success=success.replace('session.postComment(state.item.id_str, message, images) {',\n        'session.postComment(state.item.id_str, message, images, onSubmissionCancelled = { onResult(false) }) {', 1)")
 emit(container,old,source)
 write(HERE/'necessary-source.patch',''.join(patch))
 write(HERE/'source-delta-pins.json',json.dumps(rows,ensure_ascii=False,indent=2)+'\n')
 for name,path in [('detail',detail),('reply',reply),('container',container)]:
  m=load('cancel_'+name,HERE/'prepared'/path)
  emitted=m.generate(BASE,safe(HERE/'generated'/name))
  if emitted is None: emitted=sorted(safe(HERE/'generated'/name).rglob('*.kt'))
  write(HERE/(name+'-generated.json'),json.dumps([dict(path=str(p),sha256Bytes=hashlib.sha256(safe(p).read_bytes()).hexdigest()) for p in emitted],ensure_ascii=False,indent=2)+'\n')
 print(json.dumps(dict(status='prepared',sources=rows)))
if __name__=='__main__':main()
