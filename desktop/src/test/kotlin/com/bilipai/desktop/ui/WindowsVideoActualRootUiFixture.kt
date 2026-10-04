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
    private fun visible(context: AccessibleContext): Boolean {
        val component = context.accessibleComponent ?: return false
        val origin = component.locationOnScreen ?: return false
        val size = component.size
        val main = window().contentPane
        val viewport = Rectangle(main.locationOnScreen.x, main.locationOnScreen.y, main.width, main.height)
        return context.accessibleStateSet.contains(AccessibleState.SHOWING) && size.width > 0 && size.height > 0 &&
            viewport.contains(Rectangle(origin.x, origin.y, size.width, size.height))
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
    private fun click(label: String) = edt {
        val scope = videoScope(label)
        val controls = descendants(scope).filter { hasLabel(it, label) && visible(it) &&
            it.accessibleStateSet.contains(AccessibleState.ENABLED) && (it.accessibleAction?.accessibleActionCount ?: 0) == 1 }
        check(controls.size == 1) { "Expected one actual visible '$label' in complete Windows video leaf, got ${controls.size}" }
        record("mouse-${rows.size}-$label", mapOf("label" to JsonPrimitive(label), "matches" to JsonPrimitive(controls.size),
            "inputMechanism" to JsonPrimitive("OWNED_COMPOSE_AWT_MOUSE_EVENT")))
        clickOwnedComposeMouse(window(), controls.single())
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
    private fun acquireActualNativePlayer() = edt {
        current(); videoScope()
        val candidates = nativeComponents(window()).filterIsInstance<Canvas>().filter { canvas ->
            canvas.isShowing && canvas.isDisplayable && canvas.width > 100 && canvas.height > 80 &&
                SwingUtilities.getWindowAncestor(canvas) === window() &&
                canvas.javaClass.declaredFields.count { it.type == MpvPlayer::class.java } == 1
        }
        check(candidates.size == 1) { "Expected exactly one actual ordinary native Canvas, got ${candidates.size}" }
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
    }
    private var replaySearchKey: BiliPaiNavKey? = null
    private fun scopeWithEditableSearch(): AccessibleContext {
        current(); check(routes.currentKey is BiliPaiNavKey.Search)
        val candidates = all().filter { scope ->
            val children = descendants(scope)
            children.count { it.accessibleEditableText != null && visible(it) } == 1 &&
                listOf("返回", "搜索").all { label -> children.count {
                    hasLabel(it, label) && visible(it) && (it.accessibleAction?.accessibleActionCount ?: 0) == 1
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
        val control = descendants(scope).filter { hasLabel(it, label) && visible(it) &&
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
        replay.install(repository) { owner.isActive() && routes.owns() && owner.route.get() === routes }
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

    private fun actualComposeInput(): Component {
        current()
        return nativeComponents(window()).filter { component -> component.isShowing && component.isDisplayable &&
            component.isEnabled && SwingUtilities.getWindowAncestor(component) === window() && component.keyListeners.any {
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
    private fun ownedKey(input: Component, keyCode: Int, modifiers: Int = 0, typed: Char? = null) = edt {
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
        record("owned-key-${rows.size}", mapOf("keyCode" to JsonPrimitive(keyCode), "modifiers" to JsonPrimitive(modifiers),
            "pressedConsumed" to JsonPrimitive(pressed.isConsumed), "sameActualFocusedWindow" to JsonPrimitive(true),
            "inputClass" to JsonPrimitive(input.javaClass.name), "mechanism" to JsonPrimitive("OWNED_AWT_KEY_EVENT")))
    }
    private fun ownedWheel(input: Component, rotation: Int, ctrl: Boolean, point: java.awt.Point? = null) = edt {
        current(); check(input.isShowing && input.isDisplayable && SwingUtilities.getWindowAncestor(input) === window())
        val location = point ?: java.awt.Point(input.width / 3, input.height - 40)
        check(input.contains(location))
        val event = java.awt.event.MouseWheelEvent(input, java.awt.event.MouseEvent.MOUSE_WHEEL,
            System.currentTimeMillis(), if (ctrl) java.awt.event.InputEvent.CTRL_DOWN_MASK else 0,
            location.x, location.y, 0, false, java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation)
        input.dispatchEvent(event)
        record("owned-wheel-${rows.size}", mapOf("rotation" to JsonPrimitive(rotation), "ctrl" to JsonPrimitive(ctrl),
            "consumed" to JsonPrimitive(event.isConsumed), "inputClass" to JsonPrimitive(input.javaClass.name),
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
    private fun actualEditor(): AccessibleContext {
        current(); videoScope("取消回复")
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
        val input = edt { actualComposeInput() }
        focused(input); awaitFocus(input)
        var before = edt { current().serial }
        ownedKey(input, java.awt.event.KeyEvent.VK_MINUS, java.awt.event.InputEvent.CTRL_DOWN_MASK)
        awaitScale(120, before)
        clockAndCapture("151-ctrl-minus-scale-120")
        before = edt { current().serial }
        ownedKey(input, java.awt.event.KeyEvent.VK_EQUALS, java.awt.event.InputEvent.CTRL_DOWN_MASK)
        awaitScale(125, before)
        before = edt { current().serial }
        ownedWheel(input, -1, true)
        awaitScale(130, before)
        clockAndCapture("152-ctrl-wheel-scale-130")
        before = edt { current().serial }
        ownedKey(input, java.awt.event.KeyEvent.VK_0, java.awt.event.InputEvent.CTRL_DOWN_MASK)
        awaitScale(125, before)

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

        // Scroll only the visible lower detail pane. Never force a VM, send/post or write selection fields.
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
            current(); editorValue() == "fixture" && actualEditor().accessibleStateSet.contains(AccessibleState.FOCUSED) &&
                java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner === editorInput &&
                editorInput.isFocusOwner
        } }
        edt { requireNotNull(actualEditor().accessibleEditableText).selectText(0, 0) }
        val beforeEditorSeek = actualPlayer.state.value.seekCompletedId
        val beforeEditorPosition = actualPlayer.state.value.positionSeconds
        ownedKey(editorInput, java.awt.event.KeyEvent.VK_RIGHT)
        await("original editor Right advances text caret") { edt { actualEditor().accessibleText?.caretPosition == 1 } }
        ownedKey(editorInput, java.awt.event.KeyEvent.VK_SPACE, typed = ' ')
        await("original editor receives actual typed space") { edt { editorValue() == "f ixture" } }
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
    }

    private fun exercise(replay: Boolean) {
        val initial = videoFrame()
        videoKey = initial.key as BiliPaiNavKey.VideoDetail
        noStartupMobilePrompts()
        await("actual complete Windows video controls and original native surface") { edt {
            val frame = pendingCurrent() ?: return@edt false
            frame.key is BiliPaiNavKey.VideoDetail && runCatching { videoScope() }.isSuccess &&
                nativeComponents(window()).filterIsInstance<Canvas>().any { it.isShowing && it.isDisplayable && it.width > 100 && it.height > 80 }
        } }
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
        val beforeFullscreen = edt { current().serial }
        click("全屏")
        await("actual ComposeWindow fullscreen placement") { edt { (window() as ComposeWindow).placement == WindowPlacement.Fullscreen } }
        videoFrame(beforeFullscreen); Thread.sleep(1200)
        clockAndCapture("120-fullscreen-playing")
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
        val replay = if (replayMode) WindowsVideoLocalReplay.create(report, video) else null
        if (replay != null) Runtime.getRuntime().addShutdownHook(Thread({ replay.close() }, "Owned local media cleanup"))
        DesktopOriginalRootValidationTap.install { latest.set(it) }.use {
            val worker = Thread({
                try {
                    await("first actual drawn Root") { latest.get() != null }
                    val first = requireNotNull(latest.get())
                    owner = first.handle; routes = first.routes
                    ownedWindowIdentity = edt { System.identityHashCode(window()) }
                    check(first.key != BiliPaiNavKey.Onboarding) { "Windows startup still mounted the mobile agreement gate" }
                    actions.awaitActualHealth(health)
                    if (replay != null) enterVideoThroughActualSearch(replay)
                    exercise(replayMode)
                    replay?.writeReceipt()
                    val receipt = buildJsonObject {
                        put("schema", 1); put("actualMainInvocations", 1); put("actualMainReturned", false)
                        put("observationPhase", "BEFORE_REQUESTED_ACTUAL_EXIT"); put("allPreExitAssertionsPassed", true)
                        put("externalExitAndFinalPinsRequired", true); put("defaultRenderer", "DIRECT3D")
                        put("sameLiveRootAndWindow", true); put("requestedBvid", video)
                        put("guestRealApi", !replayMode); put("apiReplayInjected", replayMode); put("loopbackMediaInjected", replayMode)
                        put("validationScope", if (replayMode) "LOCAL_API_SHAPE_REAL_LOOPBACK_MEDIA_LAYOUT_ONLY" else "GUEST_LIVE_API")
                        put("interactionProofRequested", System.getProperty("bilipai.validation.scaleInput") == "true")
                        put("interactionProofCompleted", System.getProperty("bilipai.validation.scaleInput") == "true")
                        put("realAccountUsed", false); put("physicalStackWrittenByFixture", false)
                        put("newNativeActorCreatedByFixture", false); put("newRootCreatedByFixture", false)
                        put("fixturePrivateSilentPreferenceSeed", true); put("fixtureVolume", 0); put("fixtureMuted", true)
                        put("allRoutesAccepted", false); put("newExeDeployed", false); put("exclusiveAudioAccepted", false)
                        put("cidNativeRequestIndependentlyReadBack", false)
                        put("acceptedFullSourceGuardedAcrossPresentationChanges", true)
                        put("observations", JsonArray(rows.toList()))
                    }
                    Files.writeString(report.resolve("observations.json"), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), receipt), CREATE_NEW, WRITE)
                    completed.set(true)
                    actions.closeOwnedWindow(edt { current() })
                } catch (failure: Throwable) {
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
                    runCatching { actions.capture("failure-owned-window", edt { current() }) }
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
