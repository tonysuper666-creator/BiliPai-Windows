package categoryproof

import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.feature.category.CategoryViewModel
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.ui.DesktopCategoryEnvironment
import com.bilipai.desktop.ui.DesktopOriginalHomePreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private data class Request(val tid: Int, val page: Int,
    val result: CompletableDeferred<Result<List<VideoItem>>> = CompletableDeferred(),
    val finished: CompletableDeferred<Unit> = CompletableDeferred())

fun main(args: Array<String>) = runBlocking {
    val root = Path.of(args.single())
    val globalScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val globalStore = DesktopPluginStore(root)
    // Real existing global decoder/store; device default is explicit fixture input. No data-saver use is expected.
    val settings = DesktopOriginalHomePreferences.create(globalStore, globalScope,
        defaultTabletUseSidebar = true, isMobileNetwork = { error("Unused network capability was queried") })
    val homeAlive = AtomicBoolean(true)
    val gate = Any()
    val calls = AtomicInteger()
    val requests = Channel<Request>(Channel.UNLIMITED)
    var assertions = 0
    fun verify(value: Boolean) { check(value); assertions++ }
    suspend fun next() = withTimeout(5_000) { requests.receive() }
    suspend fun settle(predicate: () -> Boolean) = withTimeout(5_000) { while (!predicate()) delay(5) }
    val environment = DesktopCategoryEnvironment(13, settings, globalScope, homeAlive::get,
        commitIfCurrent = { action -> synchronized(gate) { if (homeAlive.get()) { action(); true } else false } },
        getRegionVideos = { tid, page ->
            val request = Request(tid, page)
            calls.incrementAndGet(); requests.send(request)
            try { withContext(NonCancellable) { request.result.await() } }
            finally { request.finished.complete(Unit) }
        })
    val vm = CategoryViewModel(environment)
    try {
        vm.loadCategory(13)
        verify(vm.isLoading.value)
        vm.loadMore(); vm.loadMore()
        val initial = next()
        verify(initial.tid == 13 && initial.page == 1)
        verify(calls.get() == 1)
        initial.result.complete(Result.success(listOf(VideoItem(bvid = "BVfirst", cid = 101))))
        settle { !vm.isLoading.value }
        verify(vm.videos.value.single().bvid == "BVfirst")

        vm.loadMore()
        val old = next()
        verify(old.tid == 13 && old.page == 2)
        vm.refresh()
        val replacement = next()
        verify(replacement.tid == 13 && replacement.page == 2)
        verify(vm.isRefreshing.value && !vm.isLoading.value)
        old.result.complete(Result.success(listOf(VideoItem(bvid = "BVlate", cid = 202))))
        old.finished.await(); delay(40)
        verify(vm.isRefreshing.value)
        verify(vm.videos.value.single().bvid == "BVfirst")
        replacement.result.complete(Result.success(listOf(VideoItem(bvid = "BVrefresh", cid = 303))))
        settle { !vm.isRefreshing.value }
        verify(vm.videos.value.single().bvid == "BVrefresh")
        verify(vm.error.value == null)

        vm.loadMore()
        val retired = next()
        verify(retired.tid == 13 && retired.page == 3)
        vm.close() // Category exit; the retained Home/global preference owner remains active.
        verify(homeAlive.get() && globalScope.isActive)
        retired.result.complete(Result.success(listOf(VideoItem(bvid = "BVclosed", cid = 404))))
        retired.finished.await(); delay(40)
        verify(vm.videos.value.single().bvid == "BVrefresh")
        verify(runCatching { vm.loadCategory(24) }.isFailure)
        verify(calls.get() == 4)
        println("RESULT assertions=$assertions groups=3 HTTP=false GUI=false prospectiveCategory=true actualRootAccountIntegration=false")
        for (type in listOf(CategoryViewModel::class.java, DesktopCategoryEnvironment::class.java,
            DesktopOriginalHomePreferences::class.java, DesktopPluginStore::class.java, VideoItem::class.java)) {
            println("ORIGIN ${type.name} ${type.protectionDomain.codeSource.location}")
        }
    } finally { vm.close(); requests.close(); globalScope.cancel(); globalScope.coroutineContext[Job]?.join() }
}
