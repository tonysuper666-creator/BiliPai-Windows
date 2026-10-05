"""Fixed v029 Home failure/UI slice on the existing admitted Windows Home owner.

This prepares no second data source, account, reducer or request actor. Every edit
is counted and its full inverse is retained by the caller's generation receipt.
"""
import hashlib
import json
from pathlib import Path

COMMIT = "a4b77f894d0a2dd26c0b9fc144b8adb88ac05480"
BASE = "app/src/main/java/com/android/purebilibili/feature/"
PINS = {
    BASE + "home/HomeViewModel.kt": "2f9d546a8a200bc45c4c95ae8e4911e78ce37e95975c2a089af871573176ec64",
    BASE + "home/HomeUiState.kt": "7cb53d55271535fa9a8bb11527945592d0721384e7e9ce114cd448d1db8b8a23",
    BASE + "home/HomeCategoryPage.kt": "5d8ce3009fc0509114d1e974eb82c68c94df320ef7491509428be591d5f0be97",
    BASE + "common/ListLoadError.kt": "6fc1d252d9d1d02eff83cf1d9eabd6ff0a645c4876aab81fa225148119a31a32",
    "app/src/test/java/com/android/purebilibili/feature/home/HomeLoadSequencePolicyTest.kt": "eb2d55619897cc866ae0991b3715fac1217871a3d0b240a6c1ddc57af532fcb0",
}


def wide(path):
    value = str(Path(path).absolute())
    return Path(value if value.startswith("\\\\?\\") else "\\\\?\\" + value)


def sources(repo):
    root = Path(repo) / "desktop/upstream-slices/v029-home-load"
    manifest = json.loads(wide(root / "manifest.json").read_text(encoding="utf-8"))
    if manifest["commit"] != COMMIT or {r["path"] for r in manifest["sources"]} != set(PINS):
        raise ValueError("Unknown Home failure slice identity")
    result = {}
    for path, expected in PINS.items():
        raw = wide(root / path).read_bytes()
        row = next(r for r in manifest["sources"] if r["path"] == path)
        if hashlib.sha256(raw).hexdigest() != expected or row["sha256Raw"] != expected or row["bytes"] != len(raw):
            raise ValueError("Home slice raw identity changed: " + path)
        result[path] = raw.decode("utf-8").replace("\r\n", "\n")
    return result


def declaration(text, signature):
    """Top-level fixed declarations end at their original column-zero brace."""
    if text.count(signature) != 1:
        raise ValueError("Home declaration changed: " + signature)
    begin = text.index(signature)
    end = text.index("\n}", begin) + 2
    return text[begin:end]


class Delta:
    def __init__(self, body):
        self.original = body
        self.body = body
        self.edits = []

    def change(self, before, after, count=1):
        if self.body.count(before) != count:
            raise ValueError(("Home counted edit mismatch", before[:100], self.body.count(before), count))
        # Store each sequential occurrence so the complete inverse is executable.
        cursor = 0
        for _ in range(count):
            offset = self.body.index(before, cursor)
            self.body = self.body[:offset] + after + self.body[offset + len(before):]
            self.edits.append({"offset": offset, "before": before, "after": after})
            cursor = offset + len(after)

    def done(self, audits, label):
        restored = self.body
        for edit in reversed(self.edits):
            offset = edit["offset"]
            if restored[offset:offset + len(edit["after"])] != edit["after"]:
                raise ValueError("Home inverse mismatch")
            restored = restored[:offset] + edit["before"] + restored[offset + len(edit["after"]):]
        if restored != self.original:
            raise ValueError("Home full inverse failed")
        if audits is not None:
            audits.append({"output": label, "beforeSha256LF": hashlib.sha256(self.original.encode()).hexdigest(),
                           "afterSha256LF": hashlib.sha256(self.body.encode()).hexdigest(), "edits": self.edits})
        return self.body


# A permission carried only by the existing load coroutine. The same owner and
# account admission remain authoritative. No scope, job or network is created.
VM_REQUEST_MEMBERS = '''    @Volatile private var desktopHomeSelection = Any()
    @Volatile private var desktopHomeLoad: DesktopHomeLoadRequest? = null
    @Volatile private var desktopHomeRefresh: DesktopHomeLoadRequest? = null
    private val desktopHomeLoadLocal = ThreadLocal<DesktopHomeLoadRequest?>()
    private class DesktopHomeLoadRequest(
        val selection: Any, val category: HomeCategory,
        val popular: PopularSubCategory, val live: LiveSubCategory,
        val caller: Job,
    )
    private val desktopHomeLoadKey = object : kotlin.coroutines.CoroutineContext.Key<DesktopHomeLoadElement> {}
    private inner class DesktopHomeLoadElement(val request: DesktopHomeLoadRequest) :
        kotlinx.coroutines.ThreadContextElement<DesktopHomeLoadRequest?> {
        override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = desktopHomeLoadKey
        override fun updateThreadContext(context: kotlin.coroutines.CoroutineContext) =
            desktopHomeLoadLocal.get().also { desktopHomeLoadLocal.set(request) }
        override fun restoreThreadContext(context: kotlin.coroutines.CoroutineContext, oldState: DesktopHomeLoadRequest?) {
            if (oldState == null) desktopHomeLoadLocal.remove() else desktopHomeLoadLocal.set(oldState)
        }
    }
    private fun desktopHomeLoadCurrent(request: DesktopHomeLoadRequest, requireCaller: Boolean = true): Boolean {
        val state = _uiState.value
        return desktopHomeLoad === request && desktopHomeSelection === request.selection &&
            state.currentCategory == request.category && state.popularSubCategory == request.popular &&
            state.liveSubCategory == request.live && (!requireCaller || request.caller.isActive)
    }
    private suspend fun desktopBeginHomeLoad(category: HomeCategory): DesktopHomeLoadRequest {
        val caller = checkNotNull(currentCoroutineContext()[Job])
        var captured: DesktopHomeLoadRequest? = null
        if (!ownedCommit {
            val state = _uiState.value
            if (state.currentCategory != category) throw CancellationException("Home selection retired")
            captured = DesktopHomeLoadRequest(desktopHomeSelection, category,
                state.popularSubCategory, state.liveSubCategory, caller)
            desktopHomeLoad = captured
        }) throw CancellationException("Home owner retired")
        return checkNotNull(captured)
    }
    private fun desktopRetireHomeSelection() {
        if (!ownedCommit {
            val previous = desktopHomeLoad
            desktopHomeSelection = Any()
            desktopHomeLoad = null
            desktopHomeRefresh = null
            _isRefreshing.value = false
            if (previous != null) desktopClearHomeLoading(previous)
        }) throw CancellationException("Home owner retired")
    }
    private fun desktopClearHomeLoading(request: DesktopHomeLoadRequest) {
        if (request.category == HomeCategory.POPULAR)
            updatePopularCategoryState(request.popular) { it.copy(isLoading = false) }
        else updateCategoryState(request.category) { it.copy(isLoading = false) }
    }
    private fun desktopFinishHomeLoad(request: DesktopHomeLoadRequest) {
        // A cancelled exact caller can clean only its own current busy bit. It
        // cannot publish content/error, reset a successor, or reopen old paging.
        if (!isCurrentOwner()) return
        environment.commitIfCurrent {
            if (isCurrentOwner() && desktopHomeLoadCurrent(request, requireCaller = false)) {
                val inherited = desktopHomeLoadLocal.get()
                desktopHomeLoadLocal.remove()
                // Only exact-current busy cleanup may outlive the cancelled
                // caller. All content/error publication keeps the normal guard.
                try { desktopClearHomeLoading(request) }
                finally { if (inherited != null) desktopHomeLoadLocal.set(inherited) }
            }
        }
    }

'''


def home_vm_delta(repo, body, audits=None):
    raw = sources(repo)
    vm = raw[BASE + "home/HomeViewModel.kt"]
    d = Delta(body)
    pure = declaration(vm, "internal fun applyHomeFeedLoadFailure(")
    d.change("internal fun shouldRefreshHomeUserInfoAfterFeedLoad", pure + "\n\ninternal fun shouldRefreshHomeUserInfoAfterFeedLoad")
    d.change("        hasMore = snapshot.hasMore,\n        isLoading = false,\n        error = null\n", "        hasMore = snapshot.hasMore,\n        isLoading = false,\n        error = null,\n        loadMoreError = null,\n        refreshError = null\n")
    anchor = "    private suspend fun <T> ownedRead(block:suspend()->T):T {\n"
    d.change(anchor, VM_REQUEST_MEMBERS + anchor)
    old = '    private fun ensureOwned() { if(closed.get() || !ownedJob.isActive || !environment.isCurrent())throw CancellationException("Home entry retired") }\n'
    d.change(old, '''    private fun ensureOwned() {
        if (closed.get() || !ownedJob.isActive || !environment.isCurrent()) throw CancellationException("Home entry retired")
        desktopHomeLoadLocal.get()?.let {
            if (!desktopHomeLoadCurrent(it)) throw CancellationException("Home load selection/caller retired")
        }
    }
''')
    # Retire before the asynchronous new category publishes; the identity also
    # protects A -> B -> A, where comparing only enum values is insufficient.
    d.change("        if (currentState.currentCategory == category) return\n", "        if (currentState.currentCategory == category) return\n        desktopRetireHomeSelection()\n")
    d.change("        val currentState = _uiState.value\n        if (currentState.currentCategory == category) return\n        desktopRetireHomeSelection()\n", "        var currentState = _uiState.value\n        if (currentState.currentCategory == category) return\n        desktopRetireHomeSelection()\n        currentState = _uiState.value\n")
    d.change("        if (_uiState.value.liveSubCategory == subCategory) return\n", "        if (_uiState.value.liveSubCategory == subCategory) return\n        desktopRetireHomeSelection()\n")
    d.change("        if (_uiState.value.popularSubCategory == subCategory) return\n", "        if (_uiState.value.popularSubCategory == subCategory) return\n        desktopRetireHomeSelection()\n")
    d.change("        if (current.currentCategory == category) return\n", "        if (current.currentCategory == category) return\n        desktopRetireHomeSelection()\n")
    d.change("        val current = _uiState.value\n        if (current.currentCategory == category) return\n        desktopRetireHomeSelection()\n", "        var current = _uiState.value\n        if (current.currentCategory == category) return\n        desktopRetireHomeSelection()\n        current = _uiState.value\n")
    d.change("            fetchLiveRooms(isLoadMore = false)\n", "            fetchData(isLoadMore = false, category = HomeCategory.LIVE)\n")
    d.change("    override fun refresh() = refresh(_uiState.value.currentCategory)\n", '''    private fun desktopSelectionMatches(category: HomeCategory, popular: PopularSubCategory): Boolean {
        val state = _uiState.value
        return state.currentCategory == category && (category != HomeCategory.POPULAR || state.popularSubCategory == popular)
    }
    override fun refreshIfSelected(category: HomeCategory, popular: PopularSubCategory) {
        ensureOwned()
        if (desktopSelectionMatches(category, popular)) refresh(category)
    }
    override fun loadMoreIfSelected(category: HomeCategory, popular: PopularSubCategory) {
        ensureOwned()
        if (desktopSelectionMatches(category, popular)) loadMore()
    }
    override fun refresh() = refresh(_uiState.value.currentCategory)
''')
    d.change("        if (_isRefreshing.value) return\n        viewModelScope.launch {\n            ensureOwned()\n", "        if (_isRefreshing.value) return\n        val selection = desktopHomeSelection\n        viewModelScope.launch {\n            ensureOwned()\n            if (desktopHomeSelection !== selection) return@launch\n")
    d.change("            fetchData(isLoadMore = true)\n", "            if (desktopHomeSelection !== selection) return@launch\n            fetchData(isLoadMore = true, category = currentCategory)\n")
    d.change("    override fun loadMore() {\n        ensureOwned()\n", "    override fun loadMore() {\n        ensureOwned()\n        val selection = desktopHomeSelection\n")
    signature = '''    private suspend fun fetchData(
        isLoadMore: Boolean,
        isManualRefresh: Boolean = false,
        category: HomeCategory = _uiState.value.currentCategory
    ): Int? {
'''
    d.change(signature, '''    private suspend fun fetchData(
        isLoadMore: Boolean,
        isManualRefresh: Boolean = false,
        category: HomeCategory = _uiState.value.currentCategory,
        desktopRequest: DesktopHomeLoadRequest? = null,
    ): Int? {
        val request = desktopRequest ?: desktopBeginHomeLoad(category)
        return try {
            withContext(DesktopHomeLoadElement(request)) {
                ensureOwned()
                fetchDataCaptured(isLoadMore, isManualRefresh, category)
            }
        } finally { desktopFinishHomeLoad(request) }
    }

    private suspend fun fetchDataCaptured(
        isLoadMore: Boolean, isManualRefresh: Boolean, category: HomeCategory,
    ): Int? {
''')
    d.change("it.copy(isLoading = true, error = null)", "it.copy(isLoading = true, error = null, loadMoreError = null, refreshError = null)", count=2)
    d.change('''                oldState.copy(
                    isLoading = false,
                    error = if (!isLoadMore && oldState.videos.isEmpty()) error.message ?: "网络错误" else null,
                    hasMore = if (shouldKeepHomeCategoryAutoPagingAfterFailure(isLoadMore)) {
                        oldState.hasMore
                    } else {
                        false
                    }
                )''', '''                applyHomeFeedLoadFailure(oldState, isLoadMore, error.message ?: "网络错误")''')
    d.change('''                        oldState.copy(
                            isLoading = false,
                            error = if (oldState.videos.isEmpty()) error.message ?: "请先登录" else null
                        )''', '''                        applyHomeFeedLoadFailure(oldState, isLoadMore = false, message = error.message ?: "请先登录")''')
    d.change('''                oldState.copy(
                    isLoading = false,
                    error = if (!isLoadMore && oldState.videos.isEmpty()) error.message ?: "请先登录" else null
                )''', '''                applyHomeFeedLoadFailure(oldState, isLoadMore, error.message ?: "请先登录")''')
    d.change('''                    oldState.copy(
                        followedLiveRooms = followedRooms.toImmutableList(),
                        isLoading = false,
                        error = if (followedRooms.isEmpty()) e.message ?: "网络错误" else null
                    )''', '''                    applyHomeFeedLoadFailure(oldState, isLoadMore = false, message = e.message ?: "网络错误")''')
    d.change("                updateCategoryState(HomeCategory.LIVE) { it.copy(isLoading = false) }\n", '                updateCategoryState(HomeCategory.LIVE) { applyHomeFeedLoadFailure(it, isLoadMore = true, message = e.message ?: "网络错误") }\n')
    # All successful/legitimately-empty feed writes clear the two transient errors.
    begin = d.body.index("    private suspend fun fetchDataCaptured(")
    end = d.body.index("    //  提取用户信息获取逻辑", begin)
    fragment = d.body[begin:end]
    fragment = fragment.replace("error = null,\n", "error = null,\n                        loadMoreError = null, refreshError = null,\n")
    fragment = fragment.replace('error = if (!isLoadMore && oldState.videos.isEmpty()) "没有更多内容了" else null,', 'error = if (!isLoadMore && oldState.videos.isEmpty()) "没有更多内容了" else null,\n                        loadMoreError = null, refreshError = null,')
    fragment = fragment.replace('error = if (fullVideos.isEmpty()) "暂无关注动态，请先关注一些UP主" else null,', 'error = if (fullVideos.isEmpty()) "暂无关注动态，请先关注一些UP主" else null,\n                            loadMoreError = null, refreshError = null,')
    fragment = fragment.replace('error = if (!isLoadMore && mergedVideos.isEmpty()) "暂无关注动态，请先关注一些UP主" else null,', 'error = if (!isLoadMore && mergedVideos.isEmpty()) "暂无关注动态，请先关注一些UP主" else null,\n                    loadMoreError = null, refreshError = null,')
    fragment = fragment.replace('error = "暂无直播",', 'error = "暂无直播",\n                            loadMoreError = null, refreshError = null,')
    fragment = fragment.replace('it.copy(isLoading = false, hasMore = false)', 'it.copy(isLoading = false, hasMore = false, loadMoreError = null, refreshError = null)')
    d.change(d.body[begin:end], fragment)
    # Guard refresh metadata/undo in the same actual request, including finally.
    d.change("            _isRefreshing.value = true\n            val refreshingCategory = category\n            syncCurrentCategoryForRefresh(refreshingCategory)\n", "            val refreshingCategory = category\n            syncCurrentCategoryForRefresh(refreshingCategory)\n            val request = desktopBeginHomeLoad(refreshingCategory)\n            try { withContext(DesktopHomeLoadElement(request)) {\n            ownedCommit { desktopHomeRefresh = request; _isRefreshing.value = true }\n")
    d.change("                category = refreshingCategory\n            )\n", "                category = refreshingCategory,\n                desktopRequest = request,\n            )\n")
    failed = '''            val refreshed = if (refreshingCategory == HomeCategory.POPULAR)
                _uiState.value.popularCategoryStates[request.popular] else _uiState.value.categoryStates[refreshingCategory]
            if (refreshed?.refreshError != null || refreshed?.error != null) {
                _undoSnapshot = null
                cancelUndoDismiss()
                _uiState.value = _uiState.value.copy(undoAvailable = false, refreshMessage = null,
                    refreshNewItemsCount = null)
                return@withContext
            }
'''
    d.change("            //  数据加载完成后再更新 refreshKey，避免闪烁\n", failed + "            //  数据加载完成后再更新 refreshKey，避免闪烁\n")
    d.change("            _isRefreshing.value = false\n        }\n    }\n\n    private fun syncCurrentCategoryForRefresh", '''            } } finally {
                if (isCurrentOwner()) ownedCommit {
                    if (desktopHomeRefresh === request) {
                        desktopHomeRefresh = null
                        _isRefreshing.value = false
                    }
                }
            }
        }
    }

    private fun syncCurrentCategoryForRefresh''')
    # Cancellation cleanup outside the request must not publish stale errors to
    # another category. Selection retirement already cleared its exact busy bit.
    d.change('''                updateCategoryState(category) { state ->
                    state.copy(isLoading = false, error = null)
                }
                _uiState.value = _uiState.value.copy(isLoading = false, error = null)
                throw error''', '''                throw error''')
    d.change('''                if (_uiState.value.currentCategory != category) {
                    if (category == HomeCategory.POPULAR) {
                        updatePopularCategoryState(currentState.popularSubCategory) { state ->
                            state.copy(isLoading = false, error = null)
                        }
                    } else {
                        updateCategoryState(category) { state ->
                            state.copy(isLoading = false, error = null)
                        }
                    }
                }
                throw error''', '''                throw error''')
    return d.done(audits, "DesktopOriginalHomeViewModel.kt")


def home_page_delta(repo, name, body, audits=None):
    raw = sources(repo)
    d = Delta(body)
    if name == "HomeUiState.kt":
        fixed = raw[BASE + "home/HomeUiState.kt"]
        additions = "    val loadMoreError: String? = null,\n    val refreshError: String? = null,\n"
        if additions not in fixed:
            raise ValueError("Original CategoryContent error fields changed")
        d.change("    val pageIndex: Int = 1, //  保存分页索引\n", additions + "    val pageIndex: Int = 1, //  保存分页索引\n")
    elif name == "HomeCategoryPage.kt":
        fixed = raw[BASE + "home/HomeCategoryPage.kt"]
        d.change("    onLoadMore: () -> Unit,\n", "    onLoadMore: () -> Unit,\n    onRetryLoadMore: () -> Unit,\n    onRetryRefresh: () -> Unit,\n    isActive: Boolean,\n")
        d.change("    val cardReflowState = remember(category)", "    val latestCategoryState by rememberUpdatedState(categoryState)\n    val latestIsActive by rememberUpdatedState(isActive)\n    val latestLoadMore by rememberUpdatedState(onLoadMore)\n    val cardReflowState = remember(category)")
        d.change("                isLoading = categoryState.isLoading,\n                hasMore = categoryState.hasMore,", "                isLoading = latestCategoryState.isLoading,\n                hasMore = latestCategoryState.hasMore,")
        d.change("                hasVisibleContent = categoryState.videos.isNotEmpty() ||\n                    categoryState.liveRooms.isNotEmpty() ||\n                    categoryState.followedLiveRooms.isNotEmpty()", "                hasVisibleContent = latestCategoryState.videos.isNotEmpty() ||\n                    latestCategoryState.liveRooms.isNotEmpty() ||\n                    latestCategoryState.followedLiveRooms.isNotEmpty()")
        d.change("            shouldRequestHomeCategoryLoadMore(\n", "            latestIsActive && latestCategoryState.loadMoreError == null && shouldRequestHomeCategoryLoadMore(\n")
        d.change("        if (shouldLoadMore) onLoadMore()\n", "        if (shouldLoadMore && latestIsActive && latestCategoryState.loadMoreError == null) latestLoadMore()\n")
        refresh_begin = fixed.index("        categoryState.refreshError?.let { message ->")
        refresh_end = fixed.index("\n        if (category == HomeCategory.LIVE)", refresh_begin)
        block = fixed[refresh_begin:refresh_end]
        d.change("        if (category == HomeCategory.LIVE)", block + "\n        if (category == HomeCategory.LIVE)")
        retry_begin = fixed.index("        val loadMoreError = categoryState.loadMoreError")
        retry_end = fixed.index("\n             item(", fixed.index("        } else if (categoryState.isLoading || categoryState.hasMore)", retry_begin))
        retry = fixed[retry_begin:retry_end].replace("ListLoadError(", "com.android.purebilibili.feature.common.ListLoadError(")
        block = block.replace("ListLoadError(", "com.android.purebilibili.feature.common.ListLoadError(")
        # Qualify the single other error component; it has one existing producer.
        d.change(fixed[refresh_begin:refresh_end], block)
        d.change("        if (categoryState.isLoading || categoryState.hasMore) {", retry)
    elif name == "DesktopOriginalHomeScreen.kt":
        d.change("                        onLoadMore = onPageLoadMore,\n", '''                        onLoadMore = onPageLoadMore,
                        onRetryLoadMore = onPageLoadMore,
                        onRetryRefresh = {
                            viewModel.refreshIfSelected(category, selectedPopularSubCategory)
                        },
                        isActive = currentCategory == category &&
                            (category != HomeCategory.POPULAR || popularSubCategory == selectedPopularSubCategory),
''')
        d.change("                val onLoadMoreCallback = remember(viewModel) { { viewModel.loadMore() } }", '''                val onLoadMoreCallback = remember(viewModel, category, popularSubCategory) {
                    {
                        viewModel.loadMoreIfSelected(category, popularSubCategory)
                    }
                }''')
    else:
        return body
    return d.done(audits, name)
