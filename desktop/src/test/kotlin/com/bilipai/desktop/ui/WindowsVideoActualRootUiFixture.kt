package com.bilipai.desktop.ui

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowPlacement
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot
import com.bilipai.desktop.player.PlayerPreferences
import com.bilipai.desktop.player.PlayerPreferencesStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Canvas
import java.awt.Component
import java.awt.Container
import java.awt.EventQueue
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.MouseEvent
import java.awt.event.InputEvent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleState
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities

/** Test-only actual Main. No alternate Root, repository, native player, controller or stack. */
object WindowsVideoActualRootUiFixture {
    private val latest = AtomicReference<DesktopOriginalRootValidationTap.Frame?>()
    private val completed = AtomicBoolean(false)
    private val rows = CopyOnWriteArrayList<JsonObject>()
    private lateinit var report: Path
    private lateinit var actions: OriginalOnboardingUiActions
    private lateinit var owner: DesktopReadyOriginalRootHandle
    private lateinit var routes: DesktopOriginalRootRouteAssembly
    private lateinit var video: String
    private lateinit var actualPlayer: MpvPlayer
    private lateinit var actualCanvas: Canvas
    private lateinit var accepted: OwnedPlaybackSourceSnapshot
    private var ownedWindowIdentity = 0
    @Volatile private var runtimeMainWindow: ComposeWindow? = null
    private var videoKey: BiliPaiNavKey.VideoDetail? = null

    private fun <T> edt(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        val result = AtomicReference<Result<T>?>()
        EventQueue.invokeAndWait { result.set(runCatching(block)) }
        return requireNotNull(result.get()).getOrThrow()
    }
    private fun window(): JFrame = Window.getWindows().filterIsInstance<JFrame>()
        .filter { it.isShowing && it.title == "BiliPai Windows" }.single()
    private fun ownedWindow(candidate: Window): Boolean {
        var current: Window? = candidate
        val main = window()
        while (current != null) { if (current === main) return true; current = current.owner }
        return false
    }
    private fun nativeComponents(component: Component): List<Component> = mutableListOf(component).also { result ->
        if (component is Container) component.components.forEach { result.addAll(nativeComponents(it)) }
    }
    private fun nodes(context: AccessibleContext?, result: MutableList<AccessibleContext>, depth: Int = 0) {
        if (context == null || depth > 80) return
        result.add(context)
        repeat(context.accessibleChildrenCount) { nodes(context.getAccessibleChild(it)?.accessibleContext, result, depth + 1) }
    }
    private fun descendants(context: AccessibleContext): List<AccessibleContext> = mutableListOf<AccessibleContext>().also { nodes(context, it) }
    private fun all() = descendants(window().accessibleContext)
    private fun hasLabel(context: AccessibleContext, label: String): Boolean =
        Regex("(^|[\\r\\n,，])\\s*${Regex.escape(label)}\\s*($|[\\r\\n,，])").containsMatchIn(context.accessibleName.orEmpty()) ||
            descendants(context).any { it.accessibleName == label }
    private fun visible(context: AccessibleContext, surface: Window = window()): Boolean {
        val component = context.accessibleComponent ?: return false
        val origin = component.locationOnScreen ?: return false
        val size = component.size
        check(surface.isShowing && ownedWindow(surface))
        val main = (surface as javax.swing.RootPaneContainer).contentPane
        val viewport = Rectangle(main.locationOnScreen.x, main.locationOnScreen.y, main.width, main.height)
        return context.accessibleStateSet.contains(AccessibleState.SHOWING) && size.width > 0 && size.height > 0 &&
            viewport.contains(Rectangle(origin.x, origin.y, size.width, size.height))
    }
    /** Reads only the first captured Main peer. No window search, reflection, focus or paint. */
    private fun actualMainBackendSelectionOnEdt(): WindowsMainBackendSelectionObservation {
        check(EventQueue.isDispatchThread())
        val main = runtimeMainWindow
        fun rootCurrent(frame: DesktopOriginalRootValidationTap.Frame?): Boolean {
            if (frame == null || !::owner.isInitialized || !::routes.isInitialized) return false
            return frame.handle === owner && frame.routes === routes && owner.isActive() &&
                routes.owns() && owner.route.get() === routes
        }
        fun routeCurrent(frame: DesktopOriginalRootValidationTap.Frame?): Boolean =
            rootCurrent(frame) && frame != null &&
                (if (frame.pagerHosted) routes.currentKey == BiliPaiNavKey.MainHost else frame.key == routes.currentKey)
        val frame = latest.get()
        val identity = main?.let(System::identityHashCode)
        val identityMatches = identity != null && identity == ownedWindowIdentity
        val showing = main?.isShowing
        val displayable = main?.isDisplayable
        val reason = when {
            main == null -> "OWNED_MAIN_NOT_CAPTURED"
            !identityMatches -> "OWNED_MAIN_IDENTITY_MISMATCH"
            !rootCurrent(frame) -> "ROOT_OR_ROUTE_OWNER_RETIRED"
            !routeCurrent(frame) -> "CURRENT_ROUTE_FRAME_NOT_PUBLISHED"
            displayable != true -> "OWNED_MAIN_NOT_DISPLAYABLE"
            showing != true -> "OWNED_MAIN_NOT_SHOWING"
            else -> null
        }
        if (reason != null) return WindowsMainBackendSelectionObservation(
            frameSerial = frame?.serial, capturedWindowIdentity = identity,
            windowIdentityMatches = identityMatches, windowShowing = showing,
            windowDisplayable = displayable, rootOwnershipCurrent = rootCurrent(frame),
            routeFrameCurrent = routeCurrent(frame), unavailableReason = reason,
        )
        val api = runCatching { requireNotNull(main).renderApi.name }
        val after = latest.get()
        val stillCurrent = rootCurrent(after) && routeCurrent(after) && main != null &&
            main.isShowing && main.isDisplayable && runtimeMainWindow === main &&
            System.identityHashCode(main) == ownedWindowIdentity
        return WindowsMainBackendSelectionObservation(
            frameSerial = after?.serial, capturedWindowIdentity = identity,
            windowIdentityMatches = identityMatches, windowShowing = main?.isShowing,
            windowDisplayable = main?.isDisplayable, rootOwnershipCurrent = rootCurrent(after),
            routeFrameCurrent = routeCurrent(after), renderApi = api.getOrNull()?.takeIf { stillCurrent },
            unavailableReason = when {
                !stillCurrent -> "MAIN_OWNERSHIP_CHANGED_DURING_BACKEND_READ"
                api.isFailure -> "PUBLIC_BACKEND_GETTER_FAILED"
                api.getOrNull() == "UNKNOWN" -> "SKIKO_SELECTION_UNKNOWN"
                else -> null
            },
            diagnosticExceptionType = api.exceptionOrNull()?.javaClass?.name,
        )
    }
    private fun actualMainBackendSelection(): WindowsMainBackendSelectionObservation {
        if (runtimeMainWindow == null) return WindowsMainBackendSelectionObservation(unavailableReason = "OWNED_MAIN_NOT_CAPTURED")
        if (EventQueue.isDispatchThread()) return actualMainBackendSelectionOnEdt()
        val pending = java.util.concurrent.CompletableFuture<WindowsMainBackendSelectionObservation>()
        EventQueue.invokeLater {
            if (!pending.isCancelled) pending.complete(runCatching { actualMainBackendSelectionOnEdt() }.getOrElse { error ->
                WindowsMainBackendSelectionObservation(unavailableReason = "EDT_BACKEND_OBSERVATION_FAILED",
                    diagnosticExceptionType = error.javaClass.name)
            })
        }
        return try {
            pending.get(1, java.util.concurrent.TimeUnit.SECONDS)
        } catch (error: Exception) {
            pending.cancel(false)
            if (error is InterruptedException) Thread.currentThread().interrupt()
            WindowsMainBackendSelectionObservation(unavailableReason =
                if (error is java.util.concurrent.TimeoutException) "EDT_BACKEND_OBSERVATION_TIMED_OUT" else "EDT_BACKEND_OBSERVATION_UNAVAILABLE",
                diagnosticExceptionType = error.javaClass.name)
        }
    }
    private fun writeActualMainRuntimeEvidence(stage: String, phase: String, primaryFailure: Throwable? = null) {
        runCatching {
            require(stage == "start" || stage == "end")
            val evidence = WindowsActualMainRuntimeEvidence.capture(phase, actualMainBackendSelection())
            Files.writeString(report.resolve("actual-main-runtime-$stage.json"), evidence.toString(), CREATE_NEW, WRITE)
        }.exceptionOrNull()?.let { diagnosticFailure ->
            if (primaryFailure != null && primaryFailure !== diagnosticFailure) primaryFailure.addSuppressed(diagnosticFailure)
            else System.err.println("Actual Main runtime diagnostic unavailable: ${diagnosticFailure.javaClass.name}")
        }
    }

    private fun current(): DesktopOriginalRootValidationTap.Frame {
        check(EventQueue.isDispatchThread())
        val frame = requireNotNull(latest.get())
        check(frame.handle === owner && frame.routes === routes && owner.isActive() && routes.owns() && owner.route.get() === routes)
        check(System.identityHashCode(window()) == ownedWindowIdentity)
        check(if (frame.pagerHosted) routes.currentKey == BiliPaiNavKey.MainHost else frame.key == routes.currentKey)
        return frame
    }
    private fun pendingCurrent(): DesktopOriginalRootValidationTap.Frame? {
        check(EventQueue.isDispatchThread())
        val frame = latest.get() ?: return null
        check(frame.handle === owner && frame.routes === routes && owner.isActive() && routes.owns() && owner.route.get() === routes)
        check(System.identityHashCode(window()) == ownedWindowIdentity)
        return frame.takeIf { if (it.pagerHosted) routes.currentKey == BiliPaiNavKey.MainHost else it.key == routes.currentKey }
    }
    private fun record(id: String, properties: Map<String, JsonElement> = emptyMap()) {
        val frame = edt { current() }
        rows.add(buildJsonObject {
            put("id", id); put("serial", frame.serial); put("keyType", frame.key.javaClass.simpleName)
            put("sameRootAndRouteAssembly", true); put("actualWindowIdentity", ownedWindowIdentity)
            properties.forEach { (key, value) -> put(key, value) }
        })
    }
    private fun recordDeliveredMouse(id: String, properties: Map<String, JsonElement>) {
        // One real release can synchronously change the route before its next draw.
        // This is only delivery diagnostics, never a completed-route/playing oracle.
        check(EventQueue.isDispatchThread())
        val frame = requireNotNull(latest.get())
        check(frame.handle === owner && frame.routes === routes && owner.isActive() && routes.owns() && owner.route.get() === routes)
        check(System.identityHashCode(window()) == ownedWindowIdentity)
        rows.add(buildJsonObject {
            put("id", id); put("serial", frame.serial); put("keyType", frame.key.javaClass.simpleName)
            put("sameRootAndRouteAssembly", true); put("actualWindowIdentity", ownedWindowIdentity)
            put("scope", "POST_REAL_MOUSE_RELEASE_DIAGNOSTIC_ONLY")
            put("drawnFrameIsCurrent", if (frame.pagerHosted) routes.currentKey == BiliPaiNavKey.MainHost else frame.key == routes.currentKey)
            properties.forEach { (key, value) -> put(key, value) }
        })
    }
    private fun await(description: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos()
        while (System.nanoTime() < deadline) { if (predicate()) return; Thread.sleep(100) }
        error("Actual Windows video timed out: $description")
    }
    private fun videoFrame(after: Long = 0): DesktopOriginalRootValidationTap.Frame {
        var observed: DesktopOriginalRootValidationTap.Frame? = null
        await("same drawn ordinary VideoDetail") { edt {
            val frame = pendingCurrent() ?: return@edt false
            val key = frame.key as? BiliPaiNavKey.VideoDetail ?: return@edt false
            if (key.bvid == video && frame.serial > after) { observed = frame; true } else false
        } }
        return requireNotNull(observed)
    }
    private fun videoScope(extraAnchor: String? = null): AccessibleContext {
        current(); check((routes.currentKey as? BiliPaiNavKey.VideoDetail)?.bvid == video)
        // Actual Windows header stays visible in both layouts. The original renderer
        // legitimately omits the recommendation sidebar and lower controls in fullscreen.
        val leafAnchors = listOf("返回", if ((window() as ComposeWindow).placement == WindowPlacement.Fullscreen) "退出全屏" else "全屏") + listOfNotNull(extraAnchor)
        val candidates = all().filter { root -> leafAnchors.all { anchor -> descendants(root).any {
            it.accessibleName == anchor && visible(it)
        } } }
        check(candidates.isNotEmpty()) { "Actual Windows video leaf lacks its complete visible controls" }
        val sizes = candidates.associateWith { descendants(it).size }
        val smallest = requireNotNull(sizes.values.minOrNull())
        return candidates.filter { sizes[it] == smallest }.single()
    }
    private fun click(label: String) {
        // Closing an owned menu can restore focus before Compose has republished
        // the full accessible tree. Wait for the same strict complete scope and
        // unique real control; actual Root/source retirement still fails immediately.
        await("complete actual video controls and unique '$label' after native menu retirement") { edt {
            current()
            check((routes.currentKey as? BiliPaiNavKey.VideoDetail)?.bvid == video)
            if (::accepted.isInitialized) sameNative()
            val scope = runCatching { videoScope(label) }.getOrNull() ?: return@edt false
            descendants(scope).count { hasLabel(it, label) && visible(it) &&
                it.accessibleStateSet.contains(AccessibleState.ENABLED) &&
                (it.accessibleAction?.accessibleActionCount ?: 0) == 1 } == 1
        } }
        edt {
            val scope = videoScope(label)
            if (::accepted.isInitialized) sameNative()
            val controls = descendants(scope).filter { hasLabel(it, label) && visible(it) &&
                it.accessibleStateSet.contains(AccessibleState.ENABLED) && (it.accessibleAction?.accessibleActionCount ?: 0) == 1 }
            check(controls.size == 1) { "Expected one actual visible '$label' in complete Windows video leaf, got ${controls.size}" }
            record("mouse-${rows.size}-$label", mapOf("label" to JsonPrimitive(label), "matches" to JsonPrimitive(controls.size),
                "inputMechanism" to JsonPrimitive("OWNED_COMPOSE_AWT_MOUSE_EVENT")))
            clickOwnedComposeMouse(window(), controls.single())
        }
    }
    private fun clickOwnedComposeMouse(surface: Window, control: AccessibleContext) {
        current()
        check(surface.isShowing && surface.isDisplayable && ownedWindow(surface))
        val candidates = nativeComponents(surface).filter { component ->
            component.isShowing && component.isDisplayable && component.isEnabled &&
                SwingUtilities.getWindowAncestor(component) === surface && component.keyListeners.any {
                    it.javaClass.name == "androidx.compose.ui.scene.ComposeSceneMediator\$keyListener\$1"
                }
        }
        check(candidates.size == 1) { "Expected one actual owned Compose pointer input" }
        val input = candidates.single()
        check(input.mouseListeners.any { it.javaClass.name.startsWith("androidx.compose.ui.scene.ComposeSceneMediator\$") })
        val component = requireNotNull(control.accessibleComponent)
        val origin = requireNotNull(component.locationOnScreen)
        val size = component.size
        check(size.width > 0 && size.height > 0 &&
            surface.bounds.contains(Rectangle(origin.x, origin.y, size.width, size.height)))
        val point = java.awt.Point(origin.x + size.width / 2, origin.y + size.height / 2)
        SwingUtilities.convertPointFromScreen(point, input)
        check(input.contains(point)) { "Original control center is outside its owned input" }
        val now = System.currentTimeMillis()
        input.dispatchEvent(MouseEvent(input, MouseEvent.MOUSE_MOVED, now, 0, point.x, point.y, 0, false))
        input.dispatchEvent(MouseEvent(input, MouseEvent.MOUSE_PRESSED, now + 1, InputEvent.BUTTON1_DOWN_MASK,
            point.x, point.y, 1, false, MouseEvent.BUTTON1))
        // Yield the EDT between press and release so the actual pointer coroutine
        // observes a held button, as it does for normal native mouse input.
        javax.swing.Timer(100) {
            current()
            check(surface.isShowing && ownedWindow(surface) && input.isShowing &&
                SwingUtilities.getWindowAncestor(input) === surface)
            input.dispatchEvent(MouseEvent(input, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0,
                point.x, point.y, 1, false, MouseEvent.BUTTON1))
            recordDeliveredMouse("mouse-release-${rows.size}", mapOf("ownedRendererInputClass" to JsonPrimitive(input.javaClass.name),
                "pressAndReleaseOnSeparateEdtTurns" to JsonPrimitive(true)))
        }.apply { isRepeats = false; start() }
    }


    private fun noStartupMobilePrompts() = edt {
        current()
        check(routes.stack.none { it == BiliPaiNavKey.Onboarding })
        check(all().none { it.accessibleName == "帮助改进应用" || it.accessibleName == "使用须知" })
    }
    private fun actualNativeCanvasCandidates(): List<Canvas> =
        nativeComponents(window()).filterIsInstance<Canvas>().filter { canvas ->
            canvas.isShowing && canvas.isDisplayable && canvas.width > 100 && canvas.height > 80 &&
                SwingUtilities.getWindowAncestor(canvas) === window() &&
                canvas.javaClass.enclosingClass == MpvPlayer::class.java &&
                canvas.javaClass.declaredFields.count { it.type == MpvPlayer::class.java } == 1
        }
    private fun acquireActualNativePlayer() =
        await("actual complete Windows video controls and original native surface") { edt {
        val frame = pendingCurrent() ?: return@edt false
        check((frame.key as? BiliPaiNavKey.VideoDetail)?.bvid == video)
        // Compose's own Skia Canvas can precede the retained MPV peer. Observe
        // and acquire the exact player Canvas in the same EDT turn.
        val candidates = actualNativeCanvasCandidates()
        check(candidates.size <= 1) { "Multiple actual ordinary native Canvases during startup" }
        if (candidates.isEmpty() || runCatching { videoScope() }.isFailure) return@edt false
        actualCanvas = candidates.single()
        check(actualCanvas.javaClass.enclosingClass == MpvPlayer::class.java)
        // Only the fixed owned Canvas outer-player field, never arbitrary object/callback/account reflection.
        val field = actualCanvas.javaClass.declaredFields.single { it.type == MpvPlayer::class.java }
        check(field.trySetAccessible())
        actualPlayer = field.get(actualCanvas) as MpvPlayer
        check(nativeComponents(actualPlayer.surface).filterIsInstance<Canvas>().single() === actualCanvas)
        check(SwingUtilities.getWindowAncestor(actualPlayer.surface) === window())
        record("actual-native-owner", mapOf("canvasClass" to JsonPrimitive(actualCanvas.javaClass.name),
            "sameActualSurfaceAndCanvas" to JsonPrimitive(true), "nativeActorIdentity" to JsonPrimitive(System.identityHashCode(actualPlayer))))
        true
    } }
    private interface FailureWindowApi : StdCallLibrary {
        fun GetWindowThreadProcessId(hwnd: Pointer, pid: IntByReference): Int
        fun IsWindowVisible(hwnd: Pointer): Boolean
        fun IsIconic(hwnd: Pointer): Boolean
        fun GetWindowLongW(hwnd: Pointer, index: Int): Int
        fun GetForegroundWindow(): Pointer?
        fun GetWindowRect(hwnd: Pointer, rect: Pointer): Boolean
        fun GetClientRect(hwnd: Pointer, rect: Pointer): Boolean
        fun ClientToScreen(hwnd: Pointer, point: Pointer): Boolean
        fun GetWindowPlacement(hwnd: Pointer, placement: Pointer): Boolean
    }
    private val failureWindowApi: FailureWindowApi by lazy { Native.load("user32", FailureWindowApi::class.java) }
    private fun failureWindowGeometry(phase: String): JsonObject = edt {
        val main = window()
        check(System.identityHashCode(main) == ownedWindowIdentity)
        fun rectangle(value: Rectangle) = buildJsonObject {
            put("x", value.x); put("y", value.y); put("width", value.width); put("height", value.height)
        }
        fun awt(component: Component): JsonObject = buildJsonObject {
            put("class", component.javaClass.name); put("identity", System.identityHashCode(component))
            put("showing", component.isShowing); put("displayable", component.isDisplayable)
            put("localBounds", rectangle(component.bounds))
            put("screenBounds", runCatching { rectangle(Rectangle(component.locationOnScreen, component.size)) }.getOrNull() ?: JsonNull)
        }
        fun native(component: Component): JsonObject = buildJsonObject {
            // Read only these two already-owned peers; never query another process or restore a window.
            check(component === main || (::actualCanvas.isInitialized && component === actualCanvas &&
                SwingUtilities.getWindowAncestor(component) === main))
            if (!component.isDisplayable) { put("available", false); return@buildJsonObject }
            val hwnd = Native.getComponentPointer(component)
            val pid = IntByReference()
            check(failureWindowApi.GetWindowThreadProcessId(hwnd, pid) != 0 &&
                Integer.toUnsignedLong(pid.value) == ProcessHandle.current().pid())
            put("available", true); put("ownPidVerified", true)
            put("hwnd", java.lang.Long.toUnsignedString(Pointer.nativeValue(hwnd)))
            put("visible", failureWindowApi.IsWindowVisible(hwnd)); put("iconic", failureWindowApi.IsIconic(hwnd))
            val style = failureWindowApi.GetWindowLongW(hwnd, -16)
            put("style", Integer.toUnsignedString(style)); put("minimizeStyleBit", style and 0x20000000 != 0)
            Memory(16).use { rect ->
                if (failureWindowApi.GetWindowRect(hwnd, rect)) put("windowScreenRect", rectangle(Rectangle(
                    rect.getInt(0), rect.getInt(4), rect.getInt(8) - rect.getInt(0), rect.getInt(12) - rect.getInt(4))))
                else put("windowRectQueryError", Native.getLastError())
                if (failureWindowApi.GetClientRect(hwnd, rect)) Memory(8).use { origin ->
                    origin.clear()
                    if (failureWindowApi.ClientToScreen(hwnd, origin)) put("clientScreenRect", rectangle(Rectangle(
                        origin.getInt(0), origin.getInt(4), rect.getInt(8) - rect.getInt(0), rect.getInt(12) - rect.getInt(4))))
                    else put("clientOriginQueryError", Native.getLastError())
                } else put("clientRectQueryError", Native.getLastError())
            }
            if (component === main) Memory(44).use { placement ->
                // Win32 WINDOWPLACEMENT: three UINTs, two POINTs and one RECT (44 bytes).
                placement.clear(); placement.setInt(0, 44)
                if (failureWindowApi.GetWindowPlacement(hwnd, placement)) put("placement", buildJsonObject {
                    put("flags", placement.getInt(4)); put("showCmd", placement.getInt(8))
                    put("normalPosition", rectangle(Rectangle(placement.getInt(28), placement.getInt(32),
                        placement.getInt(36) - placement.getInt(28), placement.getInt(40) - placement.getInt(32))))
                }) else put("placementQueryError", Native.getLastError())
            }
        }
        buildJsonObject {
            put("scope", "FAILURE_ONLY_OWNED_WINDOW_READ_ONLY"); put("phase", phase)
            put("observedAtEpochMillis", System.currentTimeMillis()); put("capturedOnEdt", true)
            put("windowRestoredOrRetried", false); put("geometryOracleRelaxed", false)
            put("composePlacement", (main as ComposeWindow).placement.toString())
            put("awtExtendedState", main.extendedState)
            put("awtIconified", main.extendedState and java.awt.Frame.ICONIFIED != 0)
            put("focused", main.isFocused); put("active", main.isActive)
            put("mainAwt", awt(main)); put("clientAwt", awt(main.contentPane))
            val configuration = main.graphicsConfiguration
            put("monitorAwt", rectangle(configuration.bounds))
            put("awtScaleX", configuration.defaultTransform.scaleX); put("awtScaleY", configuration.defaultTransform.scaleY)
            put("nativeCoordinateSpace", "USER32_SCREEN_COORDINATES_NO_APPLICATION_SCALE_CONVERSION")
            put("mainNative", runCatching { native(main) }.getOrElse { buildJsonObject { put("queryFailureType", it.javaClass.name) } })
            if (::actualCanvas.isInitialized) {
                put("canvasAwt", awt(actualCanvas))
                put("canvasOwnedByMain", SwingUtilities.getWindowAncestor(actualCanvas) === main)
                put("canvasNative", runCatching { native(actualCanvas) }.getOrElse { buildJsonObject { put("queryFailureType", it.javaClass.name) } })
            }
        }
    }
    private fun writeFailureWindowGeometry(phase: String) {
        Files.writeString(report.resolve("failure-owned-window-geometry.json"), failureWindowGeometry(phase).toString(), CREATE_NEW, WRITE)
    }
    private fun sameNative() = edt {
        current()
        check(nativeComponents(window()).filterIsInstance<Canvas>().filter { it.isShowing && it.width > 100 && it.height > 80 &&
            SwingUtilities.getWindowAncestor(it) === window() && it.javaClass.declaredFields.any { f -> f.type == MpvPlayer::class.java } }.single() === actualCanvas)
        check(actualCanvas.isShowing && actualCanvas.isDisplayable && SwingUtilities.getWindowAncestor(actualPlayer.surface) === window())
        check(actualPlayer.ownsSourceSnapshot(accepted)) { "Full actual source changed during fullscreen/resize" }
        val main = window().contentPane
        val view = Rectangle(main.locationOnScreen.x, main.locationOnScreen.y, main.width, main.height)
        val position = actualCanvas.locationOnScreen
        check(actualCanvas.width > 0 && actualCanvas.height > 0 &&
            view.contains(Rectangle(position.x, position.y, actualCanvas.width, actualCanvas.height))) {
            runCatching { writeFailureWindowGeometry("FIRST_CANVAS_CLIENT_CONTAINMENT_FAILURE") }
            "Actual native Canvas is clipped outside owned window client"
        }
    }
    private fun playing(): Boolean {
        val state = actualPlayer.state.value
        if (state.error != null) error("Actual native playback failed; inspect original log and bounded state receipt")
        return state.ready && !state.loading && !state.ended && state.firstVideoFrameReady && state.nativePaused == false &&
            state.videoCodec != null && state.durationSeconds > 0 && state.videoWidth > 0 && state.videoHeight > 0
    }
    private fun safeState(): JsonObject = buildJsonObject {
        val state = actualPlayer.state.value
        put("ready", state.ready); put("loading", state.loading); put("ended", state.ended)
        put("firstVideoFrameReady", state.firstVideoFrameReady); put("paused", state.paused)
        put("nativePaused", state.nativePaused?.let(::JsonPrimitive) ?: JsonNull)
        put("positionSeconds", state.positionSeconds); put("durationSeconds", state.durationSeconds)
        put("videoCodec", state.videoCodec?.let(::JsonPrimitive) ?: JsonNull)
        put("audioCodec", state.audioCodec?.let(::JsonPrimitive) ?: JsonNull)
        put("videoWidth", state.videoWidth); put("videoHeight", state.videoHeight)
        put("sourceTitle", state.sourceTitle); put("volume", state.volume); put("muted", state.muted)
        put("hasError", state.error != null); put("hasOperationError", state.operationError != null)
        put("sourceVersion", actualPlayer.currentSourceVersion)
        // No URL, header, credentials, raw full-source serialization or endpoint enumeration.
    }
    private fun clockAndCapture(id: String) {
        sameNative(); await("actual native playback frame/clock for $id") { playing() }
        val before = actualPlayer.state.value.positionSeconds
        Thread.sleep(2000)
        sameNative(); check(playing())
        val after = actualPlayer.state.value.positionSeconds
        check(after > before + 0.5) { "Actual native video clock did not advance: $before -> $after" }
        check(actualPlayer.state.value.volume == 0.0 && actualPlayer.state.value.muted) { "Private fixture silent precondition was lost" }
        noStartupMobilePrompts()
        actions.capture(id, edt { current() })
        val nativeImage = report.resolve("$id-native.png")
        runBlocking { actualPlayer.captureScreenshotForSource(accepted, nativeImage, includeSubtitles = false) }
        sameNative()
        val image = requireNotNull(ImageIO.read(nativeImage.toFile()))
        check(image.width > 0 && image.height > 0)
        val colours = mutableSetOf<Int>()
        for (y in 0 until image.height step maxOf(1, image.height / 32))
            for (x in 0 until image.width step maxOf(1, image.width / 32)) colours.add(image.getRGB(x, y))
        check(colours.size > 1) { "Actual decoded native screenshot was uniform" }
        record(id, mapOf("bvid" to JsonPrimitive(video), "sameAcceptedSourceVersion" to JsonPrimitive(accepted.sourceVersion),
            "fullImmutableSourceStillOwned" to JsonPrimitive(true), "nativeState" to safeState(),
            "clockBefore" to JsonPrimitive(before), "clockAfter" to JsonPrimitive(after),
            "actualNativeScreenshot" to JsonPrimitive(nativeImage.fileName.toString()),
            "nativeScreenshotWidth" to JsonPrimitive(image.width), "nativeScreenshotHeight" to JsonPrimitive(image.height),
            "sampledNativeColourCount" to JsonPrimitive(colours.size),
            "windowPlacement" to JsonPrimitive(edt { (window() as ComposeWindow).placement.toString() })))
        val expectedPlacement = if (id == "120-fullscreen-playing") WindowPlacement.Fullscreen else WindowPlacement.Floating
        check(edt { (window() as ComposeWindow).placement == expectedPlacement }) {
            "Unexpected actual window placement during $id; expected $expectedPlacement (captured before failure)"
        }
    }
    private var replaySearchKey: BiliPaiNavKey? = null
    private fun originalSearchHeaderLabels(label: String): Set<String> = when (label) {
        "返回" -> setOf("返回", "Back")
        "搜索" -> setOf("搜索", "Search", "搜尋")
        else -> error("Unexpected original Search header control: $label")
    }
    private fun scopeWithEditableSearch(): AccessibleContext {
        current(); check(routes.currentKey is BiliPaiNavKey.Search)
        val candidates = all().filter { scope ->
            val children = descendants(scope)
            children.count { it.accessibleEditableText != null && visible(it) } == 1 &&
                listOf("返回", "搜索").all { label -> children.count {
                    originalSearchHeaderLabels(label).any { alias -> hasLabel(it, alias) } &&
                        visible(it) && (it.accessibleAction?.accessibleActionCount ?: 0) == 1
                } == 1 }
        }
        check(candidates.isNotEmpty()) { "No complete original visible search header/editor" }
        val sizes = candidates.associateWith { descendants(it).size }
        val minimum = requireNotNull(sizes.values.minOrNull())
        return candidates.filter { sizes[it] == minimum }.single()
    }
    private fun searchValue(): String {
        val editor = descendants(scopeWithEditableSearch()).filter { it.accessibleEditableText != null && visible(it) }.single()
        val text = requireNotNull(editor.accessibleText)
        return buildString { repeat(text.charCount) { append(text.getAtIndex(javax.accessibility.AccessibleText.CHARACTER, it).orEmpty()) } }
    }
    private fun searchMouse(label: String) = edt {
        val scope = scopeWithEditableSearch()
        val control = descendants(scope).filter { originalSearchHeaderLabels(label).any { alias -> hasLabel(it, alias) } && visible(it) &&
            it.accessibleStateSet.contains(AccessibleState.ENABLED) && (it.accessibleAction?.accessibleActionCount ?: 0) == 1 }.single()
        record("search-mouse-${rows.size}-$label", mapOf("inputMechanism" to JsonPrimitive("OWNED_COMPOSE_AWT_MOUSE_EVENT")))
        clickOwnedComposeMouse(window(), control)
    }
    private fun desktopSidebarScope(): AccessibleContext {
        current(); check(routes.currentKey == BiliPaiNavKey.MainHost && current().key == BiliPaiNavKey.Home)
        // The actual scrollable Windows sidebar retains all three named controls.
        // At 125% its last download button may be clipped; it is a structural
        // context anchor, never an input target. The clicked Search stays wholly visible.
        val candidates = all().filter { scope ->
            val children = descendants(scope)
            listOf("BiliPai", "搜索", "下载与离线").all { label -> children.any { it.accessibleName == label } } &&
                listOf("BiliPai", "搜索").all { label -> children.any { it.accessibleName == label && visible(it) } }
        }
        check(candidates.isNotEmpty()) { "No complete actual Windows sidebar tree with visible title/Search" }
        val sizes = candidates.associateWith { descendants(it).size }
        val minimum = requireNotNull(sizes.values.minOrNull())
        return candidates.filter { sizes[it] == minimum }.single()
    }
    private fun enterVideoThroughActualSearch(replay: WindowsVideoLocalReplay) {
        await("actual drawn desktop Home before local transport admission") { edt {
            val frame = pendingCurrent() ?: return@edt false
            frame.key == BiliPaiNavKey.Home && frame.pagerHosted && routes.currentKey == BiliPaiNavKey.MainHost
        } }
        val repository = edt {
            current()
            val messages = requireNotNull(owner.messagePages.get())
            val spaces = requireNotNull(owner.spacePages.get())
            check(messages.isOwned() && spaces.routes === routes && messages.repository === spaces.repository)
            check(messages.repository.account.value == null)
            messages.repository
        }
        replay.install(repository) {
            owner.isActive() && if (System.getProperty("bilipai.validation.composerInput") == "true" &&
                replay.commentComposerReplay.authenticated(repository)) {
                // The Window-level handle intentionally survives account changes.
                // Only the actually installed epoch-current retained Home may read
                // synthetic transport before its new route Frame is drawn.
                owner.retainer.current()?.capturedEpoch == replay.commentComposerReplay.epoch()
            } else routes.owns() && owner.route.get() === routes
        }
        if (System.getProperty("bilipai.validation.composerInput") == "true") {
            val previousOwner = owner
            val previousRoutes = routes
            val previousRetained = requireNotNull(owner.retainer.current())
            replay.commentComposerReplay.seedSyntheticSession(repository, actions.local)
            await("real Root account/epoch replacement after same-Store synthetic seed") { edt {
                val frame = latest.get() ?: return@edt false
                if (frame.routes === previousRoutes || previousRoutes.owns() ||
                    previousRetained.isCurrentOwner() || !frame.handle.isActive() ||
                    !frame.routes.owns() || frame.handle.route.get() !== frame.routes ||
                    System.identityHashCode(window()) != ownedWindowIdentity || frame.key != BiliPaiNavKey.Home ||
                    !frame.pagerHosted || frame.routes.currentKey != BiliPaiNavKey.MainHost) return@edt false
                val messages = frame.handle.messagePages.get() ?: return@edt false
                val spaces = frame.handle.spacePages.get() ?: return@edt false
                if (!messages.isOwned() || messages.repository !== repository || spaces.repository !== repository ||
                    spaces.routes !== frame.routes || !replay.commentComposerReplay.authenticated(repository)) return@edt false
                val retained = frame.handle.retainer.current() ?: return@edt false
                if (retained === previousRetained || retained.capturedEpoch != replay.commentComposerReplay.epoch() ||
                    !retained.isCurrentOwner()) return@edt false
                // Adopt only the Root actually published by Main; no fixture Root
                // factory, route mutation or VM field writes are involved.
                owner = frame.handle; routes = frame.routes
                true
            } }
            record("composer-synthetic-session-actual-root-generation", mapOf(
                "sameActualRepository" to JsonPrimitive(true), "originalGuestEntryAndRoutesRetired" to JsonPrimitive(true),
                "sameWindowLevelRootHandle" to JsonPrimitive(owner === previousOwner), "actualRetainedHomeGenerationChanged" to JsonPrimitive(true),
                "actualAccountEpoch" to JsonPrimitive(repository.sessionEpoch),
                "syntheticPrimaryMid" to JsonPrimitive(WindowsCommentComposerReplay.MID),
                "sameNativeMainWindow" to JsonPrimitive(true), "loginUiAccepted" to JsonPrimitive(false)))
        }
        await("complete actual desktop sidebar and wholly visible Search target") { edt {
            val frame = pendingCurrent() ?: return@edt false
            frame.key == BiliPaiNavKey.Home && frame.pagerHosted &&
                routes.currentKey == BiliPaiNavKey.MainHost && runCatching { desktopSidebarScope() }.isSuccess
        } }
        val beforeSearch = edt { current().serial }
        edt {
            current()
            val sidebar = desktopSidebarScope()
            val control = descendants(sidebar).filter { hasLabel(it, "搜索") && visible(it) &&
                it.accessibleStateSet.contains(AccessibleState.ENABLED) && (it.accessibleAction?.accessibleActionCount ?: 0) == 1 }.single()
            clickOwnedComposeMouse(window(), control)
        }
        await("physical original Search drawn after sidebar mouse") { edt {
            val frame = pendingCurrent() ?: return@edt false
            frame.serial > beforeSearch && frame.key is BiliPaiNavKey.Search && runCatching { scopeWithEditableSearch() }.isSuccess
        } }
        Thread.sleep(1200)
        replaySearchKey = edt { current().key }
        actions.capture("090-local-original-search", edt { current() })
        edt {
            current()
            val editor = descendants(scopeWithEditableSearch()).filter { it.accessibleEditableText != null && visible(it) }.single()
            editor.accessibleEditableText.setTextContents(video)
        }
        await("actual original search editor BV readback") { edt { searchValue() == video } }
        actions.capture("095-local-search-bv-readback", edt { current() })
        searchMouse("搜索")
        videoFrame()
        record("local-replay-natural-search-entry", mapOf("sameActualRepository" to JsonPrimitive(true),
            "originalSearchEditorAndSubmit" to JsonPrimitive(true), "fixtureTypedRoutePushUsed" to JsonPrimitive(false)))
    }
    private fun returnReplaySearchToHome(after: Long) {
        await("actual original Video Back restores the same Search entry and BV editor") { edt {
            val frame = pendingCurrent() ?: return@edt false
            frame.serial > after && frame.key == replaySearchKey && routes.currentKey == replaySearchKey &&
                runCatching { searchValue() == video }.getOrDefault(false)
        } }
        Thread.sleep(1200)
        edt { check(current().key == replaySearchKey && searchValue() == video) }
        actions.capture("155-original-video-back-search", edt { current() })
        record("155-original-video-back-search", mapOf("sameSearchPhysicalKey" to JsonPrimitive(true),
            "actualSearchBVRetained" to JsonPrimitive(true), "originalVideoBackControlUsed" to JsonPrimitive(true)))
        searchMouse("返回")
    }

    private fun actualComposeInput(surface: Window = window()): Component {
        current()
        check(surface.isShowing && surface.isDisplayable && ownedWindow(surface))
        return nativeComponents(surface).filter { component -> component.isShowing && component.isDisplayable &&
            component.isEnabled && SwingUtilities.getWindowAncestor(component) === surface && component.keyListeners.any {
                it.javaClass.name == "androidx.compose.ui.scene.ComposeSceneMediator\$keyListener\$1"
            } }.single()
    }
    private fun privateScalePercent(): Int {
        val root = com.bilipai.desktop.DesktopLibrary.directoryForAccount(null).toAbsolutePath().normalize()
        val privateRoot = Path.of(requireNotNull(System.getenv("LOCALAPPDATA"))).toAbsolutePath().normalize()
        check(root.startsWith(privateRoot))
        val file = root.resolve("plugin-settings.json")
        if (!Files.exists(file, NOFOLLOW_LINKS)) return 125
        check(Files.isRegularFile(file, NOFOLLOW_LINKS) && !Files.isSymbolicLink(file))
        val document = Json.parseToJsonElement(Files.readString(file)).jsonObject
        return document["windows_display"]?.jsonObject?.get("scale_percent")?.jsonPrimitive?.intOrNull ?: 125
    }
    private fun focused(input: Component) = edt {
        current(); check(SwingUtilities.getWindowAncestor(input) === window())
        window().toFront(); window().requestFocus(); input.requestFocusInWindow()
    }
    private fun awaitFocus(input: Component) = await("actual owned keyboard focus") { edt {
        current()
        val focus = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
        window().isActive && focus.focusedWindow === window() && focus.focusOwner === input && input.isFocusOwner
    } }
    private fun ownedKey(input: Component, keyCode: Int, modifiers: Int = 0, typed: Char? = null,
        navigationTarget: BiliPaiNavKey? = null) {
        val alreadyFocused = edt {
            current()
            val focus = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
            window().isActive && focus.focusedWindow === window() && focus.focusOwner === input && input.isFocusOwner
        }
        if (!alreadyFocused) { focused(input); awaitFocus(input) }
        val delivered = edt {
            current(); check(input.isShowing && input.isDisplayable && input.isFocusOwner &&
                SwingUtilities.getWindowAncestor(input) === window())
            val now = System.currentTimeMillis()
            val pressed = java.awt.event.KeyEvent(input, java.awt.event.KeyEvent.KEY_PRESSED, now,
                modifiers, keyCode, typed ?: java.awt.event.KeyEvent.CHAR_UNDEFINED)
            input.dispatchEvent(pressed)
            typed?.let { input.dispatchEvent(java.awt.event.KeyEvent(input, java.awt.event.KeyEvent.KEY_TYPED, now + 1,
                modifiers, java.awt.event.KeyEvent.VK_UNDEFINED, it)) }
            input.dispatchEvent(java.awt.event.KeyEvent(input, java.awt.event.KeyEvent.KEY_RELEASED, now + 2,
                modifiers, keyCode, typed ?: java.awt.event.KeyEvent.CHAR_UNDEFINED))
            mapOf("keyCode" to JsonPrimitive(keyCode), "modifiers" to JsonPrimitive(modifiers),
                "pressedConsumed" to JsonPrimitive(pressed.isConsumed), "sameActualFocusedWindow" to JsonPrimitive(true),
                "inputClass" to JsonPrimitive(input.javaClass.name), "mechanism" to JsonPrimitive("OWNED_AWT_KEY_EVENT"))
        }
        if (navigationTarget != null) await("actual target frame after owned navigation key: $navigationTarget") { edt {
            val frame = pendingCurrent() ?: return@edt false
            frame.key == navigationTarget
        } }
        record("owned-key-${rows.size}", delivered)
    }
    private fun ownedWheel(input: Component, rotation: Int, ctrl: Boolean, point: java.awt.Point? = null) = edt {
        current(); check(input.isShowing && input.isDisplayable && SwingUtilities.getWindowAncestor(input) === window())
        check((window() as ComposeWindow).placement == WindowPlacement.Floating) {
            "Owned detail/scale wheel requires the actual Floating layout; fullscreen hides the comment pane"
        }
        val content = window().contentPane
        val client = Rectangle(content.locationOnScreen.x, content.locationOnScreen.y, content.width, content.height)
        val inputBounds = Rectangle(input.locationOnScreen.x, input.locationOnScreen.y, input.width, input.height)
        val location = point ?: run {
            sameNative()
            val target = if (ctrl) descendants(videoScope("详情")).filter { node -> node.accessibleName == "详情" &&
                visible(node) && (node.accessibleAction?.accessibleActionCount ?: 0) == 1 }.single() else detailPaneScope()
            val component = requireNotNull(target.accessibleComponent)
            val origin = requireNotNull(component.locationOnScreen)
            val area = Rectangle(origin.x, origin.y, component.size.width, component.size.height)
                .intersection(client).intersection(inputBounds)
            check(area.width > 20 && area.height > 20) { "No current owned visible wheel area in its actual control/detail pane" }
            java.awt.Point(area.x + area.width / 2 - inputBounds.x, area.y + area.height / 2 - inputBounds.y)
        }
        val screen = java.awt.Point(location.x + inputBounds.x, location.y + inputBounds.y)
        check(input.contains(location) && client.contains(screen) && inputBounds.contains(screen)) {
            "Owned wheel point is outside the current input/client intersection"
        }
        val canvasBounds = Rectangle(actualCanvas.locationOnScreen.x, actualCanvas.locationOnScreen.y,
            actualCanvas.width, actualCanvas.height)
        check(!canvasBounds.contains(screen)) { "Owned scale/detail wheel must not target the actual native video Canvas" }
        val event = java.awt.event.MouseWheelEvent(input, java.awt.event.MouseEvent.MOUSE_WHEEL,
            System.currentTimeMillis(), if (ctrl) java.awt.event.InputEvent.CTRL_DOWN_MASK else 0,
            location.x, location.y, 0, false, java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation)
        input.dispatchEvent(event)
        record("owned-wheel-${rows.size}", mapOf("rotation" to JsonPrimitive(rotation), "ctrl" to JsonPrimitive(ctrl),
            "consumed" to JsonPrimitive(event.isConsumed), "inputClass" to JsonPrimitive(input.javaClass.name),
            "screenX" to JsonPrimitive(screen.x), "screenY" to JsonPrimitive(screen.y),
            "inputWidth" to JsonPrimitive(input.width), "inputHeight" to JsonPrimitive(input.height),
            "windowPlacement" to JsonPrimitive((window() as ComposeWindow).placement.toString()),
            "mechanism" to JsonPrimitive("OWNED_AWT_WHEEL_EVENT")))
    }
    private fun awaitScale(expected: Int, after: Long) {
        await("actual durable Windows scale $expected and new current frame") {
            privateScalePercent() == expected && edt {
                val frame = pendingCurrent() ?: return@edt false
                frame.serial > after && frame.key == videoKey
            }
        }
        Thread.sleep(500); sameNative(); check(privateScalePercent() == expected)
        record("scale-$expected-${rows.size}", mapOf("actualPrivateStorePercent" to JsonPrimitive(expected),
            "nativeState" to safeState(), "actualCanvasWidth" to JsonPrimitive(edt { actualCanvas.width }),
            "actualCanvasHeight" to JsonPrimitive(edt { actualCanvas.height })))
    }
    private fun detailPaneScope(): AccessibleContext {
        current(); videoScope("关闭详情")
        val candidates = all().filter { scope ->
            val children = descendants(scope)
            scope.accessibleName == "视频详情面板" && visible(scope) &&
                children.count { it.accessibleName == "关闭详情" && visible(it) &&
                (it.accessibleAction?.accessibleActionCount ?: 0) == 1 } == 1 &&
                listOf("简介与分P", "评论", "相关推荐").all { label -> children.count {
                    it.accessibleName == label && visible(it) &&
                        it.accessibleRole == javax.accessibility.AccessibleRole.PAGE_TAB &&
                        (it.accessibleAction?.accessibleActionCount ?: 0) == 1
                } == 1 }
        }
        check(candidates.isNotEmpty()) { "No complete visible actual video detail pane with its three tabs and close control" }
        val counts = candidates.associateWith { descendants(it).size }
        val minimum = requireNotNull(counts.values.minOrNull())
        return candidates.filter { counts[it] == minimum }.single()
    }
    private fun commentsTabSelected(): Boolean {
        val tab = descendants(detailPaneScope()).filter { it.accessibleName == "评论" && visible(it) &&
            it.accessibleRole == javax.accessibility.AccessibleRole.PAGE_TAB &&
            (it.accessibleAction?.accessibleActionCount ?: 0) == 1 }.single()
        return tab.accessibleStateSet.contains(AccessibleState.SELECTED) || tab.accessibleStateSet.contains(AccessibleState.CHECKED)
    }
    private fun openCommentDetails() {
        sameNative(); click("详情")
        await("actual right detail pane opened") { edt { runCatching { detailPaneScope() }.isSuccess } }
        edt {
            val tab = descendants(detailPaneScope()).filter { it.accessibleName == "评论" && visible(it) &&
                it.accessibleRole == javax.accessibility.AccessibleRole.PAGE_TAB &&
                (it.accessibleAction?.accessibleActionCount ?: 0) == 1 }.single()
            clickOwnedComposeMouse(window(), tab)
        }
        await("actual comments tab selected on the same video detail pane") { edt {
            runCatching { commentsTabSelected() }.getOrDefault(false)
        } }
        sameNative(); check(playing())
        actions.capture("156-original-comment-detail-pane", edt { current() })
        record("156-original-comment-detail-pane", mapOf("actualDetailControlUsed" to JsonPrimitive("详情"),
            "actualCommentsTabUsed" to JsonPrimitive(true), "sameNativeSource" to JsonPrimitive(true),
            "originalNativeEditorForced" to JsonPrimitive(false)))
    }
    private fun actualEditor(): AccessibleContext {
        current(); check(commentsTabSelected()); videoScope("取消回复")
        // SwingPanel's native editor can be an AWT sibling of the Skia semantics tree.
        // The current typed page, complete original header/footer and exact owned
        // native editor identity remain mandatory; never select a label first.
        return nativeComponents(window()).filterIsInstance<javax.swing.JTextPane>().filter {
            it.javaClass.name == "com.bilipai.desktop.ui.DesktopInlineEmotePane" &&
                it.isShowing && it.isDisplayable && it.isEnabled &&
                SwingUtilities.getWindowAncestor(it) === window() && visible(it.accessibleContext)
        }.map { it.accessibleContext }.single().also { check(it.accessibleEditableText != null) }
    }
    private fun editorValue(): String {
        val text = requireNotNull(actualEditor().accessibleText)
        return buildString { repeat(text.charCount) {
            append(text.getAtIndex(javax.accessibility.AccessibleText.CHARACTER, it).orEmpty())
        } }
    }
    private fun actualEditorComponent(): javax.swing.JTextPane {
        current()
        val context = actualEditor()
        return nativeComponents(window()).filterIsInstance<javax.swing.JTextPane>().filter {
            it.javaClass.name == "com.bilipai.desktop.ui.DesktopInlineEmotePane" &&
                it.accessibleContext === context && it.isShowing && it.isDisplayable &&
                SwingUtilities.getWindowAncestor(it) === window()
        }.single()
    }
    private fun clickOwnedSwingEditor(editor: javax.swing.JTextPane) {
        check(EventQueue.isDispatchThread()); current()
        check(editor === actualEditorComponent() && editor.isShowing && editor.isEnabled)
        val now = System.currentTimeMillis()
        editor.dispatchEvent(MouseEvent(editor, MouseEvent.MOUSE_PRESSED, now, InputEvent.BUTTON1_DOWN_MASK,
            editor.width / 2, editor.height / 2, 1, false, MouseEvent.BUTTON1))
        javax.swing.Timer(100) {
            current(); check(editor === actualEditorComponent() && editor.isShowing &&
                SwingUtilities.getWindowAncestor(editor) === window())
            editor.dispatchEvent(MouseEvent(editor, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0,
                editor.width / 2, editor.height / 2, 1, false, MouseEvent.BUTTON1))
        }.apply { isRepeats = false; start() }
    }
    private fun exerciseOwnedScaleAndKeyboard() {
        sameNative(); check(playing()); check(privateScalePercent() == 125)
        var before = edt { current().serial }
        ownedKey(edt { actualComposeInput() }, java.awt.event.KeyEvent.VK_MINUS, java.awt.event.InputEvent.CTRL_DOWN_MASK)
        awaitScale(120, before)
        clockAndCapture("151-ctrl-minus-scale-120")
        before = edt { current().serial }
        ownedKey(edt { actualComposeInput() }, java.awt.event.KeyEvent.VK_EQUALS, java.awt.event.InputEvent.CTRL_DOWN_MASK)
        awaitScale(125, before)
        before = edt { current().serial }
        ownedWheel(edt { actualComposeInput() }, -1, true)
        awaitScale(130, before)
        clockAndCapture("152-ctrl-wheel-scale-130")
        before = edt { current().serial }
        ownedKey(edt { actualComposeInput() }, java.awt.event.KeyEvent.VK_0, java.awt.event.InputEvent.CTRL_DOWN_MASK)
        awaitScale(125, before)

        // Scale/layout completion can outlast the native focus activation. Start
        // the viewport click from the actually focused Compose window, as a
        // foreground user click would; the production adapter must then move
        // focus to the Canvas itself.
        val beforeViewportInput = edt { actualComposeInput() }
        focused(beforeViewportInput)
        awaitFocus(beforeViewportInput)
        // Real native viewport click transfers focus through the installed production adapter.
        edt {
            current(); check(actualCanvas.isShowing)
            val now = System.currentTimeMillis()
            actualCanvas.dispatchEvent(MouseEvent(actualCanvas, MouseEvent.MOUSE_PRESSED, now,
                InputEvent.BUTTON1_DOWN_MASK, actualCanvas.width / 2, actualCanvas.height / 2, 1, false, MouseEvent.BUTTON1))
            actualCanvas.dispatchEvent(MouseEvent(actualCanvas, MouseEvent.MOUSE_RELEASED, now + 1,
                0, actualCanvas.width / 2, actualCanvas.height / 2, 1, false, MouseEvent.BUTTON1))
        }
        awaitFocus(actualPlayer.surface)
        ownedKey(actualPlayer.surface, java.awt.event.KeyEvent.VK_SPACE)
        await("actual viewport Space pauses native source") { sameNative();
            actualPlayer.state.value.let { it.paused && it.nativePaused == true && it.error == null } }
        record("153-native-space-paused", mapOf("nativeState" to safeState()))
        ownedKey(actualPlayer.surface, java.awt.event.KeyEvent.VK_SPACE)
        await("actual viewport Space resumes native source") { playing() }
        sameNative()
        val seekId = actualPlayer.state.value.seekCompletedId
        val position = actualPlayer.state.value.positionSeconds
        ownedKey(actualPlayer.surface, java.awt.event.KeyEvent.VK_RIGHT)
        await("actual viewport Right produces original native seek completion") {
            sameNative(); actualPlayer.state.value.let { it.seekCompletedId > seekId &&
                (it.seekCompletedPositionSeconds ?: 0.0) > position + 1.0 && it.error == null }
        }
        record("154-native-right-seek", mapOf("nativeState" to safeState(), "previousSeekId" to JsonPrimitive(seekId)))

        openCommentDetails()
        // Scroll only the actual right detail pane. Never force a VM, send/post or write selection fields.
        repeat(12) {
            if (edt { runCatching { actualEditor() }.isSuccess }) return@repeat
            edt { ownedWheel(actualComposeInput(), 3, false) }; Thread.sleep(300)
        }
        await("one wholly visible actual original comment editor") { edt { runCatching { actualEditor() }.isSuccess } }
        edt {
            val editor = actualEditor()
            requireNotNull(editor.accessibleEditableText).setTextContents("fixture")
            clickOwnedSwingEditor(actualEditorComponent())
        }
        val editorInput = edt { actualEditorComponent() }
        await("real comment editor owns keyboard focus") { edt {
            current(); sameNative()
            // Editing invalidates Compose's semantics before its next full
            // publication. Keep the strict pane/editor checks, but await that
            // publication instead of failing on its temporary partial tree.
            runCatching {
                editorValue() == "fixture" && actualEditor().accessibleStateSet.contains(AccessibleState.FOCUSED) &&
                    java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner === editorInput &&
                    editorInput.isFocusOwner
            }.getOrDefault(false)
        } }
        edt { requireNotNull(actualEditor().accessibleEditableText).selectText(0, 0) }
        val beforeEditorSeek = actualPlayer.state.value.seekCompletedId
        val beforeEditorPosition = actualPlayer.state.value.positionSeconds
        ownedKey(editorInput, java.awt.event.KeyEvent.VK_RIGHT)
        await("original editor Right advances text caret") { edt {
            current(); sameNative()
            runCatching { actualEditor().accessibleText?.caretPosition == 1 }.getOrDefault(false)
        } }
        ownedKey(editorInput, java.awt.event.KeyEvent.VK_SPACE, typed = ' ')
        await("original editor receives actual typed space") { edt {
            current(); sameNative()
            runCatching { editorValue() == "f ixture" }.getOrDefault(false)
        } }
        Thread.sleep(1000)
        sameNative(); check(playing()); check(actualPlayer.state.value.seekCompletedId == beforeEditorSeek)
        val advance = actualPlayer.state.value.positionSeconds - beforeEditorPosition
        check(advance > 0.25 && advance < 3.0) { "Editor key was intercepted as a playback seek: $advance" }
        check(actualPlayer.state.value.volume == 0.0 && actualPlayer.state.value.muted)
        actions.capture("157-original-editor-keys-not-playback", edt { current() })
        record("157-original-editor-keys-not-playback", mapOf("localDraft" to JsonPrimitive(edt { editorValue() }),
            "textRightCaretVerified" to JsonPrimitive(true), "spaceInserted" to JsonPrimitive(true),
            "nativeSeekIdUnchanged" to JsonPrimitive(true), "clockAdvanceSeconds" to JsonPrimitive(advance),
            "remoteCommentSubmitted" to JsonPrimitive(false), "nativeState" to safeState()))
        edt { requireNotNull(actualEditor().accessibleEditableText).setTextContents("") }
        check(privateScalePercent() == 125)
        click("关闭详情")
        await("actual detail close restores the same compact player") { edt {
            all().none { it.accessibleName == "关闭详情" && visible(it) } && runCatching { videoScope("详情") }.isSuccess
        } }
        sameNative(); check(playing())
        record("158-original-detail-pane-closed", mapOf("actualCloseControlUsed" to JsonPrimitive("关闭详情"),
            "sameNativeSource" to JsonPrimitive(true), "remoteCommentSubmitted" to JsonPrimitive(false)))
    }

    // Opt-in test-only addition to the actual Main fixture; no preference setter or native command.
    private fun privateNvidiaEnabled(): Boolean? {
        val root = com.bilipai.desktop.DesktopLibrary.directoryForAccount(null).toAbsolutePath().normalize()
        val privateRoot = Path.of(requireNotNull(System.getenv("LOCALAPPDATA"))).toAbsolutePath().normalize()
        check(root.startsWith(privateRoot))
        val file = root.resolve("plugin-settings.json")
        if (!Files.exists(file, NOFOLLOW_LINKS)) return null
        check(Files.isRegularFile(file, NOFOLLOW_LINKS) && !Files.isSymbolicLink(file))
        val namespace = Json.parseToJsonElement(Files.readString(file)).jsonObject["windows_video_enhancement"]?.jsonObject
            ?: return null
        check(namespace["migration_version"]?.jsonPrimitive?.intOrNull == 1)
        return namespace["enabled"]?.jsonPrimitive?.booleanOrNull
    }

    private fun privateGlassDefaultMigrated(): Boolean {
        val root = com.bilipai.desktop.DesktopLibrary.directoryForAccount(null).toAbsolutePath().normalize()
        val privateRoot = Path.of(requireNotNull(System.getenv("LOCALAPPDATA"))).toAbsolutePath().normalize()
        check(root.startsWith(privateRoot))
        val file = root.resolve("plugin-settings.json")
        if (!Files.exists(file, NOFOLLOW_LINKS)) return false
        check(Files.isRegularFile(file, NOFOLLOW_LINKS) && !Files.isSymbolicLink(file))
        val settings = Json.parseToJsonElement(Files.readString(file)).jsonObject["settings"]?.jsonObject ?: return false
        return settings["windows_liquid_glass_default_v1"]?.jsonPrimitive?.booleanOrNull == true &&
            settings["android_native_liquid_glass_enabled"]?.jsonPrimitive?.booleanOrNull == true
    }

    private fun glassControl(): AccessibleContext {
        current(); check(routes.currentKey == BiliPaiNavKey.AppearanceSettings)
        return all().filter { node -> hasLabel(node, "原版液态玻璃") && visible(node) &&
            node.accessibleStateSet.contains(AccessibleState.ENABLED) &&
            (node.accessibleAction?.accessibleActionCount ?: 0) == 1 }.single()
    }

    private fun exerciseDefaultGlassAppearance() {
        await("same actual Home before typed appearance content") { edt {
            val frame = pendingCurrent() ?: return@edt false
            frame.key == BiliPaiNavKey.Home && frame.pagerHosted && routes.currentKey == BiliPaiNavKey.MainHost
        } }
        actions.capture("glass-default-home-sidebar", edt { current() })
        val prior = edt { current().serial }
        edt { current(); check(routes.push(BiliPaiNavKey.AppearanceSettings)) }
        val expectedStatus = "原版玻璃：渲染器可用，原版纯色背景已就绪"
        await("actual appearance default glass ON and real renderer/background draw ready") {
            privateGlassDefaultMigrated() && edt {
                val frame = pendingCurrent() ?: return@edt false
                if (frame.serial <= prior || frame.key != BiliPaiNavKey.AppearanceSettings || routes.currentKey != frame.key) return@edt false
                runCatching {
                    val switches = descendants(glassControl()).filter { it.accessibleRole == javax.accessibility.AccessibleRole.CHECK_BOX ||
                        it.accessibleRole == javax.accessibility.AccessibleRole.TOGGLE_BUTTON }
                    check(switches.size == 1)
                    val checked = switches.single().accessibleStateSet.let {
                        it.contains(AccessibleState.CHECKED) || it.contains(AccessibleState.SELECTED)
                    }
                    checked && all().count { it.accessibleName == expectedStatus && visible(it) } == 1
                }.getOrDefault(false)
            }
        }
        actions.capture("glass-default-appearance", edt { current() })
        record("glass-default-appearance", mapOf("entryMechanism" to JsonPrimitive("ACTUAL_SAME_ROUTES_PUSH_ON_EDT"),
            "naturalAppearanceNavigationAccepted" to JsonPrimitive(false), "actualDefaultGlassChecked" to JsonPrimitive(true),
            "actualMigrationAndPreferenceDurable" to JsonPrimitive(true), "actualStatusText" to JsonPrimitive(expectedStatus),
            "shaderCapabilityAvailable" to JsonPrimitive(true), "actualBackgroundDrawReady" to JsonPrimitive(true),
            "configuredWallpaper" to JsonPrimitive(false), "fixtureTextureSeeded" to JsonPrimitive(false),
            "nativeVideoUsedAsBackdrop" to JsonPrimitive(false), "naturalWallpaperUploadAccepted" to JsonPrimitive(false)))
        val beforeBack = edt { current().serial }
        ownedKey(edt { actualComposeInput() }, java.awt.event.KeyEvent.VK_ESCAPE, navigationTarget = BiliPaiNavKey.Home)
        await("actual appearance Escape returns same Home") { edt {
            val frame = pendingCurrent() ?: return@edt false
            frame.serial > beforeBack && frame.key == BiliPaiNavKey.Home && frame.pagerHosted &&
                routes.currentKey == BiliPaiNavKey.MainHost && routes.stack.toList() == listOf(BiliPaiNavKey.MainHost)
        } }
    }

    private fun nvidiaDialogSurface(): Window? {
        current()
        val main = window()
        val surfaces = listOf<Window>(main) + Window.getWindows().filterIsInstance<javax.swing.JDialog>()
            .filter { it.isShowing && it.isDisplayable && ownedWindow(it) }
        val matches = surfaces.filter { surface -> descendants(surface.accessibleContext).let { nodes ->
            nodes.any { it.accessibleName == "NVIDIA 自动增强" && visible(it, surface) } &&
                nodes.any { hasLabel(it, "NVIDIA 自动增强") &&
                    it.accessibleName.orEmpty().contains("所有视频统一使用 NVIDIA 视频增强") &&
                    visible(it, surface) && (it.accessibleAction?.accessibleActionCount ?: 0) == 1 } &&
                nodes.count { it.accessibleName == "完成" && visible(it, surface) &&
                    (it.accessibleAction?.accessibleActionCount ?: 0) == 1 } == 1
        } }
        check(matches.size <= 1) { "More than one complete owned NVIDIA enhancement dialog" }
        return matches.singleOrNull()
    }

    private fun nvidiaSurface(): Window {
        current()
        if (routes.currentKey is BiliPaiNavKey.VideoDetail)
            return requireNotNull(nvidiaDialogSurface()) { "Video enhancement must use its complete actual owned dialog" }
        check(routes.currentKey == BiliPaiNavKey.PlaybackSettings)
        return window()
    }

    /** Same owned Skia screenshot API as the existing Main/Aicu fixtures; no global capture. */
    private fun captureOwnedExtraSurface(id: String, surface: Window) {
        current(); check(surface.isShowing && surface.isDisplayable && surface !== window() && ownedWindow(surface))
        val nodes = descendants(surface.accessibleContext)
        Files.writeString(report.resolve("$id-accessibility.tsv"), nodes.joinToString("\n") {
            "${it.accessibleName}\t${it.accessibleRole}\t${it.accessibleStateSet}\t${it.accessibleAction?.accessibleActionCount ?: 0}"
        }, CREATE_NEW, WRITE)
        val type = Class.forName("org.jetbrains.skiko.SkiaLayer")
        val layer = nativeComponents(surface).filter { type.isInstance(it) }.single()
        val renderer = type.getMethod("getRenderApi").invoke(layer).toString()
        check(renderer == "DIRECT3D")
        surface.javaClass.methods.firstOrNull { it.name == "renderImmediately" && it.parameterCount == 0 }?.invoke(surface)
        val bitmap = requireNotNull(type.getMethod("screenshot").invoke(layer))
        try {
            val imageType = Class.forName("org.jetbrains.skia.Image")
            val companion = imageType.getField("Companion").get(null)
            val image = companion.javaClass.getMethod("makeFromBitmap", bitmap.javaClass).invoke(companion, bitmap)
            try {
                val format = Class.forName("org.jetbrains.skia.EncodedImageFormat")
                val data = requireNotNull(imageType.getMethod("encodeToData", format, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType).invoke(image, format.getField("PNG").get(null), 100, 6))
                try { Files.write(report.resolve("$id.png"), data.javaClass.getMethod("getBytes").invoke(data) as ByteArray, CREATE_NEW, WRITE) }
                finally { data.javaClass.getMethod("close").invoke(data) }
            } finally { imageType.getMethod("close").invoke(image) }
        } finally { bitmap.javaClass.getMethod("close").invoke(bitmap) }
        Files.writeString(report.resolve("$id-frame.txt"), "${current().key}\n${current().serial}\n$renderer\n", CREATE_NEW, WRITE)
    }

    private fun nvidiaControl(): AccessibleContext {
        current()
        val surface = nvidiaSurface()
        val candidates = descendants(surface.accessibleContext).filter { hasLabel(it, "NVIDIA 自动增强") && visible(it, surface) &&
            it.accessibleStateSet.contains(AccessibleState.ENABLED) && (it.accessibleAction?.accessibleActionCount ?: 0) == 1 }
        check(candidates.size == 1) { "Expected exactly one visible NVIDIA control, got ${candidates.size}" }
        return candidates.single()
    }

    private fun nvidiaChecked(): Boolean {
        val switches = descendants(nvidiaControl()).filter {
            it.accessibleRole == javax.accessibility.AccessibleRole.CHECK_BOX ||
                it.accessibleRole == javax.accessibility.AccessibleRole.TOGGLE_BUTTON
        }
        check(switches.size == 1) { "NVIDIA row must contain its one actual switch" }
        return switches.single().accessibleStateSet.let { it.contains(AccessibleState.CHECKED) || it.contains(AccessibleState.SELECTED) }
    }

    private fun wheelNvidiaPane(rotation: Int) = edt {
        current()
        val input = actualComposeInput()
        val content = window().contentPane
        val client = Rectangle(content.locationOnScreen.x, content.locationOnScreen.y, content.width, content.height)
        val panes = all().filter { node ->
            val children = descendants(node)
            // The NVIDIA row has a merged accessible name (title plus subtitle),
            // so test each child with the exact-label parser. Settings body also
            // has its two unique headers when the lower NVIDIA row is offscreen.
            val settingsBody = children.any { it.accessibleName == "解码与画质" } &&
                children.any { it.accessibleName == "倍速与字幕" }
            node.accessibleStateSet.contains(AccessibleState.SHOWING) && (settingsBody ||
                (node.accessibleRole == javax.accessibility.AccessibleRole.SCROLL_PANE &&
                    children.any { hasLabel(it, "NVIDIA 自动增强") }))
        }
        check(panes.isNotEmpty()) { "No actual NVIDIA settings scroll pane" }
        val sizes = panes.associateWith { descendants(it).size }
        val minimum = requireNotNull(sizes.values.minOrNull())
        val pane = panes.filter { sizes[it] == minimum }.single()
        fun contextBounds(context: AccessibleContext): Rectangle {
            check(context.accessibleStateSet.contains(AccessibleState.SHOWING))
            val component = requireNotNull(context.accessibleComponent)
            val position = requireNotNull(component.locationOnScreen)
            check(component.size.width > 0 && component.size.height > 0)
            return Rectangle(position.x, position.y, component.size.width, component.size.height)
        }
        var scrollArea = contextBounds(pane).intersection(client).intersection(
            Rectangle(input.locationOnScreen.x, input.locationOnScreen.y, input.width, input.height))
        var parent = pane.accessibleParent?.accessibleContext
        var depth = 0
        while (parent != null) {
            check(++depth <= 80) { "NVIDIA pane accessible parent cycle" }
            if (parent.accessibleStateSet.contains(AccessibleState.SHOWING) && parent.accessibleComponent != null)
                scrollArea = scrollArea.intersection(contextBounds(parent))
            parent = parent.accessibleParent?.accessibleContext
        }
        check(scrollArea.width > 20 && scrollArea.height > 20) { "NVIDIA scroll pane has no visible owned client area" }
        val screen = java.awt.Point(scrollArea.x + scrollArea.width / 2, scrollArea.y + scrollArea.height / 2)
        val point = java.awt.Point(screen.x - input.locationOnScreen.x, screen.y - input.locationOnScreen.y)
        check(input.contains(point) && client.contains(screen) && scrollArea.contains(screen))
        // Skia itself uses java.awt.Canvas. Only the existing MpvPlayer-owned Canvas
        // is a video viewport, and its visible parent intersections define its hit area.
        val nativeAreas = nativeComponents(window()).filterIsInstance<Canvas>().filter { canvas ->
            canvas.isShowing && canvas.isDisplayable && SwingUtilities.getWindowAncestor(canvas) === window() &&
                canvas.javaClass.enclosingClass == MpvPlayer::class.java &&
                canvas.javaClass.declaredFields.count { it.type == MpvPlayer::class.java } == 1
        }.mapNotNull { canvas ->
            var area = Rectangle(canvas.locationOnScreen.x, canvas.locationOnScreen.y, canvas.width, canvas.height).intersection(client)
            var ancestor: Component? = canvas.parent
            while (ancestor != null && ancestor !== window()) {
                check(ancestor.isShowing && ancestor.isDisplayable)
                area = area.intersection(Rectangle(ancestor.locationOnScreen.x, ancestor.locationOnScreen.y, ancestor.width, ancestor.height))
                ancestor = ancestor.parent
            }
            check(ancestor === window())
            area.takeIf { it.width > 0 && it.height > 0 }
        }
        check(nativeAreas.none { it.contains(screen) }) { "NVIDIA settings wheel would target the native video viewport" }
        record("nvidia-wheel-${rows.size}", mapOf("inputMechanism" to JsonPrimitive("OWNED_COMPOSE_AWT_MOUSE_WHEEL"),
            "screenX" to JsonPrimitive(screen.x), "screenY" to JsonPrimitive(screen.y),
            "visiblePaneX" to JsonPrimitive(scrollArea.x), "visiblePaneY" to JsonPrimitive(scrollArea.y),
            "visiblePaneWidth" to JsonPrimitive(scrollArea.width), "visiblePaneHeight" to JsonPrimitive(scrollArea.height),
            "actualVisibleVideoViewports" to JsonPrimitive(nativeAreas.size), "rotation" to JsonPrimitive(rotation)))
        input.dispatchEvent(java.awt.event.MouseWheelEvent(input, java.awt.event.MouseWheelEvent.MOUSE_WHEEL,
            System.currentTimeMillis(), 0, point.x, point.y, 0, false,
            java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation))
    }

    private fun ensureNvidiaVisible() {
        if (edt { routes.currentKey is BiliPaiNavKey.VideoDetail }) {
            await("complete visible NVIDIA switch in the actual owned enhancement dialog") {
                edt { runCatching { nvidiaControl() }.isSuccess }
            }
            return
        }
        repeat(70) {
            if (edt { runCatching { nvidiaControl() }.isSuccess }) return
            wheelNvidiaPane(3); Thread.sleep(140)
        }
        error("NVIDIA control was not reached by actual owned wheel input")
    }

    private fun toggleNvidia(expected: Boolean, id: String) {
        ensureNvidiaVisible()
        edt {
            check(nvidiaChecked() != expected) { "NVIDIA toggle must actually change the setting" }
            clickOwnedComposeMouse(nvidiaSurface(), nvidiaControl())
        }
        await("actual NVIDIA switch and same private Store publish $expected") {
            privateNvidiaEnabled() == expected && edt { runCatching { nvidiaChecked() == expected }.getOrDefault(false) }
        }
        val surface = edt { nvidiaSurface() }
        record(id, mapOf("actualChecked" to JsonPrimitive(expected), "actualDurableValue" to JsonPrimitive(expected),
            "actualNvidiaSurfaceClass" to JsonPrimitive(surface.javaClass.name),
            "actualNvidiaSurfaceKind" to JsonPrimitive(if (surface === edt { window() }) "inline-main" else "owned-dialog"),
            "inputMechanism" to JsonPrimitive("OWNED_COMPOSE_AWT_MOUSE_EVENT"), "fixturePreferenceWrite" to JsonPrimitive(false)))
        actions.capture(id, edt { current() })
        if (surface !== edt { window() }) edt { captureOwnedExtraSurface("$id-dialog", surface) }
    }

    private fun openNvidiaSettingsTyped() {
        await("actual Home before typed Windows settings") { edt {
            val frame = pendingCurrent() ?: return@edt false
            frame.key == BiliPaiNavKey.Home && frame.pagerHosted && routes.currentKey == BiliPaiNavKey.MainHost
        } }
        val prior = edt { current().serial }
        edt { current(); check(routes.push(BiliPaiNavKey.PlaybackSettings)) }
        await("actual typed PlaybackSettings drawn") { edt {
            val frame = pendingCurrent() ?: return@edt false
            frame.serial > prior && frame.key == BiliPaiNavKey.PlaybackSettings && routes.currentKey == frame.key &&
                all().any { it.accessibleName == "播放与音频" && it.accessibleRole == javax.accessibility.AccessibleRole.LABEL }
        } }
        ensureNvidiaVisible()
        record("nvidia-settings-entry-${rows.size}", mapOf("entryMechanism" to JsonPrimitive("ACTUAL_SAME_ROUTES_PUSH_ON_EDT"),
            "naturalSettingsNavigationAccepted" to JsonPrimitive(false), "directStackMutation" to JsonPrimitive(false)))
    }

    private fun backFromNvidiaSettings() {
        val prior = edt { current().serial }
        edt {
            current(); check(routes.currentKey == BiliPaiNavKey.PlaybackSettings)
            val title = all().filter { it.accessibleName == "播放与音频" &&
                it.accessibleRole == javax.accessibility.AccessibleRole.LABEL &&
                (it.accessibleAction?.accessibleActionCount ?: 0) == 0 && visible(it) }.single()
            val component = requireNotNull(title.accessibleComponent)
            val point = requireNotNull(component.locationOnScreen)
            val center = point.y + component.size.height / 2
            val back = all().filter { node ->
                if (!hasLabel(node, "返回") || node.accessibleRole != javax.accessibility.AccessibleRole.PUSH_BUTTON ||
                    (node.accessibleAction?.accessibleActionCount ?: 0) != 1 || !visible(node)) return@filter false
                val control = requireNotNull(node.accessibleComponent)
                val position = requireNotNull(control.locationOnScreen)
                kotlin.math.abs(position.y + control.size.height / 2 - center) <= 12 && position.x < point.x
            }.single()
            clickOwnedComposeMouse(window(), back)
        }
        await("actual settings Back returns the same Home") { edt {
            val frame = pendingCurrent() ?: return@edt false
            frame.serial > prior && frame.key == BiliPaiNavKey.Home && frame.pagerHosted &&
                routes.currentKey == BiliPaiNavKey.MainHost && routes.stack.toList() == listOf(BiliPaiNavKey.MainHost)
        } }
    }

    private fun exerciseNoSourceNvidiaSettings() {
        openNvidiaSettingsTyped()
        await("actual initial NVIDIA migration ON") { privateNvidiaEnabled() == true && edt { nvidiaChecked() } }
        toggleNvidia(false, "nvidia-no-source-off")
        backFromNvidiaSettings()
        openNvidiaSettingsTyped()
        check(privateNvidiaEnabled() == false && edt { !nvidiaChecked() })
        record("nvidia-no-source-reopen-off", mapOf("actualDurableValue" to JsonPrimitive(false),
            "reopenedWithinSameMain" to JsonPrimitive(true), "coldProcessAccepted" to JsonPrimitive(false),
            "playbackRouteSubmittedByFixture" to JsonPrimitive(false)))
        toggleNvidia(true, "nvidia-no-source-on")
        backFromNvidiaSettings()
    }

    private fun playerMenuSurface(): Window? {
        current()
        val main = window()
        // Popup identity does not depend on a lower item already being scrolled
        // into view. The later click still requires its one wholly visible action.
        val popups = Window.getWindows().filterIsInstance<javax.swing.JDialog>().filter {
            it.isShowing && it.isDisplayable && it.owner === main &&
                ownedWindow(it) && !it.isModal && it.title == "播放操作"
        }
        check(popups.size <= 1) { "More than one owned modeless player operation menu" }
        popups.singleOrNull()?.let { return it }
        // Preserve the existing inline fallback for callers that already had it.
        val inline = descendants(main.accessibleContext).count { node ->
            node.accessibleName == "简介、分P与播放设置" && visible(node, main) &&
                node.accessibleStateSet.contains(AccessibleState.ENABLED) &&
                (node.accessibleAction?.accessibleActionCount ?: 0) == 1
        }
        check(inline <= 1) { "More than one complete inline player operation menu" }
        return main.takeIf { inline == 1 }
    }

    /** Real bounded OS wheel input into this existing menu peer; no ScrollState/action writes. */
    private fun ensurePlayerMenuItemVisible(surface: Window, label: String) {
        check(!EventQueue.isDispatchThread())
        val (main, source, canvas) = edt {
            current(); sameNative(); Triple(window(), accepted, actualCanvas)
        }
        val peerBounds = edt { Rectangle(surface.bounds) }
        fun guard() {
            current(); sameNative()
            check(window() === main && accepted === source && actualCanvas === canvas &&
                actualPlayer.ownsSourceSnapshot(source)) { "Player menu source/Main/Canvas was retired" }
            check(surface.isShowing && surface.isDisplayable && ownedWindow(surface) &&
                surface.bounds == peerBounds && playerMenuSurface() === surface) { "Exact player menu peer was retired or moved" }
        }
        fun item(): AccessibleContext? {
            val matches = descendants(surface.accessibleContext).filter { node ->
                hasLabel(node, label) && node.accessibleRole != javax.accessibility.AccessibleRole.SCROLL_PANE &&
                    node.accessibleStateSet.contains(AccessibleState.ENABLED) &&
                    (node.accessibleAction?.accessibleActionCount ?: 0) == 1
            }
            check(matches.size <= 1) { "More than one original player menu action: $label" }
            return matches.singleOrNull()
        }
        await("one original enabled '$label' action in the owned player menu tree") { edt {
            guard(); item() != null
        } }
        var robot: java.awt.Robot? = null
        // Thirty-two single-notch wheels, and a final read, are the fixed ceiling.
        for (attempt in 0..32) {
            val step = edt {
                guard()
                val target = item()
                if (target != null && visible(target, surface)) null else {
                    val input = actualComposeInput(surface)
                    val content = (surface as javax.swing.RootPaneContainer).contentPane
                    val area = Rectangle(content.locationOnScreen, content.size)
                        .intersection(Rectangle(input.locationOnScreen, input.size))
                        .intersection(surface.graphicsConfiguration.bounds)
                        .intersection(Rectangle(main.contentPane.locationOnScreen, main.contentPane.size))
                    check(area.width > 20 && area.height > 20) { "Player menu has no bounded visible owned wheel area" }
                    val point = java.awt.Point(area.x + area.width / 2, area.y + area.height / 2)
                    check(input.contains(java.awt.Point(point.x - input.locationOnScreen.x, point.y - input.locationOnScreen.y)))
                    val origin = target?.accessibleComponent?.locationOnScreen
                    Pair(point, if (origin != null && origin.y < area.y) -1 else 1)
                }
            } ?: return
            check(attempt < 32) { "Original player menu action remains outside its viewport after 32 OS wheels: $label" }
            val wheel = robot ?: java.awt.Robot().also { robot = it }
            wheel.mouseMove(step.first.x, step.first.y)
            wheel.delay(35)
            edt { guard() }
            wheel.mouseWheel(step.second)
            wheel.delay(120)
            edt { guard() }
        }
    }

    private fun exercisePlayerMenu() {
        sameNative(); check(playing())
        val before = actualPlayer.state.value.positionSeconds
        click("更多播放操作")
        await("actual visible player operation menu") { edt { playerMenuSurface() != null } }
        val surface = edt { requireNotNull(playerMenuSurface()) }
        edt {
            check(surface is javax.swing.JDialog && !surface.isModal && ownedWindow(surface)) {
                "Player menu must be a modeless owned native window above the Canvas"
            }
            check(window().bounds.contains(surface.bounds)) { "Player menu must fit within the actual owner window" }
        }
        ensurePlayerMenuItemVisible(surface, "简介、分P与播放设置")
        actions.capture("112-owned-player-menu", edt { current() })
        if (surface !== edt { window() }) edt { captureOwnedExtraSurface("112-owned-player-menu-popup", surface) }
        edt {
            current(); sameNative(); check(playerMenuSurface() === surface)
            val item = descendants(surface.accessibleContext).filter { node ->
                node.accessibleName == "简介、分P与播放设置" && visible(node, surface) &&
                    node.accessibleStateSet.contains(AccessibleState.ENABLED) &&
                    (node.accessibleAction?.accessibleActionCount ?: 0) == 1
            }.single()
            clickOwnedComposeMouse(surface, item)
        }
        await("actual menu item opens the right detail pane and closes its menu") { edt {
            playerMenuSurface() == null && runCatching { detailPaneScope() }.isSuccess
        } }
        Thread.sleep(700)
        sameNative(); check(playing())
        val after = actualPlayer.state.value.positionSeconds
        check(after > before + .25 && actualPlayer.state.value.volume == 0.0 && actualPlayer.state.value.muted)
        record("112-owned-player-menu", mapOf("actualOpenControlUsed" to JsonPrimitive("更多播放操作"),
            "actualMenuItemUsed" to JsonPrimitive("简介、分P与播放设置"), "actualMenuItemWhollyVisible" to JsonPrimitive(true),
            "actualMenuSurfaceClass" to JsonPrimitive(surface.javaClass.name),
            "ownedModelessPopup" to JsonPrimitive(true), "popupFitsOwnerWindow" to JsonPrimitive(true),
            "actualMenuSurfaceKind" to JsonPrimitive(if (surface === edt { window() }) "inline-main" else "owned-popup"),
            "sameNativeSource" to JsonPrimitive(true), "clockBefore" to JsonPrimitive(before), "clockAfter" to JsonPrimitive(after),
            "volumeAndMutePreserved" to JsonPrimitive(true)))
        if (System.getProperty("bilipai.validation.metadataInput") == "true") exerciseVideoMetadata("112")
        if (System.getProperty("bilipai.validation.featureInput") == "true") exerciseDanmakuSettings()
        click("关闭详情")
        await("actual menu detail close restores compact player") { edt {
            // Compose may publish removed-panel semantics before restoring the sibling bar/header tree.
            // Require the same real native owner plus the complete original controls before the next click.
            sameNative()
            all().none { it.accessibleName == "关闭详情" && visible(it) } &&
                runCatching { videoScope("NVIDIA 增强详情") }.isSuccess
        } }
        sameNative(); check(playing())
    }

    private fun exerciseVideoMetadata(stage: String) {
        sameNative(); check(playing())
        val source = requireNotNull(actualPlayer.currentSourceSnapshot())
        fun named(node: AccessibleContext, label: String) =
            Regex("(^|[\\r\\n,，])\\s*${Regex.escape(label)}\\s*($|[\\r\\n,，])")
                .containsMatchIn(node.accessibleName.orEmpty())
        fun labelVisible(label: String) = edt {
            sameNative()
            val scope = runCatching { detailPaneScope() }.getOrNull() ?: return@edt false
            descendants(scope).any { named(it, label) && visible(it) }
        }
        val labels = listOf("每周必看验收", "演绎内容，仅作原版声明布局验收", "未经作者授权，请勿转载",
            "创作团队", "共 1 位", "原版团队验收 头像")
        for ((index, label) in labels.withIndex()) {
            await("original video metadata loaded: $label") { edt {
                // A real Tab click can temporarily publish only its focused node.
                // Wait for the complete same-source details tree, without retrying input.
                sameNative()
                val scope = runCatching { detailPaneScope() }.getOrNull() ?: return@edt false
                descendants(scope).any { named(it, label) }
            } }
            for (attempt in 0 until 16) {
                if (labelVisible(label)) break
                ownedWheel(edt { actualComposeInput() }, 1, false); Thread.sleep(150)
            }
            check(labelVisible(label)) { "Original metadata is outside its visible details viewport: $label" }
            if (index in listOf(0, 2, 5)) actions.capture("$stage-original-metadata-$index", edt { current() })
        }
        edt {
            val members = descendants(detailPaneScope()).filter {
                named(it, "原版团队验收 头像") && visible(it) &&
                    (it.accessibleAction?.accessibleActionCount ?: 0) == 1
            }
            check(members.isNotEmpty()) { "Original creator chip lost its space navigation action" }
            check(descendants(detailPaneScope()).any { hasLabel(it, "关注") && visible(it) &&
                (it.accessibleAction?.accessibleActionCount ?: 0) == 1 }) { "Original team follow action is missing" }
        }
        sameNative()
        check(actualPlayer.ownsSourceSnapshot(source))
        record("$stage-original-video-metadata", mapOf(
            "actualOriginalLabels" to JsonArray(labels.map(::JsonPrimitive)),
            "originalTeamNavigationAndFollowControlsVisible" to JsonPrimitive(true),
            "sameActualPlayerAndSource" to JsonPrimitive(true), "sourceVersion" to JsonPrimitive(source.sourceVersion),
            "realAccountUsed" to JsonPrimitive(false), "honorNavigationAccepted" to JsonPrimitive(false),
            "creatorFollowSubmitted" to JsonPrimitive(false)))
    }

    private fun ownedFeatureSurface(vararg anchors: String): Window? {
        current()
        val main = window()
        val surfaces = listOf<Window>(main) + Window.getWindows().filter {
            it !== main && it.isShowing && it.isDisplayable && ownedWindow(it) &&
                (it is javax.swing.JWindow || it is javax.swing.JDialog)
        }
        return surfaces.filter { surface ->
            val children = descendants(surface.accessibleContext)
            anchors.all { label -> children.any { hasLabel(it, label) && visible(it, surface) } }
        }.also { check(it.size <= 1) { "Ambiguous owned feature surface: ${anchors.toList()}" } }.singleOrNull()
    }

    private fun clickFeatureItem(surface: Window, label: String) {
        check(!EventQueue.isDispatchThread())
        if (edt { surface is javax.swing.JDialog && surface.owner === window() &&
                !surface.isModal && surface.title == "播放操作" }) ensurePlayerMenuItemVisible(surface, label)
        await("owned feature tree restores one real '$label' control") { edt {
            current(); sameNative()
            check(surface.isShowing && surface.isDisplayable && ownedWindow(surface))
            val item = descendants(surface.accessibleContext).filter { node ->
                hasLabel(node, label) &&
                    node.accessibleRole != javax.accessibility.AccessibleRole.SCROLL_PANE && visible(node, surface) &&
                    node.accessibleStateSet.contains(AccessibleState.ENABLED) &&
                    (node.accessibleAction?.accessibleActionCount ?: 0) == 1
            }.singleOrNull() ?: return@edt false
            // Resolve and deliver on the same EDT turn; a successful delivery is
            // never retried while waiting for the caller's separate outcome oracle.
            clickOwnedComposeMouse(surface, item)
            true
        } }
    }

    private fun exerciseChapterControls() {
        sameNative(); check(playing())
        click("暂停")
        await("actual Pause before exact chapter seek") { sameNative(); actualPlayer.state.value.nativePaused == true }
        val beforeLayers = settledMainInputLayers("chapter-before")
        fun choose(label: String, seconds: Double) {
            val seekId = actualPlayer.state.value.seekCompletedId
            click("视频章节")
            await("actual original chapter menu") { edt { ownedFeatureSurface("00:00 · 开场", "00:20 · 中段", "00:40 · 收尾") != null } }
            val surface = edt { requireNotNull(ownedFeatureSurface("00:00 · 开场", "00:20 · 中段", "00:40 · 收尾")) }
            if (seconds == 20.0) {
                actions.capture("114-original-chapter-menu", edt { current() })
                if (surface !== edt { window() }) edt { captureOwnedExtraSurface("114-original-chapter-menu-popup", surface) }
            }
            clickFeatureItem(surface, label)
            await("actual MPV chapter seek completion at $seconds seconds") {
                sameNative(); actualPlayer.state.value.let {
                    it.seekCompletedId > seekId && it.error == null && it.nativePaused == true &&
                        kotlin.math.abs((it.seekCompletedPositionSeconds ?: -100.0) - seconds) < .6 &&
                        kotlin.math.abs(it.positionSeconds - seconds) < .6
                }
            }
            await("chapter native menu closes before Main tooltip settlement") { edt {
                ownedFeatureSurface("00:00 · 开场", "00:20 · 中段", "00:40 · 收尾") == null &&
                    runCatching { videoScope("视频章节") }.isSuccess
            } }
            settledMainInputLayers("chapter-after-$seconds")
            await("chapter menu input layer retires") { edt {
                val layers = actualMainSceneLayers()
                ownedFeatureSurface("00:00 · 开场", "00:20 · 中段", "00:40 · 收尾") == null &&
                    layers.size == beforeLayers.size && layers.all { layer -> beforeLayers.any { it === layer } } &&
                    runCatching { videoScope("视频章节") }.isSuccess
            } }
            record(if (seconds == 20.0) "114-original-chapter-seek" else "115-original-chapter-return", mapOf(
                "actualControlUsed" to JsonPrimitive(label), "expectedPositionSeconds" to JsonPrimitive(seconds),
                "nativeState" to safeState(), "sameNativeSource" to JsonPrimitive(true),
                "previousSeekId" to JsonPrimitive(seekId), "inputMechanism" to JsonPrimitive("OWNED_COMPOSE_AWT_MOUSE_EVENT")))
        }
        choose("00:20 · 中段", 20.0)
        choose("00:00 · 开场", 0.0)
        click("播放")
        await("actual chapter source resumes without replacement") { sameNative(); playing() }
    }

    /** Fixed read-only path from this actual native source to its original owner.
     * The one lambda's typed receiver is inspected; no arbitrary object graph,
     * credential primitive or callback is read or invoked. */
    private fun actualHotOwner(): Pair<DesktopOriginalVideoOwnerAssembly, DesktopOriginalVideoAcceptedPublication> = edt {
        current(); sameNative()
        val initial = accepted.source.nativePublication as? DesktopOriginalVideoInitialPublication
            ?: error("Hot UI proof requires the actual original initial native publication")
        val predicate = DesktopOriginalVideoInitialPublication::class.java.getDeclaredField("ownsAccepted")
            .apply { check(trySetAccessible()) }.get(initial)
        check(predicate is Function0<*>)
        val nativeFields = predicate.javaClass.declaredFields.filter { it.type == DesktopOriginalVideoNativeOwner::class.java }
        check(nativeFields.size == 1) { "Original publication must capture exactly its one native owner" }
        val native = nativeFields.single().apply { check(trySetAccessible()) }.get(predicate) as DesktopOriginalVideoNativeOwner
        check(native.player === actualPlayer)
        val entry = DesktopOriginalVideoNativeOwner::class.java.getDeclaredField("isEntryCurrent")
            .apply { check(trySetAccessible()) }.get(native) as? kotlin.jvm.internal.CallableReference
            ?: error("Original native owner must retain its actual owner::owns reference")
        check(entry.name == "owns")
        val assembly = entry.boundReceiver as? DesktopOriginalVideoOwnerAssembly
            ?: error("Original source entry receiver is not its actual Assembly")
        check(assembly.javaClass == DesktopOriginalVideoOwnerAssembly::class.java && assembly.native === native &&
            assembly.section.nativePlayer === actualPlayer && assembly.owns())
        val source = requireNotNull(native.current())
        check(native.isCurrent(source) && source.nativeSource.sourceVersion == accepted.sourceVersion &&
            source.nativeSource.source == accepted.source && actualPlayer.ownsSourceSnapshot(accepted))
        val binding = assembly.environment.danmaku
        check(binding.javaClass == DesktopOriginalVideoOwnerDanmakuBinding::class.java)
        val overlay = DesktopOriginalVideoOwnerDanmakuBinding::class.java.getDeclaredField("overlay")
            .apply { check(trySetAccessible()) }.get(binding) as com.bilipai.desktop.danmaku.DanmakuOverlay
        val playerField = com.bilipai.desktop.danmaku.DanmakuOverlay::class.java.getDeclaredField("player")
            .apply { check(trySetAccessible()) }
        check(playerField.get(overlay) === actualPlayer)
        assembly to source
    }

    private fun exerciseHotDanmaku(localReplay: WindowsVideoLocalReplay) {
        check(System.getProperty("bilipai.validation.featureInput") == "true") {
            "Hot proof follows the unchanged original empty-pool feature proof"
        }
        sameNative(); check(playing())
        val originalSource = accepted
        val (assembly, source) = actualHotOwner()
        fun pureRootOwned(): Boolean {
            val frame = latest.get() ?: return false
            return frame.handle === owner && frame.routes === routes && owner.isActive() &&
                owner.route.get() === routes && owner.retainer.root.value === routes.root &&
                routes.root.entry.gate.scope.coroutineContext[kotlinx.coroutines.Job]?.isActive == true &&
                (frame.key as? BiliPaiNavKey.VideoDetail)?.bvid == video
        }
        fun assertNoMutation() {
            // The existing private replay records fixed origin/path/method only.
            // No account/header/query value is inspected or emitted here.
            val field = WindowsVideoLocalReplay::class.java.getDeclaredField("requests").apply { check(trySetAccessible()) }
            val requests = field.get(localReplay) as CopyOnWriteArrayList<*>
            check(requests.filterIsInstance<JsonObject>().none { request ->
                request["method"]?.jsonPrimitive?.contentOrNull == "POST" ||
                    request["path"]?.jsonPrimitive?.contentOrNull in setOf("/x/v2/dm/post", "/x/v2/dm/thumbup/add")
            }) { "Hot UI cancellation must not submit an account mutation" }
        }
        assertNoMutation()
        // Freeze only through the real original Pause control. Its 1100ms count
        // animation then finishes without advancing the original recent-item window.
        click("暂停")
        await("actual paused original source before hot count animation") {
            sameNative(); actualPlayer.state.value.nativePaused == true
        }
        val transport = WindowsHotDanmakuTransportFixture.install(assembly, actualPlayer, source,
            requireNotNull(System.getProperty("bilipai.rootValidationToken")), ::pureRootOwned)
        try {
            runBlocking { transport.reload() }
            fun hotSurface(): Window? = edt {
                ownedFeatureSurface("高赞验收", "×42", "发一条同款弹幕")
            }
            await("original high-like text and finished count render in actual native popup") { hotSurface() != null }
            val popup = requireNotNull(hotSurface())
            edt { captureOwnedExtraSurface("157-original-hot-danmaku-popup", popup) }
            fun hotItem(): AccessibleContext = edt {
                current(); sameNative()
                check(popup is javax.swing.JDialog && !popup.isModal && popup !== window() && ownedWindow(popup))
                val children = descendants(popup.accessibleContext)
                fun bounds(node: AccessibleContext): Rectangle {
                    val component = requireNotNull(node.accessibleComponent)
                    return Rectangle(component.locationOnScreen, component.size)
                }
                fun anchor(label: String) = bounds(children.single {
                    it.accessibleName == label && visible(it, popup)
                })
                // The original FlowRow flattens both items into one accessibility
                // group. Locate the real send control between its count and the
                // next item's text, using their current measured screen bounds.
                val text = anchor("高赞验收")
                val count = anchor("×42")
                val next = anchor("同款验收")
                check(text.x + text.width <= count.x && count.x + count.width < next.x)
                children.single { node ->
                    hasLabel(node, "发一条同款弹幕") && visible(node, popup) &&
                        node.accessibleStateSet.contains(AccessibleState.ENABLED) &&
                        (node.accessibleAction?.accessibleActionCount ?: 0) == 1 && bounds(node).let { box ->
                            box.x >= count.x + count.width && box.x + box.width <= next.x &&
                                box.y < text.y + text.height && box.y + box.height > text.y
                        }
                }
            }
            hotItem()
            actions.capture("157-original-hot-danmaku", edt { current() })
            val beforeLayers = settledMainInputLayers("hot-confirmation-before")
            edt {
                clickOwnedComposeMouse(popup, hotItem())
            }
            await("original same-send confirmation in its owned native dialog") { edt {
                ownedFeatureSurface("发送同款弹幕？", "将在当前播放位置发送：\n高赞验收", "发送", "取消") != null
            } }
            val confirmation = edt {
                requireNotNull(ownedFeatureSurface("发送同款弹幕？", "将在当前播放位置发送：\n高赞验收", "发送", "取消"))
            }
            edt {
                check(confirmation is javax.swing.JDialog && confirmation.isModal && confirmation !== popup && ownedWindow(confirmation))
                captureOwnedExtraSurface("157-original-hot-same-send-confirmation", confirmation)
            }
            clickFeatureItem(confirmation, "取消")
            await("cancel removes actual same-send native window and returns the original hot popup") { edt {
                val layers = actualMainSceneLayers()
                !confirmation.isShowing && !confirmation.isDisplayable &&
                    ownedFeatureSurface("发送同款弹幕？", "发送", "取消") == null &&
                    layers.size == beforeLayers.size && layers.all { layer -> beforeLayers.any { it === layer } } &&
                    runCatching { hotItem() }.isSuccess
            } }
            sameNative(); check(accepted == originalSource && actualPlayer.ownsSourceSnapshot(originalSource))
            check(!assembly.playback.isSendingDanmaku.value); assertNoMutation()
            record("157-original-hot-same-send-cancel", mapOf(
                "originalHotTextVisible" to JsonPrimitive("高赞验收"), "originalAnimatedCountVisible" to JsonPrimitive("×42"),
                "actualSameSendControlUsed" to JsonPrimitive("发一条同款弹幕"), "actualOriginalConfirmationVisible" to JsonPrimitive(true),
                "actualCancelControlUsed" to JsonPrimitive("取消"), "sameActualPlayerOverlaySource" to JsonPrimitive(true),
                "realAccountUsed" to JsonPrimitive(false), "remoteLikeOrSendSubmitted" to JsonPrimitive(false)))
        } finally {
            transport.close()
            Files.writeString(report.resolve("original-hot-transport.json"), transport.receipt().toString(), CREATE_NEW, WRITE)
        }
        sameNative(); assertNoMutation()
        click("播放")
        await("original source resumes after actual hot confirmation cancellation") { sameNative(); playing() }
        clockAndCapture("157-original-hot-cancel-resumed")
    }

    /** Read only the already accepted original owner and its synchronous BGM stamp.
     * Successful request completion is not treated as metadata retirement. */
    private fun awaitBgmOwner(cid: Long, musicIds: List<String>): Pair<DesktopOriginalVideoOwnerAssembly, DesktopOriginalVideoBgmResult> {
        var captured: Pair<DesktopOriginalVideoOwnerAssembly, DesktopOriginalVideoBgmResult>? = null
        await("same original BGM publication for CID $cid") {
            val (assembly, source) = actualHotOwner()
            val music = assembly.playback.captureDesktopBgmResult() ?: return@await false
            val session = assembly.playback.captureDesktopLoadState()
            val songs = com.android.purebilibili.feature.video.ui.section.resolveDisplayBgmList(music.bgmInfo, music.bgmInfoList)
            if (source.request.bvid != video || source.request.cid != cid || music.bvid != video || music.cid != cid ||
                session.currentBvid != video || session.currentCid != cid ||
                session.currentLoadRequestToken != music.requestToken || songs.map { it.musicId } != musicIds ||
                !desktopWindowsVideoBgmMatchesSource(music, assembly.playback.captureDesktopBgmResult(), source.request)) return@await false
            captured = assembly to music; true
        }
        return requireNotNull(captured)
    }

    private fun openBgmIntroduction(header: String) {
        sameNative()
        if (edt { all().none { it.accessibleName == "关闭详情" && visible(it) } }) click("详情")
        await("complete current BGM details pane") { edt { sameNative(); runCatching { detailPaneScope() }.isSuccess } }
        edt {
            current(); sameNative()
            val tab = descendants(detailPaneScope()).single { it.accessibleName == "简介与分P" &&
                it.accessibleRole == javax.accessibility.AccessibleRole.PAGE_TAB && visible(it) }
            if (!tab.accessibleStateSet.contains(AccessibleState.SELECTED) && !tab.accessibleStateSet.contains(AccessibleState.CHECKED)) {
                clickOwnedComposeMouse(window(), tab)
            }
        }
        await("original introduction tree after real tab input") { edt {
            sameNative()
            val scope = runCatching { detailPaneScope() }.getOrNull() ?: return@edt false
            descendants(scope).count { it.accessibleName == "BGM" &&
                it.accessibleStateSet.contains(AccessibleState.ENABLED) && (it.accessibleAction?.accessibleActionCount ?: 0) == 1 } == 1
        } }
        val (assembly, source) = actualHotOwner()
        val music = requireNotNull(assembly.playback.captureDesktopBgmResult())
        check(desktopWindowsVideoBgmMatchesSource(music, assembly.playback.captureDesktopBgmResult(), source.request))
        val songs = com.android.purebilibili.feature.video.ui.section.resolveDisplayBgmList(music.bgmInfo, music.bgmInfoList)
        check(songs.isNotEmpty())
        val expectedText = buildString {
            append("发现音乐《"); append(songs.first().musicTitle.ifBlank { "未知音乐" }); append("》")
            if (songs.size > 1) append("等${songs.size}首音乐")
            else songs.first().actor.takeIf { it.isNotBlank() }?.let { append(" · $it") }
        }
        check(expectedText == header) { "Original admitted BGM result does not match its expected visual row text" }
        fun headerVisible() = edt {
            sameNative()
            val scope = runCatching { detailPaneScope() }.getOrNull() ?: return@edt false
            descendants(scope).count { it.accessibleName == "BGM" && visible(it) &&
                it.accessibleStateSet.contains(AccessibleState.ENABLED) && (it.accessibleAction?.accessibleActionCount ?: 0) == 1 } == 1
        }
        // The original row merges its visual title into the music icon's "BGM"
        // semantics. Its admitted result proves the text; owned PNGs prove rendering.
        // Scroll the original bounded details viewport; never relocate its native Canvas.
        repeat(8) { if (!headerVisible()) { ownedWheel(edt { actualComposeInput() }, -3, false); Thread.sleep(100) } }
        repeat(20) { if (!headerVisible()) { ownedWheel(edt { actualComposeInput() }, 1, false); Thread.sleep(100) } }
        await("one fully visible original BGM row") { headerVisible() }
    }

    private fun clickBgmInlineRow() {
        await("one current fully visible original BGM row in its introduction scope") { edt {
            current(); sameNative()
            val scope = runCatching { detailPaneScope() }.getOrNull() ?: return@edt false
            val row = descendants(scope).filter { it.accessibleName == "BGM" && visible(it) &&
                it.accessibleStateSet.contains(AccessibleState.ENABLED) && (it.accessibleAction?.accessibleActionCount ?: 0) == 1 }.singleOrNull()
                ?: return@edt false
            clickOwnedComposeMouse(window(), row); true
        } }
    }

    private fun closeBgmIntroduction() {
        click("关闭详情")
        await("BGM details closed with the same native source") { edt {
            sameNative(); all().none { it.accessibleName == "关闭详情" && visible(it) } &&
                runCatching { videoScope("详情") }.isSuccess
        } }
    }

    private fun exerciseSingleBgm(localReplay: WindowsVideoLocalReplay) {
        sameNative(); check(playing())
        val originalSource = accepted
        val (assembly, music) = awaitBgmOwner(7007L, listOf("fixture-p1"))
        val header = "发现音乐《P1原音乐》 · 本地艺人"
        openBgmIntroduction(header)
        actions.capture("158-original-single-bgm-entry", edt { current() })
        val serial = edt { current().serial }
        clickBgmInlineRow()
        await("original single-song typed BGM detail route with its real CID") { edt {
            val frame = pendingCurrent() ?: return@edt false
            val key = frame.key as? BiliPaiNavKey.BgmDetail ?: return@edt false
            frame.serial > serial && key.musicId == "fixture-p1" && key.cid == 7007L && key.aid == 0L && !key.showVideos
        } }
        fun pageScope(): AccessibleContext = edt {
            current()
            val key = routes.currentKey as? BiliPaiNavKey.BgmDetail ?: error("Original BGM route retired")
            check(key.musicId == "fixture-p1" && key.cid == 7007L && actualPlayer.ownsSourceSnapshot(originalSource))
            val candidates = all().filter { scope ->
                val children = descendants(scope)
                listOf("音乐详情", "刷新", "P1原音乐", "这首音乐暂未开放评论").all { label ->
                    children.any { hasLabel(it, label) && visible(it) }
                } && children.count { hasLabel(it, "返回") && visible(it) &&
                    it.accessibleStateSet.contains(AccessibleState.ENABLED) && (it.accessibleAction?.accessibleActionCount ?: 0) == 1 } == 1
            }
            check(candidates.isNotEmpty()) { "No complete original loaded BGM detail scope" }
            val counts = candidates.associateWith { descendants(it).size }
            candidates.filter { counts.getValue(it) == counts.values.min() }.single()
        }
        val lastScopeFailure = AtomicReference<Throwable?>()
        try {
            await("complete original BGM detail from synthetic real-format response") { edt {
                runCatching { pageScope() }.onFailure { lastScopeFailure.set(it) }.isSuccess
            } }
        } catch (failure: Throwable) {
            // Failure-only inspection of this owned guest page. Preserve the exact
            // completion oracle and never invoke an action or publish player state.
            val diagnostic = edt {
                val children = all()
                val anchors = listOf("音乐详情", "刷新", "P1原音乐", "这首音乐暂未开放评论")
                val content = window().contentPane
                val viewport = Rectangle(content.locationOnScreen, content.size)
                fun geometry(node: AccessibleContext): JsonObject = buildJsonObject {
                    val bounds = runCatching {
                        val component = requireNotNull(node.accessibleComponent)
                        Rectangle(requireNotNull(component.locationOnScreen), component.size)
                    }.getOrNull()
                    put("role", node.accessibleRole.toString()); put("actions", node.accessibleAction?.accessibleActionCount ?: 0)
                    put("enabled", node.accessibleStateSet.contains(AccessibleState.ENABLED))
                    put("showing", node.accessibleStateSet.contains(AccessibleState.SHOWING))
                    put("passesExistingVisible", runCatching { visible(node) }.getOrDefault(false))
                    put("bounds", bounds?.let { buildJsonObject { put("x", it.x); put("y", it.y); put("width", it.width); put("height", it.height) } } ?: JsonNull)
                    put("fullyInsideOwnedClient", bounds?.let { viewport.contains(it) } ?: false)
                }
                val candidates = children.filter { scope ->
                    val nodes = descendants(scope)
                    anchors.all { label -> nodes.any { hasLabel(it, label) && runCatching { visible(it) }.getOrDefault(false) } } &&
                        nodes.count { hasLabel(it, "返回") && runCatching { visible(it) }.getOrDefault(false) &&
                            it.accessibleStateSet.contains(AccessibleState.ENABLED) && (it.accessibleAction?.accessibleActionCount ?: 0) == 1 } == 1
                }
                val sizes = candidates.map { descendants(it).size }
                val currentSource = actualPlayer.currentSourceSnapshot()
                val exception = lastScopeFailure.get()
                buildJsonObject {
                    put("observation", "FAILURE_ONLY_READONLY_ACTUAL_GUEST_BGM_PAGE")
                    put("expectedMusicId", "fixture-p1"); put("expectedCid", 7007)
                    put("frameKeyType", latest.get()?.key?.javaClass?.simpleName ?: "missing")
                    put("actualRouteType", routes.currentKey.javaClass.simpleName)
                    put("sameRootAndRouteAssembly", latest.get()?.let { it.handle === owner && it.routes === routes } == true)
                    put("assemblyOwned", assembly.owns()); put("rootOwned", owner.isActive() && routes.owns())
                    put("originalSourceVersion", originalSource.sourceVersion)
                    put("currentSourceVersion", currentSource?.sourceVersion?.let(::JsonPrimitive) ?: JsonNull)
                    put("originalSnapshotStillOwned", actualPlayer.ownsSourceSnapshot(originalSource))
                    put("currentFullSourceEqualsOriginal", currentSource?.source == originalSource.source)
                    put("sameNativePublicationIdentity", currentSource?.source?.nativePublication === originalSource.source.nativePublication)
                    put("candidateCount", candidates.size); put("candidateDescendantCounts", JsonArray(sizes.map(::JsonPrimitive)))
                    put("minimumCandidateTieCount", sizes.minOrNull()?.let { minimum -> sizes.count { it == minimum } } ?: 0)
                    put("anchorMatches", buildJsonObject {
                        (anchors + "返回").forEach { label -> put(label, buildJsonObject {
                            val matches = children.filter { hasLabel(it, label) }
                            put("directExactNameCount", matches.count { it.accessibleName == label })
                            put("existingHasLabelCount", matches.size)
                            put("visibleMatchCount", matches.count { runCatching { visible(it) }.getOrDefault(false) })
                            put("matches", JsonArray(matches.take(24).map(::geometry)))
                        }) }
                    })
                    put("lastFailureType", exception?.javaClass?.name?.let(::JsonPrimitive) ?: JsonNull)
                    put("lastFailureFixedMessage", exception?.message?.takeIf {
                        it in setOf("Check failed.", "No complete original loaded BGM detail scope", "List has more than one element.", "List is empty.", "Original BGM route retired")
                    }?.let(::JsonPrimitive) ?: JsonNull)
                    put("lastFailureFrames", JsonArray(exception?.stackTrace?.take(10)?.map { frame -> buildJsonObject {
                        put("class", frame.className); put("method", frame.methodName); put("line", frame.lineNumber)
                    } }.orEmpty()))
                    put("businessStateWritten", false); put("inputDelivered", false); put("urlsHeadersOrAccountValuesRecorded", false)
                }
            }
            Files.writeString(report.resolve("failure-original-bgm-detail-scope.json"), diagnostic.toString(), CREATE_NEW, WRITE)
            throw failure
        }
        localReplay.requireBgmRequests("fixture-p1", 7007L, discovery = false)
        actions.capture("158-original-single-bgm-detail", edt { current() })
        record("158-original-single-bgm-detail", mapOf(
            "actualOriginalInlineControlUsed" to JsonPrimitive("BGM"), "originalResultExpectedRenderedText" to JsonPrimitive(header),
            "originalResultMusicTitle" to JsonPrimitive(requireNotNull(music.bgmInfo).musicTitle),
            "originalResultActor" to JsonPrimitive(requireNotNull(music.bgmInfo).actor),
            "visualTextEvidence" to JsonPrimitive("OWNED_SCREENSHOT_ORIGINAL_MERGED_BGM_ROW"), "actualTypedMusicId" to JsonPrimitive("fixture-p1"),
            "actualTypedCid" to JsonPrimitive(7007), "originalMetadataCid" to JsonPrimitive(music.cid),
            "actualTypedShowVideos" to JsonPrimitive(false), "originalDetailOnlyRequestContract" to JsonPrimitive(true),
            "recommendationRequestObserved" to JsonPrimitive(false),
            "actualOriginalDetailLoaded" to JsonPrimitive(true), "realAccountUsed" to JsonPrimitive(false),
            "remoteWishOrCommentSubmitted" to JsonPrimitive(false)))
        val beforeBack = edt { current().serial }
        await("original BGM page Back input") { edt {
            val scope = runCatching { pageScope() }.getOrNull() ?: return@edt false
            val back = descendants(scope).filter { hasLabel(it, "返回") && visible(it) &&
                it.accessibleStateSet.contains(AccessibleState.ENABLED) && (it.accessibleAction?.accessibleActionCount ?: 0) == 1 }.single()
            clickOwnedComposeMouse(window(), back); true
        } }
        videoFrame(beforeBack)
        await("same original video controls restored after BGM Back") { edt {
            sameNative(); runCatching { videoScope("关闭详情") }.isSuccess
        } }
        check(assembly.owns() && actualPlayer.ownsSourceSnapshot(originalSource))
        awaitBgmOwner(7007L, listOf("fixture-p1"))
        if (actualPlayer.state.value.nativePaused == true) {
            click("播放"); await("original source resumes after BGM detail Back") { sameNative(); playing() }
        }
        closeBgmIntroduction(); clockAndCapture("158-original-single-bgm-return")
    }

    private fun wheelBgmSelection(surface: Window, rotation: Int) = edt {
        current(); sameNative(); check(surface.isShowing && surface.isDisplayable && ownedWindow(surface))
        val input = nativeComponents(surface).single { it.isShowing && it.isDisplayable &&
            SwingUtilities.getWindowAncestor(it) === surface && it.keyListeners.any { listener ->
                listener.javaClass.name == "androidx.compose.ui.scene.ComposeSceneMediator\$keyListener\$1"
            } }
        val content = (surface as javax.swing.RootPaneContainer).contentPane
        val viewport = Rectangle(content.locationOnScreen, content.size)
        val inputBounds = Rectangle(input.locationOnScreen, input.size)
        val area = viewport.intersection(inputBounds)
        check(area.width > 100 && area.height > 100)
        val point = java.awt.Point(area.x + area.width / 2 - inputBounds.x, area.y + area.height / 2 - inputBounds.y)
        check(input.contains(point))
        input.dispatchEvent(java.awt.event.MouseWheelEvent(input, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0,
            point.x, point.y, 0, false, java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation))
    }

    private fun exerciseMultipleBgmAndReturn(localReplay: WindowsVideoLocalReplay) {
        sameNative(); check(playing())
        val p2Source = accepted
        val (assembly, p2Music) = awaitBgmOwner(7008L, listOf("fixture-p2-a", "fixture-p2-b"))
        val header = "发现音乐《P2第一首》等2首音乐"
        openBgmIntroduction(header)
        check(p2Music.bgmInfoList.map { it.musicTitle } == listOf("P2第一首", "P2第二首"))
        val layers = settledMainInputLayers("bgm-selector-before")
        clickBgmInlineRow()
        fun selector(): Window? = edt { ownedFeatureSurface("发现音乐", "关闭", "P2第一首", "P2第二首") }
        await("original multi-song selector in a separate actual owned Windows dialog") { selector() != null }
        val surface = requireNotNull(selector())
        edt {
            check(surface is javax.swing.JDialog && surface !== window() && surface.title == "发现音乐" && ownedWindow(surface))
            val client = window().contentPane
            val viewport = Rectangle(client.locationOnScreen, client.size)
            val body = surface.contentPane
            val dialogClient = Rectangle(body.locationOnScreen, body.size)
            if (!viewport.contains(dialogClient)) runCatching {
                fun rect(value: Rectangle) = buildJsonObject {
                    put("x", value.x); put("y", value.y); put("width", value.width); put("height", value.height)
                }
                fun geometry(value: Window) = buildJsonObject {
                    put("class", value.javaClass.name); put("identity", System.identityHashCode(value))
                    put("outer", rect(value.bounds)); put("showing", value.isShowing); put("displayable", value.isDisplayable)
                    val pane = (value as javax.swing.RootPaneContainer).contentPane
                    put("client", rect(Rectangle(pane.locationOnScreen, pane.size)))
                    put("insets", buildJsonObject {
                        put("top", value.insets.top); put("left", value.insets.left)
                        put("bottom", value.insets.bottom); put("right", value.insets.right)
                    })
                    put("awtDensityX", value.graphicsConfiguration.defaultTransform.scaleX)
                    put("awtDensityY", value.graphicsConfiguration.defaultTransform.scaleY)
                }
                val frame = current()
                val currentLayers = actualMainSceneLayers()
                Files.writeString(report.resolve("failure-bgm-selector-geometry.json"), buildJsonObject {
                    put("scope", "FAILURE_ONLY_REAL_OWNED_WINDOW_GEOMETRY")
                    put("main", geometry(window())); put("dialog", geometry(surface))
                    put("configuredComposeScalePercent", privateScalePercent())
                    put("configuredComposeScaleIsNotObservedDensity", true)
                    put("mainClientContainsDialogClient", viewport.contains(dialogClient))
                    put("serial", frame.serial); put("keyType", frame.key.javaClass.simpleName)
                    put("ownedByActualMain", ownedWindow(surface)); put("dialogTitle", "发现音乐")
                    put("ownerChain", JsonArray(generateSequence(surface as Window?) { it.owner }.take(8).map { value ->
                        buildJsonObject {
                            put("class", value.javaClass.name); put("identity", System.identityHashCode(value))
                            put("isActualMain", value === window()); put("showing", value.isShowing)
                        }
                    }.toList()))
                    put("mainSceneLayerCountBefore", layers.size); put("mainSceneLayerCountAtFailure", currentLayers.size)
                    put("mainSceneLayersUnchanged", currentLayers.size == layers.size && currentLayers.all { layer -> layers.any { it === layer } })
                    put("mainSceneLayerClasses", JsonArray(currentLayers.map { JsonPrimitive(it.javaClass.name) }))
                    put("sameActualPlayerAndFullSource", actualPlayer.ownsSourceSnapshot(p2Source))
                }.toString(), CREATE_NEW, WRITE)
            }
            check(viewport.contains(dialogClient)) { "BGM selector exceeds its actual Main client: Main=$viewport Dialog=$dialogClient" }
        }
        edt { captureOwnedExtraSurface("159-original-bgm-selector-ready", surface) }
        fun requireRecommended(id: String, title: String, stage: String) {
            await("original BGM detail/recommend requests complete with CID7008 for $id") {
                sameNative()
                runCatching { localReplay.requireBgmRequests(id, 7008L, discovery = true) }.isSuccess
            }
            fun rendered() = edt { current(); sameNative(); descendants(surface.accessibleContext).any {
                hasLabel(it, "视频标题: ${title}关联视频") && visible(it, surface)
            } }
            repeat(14) { if (!rendered()) { wheelBgmSelection(surface, 1); Thread.sleep(100) } }
            edt { captureOwnedExtraSurface("$stage-before-visible-oracle", surface) }
            await("original related-video title actually visible in the bounded selector") { rendered() }
            edt { captureOwnedExtraSurface(stage, surface) }
        }
        requireRecommended("fixture-p2-a", "P2第一首", "159-original-bgm-first-recommendation")
        // Return to the real original strip, then deliver once to its only
        // clickable second-song Column (no second detail-card title exists yet).
        repeat(10) {
            val available = edt { descendants(surface.accessibleContext).count { hasLabel(it, "P2第二首") &&
                visible(it, surface) && it.accessibleStateSet.contains(AccessibleState.ENABLED) &&
                (it.accessibleAction?.accessibleActionCount ?: 0) == 1 } == 1 }
            if (!available) { wheelBgmSelection(surface, -2); Thread.sleep(100) }
        }
        clickFeatureItem(surface, "P2第二首")
        requireRecommended("fixture-p2-b", "P2第二首", "159-original-bgm-second-recommendation")
        // Header is part of the same original scroll content, not a fake toolbar.
        repeat(12) {
            val closeVisible = edt { descendants(surface.accessibleContext).count { hasLabel(it, "关闭") &&
                visible(it, surface) && (it.accessibleAction?.accessibleActionCount ?: 0) == 1 } == 1 }
            if (!closeVisible) { wheelBgmSelection(surface, -3); Thread.sleep(100) }
        }
        clickFeatureItem(surface, "关闭")
        await("original BGM close disposes its native window and restores the original video input layers") { edt {
            sameNative()
            val currentLayers = actualMainSceneLayers()
            !surface.isShowing && !surface.isDisplayable &&
                currentLayers.size == layers.size && currentLayers.all { layer -> layers.any { it === layer } } &&
                runCatching { detailPaneScope() }.isSuccess
        } }
        check(assembly.owns() && assembly.playback.captureDesktopBgmResult()?.request === p2Music.request &&
            actualPlayer.ownsSourceSnapshot(p2Source))
        record("159-original-multiple-bgm-selector", mapOf(
            "actualOriginalInlineControlUsed" to JsonPrimitive("BGM"), "originalResultExpectedRenderedText" to JsonPrimitive(header),
            "originalResultMusicTitles" to JsonArray(p2Music.bgmInfoList.map { JsonPrimitive(it.musicTitle) }),
            "originalResultActor" to JsonPrimitive(p2Music.bgmInfoList.first().actor),
            "visualTextEvidence" to JsonPrimitive("OWNED_SCREENSHOT_ORIGINAL_MERGED_BGM_ROW"),
            "originalBothSongsVisible" to JsonPrimitive(true), "actualOriginalSecondSongControlUsed" to JsonPrimitive("P2第二首"),
            "actualDiscoveryCid" to JsonPrimitive(7008), "actualOwnedNativeWindow" to JsonPrimitive(true),
            "originalDetailAndRecommendationsLoadedForBothSongs" to JsonPrimitive(true), "actualOriginalCloseUsed" to JsonPrimitive(true),
            "sourceVersion" to JsonPrimitive(p2Source.sourceVersion), "sameActualMpvAndCanvas" to JsonPrimitive(true),
            "remoteWishOrCommentSubmitted" to JsonPrimitive(false)))
        closeBgmIntroduction()
        click("更多播放操作")
        await("actual More menu for the original return-to-P1 collection") { edt {
            playerMenuSurface() != null
        } }
        clickFeatureItem(edt { requireNotNull(playerMenuSurface()) }, "视频合集")
        await("complete original collection for P2-to-P1 return") { edt {
            ownedFeatureSurface("合集", "展开简介", "关闭", "1.Local replay P1", "2.Local replay P2") != null
        } }
        val collection = edt { requireNotNull(ownedFeatureSurface("合集", "展开简介", "关闭", "1.Local replay P1", "2.Local replay P2")) }
        clickFeatureItem(collection, "1.Local replay P1")
        var p1Source: OwnedPlaybackSourceSnapshot? = null
        await("original P1 control replaces the actual native source on its same actor and Canvas") {
            val candidate = actualPlayer.currentSourceSnapshot() ?: return@await false
            if (candidate.sourceVersion <= p2Source.sourceVersion || candidate.source.nativePublication == null ||
                candidate.source.nativePublication === p2Source.source.nativePublication || !actualPlayer.ownsSourceSnapshot(candidate) || !playing()) return@await false
            edt {
                current()
                check(nativeComponents(window()).filterIsInstance<Canvas>().filter { it.isShowing && it.width > 100 && it.height > 80 &&
                    SwingUtilities.getWindowAncestor(it) === window() &&
                    it.javaClass.declaredFields.any { field -> field.type == MpvPlayer::class.java } }.single() === actualCanvas)
                check(SwingUtilities.getWindowAncestor(actualPlayer.surface) === window())
            }
            p1Source = candidate; true
        }
        accepted = requireNotNull(p1Source)
        check(!actualPlayer.ownsSourceSnapshot(p2Source))
        await("old collection and BGM input windows remain retired after source replacement") { edt {
            sameNative()
            !collection.isShowing && !collection.isDisplayable && !surface.isShowing && !surface.isDisplayable &&
                runCatching { videoScope("详情") }.isSuccess
        } }
        val (currentAssembly, p1Music) = awaitBgmOwner(7007L, listOf("fixture-p1"))
        check(currentAssembly === assembly && p1Music.request !== p2Music.request)
        val source = requireNotNull(assembly.native.current())
        check(!desktopWindowsVideoBgmMatchesSource(p2Music, assembly.playback.captureDesktopBgmResult(), source.request))
        openBgmIntroduction("发现音乐《P1原音乐》 · 本地艺人")
        edt {
            check(descendants(detailPaneScope()).count { it.accessibleName == "BGM" && visible(it) &&
                (it.accessibleAction?.accessibleActionCount ?: 0) == 1 } == 1)
            check(p1Music.bgmInfo?.musicTitle == "P1原音乐" && p1Music.bgmInfo?.actor == "本地艺人" && p1Music.bgmInfoList.isEmpty())
            check(Window.getWindows().none { it is javax.swing.JDialog && it.title == "发现音乐" && it.isShowing && ownedWindow(it) })
        }
        actions.capture("159-original-bgm-source-return-p1", edt { current() })
        record("159-original-bgm-source-retired", mapOf(
            "previousCid" to JsonPrimitive(7008), "currentCid" to JsonPrimitive(7007),
            "previousNativeSourceVersion" to JsonPrimitive(p2Source.sourceVersion), "newNativeSourceVersion" to JsonPrimitive(accepted.sourceVersion),
            "originalCollectionReturnControlUsed" to JsonPrimitive("1.Local replay P1"),
            "oldMetadataRequestRetired" to JsonPrimitive(true), "newOriginalMetadataRequestIdentity" to JsonPrimitive(true),
            "oldMultiSongUiAbsent" to JsonPrimitive(true),
            "visualTextEvidence" to JsonPrimitive("OWNED_SCREENSHOT_ORIGINAL_MERGED_BGM_ROW"),
            "currentOriginalInlineAccessibleLabel" to JsonPrimitive("BGM"),
            "currentOriginalSingleTitle" to JsonPrimitive("P1原音乐"), "currentOriginalSingleActor" to JsonPrimitive("本地艺人"),
            "oldOwnedSelectorRemainsDisposed" to JsonPrimitive(true),
            "sameActualMpvAndCanvas" to JsonPrimitive(true), "syntheticApiResponsesOnly" to JsonPrimitive(true),
            "remoteWishOrCommentSubmitted" to JsonPrimitive(false)))
        closeBgmIntroduction(); clockAndCapture("159-original-bgm-p1-playing")
    }

    private fun privateCollectionSort(): String? {
        val file = actions.local.resolve("BiliPaiWindows/plugin-settings.json")
        if (!Files.exists(file, NOFOLLOW_LINKS)) return null
        check(Files.isRegularFile(file, NOFOLLOW_LINKS) && !Files.isSymbolicLink(file))
        val settings = Json.parseToJsonElement(Files.readString(file)).jsonObject["settings"]?.jsonObject ?: return null
        val encoded = settings["collection_sort_preferences"]?.jsonPrimitive?.contentOrNull ?: return null
        return Json.parseToJsonElement(encoded).jsonObject["5007"]?.jsonPrimitive?.contentOrNull
    }

    /** Source replacement is intentional only here, after every same-source layout oracle.
     * All navigation and queue mutation comes from the complete original collection UI. */
    private fun exerciseCollectionAndQueue() {
        sameNative(); check(playing())
        val beforeLayers = settledMainInputLayers("collection-queue-before")
        val originalSource = accepted
        fun openFromMore(label: String) {
            sameNative(); click("更多播放操作")
            await("actual owned More menu for $label") { edt {
                playerMenuSurface() != null
            } }
            val menu = edt { requireNotNull(playerMenuSurface()) }
            clickFeatureItem(menu, label)
        }
        fun collection(): Window? = edt {
            // Original clickable intro merges its text into the disclosure's
            // semantics. Its full rendered text is preserved in the owned PNG.
            ownedFeatureSurface("合集", "展开简介", "关闭",
                "1.Local replay P1", "2.Local replay P2")
        }
        var collectionOpening = 0
        fun awaitCollection(): Window {
            val opening = ++collectionOpening
            await("actual owned collection native window and its original toolbar") { edt {
                val surface = Window.getWindows().filterIsInstance<javax.swing.JDialog>().singleOrNull {
                    it.isShowing && it.isDisplayable && it.title == "视频合集" && ownedWindow(it)
                } ?: return@edt false
                if (descendants(surface.accessibleContext).none { it.accessibleName == "合集" && visible(it, surface) }) return@edt false
                captureOwnedExtraSurface("158-collection-open-$opening", surface)
                true
            } }
            await("complete original collection in its owned bounded native Window") { collection() != null }
            return requireNotNull(collection()).also { surface -> edt {
                check(surface is javax.swing.JDialog && surface !== window() && ownedWindow(surface))
                check(descendants(surface.accessibleContext).count { it.accessibleName == "分享合集" && visible(it, surface) } == 1)
            } }
        }
        fun awaitClosed() {
            await("collection/queue native windows close before Main tooltip settlement") { edt {
                playerMenuSurface() == null && ownedFeatureSurface("合集", "关闭", "2.Local replay P2") == null &&
                    ownedFeatureSurface("关闭播放队列", "当前播放") == null &&
                    runCatching { videoScope("详情") }.isSuccess
            } }
            settledMainInputLayers("collection-queue-after-${rows.size}")
            await("collection/queue owned input windows retire") { edt {
                val layers = actualMainSceneLayers()
                playerMenuSurface() == null && ownedFeatureSurface("合集", "关闭", "2.Local replay P2") == null &&
                    ownedFeatureSurface("关闭播放队列", "当前播放") == null &&
                    layers.size == beforeLayers.size && layers.all { layer -> beforeLayers.any { it === layer } } &&
                    runCatching { videoScope("详情") }.isSuccess
            } }
            sameNative()
        }
        openFromMore("视频合集")
        val first = awaitCollection()
        actions.capture("158-original-collection", edt { current() })
        edt { captureOwnedExtraSurface("158-original-collection-dialog", first) }
        clickFeatureItem(first, "排序：正序")
        await("original collection sort setter persisted and original label updates") {
            privateCollectionSort() == "DESCENDING" && edt {
                descendants(first.accessibleContext).count { it.accessibleName == "排序：倒序" && visible(it, first) } == 1
            }
        }
        clickFeatureItem(first, "关闭")
        awaitClosed()
        check(playing()); check(actualPlayer.ownsSourceSnapshot(originalSource))
        openFromMore("视频合集")
        val reopened = awaitCollection()
        edt {
            check(descendants(reopened.accessibleContext).count { it.accessibleName == "排序：倒序" && visible(it, reopened) } == 1)
        }
        clickFeatureItem(reopened, "2.Local replay P2")
        // Require a real new native publication on the same actor/Canvas. No fixture
        // assignment to VM, original playlist, physical stack or native state is made.
        var newSource: OwnedPlaybackSourceSnapshot? = null
        await("original collection part selection loads a new actual native source") {
            val candidate = actualPlayer.currentSourceSnapshot() ?: return@await false
            if (candidate.sourceVersion <= originalSource.sourceVersion ||
                candidate.source.nativePublication == null || candidate.source.nativePublication === originalSource.source.nativePublication ||
                !actualPlayer.ownsSourceSnapshot(candidate) || !playing()) return@await false
            edt {
                current()
                check(nativeComponents(window()).filterIsInstance<Canvas>().filter { it.isShowing && it.width > 100 && it.height > 80 &&
                    SwingUtilities.getWindowAncestor(it) === window() &&
                    it.javaClass.declaredFields.any { field -> field.type == MpvPlayer::class.java } }.single() === actualCanvas)
                check(SwingUtilities.getWindowAncestor(actualPlayer.surface) === window())
            }
            newSource = candidate; true
        }
        check(!actualPlayer.ownsSourceSnapshot(originalSource))
        accepted = requireNotNull(newSource)
        awaitClosed(); clockAndCapture("159-original-collection-part-playing")
        openFromMore("播放队列")
        await("whole original current playback queue in its owned native Window") { edt {
            ownedFeatureSurface("关闭播放队列", "当前播放", "1个视频") != null
        } }
        val queue = edt { requireNotNull(ownedFeatureSurface("关闭播放队列", "当前播放", "1个视频")) }
        edt { check(queue is javax.swing.JDialog && queue !== window() && ownedWindow(queue)) }
        actions.capture("159-original-playback-queue", edt { current() })
        edt { captureOwnedExtraSurface("159-original-playback-queue-dialog", queue) }
        clickFeatureItem(queue, "关闭播放队列")
        awaitClosed(); sameNative(); check(playing())
        record("159-original-collection-and-queue", mapOf(
            "actualOpenControlUsed" to JsonPrimitive("更多播放操作"), "originalCollectionBodyVisible" to JsonPrimitive(true),
            "originalIntroAndBothPartChipsVisible" to JsonPrimitive(true), "originalSortDurable" to JsonPrimitive("DESCENDING"),
            "introTextEvidence" to JsonPrimitive("OWNED_SCREENSHOT_WITH_ORIGINAL_DISCLOSURE_SEMANTICS"),
            "originalSortReopened" to JsonPrimitive(true), "actualOriginalPartControlUsed" to JsonPrimitive("2.Local replay P2"),
            "previousNativeSourceVersion" to JsonPrimitive(originalSource.sourceVersion), "newNativeSourceVersion" to JsonPrimitive(accepted.sourceVersion),
            "sameActualMpvAndCanvas" to JsonPrimitive(true), "actualQueueOpenedAndClosed" to JsonPrimitive(true),
            "queueIndexedSelectionAccepted" to JsonPrimitive(false), "realAccountSubscriptionAccepted" to JsonPrimitive(false),
            "remoteSubscriptionMutationSubmitted" to JsonPrimitive(false), "nativeState" to safeState()))
    }

    private fun privateDanmakuOpacity(): Double? {
        val file = actions.local.resolve("BiliPaiWindows/plugin-settings.json")
        if (!Files.exists(file)) return null
        return Json.parseToJsonElement(Files.readString(file)).jsonObject["settings"]?.jsonObject
            ?.get("danmaku_portrait_opacity")?.jsonPrimitive?.doubleOrNull
    }

    private fun wheelDanmakuSettings(surface: Window, rotation: Int) = edt {
        current(); sameNative(); check(surface.isShowing && surface.isDisplayable && ownedWindow(surface))
        val panels = descendants(surface.accessibleContext).filter { node ->
            val children = descendants(node)
            listOf("弹幕设置", "透明度", "查看弹幕列表", "关闭").all { label -> children.any { hasLabel(it, label) } }
        }
        val panel = panels.minBy { descendants(it).size }
        val input = nativeComponents(surface).filter { it.isShowing && it.isDisplayable &&
            SwingUtilities.getWindowAncestor(it) === surface && it.keyListeners.any { listener ->
                listener.javaClass.name == "androidx.compose.ui.scene.ComposeSceneMediator\$keyListener\$1"
            } }.single()
        val content = (surface as javax.swing.RootPaneContainer).contentPane
        val viewport = Rectangle(content.locationOnScreen.x, content.locationOnScreen.y, content.width, content.height)
        val inputBounds = Rectangle(input.locationOnScreen.x, input.locationOnScreen.y, input.width, input.height)
        val component = requireNotNull(panel.accessibleComponent)
        val origin = requireNotNull(component.locationOnScreen)
        val area = Rectangle(origin.x, origin.y, component.size.width, component.size.height).intersection(viewport).intersection(inputBounds)
        check(area.width > 20 && area.height > 20) { "No owned visible original danmaku panel scroll area" }
        val point = java.awt.Point(area.x + area.width / 2 - inputBounds.x, area.y + area.height / 2 - inputBounds.y)
        check(input.contains(point))
        input.dispatchEvent(java.awt.event.MouseWheelEvent(input, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0,
            point.x, point.y, 0, false, java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation))
    }

    private fun exerciseDanmakuSettings() {
        repeat(12) {
            if (edt { descendants(detailPaneScope()).any { node -> node.accessibleName == "弹幕设置" && visible(node) } }) return@repeat
            ownedWheel(edt { actualComposeInput() }, 2, false); Thread.sleep(150)
        }
        val beforeLayers = settledMainInputLayers("danmaku-before", requireDetails = true)
        fun open() {
            click("弹幕设置")
            await("original danmaku settings opens from current Windows details") { edt {
                ownedFeatureSurface("弹幕设置", "查看弹幕列表", "关闭") != null
            } }
        }
        fun close(surface: Window) {
            repeat(12) {
                if (edt { descendants(surface.accessibleContext).any { node ->
                    node.accessibleName == "关闭" && visible(node, surface)
                } }) return@repeat
                wheelDanmakuSettings(surface, -3); Thread.sleep(150)
            }
            clickFeatureItem(surface, "关闭")
            await("original danmaku modal input layers retire") { edt {
                val layers = actualMainSceneLayers()
                ownedFeatureSurface("弹幕设置", "查看弹幕列表", "关闭") == null &&
                    ownedFeatureSurface("弹幕列表", "暂无弹幕数据", "关闭") == null &&
                    layers.size == beforeLayers.size && layers.all { layer -> beforeLayers.any { it === layer } } &&
                    runCatching { detailPaneScope() }.isSuccess
            } }
        }
        open()
        val surface = edt { requireNotNull(ownedFeatureSurface("弹幕设置", "查看弹幕列表", "关闭")) }
        actions.capture("113-original-danmaku-settings", edt { current() })
        if (surface !== edt { window() }) edt { captureOwnedExtraSurface("113-original-danmaku-settings-dialog", surface) }
        fun opacitySlider(): AccessibleContext = edt {
            current()
            descendants(surface.accessibleContext).single {
                it.accessibleName == "透明度" && it.accessibleRole == javax.accessibility.AccessibleRole.SLIDER
            }
        }
        // The original non-fullscreen panel has a bounded vertical scroll area.
        repeat(12) {
            if (edt { runCatching { visible(opacitySlider(), surface) }.getOrDefault(false) }) return@repeat
            wheelDanmakuSettings(surface, 2)
            Thread.sleep(150)
        }
        await("original opacity slider wholly visible") { edt { visible(opacitySlider(), surface) } }
        val prior = privateDanmakuOpacity()
        edt { clickOwnedComposeMouse(surface, opacitySlider()) }
        await("original opacity setter persisted the real pointer release") {
            privateDanmakuOpacity()?.let { it in .4.. .9 && it != prior } == true
        }
        val changed = requireNotNull(privateDanmakuOpacity())
        close(surface); open()
        check(privateDanmakuOpacity() == changed)
        val reopened = edt { requireNotNull(ownedFeatureSurface("弹幕设置", "查看弹幕列表", "关闭")) }
        clickFeatureItem(reopened, "查看弹幕列表")
        await("original empty raw document renders a real list rather than a blank modal") { edt {
            ownedFeatureSurface("弹幕列表", "暂无弹幕数据", "关闭") != null
        } }
        val pool = edt { requireNotNull(ownedFeatureSurface("弹幕列表", "暂无弹幕数据", "关闭")) }
        actions.capture("113-original-danmaku-pool", edt { current() })
        if (pool !== edt { window() }) edt { captureOwnedExtraSurface("113-original-danmaku-pool-dialog", pool) }
        close(pool)
        sameNative(); check(playing())
        record("113-original-danmaku-settings-and-pool", mapOf(
            "sameNativeSource" to JsonPrimitive(true), "actualSettingsEntryUsed" to JsonPrimitive(true),
            "actualOriginalEmptyPoolVisible" to JsonPrimitive(true), "actualOpacityDurable" to JsonPrimitive(changed),
            "reopenPreservedOpacity" to JsonPrimitive(true), "realAccountUsed" to JsonPrimitive(false),
            "remoteDanmakuActionSubmitted" to JsonPrimitive(false)))
    }

    // These names identify the nine tooltip-bearing IconButton positions, including
    // the playback/fullscreen variants. The actual role/state is always read below.
    private val playerIconTooltipNames = setOf("播放", "暂停", "上一集", "下一集", "音量与静音",
        "视频章节", "详情", "浮窗", "全屏", "退出全屏", "更多播放操作")

    private fun focusedPlayerTooltipButtons(): List<AccessibleContext> = all().filter { node ->
        node.accessibleName in playerIconTooltipNames &&
            node.accessibleRole == javax.accessibility.AccessibleRole.PUSH_BUTTON &&
            node.accessibleStateSet.contains(AccessibleState.FOCUSED)
    }

    private fun showingPlayerTooltipLabels(): List<AccessibleContext> = all().filter { node ->
        node.accessibleName in playerIconTooltipNames &&
            node.accessibleRole == javax.accessibility.AccessibleRole.LABEL &&
            node.accessibleStateSet.contains(AccessibleState.SHOWING) &&
            (node.accessibleAction?.accessibleActionCount ?: 0) == 0
    }

    private fun mainInputLayerFacts(layers: List<Any> = actualMainSceneLayers()): Map<String, JsonElement> {
        check(EventQueue.isDispatchThread()); current()
        fun nodeFacts(nodes: List<AccessibleContext>) = JsonArray(nodes.map { node -> buildJsonObject {
            put("name", node.accessibleName); put("roleDisplay", node.accessibleRole.toString())
            put("focused", node.accessibleStateSet.contains(AccessibleState.FOCUSED))
            put("showing", node.accessibleStateSet.contains(AccessibleState.SHOWING))
            put("actionCount", node.accessibleAction?.accessibleActionCount ?: 0)
        } })
        return mapOf("actualMainLayerCount" to JsonPrimitive(layers.size),
            "actualMainLayers" to JsonArray(layers.map { layer -> buildJsonObject {
                put("class", layer.javaClass.name); put("identity", System.identityHashCode(layer))
            } }), "actualTooltipLabels" to nodeFacts(showingPlayerTooltipLabels()),
            "actualFocusedTooltipButtons" to nodeFacts(focusedPlayerTooltipButtons()))
    }

    /** End only real hover/focus input before comparing modal layer identities.
     * Persistent tooltips are ordinary dynamic Popup layers, not leaked modal input.
     * No tooltip state or attached-layer collection is modified by the fixture. */
    private fun settledMainInputLayers(scenario: String, requireDetails: Boolean = false): List<Any> {
        record("main-input-$scenario-initial", edt { sameNative(); mainInputLayerFacts() })
        val input = edt {
            current(); sameNative()
            actualComposeInput().also { component ->
                check(component.isShowing && component.isDisplayable &&
                    SwingUtilities.getWindowAncestor(component) === window())
                component.dispatchEvent(MouseEvent(component, MouseEvent.MOUSE_EXITED,
                    System.currentTimeMillis(), 0, -1, -1, 0, false, MouseEvent.NOBUTTON))
            }
        }
        var tabSteps = 0
        while (tabSteps < 16) {
            var focusedTooltip: Boolean? = null
            await("complete same-source Main focus semantics before $scenario Tab") { edt {
                current(); sameNative()
                if (runCatching { videoScope() }.isFailure) false else {
                    focusedTooltip = focusedPlayerTooltipButtons().isNotEmpty(); true
                }
            } }
            if (focusedTooltip != true) break
            ownedKey(input, java.awt.event.KeyEvent.VK_TAB)
            tabSteps++
            Thread.sleep(100)
        }
        record("main-input-$scenario-delivered", edt { mainInputLayerFacts() } + mapOf(
            "mouseExitedDeliveredToActualMainInput" to JsonPrimitive(true),
            "ownedTabSteps" to JsonPrimitive(tabSteps), "activatedControl" to JsonPrimitive(false)))
        var previous: List<Any>? = null
        var stableSince = 0L
        var settled: List<Any>? = null
        await("real Main tooltip hover/focus and exit layers settle before $scenario") { edt {
            current(); sameNative()
            val complete = runCatching { videoScope(); if (requireDetails) detailPaneScope() }.isSuccess
            if (!complete || focusedPlayerTooltipButtons().isNotEmpty() || showingPlayerTooltipLabels().isNotEmpty()) {
                previous = null; stableSince = 0L; false
            } else {
                val now = actualMainSceneLayers()
                val before = previous
                if (before != null && now.size == before.size && now.all { layer -> before.any { it === layer } }) {
                    if (System.nanoTime() - stableSince >= Duration.ofMillis(300).toNanos()) {
                        settled = now; true
                    } else false
                } else {
                    previous = now; stableSince = System.nanoTime(); false
                }
            }
        } }
        return requireNotNull(settled).also { layers ->
            record("main-input-$scenario-stable", edt { mainInputLayerFacts(layers) } + mapOf(
                "ownedTabSteps" to JsonPrimitive(tabSteps), "tooltipLabelsAbsent" to JsonPrimitive(true),
                "tooltipButtonsUnfocused" to JsonPrimitive(true), "layerIdentityStableMillis" to JsonPrimitive(300)))
        }
    }

    /** Read only the already initialized, owned Compose 1.12.1 scene's attached input layers. */
    private fun actualMainSceneLayers(): List<Any> {
        check(EventQueue.isDispatchThread()); current()
        fun fixedField(subject: Any, ownerClass: String, name: String): Any? {
            check(subject.javaClass.name == ownerClass) { "Unexpected actual Compose owner: ${subject.javaClass.name}" }
            val field = subject.javaClass.getDeclaredField(name)
            check(field.trySetAccessible())
            return field.get(subject)
        }
        val panel = requireNotNull(fixedField(window(), "androidx.compose.ui.awt.ComposeWindow", "composePanel"))
        val container = requireNotNull(fixedField(panel, "androidx.compose.ui.awt.ComposeWindowPanel", "_composeContainer"))
        val mediator = requireNotNull(fixedField(container, "androidx.compose.ui.scene.ComposeContainer", "mediator"))
        val sceneLazy = fixedField(mediator, "androidx.compose.ui.scene.ComposeSceneMediator", "scene\$delegate") as Lazy<*>
        check(sceneLazy.isInitialized()) { "Actual Main scene must already be initialized" }
        val scene = requireNotNull(sceneLazy.value)
        val platformLayers = (fixedField(container, "androidx.compose.ui.scene.ComposeContainer", "layers") as List<*>)
            .map { requireNotNull(it) }
        val canvasLayers = when (scene.javaClass.name) {
            "androidx.compose.ui.scene.CanvasLayersComposeSceneImpl" ->
                (fixedField(scene, "androidx.compose.ui.scene.CanvasLayersComposeSceneImpl", "layers") as List<*>)
                    .map { requireNotNull(it) }
            "androidx.compose.ui.scene.PlatformLayersComposeSceneImpl" -> emptyList()
            else -> error("Unexpected actual Main scene: ${scene.javaClass.name}")
        }
        return platformLayers + canvasLayers
    }

    private fun exerciseMainNvidiaControls() {
        edt { sameNative(); check(nvidiaDialogSurface() == null) }
        val beforeDialogLayers = settledMainInputLayers("nvidia-before")
        sameNative(); click("NVIDIA 增强详情")
        await("complete actual owned NVIDIA enhancement dialog") { edt { nvidiaDialogSurface() != null } }
        val openedDialogLayers = edt { actualMainSceneLayers() }
        check(openedDialogLayers.size == beforeDialogLayers.size &&
            beforeDialogLayers.all { before -> openedDialogLayers.any { it === before } }) {
            "Native NVIDIA dialog must not leave an inline layer beneath the video Canvas"
        }
        edt { check(nvidiaSurface() !== window() && ownedWindow(nvidiaSurface())) }
        record("nvidia-video-dialog-open", mapOf("actualControlUsed" to JsonPrimitive("NVIDIA 增强详情"),
            "sameNativeSource" to JsonPrimitive(true), "completeDialogAndOriginalSwitch" to JsonPrimitive(true),
            "ownedNativeDialogAboveVideo" to JsonPrimitive(true),
            "actualMainSceneLayerCountBefore" to JsonPrimitive(beforeDialogLayers.size),
            "actualMainSceneLayerCountOpen" to JsonPrimitive(openedDialogLayers.size)))
        ensureNvidiaVisible()
        check(privateNvidiaEnabled() == true && edt { nvidiaChecked() })
        toggleNvidia(false, "nvidia-video-controls-off")
        await("OFF retires current source enhancement without stopping media") {
            sameNative(); val state = actualPlayer.nvidiaVideoState.value
            !state.active && !state.pending && !state.driverVsrAccepted && !state.driverHdrAccepted && playing()
        }
        toggleNvidia(true, "nvidia-video-controls-on")
        await("actual main native NVIDIA GPU identification") {
            sameNative(); val state = actualPlayer.nvidiaVideoState.value
            state.sourceVersion == accepted.sourceVersion && state.gpuVendorId == 0x10de && !state.gpuName.isNullOrBlank()
        }
        val state = actualPlayer.nvidiaVideoState.value
        await("actual main-session GPU name rendered through its UI StateFlow") { edt {
            current(); descendants(nvidiaSurface().accessibleContext)
                .any { it.accessibleName.orEmpty().contains(requireNotNull(state.gpuName)) }
        } }
        record("nvidia-main-native-output", mapOf("actualMainPlayerIdentity" to JsonPrimitive(System.identityHashCode(actualPlayer)),
            "actualHardwareDecoder" to JsonPrimitive(actualPlayer.state.value.hardwareDecoder),
            "sourceVersion" to JsonPrimitive(state.sourceVersion), "configurationVersion" to JsonPrimitive(state.configurationVersion),
            "gpuName" to JsonPrimitive(state.gpuName), "gpuVendorId" to JsonPrimitive(state.gpuVendorId),
            "currentGpuContext" to JsonPrimitive(state.currentGpuContext), "driverVsrAccepted" to JsonPrimitive(state.driverVsrAccepted),
            "driverHdrAccepted" to JsonPrimitive(state.driverHdrAccepted), "active" to JsonPrimitive(state.active),
            "pending" to JsonPrimitive(state.pending), "error" to JsonPrimitive(state.error),
            "inputWidth" to JsonPrimitive(state.inputWidth), "inputHeight" to JsonPrimitive(state.inputHeight),
            "outputWidth" to JsonPrimitive(state.outputWidth), "outputHeight" to JsonPrimitive(state.outputHeight),
            "outputTransfer" to JsonPrimitive(state.outputTransfer), "targetTransfer" to JsonPrimitive(state.targetTransfer),
            "targetPrimaries" to JsonPrimitive(state.targetPrimaries),
            "hdrDisplayEnabled" to JsonPrimitive(actualPlayer.videoOutput.value.hdrDisplay.hdrEnabled),
            "hdrConversionActive" to JsonPrimitive(state.hdrConversionActive),
            "vsrPositiveRequiredByThisUiTest" to JsonPrimitive(false), "hdrPositiveRequiredByThisUiTest" to JsonPrimitive(false),
            "configurationSharedAcrossSettingsAndControls" to JsonPrimitive(true)))
        actions.capture("nvidia-main-native-output", edt { current() })
        edt { captureOwnedExtraSurface("nvidia-main-native-output-dialog", nvidiaSurface()) }
        edt {
            val surface = nvidiaSurface()
            val done = descendants(surface.accessibleContext).filter { node -> node.accessibleName == "完成" &&
                visible(node, surface) && node.accessibleStateSet.contains(AccessibleState.ENABLED) &&
                (node.accessibleAction?.accessibleActionCount ?: 0) == 1 }.single()
            clickOwnedComposeMouse(surface, done)
        }
        await("actual NVIDIA native dialog closes before Main tooltip settlement") { edt {
            sameNative(); nvidiaDialogSurface() == null && runCatching { videoScope("详情") }.isSuccess
        } }
        settledMainInputLayers("nvidia-after")
        await("actual NVIDIA dialog input-layer retirement restores current ordinary video") { edt {
            sameNative()
            val currentLayers = actualMainSceneLayers()
            nvidiaDialogSurface() == null && currentLayers.size == beforeDialogLayers.size &&
                currentLayers.all { layer -> beforeDialogLayers.any { it === layer } } &&
                runCatching { videoScope("详情") }.isSuccess
        } }
        sameNative(); check(playing()); check(privateNvidiaEnabled() == true)
        record("nvidia-video-dialog-closed", mapOf("actualControlUsed" to JsonPrimitive("完成"),
            "sameNativeSource" to JsonPrimitive(true), "actualDurableValue" to JsonPrimitive(true),
            "actualMainSceneInputLayersRestored" to JsonPrimitive(true),
            "actualMainSceneLayerCountRestored" to JsonPrimitive(edt { actualMainSceneLayers().size })))
    }

    private fun boundCommentSearchWindow() {
        val target = edt {
            current()
            val main = window()
            check((main as ComposeWindow).placement == WindowPlacement.Floating)
            val configuration = main.graphicsConfiguration
            val monitor = configuration.bounds
            val insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(configuration)
            val usable = Rectangle(monitor.x + insets.left, monitor.y + insets.top,
                monitor.width - insets.left - insets.right, monitor.height - insets.top - insets.bottom)
            check(usable.width > 640 && usable.height > 480 && usable.width >= main.minimumSize.width &&
                usable.height >= main.minimumSize.height) { "The actual runner viewport cannot contain this application's safe minimum" }
            main.bounds = usable
            main.validate()
            usable
        }
        await("owned Main fits the actual runner monitor without changing app scale") { edt {
            current(); window().bounds == target && window().graphicsConfiguration.bounds.contains(window().bounds)
        } }
        record("comment-search-bounded-main", mapOf(
            "scope" to JsonPrimitive("ONLY_ACTUAL_AVAILABLE_RUNNER_VIEWPORT"),
            "x" to JsonPrimitive(target.x), "y" to JsonPrimitive(target.y),
            "width" to JsonPrimitive(target.width), "height" to JsonPrimitive(target.height),
            "fourKTested" to JsonPrimitive(false), "fullscreenResizeRegressionExecuted" to JsonPrimitive(false),
            "appScaleChangedByFixture" to JsonPrimitive(false)))
    }

    private fun exerciseCommentSearch(localReplay: WindowsVideoLocalReplay) {
        check(!EventQueue.isDispatchThread())
        sameNative(); check(playing())
        val script = localReplay.commentSearchReplay
        val (assembly, publication) = actualHotOwner()
        val comments = assembly.domains.comments
        val original = accepted
        val originalMain = edt { window() }
        val beforeLayers = settledMainInputLayers("comment-search-before")
        await("actual current comment owner finished the ordinary HOT list") { edt {
            current(); sameNative()
            comments.commentState.value.let { !it.isRepliesLoading && !it.isRepliesRefreshing && it.replies.isNotEmpty() }
        } }
        check(comments.commentState.value.sortMode.apiMode == 3) { "The isolated fixture must retain the ordinary HOT list" }
        val mainReplies = comments.commentState.value.replies
        val mainNextPage = comments.commentState.value.nextPage
        val mainEnd = comments.commentState.value.isRepliesEnd
        // Open the actual detail tab before pausing; no navigation/VM fields are set.
        if (edt { runCatching { detailPaneScope() }.isFailure }) {
            click("详情")
            await("owned detail viewport before comment search") { edt { runCatching { detailPaneScope() }.isSuccess } }
        }
        if (!edt { commentsTabSelected() }) {
            edt {
                current(); sameNative()
                val tab = descendants(detailPaneScope()).single { it.accessibleName == "评论" && visible(it) &&
                    it.accessibleRole == javax.accessibility.AccessibleRole.PAGE_TAB &&
                    (it.accessibleAction?.accessibleActionCount ?: 0) == 1 }
                clickOwnedComposeMouse(window(), tab)
            }
            await("same-source actual comment tab selected") { edt {
                current(); sameNative()
                runCatching { commentsTabSelected() }.getOrDefault(false)
            } }
        }
        click("暂停")
        await("real native pause before optional comment search") { sameNative(); actualPlayer.state.value.nativePaused == true }
        Thread.sleep(300)
        val baseline = actualPlayer.state.value
        val ownedPeers = linkedSetOf<javax.swing.JDialog>()
        fun currentSource() {
            current(); sameNative()
            check(accepted === original && assembly.owns() && assembly.native.isCurrent(publication) &&
                assembly.domains.comments === comments && actualPlayer.ownsSourceSnapshot(original))
            actualPlayer.state.value.let { state ->
                check(state.nativePaused == true && state.paused && state.seekCompletedId == baseline.seekCompletedId &&
                    state.volume == baseline.volume && state.muted == baseline.muted && state.speed == baseline.speed &&
                    kotlin.math.abs(state.positionSeconds - baseline.positionSeconds) <= 0.25) {
                    "Optional search changed the paused current playback source/state"
                }
            }
            comments.commentState.value.let { state ->
                check(state.replies == mainReplies && state.nextPage == mainNextPage && state.isRepliesEnd == mainEnd &&
                    !state.isRepliesLoading && !state.isRepliesRefreshing)
            }
        }
        fun dialog(title: String): javax.swing.JDialog? = edt {
            currentSource()
            Window.getWindows().filterIsInstance<javax.swing.JDialog>().filter {
                it.isShowing && it.isDisplayable && ownedWindow(it) && it.title == title
            }.also { check(it.size <= 1) { "Duplicate owned '$title' native peer" } }.singleOrNull()
                ?.also { ownedPeers.add(it) }
        }
        fun visibleLabel(surface: Window, label: String): Boolean = edt {
            currentSource()
            descendants(surface.accessibleContext).any { hasLabel(it, label) && visible(it, surface) }
        }
        fun capture(id: String, surface: Window) {
            val bounds = edt {
                currentSource()
                check(surface.isShowing && surface.isDisplayable && ownedWindow(surface) && surface !== window())
                check(window().bounds.contains(surface.bounds) && surface.graphicsConfiguration.bounds.contains(surface.bounds))
                Files.writeString(report.resolve("$id-accessibility.tsv"), descendants(surface.accessibleContext).joinToString("\n") {
                    "${it.accessibleName}\t${it.accessibleRole}\t${it.accessibleStateSet}\t${it.accessibleAction?.accessibleActionCount ?: 0}"
                }, CREATE_NEW, WRITE)
                Rectangle(surface.bounds)
            }
            // Pure physical read of this exact owned peer. No focus, repaint,
            // renderImmediately or decoded-video substitution is used to capture it.
            val screen = java.awt.Robot().createScreenCapture(bounds)
            check(ImageIO.write(screen, "png", report.resolve("$id-screen.png").toFile()))
            edt { currentSource(); check(surface.isShowing && surface.bounds == bounds) }
        }
        fun openSearch(): javax.swing.JDialog {
            edt { currentSource() }
            clickFeatureItem(edt { window() }, "搜索评论")
            await("actual original search sheet on its owned native peer") { dialog("搜索评论") != null }
            val surface = requireNotNull(dialog("搜索评论"))
            await("complete original search/filter/sort controls") {
                listOf("搜索评论", "关闭", "全部评论", "只看UP主", "充电评论", "最热", "最新").all { visibleLabel(surface, it) }
            }
            edt { currentSource(); check(surface.isModal && ownedWindow(surface) && window().bounds.contains(surface.bounds)) }
            return surface
        }
        fun awaitClosed(surface: javax.swing.JDialog) = await("original search peer disposed, optional request retired") { edt {
            currentSource(); !surface.isShowing && !surface.isDisplayable && dialog("搜索评论") == null
        } }
        fun closeSearch(surface: javax.swing.JDialog) {
            clickFeatureItem(surface, "关闭")
            awaitClosed(surface)
        }
        fun resultRow(surface: Window, label: String): AccessibleContext? {
            currentSource()
            val rows = descendants(surface.accessibleContext).filter { node ->
                hasLabel(node, label) && node.accessibleRole != javax.accessibility.AccessibleRole.SCROLL_PANE &&
                    visible(node, surface) && node.accessibleStateSet.contains(AccessibleState.ENABLED) &&
                    (node.accessibleAction?.accessibleActionCount ?: 0) == 1
            }
            check(rows.size <= 1) { "Ambiguous original result row '$label'" }
            return rows.singleOrNull()
        }
        fun assertResultOrder(surface: Window, first: String, second: String) {
            await("original sort displays '$first' above '$second'") { edt {
                currentSource()
                val a = resultRow(surface, first)?.accessibleComponent?.locationOnScreen ?: return@edt false
                val b = resultRow(surface, second)?.accessibleComponent?.locationOnScreen ?: return@edt false
                a.y < b.y
            } }
        }
        var firstFailure: Throwable? = null
        // An existing peer belongs to another flow and must never become our
        // cleanup target. Each subsequently observed exact peer is registered
        // before controls/state assertions can fail.
        check(dialog("搜索评论") == null && dialog("评论回复") == null)
        try {
            val cancelled = openSearch()
            await("real optional TIME request held before cancellation") {
                edt { currentSource(); comments.fullSearchState.value.isLoading } && script.firstSearchReadObserved()
            }
            await("original initial progress is visibly rendered") { visibleLabel(cancelled, "全量加载中 0/0") }
            capture("178-comment-search-loading-cancel", cancelled)
            closeSearch(cancelled)
            await("closing the sheet cancels the exact real OkHttp optional read") {
                script.cancelledSearchCallObserved() && edt { currentSource(); !comments.fullSearchState.value.isLoading }
            }
            check(comments.fullSearchReplies.value.isEmpty()) { "Cancelled optional search published a late page" }
            script.failNextSearch()
            val retried = openSearch()
            await("original failure exposes the actual retry action") {
                visibleLabel(retried, "加载失败，点此重试") && edt {
                    currentSource(); comments.fullSearchState.value.let { !it.isLoading && !it.isReady && it.error != null }
                }
            }
            capture("179-comment-search-retry-error", retried)
            script.completeNextSearch()
            clickFeatureItem(retried, "加载失败，点此重试")
            await("actual original first-page progress before final read response") {
                script.secondSearchPageObserved() && visibleLabel(retried, "全量加载中 2/3") && edt {
                    currentSource(); comments.fullSearchState.value.let { it.isLoading && it.loadedCount == 2 && it.totalCount == 3 }
                }
            }
            capture("180-comment-search-page-progress", retried)
            script.releaseFinalPage()
            await("full original two-page result pool ready") {
                visibleLabel(retried, "已全量加载 3 条") && edt {
                    currentSource(); comments.fullSearchState.value.let { it.isReady && !it.isLoading && it.error == null && it.loadedCount == 3 } &&
                        comments.fullSearchReplies.value.map { it.rpid } == listOf(WindowsCommentSearchReplay.ROOT_A,
                            WindowsCommentSearchReplay.ROOT_B, WindowsCommentSearchReplay.ROOT_C)
                }
            }
            edt {
                currentSource()
                val editor = descendants(retried.accessibleContext).single { it.accessibleEditableText != null && visible(it, retried) }
                editor.accessibleEditableText.setTextContents(WindowsCommentSearchReplay.QUERY)
            }
            await("original search text really consumed by its mounted field") { edt {
                currentSource()
                val editor = descendants(retried.accessibleContext).single { it.accessibleEditableText != null && visible(it, retried) }
                val text = requireNotNull(editor.accessibleText)
                buildString { repeat(text.charCount) { append(text.getAtIndex(javax.accessibility.AccessibleText.CHARACTER, it).orEmpty()) } } ==
                    WindowsCommentSearchReplay.QUERY
            } }
            await("ALL filter flattens three roots plus two original children") { visibleLabel(retried, "找到 5 条") }
            assertResultOrder(retried, WindowsCommentSearchReplay.ROOT_C_TEXT, WindowsCommentSearchReplay.CHILD_A_TEXT)
            capture("181-comment-search-all-hot", retried)
            clickFeatureItem(retried, "只看UP主")
            await("original UP filter retains only the actual up-mid root and child") {
                visibleLabel(retried, "找到 2 条") && visibleLabel(retried, WindowsCommentSearchReplay.ROOT_B_TEXT) &&
                    visibleLabel(retried, WindowsCommentSearchReplay.CHILD_B_TEXT)
            }
            capture("182-comment-search-up-only", retried)
            clickFeatureItem(retried, "充电评论")
            await("charged filter consumes actual protobuf field 31") {
                visibleLabel(retried, "找到 1 条") && visibleLabel(retried, WindowsCommentSearchReplay.ROOT_C_TEXT)
            }
            capture("183-comment-search-charged", retried)
            clickFeatureItem(retried, "全部评论")
            clickFeatureItem(retried, "最新")
            await("ALL result pool restored after scope selection") { visibleLabel(retried, "找到 5 条") }
            assertResultOrder(retried, WindowsCommentSearchReplay.ROOT_B_TEXT, WindowsCommentSearchReplay.CHILD_A_TEXT)
            capture("184-comment-search-all-latest", retried)
            clickFeatureItem(retried, WindowsCommentSearchReplay.CHILD_A_TEXT)
            awaitClosed(retried)
            await("whole original subreply content in its actual owned native window") { dialog("评论回复") != null }
            val replies = requireNotNull(dialog("评论回复"))
            await("original result callback opens the parent root on the SAME comment VM") { edt {
                currentSource(); comments.subReplyState.value.let {
                    it.visible && !it.isLoading && it.error == null && it.rootReply?.rpid == WindowsCommentSearchReplay.ROOT_A &&
                        it.items.any { reply -> reply.rpid == WindowsCommentSearchReplay.CHILD_A }
                }
            } }
            await("actual root and child displayed by original reply detail") {
                visibleLabel(replies, WindowsCommentSearchReplay.ROOT_A_TEXT) && visibleLabel(replies, WindowsCommentSearchReplay.CHILD_A_TEXT)
            }
            capture("185-comment-search-original-subreply", replies)
            edt {
                currentSource(); check(ownedWindow(replies) && replies.isShowing && replies.isDisplayable)
                replies.dispatchEvent(java.awt.event.WindowEvent(replies, java.awt.event.WindowEvent.WINDOW_CLOSING))
            }
            await("owned reply title-bar close consumes original dismiss") { edt {
                currentSource(); !replies.isShowing && !replies.isDisplayable && !comments.subReplyState.value.visible
            } }
            edt { currentSource() }
            check(dialog("搜索评论") == null && dialog("评论回复") == null)
            val transport = script.receipt()
            record("186-original-comment-search-completed", mapOf(
                "sameOriginalCommentVm" to JsonPrimitive(true), "sameAcceptedPublicationIdentity" to JsonPrimitive(true),
                "samePausedNativeSourceAndPreferences" to JsonPrimitive(true), "mainCommentsUnchanged" to JsonPrimitive(true),
                "actualOriginalCloseRetryScopeSortAndSubreplyConsumed" to JsonPrimitive(true),
                "fieldInputMechanism" to JsonPrimitive("ACTUAL_ACCESSIBLE_EDITABLE_TEXT_SET_CONTENTS"),
                "physicalOwnedDialogCapturesCollected" to JsonPrimitive(true), "physicalTextVisibilityHumanReviewRequired" to JsonPrimitive(true),
                "transport" to transport, "realAccountUsed" to JsonPrimitive(false), "accountMutationSubmitted" to JsonPrimitive(false)))
        } catch (failure: Throwable) {
            firstFailure = failure
            runCatching {
                val peer = edt { ownedPeers.lastOrNull { it.isShowing && it.isDisplayable } }
                if (peer != null) capture("comment-search-failure", peer)
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            // Close only the exact peer created by this branch; never cancel the
            // owner/VM or touch another window. Release held transport on failure.
            var cleanupFailure: Throwable? = null
            for (peer in ownedPeers.toList().asReversed()) {
                val failure = runCatching { edt {
                    var owner: Window? = peer
                    while (owner != null && owner !== originalMain) owner = owner.owner
                    if (peer.isShowing && peer.isDisplayable && owner === originalMain)
                        peer.dispatchEvent(java.awt.event.WindowEvent(peer, java.awt.event.WindowEvent.WINDOW_CLOSING))
                } }.exceptionOrNull()
                if (failure != null) {
                    val previous = cleanupFailure
                    if (previous == null) cleanupFailure = failure else previous.addSuppressed(failure)
                }
            }
            script.releaseOnFailure()
            if (cleanupFailure != null) {
                val primary = firstFailure
                if (primary != null) primary.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
        }
        if (edt { runCatching { detailPaneScope() }.isSuccess }) {
            click("关闭详情")
            await("same detail viewport retired after optional search") { edt {
                current(); sameNative(); all().none { it.accessibleName == "关闭详情" && visible(it) }
            } }
        }
        val afterLayers = settledMainInputLayers("comment-search-after")
        check(afterLayers.size == beforeLayers.size && beforeLayers.all { old -> afterLayers.any { it === old } }) {
            "Optional comment search must restore the exact original Main scene layer identities"
        }
        click("播放")
        val clock = actualPlayer.state.value.positionSeconds
        await("same original native source resumes after closing search and reply") {
            sameNative(); assembly.native.isCurrent(publication) && playing() && actualPlayer.state.value.positionSeconds > clock + 0.20
        }
    }

    /** Explicit extra scope after the unchanged complete composer proof. Every
     * Like terminates in the exact memory-only replay. All input below is OS
     * Robot delivery; it never calls a business VM method or dispatches mouse
     * events directly to Compose/Swing to bypass the decorative HWND. */
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun exerciseBrandFeedbackPlacement(replay: WindowsVideoLocalReplay) {
        check(!EventQueue.isDispatchThread())
        val (assembly, publication) = actualHotOwner()
        val engagement = assembly.domains.engagement
        val composer = assembly.domains.composer
        val comments = assembly.domains.comments
        val originalMain = edt { window() }
        val source = accepted
        val preferences = PlayerPreferencesStore().read()
        val originalPlacement = edt { originalMain.extendedState }
        val robot = java.awt.Robot()
        val registered = linkedSetOf<javax.swing.JDialog>()
        val repository = edt { current(); requireNotNull(owner.messagePages.get()).repository }
        val script = replay.commentComposerReplay
        check(script.authenticated(repository))
        val beforeLayers = settledMainInputLayers("feedback-before")
        var baseline = actualPlayer.state.value
        var primary: Throwable? = null
        fun guard(allowMinimized: Boolean = false) {
            current()
            val minimized = originalMain.extendedState and java.awt.Frame.ICONIFIED != 0
            if (minimized && allowMinimized) {
                // Expected hidden owner: retain exact Canvas/source identity,
                // never claim a visible viewport or screenshot while iconic.
                check(actualCanvas.isDisplayable && SwingUtilities.getWindowAncestor(actualPlayer.surface) === originalMain)
            } else sameNative()
            check(window() === originalMain && accepted === source && assembly.owns() &&
                assembly.native.isCurrent(publication) && actualPlayer.ownsSourceSnapshot(source) &&
                assembly.domains.engagement === engagement && assembly.domains.composer === composer &&
                assembly.domains.comments === comments && script.authenticated(repository) &&
                repository.sessionEpoch == script.epoch() &&
                PlayerPreferencesStore().read() == preferences && !composer.isSendingComment.value)
            check(allowMinimized || !minimized)
            val state = actualPlayer.state.value
            check(state.paused && state.nativePaused == true && state.seekCompletedId == baseline.seekCompletedId &&
                state.muted == baseline.muted && state.volume == baseline.volume && state.speed == baseline.speed &&
                kotlin.math.abs(state.positionSeconds - baseline.positionSeconds) < .25)
        }
        fun descendantsOwned(parent: Window): List<Window> = parent.ownedWindows.toList().flatMap {
            listOf(it) + descendantsOwned(it)
        }
        fun clientBounds(): Rectangle = Rectangle(originalMain.contentPane.locationOnScreen, originalMain.contentPane.size)
        fun peer(): javax.swing.JDialog? {
            guard()
            val client = clientBounds()
            return originalMain.ownedWindows.filterIsInstance<androidx.compose.ui.awt.ComposeDialog>().filter {
                it.isDisplayable && it.owner === originalMain && it.type == Window.Type.POPUP && it.isTransparent &&
                    !it.focusableWindowState && !it.isAutoRequestFocus && it.bounds == client
            }.also { check(it.size <= 1) }.singleOrNull()?.also { registered.add(it) }
        }
        fun facts(peer: javax.swing.JDialog, showing: Boolean) {
            guard(!showing && originalMain.extendedState and java.awt.Frame.ICONIFIED != 0)
            check(peer.owner === originalMain && peer.isDisplayable && peer.isShowing == showing &&
                !peer.focusableWindowState && !peer.isAutoRequestFocus && !peer.isOpaque && peer.background.alpha in 0..254)
            val pointer = Native.getWindowPointer(peer)
            val actual = failureWindowApi.GetWindowLongW(pointer, -20)
            check(DesktopDecorativeWindowStylePolicy.inputPolicyAcknowledged(actual))
            if (showing) {
                check(DesktopDecorativeWindowStylePolicy.acknowledged(actual) && peer.bounds == clientBounds() &&
                    originalMain.bounds.contains(peer.bounds))
            }
            check(failureWindowApi.GetForegroundWindow() != pointer)
        }
        fun settled(peer: javax.swing.JDialog, showing: Boolean): Boolean {
            guard(!showing && originalMain.extendedState and java.awt.Frame.ICONIFIED != 0)
            check(peer.owner === originalMain && peer.isDisplayable &&
                !peer.focusableWindowState && !peer.isAutoRequestFocus && !peer.isOpaque && peer.background.alpha in 0..254)
            if (peer.isShowing != showing) return false
            val actual = failureWindowApi.GetWindowLongW(Native.getWindowPointer(peer), -20)
            if (!DesktopDecorativeWindowStylePolicy.inputPolicyAcknowledged(actual)) return false
            if (showing && (!DesktopDecorativeWindowStylePolicy.acknowledged(actual) || peer.bounds != clientBounds())) return false
            facts(peer, showing)
            return true
        }
        fun live(origin: DesktopWindowsVideoFeedbackOrigin): Boolean = engagement.uiState.value.let {
            it.likeBurstVisible && it.desktopFeedbackOrigin(DesktopWindowsVideoFeedbackKind.LIKE) === origin
        }
        fun awaitLiveHidden(peer: javax.swing.JDialog, origin: DesktopWindowsVideoFeedbackOrigin,
            blocking: () -> Boolean, label: String) {
            await(label) { edt {
                guard()
                check(live(origin)) { "The original Like completed before this live blocking overlap was observed: $label" }
                blocking() && settled(peer, false)
            } }
        }
        fun restoredOrCompleted(peer: javax.swing.JDialog, origin: DesktopWindowsVideoFeedbackOrigin): Boolean {
            var restored = false
            await("original live receipt restores same peer; natural completion disposes it") { edt {
                guard()
                if (live(origin)) settled(peer, true).also { if (it) restored = true }
                else !peer.isShowing && !peer.isDisplayable
            } }
            return restored
        }
        fun physicalClick(surface: Window, label: String, throughPeer: javax.swing.JDialog? = null) {
            val point = edt {
                guard()
                throughPeer?.let { facts(it, true) }
                val control = descendants(surface.accessibleContext).filter { hasLabel(it, label) && visible(it, surface) &&
                    it.accessibleStateSet.contains(AccessibleState.ENABLED) && (it.accessibleAction?.accessibleActionCount ?: 0) == 1 }.single()
                val component = requireNotNull(control.accessibleComponent)
                val location = requireNotNull(component.locationOnScreen)
                java.awt.Point(location.x + component.size.width / 2, location.y + component.size.height / 2)
            }
            robot.mouseMove(point.x, point.y); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            try { robot.delay(35) } finally { robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
        }
        fun physicalKey(key: Int) {
            robot.keyPress(key)
            try { robot.delay(20) } finally { robot.keyRelease(key) }
        }
        fun tab(label: String, throughPeer: javax.swing.JDialog? = null) {
            physicalClick(originalMain, label, throughPeer)
            await("OS tab click reaches original Main through decorative HWND: $label") { edt {
                guard(); descendants(detailPaneScope()).any { it.accessibleName == label &&
                    it.accessibleRole == javax.accessibility.AccessibleRole.PAGE_TAB &&
                    it.accessibleStateSet.contains(AccessibleState.SELECTED) }
            } }
        }
        fun capture(id: String, surface: Window = originalMain) {
            val rectangle = edt {
                guard(); check(surface.isShowing && surface.isDisplayable && ownedWindow(surface))
                val rect = if (surface === originalMain) clientBounds() else Rectangle(surface.bounds)
                check(originalMain.bounds.contains(rect) && surface.graphicsConfiguration.bounds.contains(rect))
                rect
            }
            val image = robot.createScreenCapture(rectangle)
            // Capture the physical short-lived frame before tree serialization.
            val tree = edt {
                guard(); check(surface.isShowing && surface.isDisplayable && ownedWindow(surface))
                check((if(surface === originalMain) clientBounds() else Rectangle(surface.bounds)) == rectangle)
                descendants(surface.accessibleContext).joinToString("\n") {
                    "${it.accessibleName}\t${it.accessibleRole}\t${it.accessibleStateSet}"
                }
            }
            Files.writeString(report.resolve("$id-accessibility.tsv"), tree, CREATE_NEW, WRITE)
            check(ImageIO.write(image, "png", report.resolve("$id-screen.png").toFile()))
            edt { guard(); check(surface.isShowing && surface.isDisplayable &&
                (if(surface === originalMain) clientBounds() else Rectangle(surface.bounds)) == rectangle) }
        }
        fun modal(title: String): javax.swing.JDialog? = edt {
            guard()
            descendantsOwned(originalMain).filterIsInstance<javax.swing.JDialog>().filter {
                it.isDisplayable && it.title == title && ownedWindow(it)
            }.also { check(it.size <= 1) }.singleOrNull()?.also { registered.add(it) }
        }
        fun openEditor(throughPeer: javax.swing.JDialog): javax.swing.JDialog {
            tab("评论", throughPeer); physicalClick(originalMain, "发表评论", throughPeer)
            await("actual original composer modal/source stamp") { modal("发表评论")?.let { it.isShowing && it.isModal } == true }
            return requireNotNull(modal("发表评论")).also { edt {
                guard(); check(composer.commentStamp.value?.presentation?.sourceLease === publication &&
                    composer.commentStamp.value?.presentation?.nativeOwner === originalMain)
            } }
        }
        fun closeEditor(editor: javax.swing.JDialog) {
            check(edt { java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow === editor })
            physicalKey(java.awt.event.KeyEvent.VK_ESCAPE)
            await("real ESC disposes exact original composer modal") { edt {
                guard(); !editor.isDisplayable && !editor.isShowing && composer.commentStamp.value == null
            } }
        }
        fun likeAgain(): javax.swing.JDialog {
            tab("简介与分P")
            if (engagement.uiState.value.isLiked) {
                physicalClick(originalMain, "已点赞")
                await("original unlike response consumed") { edt { guard(); !engagement.uiState.value.isLiked } }
            }
            physicalClick(originalMain, "点赞")
            await("actual confirmed original Like and full-client decorative HWND") { edt {
                guard(); engagement.uiState.value.isLiked && engagement.uiState.value.likeBurstVisible && peer()?.isShowing == true
            } }
            return edt { requireNotNull(peer()).also { facts(it, true) } }
        }
        try {
            click("暂停"); await("native pause ACK before full-client carrier scope") { sameNative(); actualPlayer.state.value.nativePaused == true }
            Thread.sleep(200); baseline = actualPlayer.state.value
            if (edt { runCatching { detailPaneScope() }.isFailure }) {
                physicalClick(originalMain, "详情"); await("actual introduction sibling") { edt { runCatching { detailPaneScope() }.isSuccess } }
            }
            tab("简介与分P"); capture("220-feedback-client-baseline")
            val first = likeAgain(); capture("221-feedback-like-button-anchor")
            // The first event is only the anchor screenshot. Do not spend its
            // natural lifetime serializing a tree and then demand live overlap.
            await("first original Like naturally completes without forced replay") { edt {
                guard(); !engagement.uiState.value.likeBurstVisible && !first.isDisplayable && !first.isShowing
            } }
            val second = likeAgain()
            val secondOrigin = requireNotNull(engagement.uiState.value.desktopFeedbackOrigin(DesktopWindowsVideoFeedbackKind.LIKE))
            // No screenshot/input work between Like and the real modal+chooser.
            val nextEditor = openEditor(second)
            awaitLiveHidden(second, secondOrigin, { nextEditor.isShowing && nextEditor.isModal },
                "live same peer hidden behind actual original composer DOCUMENT_MODAL")
            physicalClick(nextEditor,"图片")
            await("actual owned Swing chooser appears") { modal("选择图片")?.let { it.isShowing && it.isModal } == true }
            val chooserPeer = requireNotNull(modal("选择图片"))
            awaitLiveHidden(second, secondOrigin, { chooserPeer.isShowing && chooserPeer.isModal &&
                desktopWindowsFeedbackHasOwnedModal(originalMain) }, "live same peer overlaps actual owned chooser")
            record("feedback-owned-modal-hides-same-peer", mapOf("samePeer" to JsonPrimitive(true),
                "actualDialogModal" to JsonPrimitive(nextEditor.isModal), "sameFullSource" to JsonPrimitive(true),
                "liveOwnedModalOverlapObserved" to JsonPrimitive(true), "liveOwnedChooserOverlapObserved" to JsonPrimitive(true)))
            // Once live overlaps and native hide were observed, later natural
            // completion is valid. It must dispose, never count as restoration.
            capture("224-feedback-owned-chooser-hidden-carrier",chooserPeer)
            val approve = edt {
                guard()
                val chooser = nativeComponents(chooserPeer).filterIsInstance<javax.swing.JFileChooser>().single()
                val fields = nativeComponents(chooser).filterIsInstance<javax.swing.JTextField>().filter { it.isShowing && it.isEnabled }
                val named = fields.filter { field -> val labels = field.accessibleContext.accessibleRelationSet
                    .get(javax.accessibility.AccessibleRelation.LABELED_BY)?.target.orEmpty()
                    (field.accessibleContext.accessibleName.orEmpty()+labels.filterIsInstance<javax.swing.JLabel>().joinToString { it.text.orEmpty() })
                        .let { it.contains("文件名") || it.contains("File name",ignoreCase=true) } }
                val filename = named.singleOrNull() ?: fields.single()
                filename.accessibleContext.accessibleEditableText.setTextContents(replay.commentComposerReplay.image.toAbsolutePath().toString())
                val button = requireNotNull(chooserPeer.rootPane.defaultButton)
                check(button.isShowing && button.isEnabled && SwingUtilities.isDescendingFrom(button,chooser))
                val p=button.locationOnScreen; java.awt.Point(p.x+button.width/2,p.y+button.height/2)
            }
            robot.mouseMove(approve.x,approve.y);robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            try { robot.delay(35) } finally { robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
            await("actual chooser commits only private PNG to original draft") { edt {
                guard(); !chooserPeer.isDisplayable && composer.composerDrafts.value.comments[0L]?.imageUris?.size == 1
            } }
            capture("225-feedback-selected-private-image",nextEditor)
            // Real OS pointer/key input to the original editor, not a VM write.
            val field = edt { guard(); descendants(nextEditor.accessibleContext).single {
                it.accessibleEditableText != null && visible(it, nextEditor) } }
            val point = edt { val c = requireNotNull(field.accessibleComponent); val p = requireNotNull(c.locationOnScreen)
                java.awt.Point(p.x + c.size.width / 2, p.y + c.size.height / 2) }
            robot.mouseMove(point.x,point.y); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            try { robot.delay(35) } finally { robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
            robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL)
            try { physicalKey(java.awt.event.KeyEvent.VK_END) }
            finally { robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL) }
            physicalKey(java.awt.event.KeyEvent.VK_R)
            await("original same-domain draft receives OS key without publishing") { edt {
                guard(); composer.composerDrafts.value.comments[0L]?.text?.endsWith("r") == true
            } }
            if (!live(secondOrigin)) await("naturally finished receipt disposes its exact hidden peer") { edt {
                guard(); !second.isShowing && !second.isDisplayable
            } }
            capture("222-feedback-owned-editor-hidden-carrier",nextEditor)
            closeEditor(nextEditor)
            val modalRestored = restoredOrCompleted(second, secondOrigin)
            capture("223-feedback-modal-dismissed")
            val third=likeAgain()
            val thirdOrigin = requireNotNull(engagement.uiState.value.desktopFeedbackOrigin(DesktopWindowsVideoFeedbackKind.LIKE))
            edt { guard(); originalMain.extendedState=originalPlacement or java.awt.Frame.ICONIFIED }
            await("same full-client peer hides while original Main minimized") { edt {
                guard(true)
                check(live(thirdOrigin)) { "Original Like completed before live minimized overlap was observed" }
                originalMain.extendedState and java.awt.Frame.ICONIFIED != 0 && settled(third, false)
            } }
            Thread.sleep(200)
            edt { guard(true); originalMain.extendedState=originalPlacement }
            val minimizedRestored = restoredOrCompleted(third, thirdOrigin)
            capture("226-feedback-minimize-restored")
            val fallbackLive = edt { guard(); live(thirdOrigin) }
            physicalClick(originalMain,"关闭详情", if (fallbackLive) third else null)
            await("actual Like anchor disposal returns feedback to original video fallback") { edt {
                guard(); all().none { it.accessibleName=="关闭详情" && visible(it) }
            } }
            capture("227-feedback-video-fallback")
            await("original completion disposes every captured decorative peer") { edt {
                guard(); !engagement.uiState.value.likeBurstVisible && registered.none { it.isShowing || it.isDisplayable }
            } }
            replay.brandFeedbackReplay.receipt()
            record("feedback-full-client-actual-main-scope",mapOf("actualOriginalLikeProtocol" to JsonPrimitive(true),
                "inputMechanism" to JsonPrimitive("OS_ROBOT"),"sameActualComposerCommentsAndEngagement" to JsonPrimitive(true),
                "ownedModalAndChooserObserved" to JsonPrimitive(true),"sourcePausePreferencesPreserved" to JsonPrimitive(true),
                "sameFullSource" to JsonPrimitive(true), "liveOwnedModalOverlapObserved" to JsonPrimitive(true),
                "liveOwnedChooserOverlapObserved" to JsonPrimitive(true), "liveOwnerMinimizedOverlapObserved" to JsonPrimitive(true),
                "samePeerModalRestoreObserved" to JsonPrimitive(modalRestored),
                "samePeerMinimizeRestoreObserved" to JsonPrimitive(minimizedRestored),
                "liveVideoFallbackNavigationObserved" to JsonPrimitive(fallbackLive),
                "remoteMutationSent" to JsonPrimitive(false),"physicalFramesRequireHumanReview" to JsonPrimitive(true)))
        } catch (failure: Throwable) {
            primary=failure
            runCatching { capture("feedback-placement-failure") }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(block: () -> Unit) { runCatching(block).exceptionOrNull()?.let { error ->
                val previous = cleanupFailure
                if (previous == null) cleanupFailure = error else previous.addSuppressed(error)
            } }
            for(peer in registered.toList().asReversed()) cleanup { edt {
                var parent: Window? = peer
                while (parent != null && parent !== originalMain) parent = parent.owner
                if(peer.isDisplayable && parent === originalMain)
                    peer.dispatchEvent(java.awt.event.WindowEvent(peer,java.awt.event.WindowEvent.WINDOW_CLOSING))
            } }
            cleanup { edt { if(originalMain.isDisplayable) originalMain.extendedState=originalPlacement } }
            cleanup {
                val deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos()
                while(edt { registered.any { it.isDisplayable } } && System.nanoTime()<deadline) Thread.sleep(50)
                check(edt { registered.none { it.isDisplayable } }) { "Full-client feedback scope leaked its captured owned peer" }
            }
            cleanupFailure?.let { failure -> primary?.addSuppressed(failure) ?: throw failure }
        }
        val afterLayers = settledMainInputLayers("feedback-after")
        check(afterLayers.size == beforeLayers.size && beforeLayers.all { old -> afterLayers.any { it === old } }) {
            "Full-client feedback must restore the exact Main input layer identities"
        }
        click("播放")
        await("same source resumes after full-client scope") { sameNative();playing() }
    }

    private fun exerciseVideoDynamicShare(replay: WindowsVideoLocalReplay) {
        check(!EventQueue.isDispatchThread())
        val (assembly, publication) = actualHotOwner()
        val engagement = assembly.domains.engagement
        val originalMain = edt { window() }
        val source = accepted
        val preferences = PlayerPreferencesStore().read()
        val originalPlacement = edt { originalMain.extendedState }
        val repository = edt { current(); requireNotNull(owner.messagePages.get()).repository }
        val session = replay.commentComposerReplay
        val script = replay.videoDynamicShareReplay
        check(session.authenticated(repository))
        val beforeLayers = settledMainInputLayers("video-share-before")
        val beforeFeedbackId = engagement.uiState.value.maidActionId
        val robot = java.awt.Robot()
        val ownedPeers = linkedSetOf<Window>()
        var baseline = actualPlayer.state.value
        var firstFailure: Throwable? = null
        fun guard(hidden: Boolean = false) {
            current()
            val iconic = originalMain.extendedState and java.awt.Frame.ICONIFIED != 0
            if (hidden && iconic) check(actualCanvas.isDisplayable &&
                SwingUtilities.getWindowAncestor(actualPlayer.surface) === originalMain)
            else sameNative()
            check(window() === originalMain && accepted === source && assembly.owns() &&
                assembly.native.isCurrent(publication) && actualPlayer.ownsSourceSnapshot(source) &&
                assembly.domains.engagement === engagement && session.authenticated(repository) &&
                repository.sessionEpoch == session.epoch() && PlayerPreferencesStore().read() == preferences)
            check(hidden || !iconic)
            val state = actualPlayer.state.value
            check(state.paused && state.nativePaused == true && state.seekCompletedId == baseline.seekCompletedId &&
                state.muted == baseline.muted && state.volume == baseline.volume && state.speed == baseline.speed &&
                kotlin.math.abs(state.positionSeconds - baseline.positionSeconds) < .25)
        }
        fun children(parent: Window): List<Window> = parent.ownedWindows.toList().flatMap { listOf(it) + children(it) }
        fun registerCreatedPeers() {
            // This branch starts with no owned input window. Register each exact
            // new modal immediately, before waiting for its Compose controls.
            children(originalMain).filter { it.isDisplayable && it is javax.swing.JDialog && it.isModal }
                .forEach { ownedPeers.add(it) }
        }
        fun has(surface: Window, label: String): Boolean = descendants(surface.accessibleContext).any {
            hasLabel(it, label) && visible(it, surface) }
        fun physicalClick(surface: Window, label: String) {
            var point: java.awt.Point? = null
            await("complete owned share controls for one real '$label' click") { edt {
                guard(); registerCreatedPeers()
                val control = descendants(surface.accessibleContext).filter {
                    hasLabel(it, label) && visible(it, surface) && it.accessibleStateSet.contains(AccessibleState.ENABLED) &&
                        it.accessibleRole != javax.accessibility.AccessibleRole.SCROLL_PANE &&
                        (it.accessibleAction?.accessibleActionCount ?: 0) == 1 }.singleOrNull() ?: return@edt false
                val c = requireNotNull(control.accessibleComponent); val origin = requireNotNull(c.locationOnScreen)
                point = java.awt.Point(origin.x + c.size.width / 2, origin.y + c.size.height / 2)
                true
            } }
            val p = requireNotNull(point)
            robot.mouseMove(p.x, p.y); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            try { robot.delay(35) } finally { robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
        }
        fun editor(surface: Window): AccessibleContext {
            guard()
            return descendants(surface.accessibleContext).filter {
                it.accessibleEditableText != null && visible(it, surface) &&
                    it.accessibleRole == javax.accessibility.AccessibleRole.TEXT &&
                    it.accessibleStateSet.contains(AccessibleState.EDITABLE) &&
                    it.accessibleStateSet.contains(AccessibleState.ENABLED) }.single()
        }
        fun text(surface: Window): String = edt {
            val field = editor(surface)
            val actual = requireNotNull(field.accessibleText)
            field.accessibleEditableText.getTextRange(0, actual.charCount).orEmpty()
        }
        fun typeDraft(surface: Window) {
            val p = edt {
                val field = editor(surface)
                val c = requireNotNull(field.accessibleComponent); val origin = requireNotNull(c.locationOnScreen)
                java.awt.Point(origin.x + c.size.width / 2, origin.y + c.size.height / 2)
            }
            robot.mouseMove(p.x, p.y); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            try { robot.delay(35) } finally { robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
            robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL)
            try { robot.keyPress(java.awt.event.KeyEvent.VK_A); robot.keyRelease(java.awt.event.KeyEvent.VK_A) }
            finally { robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL) }
            // Plain ASCII real key input; no clipboard, VM write or editableText setter.
            for (ch in WindowsVideoDynamicShareReplay.DRAFT) {
                val key = java.awt.event.KeyEvent.getExtendedKeyCodeForChar(ch.code)
                check(key != java.awt.event.KeyEvent.VK_UNDEFINED)
                if (ch.isUpperCase()) robot.keyPress(java.awt.event.KeyEvent.VK_SHIFT)
                try { robot.keyPress(key); try { robot.delay(15) } finally { robot.keyRelease(key) } }
                finally { if (ch.isUpperCase()) robot.keyRelease(java.awt.event.KeyEvent.VK_SHIFT) }
            }
            await("original dynamic TextField receives the complete OS draft") { text(surface) == WindowsVideoDynamicShareReplay.DRAFT }
        }
        fun capture(id: String, surface: Window = originalMain) {
            val bounds = edt {
                guard(); registerCreatedPeers(); check(surface.isShowing && surface.isDisplayable && ownedWindow(surface))
                val rect = if (surface === originalMain) Rectangle(originalMain.contentPane.locationOnScreen, originalMain.contentPane.size)
                    else Rectangle(surface.bounds)
                check(originalMain.bounds.contains(rect) && surface.graphicsConfiguration.bounds.contains(rect))
                rect
            }
            val image = robot.createScreenCapture(bounds)
            val tree = edt {
                guard(); check(surface.isShowing && surface.isDisplayable && ownedWindow(surface))
                check((if (surface === originalMain) Rectangle(originalMain.contentPane.locationOnScreen, originalMain.contentPane.size)
                    else Rectangle(surface.bounds)) == bounds)
                descendants(surface.accessibleContext).joinToString("\n") { "${it.accessibleName}\t${it.accessibleRole}\t${it.accessibleStateSet}" }
            }
            check(ImageIO.write(image, "png", report.resolve("$id-screen.png").toFile()))
            Files.writeString(report.resolve("$id-accessibility.tsv"), tree, CREATE_NEW, WRITE)
        }
        fun openSheet(): javax.swing.JDialog {
            physicalClick(originalMain, "更多播放操作")
            await("same Main original playback menu") { edt { guard(); playerMenuSurface() != null } }
            val menu = edt { requireNotNull(playerMenuSurface()).also { if (it !== originalMain) ownedPeers.add(it) } }
            ensurePlayerMenuItemVisible(menu, "分享视频")
            physicalClick(menu, "分享视频")
            var result: javax.swing.JDialog? = null
            await("exact owned original share peer") { edt {
                guard(); registerCreatedPeers()
                children(originalMain).filterIsInstance<javax.swing.JDialog>().filter {
                    it.isDisplayable && it.title == "分享视频" }.also { check(it.size <= 1) }.singleOrNull()
                    ?.also { result = it; ownedPeers.add(it) }?.isShowing == true
            } }
            val peer = requireNotNull(result)
            await("complete original dynamic share item") { edt {
                guard(); registerCreatedPeers(); peer.isShowing && peer.isModal && has(peer, "分享到动态") && has(peer, "取消")
            } }
            return peer
        }
        fun openDynamic(sheet: Window): Window {
            physicalClick(sheet, "分享到动态")
            var result: Window? = null
            await("original dynamic dialog TextField and actions") { edt {
                guard(); registerCreatedPeers()
                val surfaces = (listOf(sheet) + children(sheet)).filter { it.isShowing && it.isDisplayable && ownedWindow(it) }
                surfaces.filter { surface -> has(surface, "分享到动态") && has(surface, "取消") && has(surface, "发布") &&
                    runCatching { editor(surface) }.isSuccess }
                    .also { check(it.size <= 1) }.singleOrNull()?.also { result = it } != null
            } }
            return requireNotNull(result)
        }
        fun closed(sheet: Window): Boolean = edt {
            guard(); registerCreatedPeers(); !sheet.isShowing && !sheet.isDisplayable &&
                ownedPeers.none { it.isShowing || it.isDisplayable }
        }
        try {
            check(edt { children(originalMain).none { it.isDisplayable && it is javax.swing.JDialog && it.isModal } })
            click("暂停"); await("native pause ACK before video share") { sameNative(); actualPlayer.state.value.nativePaused == true }
            Thread.sleep(200); baseline = actualPlayer.state.value
            val firstSheet = openSheet(); capture("230-share-original-sheet", firstSheet)
            val firstDialog = openDynamic(firstSheet); typeDraft(firstDialog)
            val draftBeforeHide = text(firstDialog)
            check(draftBeforeHide == WindowsVideoDynamicShareReplay.DRAFT && script.count() == 0)
            // The exact retained outer JDialog must hide without disposing its
            // original TextField composition or sending the unsent draft.
            edt {
                guard(); check(firstSheet.isShowing && firstSheet.isDisplayable && firstDialog.isShowing)
                originalMain.extendedState = originalPlacement or java.awt.Frame.ICONIFIED
            }
            await("open original share draft hides on the same displayable native peer") { edt {
                guard(true); registerCreatedPeers(); check(script.count() == 0)
                originalMain.extendedState and java.awt.Frame.ICONIFIED != 0 &&
                    firstSheet.isDisplayable && !firstSheet.isShowing && !firstSheet.isVisible &&
                    firstDialog.isDisplayable && !firstDialog.isShowing
            } }
            edt {
                guard(true); check(firstSheet.isDisplayable && !firstSheet.isShowing && script.count() == 0)
                originalMain.extendedState = originalPlacement
            }
            await("open original share draft restores the exact native peer and complete text") { edt {
                guard(); registerCreatedPeers(); check(script.count() == 0)
                val sameSheet = children(originalMain).filterIsInstance<javax.swing.JDialog>()
                    .filter { it.isDisplayable && it.title == "分享视频" }.singleOrNull() === firstSheet
                sameSheet && firstSheet.isShowing && firstSheet.isVisible && firstDialog.isShowing &&
                    firstDialog.isDisplayable && text(firstDialog) == draftBeforeHide
            } }
            capture("231-share-dynamic-draft", firstDialog)
            physicalClick(firstDialog, "取消")
            await("cancel disposes exact original share without a POST") { closed(firstSheet) }
            check(script.count() == 0 && engagement.uiState.value.maidActionId == beforeFeedbackId)
            // After explicit cancellation, a second hide/restore must not
            // revive the disposed share or change the same paused native source.
            edt { guard(); originalMain.extendedState = originalPlacement or java.awt.Frame.ICONIFIED }
            await("temporary Main hide keeps the same native source after cancel") { edt {
                guard(true); originalMain.extendedState and java.awt.Frame.ICONIFIED != 0 &&
                    ownedPeers.none { it.isShowing || it.isDisplayable }
            } }
            edt { guard(true); originalMain.extendedState = originalPlacement }
            await("same native source returns without reviving the cancelled share") { edt {
                guard(); ownedPeers.none { it.isShowing || it.isDisplayable }
            } }
            capture("232-share-dynamic-cancelled")
            val secondSheet = openSheet(); val secondDialog = openDynamic(secondSheet)
            typeDraft(secondDialog) // Cancelled dialog does not promise draft retention.
            physicalClick(secondDialog, "发布")
            await("original failed share preserves draft/error and permits manual retry") { edt {
                guard(); registerCreatedPeers(); secondDialog.isShowing && has(secondDialog, WindowsVideoDynamicShareReplay.RETRY_ERROR) &&
                    has(secondDialog, "发布") && script.count() == 1
            } && text(secondDialog) == WindowsVideoDynamicShareReplay.DRAFT }
            check(engagement.uiState.value.maidActionId == beforeFeedbackId)
            capture("233-share-dynamic-retry-error", secondDialog)
            physicalClick(secondDialog, "发布")
            var confirmed: DesktopWindowsVideoFeedbackOrigin? = null
            val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            while (confirmed == null && System.nanoTime() < deadline) {
                confirmed = edt {
                    guard(); registerCreatedPeers()
                    val state = engagement.uiState.value
                    state.desktopFeedbackOrigin(DesktopWindowsVideoFeedbackKind.SHARE)?.takeIf {
                        state.maidActionId > beforeFeedbackId && it.instanceId == state.maidActionId &&
                            it.isCurrent(publication, requireNotNull(state.subject)) && script.count() == 2
                    }
                }
                if (confirmed == null) Thread.sleep(10)
            }
            val success = requireNotNull(confirmed) { "Synthetic protocol success did not publish the current original SHARE receipt" }
            await("successful original dialog disposes its exact captured peer") { closed(secondSheet) }
            capture("234-share-dynamic-success")
            val protocol = script.receipt()
            Files.writeString(report.resolve("video-share-payload-receipt.json"), protocol.toString(), CREATE_NEW, WRITE)
            record("video-share-original-dynamic-completed", mapOf(
                "sameActualEngagementDomain" to JsonPrimitive(true), "sameAcceptedPublicationIdentity" to JsonPrimitive(true),
                "samePausedNativeSourceAndPreferences" to JsonPrimitive(true), "actualOriginalSheetAndDynamicDialog" to JsonPrimitive(true),
                "cancelProducedZeroPosts" to JsonPrimitive(true), "originalFailureDraftAndErrorRetained" to JsonPrimitive(true),
                "manualRetryCompletedOriginalProtocol" to JsonPrimitive(true), "sameSourceHiddenRestoreObserved" to JsonPrimitive(true),
                "openDraftSamePeerHiddenRestore" to JsonPrimitive(true), "openDraftTextPreserved" to JsonPrimitive(true),
                "currentSourceConfirmedShareReceiptObserved" to JsonPrimitive(true), "confirmedShareInstanceId" to JsonPrimitive(success.instanceId),
                "confirmedShareSourceVersion" to JsonPrimitive(source.sourceVersion), "exactOwnedPeersDisposed" to JsonPrimitive(true),
                "inputMechanism" to JsonPrimitive("OS_ROBOT"), "syntheticPrimaryMid" to JsonPrimitive(990000024L),
                "actualAccountEpoch" to JsonPrimitive(session.epoch()), "remoteMutationSent" to JsonPrimitive(false),
                "realCredentialsUsed" to JsonPrimitive(false), "physicalFramesRequireHumanReview" to JsonPrimitive(true)))
        } catch (failure: Throwable) {
            firstFailure = failure
            runCatching { val surface = edt { ownedPeers.lastOrNull { it.isShowing && it.isDisplayable } ?: originalMain }
                capture("video-share-input-failure", surface) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(block: () -> Unit) { runCatching(block).exceptionOrNull()?.let { failure ->
                val prior = cleanupFailure; if (prior == null) cleanupFailure = failure else prior.addSuppressed(failure) } }
            // Only captured test-owned references; never cancel a shared VM or
            // scan/kill a global window/process to clean up this branch.
            for (peer in ownedPeers.toList().asReversed()) cleanup { edt {
                var parent: Window? = peer
                while (parent != null && parent !== originalMain) parent = parent.owner
                if (peer.isDisplayable && parent === originalMain)
                    peer.dispatchEvent(java.awt.event.WindowEvent(peer, java.awt.event.WindowEvent.WINDOW_CLOSING))
            } }
            cleanup { edt { if (originalMain.isDisplayable) originalMain.extendedState = originalPlacement } }
            cleanup { val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
                while (edt { ownedPeers.any { it.isDisplayable } } && System.nanoTime() < deadline) Thread.sleep(50)
                check(edt { ownedPeers.none { it.isDisplayable } }) { "Captured video-share peer leaked" }
            }
            cleanupFailure?.let { failure -> firstFailure?.addSuppressed(failure) ?: throw failure }
        }
        val afterLayers = settledMainInputLayers("video-share-after")
        check(afterLayers.size == beforeLayers.size && beforeLayers.all { old -> afterLayers.any { it === old } })
        click("播放"); await("same source resumes after original video share") { sameNative(); playing() }
    }

    private fun exerciseCommentComposer(localReplay: WindowsVideoLocalReplay) {
        check(!EventQueue.isDispatchThread())
        sameNative(); check(playing())
        val script = localReplay.commentComposerReplay
        val (assembly, publication) = actualHotOwner()
        val comments = assembly.domains.comments
        val composer = assembly.domains.composer
        val original = accepted
        val originalMain = edt { window() }
        val originalBounds = edt { Rectangle(originalMain.bounds) }
        val prefs = PlayerPreferencesStore().read()
        val repository = edt { current(); requireNotNull(owner.messagePages.get()).repository }
        check(script.authenticated(repository) && repository.sessionEpoch == script.epoch())
        val beforeLayers = settledMainInputLayers("composer-before")
        await("same authenticated comment owner and original ordinary HOT replies") { edt {
            current(); sameNative()
            comments.commentState.value.let { !it.isRepliesLoading && !it.isRepliesRefreshing &&
                it.replies.isNotEmpty() && it.currentMid == WindowsCommentComposerReplay.MID && it.canInputComment }
        } }
        val mainReplies = comments.commentState.value.replies
        val nextPage = comments.commentState.value.nextPage
        val repliesEnd = comments.commentState.value.isRepliesEnd
        if (edt { runCatching { detailPaneScope() }.isFailure }) {
            click("详情")
            await("actual detail viewport before composer input") { edt { runCatching { detailPaneScope() }.isSuccess } }
        }
        if (!edt { commentsTabSelected() }) {
            edt {
                current(); sameNative()
                val tab = descendants(detailPaneScope()).single { it.accessibleName == "评论" && visible(it) &&
                    it.accessibleRole == javax.accessibility.AccessibleRole.PAGE_TAB &&
                    (it.accessibleAction?.accessibleActionCount ?: 0) == 1 }
                clickOwnedComposeMouse(window(), tab)
            }
            await("actual authenticated comment tab selected") { edt {
                current(); sameNative()
                runCatching { commentsTabSelected() }.getOrDefault(false)
            } }
        }
        click("暂停")
        await("native pause ACK before editor input") { sameNative(); actualPlayer.state.value.nativePaused == true }
        Thread.sleep(300)
        val baseline = actualPlayer.state.value
        val ownedPeers = linkedSetOf<javax.swing.JDialog>()
        fun currentSource() {
            current(); sameNative()
            check(accepted === original && window() === originalMain && window().bounds == originalBounds &&
                assembly.owns() && assembly.native.isCurrent(publication) && assembly.domains.composer === composer &&
                assembly.domains.comments === comments && actualPlayer.ownsSourceSnapshot(original) &&
                script.authenticated(repository) && PlayerPreferencesStore().read() == prefs)
            actualPlayer.state.value.let { state ->
                check(state.nativePaused == true && state.paused && state.seekCompletedId == baseline.seekCompletedId &&
                    state.volume == baseline.volume && state.muted == baseline.muted && state.speed == baseline.speed &&
                    kotlin.math.abs(state.positionSeconds - baseline.positionSeconds) <= 0.25) {
                    "Original comment editor changed the complete paused playback source/state"
                }
            }
            comments.commentState.value.let { state ->
                check(state.replies == mainReplies && state.nextPage == nextPage && state.isRepliesEnd == repliesEnd &&
                    !state.isRepliesLoading && !state.isRepliesRefreshing && state.currentMid == WindowsCommentComposerReplay.MID)
            }
            check(!composer.isSendingComment.value) { "This fixture must never publish a comment" }
        }
        fun dialog(title: String): javax.swing.JDialog? = edt {
            currentSource()
            Window.getWindows().filterIsInstance<javax.swing.JDialog>().filter {
                it.isShowing && it.isDisplayable && ownedWindow(it) && it.title == title
            }.also { check(it.size <= 1) { "Duplicate actual '$title' peer" } }.singleOrNull()?.also { ownedPeers.add(it) }
        }
        fun has(surface: Window, label: String): Boolean = edt {
            currentSource(); descendants(surface.accessibleContext).any { hasLabel(it, label) && visible(it, surface) }
        }
        fun capture(id: String, surface: Window) {
            val bounds = edt {
                currentSource()
                check(surface.isShowing && surface.isDisplayable && ownedWindow(surface) && surface !== originalMain &&
                    originalMain.bounds.contains(surface.bounds) && surface.graphicsConfiguration.bounds.contains(surface.bounds))
                Files.writeString(report.resolve("$id-accessibility.tsv"), descendants(surface.accessibleContext).joinToString("\n") {
                    "${it.accessibleName}\t${it.accessibleRole}\t${it.accessibleStateSet}\t${it.accessibleAction?.accessibleActionCount ?: 0}"
                }, CREATE_NEW, WRITE)
                Rectangle(surface.bounds)
            }
            check(ImageIO.write(java.awt.Robot().createScreenCapture(bounds), "png", report.resolve("$id-screen.png").toFile()))
            edt { currentSource(); check(surface.isShowing && surface.bounds == bounds) }
        }
        fun draft() = composer.composerDrafts.value.comments[0L]
        fun editor(surface: Window): AccessibleContext {
            currentSource()
            check(surface.isShowing && surface.isDisplayable && ownedWindow(surface))
            // SwingPanel also exposes a Compose semantics mirror with SetText.
            // Read and edit the sole actual native document in this exact peer.
            return nativeComponents(surface).filterIsInstance<javax.swing.JTextPane>().filter {
                it.javaClass.name == "com.bilipai.desktop.ui.DesktopInlineEmotePane" &&
                    it.isShowing && it.isDisplayable && it.isEnabled &&
                    SwingUtilities.getWindowAncestor(it) === surface && visible(it.accessibleContext, surface)
            }.map { it.accessibleContext }.single().also { check(it.accessibleEditableText != null) }
        }
        fun editorText(surface: Window): String {
            val text = requireNotNull(editor(surface).accessibleText)
            return (0 until text.charCount).joinToString("") { index ->
                requireNotNull(text.getAtIndex(javax.accessibility.AccessibleText.CHARACTER, index))
            }
        }
        fun open(): javax.swing.JDialog {
            edt { currentSource(); check(composer.commentStamp.value == null && !composer.showCommentDialog.value) }
            clickFeatureItem(originalMain, "发表评论")
            await("actual original composer native peer and source-bound domain stamp") {
                val peer = dialog("发表评论") ?: return@await false
                edt {
                    currentSource()
                    val stamp = composer.commentStamp.value ?: return@edt false
                    stamp.presentation.sourceLease === publication && stamp.presentation.nativeOwner === originalMain &&
                        stamp.presentation.isCurrent() && composer.showCommentDialog.value &&
                        listOf("表情", "提及用户", "图片", "转发到动态", "发布").all { has(peer, it) } &&
                        runCatching { editor(peer).also { check(it.accessibleStateSet.contains(AccessibleState.EDITABLE)) } }.isSuccess
                }
            }
            return requireNotNull(dialog("发表评论"))
        }
        fun close(surface: javax.swing.JDialog) {
            edt { currentSource(); surface.dispatchEvent(java.awt.event.WindowEvent(surface, java.awt.event.WindowEvent.WINDOW_CLOSING)) }
            await("original editor closes exact native peer/stamp and optional mention job") { edt {
                currentSource(); !surface.isShowing && !surface.isDisplayable && composer.commentStamp.value == null &&
                    !composer.showCommentDialog.value && !composer.commentMentionSearchState.value.isLoading
            } }
        }
        fun swingMouse(component: Component) {
            check(EventQueue.isDispatchThread())
            currentSource()
            check(component.isShowing && component.isEnabled && component.isDisplayable &&
                SwingUtilities.getWindowAncestor(component)?.let(::ownedWindow) == true)
            val now = System.currentTimeMillis(); val x = component.width / 2; val y = component.height / 2
            component.dispatchEvent(MouseEvent(component, MouseEvent.MOUSE_MOVED, now, 0, x, y, 0, false))
            component.dispatchEvent(MouseEvent(component, MouseEvent.MOUSE_PRESSED, now + 1,
                InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1))
            component.dispatchEvent(MouseEvent(component, MouseEvent.MOUSE_RELEASED, now + 2, 0, x, y, 1, false, MouseEvent.BUTTON1))
        }
        var firstFailure: Throwable? = null
        check(dialog("发表评论") == null && dialog("选择图片") == null)
        try {
            val first = open()
            val firstStamp = requireNotNull(composer.commentStamp.value)
            edt { currentSource(); editor(first).accessibleEditableText.setTextContents(WindowsCommentComposerReplay.DRAFT) }
            await("original text field publishes the root draft") { edt {
                currentSource(); draft()?.text == WindowsCommentComposerReplay.DRAFT
            } }
            capture("210-composer-text-draft", first)
            close(first)
            check(draft()?.text == WindowsCommentComposerReplay.DRAFT)
            var second = open()
            check(composer.commentStamp.value !== firstStamp)
            await("original editor reopens actual retained text") { edt {
                currentSource()
                editorText(second) == WindowsCommentComposerReplay.DRAFT && draft()?.text == WindowsCommentComposerReplay.DRAFT
            } }
            capture("211-composer-reopened-draft", second)
            clickFeatureItem(second, "表情")
            // Semantics can precede the completed panel layout. UI03 captured
            // the old toolbar position after the new emote semantics appeared.
            // Wait for the complete panel and stable actual control geometry,
            // then deliver exactly one ordinary OS click. Never retry insertion.
            fun emoteBounds(): Rectangle? {
                currentSource()
                if (!listOf("小黄脸", "小电视", "热词系列", "私有表情4", "颜文字").all { has(second, it) }) return null
                val controls = descendants(second.accessibleContext).filter {
                    hasLabel(it, WindowsCommentComposerReplay.EMOTE) && visible(it, second) &&
                        it.accessibleRole == javax.accessibility.AccessibleRole.PUSH_BUTTON &&
                        it.accessibleStateSet.contains(AccessibleState.ENABLED) &&
                        (it.accessibleAction?.accessibleActionCount ?: 0) == 1
                }
                check(controls.size <= 1) { "Ambiguous original emote control" }
                val control = controls.singleOrNull() ?: return null
                val component = requireNotNull(control.accessibleComponent)
                return Rectangle(requireNotNull(component.locationOnScreen), component.size).also {
                    check(it.width > 0 && it.height > 0 && second.bounds.contains(it))
                }
            }
            var stableBounds: Rectangle? = null
            var stableSince = 0L
            await("complete original emote panel has stable physical control geometry") { edt {
                currentSource()
                val bounds = emoteBounds()
                val now = System.nanoTime()
                if (bounds == null || bounds != stableBounds) {
                    stableBounds = bounds; stableSince = now; false
                } else now - stableSince >= Duration.ofMillis(200).toNanos()
            } }
            capture("212-composer-original-emote", second)
            val expectedStamp = requireNotNull(composer.commentStamp.value)
            val expectedEditor = edt { editor(second) }
            var everNativeEmote = false
            var everDomainEmote = false
            fun observeEmote(id: String): Boolean {
                currentSource()
                val nativeText = editorText(second)
                val domainText = draft()?.text.orEmpty()
                val nativeHas = nativeText.contains(WindowsCommentComposerReplay.EMOTE)
                val domainHas = domainText.contains(WindowsCommentComposerReplay.EMOTE)
                everNativeEmote = everNativeEmote || nativeHas
                everDomainEmote = everDomainEmote || domainHas
                if (id.isNotEmpty()) {
                    val field = editor(second)
                    val text = requireNotNull(field.accessibleText)
                    // Diagnostic geometry may disappear while the original panel refreshes after insertion.
                    // The pre-click target and original draft success checks remain mandatory.
                    val bounds = emoteBounds()
                    record(id, mapOf("sameOriginalComposerStamp" to JsonPrimitive(composer.commentStamp.value === expectedStamp),
                        "sameNativeEditor" to JsonPrimitive(field === expectedEditor),
                        "nativeTextLength" to JsonPrimitive(nativeText.length), "domainTextLength" to JsonPrimitive(domainText.length),
                        "nativeContainsEmote" to JsonPrimitive(nativeHas), "domainContainsEmote" to JsonPrimitive(domainHas),
                        "everNativeContainsEmote" to JsonPrimitive(everNativeEmote), "everDomainContainsEmote" to JsonPrimitive(everDomainEmote),
                        "nativeCaret" to JsonPrimitive(text.caretPosition),
                        "nativeEditable" to JsonPrimitive(field.accessibleStateSet.contains(AccessibleState.EDITABLE)),
                        "focusOwnerClass" to JsonPrimitive(java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner?.javaClass?.name.orEmpty()),
                        "emoteControlCurrentlyResolved" to JsonPrimitive(bounds != null),
                        "emoteX" to (bounds?.x?.let(::JsonPrimitive) ?: JsonNull), "emoteY" to (bounds?.y?.let(::JsonPrimitive) ?: JsonNull),
                        "emoteWidth" to (bounds?.width?.let(::JsonPrimitive) ?: JsonNull), "emoteHeight" to (bounds?.height?.let(::JsonPrimitive) ?: JsonNull),
                        "inputMechanism" to JsonPrimitive("OS_ROBOT_SINGLE_CLICK")))
                }
                return domainHas
            }
            val emotePoint = edt {
                currentSource(); check(composer.commentStamp.value === expectedStamp && editor(second) === expectedEditor)
                val bounds = requireNotNull(emoteBounds()); check(bounds == stableBounds)
                observeEmote("composer-emote-before-os-click")
                java.awt.Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)
            }
            val emoteRobot = java.awt.Robot()
            emoteRobot.mouseMove(emotePoint.x, emotePoint.y)
            emoteRobot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            try { emoteRobot.delay(35) } finally { emoteRobot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
            try {
                await("actual emote click updates the original draft") { edt { observeEmote("") } }
                edt { observeEmote("composer-emote-after-os-click") }
            } catch (failure: Throwable) {
                runCatching { edt { observeEmote("composer-emote-os-click-timeout") } }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
            // Hide the actual emote panel before the original mention toolbar.
            clickFeatureItem(second, "表情")
            await("original emote panel naturally retires before mention toolbar input") {
                !has(second, WindowsCommentComposerReplay.EMOTE)
            }
            clickFeatureItem(second, "提及用户")
            await("original mention query field appears") { has(second, "搜索好友昵称") }
            edt {
                currentSource()
                val fields = descendants(second.accessibleContext).filter { it.accessibleEditableText != null &&
                    it.accessibleStateSet.contains(AccessibleState.EDITABLE) && visible(it, second) }
                val query = fields.filter { hasLabel(it, "搜索好友昵称") }.singleOrNull()
                    ?: fields.single { it.accessibleText?.charCount == 0 }
                query.accessibleEditableText.setTextContents(WindowsCommentComposerReplay.MENTION_QUERY)
            }
            await("original debounced mention request result is rendered") {
                has(second, WindowsCommentComposerReplay.FRIEND_NAME) && edt {
                    currentSource(); composer.commentMentionSearchState.value.let {
                        !it.isLoading && it.query == WindowsCommentComposerReplay.MENTION_QUERY &&
                            it.users.singleOrNull()?.uid == WindowsCommentComposerReplay.FRIEND_MID
                    }
                }
            }
            capture("213-composer-original-mention", second)
            clickFeatureItem(second, WindowsCommentComposerReplay.FRIEND_NAME)
            await("original mention insertion updates the original draft") { edt {
                currentSource(); draft()?.text?.contains("@${WindowsCommentComposerReplay.FRIEND_NAME}") == true
            } }
            val mentionedDraft = edt {
                currentSource()
                requireNotNull(draft()).also {
                    check(!it.syncToDynamic && it.imageUris.isEmpty())
                    check(it.text.contains(WindowsCommentComposerReplay.DRAFT) &&
                        it.text.contains(WindowsCommentComposerReplay.EMOTE) &&
                        it.text.contains("@${WindowsCommentComposerReplay.FRIEND_NAME}"))
                }
            }
            // Persist the actual selected mention before any later editing,
            // sync toggle or image selection can incidentally publish it.
            close(second)
            second = open()
            await("original mention draft survives immediate close and reopen without another edit") { edt {
                currentSource()
                draft() == mentionedDraft && editorText(second) == mentionedDraft.text
            } }
            clickFeatureItem(second, "转发到动态")
            await("original sync-to-dynamic toggle publishes its true draft flag") { edt {
                currentSource(); draft()?.syncToDynamic == true
            } }
            // Real Compose mouse release is posted asynchronously: its original
            // picker enters a Swing modal secondary loop on EDT. Never block
            // this worker in invokeAndWait until the chooser has been answered.
            val postedFailure = AtomicReference<Throwable?>()
            val posted = AtomicBoolean(false)
            EventQueue.invokeLater {
                runCatching {
                    currentSource()
                    val imageButton = descendants(second.accessibleContext).single { hasLabel(it, "图片") &&
                        visible(it, second) && it.accessibleStateSet.contains(AccessibleState.ENABLED) &&
                        (it.accessibleAction?.accessibleActionCount ?: 0) == 1 }
                    clickOwnedComposeMouse(second, imageButton); posted.set(true)
                }.exceptionOrNull()?.let(postedFailure::set)
            }
            await("actual original OS chooser opens after asynchronous toolbar input") {
                postedFailure.get()?.let { throw it }
                posted.get() && dialog("选择图片") != null
            }
            val pickerPeer = requireNotNull(dialog("选择图片"))
            val chooser = edt {
                currentSource()
                check(pickerPeer.isModal && ownedWindow(pickerPeer))
                nativeComponents(pickerPeer).filterIsInstance<javax.swing.JFileChooser>().single()
            }
            capture("214-composer-owned-os-chooser", pickerPeer)
            edt {
                currentSource()
                check(chooser.dialogType == javax.swing.JFileChooser.OPEN_DIALOG && chooser.isMultiSelectionEnabled)
                val inputs = nativeComponents(chooser).filterIsInstance<javax.swing.JTextField>().filter { it.isShowing && it.isEnabled }
                val named = inputs.filter { field ->
                    val context = field.accessibleContext
                    val labels = context.accessibleRelationSet.get(javax.accessibility.AccessibleRelation.LABELED_BY)?.target.orEmpty()
                    val text = context.accessibleName.orEmpty() + labels.filterIsInstance<javax.swing.JLabel>().joinToString { it.text.orEmpty() }
                    text.contains("文件名") || text.contains("File name", ignoreCase = true)
                }
                val filename = named.singleOrNull() ?: inputs.single()
                check(script.image.toRealPath().parent == report && Files.isRegularFile(script.image, NOFOLLOW_LINKS) && !Files.isSymbolicLink(script.image))
                filename.accessibleContext.accessibleEditableText.setTextContents(script.image.toAbsolutePath().toString())
                val expected = chooser.approveButtonText ?: javax.swing.UIManager.getString("FileChooser.openButtonText")
                val approve = pickerPeer.rootPane.defaultButton?.takeIf { it.isShowing && it.isEnabled && SwingUtilities.isDescendingFrom(it, chooser) }
                    ?: nativeComponents(chooser).filterIsInstance<javax.swing.JButton>().single { it.isShowing && it.isEnabled && it.text == expected }
                // Ordinary native button mouse input, not setSelectedFile,
                // approveSelection, selected-image injection or VM writes.
                swingMouse(approve)
            }
            await("real picker disposes and original input retains selected private PNG") { edt {
                currentSource(); !pickerPeer.isShowing && !pickerPeer.isDisplayable && draft()?.imageUris?.size == 1 &&
                    has(second, "已选 1/9 张") && has(second, "已选图片")
            } }
            val withImage = requireNotNull(draft())
            check(withImage.text.contains(WindowsCommentComposerReplay.DRAFT) && withImage.text.contains(WindowsCommentComposerReplay.EMOTE) &&
                withImage.text.contains("@${WindowsCommentComposerReplay.FRIEND_NAME}") && withImage.syncToDynamic)
            capture("215-composer-selected-private-image", second)
            close(second)
            val third = open()
            await("reopened editor restores text/emote/mention/image/sync together") { edt {
                currentSource(); draft() == withImage && has(third, "已选 1/9 张") && has(third, "已选图片") &&
                    editorText(third) == withImage.text
            } }
            capture("216-composer-restored-complete-draft", third)
            clickFeatureItem(third, "移除")
            await("original remove image action updates only the current draft") { edt {
                currentSource(); draft()?.imageUris?.isEmpty() == true && draft()?.text == withImage.text && draft()?.syncToDynamic == true
            } }
            capture("217-composer-image-removed", third)
            close(third)
            record("composer-original-input-closed-without-publish", mapOf(
                "sameActualComposerDomain" to JsonPrimitive(true), "sourcePausedAndPreferencesPreserved" to JsonPrimitive(true),
                "textDraftRestored" to JsonPrimitive(true), "originalEmoteAndMentionInserted" to JsonPrimitive(true),
                "originalSyncFlagRestored" to JsonPrimitive(true), "realOwnedOsChooserPrivatePngSelected" to JsonPrimitive(true),
                "selectedImageRemoved" to JsonPrimitive(true), "publishClicked" to JsonPrimitive(false),
                "realCredentialsUsed" to JsonPrimitive(false), "originalImageUploadAccepted" to JsonPrimitive(false)))
        } catch (failure: Throwable) {
            firstFailure = failure
            runCatching { val peer = edt { ownedPeers.lastOrNull { it.isShowing && it.isDisplayable } }
                if (peer != null) capture("composer-input-failure", peer) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            for (peer in ownedPeers.toList().asReversed()) {
                val failure = runCatching { edt {
                    var parent: Window? = peer
                    while (parent != null && parent !== originalMain) parent = parent.owner
                    if (peer.isDisplayable && parent === originalMain)
                        peer.dispatchEvent(java.awt.event.WindowEvent(peer, java.awt.event.WindowEvent.WINDOW_CLOSING))
                }
                    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                    while ((peer.isShowing || peer.isDisplayable) && System.nanoTime() < deadline) Thread.sleep(50)
                    check(!peer.isShowing && !peer.isDisplayable) { "Captured composer/chooser peer did not dispose in cleanup" }
                }.exceptionOrNull()
                if (failure != null) {
                    val previous = cleanupFailure
                    if (previous == null) cleanupFailure = failure else previous.addSuppressed(failure)
                }
            }
            if (cleanupFailure != null) {
                val primary = firstFailure
                if (primary != null) primary.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
        }
        click("关闭详情")
        await("actual comment detail viewport retires after editor proof") { edt {
            currentSource(); all().none { it.accessibleName == "关闭详情" && visible(it) }
        } }
        val afterLayers = settledMainInputLayers("composer-after")
        check(afterLayers.size == beforeLayers.size && beforeLayers.all { old -> afterLayers.any { it === old } }) {
            "Original composer must restore the exact Main scene layer identities"
        }
        click("播放")
        val clock = actualPlayer.state.value.positionSeconds
        await("same original native source resumes after original composer close") {
            sameNative(); assembly.native.isCurrent(publication) && playing() && actualPlayer.state.value.positionSeconds > clock + 0.20
        }
    }

    private fun exerciseOriginalVideoInteractions() {
        sameNative(); check(playing())
        val (assembly, publication) = actualHotOwner()
        val original = accepted
        val layers = settledMainInputLayers("original-interactions-before")
        fun currentSource() {
            sameNative()
            check(accepted == original && assembly.owns() && assembly.native.isCurrent(publication))
            check(actualPlayer.state.value.volume == 0.0 && actualPlayer.state.value.muted)
        }
        fun dialog(title: String): javax.swing.JDialog? = edt {
            currentSource()
            Window.getWindows().filterIsInstance<javax.swing.JDialog>().filter {
                it.isShowing && it.isDisplayable && ownedWindow(it) && it.title == title
            }.singleOrNull()
        }
        fun openMore(label: String) {
            click("更多播放操作")
            await("actual menu for original $label") { edt { playerMenuSurface() != null } }
            clickFeatureItem(edt { requireNotNull(playerMenuSurface()) }, label)
        }
        openMore("发送弹幕")
        await("original guest danmaku login feedback") { edt {
            currentSource()
            all().any { hasLabel(it, "请先登录后再发送弹幕") && visible(it) }
        } }
        check(!assembly.playback.showDanmakuDialog.value && !assembly.playback.isSendingDanmaku.value)
        check(dialog("发送弹幕") == null && playing())
        record("174-original-danmaku-guest", mapOf(
            "actualMenuItemUsed" to JsonPrimitive("发送弹幕"),
            "originalLoginFeedbackVisible" to JsonPrimitive("请先登录后再发送弹幕"),
            "originalComposerVisible" to JsonPrimitive(false),
            "fullOriginalPublicationStillOwned" to JsonPrimitive(true),
            "realAccountUsed" to JsonPrimitive(false), "remoteSendSubmitted" to JsonPrimitive(false)))
        openMore("分享视频")
        await("actual native original share dialog") { dialog("分享视频") != null }
        val share = requireNotNull(dialog("分享视频"))
        val shareLabels = listOf("链接", "卡片", "B 站好友", "复制链接", "更多", "取消")
        await("complete original share controls") { edt {
            currentSource()
            val nodes = descendants(share.accessibleContext)
            shareLabels.all { label -> nodes.any { hasLabel(it, label) && visible(it, share) } }
        } }
        edt {
            check(share.isModal && ownedWindow(share) && window().bounds.contains(share.bounds))
            captureOwnedExtraSurface("175-original-video-share", share)
        }
        clickFeatureItem(share, "取消")
        await("original share cancel retires native dialog") { edt {
            currentSource()
            !share.isShowing && !share.isDisplayable && playerMenuSurface() == null
        } }
        record("175-original-video-share", mapOf(
            "actualMenuItemUsed" to JsonPrimitive("分享视频"),
            "actualOriginalLabels" to JsonArray(shareLabels.map(::JsonPrimitive)),
            "actualCancelControlUsed" to JsonPrimitive("取消"),
            "fullOriginalPublicationStillOwned" to JsonPrimitive(true),
            "clipboardWritten" to JsonPrimitive(false), "systemShareInvoked" to JsonPrimitive(false)))
        click("暂停")
        await("native pause before original AI timestamp") { actualPlayer.state.value.nativePaused == true }
        click("详情")
        await("original interaction detail entries") { edt { runCatching { detailPaneScope() }.isSuccess } }
        edt {
            val tab = descendants(detailPaneScope()).single { it.accessibleName == "简介与分P" &&
                visible(it) && it.accessibleRole == javax.accessibility.AccessibleRole.PAGE_TAB &&
                (it.accessibleAction?.accessibleActionCount ?: 0) == 1 }
            clickOwnedComposeMouse(window(), tab)
        }
        await("actual introduction tab selected for original interaction entries") { edt {
            currentSource()
            runCatching {
                descendants(detailPaneScope()).single { it.accessibleName == "简介与分P" &&
                    visible(it) && it.accessibleRole == javax.accessibility.AccessibleRole.PAGE_TAB }
                    .accessibleStateSet.let { it.contains(AccessibleState.SELECTED) || it.contains(AccessibleState.CHECKED) }
            }.getOrDefault(false)
        } }
        fun detailEntry(label: String) {
            fun visibleEntry(): Boolean {
                var found = false
                await("complete actual detail pane after original modal retirement") { edt {
                    currentSource()
                    val pane = runCatching { detailPaneScope() }.getOrNull() ?: return@edt false
                    found = descendants(pane).any { hasLabel(it, label) && visible(it) }
                    true
                } }
                return found
            }
            for (attempt in 0 until 18) {
                if (visibleEntry()) break
                await("complete actual detail pane before its scroll input") { edt {
                    currentSource()
                    if (runCatching { detailPaneScope() }.isFailure) return@edt false
                    ownedWheel(actualComposeInput(), 1, false)
                    true
                } }
                Thread.sleep(120)
            }
            check(visibleEntry()) { "Original detail entry is not visible: $label" }
            clickFeatureItem(edt { window() }, label)
        }
        fun closeNativeDialog(surface: javax.swing.JDialog) {
            edt {
                currentSource(); check(surface.isShowing && ownedWindow(surface))
                surface.dispatchEvent(java.awt.event.WindowEvent(surface, java.awt.event.WindowEvent.WINDOW_CLOSING))
            }
            await("owned original dialog title-bar close") { !surface.isShowing && !surface.isDisplayable }
            currentSource()
        }
        detailEntry("AI 总结")
        await("original AI summary shown in native dialog") { dialog("AI 总结")?.let { surface -> edt {
            val nodes = descendants(surface.accessibleContext)
            listOf("本地章节", "本地合成总结").all { label -> nodes.any { hasLabel(it, label) && visible(it, surface) } }
        } } == true }
        val summary = requireNotNull(dialog("AI 总结"))
        edt { captureOwnedExtraSurface("176-original-ai-summary", summary) }
        val priorSeek = actualPlayer.state.value.seekCompletedId
        clickFeatureItem(summary, "本地章节")
        await("original AI timestamp is acknowledged by the same native source") {
            currentSource()
            actualPlayer.state.value.let { it.nativePaused == true && it.seekCompletedId > priorSeek &&
                kotlin.math.abs(it.positionSeconds - 20.0) < .6 &&
                kotlin.math.abs((it.seekCompletedPositionSeconds ?: -100.0) - 20.0) < .6 }
        }
        record("176-original-ai-summary", mapOf("actualOriginalSummary" to JsonPrimitive("本地合成总结"),
            "actualTimestampControlUsed" to JsonPrimitive("本地章节"), "nativeSeekSeconds" to JsonPrimitive(20.0),
            "previousSeekId" to JsonPrimitive(priorSeek), "nativeSeekCompletedId" to JsonPrimitive(actualPlayer.state.value.seekCompletedId),
            "fullOriginalPublicationStillOwned" to JsonPrimitive(true)))
        closeNativeDialog(summary)
        detailEntry("视频笔记")
        await("original guest note list shown in native dialog") { dialog("视频笔记")?.let { surface -> edt {
            descendants(surface.accessibleContext).any { hasLabel(it, "登录后开始记笔记") && visible(it, surface) }
        } } == true }
        val notes = requireNotNull(dialog("视频笔记"))
        edt {
            val nodes = descendants(notes.accessibleContext)
            check(nodes.any { hasLabel(it, "登录后开始记笔记") && visible(it, notes) &&
                !it.accessibleStateSet.contains(AccessibleState.ENABLED) })
            captureOwnedExtraSurface("177-original-video-notes-guest", notes)
        }
        record("177-original-video-notes-guest", mapOf(
            "actualOriginalLoginAction" to JsonPrimitive("登录后开始记笔记"),
            "originalGuestEditorDisabled" to JsonPrimitive(true), "fullOriginalPublicationStillOwned" to JsonPrimitive(true),
            "realAccountUsed" to JsonPrimitive(false), "remoteNoteSavedOrDeleted" to JsonPrimitive(false)))
        closeNativeDialog(notes)
        click("关闭详情")
        await("interaction details close restores compact Main controls") { edt {
            all().none { hasLabel(it, "关闭详情") && visible(it) } && runCatching { videoScope() }.isSuccess
        } }
        click("播放")
        await("original Play after AI and notes") { playing() }
        settledMainInputLayers("original-interactions-after")
        await("original interaction windows restore Main input layers") { edt {
            val now = actualMainSceneLayers()
            currentSource()
            now.size == layers.size && now.all { layer -> layers.any { it === layer } }
        } }
        clockAndCapture("178-original-interactions-resumed")
    }

    private fun exercisePictureInPicture() {
        sameNative(); check(playing())
        val (assembly, publication) = actualHotOwner()
        val initial = actualPlayer.state.value
        var expectedMuted = initial.muted
        fun samePublication() {
            check(actualPlayer.ownsSourceSnapshot(accepted) && assembly.owns() && assembly.native.isCurrent(publication)) {
                "PiP changed the full original accepted source"
            }
            val state = actualPlayer.state.value
            check(state.volume == initial.volume && state.muted == expectedMuted && state.speed == initial.speed)
        }
        fun pipWindow(): JFrame? = edt {
            current(); samePublication()
            (SwingUtilities.getWindowAncestor(actualCanvas) as? JFrame)?.takeIf {
                it !== window() && it.isShowing && it.isAlwaysOnTop && actualCanvas.isShowing && actualCanvas.isDisplayable
            }
        }
        fun pipReady(paused: Boolean): Boolean {
            if (pipWindow() == null) return false
            val state = actualPlayer.state.value
            check(state.error == null)
            return state.ready && !state.loading && !state.ended && state.firstVideoFrameReady &&
                state.videoCodec != null && state.audioCodec != null && state.nativePaused == paused
        }
        fun awaitNativeMute(muted: Boolean) {
            check(!SwingUtilities.isEventDispatchThread())
            await("actual native mute acknowledgement: $muted") {
                // Queue the read behind the actual button/key command. The UI's
                // optimistic intent alone does not prove mpv applied the mute.
                val native = runBlocking { actualPlayer.captureNativeAudioDiagnostic() }
                    ?: return@await false
                check(native.sourceVersion == accepted.sourceVersion &&
                    native.activeSourceVersion == accepted.sourceVersion &&
                    native.playbackRevision == native.activePlaybackRevision && native.fileLoaded)
                val mute = native.properties.getValue("mute")
                val volume = native.properties.getValue("volume")
                mute.nativeCode >= 0 && mute.value == (if (muted) "yes" else "no") &&
                    volume.nativeCode >= 0 && volume.value?.toDoubleOrNull() == 0.0 &&
                    actualPlayer.state.value.muted == muted
            }
            samePublication()
        }
        fun captureNativeWindow(id: String, floating: Boolean = true): Pair<Int, Int> {
            val bounds = edt {
                val nativeWindow = if (floating) requireNotNull(pipWindow()) else window().also { sameNative() }
                samePublication()
                check(nativeComponents(nativeWindow).filterIsInstance<Canvas>().filter { canvas ->
                    canvas.isShowing && canvas.width > 100 && canvas.height > 80 &&
                        SwingUtilities.getWindowAncestor(canvas) === nativeWindow &&
                        canvas.javaClass.declaredFields.any { it.type == MpvPlayer::class.java }
                }.single() === actualCanvas)
                if (floating) check(nativeComponents(window()).none { it === actualCanvas })
                val pane = (nativeWindow as javax.swing.RootPaneContainer).contentPane
                val client = Rectangle(pane.locationOnScreen, pane.size)
                val surface = Rectangle(actualCanvas.locationOnScreen, actualCanvas.size)
                check(surface.width > 100 && surface.height > 80 && client.contains(surface))
                check(nativeWindow.graphicsConfiguration.bounds.contains(surface))
                surface
            }
            // Capture only this fixture's actual Canvas rectangle, after proving
            // its expected current window and retained player identity. Decoded pixels alone do not pass.
            val image = java.awt.Robot().createScreenCapture(bounds)
            ImageIO.write(image, "png", report.resolve("$id-screen.png").toFile())
            var cyan = 0; var pink = 0
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val color = java.awt.Color(image.getRGB(x, y))
                if (color.blue > 180 && color.green > 135 && color.red < 135) cyan++
                // RTX HDR can lift the synthetic pink from SDR (250,106,151)
                // to (255,204,248) in the desktop capture. Its red/blue chroma
                // still separates it from black, gray, white and the cyan disk.
                if (color.red > 180 && color.blue > 100 && color.red > color.green + 25 &&
                    color.blue > color.green + 20) pink++
            }
            check(cyan >= 100 && pink >= 100) { "Actual native screen $id did not show fixture video (cyan=$cyan,pink=$pink)" }
            return cyan to pink
        }
        fun captureReturn(id: String) {
            val colors = captureNativeWindow(id, floating = false)
            record(id, mapOf("sameAcceptedSourceVersion" to JsonPrimitive(accepted.sourceVersion),
                "fullOriginalPublicationStillOwned" to JsonPrimitive(true), "sameActualCanvasInMain" to JsonPrimitive(true),
                "actualScreenCyanPixels" to JsonPrimitive(colors.first), "actualScreenPinkPixels" to JsonPrimitive(colors.second)))
        }
        fun pressActualPipButton(tooltip: String) = edt {
            val pip = requireNotNull(pipWindow())
            val button = nativeComponents(pip).filterIsInstance<javax.swing.JButton>()
                .single { it.toolTipText == tooltip }
            check(button.isShowing && button.isEnabled && SwingUtilities.getWindowAncestor(button) === pip)
            val now = System.currentTimeMillis()
            val x = button.width / 2; val y = button.height / 2
            button.dispatchEvent(MouseEvent(button, MouseEvent.MOUSE_ENTERED, now, 0, x, y, 0, false))
            button.dispatchEvent(MouseEvent(button, MouseEvent.MOUSE_PRESSED, now + 1, InputEvent.BUTTON1_DOWN_MASK,
                x, y, 1, false, MouseEvent.BUTTON1))
            button.dispatchEvent(MouseEvent(button, MouseEvent.MOUSE_RELEASED, now + 2, 0,
                x, y, 1, false, MouseEvent.BUTTON1))
        }
        val before = actualPlayer.state.value.positionSeconds
        check(before > 5.0)
        click("浮窗")
        await("same actual Canvas and complete source playing in independent PiP") { pipReady(false) }
        val first = actualPlayer.state.value.positionSeconds
        check(first >= before - 1.0) { "PiP replayed the route's original start position" }
        Thread.sleep(1500)
        check(pipReady(false)); samePublication()
        val after = actualPlayer.state.value.positionSeconds
        check(after > first + .5)
        val colors = captureNativeWindow("170-pip-playing")
        record("170-pip-playing", mapOf("sameAcceptedSourceVersion" to JsonPrimitive(accepted.sourceVersion),
            "fullOriginalPublicationStillOwned" to JsonPrimitive(true), "sameActualCanvas" to JsonPrimitive(true),
            "independentNativeWindow" to JsonPrimitive(true), "actualOpenControlUsed" to JsonPrimitive("浮窗"),
            "clockBefore" to JsonPrimitive(before), "clockFirstPip" to JsonPrimitive(first), "clockAfter" to JsonPrimitive(after),
            "actualScreenCyanPixels" to JsonPrimitive(colors.first), "actualScreenPinkPixels" to JsonPrimitive(colors.second),
            "volumeMuteSpeedPreserved" to JsonPrimitive(true)))
        pressActualPipButton("返回主播放器")
        await("actual PiP Return restores same Main Canvas and original source") {
            edt { runCatching { sameNative(); samePublication(); playing() }.getOrDefault(false) }
        }
        check(actualPlayer.state.value.positionSeconds >= after - 1.0)
        clockAndCapture("171-pip-return-playing")
        captureReturn("171-pip-return-visible")

        click("暂停")
        await("original main Pause has native readback before PiP") { actualPlayer.state.value.nativePaused == true }
        val pausedPosition = actualPlayer.state.value.positionSeconds
        click("浮窗")
        await("paused original source in actual PiP") { pipReady(true) }
        Thread.sleep(800)
        check(pipReady(true) && kotlin.math.abs(actualPlayer.state.value.positionSeconds - pausedPosition) < .5)
        check(initial.volume == 0.0 && initial.muted)
        pressActualPipButton("静音/取消静音")
        expectedMuted = false
        await("actual PiP mute button changes runtime intent while volume stays zero") {
            actualPlayer.state.value.let { !it.muted && it.volume == 0.0 && it.nativePaused == true }
        }
        awaitNativeMute(false)
        val pausedColors = captureNativeWindow("172-pip-paused")
        record("172-pip-paused", mapOf("sameAcceptedSourceVersion" to JsonPrimitive(accepted.sourceVersion),
            "fullOriginalPublicationStillOwned" to JsonPrimitive(true), "sameActualCanvas" to JsonPrimitive(true),
            "pausedPosition" to JsonPrimitive(pausedPosition), "nativePausePreserved" to JsonPrimitive(true),
            "runtimeMuteChangedThroughActualPipControl" to JsonPrimitive(true), "runtimeMuted" to JsonPrimitive(false),
            "nativeMuteAcknowledged" to JsonPrimitive(true),
            "actualScreenCyanPixels" to JsonPrimitive(pausedColors.first), "actualScreenPinkPixels" to JsonPrimitive(pausedColors.second),
            "volumeMuteSpeedPreserved" to JsonPrimitive(true)))
        pressActualPipButton("返回主播放器")
        await("paused same source restored to Main through actual PiP Return") { edt {
            runCatching { sameNative(); samePublication()
                val state = actualPlayer.state.value
                state.ready && !state.loading && state.nativePaused == true && state.firstVideoFrameReady
            }.getOrDefault(false)
        } }
        Thread.sleep(600)
        check(kotlin.math.abs(actualPlayer.state.value.positionSeconds - pausedPosition) < .5)
        samePublication()
        check(!actualPlayer.state.value.muted && actualPlayer.state.value.volume == 0.0)
        awaitNativeMute(false)
        record("172-pip-runtime-mute-return", mapOf("sameAcceptedSourceVersion" to JsonPrimitive(accepted.sourceVersion),
            "fullOriginalPublicationStillOwned" to JsonPrimitive(true), "runtimeMuted" to JsonPrimitive(false),
            "nativeMuteAcknowledged" to JsonPrimitive(true),
            "originalLoadMuteNotReapplied" to JsonPrimitive(true), "nativePausePreserved" to JsonPrimitive(true)))
        ownedKey(actualPlayer.surface, java.awt.event.KeyEvent.VK_M)
        expectedMuted = true
        await("Main native keyboard restores private silent mute intent") { actualPlayer.state.value.muted }
        awaitNativeMute(true)
        click("播放")
        await("original main Play remains usable after both PiP round trips") { playing() }
        clockAndCapture("173-pip-paused-return-playing")
        captureReturn("173-pip-paused-return-visible")
        samePublication()
    }

    /** Opt-in actual Main input proof; no UI timer/state/native/source injection. */
    private fun exerciseFullscreenIdleChrome() {
        val entry = edt { current(); routes.currentKey }
        val mainBounds = edt { Rectangle(window().bounds) }
        fun ownedFullscreen() {
            sameNative()
            check(routes.currentKey === entry && window().bounds == mainBounds &&
                (window() as ComposeWindow).placement == WindowPlacement.Fullscreen)
        }
        fun completeChrome(): Boolean = edt {
            ownedFullscreen()
            val scope = runCatching { videoScope("播放进度") }.getOrNull() ?: return@edt false
            val nodes = descendants(scope)
            listOf("返回", "退出全屏", "更多播放操作").all { label ->
                nodes.count { hasLabel(it, label) && visible(it) &&
                    it.accessibleStateSet.contains(AccessibleState.ENABLED) &&
                    (it.accessibleAction?.accessibleActionCount ?: 0) == 1 } == 1
            }
        }
        fun moveOnCanvas(focus: Boolean = false) = edt {
            ownedFullscreen()
            val now = System.currentTimeMillis()
            // A real crossing leaves the prior Compose button before entering
            // the retained heavyweight peer. Deliver both to these owned inputs.
            val compose = actualComposeInput()
            compose.dispatchEvent(MouseEvent(compose, MouseEvent.MOUSE_EXITED, now, 0, -1, -1, 0, false))
            val x = actualCanvas.width / 2; val y = actualCanvas.height / 2
            actualCanvas.dispatchEvent(MouseEvent(actualCanvas, MouseEvent.MOUSE_MOVED, now + 1, 0, x, y, 0, false))
            if (focus) {
                actualCanvas.dispatchEvent(MouseEvent(actualCanvas, MouseEvent.MOUSE_PRESSED, now + 2,
                    InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1))
                actualCanvas.dispatchEvent(MouseEvent(actualCanvas, MouseEvent.MOUSE_RELEASED, now + 3,
                    0, x, y, 1, false, MouseEvent.BUTTON1))
            }
        }
        fun bounds() = edt { ownedFullscreen(); Rectangle(actualCanvas.locationOnScreen, actualCanvas.size) }
        fun capture(id: String, properties: Map<String, JsonElement>) {
            edt { ownedFullscreen() }
            actions.capture(id, edt { current() })
            edt { ownedFullscreen() }
            val captureMain = edt { window() as ComposeWindow }
            val client = edt {
                ownedFullscreen(); check(captureMain === window() && captureMain.isShowing && captureMain.isDisplayable)
                Rectangle(captureMain.contentPane.locationOnScreen, captureMain.contentPane.size).also {
                    check(it.width > 0 && it.height > 0)
                }
            }
            val canvas = bounds()
            fun sameCaptureOwner() = edt {
                ownedFullscreen()
                check(captureMain === window() && captureMain.isShowing && captureMain.isDisplayable &&
                    Rectangle(captureMain.contentPane.locationOnScreen, captureMain.contentPane.size) == client && bounds() == canvas)
            }
            sameCaptureOwner()
            // Read-only physical capture; no mouse/key input or pixel PASS is inferred.
            val screen = java.awt.Robot().createScreenCapture(client)
            sameCaptureOwner()
            check(screen.width == client.width && screen.height == client.height)
            check(ImageIO.write(screen, "png", report.resolve("$id-screen.png").toFile()))
            sameCaptureOwner()
            record(id, properties + mapOf("sameAcceptedSourceVersion" to JsonPrimitive(accepted.sourceVersion),
                "fullImmutableSourceStillOwned" to JsonPrimitive(true), "sameActualCanvasRetained" to JsonPrimitive(true),
                "nativeState" to safeState(), "physicalVideoPixelsIndependentlyChecked" to JsonPrimitive(false),
                "physicalScreenHumanReviewRequired" to JsonPrimitive(true), "screenCaptureFile" to JsonPrimitive("$id-screen.png"),
                "screenCaptureClientBounds" to buildJsonObject {
                    put("x", client.x); put("y", client.y); put("width", client.width); put("height", client.height)
                }))
        }
        check(playing() && actualPlayer.state.value.durationSeconds - actualPlayer.state.value.positionSeconds > 12.0) {
            "Fullscreen idle proof requires a playing fixture with more than 12 seconds remaining"
        }
        moveOnCanvas(focus = true)
        awaitFocus(actualPlayer.surface)
        await("complete fullscreen chrome after actual retained-Canvas input") { completeChrome() }
        val shown = bounds()
        val started = System.nanoTime()
        val beforePosition = actualPlayer.state.value.positionSeconds
        var hidden: Rectangle? = null
        await("after at least four seconds of observed idle both real chrome rows are hidden on the same Canvas/source") { edt {
            ownedFullscreen()
            check(playing())
            val area = Rectangle(actualCanvas.locationOnScreen, actualCanvas.size)
            val anchorsGone = listOf("返回", "退出全屏", "播放进度", "更多播放操作").none { label ->
                all().any { hasLabel(it, label) && visible(it) }
            }
            if (anchorsGone && area.y < shown.y && area.y + area.height > shown.y + shown.height &&
                area.x == shown.x && area.width == shown.width &&
                System.nanoTime() - started >= 4_000_000_000L && actualPlayer.state.value.positionSeconds > beforePosition + .5) {
                hidden = area; true
            } else false
        } }
        capture("121-fullscreen-idle-hidden", mapOf("idleMillis" to JsonPrimitive((System.nanoTime() - started) / 1_000_000L),
            "clockBefore" to JsonPrimitive(beforePosition), "clockAfter" to JsonPrimitive(actualPlayer.state.value.positionSeconds),
            "shownCanvasHeight" to JsonPrimitive(shown.height), "hiddenCanvasHeight" to JsonPrimitive(requireNotNull(hidden).height),
            "topAndBottomControlsHidden" to JsonPrimitive(true)))
        moveOnCanvas()
        await("actual retained-Canvas mouse move restores complete fullscreen controls and original viewport") {
            completeChrome() && bounds() == shown && playing()
        }
        capture("122-fullscreen-mouse-restored", mapOf("inputMechanism" to JsonPrimitive("OWNED_ACTUAL_CANVAS_MOUSE_MOVED"),
            "topAndBottomControlsRestored" to JsonPrimitive(true)))
        click("暂停")
        await("actual fullscreen Pause is acknowledged by the original native player") { sameNative(); actualPlayer.state.value.nativePaused == true }
        moveOnCanvas(focus = true)
        awaitFocus(actualPlayer.surface)
        await("complete fullscreen chrome has settled while native pause remains acknowledged") {
            completeChrome() && actualPlayer.state.value.nativePaused == true
        }
        val pausedAt = actualPlayer.state.value.positionSeconds
        val pausedStart = System.nanoTime()
        await("paused fullscreen retains complete controls beyond its idle delay") {
            check(completeChrome()) { "Original fullscreen chrome disappeared while native pause was acknowledged" }
            val state = actualPlayer.state.value
            check(state.nativePaused == true && kotlin.math.abs(state.positionSeconds - pausedAt) < .15)
            System.nanoTime() - pausedStart >= 4_500_000_000L
        }
        capture("123-fullscreen-paused-hold", mapOf("nativePauseAcknowledged" to JsonPrimitive(true),
            "controlsStayedVisible" to JsonPrimitive(true), "menuHoldExecuted" to JsonPrimitive(false)))
        click("播放")
        await("original fullscreen Play resumes the same native source before normal fullscreen exit") { sameNative(); playing() }
    }

    private fun exercise(replay: Boolean, localReplay: WindowsVideoLocalReplay?) {
        val initial = videoFrame()
        videoKey = initial.key as BiliPaiNavKey.VideoDetail
        noStartupMobilePrompts()
        acquireActualNativePlayer()
        await("actual source loaded") { actualPlayer.currentSourceSnapshot() != null && actualPlayer.state.value.firstVideoFrameReady }
        if (actualPlayer.state.value.nativePaused == true) {
            click("播放")
            await("original Play control starts actual source") { actualPlayer.state.value.nativePaused == false }
        }
        accepted = requireNotNull(actualPlayer.currentSourceSnapshot())
        check(accepted.sourceVersion > 0 && accepted.source.nativePublication != null)
        check(edt { (routes.currentKey as BiliPaiNavKey.VideoDetail).bvid } == video)
        val originalBounds = edt { Rectangle(window().bounds) }
        val initialPlacement = edt { (window() as ComposeWindow).placement }
        check(initialPlacement == WindowPlacement.Floating)
        clockAndCapture("110-ordinary-playing")
        if (System.getProperty("bilipai.validation.videoDynamicShareInput") == "true") {
            check(replay) { "Video share proof requires the explicit synthetic protocol/session replay" }
            exerciseVideoDynamicShare(requireNotNull(localReplay))
        } else if (System.getProperty("bilipai.validation.composerInput") == "true") {
            check(replay) { "Composer proof requires private synthetic API/session and loopback media" }
            exerciseCommentComposer(requireNotNull(localReplay))
            if (System.getProperty("bilipai.validation.brandFeedbackPlacementInput") == "true")
                exerciseBrandFeedbackPlacement(localReplay)
        } else if (System.getProperty("bilipai.validation.commentSearchInput") == "true") {
            check(replay) { "Comment search proof requires the isolated guest API/loopback replay" }
            exerciseCommentSearch(requireNotNull(localReplay))
        } else {
        exercisePlayerMenu()
        if (System.getProperty("bilipai.validation.featureInput") == "true") exerciseChapterControls()
        if (System.getProperty("bilipai.validation.nvidiaInput") == "true") exerciseMainNvidiaControls()
        val beforeFullscreen = edt { current().serial }
        click("全屏")
        await("actual ComposeWindow fullscreen placement") { edt { (window() as ComposeWindow).placement == WindowPlacement.Fullscreen } }
        videoFrame(beforeFullscreen); Thread.sleep(1200)
        clockAndCapture("120-fullscreen-playing")
        if (System.getProperty("bilipai.validation.fullscreenIdleInput") == "true") exerciseFullscreenIdleChrome()
        click("退出全屏")
        await("actual ComposeWindow floating placement restored") { edt { (window() as ComposeWindow).placement == WindowPlacement.Floating } }
        Thread.sleep(1200)
        clockAndCapture("130-fullscreen-exit-playing")
        val beforeResize = edt { current().serial }
        val resized = edt {
            current()
            val main = window()
            Rectangle(originalBounds.x, originalBounds.y, maxOf(main.minimumSize.width, originalBounds.width - 120),
                maxOf(main.minimumSize.height, originalBounds.height - 100)).also { target ->
                check(target.width != originalBounds.width || target.height != originalBounds.height)
                main.bounds = target; main.validate()
            }
        }
        await("actual owned native resize applied") { edt { window().bounds == resized } }
        videoFrame(beforeResize); Thread.sleep(1200)
        clockAndCapture("140-resized-playing")
        val beforeRestore = edt { current().serial }
        edt { current(); window().bounds = originalBounds; window().validate() }
        await("actual original window bounds restored") { edt { window().bounds == originalBounds } }
        videoFrame(beforeRestore); Thread.sleep(1200)
        clockAndCapture("150-restored-playing")
        if (System.getProperty("bilipai.validation.scaleInput") == "true") exerciseOwnedScaleAndKeyboard()
        if (System.getProperty("bilipai.validation.pipInput") == "true") {
            check(replay) { "PiP proof requires private local API and media" }
            exercisePictureInPicture()
        }
        if (System.getProperty("bilipai.validation.originalInteractionInput") == "true") {
            check(replay) { "Original interaction proof requires the private guest replay" }
            exerciseOriginalVideoInteractions()
        }
        if (System.getProperty("bilipai.validation.hotInput") == "true") {
            check(replay) { "Hot UI proof requires the private actual Main local replay" }
            exerciseHotDanmaku(requireNotNull(localReplay))
        }
        if (System.getProperty("bilipai.validation.bgmInput") == "true") {
            check(replay && System.getProperty("bilipai.validation.collectionInput") == "true")
            exerciseSingleBgm(requireNotNull(localReplay))
        }
        if (System.getProperty("bilipai.validation.collectionInput") == "true") {
            check(replay) { "Collection/queue layout proof requires private synthetic metadata" }
            exerciseCollectionAndQueue()
            if (System.getProperty("bilipai.validation.metadataInput") == "true") {
                click("详情")
                await("details reopened on the second accepted native part") { edt {
                    runCatching { detailPaneScope() }.isSuccess
                } }
                edt {
                    val tab = descendants(detailPaneScope()).single { it.accessibleName == "简介与分P" &&
                        it.accessibleRole == javax.accessibility.AccessibleRole.PAGE_TAB && visible(it) }
                    clickOwnedComposeMouse(window(), tab)
                }
                exerciseVideoMetadata("159")
                click("关闭详情")
                await("metadata details retired before real Back") { edt {
                    all().none { it.accessibleName == "关闭详情" && visible(it) }
                } }
            }
        }
        // Preserve .8's P2 metadata/source-version proof above, then perform the
        // optional original BGM P2-to-P1 return as its own source-changing phase.
        if (System.getProperty("bilipai.validation.bgmInput") == "true") {
            exerciseMultipleBgmAndReturn(requireNotNull(localReplay))
        }
        }
        val beforeBack = edt { current().serial }
        click("返回")
        if (replay) returnReplaySearchToHome(beforeBack)
        await("actual original Back draws Home with desktop sidebar") { edt {
            val frame = pendingCurrent() ?: return@edt false
            frame.serial > beforeBack && frame.key == BiliPaiNavKey.Home && frame.pagerHosted &&
                routes.currentKey == BiliPaiNavKey.MainHost && runCatching { desktopSidebarScope() }.isSuccess
        } }
        Thread.sleep(1200)
        edt { check(current().key == BiliPaiNavKey.Home && routes.stack.toList() == listOf(BiliPaiNavKey.MainHost)) }
        actions.capture("160-original-back-home", edt { current() })
        record("160-original-back-home", mapOf("physicalStack" to JsonArray(listOf(JsonPrimitive("MainHost"))),
            "originalVideoControlUsed" to JsonPrimitive(true), "fixtureBackCommandUsed" to JsonPrimitive(false)))
    }
    @JvmStatic fun main(args: Array<String>) {
        require(args.size in 4..5 && Regex("BV[0-9A-Za-z]{10}").matches(args[3]))
        require(args.size == 4 || args[4] == "replay")
        val replayMode = args.size == 5
        video = args[3]; report = Path.of(args[0]).toRealPath()
        Files.list(report).use { require(it.findAny().isEmpty) }
        val health = Path.of(args[1]).toAbsolutePath().normalize()
        require(!Files.exists(health, NOFOLLOW_LINKS))
        actions = OriginalOnboardingUiActions(latest::get, report, args[2])
        actions.requireFreshGuestStartup()
        val prefsPath = actions.local.resolve("BiliPaiWindows/player-settings.json")
        require(!Files.exists(prefsPath, NOFOLLOW_LINKS))
        // Explicit test precondition in the fresh temporary profile, using the actual production Store.
        // This is not settings-UI acceptance and does not change any real user's volume/mute.
        PlayerPreferencesStore().save(PlayerPreferences(volume = 0.0, muted = true))
        check(PlayerPreferencesStore().read() == PlayerPreferences(volume = 0.0, muted = true).normalized())
        check(System.getProperty("bilipai.validation.brandFeedbackPlacementInput") != "true" ||
            (replayMode && System.getProperty("bilipai.validation.composerInput") == "true")) {
            "Feedback placement must use the real Main isolated composer synthetic-session replay"
        }
        check(System.getProperty("bilipai.validation.videoDynamicShareInput") != "true" ||
            (replayMode && System.getProperty("bilipai.validation.composerInput") == "true" &&
                System.getProperty("bilipai.validation.brandFeedbackPlacementInput") != "true"))
        val replay = if (replayMode) WindowsVideoLocalReplay.create(report, video) else null
        if (replay != null) Runtime.getRuntime().addShutdownHook(Thread({ replay.close() }, "Owned local media cleanup"))
        DesktopOriginalRootValidationTap.install { latest.set(it) }.use {
            val worker = Thread({
                try {
                    await("first actual drawn Root") { latest.get() != null }
                    val first = requireNotNull(latest.get())
                    owner = first.handle; routes = first.routes
                    ownedWindowIdentity = edt {
                        val main = window() as ComposeWindow
                        runtimeMainWindow = main
                        System.identityHashCode(main)
                    }
                    check(first.key != BiliPaiNavKey.Onboarding) { "Windows startup still mounted the mobile agreement gate" }
                    actions.awaitActualHealth(health)
                    writeActualMainRuntimeEvidence("start", "FIRST_DRAWN_ROOT_AND_ACTUAL_HEALTH")
                    if (System.getProperty("bilipai.validation.commentSearchInput") == "true" ||
                        System.getProperty("bilipai.validation.composerInput") == "true" ||
                        System.getProperty("bilipai.validation.fullscreenIdleInput") == "true") {
                        check(replay != null)
                        require(!(System.getProperty("bilipai.validation.commentSearchInput") == "true" &&
                            System.getProperty("bilipai.validation.composerInput") == "true"))
                        val excluded = if (System.getProperty("bilipai.validation.composerInput") == "true")
                            listOf("nvidiaInput", "scaleInput", "featureInput", "hotInput", "collectionInput",
                                "metadataInput", "bgmInput", "pipInput", "originalInteractionInput")
                            else listOf("nvidiaInput", "scaleInput", "featureInput", "hotInput")
                        for (mode in excluded)
                            require(System.getProperty("bilipai.validation.$mode") != "true") { "Run comment search as its bounded independent branch" }
                        boundCommentSearchWindow()
                    }
                    if (System.getProperty("bilipai.validation.nvidiaInput") == "true") {
                        check(replay != null) { "NVIDIA UI proof requires the private local replay" }
                        exerciseDefaultGlassAppearance()
                        exerciseNoSourceNvidiaSettings()
                    }
                    if (replay != null) enterVideoThroughActualSearch(replay)
                    exercise(replayMode, replay)
                    replay?.writeReceipt()
                    val receipt = buildJsonObject {
                        put("schema", 1); put("actualMainInvocations", 1); put("actualMainReturned", false)
                        put("observationPhase", "BEFORE_REQUESTED_ACTUAL_EXIT"); put("allPreExitAssertionsPassed", true)
                        put("externalExitAndFinalPinsRequired", true); put("defaultRenderer", "DIRECT3D")
                        put("sameLiveRootAndWindow", true); put("requestedBvid", video)
                        put("guestRealApi", !replayMode); put("apiReplayInjected", replayMode); put("loopbackMediaInjected", replayMode)
                        put("validationScope", if (replayMode) "LOCAL_API_SHAPE_REAL_LOOPBACK_MEDIA_LAYOUT_ONLY" else "GUEST_LIVE_API")
                        put("nvidiaUiProofRequested", System.getProperty("bilipai.validation.nvidiaInput") == "true")
                        put("nvidiaUiProofCompleted", System.getProperty("bilipai.validation.nvidiaInput") == "true")
                        put("interactionProofRequested", System.getProperty("bilipai.validation.scaleInput") == "true")
                        put("interactionProofCompleted", System.getProperty("bilipai.validation.scaleInput") == "true")
                        put("featureInputProofCompleted", System.getProperty("bilipai.validation.featureInput") == "true")
                        put("hotInputProofRequested", System.getProperty("bilipai.validation.hotInput") == "true")
                        put("hotInputProofCompleted", System.getProperty("bilipai.validation.hotInput") == "true")
                        put("hotLikeOrSendAccepted", false)
                        put("collectionInputProofRequested", System.getProperty("bilipai.validation.collectionInput") == "true")
                        put("collectionInputProofCompleted", System.getProperty("bilipai.validation.collectionInput") == "true")
                        put("queueIndexedSelectionAccepted", false)
                        put("videoMetadataProofCompleted", System.getProperty("bilipai.validation.metadataInput") == "true")
                        put("bgmInputProofRequested", System.getProperty("bilipai.validation.bgmInput") == "true")
                        put("bgmInputProofCompleted", System.getProperty("bilipai.validation.bgmInput") == "true")
                        put("bgmApiResponsesAreSynthetic", System.getProperty("bilipai.validation.bgmInput") == "true")
                        put("bgmAccountMutationAccepted", false); put("commentsSent", false)
                        put("pipInputProofRequested", System.getProperty("bilipai.validation.pipInput") == "true")
                        put("pipInputProofCompleted", System.getProperty("bilipai.validation.pipInput") == "true")
                        put("pipRapidCancellationAccepted", false)
                        put("commentSearchProofRequested", System.getProperty("bilipai.validation.commentSearchInput") == "true")
                        put("commentSearchInputProofCompleted", System.getProperty("bilipai.validation.commentSearchInput") == "true")
                        put("commentSearchReadResponsesAreSynthetic", System.getProperty("bilipai.validation.commentSearchInput") == "true")
                        put("commentSearchPhysicalTextHumanReviewRequired", System.getProperty("bilipai.validation.commentSearchInput") == "true")
                        put("composerInputProofRequested", System.getProperty("bilipai.validation.composerInput") == "true" && System.getProperty("bilipai.validation.videoDynamicShareInput") != "true")
                        put("composerInputProofCompleted", System.getProperty("bilipai.validation.composerInput") == "true" && System.getProperty("bilipai.validation.videoDynamicShareInput") != "true")
                        put("videoDynamicShareProofRequested", System.getProperty("bilipai.validation.videoDynamicShareInput") == "true")
                        put("videoDynamicShareProofCompleted", System.getProperty("bilipai.validation.videoDynamicShareInput") == "true")
                        put("videoDynamicSharePhysicalFramesRequireHumanReview", System.getProperty("bilipai.validation.videoDynamicShareInput") == "true")
                        put("brandFeedbackPlacementProofRequested", System.getProperty("bilipai.validation.brandFeedbackPlacementInput") == "true")
                        put("brandFeedbackPlacementProofCompleted", System.getProperty("bilipai.validation.brandFeedbackPlacementInput") == "true")
                        put("brandFeedbackPhysicalFramesRequireHumanReview", System.getProperty("bilipai.validation.brandFeedbackPlacementInput") == "true")
                        put("syntheticAccountSeededThroughActualSessionStore", System.getProperty("bilipai.validation.composerInput") == "true")
                        put("commentPublishingAccepted", false); put("imageUploadAccepted", false); put("loginUiAccepted", false)
                        put("ordinaryFullscreenResizeRegressionExecuted", System.getProperty("bilipai.validation.commentSearchInput") != "true" &&
                            System.getProperty("bilipai.validation.composerInput") != "true")
                        put("commentSearchFourKTested", false)
                        put("originalInteractionProofRequested", System.getProperty("bilipai.validation.originalInteractionInput") == "true")
                        put("originalInteractionProofCompleted", System.getProperty("bilipai.validation.originalInteractionInput") == "true")
                        put("originalLoggedInDanmakuSendAccepted", false)
                        put("originalSystemShareAccepted", false)
                        put("realAccountUsed", false); put("physicalStackWrittenByFixture", System.getProperty("bilipai.validation.nvidiaInput") == "true")
                        put("directPhysicalStackListMutation", false)
                        put("newNativeActorCreatedByFixture", false); put("newRootCreatedByFixture", false)
                        put("fixturePrivateSilentPreferenceSeed", true); put("fixtureVolume", 0); put("fixtureMuted", true)
                        put("allRoutesAccepted", false); put("newExeDeployed", false); put("exclusiveAudioAccepted", false)
                        put("cidNativeRequestIndependentlyReadBack", false)
                        put("acceptedFullSourceGuardedAcrossPresentationChanges", true)
                        put("observations", JsonArray(rows.toList()))
                    }
                    Files.writeString(report.resolve("observations.json"), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), receipt), CREATE_NEW, WRITE)
                    writeActualMainRuntimeEvidence("end", "BEFORE_REQUESTED_ACTUAL_EXIT")
                    completed.set(true)
                    actions.closeOwnedWindow(edt { current() })
                } catch (failure: Throwable) {
                    writeActualMainRuntimeEvidence("end", "FAILURE_HANDLER_BEFORE_OWNED_EXIT", failure)
                    failure.printStackTrace()
                    runCatching { replay?.writeFailureReceipt() }
                    runCatching {
                        val stacks = Thread.getAllStackTraces().entries.map { (thread, frames) -> buildJsonObject {
                            put("threadId", thread.threadId()); put("state", thread.state.name)
                            put("category", when { thread.name.startsWith("AWT") -> "AWT"
                                thread.name == "BiliPai-native-player" -> "MPV"
                                thread.name.startsWith("DefaultDispatcher") -> "Coroutine"
                                else -> "Other" })
                            put("frames", JsonArray(frames.take(40).map { JsonPrimitive(it.toString()) }))
                        } }
                        Files.writeString(report.resolve("failure-thread-stacks.json"), JsonArray(stacks).toString(), CREATE_NEW, WRITE)
                    }
                    runCatching { if (::actualPlayer.isInitialized) Files.writeString(report.resolve("failure-native-state.json"), safeState().toString(), CREATE_NEW, WRITE) }
                    runCatching { if (::actualPlayer.isInitialized) {
                        val source = requireNotNull(actualPlayer.currentSourceSnapshot())
                        val repository = edt { current(); requireNotNull(owner.messagePages.get()).repository }
                        val initial = source.source.nativePublication as? DesktopOriginalVideoInitialPublication
                        val requestJob = initial?.let { publication ->
                            DesktopOriginalVideoInitialPublication::class.java.getDeclaredField("requestJob")
                                .apply { check(trySetAccessible()) }.get(publication) as kotlinx.coroutines.Job
                        }
                        val admission = buildJsonObject {
                            put("sameNativeSourceStillOwned", actualPlayer.ownsSourceSnapshot(source))
                            put("rootOwned", owner.isActive() && routes.owns())
                            put("actualRepositoryEpoch", repository.sessionEpoch)
                            put("actualAuthorizationRevision", repository.playbackAuthorizationRevision.value)
                            put("sourceAuthorizationEpoch", source.source.authorizationReceipt?.accountEpoch?.let(::JsonPrimitive) ?: JsonNull)
                            put("sourceAuthorizationRevision", source.source.authorizationReceipt?.revision?.let(::JsonPrimitive) ?: JsonNull)
                            put("initialPublicationPresent", initial != null)
                            put("initialTransportCurrent", initial?.isTransportCurrent()?.let(::JsonPrimitive) ?: JsonNull)
                            if (initial != null) {
                                val type = DesktopOriginalVideoInitialPublication::class.java
                                val consumed = type.getDeclaredField("consumed").apply { check(trySetAccessible()) }
                                    .get(initial) as java.util.concurrent.atomic.AtomicBoolean
                                put("actualLoadCommandAcknowledged", consumed.get())
                                for (name in listOf("ownsAccepted", "isRequestCurrent")) {
                                    @Suppress("UNCHECKED_CAST")
                                    val predicate = type.getDeclaredField(name).apply { check(trySetAccessible()) }.get(initial) as () -> Boolean
                                    put(name, predicate())
                                }
                            }
                            put("requestJobCancelled", requestJob?.isCancelled?.let(::JsonPrimitive) ?: JsonNull)
                            put("requestJobCompleted", requestJob?.isCompleted?.let(::JsonPrimitive) ?: JsonNull)
                            put("requestJobType", requestJob?.javaClass?.name?.let(::JsonPrimitive) ?: JsonNull)
                            if (requestJob?.isCancelled == true) {
                                val cancellation = kotlinx.coroutines.Job::class.java.getMethod("getCancellationException")
                                    .invoke(requestJob) as java.util.concurrent.CancellationException
                                put("requestCancellationType", cancellation.javaClass.name)
                                put("requestCancellationCauseType", cancellation.cause?.javaClass?.name?.let(::JsonPrimitive) ?: JsonNull)
                                val fixedMessage = cancellation.message?.takeIf { it.startsWith("Original ") && it.length < 100 && !it.contains("://") }
                                put("requestCancellationLocalMessage", fixedMessage?.let(::JsonPrimitive) ?: JsonNull)
                                put("requestCancellationLocalFrames", JsonArray(cancellation.stackTrace.take(32).map { frame -> buildJsonObject {
                                    put("class", frame.className); put("method", frame.methodName); put("line", frame.lineNumber)
                                } }))
                            }
                            val playerType = com.bilipai.desktop.player.MpvPlayer::class.java
                            val playerLock = playerType.getDeclaredField("lock").apply { check(trySetAccessible()) }.get(actualPlayer)
                            synchronized(playerLock) {
                                val session = playerType.getDeclaredField("session").apply { check(trySetAccessible()) }.get(actualPlayer)
                                if (session != null) {
                                    for (name in listOf("activeSourceVersion", "activeRevision", "expectedEntry")) {
                                        val value = session.javaClass.getDeclaredField(name).apply { check(trySetAccessible()) }.get(session) as Number?
                                        put("nativeActor_$name", value?.toLong()?.let(::JsonPrimitive) ?: JsonNull)
                                    }
                                }
                            }
                            put("windowsAudioPhase", actualPlayer.windowsAudioOutput.value.phase.name)
                            put("windowsAudioSourceVersion", actualPlayer.windowsAudioOutput.value.sourceVersion)
                        }
                        Files.writeString(report.resolve("failure-source-admission.json"), admission.toString(), CREATE_NEW, WRITE)
                    } }
                    runCatching { writeFailureWindowGeometry("FAILURE_HANDLER_FALLBACK") }
                    runCatching { actions.capture("failure-owned-window", edt { current() }) }
                    runCatching {
                        Files.writeString(report.resolve("failure-main-input-layers.json"),
                            JsonObject(edt { mainInputLayerFacts() }).toString(), CREATE_NEW, WRITE)
                    }
                    runCatching { Files.writeString(report.resolve("failure-observations.json"), JsonArray(rows.toList()).toString(), CREATE_NEW, WRITE) }
                    kotlin.system.exitProcess(91)
                }
            }, "Actual Windows video Root observer")
            worker.isDaemon = true; worker.start()
            val mainArguments = if (replayMode) arrayOf("--update-health-file", health.toString(), "--update-health-token", args[2])
                else arrayOf("--video=$video", "--update-health-file", health.toString(), "--update-health-token", args[2])
            com.bilipai.desktop.main(mainArguments)
            check(completed.get()) { "Actual Main returned before Windows video assertions" }
        }
    }
}
