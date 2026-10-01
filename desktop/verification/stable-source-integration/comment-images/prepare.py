"""Prepared stable comment image closure. Writes only this task lane.

Installation is into sibling BiliPai-v023, never the verified alpha.9 Main.
Operations is an additive member patch; its full copied form is proof input only.
"""
from pathlib import Path
import hashlib, importlib.util, json, subprocess, sys, textwrap
sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
MAIN = next(p for p in HERE.parents if (p / '.git').exists())
BASE = MAIN.parent / 'BiliPai-v023'
COMMIT = '3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'

def safe(p):
    s = str(Path(p).absolute())
    return Path(s if s.startswith('\\\\?\\') else '\\\\?\\' + s)
def read(p): return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,s):
    safe(p.parent).mkdir(parents=True,exist_ok=True)
    safe(p).write_text(s,encoding='utf-8',newline='\n')
def save(p,s):write(p,json.dumps(s,ensure_ascii=False,indent=2)+'\n')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def one(s,a,b):
    assert s.count(a)==1,(s.count(a),a[:120])
    return s.replace(a,b,1)
def load(name,p):
    spec=importlib.util.spec_from_file_location(name,p)
    m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m

def main():
    assert subprocess.check_output(['git','rev-parse','v0.2.3'],cwd=BASE,text=True).strip()==COMMIT
    inputs={}
    def source(path):
        s=read(BASE/path)
        inputs[path]={'path':path,'sha256Bytes':sha(BASE/path),'sha256LF':hashlib.sha256(s.encode()).hexdigest()}
        write(HERE/'base-inputs'/path,s)
        return s
    reply='desktop/tools/extract-upstream-dynamic-reply.py'
    detail='desktop/tools/extract-upstream-dynamic-detail.py'
    body=source(reply)
    anchor="    declarations = declarations.replace('val keyboardController = LocalSoftwareKeyboardController.current',"
    replacement='''    # Android Uri is represented by the existing owner-selected file URI on Windows.
    declarations = declarations.replace('List<Uri>', 'List<String>')
    picker_begin = declarations.index('    val picker = rememberLauncherForActivityResult(')
    picker_end = declarations.index('    LaunchedEffect(onClearReplyTarget != null)', picker_begin)
    picker_original = declarations[picker_begin:picker_end]
    assert 'maxItems = 9' in picker_original and '.distinct().take(9)' in picker_original
    declarations = declarations[:picker_begin] + '''+repr('''    val platform = LocalDesktopCommentBindings.current
    val onImagesSelected by rememberUpdatedState<(List<String>) -> Unit> { uris ->
        if (!isSending && onClearReplyTarget == null) {
            selectedImages = (selectedImages + uris).distinct().take(9)
        }
    }
    fun pickImages() {
        if (!platform.isOwned()) return
        platform.pickCommentImages(9) { uris ->
            if (platform.isOwned()) onImagesSelected(uris)
        }
    }
''')+''' + declarations[picker_end:]
    declarations = replace_once(declarations,
        'picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))', 'pickImages()')
'''+anchor
    body=one(body,anchor,replacement)
    body=one(body,'import androidx.compose.foundation.lazy.LazyListScope\n','import androidx.compose.foundation.lazy.LazyListScope\nimport androidx.compose.foundation.lazy.LazyRow\n')
    body=one(body,'import androidx.compose.ui.platform.LocalFocusManager\n','import androidx.compose.ui.platform.LocalFocusManager\nimport androidx.compose.ui.layout.ContentScale\nimport androidx.compose.material.icons.Icons\nimport androidx.compose.material.icons.outlined.Image\nimport coil3.compose.AsyncImage\nimport com.bilipai.desktop.ui.LocalDesktopCommentBindings\n')
    write(HERE/'prepared'/reply,body)
    body=source(detail)
    anchor="    body=body.replace('CommentRepository.','requests.')"
    replacement='''    # Only Android ContentResolver/Uri admission is adapted. The original 9-image
    # validation, reply prohibition, ordered map, pictures payload and callbacks remain.
    upload = textwrap.indent(media.function(original,'uploadCommentPictures',parser).strip(),'    ')
    assert upload in body
    body=body.replace(upload,''' + repr('''    private suspend fun uploadCommentPictures(imageUris: List<String>): List<ReplyPicture> =
        withContext(Dispatchers.IO) {
            require(imageUris.size <= 9) { "最多选择 9 张图片" }
            imageUris.mapIndexed { index, uri ->
                requests.uploadCommentPicture(uri, index).getOrElse { throw it }
            }
        }''') + ''',1)
    body=body.replace('List<Uri>', 'List<String>')
    body=body.replace('                val pictures = uploadCommentPictures(imageUris)\\n',
                      '                val pictures = uploadCommentPictures(imageUris)\\n                ensureRequestOwned()\\n')
'''+anchor
    body=one(body,anchor,replacement)
    write(HERE/'prepared'/detail,body)

    ui='desktop/src/main/kotlin/com/bilipai/desktop/ui/'
    path=ui+'DesktopDynamicReplyRequests.kt';body=source(path)
    body=one(body,'import com.bilipai.desktop.data.DesktopDynamicCardOperations\n','import com.bilipai.desktop.data.DesktopDynamicCardOperations\nimport kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.currentCoroutineContext\nimport kotlinx.coroutines.ensureActive\nimport okhttp3.RequestBody\n')
    body=one(body,'    suspend fun addCommentForSubject(oid: Long, type: Int, message: String, root: Long = 0, parent: Long = 0): Result<ReplyItem?>',
        '    suspend fun uploadCommentPicture(source: String, index: Int): Result<ReplyPicture>\n    suspend fun addCommentForSubject(oid: Long, type: Int, message: String, root: Long = 0, parent: Long = 0, pictures: List<ReplyPicture> = emptyList()): Result<ReplyItem?>')
    body=one(body,'    private val detailLoader: suspend (String) -> Result<DynamicItem>,','    private val detailLoader: suspend (String) -> Result<DynamicItem>,\n    private val imageProvider: suspend (String) -> Triple<String?, String?, RequestBody>,')
    body=one(body,'    override suspend fun addCommentForSubject(oid: Long, type: Int, message: String, root: Long, parent: Long) =\n        operations.addCommentForSubject(oid, type, message, root, parent)', '''    private suspend fun ensureOwned() {
        currentCoroutineContext().ensureActive()
        if (!isOwned()) throw CancellationException("Reply image owner retired")
    }
    override suspend fun uploadCommentPicture(source: String, index: Int): Result<ReplyPicture> {
        ensureOwned()
        return try {
            val selected = imageProvider(source)
            ensureOwned()
            operations.uploadCommentImageBody(
                selected.first ?: "comment_${System.currentTimeMillis()}_${index + 1}.jpg",
                selected.second ?: "image/jpeg", selected.third).also { ensureOwned() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { ensureOwned(); Result.failure(failure) }
    }
    override suspend fun addCommentForSubject(oid: Long, type: Int, message: String, root: Long, parent: Long,
        pictures: List<ReplyPicture>) = operations.addCommentForSubject(oid, type, message, root, parent, pictures)''')
    write(HERE/'prepared'/path,body)
    path=ui+'DesktopCommentPlatform.kt';body=source(path)
    body=one(body,'    fun showFeedback(message: String)','    fun pickCommentImages(maxItems: Int, onSelected: (List<String>) -> Unit)\n    fun showFeedback(message: String)')
    write(HERE/'prepared'/path,body)
    path=ui+'DesktopDynamicCommentPlatform.kt';body=source(path)
    body=one(body,'    private val saveImage: suspend (ReplyCommentImageSpec) -> Boolean,','    private val saveImage: suspend (ReplyCommentImageSpec) -> Boolean,\n    private val pickImages: (Int, (List<String>) -> Unit) -> Unit,')
    body=one(body,'    override fun showFeedback(message: String)', '''    override fun pickCommentImages(maxItems: Int, onSelected: (List<String>) -> Unit) {
        if (!isOwned()) return
        require(maxItems in 1..9) { "最多选择 9 张图片" }
        pickImages(maxItems) { images -> if (isOwned()) onSelected(images) }
    }
    override fun showFeedback(message: String)''')
    write(HERE/'prepared'/path,body)
    path=ui+'CommunityDynamicScreens.kt';body=source(path)
    body=one(body,'    var data by remember(id,capturedEpoch)', '''    val selectedCommentImages=remember(alive){DesktopDynamicEditorSelectedImages(::owned,operations::withOwnedEditorImageAdmission)}
    val saveParent=LocalDesktopDynamicSaveParent.current
    var data by remember(id,capturedEpoch)''')
    body=one(body,'            onArticleViewed=history)})}','            onArticleViewed=history)},imageProvider=selectedCommentImages::read)}')
    body=one(body,'    val clipboard=LocalDesktopTextClipboard.current','''    val commentPickers=remember(alive,saveParent){DesktopDynamicEditorWindowsPickers(selectedCommentImages,::owned,
        {saveParent},onFailure={message->if(owned())pageScope.launch{snackbar.showSnackbar(message)}})}
    val clipboard=LocalDesktopTextClipboard.current''')
    body=one(body,'        })}\n    DisposableEffect(alive){onDispose{synchronized(exportOwnerLock){alive.set(false)};replySession.close();pageScope.cancel()}}',
        '        },pickImages=commentPickers::pickImages)}\n    DisposableEffect(alive){onDispose{synchronized(exportOwnerLock){alive.set(false)};replySession.close();selectedCommentImages.close();pageScope.cancel()}}')
    body=one(body,'        CompositionLocalProvider(LocalDesktopDynamicCardMutations provides detailMutations,','''        key(alive) {
        CompositionLocalProvider(LocalDesktopDynamicCardMutations provides detailMutations,''')
    body=one(body,'        SnackbarHost(snackbar,Modifier.align(Alignment.BottomCenter))','        }\n        SnackbarHost(snackbar,Modifier.align(Alignment.BottomCenter))')
    write(HERE/'prepared'/path,body)
    path='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt';ops=source(path)
    assert hashlib.sha256(ops.encode()).hexdigest()=='833678b38f32928f0cbdc837e7bf380b7208f7ef62575fe654af402e09d0fda2'
    fragment='''
    // Desktop original comment image streaming binding. Reuse the editor's one original upload body.
    suspend fun uploadCommentImageBody(fileName: String, mimeType: String, fileBody: okhttp3.RequestBody): Result<ReplyPicture> =
        result { mutate { csrf -> uploadEditorCommentImageBody(csrf, fileName, mimeType, fileBody) } }
'''
    write(HERE/'operations-member.fragment.kt',fragment)
    anchor='suspend fun uploadCommentImage(fileName:String,mimeType:String,bytes:ByteArray):Result<ReplyPicture> = result { mutate { csrf -> uploadEditorCommentImage(csrf,fileName,mimeType,bytes) } }'
    write(HERE/'proof-only'/path,one(ops,anchor,anchor+'\n'+fragment))
    # Freeze the shared stream/editor inputs actually installed into sibling. They
    # are explicit proof dependencies, never a replacement install payload here.
    for name in ('DesktopDynamicEditorSelectedImages.kt','DesktopDynamicEditorWindowsPickers.kt','DesktopDynamicGallerySelection.kt'):
        source(ui+name)
    save(HERE/'base-source-pins.json',list(inputs.values()))
    # Both source-owned producers replay the same stable original. No second UI.
    for name,path in [('prepared_reply',reply),('prepared_detail',detail)]:
        m=load(name,HERE/'prepared'/path)
        emitted=m.generate(BASE,HERE/'generated'/('reply' if name=='prepared_reply' else 'detail'))
        save(HERE/(name+'-generated.json'),[{'path':str(p),'sha256Bytes':sha(p)} for p in emitted])
    original_paths=['app/src/main/java/com/android/purebilibili/feature/dynamic/components/DynamicCommentSheet.kt',
        'app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicViewModel.kt',
        'app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicDetailScreen.kt',
        'app/src/main/java/com/android/purebilibili/data/repository/CommentRepository.kt']
    originals=[]
    for p in original_paths:
        blob=subprocess.check_output(['git','show',COMMIT+':'+p],cwd=BASE).decode().replace('\r\n','\n')
        assert blob==read(BASE/p),p
        write(HERE/'original-source'/p,blob)
        originals.append({'path':p,'commit':COMMIT,'sha256LF':hashlib.sha256(blob.encode()).hexdigest()})
    save(HERE/'original-source-pins.json',originals)
    print(json.dumps({'status':'prepared','baseSources':len(inputs),'lane':str(HERE)}))

if __name__=='__main__':main()
