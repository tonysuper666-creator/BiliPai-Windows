from pathlib import Path
import hashlib, json, subprocess, sys, textwrap, difflib
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
MAIN=HERE.parents[2]
CANDIDATE=MAIN.parent/'BiliPai-v023'
BASE='app/src/main/java/com/android/purebilibili/'
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
def write(p,s):
 p=safe(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
def lf(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n').replace('\r','\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
paths=[BASE+'feature/home/WeeklySeriesScreen.kt',BASE+'feature/home/WeeklySeriesViewModel.kt',BASE+'data/repository/VideoRepository.kt']
pins={p:sha(lf(CANDIDATE/p)) for p in paths}
for p in paths:
 upstream=subprocess.check_output(['git','show','3d5d19a2f994daccd0e2f8b5f522b6d82f43d589:'+p],cwd=CANDIDATE).decode().replace('\r\n','\n')
 assert sha(upstream)==pins[p],p
 write(HERE/'original'/Path(p).name,upstream)
generator="""'''Stable original Weekly UI/state/repository selection; no direct leaf producers.'''
from pathlib import Path
import hashlib, importlib.util, sys, textwrap
sys.dont_write_bytecode=True
PINS=__PINS__
def generate(repo:Path,output:Path):
 spec=importlib.util.spec_from_file_location('weekly_ast',repo/'desktop/tools/sync-upstream.py')
 parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
 def read(p):
  s=(repo/p).read_text(encoding='utf-8').replace('\\r\\n','\\n').replace('\\r','\\n')
  assert hashlib.sha256(s.encode()).hexdigest()==PINS[p],p
  return s
 def emit(p,path,body):
  target=output/path;target.parent.mkdir(parents=True,exist_ok=True)
  target.write_text('// GENERATED from '+p+'; do not edit.\\n// LF-normalized SHA-256: '+PINS[p]+'\\n'+body,encoding='utf-8',newline='\\n')
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
 for line in ['import androidx.lifecycle.compose.collectAsStateWithLifecycle\\n','import androidx.lifecycle.viewmodel.compose.viewModel\\n']:
  assert s.count(line)==1;s=s.replace(line,'')
 assert s.count('viewModel: WeeklySeriesViewModel = viewModel(),')==1
 s=s.replace('viewModel: WeeklySeriesViewModel = viewModel(),','viewModel: WeeklySeriesViewModel,')
 assert s.count('collectAsStateWithLifecycle()')==1
 s=s.replace('collectAsStateWithLifecycle()','collectAsState()')
 emit(p,'com/android/purebilibili/feature/home/DesktopOriginalWeeklySeriesScreen.kt',s)
 p='app/src/main/java/com/android/purebilibili/feature/home/WeeklySeriesViewModel.kt';s=read(p)
 for line in ['import androidx.lifecycle.SavedStateHandle\\n','import androidx.lifecycle.ViewModel\\n','import androidx.lifecycle.viewModelScope\\n','import com.android.purebilibili.data.repository.VideoRepository\\n']:
  assert s.count(line)==1;s=s.replace(line,'')
 s=s.replace('import kotlinx.coroutines.Job','import kotlinx.coroutines.Job\\nimport kotlinx.coroutines.CoroutineScope\\nimport kotlinx.coroutines.CancellationException\\nimport kotlinx.coroutines.ensureActive\\nimport kotlin.coroutines.coroutineContext')
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
 body='package com.android.purebilibili.data.repository\\nimport com.android.purebilibili.data.model.response.*\\nimport com.android.purebilibili.core.network.BilibiliApi\\nimport kotlinx.coroutines.Dispatchers\\nimport kotlinx.coroutines.CancellationException\\nimport kotlinx.coroutines.withContext\\ninternal class DesktopWeeklySeriesProtocol(private val api: BilibiliApi) {\\n'+ '\\n\\n'.join(textwrap.indent(m,'    ') for m in methods)+'\\n}\\n'
 emit(p,'com/android/purebilibili/data/repository/DesktopWeeklySeriesProtocol.kt',body)
if __name__=='__main__':generate(Path(sys.argv[1]).resolve(),Path(sys.argv[2]).resolve())
""".replace('__PINS__',repr(pins))
write(HERE/'prepared/desktop/tools/extract-stable-weekly-series.py',generator)
write(HERE/'source-pins.json',json.dumps(pins,indent=2))
import importlib.util
spec=importlib.util.spec_from_file_location('weekly_generator',HERE/'prepared/desktop/tools/extract-stable-weekly-series.py')
g=importlib.util.module_from_spec(spec);spec.loader.exec_module(g);g.generate(CANDIDATE,HERE/'generated')

platform='''package com.android.purebilibili.feature.home

import com.android.purebilibili.data.model.response.PopularSeriesOneData
import com.android.purebilibili.data.model.response.PopularSeriesPeriod

/** Platform port to the existing shared DiscoveryRepository; no client or cache. */
interface DesktopWeeklySeriesRequests {
    suspend fun getWeeklyPeriods(): Result<List<PopularSeriesPeriod>>
    suspend fun getWeeklyPeriod(number: Int): Result<PopularSeriesOneData>
}

/** Original SavedStateHandle's one route value, retained by Root BrowseMemory. */
class DesktopWeeklySeriesSavedState {
    @Volatile private var weeklyNumber: Int? = null
    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T? {
        check(key == "weeklyNumber")
        return weeklyNumber as T?
    }
    operator fun set(key: String, number: Int) {
        check(key == "weeklyNumber")
        weeklyNumber = number
    }
}
'''
write(HERE/'prepared/desktop/src/main/kotlin/com/android/purebilibili/feature/home/DesktopWeeklySeriesPlatform.kt',platform)
host='''package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.feature.home.*
import com.bilipai.desktop.data.DesktopRepository
import java.util.concurrent.atomic.AtomicBoolean

/** Original stable weekly page. Route/account lifetime owns only its load task. */
@Composable
fun DesktopWeeklySeriesScreen(
    requests: DesktopWeeklySeriesRequests,
    repository: DesktopRepository,
    initialNumber: Int? = null,
    onBack: () -> Unit,
    onVideoClick: (VideoItem, List<VideoItem>) -> Unit,
    modifier: Modifier = Modifier,
    isClosing: () -> Boolean = { false },
) {
    val account by repository.account.collectAsState()
    val epoch by repository.sessionEpochFlow.collectAsState()
    val capturedEpoch = epoch
    val capturedMid = account?.mid
    val memory = LocalDesktopBrowseMemory.current
    val savedState = remember(memory, capturedMid, capturedEpoch) {
        memory?.screen(listOf("weekly-series-number", capturedMid, capturedEpoch)) { DesktopWeeklySeriesSavedState() }
            ?: DesktopWeeklySeriesSavedState()
    }
    val scope = rememberCoroutineScope()
    val latestIsClosing by rememberUpdatedState(isClosing)
    val latestBack by rememberUpdatedState(onBack)
    val latestVideo by rememberUpdatedState(onVideoClick)
    val alive = remember(requests, repository, capturedMid, capturedEpoch) { AtomicBoolean(true) }
    val owns = remember(alive, repository, capturedMid, capturedEpoch) {
        { alive.get() && !latestIsClosing() && repository.sessionEpoch == capturedEpoch && repository.account.value?.mid == capturedMid }
    }
    val viewModel = remember(requests, savedState, scope, owns) { WeeklySeriesViewModel(savedState, requests, scope, owns) }
    DisposableEffect(viewModel) { onDispose { alive.set(false); viewModel.close() } }
    key(alive) {
        WeeklySeriesScreen(initialNumber, onBack = { if (owns()) latestBack() },
            onVideoClick = { video, list -> if (owns()) latestVideo(video, list) },
            modifier = modifier, viewModel = viewModel)
    }
}
'''
write(HERE/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopWeeklySeriesScreen.kt',host)
fragment='''
    // STABLE_WEEKLY_SERIES_MEMBERS: sole original Result protocol / shared API.
    private val weeklySeriesRequestsDelegate by lazy {
        object : com.android.purebilibili.feature.home.DesktopWeeklySeriesRequests {
            private val original = com.android.purebilibili.data.repository.DesktopWeeklySeriesProtocol(api)
            override suspend fun getWeeklyPeriods(): Result<List<PopularSeriesPeriod>> = withContext(Dispatchers.IO) {
                try { repository.ensureSession(); original.getWeeklyPeriods() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { Result.failure(failure) }
            }
            override suspend fun getWeeklyPeriod(number: Int): Result<PopularSeriesOneData> = withContext(Dispatchers.IO) {
                try { repository.ensureSession(); original.getWeeklyPeriod(number) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { Result.failure(failure) }
            }
        }
    }
    fun weeklySeriesRequests(): com.android.purebilibili.feature.home.DesktopWeeklySeriesRequests = weeklySeriesRequestsDelegate

'''
write(HERE/'prepared/fragments/DesktopDiscoveryWeeklyMembers.fragment',fragment)
path='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDiscoveryRepository.kt'
original=lf(CANDIDATE/path);anchor='    suspend fun weeklyPeriods(): List<PopularSeriesPeriod>'
assert original.count(anchor)==1
desired=original.replace(anchor,fragment+anchor,1)
write(HERE/'patches/repository.patch',''.join(difflib.unified_diff(original.splitlines(True),desired.splitlines(True),fromfile='a/'+path,tofile='b/'+path)))
write(HERE/'references/DesktopDiscoveryRepository.kt',desired)
discoveryPath='desktop/src/main/kotlin/com/bilipai/desktop/ui/DiscoveryScreens.kt'
discovery=lf(CANDIDATE/discoveryPath)
changes=[
 ('onRestart: (() -> Unit)? = null, isClosing: () -> Boolean = { false }) {',
  'onRestart: (() -> Unit)? = null, isClosing: () -> Boolean = { false }, onWeeklyBack: (() -> Unit)? = null) {'),
 ('regionId, onPlayQueue, runtime, capturedMid, feedback)',
  'regionId, onPlayQueue, runtime, capturedMid, feedback, isClosing, onWeeklyBack)'),
 ('runtime: DesktopPluginRuntime?, accountMid: Long?, feedbackSource: StateFlow<TodayWatchFeedbackSnapshot>) {',
  'runtime: DesktopPluginRuntime?, accountMid: Long?, feedbackSource: StateFlow<TodayWatchFeedbackSnapshot>,\n    isClosing: () -> Boolean, onWeeklyBack: (() -> Unit)?) {'),
 ('    var mode by state::mode\n', '''    var mode by state::mode
    // The dedicated original stable page owns its state, period selection and grid.
    // Return before legacy weekly period/feed effects so only one loader runs.
    if (mode == DiscoverySection.WEEKLY) {
        DesktopWeeklySeriesScreen(discovery.weeklySeriesRequests(), repository, initialNumber = state.period,
            onBack = { onWeeklyBack?.invoke() ?: run { mode = DiscoverySection.POPULAR } },
            onVideoClick = { video, videos -> onPlayQueue(videos.map(::discoveryVideoCard), discoveryVideoCard(video)) },
            modifier = Modifier.fillMaxSize(), isClosing = isClosing)
        return
    }
''')]
changed=discovery
for old,new in changes:
 assert changed.count(old)==1,old
 changed=changed.replace(old,new,1)
write(HERE/'patches/discovery-consumer.patch',''.join(difflib.unified_diff(discovery.splitlines(True),changed.splitlines(True),fromfile='a/'+discoveryPath,tofile='b/'+discoveryPath)))
write(HERE/'references/DiscoveryScreens.kt',changed)
shellPath='desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt'
shell=lf(CANDIDATE/shellPath)
old='onRestart = onRestart, isClosing = isClosing)\n                            section == DesktopSection.LIVE'
assert shell.count(old)==1
new='onRestart = onRestart, isClosing = isClosing,\n                                    onWeeklyBack = { navigate(DesktopSection.POPULAR) })\n                            section == DesktopSection.LIVE'
shellChanged=shell.replace(old,new,1)
write(HERE/'patches/root-routing.patch',''.join(difflib.unified_diff(shell.splitlines(True),shellChanged.splitlines(True),fromfile='a/'+shellPath,tofile='b/'+shellPath)))
write(HERE/'candidate-baselines.json',json.dumps({path:{'baseLfSha256':sha(original),'desiredLfSha256':sha(desired)},
 discoveryPath:{'baseLfSha256':sha(discovery),'desiredLfSha256':sha(changed)},
 shellPath:{'baseLfSha256':sha(shell),'desiredLfSha256':sha(shellChanged)}},indent=2))
print('prepared original Weekly UI/state/protocol plus Root-owned repository append')
