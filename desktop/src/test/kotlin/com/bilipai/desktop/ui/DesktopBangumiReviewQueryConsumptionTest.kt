package com.bilipai.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.network.*
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.bangumi.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.*
import kotlinx.coroutines.*
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.Collections
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Mount the generated production screen and its real request adapter, with an
 * uncancellable API transport so obsolete replies can deliberately arrive late.
 * ImageComposeScene renders offscreen; no Window, network or user profile is used.
 */
@OptIn(ExperimentalComposeUiApi::class)
class DesktopBangumiReviewQueryConsumptionTest {
    private class Call(val name: String, val args: List<Any?>, val epoch: Int, val continuation: Continuation<Any?>) {
        @Volatile var taken = false
        private var done = false
        @Synchronized fun complete(value: Any?) { check(!done); done = true; continuation.resume(value) }
        @Synchronized fun cancel() {
            if (!done) { done = true; continuation.resumeWithException(CancellationException("fixture closed")) }
        }
        fun page(label: String? = null, next: String = "", id: Long = 51L, code: Int = 0) = complete(
            BangumiReviewListResponse(code = code, message = if (code == 0) "" else "controlled failure",
                data = BangumiReviewListData(next = next, list = label?.let { listOf(
                    BangumiReviewItem(review_id = id, content = it, stat = BangumiReviewStat())
                ) }.orEmpty()))
        )
    }

    private class Fixture {
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> failures += e })
        val calls = Collections.synchronizedList(mutableListOf<Call>())
        val feedback = Collections.synchronizedList(mutableListOf<String>())
        val web = mutableListOf<Pair<String, String>>()
        @Volatile var epoch = 1
        var mounted by mutableStateOf(true)
        var media by mutableStateOf(19L)
        val api = Proxy.newProxyInstance(BangumiApi::class.java.classLoader, arrayOf(BangumiApi::class.java)) { _, method, args ->
            @Suppress("UNCHECKED_CAST")
            val call = Call(method.name, args!!.dropLast(1), epoch, args.last() as Continuation<Any?>)
            calls += call
            COROUTINE_SUSPENDED
        } as BangumiApi
        private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.singleOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Unused ${T::class.java.simpleName}"
                else -> error("Unexpected port ${method.name}")
            }
        } as T
        fun environment(owner: Int): DesktopOriginalBangumiPagesEnvironment {
            val owns = { epoch == owner }
            val commit: (() -> Unit) -> Boolean = { action -> if (owns()) { action(); true } else false }
            val repository = DesktopOriginalBangumiHubRepository(api, unused<BilibiliApi>(), unused<SearchApi>(), { "synthetic-csrf" }, { 71L })
            val vm = BangumiHubViewModel(DesktopBangumiHubEnvironment(repository, scope, { true }, owns, commit))
            return DesktopOriginalBangumiPagesEnvironment(vm,
                DesktopOriginalBangumiPagesRequests(api, repository, { "synthetic-csrf" }, { null }, owns, owns),
                DesktopOriginalBangumiReviewRequests(api, { "synthetic-csrf" }, owns, owns),
                unused<DesktopOriginalVideoProgressPort>(), scope, commit, owns, { feedback += it }, false,
                { BangumiFollowSession(null, null) })
        }
        var owner by mutableStateOf(environment(1))
        val dir = Files.createTempDirectory("bangumi-review-queries")
        val context = DesktopPluginContext(DesktopPluginStore(dir.resolve("preferences")))
        val listeners = mutableSetOf<DesktopHomeWindowBackgroundPort.Listener>()
        val background = object : DesktopHomeWindowBackgroundPort {
            override val isInBackground = false
            override fun addListener(listener: DesktopHomeWindowBackgroundPort.Listener) { check(listeners.add(listener)) }
            override fun removeListener(listener: DesktopHomeWindowBackgroundPort.Listener) { check(listeners.remove(listener)) }
        }
        val platform = DesktopHomePlatform(false, true, false, false, false, false, background, 0f, false)
        val tabs = DesktopLiquidTabSettings(context.store)
        var now = 0L
        val scene = ImageComposeScene(1000, 1200, Density(1f)) {
            MaterialTheme {
                CompositionLocalProvider(LocalDesktopOriginalBangumiPagesEnvironment provides owner,
                    LocalDesktopUpstreamAndroidContext provides context.asAndroidContext(),
                    LocalDesktopHomePlatform provides platform, LocalDesktopLiquidTabSettings provides tabs,
                    LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled = false),
                    LocalComponentMotionEnabled provides false) {
                    // Deliberately no host key: the screen must isolate media/account state itself.
                    if (mounted) BangumiReviewScreen(media, "Reviews", {}, onOpenWeb = { url, title -> web += url to title })
                }
            }
        }
        fun frames() { repeat(8) { Snapshot.sendApplyNotifications(); now += 20_000_000; scene.render(now).close() } }
        fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
        fun nodes() = scene.semanticsOwners.flatMap { tree(it.unmergedRootSemanticsNode) }
        fun labeled(label: String) = nodes().filter { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
        }
        fun click(label: String, render: Boolean = true) {
            var node: SemanticsNode? = assertNotNull(labeled(label).firstOrNull(), label)
            while (node != null && !node.config.contains(SemanticsActions.OnClick)) node = node.parent
            assertTrue(assertNotNull(node, label).config[SemanticsActions.OnClick].action!!.invoke())
            if (render) frames()
        }
        suspend fun until(predicate: () -> Boolean) {
            withTimeout(15_000) { while (!predicate()) { frames(); yield() } }
            frames()
        }
        suspend fun text(label: String) = until { labeled(label).isNotEmpty() }
        fun snapshot(): List<Call> = synchronized(calls) { calls.toList() }
        suspend fun request(type: String, sort: Int = 0, cursor: String = "", mediaId: Long = media, account: Int = epoch): Call {
            val name = when (type) { "long" -> "getBangumiLongReviews"; "short" -> "getBangumiShortReviews"; else -> type }
            // A canceled prefetch may still be held by the hostile transport.
            // Capture it before switching when testing late delivery; a new
            // selected query must receive the currently active continuation.
            fun find() = snapshot().firstOrNull { !it.taken && it.continuation.context[Job]?.isCancelled != true &&
                it.name == name && it.epoch == account &&
                (type !in listOf("long", "short") || (it.args[0] == mediaId && it.args[2] == sort && it.args[3] == cursor)) }
            until { find() != null }
            return assertNotNull(find()).also { it.taken = true }
        }
        suspend fun firstLong(): Call {
            until { snapshot().isNotEmpty() }
            // Reach the same query on the old SHORT-default producer as well,
            // so race regressions fail on their behavior, independently of default.
            if (snapshot().first().name == "getBangumiShortReviews") {
                request("short").page("short default"); text("short default"); click("长评")
            }
            return request("long")
        }
        suspend fun loaded(long: String = "long default", short: String = "short default") {
            firstLong().page(long); text(long)
            if (snapshot().first().name == "getBangumiLongReviews") request("short").page(short)
            frames()
        }
        suspend fun close() {
            scene.close()
            snapshot().forEach { it.cancel() }
            scope.cancel(); scope.coroutineContext[Job]!!.join()
            snapshot().forEach { it.continuation.context[Job]?.join() }
            assertTrue(listeners.isEmpty())
            assertTrue(failures.isEmpty(), failures.toString())
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun defaultsToLongAndKeepsTheShortComposerAndExistingLongWebRoute() = runBlocking {
        val f = Fixture()
        try {
            f.until { f.snapshot().isNotEmpty() }
            assertEquals("getBangumiLongReviews", f.snapshot().first().name)
            f.loaded()
            f.click("写长评")
            assertEquals("写长评", f.web.single().second)
            assertTrue(f.web.single().first.startsWith("https://member.bilibili.com/article-text/mobile?theme="))
            assertTrue(f.web.single().first.endsWith("&media_id=19"))
            f.click("短评"); f.text("short default")
            f.click("写短评")
            val input = assertNotNull(f.nodes().firstOrNull { it.config.contains(SemanticsActions.SetText) })
            assertTrue(input.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("  composed review  ")))
            f.frames(); f.click("发布")
            val post = f.request("postBangumiShortReview")
            assertEquals(listOf<Any?>(19L, 8, "composed review"), post.args.take(3))
            assertEquals("synthetic-csrf", post.args.last())
            post.complete(SimpleApiResponse())
            f.request("short").page("after post"); f.text("after post")
            assertEquals(listOf("已发布短评"), f.feedback.toList())
        } finally { f.close() }
    }

    @Test fun eachTypeRemembersItsSortAndCompletedQueriesAreServedFromCache() = runBlocking {
        val f = Fixture()
        try {
            f.loaded()
            f.click("短评"); f.text("short default")
            f.click("最新"); f.request("short", 1).page("short latest"); f.text("short latest")
            f.request("long", 1).page("long latest"); f.frames()
            f.click("长评"); f.text("long default")
            f.click("最新"); f.text("long latest")
            f.click("短评"); f.text("short latest")
            f.click("默认"); f.text("short default")
            assertEquals(4, f.snapshot().size, "cache hits must not request another first page")
        } finally { f.close() }
    }

    @Test fun rapidTypeAndSortChangesRejectLateInitialAndPrefetchReplies() = runBlocking {
        val f = Fixture()
        try {
            val oldLong = f.firstLong()
            f.click("短评"); val oldShort = f.request("short")
            f.click("最新"); f.request("short", 1).page("selected short latest"); f.text("selected short latest")
            oldLong.page("obsolete long"); oldShort.page("obsolete short")
            f.until { listOf(oldLong, oldShort).all { it.continuation.context[Job]?.isCompleted == true } }
            assertTrue(f.labeled("selected short latest").isNotEmpty(), "late initial replies replaced the selected sort")
            val prefetch = f.request("long", 1)
            f.click("默认"); f.request("short").page("selected short default"); f.text("selected short default")
            prefetch.page("obsolete prefetch")
            f.until { prefetch.continuation.context[Job]?.isCompleted == true }
            assertTrue(f.labeled("selected short default").isNotEmpty())
            for (label in listOf("obsolete long", "obsolete short", "obsolete prefetch")) assertTrue(f.labeled(label).isEmpty())
            f.click("长评"); f.request("long").page("fresh long"); f.text("fresh long")
            assertTrue(f.feedback.isEmpty())
        } finally { f.close() }
    }

    @Test fun latePaginationCannotAppendToAnotherSortAndCachedCursorResumesItsOwnQuery() = runBlocking {
        val f = Fixture()
        try {
            f.firstLong().page("first page", next = "opaque-next", id = 61)
            f.text("first page")
            val oldPage = f.request("long", cursor = "opaque-next")
            f.click("最新"); f.request("long", 1).page("latest page", id = 71); f.text("latest page")
            oldPage.page("obsolete append", id = 62)
            f.until { oldPage.continuation.context[Job]?.isCompleted == true }
            assertTrue(f.labeled("obsolete append").isEmpty())
            assertTrue(f.labeled("latest page").isNotEmpty())
            f.click("默认"); f.text("first page")
            val resumed = f.request("long", cursor = "opaque-next")
            assertEquals(listOf<Any?>(19L, 20, 0, "opaque-next", "666.19"), resumed.args)
            resumed.page("second page", id = 63); f.text("second page")
            assertTrue(f.labeled("first page").isNotEmpty())
            f.click("最新"); f.text("latest page")
            f.click("默认"); f.text("second page")
            assertEquals(2, f.snapshot().count { it.name == "getBangumiLongReviews" && it.args[3] == "opaque-next" })
        } finally { f.close() }
    }

    @Test fun switchingAwayAndBackBeforeRecompositionStillStartsANewQueryGeneration() = runBlocking {
        val f = Fixture()
        try {
            val obsolete = f.firstLong()
            f.click("短评", render = false)
            f.click("长评", render = false)
            f.frames()
            assertTrue(assertNotNull(obsolete.continuation.context[Job]).isCancelled)
            f.request("long").page("fresh generation"); f.text("fresh generation")
            obsolete.page("obsolete generation")
            f.until { obsolete.continuation.context[Job]?.isCompleted == true }
            assertTrue(f.labeled("fresh generation").isNotEmpty())
            assertTrue(f.labeled("obsolete generation").isEmpty())
        } finally { f.close() }
    }

    @Test fun failedQueriesRetryButSuccessfulEmptyPagesRemainCached() = runBlocking {
        val f = Fixture()
        try {
            f.firstLong().page(code = -1); f.text("controlled failure")
            f.request("short").page()
            f.click("重试"); f.request("long").page(code = -1); f.text("controlled failure")
            f.click("短评"); f.text("还没有点评")
            f.click("长评"); f.request("long").page(); f.text("还没有点评")
            val callsAfterSuccessfulEmptyPages = f.snapshot().size
            f.click("短评"); f.text("还没有点评")
            f.click("长评"); f.text("还没有点评")
            assertEquals(callsAfterSuccessfulEmptyPages, f.snapshot().size, "successful empty queries must remain cached")
        } finally { f.close() }
    }

    @Test fun changingMediaOrAccountOwnerRetiresRequestsAndDropsAllCachedQueries() = runBlocking {
        val f = Fixture()
        try {
            val oldMedia = f.firstLong()
            f.media = 20; f.frames()
            f.request("long", mediaId = 20).page("media20 account1"); f.text("media20 account1")
            oldMedia.page("media19 late")
            f.until { oldMedia.continuation.context[Job]?.isCompleted == true }
            assertTrue(f.labeled("media20 account1").isNotEmpty(), "another media's reply replaced the current list")
            val oldAccount = f.request("short", mediaId = 20)
            f.epoch = 2; f.owner = f.environment(2); f.frames()
            f.request("long", mediaId = 20, account = 2).page("media20 account2"); f.text("media20 account2")
            f.request("short", mediaId = 20, account = 2).page("account2 short")
            oldAccount.page("account1 late")
            f.until { oldAccount.continuation.context[Job]?.isCompleted == true }
            assertTrue(f.labeled("media20 account2").isNotEmpty())
            for (label in listOf("media20 account1", "media19 late", "account1 late")) assertTrue(f.labeled(label).isEmpty())
            f.click("短评"); f.text("account2 short")
            assertTrue(f.feedback.isEmpty())
        } finally { f.close() }
    }

    @Test fun pendingLikeCannotRestoreAnOldCacheAndUnmountCancelsThePageAction() = runBlocking {
        val f = Fixture()
        try {
            f.loaded()
            f.click("点赞"); val like = f.request("likeBangumiReview")
            assertEquals(listOf<Any?>(19L, 2, 51L, "synthetic-csrf"), like.args)
            f.click("短评"); f.text("short default")
            f.click("长评"); f.request("long").page("before like committed"); f.text("before like committed")
            like.complete(SimpleApiResponse())
            f.request("long").page("after like committed"); f.text("after like committed")
            f.click("点赞"); val retired = f.request("likeBangumiReview")
            val job = assertNotNull(retired.continuation.context[Job])
            f.mounted = false; f.frames(); assertTrue(job.isCancelled)
            retired.complete(SimpleApiResponse())
            withTimeout(15_000) { job.join() }
            assertTrue(f.feedback.isEmpty())
        } finally { f.close() }
    }
}
