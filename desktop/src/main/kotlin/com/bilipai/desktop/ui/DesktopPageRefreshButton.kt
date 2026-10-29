package com.bilipai.desktop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.LocalBottomBarContentPadding
import java.awt.Component
import java.awt.Container
import java.awt.EventQueue
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ResponseBody.Companion.toResponseBody
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.platform.LocalLayoutDirection
import okhttp3.Interceptor
import okhttp3.Response
import com.bilipai.desktop.diagnostics.DesktopDiagnosticsBridge
import com.bilipai.desktop.ui.kit.DesktopTooltipBox
import com.bilipai.desktop.ui.kit.LocalDesktopKitReducedMotion
import com.bilipai.desktop.ui.kit.desktopKitHairlineBorder
import com.bilipai.desktop.ui.kit.desktopKitInteraction
import top.yukonga.miuix.kmp.basic.TooltipAnchorPosition

/*
 * The desktop refresh button (TON-1026). Pull to refresh is a touch gesture: a mouse wheel or a mouse drag does not produce the
 * overscroll it waits for, so on Windows it is unreliable. Every list that upstream refreshes with a pull gets this button in the
 * bottom right corner instead, and the gesture itself is switched off. Two places draw it and nothing else does:
 *
 *   - the build-time patch of the upstream AdaptivePullToRefreshBox (upstream-core/patches/design-system/.../AdaptivePullToRefreshBox.kt.patch)
 *     calls DesktopRefreshButton inside the box content, so every page that used the pull box has the button, and a page that
 *     migrates from a legacy copy to the upstream file keeps it;
 *   - the desktop-owned dynamic feed (DesktopDynamicTimeline.kt), which never had a pull box.
 *
 * The model below is pure so DesktopPageRefreshButtonTest can check it without a window: a click is ignored while a refresh runs
 * and during the short window between the click and the moment the owner reports isRefreshing, so a double click starts one refresh.
 */

internal const val DESKTOP_REFRESH_BUTTON_SIZE_DP = 44
internal const val DESKTOP_REFRESH_BUTTON_MARGIN_DP = 20
internal const val DESKTOP_REFRESH_BUTTON_GAP_DP = 12

/**
 * Work to do before the list's own refresh starts, provided by a host that knows how to scroll its lists (Home: the pager's
 * scroll to top). The refreshed rows then appear at the top. Null where the list is not behind such a host.
 */
internal val LocalDesktopRefreshPrelude = staticCompositionLocalOf<(() -> Unit)?> { null }

/** What the back-to-top buttons and the refresh button share: the lift that puts the back-to-top button above the refresh slot. */
internal const val DESKTOP_REFRESH_STACK_LIFT_DP = DESKTOP_REFRESH_BUTTON_SIZE_DP + DESKTOP_REFRESH_BUTTON_GAP_DP

/** How long a click is held as "refreshing" while the owner has not yet reported isRefreshing (a refresh that the owner declines never reports it). */
internal const val DESKTOP_REFRESH_PENDING_TIMEOUT_MS = 1_500L

/** Space a list keeps free at its end so that its last row, and the menu of a card in the last column, can be scrolled clear of the button. */
internal const val DESKTOP_REFRESH_SCROLL_RESERVE_DP = DESKTOP_REFRESH_BUTTON_MARGIN_DP + DESKTOP_REFRESH_BUTTON_SIZE_DP + DESKTOP_REFRESH_BUTTON_GAP_DP

internal const val DESKTOP_REFRESH_LABEL = "刷新"
internal const val DESKTOP_REFRESH_SHORTCUT = "F5"
internal const val DESKTOP_REFRESH_SPIN_PERIOD_MS = 900

/**
 * The click bookkeeping of one button. pendingSinceMs is the time of an accepted click that the owner has not yet answered with
 * isRefreshing = true; a running refresh (refreshing) or a click younger than the timeout makes the button busy.
 */
internal data class DesktopRefreshButtonState(val pendingSinceMs: Long? = null) {
    fun busy(refreshing: Boolean, nowMs: Long): Boolean =
        refreshing || (pendingSinceMs != null && nowMs - pendingSinceMs in 0 until DESKTOP_REFRESH_PENDING_TIMEOUT_MS)

    /** The state after a click, and whether the click starts a refresh. */
    fun click(refreshing: Boolean, nowMs: Long): Pair<DesktopRefreshButtonState, Boolean> =
        if (busy(refreshing, nowMs)) this to false else copy(pendingSinceMs = nowMs) to true

    /** The owner took the refresh over (isRefreshing became true), so the pending hold is no longer needed. */
    fun onRefreshing(refreshing: Boolean): DesktopRefreshButtonState =
        if (refreshing && pendingSinceMs != null) copy(pendingSinceMs = null) else this
}

/**
 * The one switch of the pull gesture (TON-1026, item 3). The patched AdaptivePullToRefreshBox asks it with the caller's `enabled`
 * and mounts the pull container only when the answer is true. A mouse wheel that keeps scrolling up at the top of a list sends
 * no overscroll, so on the desktop the gesture can neither fire nor hang half way; it stays off and the button does the work.
 */
@Suppress("UNUSED_PARAMETER")
internal fun desktopPullGestureEnabled(requested: Boolean): Boolean = false

/** The toast text of a refresh that failed: the first line of the reason when there is one. */
internal fun desktopRefreshFailureMessage(failure: Throwable?): String {
    val reason = failure?.message.orEmpty().lineSequence().firstOrNull().orEmpty().trim().take(60)
    return if (reason.isEmpty()) "刷新失败，请稍后重试" else "刷新失败：$reason"
}

/** The diagnostic log lines of a refresh (tag DesktopRefresh): who asked, and how long the owner reported isRefreshing. */
internal const val DESKTOP_REFRESH_LOG_TAG = "DesktopRefresh"
internal fun desktopRefreshStartLog(source: String): String = "刷新开始：来源=$source"
internal const val DESKTOP_REFRESH_SHORTCUT_SOURCE = "F5/Ctrl+R"
internal fun desktopRefreshRunningLog(): String = "刷新进行中：页面已报告正在刷新"
internal fun desktopRefreshEndLog(startedMs: Long, endedMs: Long): String = "刷新结束：用时=${(endedMs - startedMs).coerceAtLeast(0)}ms"

/**
 * One refresh button as F5 and Ctrl+R see it. trigger is the same function the click runs (busy gate, scroll prelude, the page's own
 * onRefresh, log line), so a key press and a click cannot differ. visible is false while the button is clipped away (a pager page
 * that is not showing) or not laid out.
 */
internal class DesktopRefreshTarget(val trigger: (source: String) -> Boolean) {
    @Volatile var visible: Boolean = false
    /** The box the button sits in, in root pixels: where a scroll to the top has to be aimed. */
    @Volatile var boxBounds: Rect = Rect.Zero
}

/**
 * The refresh buttons that are composed now, oldest first. The key goes to the newest visible one: a sheet or a page pushed over
 * another list was composed later and is the one the user sees. A target that is busy still counts as the answer (the key is
 * consumed and nothing starts twice).
 */
internal class DesktopRefreshTargets {
    private val targets = mutableListOf<DesktopRefreshTarget>()
    @Synchronized fun register(target: DesktopRefreshTarget) { targets += target }
    @Synchronized fun unregister(target: DesktopRefreshTarget) { targets.remove(target) }
    @Synchronized fun topmost(): DesktopRefreshTarget? = targets.lastOrNull { it.visible }
    /** True when a visible button took the key (whether or not it started a refresh). */
    fun refreshTopmost(source: String): Boolean = topmost()?.let { it.trigger(source); true } ?: false
}

/** The registry of the window shell, and the page the shell says is showing (null: the shortcuts are off, as in a detached window). */
internal object DesktopRefreshRegistry {
    val shared = DesktopRefreshTargets()
    @Volatile var pageProvider: () -> DesktopShellPage? = { null }
}

/**
 * Pages whose F5 / Ctrl+R reach a refresh button: the Home and Dynamic pages (the shell table) and every other list page (the host's
 * fallback). The player page owns its keys and the search page has its own, so there the button must not promise F5.
 */
internal fun desktopRefreshShortcutPage(page: DesktopShellPage?): Boolean =
    page == DesktopShellPage.HOME || page == DesktopShellPage.DYNAMIC || page == DesktopShellPage.OTHER

/** The F5 or Ctrl+R chord of a key event reduced to data, with no other modifier. */
internal fun desktopRefreshChord(data: DesktopShellKeyData): Boolean =
    data.keyDown && !data.alt && !data.shift && !data.meta &&
        ((data.key == "F5" && !data.ctrl) || (data.key == "R" && data.ctrl))

/**
 * Scrolls whatever list a refresh box holds back to its top, for pages that do not hand their list state to the box. The desktop
 * implementation sends the list the same mouse wheel a user would turn, aimed at the middle of the box, so it works for any
 * Compose scrollable without the page's cooperation. Home has its own pager request (LocalDesktopRefreshPrelude) and does not use it.
 */
internal fun interface DesktopRefreshScroller {
    /** True when a scroll was sent; boxInRoot is the refresh box in root pixels. */
    fun scrollToTop(boxInRoot: Rect): Boolean
}

internal val LocalDesktopRefreshScroller = staticCompositionLocalOf<DesktopRefreshScroller?> { null }

/** The wheel notches of the one upward scroll: far more than any feed is long, so it ends at the top. */
internal const val DESKTOP_REFRESH_WHEEL_NOTCHES = 4_000

/** The showing component of the window that takes the mouse wheel (the Compose layer): the largest one with a wheel listener. */
internal fun desktopFindWheelTarget(root: Container): Component? {
    var best: Component? = null
    var bestArea = 0L
    fun visit(component: Component) {
        if (component.isShowing && component.mouseWheelListeners.isNotEmpty()) {
            val area = component.width.toLong() * component.height
            if (area > bestArea) { best = component; bestArea = area }
        }
        if (component is Container) component.components.forEach(::visit)
    }
    visit(root)
    return best
}

/** The wheel event of the scroll to the top, at the given point of the target. */
internal fun desktopRefreshWheelEvent(target: Component, x: Int, y: Int): MouseWheelEvent =
    MouseWheelEvent(target, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0, x, y, 0, false,
        MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -DESKTOP_REFRESH_WHEEL_NOTCHES)

internal class DesktopAwtWheelScroller(
    private val root: () -> Container?,
    private val density: () -> Float,
    private val post: (Runnable) -> Unit = { EventQueue.invokeLater(it) },
) : DesktopRefreshScroller {
    override fun scrollToTop(boxInRoot: Rect): Boolean {
        val container = root() ?: return false
        val target = desktopFindWheelTarget(container) ?: return false
        if (boxInRoot.width <= 0f || boxInRoot.height <= 0f) return false
        val scale = density().coerceAtLeast(0.1f)
        val x = (boxInRoot.center.x / scale).toInt()
        val y = (boxInRoot.center.y / scale).toInt()
        post(Runnable { repeat(2) { target.dispatchEvent(desktopRefreshWheelEvent(target, x, y)) } })
        return true
    }
}

/**
 * One immutable operation per button invocation. Its launch context and Call.Factory bind requests before dispatch; the
 * interceptor and Retrofit decoder publish their final outcomes to that operation. Unrelated requests and closed operations
 * never contribute to a later refresh. A cancelled call and a retired request owner are not failures.
 */
internal object DesktopRefreshFailureWatch {
    class Operation {
        private var failures = 0
        private var ended = false
        @Synchronized fun active(): Boolean = !ended
        @Synchronized fun failed() { if (!ended) failures++ }
        @Synchronized fun end(): Int { ended = true; return failures }
    }
    private val current = ThreadLocal<Operation?>()
    fun context(): CoroutineContext = current.asContextElement(current.get())
    fun operation(): Operation? = current.get()
    fun <T> within(operation: Operation?, block: () -> T): T {
        val previous = current.get()
        current.set(operation)
        return try { block() } finally { current.set(previous) }
    }
    // Synchronous callers may also delimit an operation; no process-wide active counter.
    fun begin() { current.set(Operation()) }
    fun end(): Int = current.get()?.let { current.remove(); it.end() } ?: 0
    private class RequestFailure(private val operation: Operation) {
        private val counted = AtomicBoolean()
        fun failed() { if (counted.compareAndSet(false, true)) operation.failed() }
    }
    private val decodingRequest = ThreadLocal<RequestFailure?>()
    fun decodeFailed() { decodingRequest.get()?.failed() ?: current.get()?.failed() }
    private fun <T> decoding(request: RequestFailure, block: () -> T): T {
        val previous = decodingRequest.get()
        decodingRequest.set(request)
        return try { block() } finally { decodingRequest.set(previous) }
    }
    fun callFactory(client: Call.Factory): Call.Factory = Call.Factory { request ->
        val operation = (request.tag(Operation::class.java) ?: current.get())?.takeIf { it.active() }
        if (operation == null) client.newCall(request) else {
            val outcome = RequestFailure(operation)
            val tagged = request.newBuilder().tag(Operation::class.java, operation).tag(RequestFailure::class.java, outcome).build()
            val call = client.newCall(tagged)
            object : Call by call {
                override fun enqueue(callback: Callback) {
                    val caller = this
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) = decoding(outcome) {
                            if (e.message != "Canceled" && e.message != "Request owner retired") outcome.failed()
                            within(operation) { callback.onFailure(caller, e) }
                        }
                        override fun onResponse(call: Call, response: Response) = decoding(outcome) {
                            within(operation) { callback.onResponse(caller, response) }
                        }
                    })
                }
                override fun clone(): Call = callFactory(client).newCall(tagged)
            }
        }
    }
    val interceptor = Interceptor { chain ->
        val operation = (chain.request().tag(Operation::class.java) ?: current.get())?.takeIf { it.active() }
            ?: return@Interceptor chain.proceed(chain.request())
        // One terminal outcome per request, tied to the immutable initiating operation.
        val outcome = chain.request().tag(RequestFailure::class.java) ?: RequestFailure(operation)
        fun failed() = outcome.failed()
        try {
            val response = chain.proceed(chain.request())
            if (response.code >= 400) failed()
            val body = response.body
            if (body != null && body.contentType()?.subtype?.let { it == "json" || it.endsWith("+json") } == true) {
                // Complete the JSON read before returning it to Retrofit. No prefix heuristic:
                // late disconnects and malformed JSON are part of this operation's result.
                val bytes = body.use { it.bytes() }
                val replacement = response.newBuilder().body(DesktopRefreshResponseBody(bytes.toResponseBody(body.contentType()), ::failed)).build()
                try { if (desktopRefreshResponseFailed(replacement)) failed() }
                catch (failure: kotlinx.serialization.SerializationException) { failed(); throw failure }
                replacement
            } else response.newBuilder().body(DesktopRefreshResponseBody(body, ::failed)).build()
        } catch (failure: Exception) {
            if (failure !is CancellationException && failure.message != "Canceled" && failure.message != "Request owner retired") failed()
            throw failure
        }
    }
}

/** Capture the button's operation when launching into a retained VM scope, before dispatch. */
internal fun CoroutineScope.desktopRefreshLaunch(
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend CoroutineScope.() -> Unit,
): Job = launch(DesktopRefreshFailureWatch.context() + context, start, block)

internal fun desktopRefreshResponseFailed(response: Response): Boolean {
    if (response.code >= 400) return true
    val type = response.body?.contentType() ?: return false
    if (type.subtype != "json" && !type.subtype.endsWith("+json")) return false
    val json = Json.parseToJsonElement(response.peekBody(Long.MAX_VALUE).string()) as? JsonObject ?: return false
    return (json["code"] as? JsonPrimitive)?.intOrNull?.let { it != 0 } ?: false
}

internal fun desktopRefreshWatchedFailureMessage(count: Int): String =
    if (count <= 1) "刷新未完全成功：有 1 个请求失败，已保留原内容" else "刷新未完全成功：有 $count 个请求失败，已保留原内容"

/** The wording of the tooltip and the accessible name, one place for the button and for the tests. */
internal fun desktopRefreshButtonName(busy: Boolean): String = if (busy) "正在刷新" else DESKTOP_REFRESH_LABEL

/**
 * Number of refresh buttons that are composed right now. The back-to-top button of the legacy list pages sits in the same corner,
 * so it reads desktopRefreshStackLift() and rises above the refresh button while one exists (a patch of its offset).
 */
internal object DesktopRefreshButtonPresence {
    private val count = mutableIntStateOf(0)
    val present: Boolean get() = count.intValue > 0
    fun enter() { count.intValue += 1 }
    fun leave() { count.intValue = (count.intValue - 1).coerceAtLeast(0) }
}

/**
 * The content of a refresh box sees at least the scroll reserve as the bottom content padding that list pages already read
 * (LocalBottomBarContentPadding, zero on the desktop), so lists that honour it end clear of the corner button.
 */
internal class DesktopRefreshLists {
    val callbacks = LinkedHashMap<Any, () -> Unit>()
    fun scrollToTop() { callbacks.values.toList().forEach { it() } }
}
internal val LocalDesktopRefreshLists = staticCompositionLocalOf<DesktopRefreshLists?> { null }

@Composable
internal fun DesktopRefreshReserve(content: @Composable () -> Unit) {
    val lists = remember { DesktopRefreshLists() }
    val reserve = DESKTOP_REFRESH_SCROLL_RESERVE_DP.dp
    val current = LocalBottomBarContentPadding.current
    CompositionLocalProvider(
        LocalDesktopRefreshLists provides lists,
        LocalBottomBarContentPadding provides maxOf(current, reserve), content = content,
    )
}

/** Only lists inside a refresh host reserve room; caller's existing insets are preserved. */
@Composable
internal fun desktopRefreshContentPadding(padding: PaddingValues): PaddingValues {
    if (LocalDesktopRefreshLists.current == null) return padding
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = padding.calculateStartPadding(direction), end = padding.calculateEndPadding(direction),
        top = padding.calculateTopPadding(),
        bottom = maxOf(padding.calculateBottomPadding(), LocalBottomBarContentPadding.current),
    )
}

@Composable
internal fun DesktopRefreshList(state: LazyListState) {
    val lists = LocalDesktopRefreshLists.current
    val scope = rememberCoroutineScope()
    DisposableEffect(lists, state) {
        lists?.callbacks?.put(state) { scope.launch { state.scrollToItem(0) } }
        onDispose { lists?.callbacks?.remove(state) }
    }
}
@Composable
internal fun DesktopRefreshList(state: LazyGridState) {
    val lists = LocalDesktopRefreshLists.current
    val scope = rememberCoroutineScope()
    DisposableEffect(lists, state) {
        lists?.callbacks?.put(state) { scope.launch { state.scrollToItem(0) } }
        onDispose { lists?.callbacks?.remove(state) }
    }
}

/** Extra offset for a floating button that shares the bottom right corner: zero without a refresh button, one button height and gap with it. */
@Composable
internal fun desktopRefreshStackLift(): Dp = if (DesktopRefreshButtonPresence.present) DESKTOP_REFRESH_STACK_LIFT_DP.dp else 0.dp

private fun nowMs(): Long = System.nanoTime() / 1_000_000L

/**
 * The button. [refreshing] is the owner's flag, [onRefresh] the owner's refresh. Placed in the bottom right corner of the
 * enclosing box; the caller does not position it.
 */
@Composable
internal fun BoxScope.DesktopRefreshButton(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    /** False for a page that announces its own failures precisely (the dynamic feed). */
    reportFailures: Boolean = true,
) {
    DisposableEffect(Unit) {
        DesktopRefreshButtonPresence.enter()
        onDispose { DesktopRefreshButtonPresence.leave() }
    }
    var state by remember { mutableStateOf(DesktopRefreshButtonState()) }
    // The clock only has to wake the composition when a pending hold runs out; isRefreshing itself is snapshot state.
    var clock by remember { mutableStateOf(nowMs()) }
    val pendingSince = state.pendingSinceMs
    val toaster = rememberUpdatedState(LocalDesktopToaster.current)
    // A refresh started here is watched for failed requests until the owner reports it finished (or never started).
    var operation by remember { mutableStateOf<DesktopRefreshFailureWatch.Operation?>(null) }
    val lists = LocalDesktopRefreshLists.current
    DisposableEffect(Unit) { onDispose { operation?.end() } }
    var scrolledHere by remember { mutableStateOf(false) }
    LaunchedEffect(pendingSince) {
        if (pendingSince != null) {
            kotlinx.coroutines.delay(DESKTOP_REFRESH_PENDING_TIMEOUT_MS)
            clock = nowMs()
        }
    }
    LaunchedEffect(refreshing) { state = state.onRefreshing(refreshing) }
    // Evidence that a refresh really ran, whoever asked for it (button, F5 or Ctrl+R): the owner's flag going up and coming down.
    var runningSinceMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(refreshing) {
        if (refreshing) {
            runningSinceMs = nowMs()
            DesktopDiagnosticsBridge.record("I", DESKTOP_REFRESH_LOG_TAG, desktopRefreshRunningLog())
        } else {
            runningSinceMs?.let {
                DesktopDiagnosticsBridge.record("I", DESKTOP_REFRESH_LOG_TAG, desktopRefreshEndLog(it, nowMs()))
                // The refreshed rows replaced the old ones: aim the scroll to the top again for lists that keep their position.
                if (scrolledHere) lists?.scrollToTop()
                operation?.let { completed ->
                    operation = null
                    val failed = completed.end()
                    if (failed > 0) toaster.value.show(desktopRefreshWatchedFailureMessage(failed))
                }
            }
            runningSinceMs = null
            scrolledHere = false
        }
    }
    // A refresh that the owner declined never reports isRefreshing: stop watching when the hold runs out.
    LaunchedEffect(pendingSince, operation) {
        if (pendingSince != null && operation != null) {
            kotlinx.coroutines.delay(DESKTOP_REFRESH_PENDING_TIMEOUT_MS + 100)
            if (runningSinceMs == null) {
                val failed = operation?.end() ?: 0
                operation = null
                if (failed > 0) toaster.value.show(desktopRefreshWatchedFailureMessage(failed))
                if (scrolledHere) lists?.scrollToTop()
            }
        }
    }
    val busy = state.busy(refreshing, if (pendingSince != null) clock.coerceAtLeast(pendingSince) else clock)
    val latestOnRefresh = rememberUpdatedState(onRefresh)
    val latestRefreshing = rememberUpdatedState(refreshing)
    val latestPrelude = rememberUpdatedState(LocalDesktopRefreshPrelude.current)
    val latestState = rememberUpdatedState(state)
    // The one entry of a click and of F5 / Ctrl+R: true when this call started a refresh.
    val trigger = remember {
        { sourceName: String ->
            val (next, start) = latestState.value.click(latestRefreshing.value, nowMs())
            state = next
            if (start) {
                clock = nowMs()
                DesktopDiagnosticsBridge.record("I", DESKTOP_REFRESH_LOG_TAG, desktopRefreshStartLog(sourceName))
                if (reportFailures) operation = DesktopRefreshFailureWatch.Operation()
                val prelude = latestPrelude.value
                if (prelude != null) prelude() else { lists?.scrollToTop(); scrolledHere = lists != null }
                DesktopRefreshFailureWatch.within(operation) { latestOnRefresh.value() }
            }
            start
        }
    }
    val target = remember { DesktopRefreshTarget(trigger) }
    DisposableEffect(target) {
        DesktopRefreshRegistry.shared.register(target)
        onDispose { DesktopRefreshRegistry.shared.unregister(target) }
    }
    val source = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    val name = desktopRefreshButtonName(busy)
    DesktopTooltipBox(
        text = name,
        shortcut = if (busy || !desktopRefreshShortcutPage(DesktopRefreshRegistry.pageProvider())) null else DESKTOP_REFRESH_SHORTCUT,
        positioning = TooltipAnchorPosition.Above,
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                // The wrapper's parent is the refresh box itself: the list a scroll to the top has to reach.
                coordinates.parentLayoutCoordinates?.let { parent ->
                    val box = parent.boundsInRoot()
                    target.boxBounds = box
                }
            }
            .align(Alignment.BottomEnd)
            .padding(end = DESKTOP_REFRESH_BUTTON_MARGIN_DP.dp, bottom = DESKTOP_REFRESH_BUTTON_MARGIN_DP.dp),
    ) {
        Box(
            Modifier
                .size(DESKTOP_REFRESH_BUTTON_SIZE_DP.dp)
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInRoot()
                    val root = coordinates.findRootCoordinates().size

                    target.visible = coordinates.isAttached && bounds.width > 0f && bounds.height > 0f &&
                        bounds.right > 0f && bounds.bottom > 0f && bounds.left < root.width && bounds.top < root.height
                }
                .shadow(4.dp, CircleShape)
                .background(colors.surfaceContainerHigh, CircleShape)
                .desktopKitHairlineBorder(colors.outlineVariant, (DESKTOP_REFRESH_BUTTON_SIZE_DP / 2).dp)
                .desktopKitInteraction(source, (DESKTOP_REFRESH_BUTTON_SIZE_DP / 2).dp, enabled = true)
                .clickable(interactionSource = source, indication = null, role = Role.Button) { trigger("按钮") }
                .semantics { contentDescription = name },
            contentAlignment = Alignment.Center,
        ) {
            if (busy && !LocalDesktopKitReducedMotion.current) {
                SpinningGlyph()
            } else {
                Icon(
                    Icons.Rounded.Refresh, contentDescription = null,
                    modifier = Modifier.size(22.dp).alpha(if (busy) 0.6f else 1f),
                    tint = colors.onSurface,
                )
            }
        }
    }
}

@Composable
private fun SpinningGlyph() {
    val transition = rememberInfiniteTransition(label = "desktopRefreshSpin")
    val angle by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(DESKTOP_REFRESH_SPIN_PERIOD_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "desktopRefreshSpinAngle",
    )
    Icon(
        Icons.Rounded.Refresh, contentDescription = null,
        modifier = Modifier.size(22.dp).rotate(angle),
        tint = MaterialTheme.colorScheme.primary,
    )
}
