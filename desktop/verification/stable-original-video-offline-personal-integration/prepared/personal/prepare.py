from pathlib import Path
import hashlib, importlib.util, json, os, re, subprocess, textwrap
HERE=Path(__file__).resolve().parent
MAIN=HERE.parents[2]
REPO=MAIN.parent/'BiliPai-v023'
PIN='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=os.path.abspath(p); return Path(s if s.startswith(PREFIX) else PREFIX+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,s):safe(p).parent.mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
spec=importlib.util.spec_from_file_location('parser',REPO/'desktop/tools/extract-upstream-dynamic-reply-protocol.py')
parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
rows=[]
def source(rel):
 p='app/src/main/java/com/android/purebilibili/'+rel+'.kt'
 s=read(REPO/p);b=subprocess.check_output(['git','show',PIN+':'+p],cwd=REPO).replace(b'\r\n',b'\n')
 assert s.encode()==b,p
 rows.append(dict(path=p,commit=PIN,sha256LfUtf8=hashlib.sha256(b).hexdigest()))
 return s
def emit(name,s):
 p=HERE/'prepared/selected/com/bilipai/desktop/ui'/name
 write(p,s);rows[-1].setdefault('outputs',[]).append(dict(path=p.relative_to(HERE).as_posix(),sha256LfUtf8=hashlib.sha256(s.encode()).hexdigest()))
s=source('navigation/AppNavigation')
anchor=s.index('BiliPaiNavEntryContentRole.HISTORY ->')
start=s.index('                                    onVideoClick = { lookupKey, cid, cover, isVertical ->',anchor)
brace=s.index('{',start);end=parser.balanced(parser.masked(s),brace,'{','}')
block=textwrap.dedent(s[brace:end]).strip()
block=block.replace('pushNavigation3Route(', 'platform.pushRoute(').replace('pushNavigation3Key(', 'platform.push(').replace('navigateToVideoInNavigation3(', 'platform.video(').replace('historyNavigationScope.launch {','historyNavigationScope.launch {\n    if (!stillOwned()) return@launch').replace('resolveArticleNavigationTarget(articleId)','platform.articleTarget(articleId)')
body='''package com.bilipai.desktop.ui
import com.android.purebilibili.feature.list.*
import com.android.purebilibili.navigation.ScreenRoutes
import com.android.purebilibili.navigation3.*
import kotlinx.coroutines.*
import com.bilipai.desktop.ui.ArticleNavigationTarget

/** Complete pinned AppNavigation HISTORY video callback. Only concrete Root calls/scope
 * are parameters; business dispatch, CID, resume and vertical rules remain original. */
internal fun desktopOriginalHistoryVideoClick(
    historyViewModel: HistoryViewModel,
    historyNavigationScope: CoroutineScope,
    stillOwned: () -> Boolean,
    platform: DesktopPersonalListNavigation,
): (String, Long, String, Boolean) -> Unit {
    val original: (String, Long, String, Boolean) -> Unit = '''+block+'''
    return { key, cid, cover, vertical ->
        if (stillOwned()) original(key, cid, cover, vertical)
    }
}
'''
emit('DesktopOriginalHistoryNavigation.kt',body)
s=source('feature/search/SearchArticleNavigationPolicy')
selected=[]
for name in ['ArticleNavigationTarget','buildArticleWebUrl','resolveArticleNavigationTargetFromRedirect']:
 mask=parser.masked(s);m=re.search(r'(?m)^(?:internal )?(?:sealed interface|fun) '+name+r'\b',mask);assert m,name
 a=m.start();b=parser.balanced(mask,mask.index('{',m.end()),'{','}');selected.append(s[a:b])
emit('DesktopOriginalHistoryArticlePolicy.kt','package com.bilipai.desktop.ui\n'+ '\n\n'.join(selected)+'\n')
for rel in ['feature/list/CommonListScreen','feature/list/ListViewModel','data/repository/HistoryRepository','data/repository/LikedVideosRepository','feature/list/HistoryNavigationPolicy','core/refresh/HistoryRefreshBus','core/refresh/WatchLaterRefreshBus','data/model/response/HistoryModels','core/network/ApiClient']:
 source(rel);rows[-1]['mode']='reference: actual installed sole producer; no new output'
write(HERE/'source-inventory.json',json.dumps(rows,indent=2)+'\n')
print('selected original callback and three article policy declarations; existing full UI/VM referenced')
