#!/usr/bin/env python3
"""Complete original Category UI/VM; only lifecycle, owned requests and global settings are adapted."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse, hashlib, json
SOURCE='app/src/main/java/com/android/purebilibili/feature/category/CategoryScreen.kt'
def read(path):return Path(path).read_bytes().decode('utf-8').replace('\r\n','\n')
def sha(text):return hashlib.sha256(text.encode()).hexdigest()
def write(path,text):
 Path(path).parent.mkdir(parents=True,exist_ok=True);Path(path).write_text(text,encoding='utf-8',newline='\n')

def generate(repo,output,standalone=False):
 original=read(_desktop_canonical_source(repo, SOURCE))
 # Fixed identity is filled from git's exact v0.2.3 blob by the task source audit.
 assert sha(original)==ORIGINAL_SHA, 'Category source drift'
 text=original;changes=[]
 def replace(before,after):
  nonlocal text
  assert text.count(before)==1,(before[:100],text.count(before))
  text=text.replace(before,after);changes.append(dict(before=before,after=after))
 replace('import androidx.compose.ui.platform.LocalContext\n','')
 replace('import androidx.lifecycle.ViewModel\n','')
 replace('import androidx.lifecycle.viewModelScope\n','import kotlinx.coroutines.*\n')
 replace('import androidx.lifecycle.viewmodel.compose.viewModel\n','')
 replace('import com.android.purebilibili.core.store.SettingsManager\n','')
 replace('import com.android.purebilibili.data.repository.VideoRepository\n','')
 replace('class CategoryViewModel : ViewModel() {', '''class CategoryViewModel internal constructor(
    private val environment: com.bilipai.desktop.ui.DesktopCategoryEnvironment,
) : AutoCloseable {
    private val ownerLock = Any()
    @Volatile private var closed = false
    private val ownerJob = SupervisorJob(environment.parentScope.coroutineContext[Job])
    private val viewModelScope = CoroutineScope(environment.parentScope.coroutineContext + ownerJob)
    private var requestId = 0L
    private var requestJob: Job? = null
    internal val settings get() = environment.settings
    private fun owned() = !closed && ownerJob.isActive && environment.stillOwned()
    private fun commit(block: () -> Unit): Boolean {
        var applied = false
        environment.commitIfCurrent {
            synchronized(ownerLock) { if (owned()) { block(); applied = true } }
        }
        return applied
    }
    internal fun commitNavigation(action: () -> Unit) = commit(action)
    override fun close() {
        synchronized(ownerLock) { closed = true; requestId++ }
        ownerJob.cancel()
    }''')
 start=text.index('    fun loadCategory(tid: Int) {');end=text.index('    private fun loadVideos(',start)
 before=text[start:end]
 originalLoadBody=before[before.index('        if '):before.rfind('    }')]
 # Original same-TID reuse/reset sequence retained. Root uses a distinct owner for another route.
 replace(before,'''    fun loadCategory(tid: Int) {
        require(tid == environment.tid) { "A different category needs a new navigation owner" }
        var changed = false
        commit {
            if (currentTid != tid || _videos.value.isEmpty()) {
                currentTid = tid
                currentPage = 1
                hasMore = true
                _videos.value = emptyList()
                changed = true
            }
        }
        if (changed) loadVideos(replace = true)
    }
    
''')
 start=text.index('    private fun loadVideos(');end=text.index('    fun loadMore()',start)
 before=text[start:end]
 success=before[before.index('                    if (newVideos.isEmpty())'):before.index('\n                }\n                .onFailure')]
 successCheck=success
 after='''    private fun loadVideos(replace: Boolean = false, isRefresh: Boolean = false) {
        var previous: Job? = null
        var replacement: Job? = null
        commit {
            if (!_isRefreshing.value && (isRefresh || (!_isLoading.value && hasMore))) {
                // Capture immutable route/page at synchronous admission, before launch can be queued.
                val capturedTid = currentTid
                val capturedRequest = ++requestId
                val pageToFetch = if (isRefresh) {
                    resolveReplaceRefreshPage(nextLoadPage = currentPage, hasMore = hasMore)
                } else {
                    currentPage
                }
                previous = requestJob
                _isRefreshing.value = isRefresh
                _isLoading.value = !isRefresh
                _error.value = null
                replacement = viewModelScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        currentCoroutineContext().ensureActive()
                        environment.getRegionVideos(capturedTid, pageToFetch)
                            .onSuccess { newVideos ->
                                currentCoroutineContext().ensureActive()
                                commit {
                                    if (capturedRequest == requestId && capturedTid == currentTid) {
'''+success+'''
                                    }
                                }
                            }
                            .onFailure { e ->
                                if (e is CancellationException) throw e
                                currentCoroutineContext().ensureActive()
                                commit { if (capturedRequest == requestId && capturedTid == currentTid) _error.value = e.message ?: "加载失败" }
                            }
                    } finally {
                        // Cleanup may run after this request cancels; it can only clear its own busy state.
                        commit {
                            if (capturedRequest == requestId && capturedTid == currentTid) {
                                _isLoading.value = false
                                _isRefreshing.value = false
                                requestJob = null
                            }
                        }
                    }
                }
                requestJob = replacement
            }
        }
        // Cancellation and dispatch are outside Root/VM admission monitors.
        previous?.cancel()
        replacement?.start()
    }
    
'''
 assert successCheck in after
 replace(before,after)
 replace('    onVideoClick: (String, Long, String, Boolean) -> Unit = { _, _, _, _ -> },','    onVideoClick: (String, Long, String, Boolean) -> Unit,')
 replace('    isReturningFromVideoDetail: Boolean = false,','    isReturningFromVideoDetail: Boolean,')
 replace('    isQuickReturningFromVideoDetail: Boolean = false,','    isQuickReturningFromVideoDetail: Boolean,')
 replace('    viewModel: CategoryViewModel = viewModel()','    viewModel: CategoryViewModel')
 replace('    val context = LocalContext.current\n','')
 replace('''SettingsManager.getHomeSettings(context).collectAsStateWithLifecycle(initialValue = HomeSettings()
    )''','viewModel.settings.homeSettings.collectAsStateWithLifecycle()')
 replace('''SettingsManager
        .getHomeFeedCardStyle(context)
        .collectAsStateWithLifecycle(initialValue = HomeFeedCardStyle.BILIPAI)''','viewModel.settings.homeFeedCardStyle.collectAsStateWithLifecycle()')
 replace('SettingsManager.getShowOnlineCount(context).collectAsStateWithLifecycle(initialValue = false)','viewModel.settings.showOnlineCount.collectAsStateWithLifecycle()')
 inverse=text
 for row in reversed(changes):
  if row['after']:inverse=inverse.replace(row['after'],row['before'])
 normalized=original
 for row in changes:
  if not row['after']:
   assert row['before'].startswith('import ') or row['before']=='    val context = LocalContext.current\n'
   normalized=normalized.replace(row['before'],'')
 assert inverse==normalized, 'Non-whitelisted Category source adaptation'
 target=Path(output)/'com/android/purebilibili/feature/category/CategoryScreen.kt'
 write(target,'// Original '+SOURCE+'\n// Original LF SHA256 '+ORIGINAL_SHA+'\n'+text)
 inventory=dict(path=SOURCE,sha256LF=ORIGINAL_SHA,mode='extracted',fullSourceSelected=True,
  originalLineCount=len(original.splitlines()),originalPageSuccessBodyUnchanged=True,originalAppendRefreshEmptyAlgorithmsUnchanged=True,
  immutableRoute=True,requiredCallbacks=True,changes=changes,inverseNormalizedOriginalEqual=True,generatedSha256LF=sha(read(target)))
 write(Path(output)/'category-page-producer-inventory.json',json.dumps([inventory],ensure_ascii=False,indent=2)+'\n')
 return [target]

ORIGINAL_SHA='fc7bb877d961633bb0a3fe8aaa4ceb15476b394d0f2d3a3ffb3bc72acbaffc96'
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--repo',required=True);p.add_argument('--output',required=True);p.add_argument('--standalone',action='store_true');args=p.parse_args()
 print('Original Category outputs',len(generate(args.repo,args.output,args.standalone)))
