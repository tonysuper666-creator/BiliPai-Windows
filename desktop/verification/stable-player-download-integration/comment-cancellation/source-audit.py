from pathlib import Path
import hashlib,importlib.util,json,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
MAIN=next(p for p in HERE.parents if (p/'.git').exists())
BASE=MAIN.parent/'BiliPai-v023';FIRST=MAIN/'desktop/.local/stable-dynamic-reply-image-composer-parity'
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def load(name,p):
 spec=importlib.util.spec_from_file_location(name,p);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def main():
 host=load('readonly_host',BASE/'desktop/tools/extract-upstream-plugins.py');media=host.media_extractor(BASE);parser=media.parser_for(BASE)
 def find(root,name):
  p=list(safe(root).rglob(name));assert len(p)==1;return p[0]
 def fn(s,n):return media.function(s,n,parser).strip()
 checks=[]
 def prove(name,ok,**metadata):assert ok,name;checks.append(dict(name=name,status='PASS',**metadata))
 oldsession=read(find(FIRST/'generated/detail','DesktopOriginalDynamicReplySession.kt'))
 session=read(find(HERE/'generated/detail','DesktopOriginalDynamicReplySession.kt'))
 old=fn(oldsession,'postComment');new=fn(session,'postComment')
 reverse=new.replace('    onSubmissionCancelled: () -> Unit = {},\n','',1)
 start=reverse.index('    val submissionId = commentSubmissionSequence.incrementAndGet()')
 end=reverse.index('    val submissionJob = launchOwned {',start)
 reverse=reverse[:start]+reverse[end:]
 reverse=reverse.replace('    val submissionJob = launchOwned {','    launchOwned {',1).replace('deliverSubmissionResult(','onResult(')
 start=reverse.index('    submissionJob.invokeOnCompletion { failure ->')
 end=reverse.rindex('\n}')
 reverse=reverse[:start]+reverse[end:]
 # Removing an appended completion leaves the exact prior closing brace; retain
 # its source newline, not an additional blank line introduced by slicing.
 reverse=reverse.replace('    }\n\n}','    }\n}')
 prove('postComment previous original-business body byte equality after only completion gateway reversal',reverse==old,previousBodySha256LF=sha(old),candidateBodySha256LF=sha(new))
 oldinline=read(find(FIRST/'generated/reply','DesktopOriginalDynamicInlineReplyUi.kt'))
 inline=read(find(HERE/'generated/reply','DesktopOriginalDynamicInlineReplyUi.kt'))
 old=fn(oldinline,'DynamicCommentComposer');new=fn(inline,'DynamicCommentComposer')
 reverse=new.replace('    var activeSubmission by remember { mutableStateOf<Any?>(null) }\n','',1)
 reverse=reverse.replace('        val submission = Any()\n        activeSubmission = submission\n','',1)
 reverse=reverse.replace('            if (!platform.isOwned() || activeSubmission !== submission) return@onSubmit\n            activeSubmission = null\n','',1)
 prove('private composer previous complete original UI body byte equality after matching callback guard reversal',reverse==old,previousBodySha256LF=sha(old),candidateBodySha256LF=sha(new))
 prove('public wrapper unchanged including success-only text/target/focus clearing',fn(oldinline,'DynamicInlineCommentComposer')==fn(inline,'DynamicInlineCommentComposer'))
 prove('original upload and pictures map unchanged',fn(oldsession,'uploadCommentPictures')==fn(session,'uploadCommentPictures'))
 prove('completion requires cancellation and current live owner/latest submit/once only',all(s in session for s in ['failure is CancellationException && isOwned() && commentSubmissionSequence.get() == submissionId','resultDelivered.compareAndSet(false, true)','onSubmissionCancelled()']))
 prove('completion contains no business receipt or feedback','_commentConfirmationRevision' not in session[session.index('        submissionJob.invokeOnCompletion'):session.index('    private suspend fun uploadCommentPictures')])
 prove('UI only matching owner/token clears actual busy','if (!platform.isOwned() || activeSubmission !== submission) return@onSubmit' in inline and 'activeSubmission = null\n            if (success) selectedImages = emptyList()\n            isSending = false' in inline)
 panel=read(find(HERE/'generated/reply','DesktopOriginalDynamicCommentPanel.kt'))
 oldpanel=read(find(FIRST/'generated/reply','DesktopOriginalDynamicCommentPanel.kt'))
 prove('panel only cancellation callback argument differs',panel.replace(', onSubmissionCancelled = { onResult(false) }','')==oldpanel)
 path='desktop/tools/extract-upstream-dynamic-detail-container.py'
 base=load('readonly_base_container',HERE/'base-inputs'/path)
 base.generate(BASE,safe(HERE/'base-generated/container'))
 oldlayout=read(find(HERE/'base-generated/container','DesktopOriginalDynamicDetailLayout.kt'))
 layout=read(find(HERE/'generated/container','DesktopOriginalDynamicDetailLayout.kt'))
 prove('actual layout entire generated source differs only completion callback argument',layout.replace(', onSubmissionCancelled = { onResult(false) }','')==oldlayout)
 oldfiles={p.name:read(p) for p in safe(HERE/'base-generated/container').rglob('*.kt')}
 newfiles={p.name:read(p) for p in safe(HERE/'generated/container').rglob('*.kt')}
 prove('all other container bodies unchanged',oldfiles.keys()==newfiles.keys() and all(oldfiles[n]==newfiles[n] for n in oldfiles if n!='DesktopOriginalDynamicDetailLayout.kt'),generatedSources=len(newfiles))
 result=dict(status='PASS',checks=checks,checkCount=len(checks),previous109ManifestSha256Bytes='a91a58a41674adb28b77fb39e9edd56208865536501a1ed9e1fa6720d907902f',scope='owned submission completion metadata only; no original model/store/math/config changes',preparedOnly=True)
 safe(HERE/'source-contract-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
 print(json.dumps(dict(status='PASS',checks=len(checks))))
if __name__=='__main__':main()
