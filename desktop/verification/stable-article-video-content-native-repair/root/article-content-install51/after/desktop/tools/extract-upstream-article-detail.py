"""Full original Article screen/skeleton; canonical policies remain sole upstream sync outputs."""
from pathlib import Path
import hashlib,importlib.util,json,re
def wide(p):
 s=str(Path(p).absolute()); prefix=chr(92)*2+'?'+chr(92); return Path(s if s.startswith(prefix) else prefix+s)
def generate(repo:Path,output:Path):
    spec=importlib.util.spec_from_file_location('article_source_lexer',repo/'desktop/tools/extract-upstream-dynamic-reply-protocol.py')
    lexer=importlib.util.module_from_spec(spec); spec.loader.exec_module(lexer)
    base='app/src/main/java/com/android/purebilibili/'
    paths=[base+'feature/article/ArticleDetailScreen.kt',base+'core/ui/skeleton/ContentLoadingSkeletons.kt']
    sources,identities=lexer.load_pinned_sources(repo,paths); edits=[]; bodies=[]
    def change(text,before,after,label):
        assert text.count(before)==1,label
        edits.append(dict(label=label,before=before,after=after)); return text.replace(before,after,1)
    def put(relative,text):
        target=wide(output/relative); target.parent.mkdir(parents=True,exist_ok=True); target.write_text(text,encoding='utf-8',newline='\n')
    ui=sources[paths[0]]
    replacements=[
        ('import androidx.activity.compose.BackHandler','import com.android.purebilibili.core.ui.LocalNavigationBackHandler as BackHandler','existing Root back dispatcher'),
        ('import androidx.compose.ui.res.stringResource','import com.bilipai.desktop.ui.desktopHomeStringResource','same original multilingual resource map'),
        ('import com.android.purebilibili.R\n','import com.bilipai.desktop.ui.DesktopOriginalArticleBindings\n','required owned loader'),
        ('    onBack: (Boolean) -> Unit,\n    onUserClick: (Long) -> Unit\n) {','    onBack: (Boolean) -> Unit,\n    onUserClick: (Long) -> Unit,\n    bindings: DesktopOriginalArticleBindings,\n) {','screen required loader ABI'),
    ]
    replacements += [('stringResource(R.string.'+key+')','desktopHomeStringResource("'+key+'")','original resource '+key) for key in ['common_back','common_retry','dynamic_detail_load_failed']]
    replacements += [
        ('ArticleRepository.getArticleDetail(articleId)','bindings.load(articleId)','sole original owned Article protocol'),
        ('import com.android.purebilibili.data.repository.ArticleRepository\n','','no singleton transport'),
        ('    BackHandler {','    BackHandler(enabled = true) {','required existing Root back admission'),
        ('navigationBarsBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()','navigationBarsBottom = 0.dp','Windows client area has no Android nav-bar inset'),
    ]
    for before,after,label in replacements: ui=change(ui,before,after,label)
    put('com/android/purebilibili/feature/article/ArticleDetailScreen.kt',ui)
    source=sources[paths[1]]; mask=lexer.masked(source); match=re.search(r'(?m)^fun\s+ArticleDetailSkeleton\s*\(',mask); assert match
    start=source.rfind('@Composable\n',0,match.start()); assert start>=0
    param=mask.index('(',match.start()); param_end=lexer.balanced(mask,param); body=mask.index('{',param_end); end=lexer.balanced(mask,body,'{','}'); block=source[start:end]
    bodies.append(dict(path=paths[1],name='ArticleDetailSkeleton',startLine=source[:start].count('\n')+1,endLine=source[:end].count('\n')+1,sha256Lf=hashlib.sha256(block.encode()).hexdigest()))
    header='''package com.android.purebilibili.core.ui.skeleton
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyListItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
'''
    put('com/android/purebilibili/core/ui/skeleton/DesktopOriginalArticleSkeleton.kt',header+block+'\n')
    put('source-identity.json',json.dumps(identities,indent=2)+'\n'); put('selected-source-bodies.json',json.dumps(bodies,indent=2)+'\n'); put('platform-edits.json',json.dumps(edits,ensure_ascii=False,indent=2)+'\n')
    return sorted(wide(output).rglob('*.kt'))
if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser(); parser.add_argument('--repo',required=True,type=Path); parser.add_argument('--output',required=True,type=Path)
    args=parser.parse_args(); generate(args.repo,args.output)
