from pathlib import Path
import hashlib,json,re,subprocess,zipfile
HERE=Path(__file__).resolve().parent
ROOT=next(p for p in HERE.parents if (p/'app/src/main').is_dir())
TAG='v0.2.3-alpha.9'
PIN=ROOT/'desktop/.local/native-share-main-product-snapshot-01'
PIN_MANIFEST_SHA='4e97c4c054a7ec0ed8f064319009bcfb9b68f9c191ef089a6bf677f163df959f'
PIN_CP_SHA='a733a3435e18c5dac83ff155023c36e6d0d1997822b0a6b77ba7d4efeff63fcc'
PIN_KOTLIN_SHA='a988ad58a727676e89b1d1dde3cb24c11f16e38988c4dec84ef330c0018c5b87'
def safe(path):
    value=str(path.absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def read(path):return safe(path).read_text(encoding='utf-8').replace('\r\n','\n').replace('\r','\n')
def sha(path):return hashlib.sha256(safe(path).read_bytes()).hexdigest()
def digest(text):return hashlib.sha256(text.encode('utf-8')).hexdigest()
def write(path,text):
    assert path==HERE or HERE in path.parents
    safe(path.parent).mkdir(parents=True,exist_ok=True);safe(path).write_text(text,encoding='utf-8',newline='\n')
def masked(text):
    # Keep offsets/newlines while masking comments and quoted Kotlin tokens.
    out=list(text);i=0;n=len(text)
    while i<n:
        if text.startswith('//',i):
            end=text.find('\n',i);end=n if end<0 else end
        elif text.startswith('/*',i):
            end=i+2;depth=1
            while end<n and depth:
                if text.startswith('/*',end):depth+=1;end+=2
                elif text.startswith('*/',end):depth-=1;end+=2
                else:end+=1
        elif text.startswith('"""',i):
            end=text.find('"""',i+3);assert end>=0;end+=3
        elif text[i] in ('"',"'"):
            quote=text[i];end=i+1
            while end<n:
                if text[end]=='\\':end+=2
                elif text[end]==quote:end+=1;break
                else:end+=1
        else:i+=1;continue
        for j in range(i,end):
            if out[j]!='\n':out[j]=' '
        i=end
    return ''.join(out)
def balanced(mask,start,left='(',right=')'):
    assert mask[start]==left
    depth=0
    for i in range(start,len(mask)):
        if mask[i]==left:depth+=1
        elif mask[i]==right:
            depth-=1
            if not depth:return i+1
    raise ValueError('Unclosed token span')
def fun_span(text,name):
    mask=masked(text)
    match=re.search(r'(?m)^    (?:(?:private|internal|suspend|inline)\s+)*fun\s+'+re.escape(name)+r'\s*\(',mask)
    assert match,name
    param=mask.index('(',match.start());param_end=balanced(mask,param)
    body=mask.index('{',param_end);end=balanced(mask,body,'{','}')
    return match.start(),end
records=[]
def extract(text,name,source_path):
    start,end=fun_span(text,name)
    original=text[start:end]
    records.append(dict(source=source_path,name=name,startLine=text[:start].count('\n')+1,endLine=text[:end].count('\n')+1,sha256LfUtf8=digest(original)))
    return original
def drop_logs(text):
    while True:
        mask=masked(text);m=re.search(r'(?:Logger\.[dwei]|android\.util\.Log\.e)\s*\(',mask)
        if not m:return text
        start=text.rfind('\n',0,m.start())+1
        assert not text[start:m.start()].strip()
        end=balanced(mask,mask.index('(',m.start()))
        text=text[:start]+text[end:]
def result_values(text):
    for name in ('success','failure'):
        while True:
            mask=masked(text);m=re.search(r'Result\.'+name+r'\(',mask)
            if not m:break
            open_at=mask.index('(',m.start());end=balanced(mask,open_at)
            argument=text[open_at+1:end-1]
            replacement=argument if name=='success' else 'throw '+argument
            text=text[:m.start()]+replacement+text[end:]
    return text
def owned_api_calls(text):
    # Await guards wrap only original Retrofit calls; argument tokens are unchanged.
    pattern=re.compile(r'(?:apiClient|guestApi|api|resolveReadApi\([^)]*\))\.(?:getReplyList(?:Main|Legacy)?|getReplyReply|getReplyCount)\s*\(')
    matches=list(pattern.finditer(masked(text)))
    for m in reversed(matches):
        mask=masked(text);open_at=mask.index('(',m.end()-1);end=balanced(mask,open_at)
        text=text[:m.start()]+'ownedCall { '+text[m.start():end]+' }'+text[end:]
    return text
def adapt_read(text):
    text=drop_logs(text).replace('CommentGrpcRepository.MODE_','DesktopDynamicCommentGrpc.MODE_').replace('CommentGrpcRepository.','commentGrpc.')
    text=text.replace('VideoRepository.ensureBuvid3()','currentCoroutineContext().ensureActive(); assertOwner()')
    text=text.replace('!com.android.purebilibili.core.store.TokenManager.sessDataCache.isNullOrEmpty()','hasSession()')
    text=text.replace('runCatching','ownedCatching')
    replacement='} catch (e: Exception) {\n            currentCoroutineContext().ensureActive(); assertOwner()'
    if 'catch (e: CancellationException)' not in text:
        replacement='} catch (cancelled: CancellationException) {\n            throw cancelled\n        '+replacement
    text=text.replace('} catch (e: Exception) {',replacement)
    if 'val wbiKeys = getWbiKeysOrNull(apiClient)' in text:
        text=text.replace('val wbiKeys = getWbiKeysOrNull(apiClient)','val signedParams = ownedCatching { ownedCall { signParams(params) } }.getOrNull()')
        text=text.replace('if (wbiKeys != null)','if (signedParams != null)')
        text=text.replace('                    val (imgKey, subKey) = wbiKeys\n                    val signedParams = WbiUtils.sign(params, imgKey, subKey)\n','')
    return owned_api_calls(text)
A='app/src/main/java/com/android/purebilibili/'
paths=[A+'data/repository/'+name+'.kt' for name in ('CommentRepository','CommentReadAccessPolicy','CommentGrpcRepository')]
paths += [A+'core/network/ApiClient.kt',A+'data/model/response/ResponseModels.kt']
sources={}
source_ids=[]
for relative in paths:
    content=read(ROOT/relative);sources[relative]=content
    blob=subprocess.check_output(['git','rev-parse',TAG+':'+relative],cwd=ROOT,text=True).strip()
    current=subprocess.check_output(['git','hash-object','--path='+relative,relative],cwd=ROOT,text=True).strip()
    assert blob==current,relative
    source_ids.append(dict(path=relative,tagBlob=blob,currentGitBlob=current,sha256LfUtf8=digest(content),matchesTag=True))
assert sha(PIN/'manifest.json')==PIN_MANIFEST_SHA
assert sha(PIN/'ordered-runtime-cp.json')==PIN_CP_SHA
assert sha(PIN/'main-kotlin.jar')==PIN_KOTLIN_SHA
assert len(json.loads(read(PIN/'ordered-runtime-cp.json')))==89
repo_path=paths[0];repo=sources[repo_path]
read_names=['resolveReadApi','fetchNonWbiCommentFallback','fetchCommentsByApi','fetchGuestHotCommentsCompat','fetchLegacyHotCommentsCompat','fetchCommentEmptySuccessFallback','getCommentsForSubject','getCommentCountForSubject','getSortedSubCommentsForSubject','getSubCommentsForSubject','getDialogCommentsForSubject','shouldTryGrpcMainList','resolveCommentMainListPaginationParameters','resolveCommentMainListMode','shouldTryGrpcPagedRequest']
selected='\n\n'.join(adapt_read(extract(repo,name,repo_path)) for name in read_names)
header='// GENERATED by protocol/produce.py; task-only prepared source.\n// Original: '+repo_path+'\n// Original LF SHA-256: '+digest(repo)+'\n'
protocol=header+'''package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.TreeMap
internal class DesktopDynamicCommentProtocol(
    private val api: BilibiliApi,
    private val guestApi: BilibiliApi,
    private val commentGrpc: DesktopDynamicCommentGrpc,
    private val hasSession: () -> Boolean,
    private val signParams: suspend (Map<String,String>) -> Map<String,String>,
    private val assertOwner: () -> Unit,
) {
    private val commentJson = Json { ignoreUnknownKeys = true }
    private suspend inline fun <T> ownedCall(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive(); assertOwner()
        return block().also { currentCoroutineContext().ensureActive(); assertOwner() }
    }
    private inline fun <T> ownedCatching(block: () -> T): Result<T> = try {
        assertOwner(); Result.success(block().also { assertOwner() })
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (failure: Exception) { assertOwner(); Result.failure(failure) }
'''+selected+'\n}\n'
write(HERE/'generated/com/android/purebilibili/data/repository/DesktopDynamicCommentProtocol.kt',protocol)
policy_path=paths[1];policy=sources[policy_path]
write(HERE/'generated/com/android/purebilibili/data/repository/DesktopOriginalCommentReadAccessPolicy.kt','// GENERATED original full read policy; sole producer.\n// Original: '+policy_path+'\n// Original LF SHA-256: '+digest(policy)+'\n'+policy)
grpc_path=paths[2];original_grpc=sources[grpc_path]
grpc=original_grpc.replace('import com.android.purebilibili.core.network.grpc.BiliGrpcClient\n','')
grpc=grpc.replace('internal object CommentGrpcRepository {','''internal class DesktopDynamicCommentGrpc(
    private val requestTransport: suspend (String, ByteArray) -> ByteArray,
    private val assertOwner: () -> Unit,
) {
    private suspend fun request(path: String, message: ByteArray): ByteArray {
        kotlinx.coroutines.currentCoroutineContext().ensureActive(); assertOwner()
        return requestTransport(path, message).also { kotlinx.coroutines.currentCoroutineContext().ensureActive(); assertOwner() }
    }
    private inline fun <T> ownedCatching(block: () -> T): Result<T> = try {
        assertOwner(); Result.success(block().also { assertOwner() })
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
    } catch (failure: Exception) { assertOwner(); Result.failure(failure) }
''')
grpc=grpc.replace('private const val PATH_','private val PATH_')
grpc=grpc.replace('    internal const val MODE_TIME = 2\n    internal const val MODE_HOT = 3','    internal companion object {\n        internal const val MODE_TIME = 2\n        internal const val MODE_HOT = 3\n    }')
grpc=grpc.replace('BiliGrpcClient.request(','request(').replace('return runCatching {','return ownedCatching {').replace('        runCatching {','        ownedCatching {')
for name in ('getMainList','getDetailList','getDialogList'):
    grpc=grpc.replace('    fun '+name+'(','    suspend fun '+name+'(')
grpc=grpc.replace('import kotlinx.coroutines.Dispatchers\n','import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.ensureActive\n')
write(HERE/'generated/com/android/purebilibili/data/repository/DesktopDynamicCommentGrpc.kt','// GENERATED original full builders/parser with owned transport only.\n// Original: '+grpc_path+'\n// Original LF SHA-256: '+digest(original_grpc)+'\n'+grpc)

# Existing Operations members only; never a copied/replacement Operations source.
fragment='''// Paste inside existing DesktopDynamicCardOperations; no package/class/API/model producer.
// Requires the frozen editor's existing private uploadEditorCommentImage (one upload body).
private val guestCommentApi = guestWeb.create(BilibiliApi::class.java)
private val commentGrpc = com.android.purebilibili.data.repository.DesktopDynamicCommentGrpc(grpc::request, ::assertOwned)
private val commentProtocol = com.android.purebilibili.data.repository.DesktopDynamicCommentProtocol(
    api, guestCommentApi, commentGrpc,
    { assertOwned(); !repository.authCookies()["SESSDATA"].isNullOrEmpty() },
    { params -> assertOwned(); repository.signWebParams(params).also { assertOwned() } },
    ::assertOwned,
)
'''
read_signatures={
'getCommentsForSubject':('oid:Long,type:Int,page:Int,ps:Int=20,mode:Int=3,paginationOffset:String?=null,fallbackOnMissingLocation:Boolean=false','oid,type,page,ps,mode,paginationOffset,fallbackOnMissingLocation','ReplyData'),
'getCommentCountForSubject':('oid:Long,type:Int','oid,type','Int'),
'getSortedSubCommentsForSubject':('oid:Long,type:Int,rootId:Long,mode:Int,paginationOffset:String?=null,targetReplyId:Long=0L','oid,type,rootId,mode,paginationOffset,targetReplyId','ReplyData'),
'getSubCommentsForSubject':('oid:Long,type:Int,rootId:Long,page:Int,ps:Int=20,paginationOffset:String?=null,preferRestPaging:Boolean=true','oid,type,rootId,page,ps,paginationOffset,preferRestPaging','ReplyData'),
'getDialogCommentsForSubject':('oid:Long,type:Int,rootId:Long,dialogId:Long,page:Int,paginationOffset:String?=null','oid,type,rootId,dialogId,page,paginationOffset','ReplyData'),
}
for name,(params,args,ret) in read_signatures.items():
    fragment+='\nsuspend fun '+name+'('+params+'):Result<'+ret+'> = result { read { commentProtocol.'+name+'('+args+').getOrThrow() } }\n'
fragment+='\nsuspend fun translateReply(type:Long,oid:Long,rpid:Long):Result<String?> = result { read { commentGrpc.translateReply(type,oid,rpid).getOrThrow() } }\n'
fragment+='\nsuspend fun uploadCommentImage(fileName:String,mimeType:String,bytes:ByteArray):Result<ReplyPicture> = result { mutate { csrf -> uploadEditorCommentImage(csrf,fileName,mimeType,bytes) } }\n'
for name in ('addCommentForSubject','likeCommentForSubject','hateCommentForSubject','deleteCommentForSubject','setCommentTopForSubject','reportCommentForSubject'):
    original=extract(repo,name,repo_path);mask=masked(original);param=mask.index('(');param_end=balanced(mask,param)
    signature=original[:param_end]
    if name=='addCommentForSubject':
        signature=signature.replace('syncToDynamic: Boolean = false','syncToDynamic: Boolean = false,\n        onPublishedRecord: ((ReplyItem, Long, Int, Long, Long, String, Long) -> Unit)? = null')
    try_at=mask.index('try');body_start=mask.index('{',try_at);body_end=balanced(mask,body_start,'{','}')
    body=original[body_start+1:body_end-1]
    start=body.index('val picturePayload') if name=='addCommentForSubject' else body.index('val response')
    body=body[start:]
    if name=='addCommentForSubject':
        m=re.search(r'AppScope\.ioScope\.launch\s*\{',masked(body));launch_start=m.start();open_at=masked(body).index('{',launch_start);launch_end=balanced(masked(body),open_at,'{','}')
        body=body[:launch_start]+'''coroutineContext.ensureActive(); assertOwned()
                    onPublishedRecord?.invoke(reply, oid, type, root, parent, message, serverPostTime)
                    coroutineContext.ensureActive(); assertOwned()'''+body[launch_end:]
        body=body.replace('val userUid = reply.mid','').replace('buildPicturesPayload(','buildCommentPicturesPayload(').replace('resolveSyncToDynamicField(','resolveCommentSyncToDynamicField(')
        body=body.replace('// [纯异步旁路] 在后台全局协程中静默存库，完全不卡主流程，零延迟返回','// Owned optional original-record seam; null is explicitly unbound.')
    body=body.replace('resolveReplyTopActionField(','resolveCommentTopActionField(')
    body=drop_logs(body)
    # The existing guarded api/read/mutate supplies transport checks; make post-await explicit.
    response_at=masked(body).index('val response = api.');response_open=masked(body).index('(',response_at);response_end=balanced(masked(body),response_open)
    body=body[:response_end]+'\n            coroutineContext.ensureActive(); assertOwned()\n'+body[response_end:]
    body=result_values(body)
    ret='ReplyItem?' if name=='addCommentForSubject' else 'Unit'
    fragment+='\n'+signature+': Result<'+ret+'> = result { mutate { csrf ->\n'+body+'\n} }\n'
fragment+='''\nprivate fun buildCommentPicturesPayload(pictures:List<ReplyPicture>):String? {
    if (pictures.isEmpty()) return null
    // Original ReplyPicture already has exactly the four original CommentPicturePayload fields.
    // Emit defaults to match the original private payload's four required fields; no new DTO.
    return kotlinx.serialization.json.Json(json) { encodeDefaults = true }.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(ReplyPicture.serializer()), pictures)
}
'''
for old,new in [('resolveSyncToDynamicField','resolveCommentSyncToDynamicField'),('resolveReplyTopActionField','resolveCommentTopActionField')]:
    block=extract(repo,old,repo_path).replace('internal fun '+old,'private fun '+new)
    fragment+='\n'+block+'\n'
write(HERE/'DesktopDynamicCommentOperations.fragment.kt',fragment)
with zipfile.ZipFile(PIN/'main-kotlin.jar') as z:
    names=set(z.namelist())
    produced=['com.android.purebilibili.data.repository.'+name for name in ('DesktopDynamicCommentProtocol','DesktopDynamicCommentGrpc','CommentReadApiMode','CommentReadPlan','DesktopOriginalCommentReadAccessPolicyKt')]
    assert all(fqn.replace('.','/')+'.class' not in names for fqn in produced)
    imports=re.findall(r'^import (com\.android\.purebilibili\.data\.model\.response\.\w+)$',original_grpc,re.M)
    assert all(fqn.replace('.','/')+'.class' in names for fqn in imports),imports
    reusable=[dict(fqn=fqn,classEntry=fqn.replace('.','/')+'.class',present=True) for fqn in imports]
manifest=dict(formatVersion=1,scope='Prepared comment protocol member fragment and owned transport helpers; no Main integration or execution claim',
    originalTag=TAG,originalCommit=subprocess.check_output(['git','rev-parse',TAG],cwd=ROOT,text=True).strip(),
    snapshotManifestSha256Bytes=PIN_MANIFEST_SHA,orderedRuntimeCpSha256Bytes=PIN_CP_SHA,orderedRuntimeCpCount=89,mainKotlinSha256Bytes=PIN_KOTLIN_SHA,
    sources=source_ids,tokenExtractedFunctions=records,newSoleProducers=produced,reusedModelFQNs=reusable,
    adaptations=['Existing owner/API/guest/WBI signer/visitor/grpc; no new account or WBI cache','Cancellation rethrown and post-await owner/job checked',
        'Diagnostic Android logging omitted','Original addComment background fraud-store side effect becomes optional owned onPublishedRecord seam; null is explicitly unbound',
        'Existing editor uploadEditorCommentImage is sole multipart body','Original ReplyPicture serializer with encodeDefaults emits original four picture keys without private parallel DTO'],
    produced=[dict(path=p.relative_to(HERE).as_posix(),sha256Bytes=sha(p)) for p in sorted(HERE.rglob('*.kt')) if 'generated' in p.parts or p.name.endswith('.fragment.kt')])
write(HERE/'source-inventory.json',json.dumps(manifest,indent=2,ensure_ascii=False)+'\n')
print(json.dumps(dict(prepared=True,helpers=3,fragment=True,sourceFiles=5,tokenFunctions=len(records),snapshot='4e97',executionClaim=False),indent=2))

