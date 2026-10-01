from pathlib import Path
import ast,hashlib,json,re,subprocess,zipfile
HERE=Path(__file__).resolve().parent
ROOT=next(p for p in HERE.parents if (p/'app/src/main').is_dir())
def safe(path):
    value=str(path.absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def read(path):return safe(path).read_text(encoding='utf-8').replace('\r\n','\n').replace('\r','\n')
def sha(path):return hashlib.sha256(safe(path).read_bytes()).hexdigest()
def digest(text):return hashlib.sha256(text.encode('utf-8')).hexdigest()
def write(path,text):
    assert HERE in path.parents
    safe(path.parent).mkdir(parents=True,exist_ok=True)
    safe(path).write_text(text,encoding='utf-8',newline='\n')
# Reuse frozen producer lexer functions only; never execute its generator.
lex_source=read(HERE.parent/'produce.py')
tree=ast.parse(lex_source)
defs=[n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name in {'masked','balanced','fun_span','drop_logs'}]
assert len(defs)==4
lex={'re':re};exec(compile(ast.Module(body=defs,type_ignores=[]),'frozen-lexer-functions','exec'),lex)
masked=lex['masked'];balanced=lex['balanced'];fun_span=lex['fun_span'];drop_logs=lex['drop_logs']
records=[]
def extract(text,name,path):
    start,end=fun_span(text,name);block=text[start:end]
    records.append(dict(source=path,name=name,startLine=text[:start].count('\n')+1,endLine=text[:end].count('\n')+1,sha256LfUtf8=digest(block)))
    return block
A='app/src/main/java/com/android/purebilibili/'
repo_path=A+'data/repository/DynamicRepository.kt'
article_path=A+'data/repository/ArticleRepository.kt'
policy_path=A+'data/repository/DynamicDetailFallbackPolicy.kt'
paths=[repo_path,article_path,policy_path,A+'feature/article/ArticleContentLoadPolicy.kt',A+'core/network/ApiClient.kt',A+'feature/dynamic/DynamicDetailScreen.kt']
sources={};ids=[]
for path in paths:
    sources[path]=read(ROOT/path)
    blob=subprocess.check_output(['git','rev-parse','v0.2.3-alpha.9:'+path],cwd=ROOT,text=True).strip()
    current=subprocess.check_output(['git','hash-object','--path='+path,path],cwd=ROOT,text=True).strip()
    assert blob==current,path
    ids.append(dict(path=path,tagBlob=blob,currentGitBlob=current,sha256LfUtf8=digest(sources[path]),matchesTag=True))
def adapt(block):
    block=drop_logs(block).replace('e.printStackTrace()','').replace('runCatching','ownedCatching')
    block=block.replace('NetworkModule.dynamicApi.','dynamicApi.')
    block=block.replace('} catch (e: Exception) {','} catch (cancelled: CancellationException) { throw cancelled\n        } catch (e: Exception) {\n            currentCoroutineContext().ensureActive(); assertOwner()')
    return block
repo=sources[repo_path]
main=adapt(extract(repo,'getDynamicDetail',repo_path))
extract(repo,'signDynamicWbi',repo_path) # Identity of the original unsigned-on-signing-failure policy.
main=main.replace('dynamicId: String)','dynamicId: String, seedItem: DynamicItem?)')
main=main.replace('val seed = peekDynamicDetailSeed(cleanedId)','val seed = seedItem')
main=main.replace('ArticleRepository.getArticleDetail(cvId).getOrNull()?.let { article ->','ownedCall { fetchArticle(cvId) }?.let { article ->')
main=main.replace('title = article.title','title = article.first').replace('blocks = article.blocks','blocks = article.second')
helpers='\n\n'.join(adapt(extract(repo,name,repo_path)) for name in ['fetchWebDetailItem','fetchOpusDetail','fetchDesktopDetailItem'])
helpers=helpers.replace('signDynamicWbi(','signDetailParams(')
# Original private local result structure, not a network/API schema.
type_match=re.search(r'    private data class OpusDetailFetch\(',masked(repo))
type_open=masked(repo).index('(',type_match.start());type_end=balanced(masked(repo),type_open)
private_type=repo[type_match.start():type_end]
# Wrap each original awaited API expression after preserving the original query tokens.
def owned_calls(text):
    matches=list(re.finditer(r'(?:dynamicApi|articleApi)\.(?:getDynamicDetail(?:Fallback)?|getOpusDetail|getArticleView)\s*\(',masked(text)))
    for m in reversed(matches):
        ma=masked(text);op=ma.index('(',m.end()-1);end=balanced(ma,op)
        text=text[:m.start()]+'ownedCall { '+text[m.start():end]+' }'+text[end:]
    return text
header='// GENERATED selected original owned detail protocol; task-only.\n// Original: '+repo_path+'\n// Original LF SHA-256: '+digest(repo)+'\n'
common='''    private suspend inline fun <T> ownedCall(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive(); assertOwner()
        return block().also { currentCoroutineContext().ensureActive(); assertOwner() }
    }
    private inline fun <T> ownedCatching(block: () -> T): Result<T> = try {
        assertOwner(); Result.success(block().also { assertOwner() })
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (failure: Exception) { assertOwner(); Result.failure(failure) }
'''
imports='''package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.article.*
import kotlinx.coroutines.*
'''
dynamic=header+imports+'''internal class DesktopDynamicDetailProtocol(
    private val dynamicApi: DynamicApi,
    private val signDetailParams: suspend (Map<String,String>) -> Map<String,String>,
    private val fetchArticle: suspend (Long) -> Pair<String,List<ArticleContentBlock>>?,
    private val assertOwner: () -> Unit,
) {
'''+common+owned_calls(main)+'\n'+private_type+'\n'+owned_calls(helpers)+'\n}\n'
write(HERE/'generated/com/android/purebilibili/data/repository/DesktopDynamicDetailProtocol.kt',dynamic)
write(HERE/'generated/com/android/purebilibili/data/repository/DesktopOriginalDynamicDetailFallbackPolicy.kt',
    '// GENERATED full original pure detail policy; sole producer.\n// Original: '+policy_path+'\n// Original LF SHA-256: '+digest(sources[policy_path])+'\n'+sources[policy_path])
# Narrow consumed Article title/blocks transport. No ArticleDetailUiModel producer.
article=sources[article_path]
get_article=adapt(extract(article,'getArticleDetail',article_path))
get_article=get_article.replace('Result<ArticleDetailUiModel>','Result<Pair<String,List<ArticleContentBlock>>>')
get_article=get_article.replace('signWithWbi(','signDetailParams(')
get_article=get_article.replace('val fromView = response.data.toUiModel()','val fromView = response.data.toDetailTitleAndBlocks()')
get_article=get_article.replace('fromView.copy(blocks = selectRicherArticleBlocks(fromView.blocks, opusBlocks))','fromView.first to selectRicherArticleBlocks(fromView.second, opusBlocks)')
get_article=get_article.replace('ownedCatching { HistoryRepository.reportArticleView(merged.articleId) }','ownedCatching { onArticleViewed?.let { callback -> ownedCall { callback(response.data.id) } } }')
fetch_article=adapt(extract(article,'fetchOpusArticleBlocks',article_path)).replace('signWithWbi(','signDetailParams(')
# Exact original parser/title statements from original extension, consumed by dynamic merge.
extension=extract(article,'ArticleViewData.toUiModel',article_path) if False else None
ma=masked(article);m=re.search(r'    private fun ArticleViewData\.toUiModel\(\)',ma)
op=ma.index('{',m.start());end=balanced(ma,op,'{','}')
original_extension=article[m.start():end]
records.append(dict(source=article_path,name='ArticleViewData.toUiModel',startLine=article[:m.start()].count('\n')+1,endLine=article[:end].count('\n')+1,sha256LfUtf8=digest(original_extension)))
start=original_extension.index('val parsedBlocks')
stop=original_extension.index('        val resolvedSummary')
slice_title=original_extension[start:stop]
title_extension='    private fun ArticleViewData.toDetailTitleAndBlocks():Pair<String,List<ArticleContentBlock>> {\n        '+slice_title+'        return resolvedTitle to parsedBlocks\n    }\n'
article_helper='// GENERATED selected original Article view/opus/consumed title+blocks.\n// Original: '+article_path+'\n// Original LF SHA-256: '+digest(article)+'\n'+imports+'''internal class DesktopDynamicDetailArticleProtocol(
    private val articleApi: ArticleApi,
    private val dynamicApi: DynamicApi,
    private val signDetailParams: suspend (Map<String,String>) -> Map<String,String>,
    private val assertOwner: () -> Unit,
    private val onArticleViewed: (suspend (Long) -> Unit)?,
) {
'''+common+owned_calls(get_article)+'\n'+owned_calls(fetch_article)+'\n'+title_extension+'}\n'
write(HERE/'generated/com/android/purebilibili/data/repository/DesktopDynamicDetailArticleProtocol.kt',article_helper)
fragment='''// Additional members inside the existing DesktopDynamicCardOperations; no replacement Ops file.
// Seed is the original DynamicItem captured by the owner's caller. No new seed cache.
// Null history callback explicitly leaves the original best-effort article history side effect unbound.
suspend fun getDynamicDetail(
    dynamicId:String,
    seedItem:DynamicItem?,
    onArticleViewed:(suspend (Long)->Unit)?=null,
):Result<DynamicItem> = result { read {
    val articleProtocol=com.android.purebilibili.data.repository.DesktopDynamicDetailArticleProtocol(
        web.create(ArticleApi::class.java), dynamic, ::signOriginalDetailParams, ::assertOwned, onArticleViewed)
    val protocol=com.android.purebilibili.data.repository.DesktopDynamicDetailProtocol(
        dynamic, ::signOriginalDetailParams,
        { id -> articleProtocol.getArticleDetail(id).getOrNull() }, ::assertOwned)
    protocol.getDynamicDetail(dynamicId,seedItem).getOrThrow()
} }
private suspend fun signOriginalDetailParams(params:Map<String,String>):Map<String,String> {
    coroutineContext.ensureActive();assertOwned()
    return try { repository.signWebParams(params).also { coroutineContext.ensureActive();assertOwned() }
    } catch(cancelled:CancellationException) { throw cancelled
    } catch(failure:Exception) { coroutineContext.ensureActive();assertOwned();params }
}
'''
write(HERE/'DesktopDynamicDetailOperations.fragment.kt',fragment)
pin=ROOT/'desktop/.local/dynamic-editor-main-product-snapshot-01'
assert sha(pin/'manifest.json')=='bc6cf9ef064ba68f28f36c3bbf2b59721629405567c7ced7e4799a844ff7b062'
assert sha(pin/'ordered-runtime-cp.json')=='515bf598f56b27de45aa04d63493e1f8ad38b7e4710ef358fea97b9fc9d620c1'
assert sha(pin/'main-kotlin.jar')=='47d866dc71544bf4dcc4ee39da7c6de3f0121fce90e7baca77c8e88e46ee45ed'
with zipfile.ZipFile(pin/'main-kotlin.jar') as z:
    names=set(z.namelist())
    required=['com/android/purebilibili/feature/article/ArticleContentLoadPolicyKt.class','com/android/purebilibili/feature/article/ArticleContentBlock.class']
    assert all(n in names for n in required)
    produced=['com.android.purebilibili.data.repository.'+n for n in ['DesktopDynamicDetailProtocol','DesktopDynamicDetailArticleProtocol','DesktopOriginalDynamicDetailFallbackPolicyKt']]
    assert all(n.replace('.','/')+'.class' not in names for n in produced)
manifest=dict(prepared=True,MainIntegration=False,executed=False,
    snapshotManifestSha256Bytes=sha(pin/'manifest.json'),orderedRuntimeCpSha256Bytes=sha(pin/'ordered-runtime-cp.json'),
    originalTag='v0.2.3-alpha.9',sources=ids,tokenExtractedFunctions=records,
    newSoleProducers=produced,reusedOriginalClassEntries=required,
    adaptations=['Original caller-supplied seed replaces lookup only; no new cache',
        'Existing owner/read/dynamic API and repository WBI signer, unsigned-query fallback retained',
        'Cancellation rethrown and job/owner guarded around awaits',
        'Original Article algorithm retained with only consumed title+blocks returned as existing Pair; no DTO',
        'Article history is optional owned best-effort callback; null explicitly unbound'],
    existingDesktopArticleDifference='DesktopCommunityRepository.articleDetail secondary opus error fails overall; original preserves successful article view.',
    frozenCommentProtocolManifestSha256Bytes=sha(HERE.parent/'frozen-manifest.json'),
    produced=[dict(path=p.relative_to(HERE).as_posix(),sha256Bytes=sha(p)) for p in [HERE/'DesktopDynamicDetailOperations.fragment.kt']+list(HERE.rglob('generated/**/*.kt'))])
write(HERE/'source-inventory.json',json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(dict(prepared=True,helpers=3,fragment=True,tokenFunctions=len(records),executed=False)))

