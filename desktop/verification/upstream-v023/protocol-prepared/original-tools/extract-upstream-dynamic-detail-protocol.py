"""Original selected protocol producer; no snapshot or .local dependency."""
from pathlib import Path
import hashlib, re, subprocess, json
TAG = 'v0.2.3-alpha.9'

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
        assert HERE in path.parents
        safe(path.parent).mkdir(parents=True, exist_ok=True)
        safe(path).write_text(text, encoding='utf-8', newline='\n')
    import importlib.util
    spec = importlib.util.spec_from_file_location('original_reply_protocol_lexer', Path(__file__).with_name('extract-upstream-dynamic-reply-protocol.py'))
    protocol = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(protocol)
    masked = protocol.masked
    balanced = protocol.balanced
    fun_span = protocol.fun_span
    drop_logs = protocol.drop_logs
    records = []

    def extract(text, name, path):
        start, end = fun_span(text, name)
        block = text[start:end]
        records.append(dict(source=path, name=name, startLine=text[:start].count('\n') + 1, endLine=text[:end].count('\n') + 1, sha256LfUtf8=digest(block)))
        return block
    A = 'app/src/main/java/com/android/purebilibili/'
    repo_path = A + 'data/repository/DynamicRepository.kt'
    article_path = A + 'data/repository/ArticleRepository.kt'
    policy_path = A + 'data/repository/DynamicDetailFallbackPolicy.kt'
    paths = [repo_path, article_path, policy_path, A + 'feature/article/ArticleContentLoadPolicy.kt', A + 'core/network/ApiClient.kt', A + 'feature/dynamic/DynamicDetailScreen.kt']
    sources = {}
    ids = []
    for path in paths:
        sources[path] = read(ROOT / path)
        blob = subprocess.check_output(['git', 'rev-parse', 'v0.2.3-alpha.9:' + path], cwd=ROOT, text=True).strip()
        current = subprocess.check_output(['git', 'hash-object', '--path=' + path, path], cwd=ROOT, text=True).strip()
        assert blob == current, path
        ids.append(dict(path=path, tagBlob=blob, currentGitBlob=current, sha256LfUtf8=digest(sources[path]), matchesTag=True))

    def adapt(block):
        block = drop_logs(block).replace('e.printStackTrace()', '').replace('runCatching', 'ownedCatching')
        block = block.replace('NetworkModule.dynamicApi.', 'dynamicApi.')
        block = block.replace('} catch (e: Exception) {', '} catch (cancelled: CancellationException) { throw cancelled\n        } catch (e: Exception) {\n            currentCoroutineContext().ensureActive(); assertOwner()')
        return block
    repo = sources[repo_path]
    main = adapt(extract(repo, 'getDynamicDetail', repo_path))
    extract(repo, 'signDynamicWbi', repo_path)
    main = main.replace('dynamicId: String)', 'dynamicId: String, seedItem: DynamicItem?)')
    main = main.replace('val seed = peekDynamicDetailSeed(cleanedId)', 'val seed = seedItem')
    main = main.replace('ArticleRepository.getArticleDetail(cvId).getOrNull()?.let { article ->', 'ownedCall { fetchArticle(cvId) }?.let { article ->')
    main = main.replace('title = article.title', 'title = article.first').replace('blocks = article.blocks', 'blocks = article.second')
    helpers = '\n\n'.join((adapt(extract(repo, name, repo_path)) for name in ['fetchWebDetailItem', 'fetchOpusDetail', 'fetchDesktopDetailItem']))
    helpers = helpers.replace('signDynamicWbi(', 'signDetailParams(')
    type_match = re.search('    private data class OpusDetailFetch\\(', masked(repo))
    type_open = masked(repo).index('(', type_match.start())
    type_end = balanced(masked(repo), type_open)
    private_type = repo[type_match.start():type_end]

    def owned_calls(text):
        matches = list(re.finditer('(?:dynamicApi|articleApi)\\.(?:getDynamicDetail(?:Fallback)?|getOpusDetail|getArticleView)\\s*\\(', masked(text)))
        for m in reversed(matches):
            ma = masked(text)
            op = ma.index('(', m.end() - 1)
            end = balanced(ma, op)
            text = text[:m.start()] + 'ownedCall { ' + text[m.start():end] + ' }' + text[end:]
        return text
    header = '// GENERATED selected original owned detail protocol; task-only.\n// Original: ' + repo_path + '\n// Original LF SHA-256: ' + digest(repo) + '\n'
    common = '    private suspend inline fun <T> ownedCall(block: suspend () -> T): T {\n        currentCoroutineContext().ensureActive(); assertOwner()\n        return block().also { currentCoroutineContext().ensureActive(); assertOwner() }\n    }\n    private inline fun <T> ownedCatching(block: () -> T): Result<T> = try {\n        assertOwner(); Result.success(block().also { assertOwner() })\n    } catch (cancelled: CancellationException) { throw cancelled\n    } catch (failure: Exception) { assertOwner(); Result.failure(failure) }\n'
    imports = 'package com.android.purebilibili.data.repository\nimport com.android.purebilibili.core.network.*\nimport com.android.purebilibili.data.model.response.*\nimport com.android.purebilibili.feature.article.*\nimport kotlinx.coroutines.*\n'
    dynamic = header + imports + 'internal class DesktopDynamicDetailProtocol(\n    private val dynamicApi: DynamicApi,\n    private val signDetailParams: suspend (Map<String,String>) -> Map<String,String>,\n    private val fetchArticle: suspend (Long) -> Pair<String,List<ArticleContentBlock>>?,\n    private val assertOwner: () -> Unit,\n) {\n' + common + owned_calls(main) + '\n' + private_type + '\n' + owned_calls(helpers) + '\n}\n'
    write(HERE / 'generated/com/android/purebilibili/data/repository/DesktopDynamicDetailProtocol.kt', dynamic)
    write(HERE / 'generated/com/android/purebilibili/data/repository/DesktopOriginalDynamicDetailFallbackPolicy.kt', '// GENERATED full original pure detail policy; sole producer.\n// Original: ' + policy_path + '\n// Original LF SHA-256: ' + digest(sources[policy_path]) + '\n' + sources[policy_path])
    article = sources[article_path]
    get_article = adapt(extract(article, 'getArticleDetail', article_path))
    get_article = get_article.replace('Result<ArticleDetailUiModel>', 'Result<Pair<String,List<ArticleContentBlock>>>')
    get_article = get_article.replace('signWithWbi(', 'signDetailParams(')
    get_article = get_article.replace('val fromView = response.data.toUiModel()', 'val fromView = response.data.toDetailTitleAndBlocks()')
    get_article = get_article.replace('fromView.copy(blocks = selectRicherArticleBlocks(fromView.blocks, opusBlocks))', 'fromView.first to selectRicherArticleBlocks(fromView.second, opusBlocks)')
    get_article = get_article.replace('ownedCatching { HistoryRepository.reportArticleView(merged.articleId) }', 'ownedCatching { onArticleViewed?.let { callback -> ownedCall { callback(response.data.id) } } }')
    fetch_article = adapt(extract(article, 'fetchOpusArticleBlocks', article_path)).replace('signWithWbi(', 'signDetailParams(')
    extension = extract(article, 'ArticleViewData.toUiModel', article_path) if False else None
    ma = masked(article)
    m = re.search('    private fun ArticleViewData\\.toUiModel\\(\\)', ma)
    op = ma.index('{', m.start())
    end = balanced(ma, op, '{', '}')
    original_extension = article[m.start():end]
    records.append(dict(source=article_path, name='ArticleViewData.toUiModel', startLine=article[:m.start()].count('\n') + 1, endLine=article[:end].count('\n') + 1, sha256LfUtf8=digest(original_extension)))
    start = original_extension.index('val parsedBlocks')
    stop = original_extension.index('        val resolvedSummary')
    slice_title = original_extension[start:stop]
    title_extension = '    private fun ArticleViewData.toDetailTitleAndBlocks():Pair<String,List<ArticleContentBlock>> {\n        ' + slice_title + '        return resolvedTitle to parsedBlocks\n    }\n'
    article_helper = '// GENERATED selected original Article view/opus/consumed title+blocks.\n// Original: ' + article_path + '\n// Original LF SHA-256: ' + digest(article) + '\n' + imports + 'internal class DesktopDynamicDetailArticleProtocol(\n    private val articleApi: ArticleApi,\n    private val dynamicApi: DynamicApi,\n    private val signDetailParams: suspend (Map<String,String>) -> Map<String,String>,\n    private val assertOwner: () -> Unit,\n    private val onArticleViewed: (suspend (Long) -> Unit)?,\n) {\n' + common + owned_calls(get_article) + '\n' + owned_calls(fetch_article) + '\n' + title_extension + '}\n'
    write(HERE / 'generated/com/android/purebilibili/data/repository/DesktopDynamicDetailArticleProtocol.kt', article_helper)
    fragment = "// Additional members inside the existing DesktopDynamicCardOperations; no replacement Ops file.\n// Seed is the original DynamicItem captured by the owner's caller. No new seed cache.\n// Null history callback explicitly leaves the original best-effort article history side effect unbound.\nsuspend fun getDynamicDetail(\n    dynamicId:String,\n    seedItem:DynamicItem?,\n    onArticleViewed:(suspend (Long)->Unit)?=null,\n):Result<DynamicItem> = result { read {\n    val articleProtocol=com.android.purebilibili.data.repository.DesktopDynamicDetailArticleProtocol(\n        web.create(ArticleApi::class.java), dynamic, ::signOriginalDetailParams, ::assertOwned, onArticleViewed)\n    val protocol=com.android.purebilibili.data.repository.DesktopDynamicDetailProtocol(\n        dynamic, ::signOriginalDetailParams,\n        { id -> articleProtocol.getArticleDetail(id).getOrNull() }, ::assertOwned)\n    protocol.getDynamicDetail(dynamicId,seedItem).getOrThrow()\n} }\nprivate suspend fun signOriginalDetailParams(params:Map<String,String>):Map<String,String> {\n    coroutineContext.ensureActive();assertOwned()\n    return try { repository.signWebParams(params).also { coroutineContext.ensureActive();assertOwned() }\n    } catch(cancelled:CancellationException) { throw cancelled\n    } catch(failure:Exception) { coroutineContext.ensureActive();assertOwned();params }\n}\n"
    write(HERE / 'DesktopDynamicDetailOperations.fragment.kt', fragment)
    return sorted(HERE.rglob('generated/**/*.kt'))
if __name__ == '__main__':
    import argparse
    cli = argparse.ArgumentParser()
    cli.add_argument('--repo', type=Path, required=True)
    cli.add_argument('--output', type=Path, required=True)
    args = cli.parse_args()
    generate(args.repo, args.output)
