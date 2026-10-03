'''Stable original Weekly UI/state/repository selection; no direct leaf producers.'''
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import hashlib, importlib.util, sys, textwrap
sys.dont_write_bytecode=True
PINS={'app/src/main/java/com/android/purebilibili/feature/home/WeeklySeriesScreen.kt': '9c62f6eec189e5214dbaae7224922e00bfc716100c72423cd48d2d7676d634ca', 'app/src/main/java/com/android/purebilibili/feature/home/WeeklySeriesViewModel.kt': '0d62f01e9fb55151333b5c7de117f0251e47d926f414901a517bf3bdac30f8df', 'app/src/main/java/com/android/purebilibili/data/repository/VideoRepository.kt': 'acf05cba9a89666533378eef35a21609484a02ccd874a460c259c3f3363d1e64'}
def generate(repo:Path,output:Path):
 spec=importlib.util.spec_from_file_location('weekly_ast',repo/'desktop/tools/sync-upstream.py')
 parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
 def read(p):
  s=(_desktop_canonical_source(repo, p)).read_text(encoding='utf-8').replace('\r\n','\n').replace('\r','\n')
  assert hashlib.sha256(s.encode()).hexdigest()==PINS[p],p
  return s
 def emit(p,path,body):
  target=output/path;target.parent.mkdir(parents=True,exist_ok=True)
  target.write_text('// GENERATED from '+p+'; do not edit.\n// LF-normalized SHA-256: '+PINS[p]+'\n'+body,encoding='utf-8',newline='\n')
 def fun(s,anchor):
  tokens=parser.kotlin_tokens(s);a=s.index(anchor);start=next(i for i,t in enumerate(tokens) if t[1]>=a);end=start
  while tokens[end][0]!='(':end+=1
  depth=1
  while depth:end+=1;depth+=(tokens[end][0]=='(')-(tokens[end][0]==')')
  while tokens[end][0]!='{':end+=1
  depth=1
  while depth:end+=1;depth+=(tokens[end][0]=='{')-(tokens[end][0]=='}')
  return s[tokens[start][1]:tokens[end][2]]
 p='app/src/main/java/com/android/purebilibili/feature/home/WeeklySeriesScreen.kt';s=read(p)
 for line in ['import androidx.lifecycle.compose.collectAsStateWithLifecycle\n','import androidx.lifecycle.viewmodel.compose.viewModel\n']:
  assert s.count(line)==1;s=s.replace(line,'')
 assert s.count('viewModel: WeeklySeriesViewModel = viewModel(),')==1
 s=s.replace('viewModel: WeeklySeriesViewModel = viewModel(),','viewModel: WeeklySeriesViewModel,')
 assert s.count('collectAsStateWithLifecycle()')==1
 s=s.replace('collectAsStateWithLifecycle()','collectAsState()')
 emit(p,'com/android/purebilibili/feature/home/DesktopOriginalWeeklySeriesScreen.kt',s)
 p='app/src/main/java/com/android/purebilibili/feature/home/WeeklySeriesViewModel.kt';s=read(p)
 for line in ['import androidx.lifecycle.SavedStateHandle\n','import androidx.lifecycle.ViewModel\n','import androidx.lifecycle.viewModelScope\n','import com.android.purebilibili.data.repository.VideoRepository\n']:
  assert s.count(line)==1;s=s.replace(line,'')
 s=s.replace('import kotlinx.coroutines.Job','import kotlinx.coroutines.Job\nimport kotlinx.coroutines.CoroutineScope\nimport kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.ensureActive\nimport kotlin.coroutines.coroutineContext')
 original='internal class WeeklySeriesViewModel(private val savedState: SavedStateHandle) : ViewModel() {'
 assert s.count(original)==1
 s=s.replace(original,'''internal class WeeklySeriesViewModel(
    private val savedState: DesktopWeeklySeriesSavedState,
    private val requests: DesktopWeeklySeriesRequests,
    private val desktopScope: CoroutineScope,
    private val stillOwned: () -> Boolean = { true },
) : AutoCloseable {''')
 s=s.replace('    private var initialized = false','''    private var initialized = false
    @Volatile private var closed = false
    @Volatile private var desktopGeneration = 0L
    private fun desktopOwned() = !closed && stillOwned()
    override fun close() {
        closed = true
        desktopGeneration++
        loadJob?.cancel()
    }''')
 s=s.replace('        if (initialized) return','        if (!desktopOwned() || initialized) return',1)
 anchor='''    private fun load(requested: Int?) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {'''
 assert s.count(anchor)==1
 s=s.replace(anchor,'''    private fun load(requested: Int?) {
        if (!desktopOwned()) return
        val desktopRequest = ++desktopGeneration
        loadJob?.cancel()
        loadJob = desktopScope.launch {
            fun ensureDesktopOwned() {
                if (!desktopOwned() || desktopGeneration != desktopRequest) {
                    throw CancellationException("Weekly series owner retired")
                }
            }
            coroutineContext.ensureActive()
            ensureDesktopOwned()''',1)
 for name,args in [('getWeeklyPeriods',''),('getWeeklyPeriod','number')]:
  old='VideoRepository.'+name+'('+args+').fold('
  assert s.count(old)==1
  s=s.replace(old,'requests.'+name+'('+args+').also { coroutineContext.ensureActive(); ensureDesktopOwned() }.fold(',1)
 emit(p,'com/android/purebilibili/feature/home/DesktopOriginalWeeklySeriesViewModel.kt',s)
 p='app/src/main/java/com/android/purebilibili/data/repository/VideoRepository.kt';s=read(p)
 methods=[fun(s,'suspend fun getWeeklyPeriods()'),fun(s,'suspend fun getWeeklyPeriod(number:')]
 body='package com.android.purebilibili.data.repository\nimport com.android.purebilibili.data.model.response.*\nimport com.android.purebilibili.core.network.BilibiliApi\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.withContext\ninternal class DesktopWeeklySeriesProtocol(private val api: BilibiliApi) {\n'+ '\n\n'.join(textwrap.indent(m,'    ') for m in methods)+'\n}\n'
 emit(p,'com/android/purebilibili/data/repository/DesktopWeeklySeriesProtocol.kt',body)
if __name__=='__main__':generate(Path(sys.argv[1]).resolve(),Path(sys.argv[2]).resolve())
