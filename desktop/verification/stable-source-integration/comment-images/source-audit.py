"""Body identity and declared platform adaptations, source only."""
from pathlib import Path
import hashlib,importlib.util,json,re,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
MAIN=next(p for p in HERE.parents if (p/'.git').exists())
BASE=MAIN.parent/'BiliPai-v023'
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def load(name,p):
 spec=importlib.util.spec_from_file_location(name,p);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def main():
 host=load('host',BASE/'desktop/tools/extract-upstream-plugins.py');media=host.media_extractor(BASE);parser=media.parser_for(BASE)
 def fn(s,n):return media.function(s,n,parser).strip()
 paths='app/src/main/java/com/android/purebilibili/'
 vm=read(HERE/'original-source'/ (paths+'feature/dynamic/DynamicViewModel.kt'))
 sheet=read(HERE/'original-source'/ (paths+'feature/dynamic/components/DynamicCommentSheet.kt'))
 session=read(next(safe(HERE/'generated/detail').rglob('DesktopOriginalDynamicReplySession.kt')))
 inline=read(next(safe(HERE/'generated/reply').rglob('DesktopOriginalDynamicInlineReplyUi.kt')))
 checks=[]
 def prove(name,value,**metadata):
  assert value,name;checks.append(dict(name=name,status='PASS',**metadata))
 original=fn(vm,'postComment');candidate=fn(session,'postComment')
 reverse=candidate.replace('List<String>','List<Uri>')
 reverse=reverse.replace('    if (!isOwned()) return\n','',1)
 reverse=reverse.replace('    val replyTarget = _commentReplyTarget.value\n','',1)
 reverse=reverse.replace('            if (message.isBlank()', '            val replyTarget = _commentReplyTarget.value\n            if (message.isBlank()',1)
 reverse=reverse.replace('launchOwned {','viewModelScope.launch {').replace('return@launchOwned','return@launch')
 reverse=reverse.replace('            if (!requests.hasCsrf()) {','            val csrf = com.android.purebilibili.core.store.TokenManager.csrfCache\n            if (csrf.isNullOrEmpty()) {')
 reverse=reverse.replace('requests.addCommentForSubject','CommentRepository.addCommentForSubject')
 reverse=re.sub(r'^\s*ensureRequestOwned\(\)\n','',reverse,flags=re.M)
 if reverse!=original:
  import difflib
  print(''.join(difflib.unified_diff(original.splitlines(True),reverse.splitlines(True))))
 prove('postComment complete original business body reverse-normalized byte equality',reverse==original,originalBodySha256LF=sha(original),candidateBodySha256LF=sha(candidate),adaptations=['Uri->fileURI String','owned request launch/epoch checks','existing csrf owner','immutable synchronous reply target capture','post-upload request generation guard'])
 orig=fn(sheet,'DynamicInlineCommentComposer');cand=fn(inline,'DynamicInlineCommentComposer')
 normorig=orig.replace('    val keyboardController = LocalSoftwareKeyboardController.current','    // Physical Windows keyboard; original focus and IME action are retained.').replace('                keyboardController?.show()\n','').replace('            keyboardController?.hide()\n','').replace('List<Uri>','List<String>')
 prove('inline wrapper entire original body except declared keyboard/URI platform mappings',normorig==cand,originalBodySha256LF=sha(orig),candidateBodySha256LF=sha(cand))
 orig=fn(sheet,'DynamicCommentComposer');cand=fn(inline,'DynamicCommentComposer')
 reverse=cand.replace('List<String>','List<Uri>')
 start=reverse.index('    val platform = LocalDesktopCommentBindings.current')
 end=reverse.index('    LaunchedEffect(onClearReplyTarget != null)',start)
 oldstart=orig.index('    val picker = rememberLauncherForActivityResult(')
 oldend=orig.index('    LaunchedEffect(onClearReplyTarget != null)',oldstart)
 reverse=reverse[:start]+orig[oldstart:oldend]+reverse[end:]
 reverse=reverse.replace('                        pickImages()','                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))')
 start=reverse.index('        Box(\n')
 end=reverse.index('            val fieldColor = if (liquidChromeActive)',start)
 oldstart=orig.index('        BottomBarMatchedReusableLiquidDock(')
 oldend=orig.index('            val fieldColor = if (liquidChromeActive)',oldstart)
 reverse=reverse[:start]+orig[oldstart:oldend]+reverse[end:]
 lens='    val composerLensIntensity = resolveFloatingDockGeometryScale(composerHeight.value)\n'
 reverse=reverse.replace('    Column(\n',lens+'    Column(\n',1)
 prove('composer complete original UI body reverse-normalized byte equality',reverse==orig,originalBodySha256LF=sha(orig),candidateBodySha256LF=sha(cand),adaptations=['Uri->existing selected fileURI String','real owned Windows picker callback with rememberUpdatedState','existing explicit native dock capability fallback retained'])
 upload=fn(session,'uploadCommentPictures')
 prove('original upload nine bound ordered map/index and failure propagation retained',all(t in upload for t in ['require(imageUris.size <= 9) { "最多选择 9 张图片" }','imageUris.mapIndexed { index, uri ->','requests.uploadCommentPicture(uri, index).getOrElse { throw it }','withContext(Dispatchers.IO)']))
 opspath='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt'
 oldops=read(HERE/'base-inputs'/opspath);newops=read(HERE/'proof-only'/opspath)
 prove('existing sole stream multipart/upload/parser body byte unchanged',fn(oldops,'uploadEditorCommentImageBody')==fn(newops,'uploadEditorCommentImageBody'),bodySha256LF=sha(fn(oldops,'uploadEditorCommentImageBody')))
 prove('existing addCommentForSubject complete original protocol body byte unchanged',fn(oldops,'addCommentForSubject')==fn(newops,'addCommentForSubject'),bodySha256LF=sha(fn(oldops,'addCommentForSubject')))
 fragment=read(HERE/'operations-member.fragment.kt')
 prove('single additive comment member reaches same original streamed body',fragment.count('uploadEditorCommentImageBody(')==1 and 'result { mutate { csrf ->' in fragment and 'ByteArray' not in fragment)
 requests=read(HERE/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicReplyRequests.kt')
 prove('original comment filename/MIME fallback keys reused', '"comment_${System.currentTimeMillis()}_${index + 1}.jpg"' in requests and 'selected.second ?: "image/jpeg"' in requests)
 prove('upload adapter cancellation rethrows and gates provider and result', 'catch (cancelled: CancellationException) { throw cancelled }' in requests and requests.count('ensureOwned()')>=4)
 root=read(HERE/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/CommunityDynamicScreens.kt')
 prove('same detail owner uses sole existing stream selection and Root parent', 'DesktopDynamicEditorSelectedImages(::owned,operations::withOwnedEditorImageAdmission)' in root and 'imageProvider=selectedCommentImages::read' in root and 'LocalDesktopDynamicSaveParent.current' in root)
 prove('same owner actual Root picker and platform required callback connected','DesktopDynamicEditorWindowsPickers(selectedCommentImages,::owned,' in root and 'pickImages=commentPickers::pickImages' in root)
 prove('owner replacement disposes composer state and closes selected streams','key(alive)' in root and 'alive.set(false)};replySession.close();selectedCommentImages.close();pageScope.cancel()' in root)
 prove('picker latest callback cannot attach after reply transition','rememberUpdatedState<(List<String>) -> Unit>' in inline and 'if (!isSending && onClearReplyTarget == null)' in inline and 'platform.pickCommentImages(9)' in inline)
 prove('original image-only submit, send disable, reply clear and 9 labels retained',all(t in inline for t in ['!isSending && (value.isNotBlank() || selectedImages.isNotEmpty())','if (success) selectedImages = emptyList()','selectedImages.size < 9','添加图片，已选 ${selectedImages.size}/9 张','if (onClearReplyTarget != null) selectedImages = emptyList()']))
 result=dict(status='PASS',checks=checks,checkCount=len(checks),preparedOnly=True,originalCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',scope='full original inline composer + post business, owned streaming platform seams only',noClaimFullLiquidDock=True,noClaimChooserOrRootButtonE2E=True)
 safe(HERE/'source-contract-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
 print(json.dumps(dict(status='PASS',checks=len(checks))))
if __name__=='__main__':main()
