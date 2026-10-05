"""Original selected protocol producer; no snapshot or .local dependency."""
from v025_source_paths import canonical_source as _desktop_canonical_source, canonical_relative as _desktop_canonical_relative
from pathlib import Path
import v029_comment_search as comment_search
import hashlib, re, subprocess, json
def load_pinned_sources(repo: Path, paths):
    """One fixed manifest commit plus Git blobs; never silently accept a local edit."""
    def normalized(path):
        value = str(path.absolute())
        safe = Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value)
        return safe.read_text(encoding='utf-8').replace('\r\n', '\n').replace('\r', '\n')
    manifest = json.loads(normalized(repo / 'desktop/upstream-sources.json'))
    commit = manifest['upstreamCommit']
    assert re.fullmatch(r'[0-9a-f]{40}', commit), 'upstreamCommit must be a full fixed commit'
    assert manifest['hashNormalization'] == 'lf'
    assert manifest['upstreamRepository'] == 'jay3-yy/BiliPai'
    pins = {}
    for row in manifest['sources']:
        assert row['path'] not in pins, 'duplicate source identity'
        pins[row['path']] = row['sha256']
    sources = {}; identities = []
    for path in paths:
        canonical_path = _desktop_canonical_relative(repo, path)
        selected = _desktop_canonical_source(repo, path)
        assert not selected.is_symlink() and selected.resolve().is_relative_to(repo.resolve()), path
        text = normalized(selected)
        actual_sha = hashlib.sha256(text.encode('utf-8')).hexdigest()
        assert actual_sha == pins[canonical_path], path + ' differs from fixed source manifest'
        blob = subprocess.check_output(['git', '-c', 'core.longpaths=true', 'rev-parse', commit + ':' + canonical_path], cwd=repo, text=True).strip()
        current = subprocess.check_output(['git', '-c', 'core.longpaths=true', 'hash-object', '--path=' + canonical_path, canonical_path], cwd=repo, text=True).strip()
        assert blob == current, path + ' differs from fixed Git commit blob'
        sources[path] = text
        identities.append(dict(path=canonical_path, requestedPath=path, pinnedCommit=commit, pinnedTag=manifest['upstreamTag'],
            pinnedGitBlob=blob, currentGitBlob=current, sha256LfUtf8=actual_sha, matchesPinnedCommit=True))
    return sources, identities


def masked(text):
    out = list(text)
    i = 0
    n = len(text)
    while i < n:
        if text.startswith('//', i):
            end = text.find('\n', i)
            end = n if end < 0 else end
        elif text.startswith('/*', i):
            end = i + 2
            depth = 1
            while end < n and depth:
                if text.startswith('/*', end):
                    depth += 1
                    end += 2
                elif text.startswith('*/', end):
                    depth -= 1
                    end += 2
                else:
                    end += 1
        elif text.startswith('"""', i):
            end = text.find('"""', i + 3)
            assert end >= 0
            end += 3
        elif text[i] in ('"', "'"):
            quote = text[i]
            end = i + 1
            while end < n:
                if text[end] == '\\':
                    end += 2
                elif text[end] == quote:
                    end += 1
                    break
                else:
                    end += 1
        else:
            i += 1
            continue
        for j in range(i, end):
            if out[j] != '\n':
                out[j] = ' '
        i = end
    return ''.join(out)

def balanced(mask, start, left='(', right=')'):
    assert mask[start] == left
    depth = 0
    for i in range(start, len(mask)):
        if mask[i] == left:
            depth += 1
        elif mask[i] == right:
            depth -= 1
            if not depth:
                return i + 1
    raise ValueError('Unclosed token span')

def fun_span(text, name):
    mask = masked(text)
    match = re.search('(?m)^    (?:(?:private|internal|suspend|inline)\\s+)*fun\\s+' + re.escape(name) + '\\s*\\(', mask)
    assert match, name
    param = mask.index('(', match.start())
    param_end = balanced(mask, param)
    body = mask.index('{', param_end)
    end = balanced(mask, body, '{', '}')
    return (match.start(), end)

def drop_logs(text):
    while True:
        mask = masked(text)
        m = re.search('(?:Logger\\.[dwei]|android\\.util\\.Log\\.e)\\s*\\(', mask)
        if not m:
            return text
        start = text.rfind('\n', 0, m.start()) + 1
        assert not text[start:m.start()].strip()
        end = balanced(mask, mask.index('(', m.start()))
        text = text[:start] + text[end:]

def generate(repo: Path, output: Path):
    ROOT = repo
    HERE = output

    def safe(path):
        value = str(path.absolute())
        return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value)

    def read(path):
        return safe(path).read_text(encoding='utf-8').replace('\r\n', '\n').replace('\r', '\n')

    def sha(path):
        return hashlib.sha256(safe(path).read_bytes()).hexdigest()

    def digest(text):
        return hashlib.sha256(text.encode('utf-8')).hexdigest()

    def write(path, text):
        assert path == HERE or HERE in path.parents
        safe(path.parent).mkdir(parents=True, exist_ok=True)
        safe(path).write_text(text, encoding='utf-8', newline='\n')

    def masked(text):
        out = list(text)
        i = 0
        n = len(text)
        while i < n:
            if text.startswith('//', i):
                end = text.find('\n', i)
                end = n if end < 0 else end
            elif text.startswith('/*', i):
                end = i + 2
                depth = 1
                while end < n and depth:
                    if text.startswith('/*', end):
                        depth += 1
                        end += 2
                    elif text.startswith('*/', end):
                        depth -= 1
                        end += 2
                    else:
                        end += 1
            elif text.startswith('"""', i):
                end = text.find('"""', i + 3)
                assert end >= 0
                end += 3
            elif text[i] in ('"', "'"):
                quote = text[i]
                end = i + 1
                while end < n:
                    if text[end] == '\\':
                        end += 2
                    elif text[end] == quote:
                        end += 1
                        break
                    else:
                        end += 1
            else:
                i += 1
                continue
            for j in range(i, end):
                if out[j] != '\n':
                    out[j] = ' '
            i = end
        return ''.join(out)

    def balanced(mask, start, left='(', right=')'):
        assert mask[start] == left
        depth = 0
        for i in range(start, len(mask)):
            if mask[i] == left:
                depth += 1
            elif mask[i] == right:
                depth -= 1
                if not depth:
                    return i + 1
        raise ValueError('Unclosed token span')

    def fun_span(text, name):
        mask = masked(text)
        match = re.search('(?m)^    (?:(?:private|internal|suspend|inline)\\s+)*fun\\s+' + re.escape(name) + '\\s*\\(', mask)
        assert match, name
        param = mask.index('(', match.start())
        param_end = balanced(mask, param)
        body = mask.index('{', param_end)
        end = balanced(mask, body, '{', '}')
        return (match.start(), end)
    records = []

    def extract(text, name, source_path):
        start, end = fun_span(text, name)
        original = text[start:end]
        records.append(dict(source=source_path, name=name, startLine=text[:start].count('\n') + 1, endLine=text[:end].count('\n') + 1, sha256LfUtf8=digest(original)))
        return original

    def drop_logs(text):
        while True:
            mask = masked(text)
            m = re.search('(?:Logger\\.[dwei]|android\\.util\\.Log\\.e)\\s*\\(', mask)
            if not m:
                return text
            start = text.rfind('\n', 0, m.start()) + 1
            assert not text[start:m.start()].strip()
            end = balanced(mask, mask.index('(', m.start()))
            text = text[:start] + text[end:]

    def result_values(text):
        for name in ('success', 'failure'):
            while True:
                mask = masked(text)
                m = re.search('Result\\.' + name + '\\(', mask)
                if not m:
                    break
                open_at = mask.index('(', m.start())
                end = balanced(mask, open_at)
                argument = text[open_at + 1:end - 1]
                replacement = argument if name == 'success' else 'throw ' + argument
                text = text[:m.start()] + replacement + text[end:]
        return text

    def owned_api_calls(text):
        pattern = re.compile('(?:apiClient|guestApi|api|resolveReadApi\\([^)]*\\))\\.(?:getReplyList(?:Main|Legacy)?|getReplyReply|getReplyCount)\\s*\\(')
        matches = list(pattern.finditer(masked(text)))
        for m in reversed(matches):
            mask = masked(text)
            open_at = mask.index('(', m.end() - 1)
            end = balanced(mask, open_at)
            text = text[:m.start()] + 'ownedCall { ' + text[m.start():end] + ' }' + text[end:]
        return text

    def adapt_read(text):
        text = drop_logs(text).replace('CommentGrpcRepository.MODE_', 'DesktopDynamicCommentGrpc.MODE_').replace('CommentGrpcRepository.', 'commentGrpc.')
        text = text.replace('VideoRepository.ensureBuvid3()', 'currentCoroutineContext().ensureActive(); assertOwner()')
        text = text.replace('!com.android.purebilibili.core.store.TokenManager.sessDataCache.isNullOrEmpty()', 'hasSession()')
        text = text.replace('runCatching', 'ownedCatching')
        replacement = '} catch (e: Exception) {\n            currentCoroutineContext().ensureActive(); assertOwner()'
        if 'catch (e: CancellationException)' not in text:
            replacement = '} catch (cancelled: CancellationException) {\n            throw cancelled\n        ' + replacement
        text = text.replace('} catch (e: Exception) {', replacement)
        if 'val wbiKeys = getWbiKeysOrNull(apiClient)' in text:
            text = text.replace('val wbiKeys = getWbiKeysOrNull(apiClient)', 'val signedParams = ownedCatching { ownedCall { signParams(params) } }.getOrNull()')
            text = text.replace('if (wbiKeys != null)', 'if (signedParams != null)')
            text = text.replace('                    val (imgKey, subKey) = wbiKeys\n                    val signedParams = WbiUtils.sign(params, imgKey, subKey)\n', '')
        if 'private suspend fun supplementSortedSubReplyLocations(' in text:
            # Keep the original optional-read window/batch/ranking behavior. The
            # required existing sign port owns WBI keys; no second manager/cache.
            first='val keys = getWbiKeysOrNull() ?: return@withTimeoutOrNull'
            replacement='ownedCatching { ownedCall { signParams(emptyMap()) } }.getOrNull() ?: return@withTimeoutOrNull'
            assert text.count(first)==1
            text=text.replace(first,replacement,1)
            first='WbiUtils.sign(params, keys.first, keys.second)'
            assert text.count(first)==1
            text=text.replace(first,'ownedCall { signParams(params) }',1)
            first='        return mergeCommentReplyLocations(data, supplements)'
            assert text.count(first)==1
            text=text.replace(first,'        currentCoroutineContext().ensureActive(); assertOwner()\n'+first,1)
        return owned_api_calls(text)
    A = 'app/src/main/java/com/android/purebilibili/'
    paths = [A + 'data/repository/' + name + '.kt' for name in ('CommentRepository', 'CommentReadAccessPolicy', 'CommentGrpcRepository')]
    paths += [A + 'core/network/ApiClient.kt', A + 'data/model/response/ResponseModels.kt']
    sources, source_ids = load_pinned_sources(ROOT, paths)
    repo_path = paths[0]
    repo = sources[repo_path]
    read_names = ['resolveReadApi', 'fetchNonWbiCommentFallback', 'fetchCommentsByApi', 'fetchGuestHotCommentsCompat', 'fetchLegacyHotCommentsCompat', 'fetchCommentEmptySuccessFallback', 'getCommentsForSubject', 'getCommentCountForSubject', 'getSortedSubCommentsForSubject', 'supplementSortedSubReplyLocations', 'getSubCommentsForSubject', 'getDialogCommentsForSubject', 'shouldTryGrpcMainList', 'resolveCommentMainListPaginationParameters', 'resolveCommentMainListMode', 'shouldTryGrpcPagedRequest']
    selected = '\n\n'.join((adapt_read(extract(repo, name, repo_path)) for name in read_names))
    header = '// GENERATED by protocol/produce.py; task-only prepared source.\n// Original: ' + repo_path + '\n// Original LF SHA-256: ' + digest(repo) + '\n'
    protocol = header + 'package com.android.purebilibili.data.repository\nimport com.android.purebilibili.core.network.BilibiliApi\nimport com.android.purebilibili.data.model.response.*\nimport kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.currentCoroutineContext\nimport kotlinx.coroutines.ensureActive\nimport kotlinx.coroutines.withContext\nimport kotlinx.coroutines.async\nimport kotlinx.coroutines.awaitAll\nimport kotlinx.coroutines.coroutineScope\nimport kotlinx.coroutines.withTimeoutOrNull\nimport kotlinx.serialization.encodeToString\nimport kotlinx.serialization.json.Json\nimport java.util.TreeMap\ninternal class DesktopDynamicCommentProtocol(\n    private val api: BilibiliApi,\n    private val guestApi: BilibiliApi,\n    private val commentGrpc: DesktopDynamicCommentGrpc,\n    private val hasSession: () -> Boolean,\n    private val signParams: suspend (Map<String,String>) -> Map<String,String>,\n    private val assertOwner: () -> Unit,\n) {\n    private val commentJson = Json { ignoreUnknownKeys = true }\n    private suspend inline fun <T> ownedCall(block: suspend () -> T): T {\n        currentCoroutineContext().ensureActive(); assertOwner()\n        return block().also { currentCoroutineContext().ensureActive(); assertOwner() }\n    }\n    private inline fun <T> ownedCatching(block: () -> T): Result<T> = try {\n        assertOwner(); Result.success(block().also { assertOwner() })\n    } catch (cancelled: CancellationException) { throw cancelled\n    } catch (failure: Exception) { assertOwner(); Result.failure(failure) }\n' + selected + '\n}\n'
    write(HERE / 'generated/com/android/purebilibili/data/repository/DesktopDynamicCommentProtocol.kt', protocol)
    policy_path = paths[1]
    policy = sources[policy_path]
    write(HERE / 'generated/com/android/purebilibili/data/repository/DesktopOriginalCommentReadAccessPolicy.kt', '// GENERATED original full read policy; sole producer.\n// Original: ' + policy_path + '\n// Original LF SHA-256: ' + digest(policy) + '\n' + policy)
    grpc_path = paths[2]
    original_grpc, grpc_selection = comment_search.select_full(ROOT,grpc_path,sources[grpc_path])
    grpc = original_grpc.replace('import com.android.purebilibili.core.network.grpc.BiliGrpcClient\n', '')
    grpc = grpc.replace('internal object CommentGrpcRepository {', 'internal class DesktopDynamicCommentGrpc(\n    private val requestTransport: suspend (String, ByteArray) -> ByteArray,\n    private val assertOwner: () -> Unit,\n) {\n    private suspend fun request(path: String, message: ByteArray): ByteArray {\n        kotlinx.coroutines.currentCoroutineContext().ensureActive(); assertOwner()\n        return requestTransport(path, message).also { kotlinx.coroutines.currentCoroutineContext().ensureActive(); assertOwner() }\n    }\n    private inline fun <T> ownedCatching(block: () -> T): Result<T> = try {\n        assertOwner(); Result.success(block().also { assertOwner() })\n    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled\n    } catch (failure: Exception) { assertOwner(); Result.failure(failure) }\n')
    grpc = grpc.replace('private const val PATH_', 'private val PATH_')
    grpc = grpc.replace('    internal const val MODE_TIME = 2\n    internal const val MODE_HOT = 3', '    internal companion object {\n        internal const val MODE_TIME = 2\n        internal const val MODE_HOT = 3\n    }')
    grpc = grpc.replace('BiliGrpcClient.request(', 'request(').replace('return runCatching {', 'return ownedCatching {').replace('        runCatching {', '        ownedCatching {')
    for name in ('getMainList', 'getDetailList', 'getDialogList'):
        grpc = grpc.replace('    fun ' + name + '(', '    suspend fun ' + name + '(')
    grpc = grpc.replace('import kotlinx.coroutines.Dispatchers\n', 'import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.ensureActive\n')
    grpc_selection['originalToPlatform']=comment_search.whole_proof(original_grpc,grpc)
    write(HERE/'v029-comment-grpc-source.json',json.dumps(grpc_selection,ensure_ascii=False,indent=2)+'\n')
    write(HERE / 'generated/com/android/purebilibili/data/repository/DesktopDynamicCommentGrpc.kt', '// GENERATED original full builders/parser with owned transport only.\n// Original: ' + grpc_path + '\n// Original LF SHA-256: ' + digest(original_grpc) + '\n' + grpc)
    fragment = '// Paste inside existing DesktopDynamicCardOperations; no package/class/API/model producer.\n// Requires the frozen editor\'s existing private uploadEditorCommentImage (one upload body).\nprivate val guestCommentApi = guestWeb.create(BilibiliApi::class.java)\nprivate val commentGrpc = com.android.purebilibili.data.repository.DesktopDynamicCommentGrpc(grpc::request, ::assertOwned)\nprivate val commentProtocol = com.android.purebilibili.data.repository.DesktopDynamicCommentProtocol(\n    api, guestCommentApi, commentGrpc,\n    { assertOwned(); !repository.authCookies()["SESSDATA"].isNullOrEmpty() },\n    { params -> assertOwned(); repository.signWebParams(params).also { assertOwned() } },\n    ::assertOwned,\n)\n'
    read_signatures = {'getCommentsForSubject': ('oid:Long,type:Int,page:Int,ps:Int=20,mode:Int=3,paginationOffset:String?=null,fallbackOnMissingLocation:Boolean=false', 'oid,type,page,ps,mode,paginationOffset,fallbackOnMissingLocation', 'ReplyData'), 'getCommentCountForSubject': ('oid:Long,type:Int', 'oid,type', 'Int'), 'getSortedSubCommentsForSubject': ('oid:Long,type:Int,rootId:Long,mode:Int,paginationOffset:String?=null,targetReplyId:Long=0L', 'oid,type,rootId,mode,paginationOffset,targetReplyId', 'ReplyData'), 'getSubCommentsForSubject': ('oid:Long,type:Int,rootId:Long,page:Int,ps:Int=20,paginationOffset:String?=null,preferRestPaging:Boolean=true', 'oid,type,rootId,page,ps,paginationOffset,preferRestPaging', 'ReplyData'), 'getDialogCommentsForSubject': ('oid:Long,type:Int,rootId:Long,dialogId:Long,page:Int,paginationOffset:String?=null', 'oid,type,rootId,dialogId,page,paginationOffset', 'ReplyData')}
    for name, (params, args, ret) in read_signatures.items():
        fragment += '\nsuspend fun ' + name + '(' + params + '):Result<' + ret + '> = result { read { commentProtocol.' + name + '(' + args + ').getOrThrow() } }\n'
    fragment += '\nsuspend fun translateReply(type:Long,oid:Long,rpid:Long):Result<String?> = result { read { commentGrpc.translateReply(type,oid,rpid).getOrThrow() } }\n'
    fragment += '\nsuspend fun uploadCommentImage(fileName:String,mimeType:String,bytes:ByteArray):Result<ReplyPicture> = result { mutate { csrf -> uploadEditorCommentImage(csrf,fileName,mimeType,bytes) } }\n'
    fragment += '\n    // Desktop original comment image streaming binding. Reuse the editor\'s one original upload body.\n    suspend fun uploadCommentImageBody(fileName: String, mimeType: String, fileBody: okhttp3.RequestBody): Result<ReplyPicture> =\n        result { mutate { csrf -> uploadEditorCommentImageBody(csrf, fileName, mimeType, fileBody) } }\n\n'
    for name in ('addCommentForSubject', 'likeCommentForSubject', 'hateCommentForSubject', 'deleteCommentForSubject', 'setCommentTopForSubject', 'reportCommentForSubject'):
        original = extract(repo, name, repo_path)
        mask = masked(original)
        param = mask.index('(')
        param_end = balanced(mask, param)
        signature = original[:param_end]
        if name == 'addCommentForSubject':
            signature = signature.replace('syncToDynamic: Boolean = false', 'syncToDynamic: Boolean = false,\n        onPublishedRecord: ((ReplyItem, Long, Int, Long, Long, String, Long) -> Unit)? = null')
        try_at = mask.index('try')
        body_start = mask.index('{', try_at)
        body_end = balanced(mask, body_start, '{', '}')
        body = original[body_start + 1:body_end - 1]
        start = body.index('val picturePayload') if name == 'addCommentForSubject' else body.index('val response')
        body = body[start:]
        if name == 'addCommentForSubject':
            m = re.search('AppScope\\.ioScope\\.launch\\s*\\{', masked(body))
            launch_start = m.start()
            open_at = masked(body).index('{', launch_start)
            launch_end = balanced(masked(body), open_at, '{', '}')
            body = body[:launch_start] + 'coroutineContext.ensureActive(); assertOwned()\n                    onPublishedRecord?.invoke(reply, oid, type, root, parent, message, serverPostTime)\n                    coroutineContext.ensureActive(); assertOwned()' + body[launch_end:]
            body = body.replace('val userUid = reply.mid', '').replace('buildPicturesPayload(', 'buildCommentPicturesPayload(').replace('resolveSyncToDynamicField(', 'resolveCommentSyncToDynamicField(')
            body = body.replace('// [纯异步旁路] 在后台全局协程中静默存库，完全不卡主流程，零延迟返回', '// Owned optional original-record seam; null is explicitly unbound.')
        body = body.replace('resolveReplyTopActionField(', 'resolveCommentTopActionField(')
        body = drop_logs(body)
        response_at = masked(body).index('val response = api.')
        response_open = masked(body).index('(', response_at)
        response_end = balanced(masked(body), response_open)
        body = body[:response_end] + '\n            coroutineContext.ensureActive(); assertOwned()\n' + body[response_end:]
        body = result_values(body)
        ret = 'ReplyItem?' if name == 'addCommentForSubject' else 'Unit'
        fragment += '\n' + signature + ': Result<' + ret + '> = result { mutate { csrf ->\n' + body + '\n} }\n'
    fragment += "\nprivate fun buildCommentPicturesPayload(pictures:List<ReplyPicture>):String? {\n    if (pictures.isEmpty()) return null\n    // Original ReplyPicture already has exactly the four original CommentPicturePayload fields.\n    // Emit defaults to match the original private payload's four required fields; no new DTO.\n    return kotlinx.serialization.json.Json(json) { encodeDefaults = true }.encodeToString(\n        kotlinx.serialization.builtins.ListSerializer(ReplyPicture.serializer()), pictures)\n}\n"
    for old, new in [('resolveSyncToDynamicField', 'resolveCommentSyncToDynamicField'), ('resolveReplyTopActionField', 'resolveCommentTopActionField')]:
        block = extract(repo, old, repo_path).replace('internal fun ' + old, 'private fun ' + new)
        fragment += '\n' + block + '\n'
    write(HERE / 'DesktopDynamicCommentOperations.fragment.kt', fragment)
    write(HERE / 'source-identity.json', json.dumps(source_ids, indent=2) + '\n')
    write(HERE / 'selected-source-bodies.json', json.dumps(records, indent=2) + '\n')
    return sorted(HERE.rglob('generated/**/*.kt'))
if __name__ == '__main__':
    import argparse
    cli = argparse.ArgumentParser()
    cli.add_argument('--repo', type=Path, required=True)
    cli.add_argument('--output', type=Path, required=True)
    args = cli.parse_args()
    generate(args.repo, args.output)
