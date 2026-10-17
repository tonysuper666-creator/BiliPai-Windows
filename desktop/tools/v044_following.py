"""Whole current original following page/paging on the retained Windows owner."""
from pathlib import Path
import json
from v044_favorite_detail import inverse_proof
from v044_fans import fixed, sha, need, COMMIT, MANIFEST_SHA256

def bind_screen(repo, drop_logs):
    original = fixed(repo)['FollowingListScreen.kt']
    body = original
    def replace(before, after, count=1):
        nonlocal body
        need(body.count(before) == count, 'Current following platform seam changed: '+before)
        body = body.replace(before, after)
    for line in ['import androidx.lifecycle.ViewModel\n', 'import androidx.lifecycle.viewModelScope\n',
                 'import androidx.lifecycle.viewmodel.compose.viewModel\n',
                 'import com.android.purebilibili.core.network.NetworkModule\n',
                 'import com.android.purebilibili.data.repository.ActionRepository\n']:
        replace(line, '')
    replace('import androidx.compose.ui.platform.LocalContext', 'import coil3.compose.LocalPlatformContext as LocalContext')
    replace('import androidx.lifecycle.compose.collectAsStateWithLifecycle', 'import androidx.compose.runtime.collectAsState as collectAsStateWithLifecycle')
    replace('import kotlinx.coroutines.Job\n', 'import kotlinx.coroutines.Job\nimport kotlinx.coroutines.ensureActive\n')
    replace('class FollowingListViewModel : ViewModel() {',
            'class FollowingListViewModel(environment: DesktopFollowingEnvironment) : DesktopFollowingScopedOwner(environment) {')
    replace('viewModel: FollowingListViewModel = viewModel()', 'viewModel: FollowingListViewModel')
    need(body.count('NetworkModule.api.getFollowings(') == 2, 'Original following request topology changed')
    body = body.replace('NetworkModule.api.getFollowings(', 'environment.getFollowings(')
    replace('NetworkModule.appContext ?: return false', 'environment.cacheContext')
    replace('NetworkModule.appContext ?: return', 'environment.cacheContext')
    need('ActionRepository.' in body, 'Original following action owner changed')
    body = body.replace('ActionRepository.', 'environment.actions.')
    # Reuse the original generation, not a second request/state authority. The
    # existing Root cache permit can observe it on its IO dispatcher.
    replace('    private var followingGeneration = 0L', '''    @Volatile private var followingGeneration = 0L
    @Volatile private var cacheWriteGeneration = 0L
    private var firstPageJob: Job? = null
    private suspend fun checkRequest(generation: Long) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        environment.assertOwned()
        if (generation != followingGeneration) throw CancellationException("Following request retired")
    }''')
    replace('    fun loadFollowingList(mid: Long, forceRefresh: Boolean = false) {',
            '    fun loadFollowingList(mid: Long, forceRefresh: Boolean = false) {\n        environment.assertOwned()')
    replace('        val generation = ++followingGeneration\n', '        val generation = ++followingGeneration\n        firstPageJob?.cancel()\n')
    replace('        viewModelScope.launch {\n            if (!restoredFromCache)',
            '        firstPageJob = viewModelScope.launch {\n            if (!restoredFromCache)')
    replace('                val response = environment.getFollowings(mid, pn = 1, ps = 50)',
            '                val response = environment.getFollowings(mid, pn = 1, ps = 50)\n                checkRequest(generation)')
    replace('            val response = environment.getFollowings(mid, pn = page, ps = 50)',
            '            val response = environment.getFollowings(mid, pn = page, ps = 50)\n            checkRequest(generation)')
    replace('            val merged = mergeFollowingUsersOffMain(latest.users, incoming)',
            '            val merged = mergeFollowingUsersOffMain(latest.users, incoming)\n            checkRequest(generation)')
    for name in ['loadAllForSearch', 'loadMore']:
        replace('    fun '+name+'() {', '    fun '+name+'() {\n        environment.assertOwned()')
    replace('if (generation == followingGeneration) loadRemainingPagesJob = null',
            'if (generation == followingGeneration && loadRemainingPagesJob === kotlinx.coroutines.currentCoroutineContext()[Job]) loadRemainingPagesJob = null', 2)
    replace('        val context = environment.cacheContext\n        val snapshotUsers',
            '        val generation = followingGeneration\n        val writeGeneration = ++cacheWriteGeneration\n'
            '        val context = environment.cacheContext.forRequest { generation == followingGeneration && writeGeneration == cacheWriteGeneration }\n'
            '        val snapshotUsers')
    body = drop_logs(body)
    body, brand = bind_brand(body)
    need('emitBrandFeedback = false' in body and 'loadAllRemainingPages(' not in body,
         'Current explicit paging or aggregate feedback contract changed')
    return body, dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        completeOriginalFollowingVmAndUi=True, originalGenerationReused=True,
        rootCacheRequestAndWriteAdmissionRetained=True, existingRetainedScopeApiAndActionsUsed=True,
        firstPageOnlyAtStartup=True, explicitOriginalLoadAllForSearch=True,
        originalToWindows=inverse_proof(original, body), windowsBrandSuccessAdaptation=brand)

def group_source(repo):
    return fixed(repo)['FollowingGroupPolicy.kt']

def emit_tests(repo, output):
    sources = fixed(repo)
    receipts = []
    for name, count in [('FollowingGroupPolicyTest.kt', 8), ('FollowingBatchSelectionPolicyTest.kt', 14)]:
        original = sources[name]
        need(original.count('@Test') == count, 'Current original following regression inventory changed')
        target = Path(output)/'com/android/purebilibili/feature/following'/name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(original, encoding='utf8', newline='\n')
        receipts.append(dict(source=name, sha256=sha(original), casesSelected=count, wholeOriginalSuite=True, testsExecuted=False))
    (Path(output)/'following-original-test-proof.json').write_text(json.dumps(receipts,indent=2)+'\n',encoding='utf8',newline='\n')
    return receipts


def bind_brand(body):
    from v029_brand_success import replace as brand_replace, proof as brand_proof, checked
    checked()
    before_brand = body
    edits = []
    body = brand_replace(body, '        _isBatchUnfollowing.value = true\n',
        '        val brandOrigin = environment.favorites.captureBrandFeedback()\n        _isBatchUnfollowing.value = true\n',
        'same actual 批量取关 caller captures Root origin', edits)
    body = brand_replace(body, 'com.android.purebilibili.core.events.BrandSuccessEvents.followChanged(\n',
        'environment.favorites.confirmBrandFollow(brandOrigin,\n', 'complete current aggregate success through captured Root owner', edits)
    brand = brand_proof(before_brand, body, edits)
    return body, brand
