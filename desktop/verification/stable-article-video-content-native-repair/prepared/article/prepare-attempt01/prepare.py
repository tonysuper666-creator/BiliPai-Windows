from pathlib import Path
import hashlib,json,importlib.util,re
LANE=Path(__file__).resolve().parent; REPO=LANE.parents[2].parent/'BiliPai-v023'
def wide(p):
 s=str(Path(p).absolute()); prefix=chr(92)*2+'?'+chr(92); return Path(s if s.startswith(prefix) else prefix+s)
def read(p): return wide(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,data): wide(p).parent.mkdir(parents=True,exist_ok=True); wide(p).write_text(data,encoding='utf-8',newline='\n')
sha=lambda s:hashlib.sha256(s.encode()).hexdigest()
spec=importlib.util.spec_from_file_location('lexer',REPO/'desktop/tools/extract-upstream-dynamic-reply-protocol.py'); lexer=importlib.util.module_from_spec(spec); spec.loader.exec_module(lexer)
base='app/src/main/java/com/android/purebilibili/'
paths=[base+'feature/article/'+x+'.kt' for x in ['ArticleDetailScreen','ArticleRichTextRenderer','ArticleImagePreviewPolicy','ArticleDetailLayoutPolicy']]+[base+'core/ui/skeleton/ContentLoadingSkeletons.kt',base+'data/repository/ArticleRepository.kt']
sources,identities=lexer.load_pinned_sources(REPO,paths); edits=[]; bodies=[]
def adapt(text,before,after,label):
 assert text.count(before)==1,label; edits.append(dict(label=label,before=before,after=after)); return text.replace(before,after,1)
def selected(path,name):
 source=sources[path]; start,end=lexer.fun_span(source,name); block=source[start:end]
 bodies.append(dict(path=path,name=name,startLine=source[:start].count('\n')+1,endLine=source[:end].count('\n')+1,sha256Lf=sha(block)))
 return block
out=LANE/'prepared/selected'
ui=sources[paths[0]]
ui=adapt(ui,'import androidx.activity.compose.BackHandler','import com.android.purebilibili.core.ui.LocalNavigationBackHandler as BackHandler','existing Root back dispatcher')
ui=adapt(ui,'import androidx.compose.ui.res.stringResource','import com.bilipai.desktop.ui.desktopHomeStringResource','same original multilingual resource map')
ui=adapt(ui,'import com.android.purebilibili.R\n','import com.bilipai.desktop.ui.DesktopOriginalArticleBindings\n','required owned loader')
ui=adapt(ui,'    onUserClick: (Long) -> Unit\n) {','    onUserClick: (Long) -> Unit,\n    bindings: DesktopOriginalArticleBindings,\n) {','screen required loader ABI')
for key in ['common_back','common_retry','dynamic_detail_load_failed']:
 ui=adapt(ui,'stringResource(R.string.'+key+')','desktopHomeStringResource("'+key+'")','original resource '+key)
ui=adapt(ui,'ArticleRepository.getArticleDetail(articleId)','bindings.load(articleId)','sole original owned Article protocol')
ui=adapt(ui,'import com.android.purebilibili.data.repository.ArticleRepository\n','','no singleton transport')
ui=adapt(ui,'navigationBarsBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()','navigationBarsBottom = 0.dp','Windows client area has no Android nav-bar inset')
write(out/'com/android/purebilibili/feature/article/ArticleDetailScreen.kt',ui)
for path in paths[1:4]: write(out/path.removeprefix(base),sources[path].replace('package com.android.purebilibili','package com.android.purebilibili',1))
# Keep original pulse/color/block helpers in their already-installed sole producer.
skeleton='''package com.android.purebilibili.core.ui.skeleton
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyListItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
'''+selected(paths[4],'ArticleDetailSkeleton')+'\n'
write(out/'com/android/purebilibili/core/ui/skeleton/DesktopOriginalArticleSkeleton.kt',skeleton)
original=REPO/'desktop/tools/extract-upstream-dynamic-detail-protocol.py'; tool=read(original); tool_edits=[]
def tool_edit(old,new,label):
 global tool
 assert tool.count(old)==1,label; tool_edits.append(dict(label=label,before=old,after=new)); tool=tool.replace(old,new,1)
tool_edit("    get_article = get_article.replace('Result<ArticleDetailUiModel>', 'Result<Pair<String,List<ArticleContentBlock>>>')", "    get_article = get_article.replace('fun getArticleDetail(', 'fun getArticleUiDetail(')",'canonical complete original model method')
tool_edit("    get_article = get_article.replace('val fromView = response.data.toUiModel()', 'val fromView = response.data.toDetailTitleAndBlocks()')\n    get_article = get_article.replace('fromView.copy(blocks = selectRicherArticleBlocks(fromView.blocks, opusBlocks))', 'fromView.first to selectRicherArticleBlocks(fromView.second, opusBlocks)')\n", '', 'retain whole original conversion and merge')
tool_edit("callback(response.data.id)","callback(merged.articleId)",'original loaded article history identity')
start=tool.index("    start = original_extension.index('val parsedBlocks')"); stop=tool.index("    write(HERE / 'generated/com/android/purebilibili/data/repository/DesktopDynamicDetailArticleProtocol.kt', article_helper)",start)
before=tool[start:stop]
after='''    normalize_image = extract(article, 'String?.normalizeImageUrl', article_path)
    model_start = article.index('data class ArticleDetailUiModel(')
    model_open = masked(article).index('(', model_start)
    model_end = balanced(masked(article), model_open)
    model = article[model_start:model_end]
    records.append(dict(source=article_path, name='ArticleDetailUiModel', startLine=article[:model_start].count('\\n')+1, endLine=article[:model_end].count('\\n')+1, sha256LfUtf8=digest(model)))
    projection = '    suspend fun getArticleDetail(articleId:Long):Result<Pair<String,List<ArticleContentBlock>>> = getArticleUiDetail(articleId).map { it.title to it.blocks }\\n'
    article_helper = '// GENERATED complete original Article detail algorithm/model; existing Pair consumer is a projection.\\n// Original: ' + article_path + '\\n// Original LF SHA-256: ' + digest(article) + '\\n' + imports + 'import com.android.purebilibili.core.util.FormatUtils\\n' + model + '\\ninternal class DesktopDynamicDetailArticleProtocol(\\n    private val articleApi: ArticleApi,\\n    private val dynamicApi: DynamicApi,\\n    private val signDetailParams: suspend (Map<String,String>) -> Map<String,String>,\\n    private val assertOwner: () -> Unit,\\n    private val onArticleViewed: (suspend (Long) -> Unit)?,\\n) {\\n' + common + projection + owned_calls(get_article) + '\\n' + owned_calls(fetch_article) + '\\n' + original_extension + '\\n' + normalize_image + '\\n}\\n'
'''
tool_edit(before,after,'retain full original model/conversion/URL normalization; keep sole Pair ABI')
write(LANE/'prepared/tools/extract-upstream-dynamic-detail-protocol.py',tool)
spec=importlib.util.spec_from_file_location('article_prospective_producer',LANE/'prepared/tools/extract-upstream-dynamic-detail-protocol.py'); producer=importlib.util.module_from_spec(spec); producer.__file__=str(original); spec.loader.exec_module(producer)
producer.generate(REPO,LANE/'prepared/protocol')
write(LANE/'tool-delta.json',json.dumps(dict(path='desktop/tools/extract-upstream-dynamic-detail-protocol.py',baseLfSha256=sha(read(original)),candidateLfSha256=sha(tool),hunks=tool_edits),ensure_ascii=False,indent=2)+'\n')
write(LANE/'source-identity.json',json.dumps(identities,indent=2)+'\n'); write(LANE/'selected-source-bodies.json',json.dumps(bodies,indent=2)+'\n'); write(LANE/'ui-platform-edits.json',json.dumps(edits,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(dict(prepared=True,originalFullUiLines=len(sources[paths[0]].splitlines()),uiPlatformEdits=len(edits),toolHunks=len(tool_edits),liveMutation=False)))
