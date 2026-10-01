from pathlib import Path
import difflib, hashlib, importlib.util, json, re, sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
IDENTITY=REPO/'desktop/.local/stable-dynamic-protocol-rebase/prepared/desktop/tools/extract-upstream-dynamic-reply-protocol.py'
EXT=chr(92)*2+'?'+chr(92)
def safe(p):
 v=str(Path(p).absolute());return Path(v if v.startswith(EXT) else EXT+v)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def dump(p,v):write(p,json.dumps(v,indent=2,ensure_ascii=False)+'\n')
def digest(s):return hashlib.sha256(s.encode()).hexdigest()
def replace(s,a,b):assert s.count(a)==1,a;return s.replace(a,b)
def module(n,p):
 spec=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
producer_path='desktop/tools/extract-upstream-dynamic-editor-protocol.py'
old=read(REPO/producer_path);new=old
new=replace(new,"    create=(repo/(BASE+'data/repository/DynamicCreateRepository.kt')).read_text(encoding='utf-8').replace('\\r\\n','\\n')\n    comment=(repo/(BASE+'data/repository/CommentRepository.kt')).read_text(encoding='utf-8').replace('\\r\\n','\\n')",'''    identity=module(repo,'editor_fixed_source_identity',Path(__file__).with_name('extract-upstream-dynamic-reply-protocol.py'))
    sources,_=identity.load_pinned_sources(repo,[BASE+'data/repository/DynamicCreateRepository.kt',BASE+'data/repository/CommentRepository.kt'])
    create=sources[BASE+'data/repository/DynamicCreateRepository.kt']
    comment=sources[BASE+'data/repository/CommentRepository.kt']''')
new=new.replace('Triple<String?, String?, ByteArray>','Triple<String?, String?, okhttp3.RequestBody>')
start=new.index("    upload=fun(create,'uploadImage')");end=new.index("    reserve=fun(create,'resolveReserveAttachCard')",start)
replacement='''    upload=fun(create,'uploadImage')
    a=upload.index('        val mimeType =');b=upload.rfind('\\n    }')
    body=upload[a:b]
    body=sub(body,'context.contentResolver.getType(uri)','selected.second')
    body=sub(body,'queryDisplayName(context, uri)','selected.first')
    body=sub(body,'CommentRepository.uploadCommentImage(','uploadEditorCommentImageBody(csrf,')
    body=sub(body,'            resolver = context.contentResolver,\\n            uri = uri','            fileBody = selected.third')
    body=sub(body,').getOrElse { throw it }',')')
    members.append('private suspend fun uploadEditorImage(csrf: String, selected: Triple<String?, String?, okhttp3.RequestBody>): DynamicCreatePic {\\n        coroutineContext.ensureActive(); assertOwned()\\n'+body+'\\n}\\n')
    # Stable byte overload is retained for the existing reply API. The editor's
    # known-size selected files take the RequestBody path; one multipart algorithm.
    byte_start,byte_end=identity.fun_span(comment,'uploadCommentImage')
    byte_function=comment[byte_start:byte_end]
    byte_mask=identity.masked(byte_function);byte_open=byte_mask.index('{')
    byte_body=byte_function[byte_open+1:identity.balanced(byte_mask,byte_open,'{','}')-1].strip('\\n')
    byte_body=sub(byte_body,'        uploadCommentImagePart(','        return uploadEditorCommentImageBody(csrf,')
    members.append('private suspend fun uploadEditorCommentImage(csrf: String, fileName: String, mimeType: String, bytes: ByteArray): ReplyPicture {\\n        coroutineContext.ensureActive(); assertOwned()\\n'+byte_body+'\\n}\\n')
    body=block(fun(comment,'uploadCommentImagePart'),'try {')
    a=body.index('            val part =');body=body[a:]
    body=sub(body,'            if (response.code == 0 && response.data != null) {\\n                val data = response.data','            coroutineContext.ensureActive(); assertOwned()\\n            val uploadContext = coroutineContext\\n            if (!withOwnedEditorImageAdmission { uploadContext.ensureActive() }) throw CancellationException("Dynamic upload account owner retired")\\n            val data = response.data\\n            return if (response.code == 0 && data != null) {')
    body=sub(body,'                Result.success(\\n                    ReplyPicture(','                ReplyPicture(')
    body=sub(body,'                    )\\n                )','                    )')
    body=identity.drop_logs(body)
    body=sub(body,'Result.failure(Exception(response.message.ifEmpty { "图片上传失败 (${response.code})" }))','throw Exception(response.message.ifEmpty { "图片上传失败 (${response.code})" })')
    members.append('private suspend fun uploadEditorCommentImageBody(csrf: String, fileName: String, mimeType: String, fileBody: okhttp3.RequestBody): ReplyPicture {\\n        coroutineContext.ensureActive(); assertOwned()\\n'+body+'\\n}\\n')
'''
new=new[:start]+replacement+new[end:]
write(HERE/'prepared'/producer_path,new)
write(HERE/'prepared/desktop/tools/extract-upstream-dynamic-reply-protocol.py',read(IDENTITY))
producer=module('stable_stream_editor_producer',HERE/'prepared'/producer_path)
members=producer.generate_members(REPO);write(HERE/'generated/EditorOperations.fragment.kt',members)
rows=[];patches=[]
def payload(path,base,candidate):
 write(HERE/'original'/path,base);write(HERE/'prepared'/path,candidate)
 rows.append({'path':path,'baseLfSha256':digest(base),'candidateLfSha256':digest(candidate),'changed':base!=candidate})
 if base!=candidate:patches.extend(difflib.unified_diff(base.splitlines(True),candidate.splitlines(True),fromfile='a/'+path,tofile='b/'+path))
payload(producer_path,old,new)
verifier='desktop/tools/verify-upstream-dynamic-editor-protocol.py';payload(verifier,read(REPO/verifier),read(REPO/verifier))
operations='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt'
ops=read(REPO/operations);updated=ops
marker='// GENERATED original editor members; do not hand-maintain a second request algorithm.'
reply_marker='// Paste inside existing DesktopDynamicCardOperations; no package/class/API/model producer.'
start=updated.index(marker);end=updated.index(reply_marker,start)
updated=updated[:start]+members.rstrip('\n')+'\n\n'+updated[end:]
updated=replace(updated,'    private val mutex = Mutex()','''    private val editorImageOwner = repository.dynamicCacheSessionGuard.dynamicCacheOwner()
    internal fun withOwnedEditorImageAdmission(block: () -> Unit): Boolean {
        val owner = editorImageOwner ?: return false
        return repository.dynamicCacheSessionGuard.withCurrentDynamicCacheOwner(owner) {
            assertOwned(); block(); assertOwned()
        }
    }
    private val mutex = Mutex()''')
payload(operations,ops,updated)
selected='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicEditorSelectedImages.kt'
selected_candidate=read(HERE/'DesktopDynamicEditorSelectedImages.kt')
identity=module('prepared_editor_identity',IDENTITY)
sources,ids=identity.load_pinned_sources(REPO,['app/src/main/java/com/android/purebilibili/data/repository/CommentRepository.kt',
 'app/src/main/java/com/android/purebilibili/data/repository/DynamicCreateRepository.kt'])
comment=sources['app/src/main/java/com/android/purebilibili/data/repository/CommentRepository.kt']
assert re.search(r'private const val MAX_COMMENT_IMAGE_BYTES = 15L \* 1024 \* 1024',comment)
assert 'private const val IMAGE_TOO_LARGE_MESSAGE = "图片过大（单张最大 15MB）"' in comment
assert 'Files.readAllBytes' not in selected_candidate and 'readBytes()' not in selected_candidate
payload(selected,read(REPO/selected),selected_candidate)
host='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalDynamicEditorHost.kt'
payload(host,read(REPO/host),read(REPO/host).replace('Triple<String?, String?, ByteArray>','Triple<String?, String?, okhttp3.RequestBody>'))
root='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicEditorRoot.kt'
root_old=read(REPO/root);root_new=replace(root_old,'        val selected = remember(root, request) { DesktopDynamicEditorSelectedImages(owned) }\n','')
anchor='''        val pickers = remember(selected, hostWindow) {'''
root_new=replace(root_new,anchor,'''        val selected = remember(root, request, operations) {
            DesktopDynamicEditorSelectedImages(owned, operations::withOwnedEditorImageAdmission)
        }
'''+anchor)
payload(root,root_old,root_new)
write(HERE/'stream-upload.patch',''.join(patches))
source_map=[]
audited=json.loads(read(REPO/'desktop/.local/stable-dynamic-protocol-rebase/method-token-original-diff.json'))
for path,names in {
 'app/src/main/java/com/android/purebilibili/data/repository/DynamicCreateRepository.kt':['publish','edit','createVote','createReserve','searchPublishTopics','uploadImage','resolveReserveAttachCard'],
 'app/src/main/java/com/android/purebilibili/data/repository/CommentRepository.kt':['searchMentionUsers','uploadCommentImage','uploadCommentImagePart','queryContentImageSize','contentType','contentLength','isOneShot','writeTo'],
}.items():
 for row in audited['methods']:
  if row['path']==path and row['stable'] and row['stable']['name'] in names:source_map.append(row)
dump(HERE/'stable-upload-source-map.json',{'fixedStableCommit':'3d5d19a2f994daccd0e2f8b5f522b6d82f43d589','sourceIdentities':ids,'selectedMethods':source_map,
 'existingKnownSizeValidator':'Exact stable <=0 and >15MiB failures before any file input is opened',
 'platformDifference':'Windows selected Paths always have filesystem size; no Android DocumentsProvider unknown-size interface is exposed',
 'oneShot':'Original ContentUriRequestBody isOneShot=true/contentLength and contentType semantics retained',
 'streamBoundary':'Windows owned 64KiB copy with actual SessionStore -> selected-owner gate and active publishing/editing caller context; close retires registered streams',
 'requestAlgorithm':'One stable uploadCommentImagePart-derived multipart body shared by original byte API and editor streaming provider',
 'MainChanged':False,'sharedGradle':False})
dump(HERE/'candidate-source-inventory.json',{'sourceCandidates':rows,'requiresDetailReplyIdentityProducer':str(IDENTITY),
 'identityProducerLfSha256':digest(read(IDENTITY)),'generatedEditorMembersLfSha256':digest(members),'MainChanged':False,
 'newDependencies':False,'newHttpClient':False,'newStore':False,
 'gradleInputRecommendation':'verifyUpstreamDynamicEditorProtocol inputs must also include extract-upstream-dynamic-reply-protocol.py for its shared fixed-source identity helper'})
print(json.dumps({'candidateFiles':rows,'selectedStableHelpers':len(source_map),'streamProvider':'Triple<fileName,mimeType,RequestBody>','MainChanged':False},indent=2))
