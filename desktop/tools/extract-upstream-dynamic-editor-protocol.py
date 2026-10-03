"""Members for the existing CardOperations, selected from original source.

No account/API/model producer. Only Android image IO, singleton API/credentials,
WBI key acquisition and coroutine/epoch ownership have Windows bindings.
"""
from pathlib import Path
import hashlib,importlib.util,sys
sys.dont_write_bytecode=True
BASE='app/src/main/java/com/android/purebilibili/'
def module(repo,name,p):
    spec=importlib.util.spec_from_file_location(name,repo/p);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def generate_members(repo):
    host=module(repo,'editor_protocol_host','desktop/tools/extract-upstream-plugins.py')
    media=host.media_extractor(repo);parser=media.parser_for(repo)
    identity=module(repo,'editor_fixed_source_identity',Path(__file__).with_name('extract-upstream-dynamic-reply-protocol.py'))
    sources,_=identity.load_pinned_sources(repo,[BASE+'data/repository/DynamicCreateRepository.kt',BASE+'data/repository/CommentRepository.kt'])
    create=sources[BASE+'data/repository/DynamicCreateRepository.kt']
    comment=sources[BASE+'data/repository/CommentRepository.kt']
    def fun(s,n):return '\n'.join('    '+line for line in media.function(s,n,parser).splitlines())
    def sub(s,a,b):return host.substitute(s,a,b)
    def block(s,marker):
        tokens=parser.kotlin_tokens(s);offset=s.index(marker);begin=next(i for i,t in enumerate(tokens)if t[0]=='{'and t[1]>=offset)
        depth=1;end=begin
        while depth:
            end+=1;depth+=(tokens[end][0]=='{')-(tokens[end][0]=='}')
        return s[tokens[begin][2]:tokens[end][1]].strip('\n')
    def expression(s,marker):
        tokens=parser.kotlin_tokens(s);offset=s.index(marker);i=next(i for i,t in enumerate(tokens)if t[0]=='('and t[1]>=offset)
        depth=1;end=i
        while depth:
            end+=1;depth+=(tokens[end][0]=='(')-(tokens[end][0]==')')
        return s[offset:tokens[end][2]]
    def credentials(s):
        s=sub(s,'            val csrf = TokenManager.csrfCache.orEmpty()\n            if (csrf.isBlank()) error("请先登录")\n','')
        return s.replace('TokenManager.midCache ?: 0L','repository.requireAccount().mid').replace('NetworkModule.dynamicApi.','dynamic.').replace('resolveReserveAttachCard(','resolveEditorReserveAttachCard(')
    def wrap(signature,body,mutate=True):
        return signature+' = result {\n    '+('mutate { csrf ->'if mutate else'read {')+'\n'+body+'\n    }\n}\n'
    members=[]
    for name in ['publish','edit','createVote','createReserve']:
        body=credentials(block(fun(create,name),'runCatching {'))
        body=body.replace('uploadImage(context, Uri.parse(uriString))','uploadEditorImage(csrf, imageProvider(uriString))')
        body=body.replace('            val response = dynamic.','            coroutineContext.ensureActive(); assertOwned()\n            val response = dynamic.')
        if name=='publish':sig='suspend fun publishDynamic(draft: DynamicPublishDraft, imageProvider: suspend (String) -> Triple<String?, String?, okhttp3.RequestBody>): Result<String>'
        elif name=='edit':
            a=body.index('            val wbiKeys = ');b=body.index('            coroutineContext.ensureActive(); assertOwned()',a)
            params=expression(body[a:b],'mapOf(')
            body=body[:a]+'            coroutineContext.ensureActive(); assertOwned()\n            val query = repository.signWebParams('+params+')\n'+body[b:]
            sig='suspend fun editDynamic(dynamicId: String, draft: DynamicPublishDraft, imageProvider: suspend (String) -> Triple<String?, String?, okhttp3.RequestBody>): Result<Unit>'
        elif name=='createVote':
            body=sub(body,'durationSeconds.coerceAtLeast(60)','(durationDays * 24 * 60 * 60).coerceAtLeast(60)')
            sig='suspend fun createVote(title: String, options: List<String>, description: String, choiceCount: Int, durationDays: Int): Result<DynamicCreatedVote>'
        else:sig='suspend fun createReserve(title: String, livePlanStartTimeSeconds: Long, subType: Int): Result<DynamicCreatedReserve>'
        members.append(wrap(sig,body))
    body=block(fun(create,'searchPublishTopics'),'runCatching {').replace('NetworkModule.api.','api.')
    members.append(wrap('suspend fun searchPublishTopics(keyword: String): Result<List<DynamicTopicSearchItem>>',body,False))
    body=block(fun(comment,'searchMentionUsers'),'try {')
    body=sub(body,'Result.success(users)','users');body=sub(body,'Result.failure(Exception(errorMsg))','throw Exception(errorMsg)')
    members.append(wrap('suspend fun searchMentionUsers(keyword: String): Result<List<MentionSearchUser>>',body,False))
    upload=fun(create,'uploadImage')
    a=upload.index('        val mimeType =');b=upload.rfind('\n    }')
    body=upload[a:b]
    body=sub(body,'context.contentResolver.getType(uri)','selected.second')
    body=sub(body,'queryDisplayName(context, uri)','selected.first')
    body=sub(body,'CommentRepository.uploadCommentImage(','uploadEditorCommentImageBody(csrf,')
    body=sub(body,'            resolver = context.contentResolver,\n            uri = uri','            fileBody = selected.third')
    body=sub(body,').getOrElse { throw it }',')')
    members.append('private suspend fun uploadEditorImage(csrf: String, selected: Triple<String?, String?, okhttp3.RequestBody>): DynamicCreatePic {\n        coroutineContext.ensureActive(); assertOwned()\n'+body+'\n}\n')
    # Stable byte overload is retained for the existing reply API. The editor's
    # known-size selected files take the RequestBody path; one multipart algorithm.
    byte_start,byte_end=identity.fun_span(comment,'uploadCommentImage')
    byte_function=comment[byte_start:byte_end]
    byte_mask=identity.masked(byte_function);byte_open=byte_mask.index('{')
    byte_body=byte_function[byte_open+1:identity.balanced(byte_mask,byte_open,'{','}')-1].strip('\n')
    byte_body=sub(byte_body,'        uploadCommentImagePart(','        return uploadEditorCommentImageBody(csrf,')
    members.append('private suspend fun uploadEditorCommentImage(csrf: String, fileName: String, mimeType: String, bytes: ByteArray): ReplyPicture {\n        coroutineContext.ensureActive(); assertOwned()\n'+byte_body+'\n}\n')
    body=block(fun(comment,'uploadCommentImagePart'),'try {')
    a=body.index('            val part =');body=body[a:]
    body=sub(body,'            if (response.code == 0 && response.data != null) {','            coroutineContext.ensureActive(); assertOwned()\n            val uploadContext = coroutineContext\n            if (!withOwnedEditorImageAdmission { uploadContext.ensureActive() }) throw CancellationException("Dynamic upload account owner retired")\n            return if (response.code == 0 && response.data != null) {')
    body=sub(body,'                Result.success(\n                    ReplyPicture(','                ReplyPicture(')
    body=sub(body,'                    )\n                )','                    )')
    body=identity.drop_logs(body)
    body=sub(body,'Result.failure(Exception(response.message.ifEmpty { "图片上传失败 (${response.code})" }))','throw Exception(response.message.ifEmpty { "图片上传失败 (${response.code})" })')
    members.append('private suspend fun uploadEditorCommentImageBody(csrf: String, fileName: String, mimeType: String, fileBody: okhttp3.RequestBody): ReplyPicture {\n        coroutineContext.ensureActive(); assertOwned()\n'+body+'\n}\n')
    reserve=fun(create,'resolveReserveAttachCard').replace('resolveReserveAttachCard','resolveEditorReserveAttachCard')
    members.append(reserve)
    header='// GENERATED original editor members; do not hand-maintain a second request algorithm.\n'
    for path,source in [(BASE+'data/repository/DynamicCreateRepository.kt',create),(BASE+'data/repository/CommentRepository.kt',comment)]:
        header+='// ORIGINAL '+path+'\n// LF-normalized SHA-256: '+hashlib.sha256(source.encode()).hexdigest()+'\n'
    return header+'\n'+'\n'.join(members)

if __name__=='__main__':
    import argparse
    ap=argparse.ArgumentParser();ap.add_argument('--repo',type=Path,required=True);ap.add_argument('--output',type=Path,required=True);args=ap.parse_args()
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(generate_members(args.repo),encoding='utf-8',newline='\n')
