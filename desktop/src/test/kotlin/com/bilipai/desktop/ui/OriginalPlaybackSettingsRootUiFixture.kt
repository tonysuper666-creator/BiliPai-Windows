package com.bilipai.desktop.ui

import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.DesktopLibrary
import kotlinx.serialization.json.*
import java.awt.Component
import java.awt.Container
import java.awt.EventQueue
import java.awt.Window
import java.awt.Rectangle
import java.awt.event.MouseWheelEvent
import java.awt.event.MouseEvent
import java.awt.event.InputEvent
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.accessibility.AccessibleState
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities

import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.awt.event.KeyAdapter

/** Test-only original controls over unchanged Main; actual frame ownership is read-only. */
object OriginalPlaybackSettingsRootUiFixture {
    private val latest = AtomicReference<DesktopOriginalRootValidationTap.Frame?>()
    private val completed = AtomicBoolean(false)
    private val rows = CopyOnWriteArrayList<JsonObject>()
    private lateinit var report: Path
    private lateinit var actions: OriginalOnboardingUiActions
    private lateinit var owner: DesktopReadyOriginalRootHandle
    private lateinit var routes: DesktopOriginalRootRouteAssembly
    private var ownedWindowIdentity = 0
    private var diagnosticWindow: JFrame? = null
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

    private fun nodes(context: AccessibleContext?, result: MutableList<AccessibleContext>, depth: Int = 0) {
        if (context == null || depth > 80) return
        result.add(context)
        repeat(context.accessibleChildrenCount) { nodes(context.getAccessibleChild(it)?.accessibleContext, result, depth + 1) }
    }

    private fun descendants(context: AccessibleContext): List<AccessibleContext> = mutableListOf<AccessibleContext>().also { nodes(context, it) }

    private fun all(candidate: Window = window()) = descendants(candidate.accessibleContext)

    private fun hasLabel(context: AccessibleContext, label: String): Boolean =
        Regex("(^|[\\r\\n,，])\\s*${Regex.escape(label)}\\s*($|[\\r\\n,，])").containsMatchIn(context.accessibleName.orEmpty()) ||
            descendants(context).any { it.accessibleName == label }

    private fun current(): DesktopOriginalRootValidationTap.Frame {
        check(EventQueue.isDispatchThread())
        val frame = requireNotNull(latest.get())
        check(frame.handle === owner && frame.routes === routes && owner.isActive() && routes.owns() && owner.route.get() === routes)
        check(System.identityHashCode(window()) == ownedWindowIdentity)
        return frame
    }

    private fun record(id: String, properties: Map<String, JsonElement> = emptyMap()) {
        val frame = edt { current() }
        rows.add(buildJsonObject {
            put("id", id); put("serial", frame.serial); put("key", frame.key.toString())
            put("sameRootAndRouteAssembly", true); put("actualWindowIdentity", ownedWindowIdentity)
            properties.forEach { (key, value) -> put(key, value) }
        })
    }

    private fun await(description: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos()
        while (System.nanoTime() < deadline) { if (predicate()) return; Thread.sleep(100) }
        edt { dump("timeout", window()) }
        error("Actual PlaybackSettings condition timed out: $description; current=${latest.get()?.key}")
    }

    private fun awaitPage(key: BiliPaiNavKey, after: Long, anchors: List<String>): DesktopOriginalRootValidationTap.Frame {
        await("drawn $key with original page anchors") { edt {
            val frame = current()
            frame.key == key && frame.serial > after && anchors.all { text -> all().any { it.accessibleName.orEmpty().contains(text) } }
        } }
        Thread.sleep(1200)
        return edt { current().also { check(it.key == key) } }
    }

    private fun click(scope: AccessibleContext, label: String, role: AccessibleRole? = null, mouseSurface: Window? = null): String {
        current()
        val matches = descendants(scope).filter { node ->
            hasLabel(node, label) && (node.accessibleAction?.accessibleActionCount ?: 0) == 1 &&
                (role == null || node.accessibleRole == role)
        }
        check(matches.size == 1) { "Expected one actual '$label' in scoped original surface, found ${matches.size}: ${matches.map { it.accessibleRole }}" }
        val node = matches.single()
        check(node.accessibleStateSet.contains(AccessibleState.ENABLED)) { "Actual original control is disabled: $label" }
        val action = requireNotNull(node.accessibleAction)
        val bounds = node.accessibleComponent?.let { component ->
            val point = component.locationOnScreen
            val size = component.size
            buildJsonObject {
                put("x", point?.x?.let(::JsonPrimitive) ?: JsonNull)
                put("y", point?.y?.let(::JsonPrimitive) ?: JsonNull)
                put("width", size.width); put("height", size.height)
            }
        }
        record("action-${rows.size}-$label-before", mapOf(
            "requestedLabel" to JsonPrimitive(label),
            "actualAccessibleName" to JsonPrimitive(node.accessibleName.orEmpty()),
            "actualAccessibleRole" to JsonPrimitive(node.accessibleRole.toString()),
            "actualContextIdentity" to JsonPrimitive(System.identityHashCode(node)),
            "actualScopeIdentity" to JsonPrimitive(System.identityHashCode(scope)),
            "actualActionDescription" to JsonPrimitive(action.getAccessibleActionDescription(0).orEmpty()),
            "inputMechanism" to JsonPrimitive(if (mouseSurface == null) "ORIGINAL_ACCESSIBLE_ACTION" else "OWNED_COMPOSE_AWT_MOUSE_EVENT"),
            "actualNativeBounds" to (bounds ?: JsonNull),
            "matchesInCompleteScope" to JsonPrimitive(matches.size)))
        if (mouseSurface == null) check(action.doAccessibleAction(0))
        else clickOwnedComposeMouse(mouseSurface, node)
        record("action-${rows.size}-$label-received", mapOf("actionReceived" to JsonPrimitive(true)))
        return node.accessibleRole.toString()
    }

    private fun nativeComponents(component: Component): List<Component> =
        mutableListOf(component).also { result ->
            if (component is Container) component.components.forEach { result.addAll(nativeComponents(it)) }
        }
    /** Same owned renderer input used by normal pointer events. Accessibility identifies
     * the unique original control; its cached action closure is not used for this branch. */

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
            record("mouse-release-${rows.size}", mapOf("ownedRendererInputClass" to JsonPrimitive(input.javaClass.name),
                "pressAndReleaseOnSeparateEdtTurns" to JsonPrimitive(true)))
        }.apply { isRepeats = false; start() }
    }

    private fun completeScope(root: AccessibleContext, anchors: List<String>, exactActionAnchors: Boolean = false): AccessibleContext {
        val candidates = descendants(root).filter { node -> anchors.all { anchor -> descendants(node).any {
            if (exactActionAnchors) it.accessibleName == anchor && (it.accessibleAction?.accessibleActionCount ?: 0) == 1
            else it.accessibleName.orEmpty().contains(anchor)
        } } }
        check(candidates.isNotEmpty()) { "No actual original complete group: $anchors" }
        val counts = candidates.associateWith { descendants(it).size }
        val smallest = requireNotNull(counts.values.minOrNull())
        return candidates.filter { counts[it] == smallest }.single()
    }

    private fun dump(id: String, surface: Window) = Files.writeString(report.resolve("$id-accessibility.tsv"), all(surface).joinToString("\n") {
        "${it.accessibleName}\t${it.accessibleRole}\t${it.accessibleStateSet}\t${it.accessibleAction?.accessibleActionCount ?: 0}"
    }, CREATE_NEW, WRITE)

    private fun layer(component: Component, type: Class<*>): Any? {
        if (type.isInstance(component)) return component
        if (component is Container) component.components.forEach { layer(it, type)?.let { found -> return found } }
        return null
    }

    private fun capture(id: String, surface: Window? = null) {
        if (surface == null) { actions.capture(id, edt { current() }); return }
        edt {
            val frame = current()
            check(surface.isShowing && ownedWindow(surface))
            dump(id, surface)
            val type = Class.forName("org.jetbrains.skiko.SkiaLayer")
            val actual = requireNotNull(layer(surface, type))
            val renderer = type.getMethod("getRenderApi").invoke(actual).toString()
            check(renderer == "DIRECT3D")
            surface.javaClass.getMethod("renderImmediately").invoke(surface)
            val bitmap = requireNotNull(type.getMethod("screenshot").invoke(actual))
            try {
                val imageType = Class.forName("org.jetbrains.skia.Image")
                val companion = imageType.getField("Companion").get(null)
                val image = companion.javaClass.getMethod("makeFromBitmap", bitmap.javaClass).invoke(companion, bitmap)
                try {
                    val formatType = Class.forName("org.jetbrains.skia.EncodedImageFormat")
                    val data = requireNotNull(imageType.getMethod("encodeToData", formatType, Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType).invoke(image, formatType.getField("PNG").get(null), 100, 6))
                    try { Files.write(report.resolve("$id.png"), data.javaClass.getMethod("getBytes").invoke(data) as ByteArray, CREATE_NEW, WRITE) }
                    finally { data.javaClass.getMethod("close").invoke(data) }
                } finally { imageType.getMethod("close").invoke(image) }
            } finally { bitmap.javaClass.getMethod("close").invoke(bitmap) }
            Files.writeString(report.resolve("$id-frame.txt"), "${frame.key}\n${frame.serial}\n$renderer\n${surface.javaClass.name}\n", CREATE_NEW, WRITE)
        }
    }

    private fun namespaces(): JsonObject {
        val path = DesktopLibrary.directoryForAccount(null).resolve("plugin-settings.json")
        if (!Files.exists(path, NOFOLLOW_LINKS)) return JsonObject(emptyMap())
        check(com.bilipai.desktop.update.UpdateStorage.existingPathWithoutLinks(path) == path.toAbsolutePath().normalize())
        return Json.parseToJsonElement(Files.readString(path)).jsonObject
    }

    private fun settings(): JsonObject = namespaces()["settings"] as? JsonObject ?: JsonObject(emptyMap())

    private fun diagnosticSurface(): Window? {
        current()
        val bodies = listOf("Windows 版本仅在本地保存脱敏的基础错误与崩溃快照", "启用增强本地诊断",
            "关闭后不保留增强日志；基础错误与崩溃快照仍保留")
        val candidates = Window.getWindows().filter { it.isShowing && ownedWindow(it) }.filter { surface ->
            val nodes = all(surface)
            nodes.any { it.accessibleName == "帮助改进应用" } &&
                bodies.all { body -> nodes.any { it.accessibleName.orEmpty().contains(body) } } &&
                nodes.count { it.accessibleName == "确定" && (it.accessibleAction?.accessibleActionCount ?: 0) == 1 } == 1
        }
        check(candidates.size <= 1)
        return candidates.singleOrNull()
    }

    private fun dismissActualDiagnosticPrompt(fresh: Boolean) {
        check((settings()["enhanced_diagnostic_logging_enabled"] as? JsonPrimitive)?.booleanOrNull != true)
        check((settings()["crash_tracking_enabled"] as? JsonPrimitive)?.booleanOrNull != true)
        if (fresh) {
            await("complete original default-off diagnostic prompt") { edt { diagnosticSurface() != null } }
            val surface = edt { requireNotNull(diagnosticSurface()) }
            capture("110-home-diagnostic-default-off", surface)
            edt { click(surface.accessibleContext, "确定") }
            await("original diagnostic confirmation persisted false and dismissed") {
                edt { diagnosticSurface() == null } &&
                    (settings()["crash_tracking_consent_shown"] as? JsonPrimitive)?.booleanOrNull == true &&
                    (settings()["enhanced_diagnostic_logging_enabled"] as? JsonPrimitive)?.booleanOrNull == false &&
                    (settings()["crash_tracking_enabled"] as? JsonPrimitive)?.booleanOrNull == false
            }
        } else {
            check((settings()["crash_tracking_consent_shown"] as? JsonPrimitive)?.booleanOrNull == true)
            check(edt { diagnosticSurface() == null })
        }
        record("diagnostic-confirmation", mapOf("originalControlOnly" to JsonPrimitive(true),
            "enhancedDiagnosticLoggingStillDisabled" to JsonPrimitive(true), "crashTrackingStillDisabled" to JsonPrimitive(true)))
    }

    private fun composeKeyboardComponent(surface: Window): Component {
        current(); check(surface.isShowing && surface.isDisplayable && ownedWindow(surface))
        val candidates = nativeComponents(surface).filter { component ->
            component.isShowing && component.isDisplayable && component.isEnabled && component.isFocusable &&
                SwingUtilities.getWindowAncestor(component) === surface && component.keyListeners.any {
                    it.javaClass.name == "androidx.compose.ui.scene.ComposeSceneMediator\$keyListener\$1"
                }
        }
        check(candidates.size == 1) { "Expected one actual owned Compose keyboard component, got ${candidates.map { it.javaClass.name }}" }
        return candidates.single()
    }

    private fun focusSnapshot(surface: Window, target: Component, requestAccepted: Boolean): JsonObject {
        check(EventQueue.isDispatchThread())
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        return buildJsonObject {
            put("actualInputClass", target.javaClass.name); put("inputIdentity", System.identityHashCode(target))
            put("requestFocusInWindowAccepted", requestAccepted); put("activeWindowIsRequestedSurface", manager.activeWindow === surface)
            put("focusOwnerIsActualComposeInput", manager.focusOwner === target)
            put("focusOwnerClass", manager.focusOwner?.javaClass?.name ?: "null")
            put("focusOwnerBelongsToRequestedSurface", manager.focusOwner?.let { SwingUtilities.getWindowAncestor(it) === surface } == true)
            put("requestedOwnedSurfaceClass", surface.javaClass.name)
            put("nativeOwnedComponents", JsonArray(nativeComponents(surface).map { component -> buildJsonObject {
                put("class", component.javaClass.name); put("identity", System.identityHashCode(component))
                put("showing", component.isShowing); put("displayable", component.isDisplayable)
                put("focusable", component.isFocusable); put("enabled", component.isEnabled); put("isFocusOwner", component.isFocusOwner)
                put("keyListeners", JsonArray(component.keyListeners.map { JsonPrimitive(it.javaClass.name) }))
            } }))
        }
    }

    private fun escape(surface: Window) {
        val target = edt {
            current(); check(ownedWindow(surface) && surface.isShowing)
            surface.toFront(); surface.requestFocus()
            composeKeyboardComponent(surface)
        }
        val snapshots = mutableListOf<JsonObject>()
        val received = mutableListOf<JsonObject>()
        val deliveries = mutableListOf<JsonObject>()
        var pressed: KeyEvent? = null
        var released: KeyEvent? = null
        // Observe only the same event identities after the already installed real
        // Compose listener. Never consume, invoke a handler, or inject another event.
        val receipt = object : KeyAdapter() {
            private fun record(event: KeyEvent) {
                if (event !== pressed && event !== released) return
                received.add(buildJsonObject {
                    put("id", event.id); put("keyCode", event.keyCode); put("keyLocation", event.keyLocation)
                    put("sameEventIdentity", true); put("sourceIsActualOwnedInput", event.component === target)
                    put("consumedAfterRealComposeListener", event.isConsumed)
                    put("inputIdentity", System.identityHashCode(target))
                })
            }
            override fun keyPressed(event: KeyEvent) = record(event)
            override fun keyReleased(event: KeyEvent) = record(event)
        }
        var receiptInstalled = false
        var accepted = edt { target.requestFocusInWindow().also { snapshots.add(focusSnapshot(surface, target, it)) } }
        try {
            await("owned window and actual Compose component keyboard focus") { edt {
                current(); check(ownedWindow(surface) && surface.isShowing && composeKeyboardComponent(surface) === target)
                val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                val ready = manager.activeWindow === surface && manager.focusOwner === target &&
                    SwingUtilities.getWindowAncestor(target) === surface
                // The first component request can precede AWT Window activation. Retry only
                // that same real input in the now-active owned surface; never grant fake focus.
                if (!ready && manager.activeWindow === surface) accepted = target.requestFocusInWindow()
                ready
            } }
            edt {
                current()
                val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                check(composeKeyboardComponent(surface) === target && manager.activeWindow === surface && manager.focusOwner === target &&
                    SwingUtilities.getWindowAncestor(target) === surface)
                snapshots.add(focusSnapshot(surface, target, accepted))
                val now = System.currentTimeMillis()
                target.addKeyListener(receipt); receiptInstalled = true
                val down = KeyEvent(target, KeyEvent.KEY_PRESSED, now, 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED)
                pressed = down
                val downDispatched = manager.dispatchEvent(down)
                deliveries.add(buildJsonObject {
                    put("id", down.id); put("keyCode", down.keyCode); put("keyLocation", down.keyLocation)
                    put("dispatchReturn", downDispatched); put("consumed", down.isConsumed)
                    put("observedRealInputReceipt", received.count { it["id"]?.jsonPrimitive?.int == KeyEvent.KEY_PRESSED } == 1)
                })
                val up = KeyEvent(target, KeyEvent.KEY_RELEASED, now + 1, 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED)
                released = up
                val upDispatched = manager.dispatchEvent(up)
                deliveries.add(buildJsonObject {
                    put("id", up.id); put("keyCode", up.keyCode); put("keyLocation", up.keyLocation)
                    put("dispatchReturn", upDispatched); put("consumed", up.isConsumed)
                    put("observedRealInputReceipt", received.count { it["id"]?.jsonPrimitive?.int == KeyEvent.KEY_RELEASED } == 1)
                })
            }
        } finally {
            snapshots.add(edt {
                if (receiptInstalled) target.removeKeyListener(receipt)
                check(target.keyListeners.none { it === receipt })
                focusSnapshot(surface, target, accepted)
            })
            Files.writeString(report.resolve("owned-keyboard-focus-${rows.size}.json"),
                JsonArray(snapshots).toString() + "\n", CREATE_NEW, WRITE)
            Files.writeString(report.resolve("owned-keyboard-delivery-${rows.size}.json"), buildJsonObject {
                put("scope", "ACTUAL_OWNED_AWT_COMPONENT_REAL_COMPOSE_LISTENER")
                put("dispatch", JsonArray(deliveries)); put("received", JsonArray(received))
                put("testObserverRemoved", true)
                put("pressedReceiptRequired", true)
                put("releasedMayBeRetiredByOriginalModalClose", true)
            }.toString() + "\n", CREATE_NEW, WRITE)
        }
        check(received.count { it["id"]?.jsonPrimitive?.int == KeyEvent.KEY_PRESSED &&
            it["keyCode"]?.jsonPrimitive?.int == KeyEvent.VK_ESCAPE &&
            it["sourceIsActualOwnedInput"]?.jsonPrimitive?.boolean == true } == 1) {
            "Actual owned Compose input did not receive the one original Escape press"
        }
    }
    private val headers = listOf("视频解码", "播放速度", "小窗与后台", "手势控制", "诊断", "网络与画质", "省流量", "互动与评论", "全屏与手势")
    private val expected = linkedMapOf("hw_decode" to false, "startup_auto_play_enabled" to true,
        "detailed_comment_time_enabled" to true, "favorite_quick_save_default_folder" to true)
    private fun visible(node: AccessibleContext): Boolean {
        val component = node.accessibleComponent ?: return false
        val point = component.locationOnScreen ?: return false
        val size = component.size
        val content = window().contentPane
        val origin = content.locationOnScreen
        return size.width > 0 && size.height > 0 &&
            Rectangle(origin.x, origin.y, content.width, content.height).contains(Rectangle(point.x, point.y, size.width, size.height))
    }
    private fun wheel(rotation: Int) = edt {
        current()
        val input = requireNotNull(layer(window(), Class.forName("org.jetbrains.skiko.SkiaLayer"))) as Component
        check(input.isShowing && SwingUtilities.getWindowAncestor(input) === window())
        input.dispatchEvent(MouseWheelEvent(input, MouseWheelEvent.MOUSE_WHEEL, System.currentTimeMillis(),
            0, input.width / 2, input.height / 2, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation))
    }
    private fun control(label: String): AccessibleContext {
        current()
        val candidates = all().filter { hasLabel(it, label) && (it.accessibleAction?.accessibleActionCount ?: 0) == 1 }
        check(candidates.size <= 1) { "Ambiguous original playback control $label: ${candidates.map { it.accessibleRole }}" }
        return requireNotNull(candidates.singleOrNull()) { "Original playback control absent: $label" }
    }
    private fun ensureControlVisible(label: String) {
        repeat(130) {
            if (edt { all().any { hasLabel(it, label) && (it.accessibleAction?.accessibleActionCount ?: 0) == 1 && visible(it) } }) return
            wheel(3); Thread.sleep(120)
        }
        error("Original control was not reached by real wheel input: $label")
    }
    private fun naturalEntry(after: Long): DesktopOriginalRootValidationTap.Frame {
        edt { click(window().accessibleContext, "设置") }
        val root = awaitPage(BiliPaiNavKey.Settings, after, listOf("搜索设置", "播放与画质"))
        edt { click(window().accessibleContext, "播放与画质") }
        val category = awaitPage(BiliPaiNavKey.Settings, root.serial, listOf("播放设置", "互动与评论"))
        capture("120-original-playback-category")
        edt { click(window().accessibleContext, "播放设置", AccessibleRole.LABEL) }
        val page = awaitPage(BiliPaiNavKey.Settings, category.serial, listOf("播放设置", "启用硬件解码", "视频解码"))
        record("natural-playback-entry", mapOf("entryMechanism" to JsonPrimitive("ORIGINAL_ACCESSIBLE_ACTIONS"),
            "originalFocusEntrance" to JsonPrimitive("PLAYBACK_DECODER")))
        return page
    }
    private fun originalPlaybackBack() = edt {
        current()
        val titles = all().filter { it.accessibleRole == AccessibleRole.LABEL && it.accessibleName == "播放设置" &&
            (it.accessibleAction?.accessibleActionCount ?: 0) == 0 && visible(it) }
        check(titles.size == 1)
        val title = requireNotNull(titles.single().accessibleComponent)
        val titlePosition = requireNotNull(title.locationOnScreen)
        val center = titlePosition.y + title.size.height / 2
        val candidates = all().filter { node ->
            if (node.accessibleRole != AccessibleRole.PUSH_BUTTON || !hasLabel(node, "返回") ||
                (node.accessibleAction?.accessibleActionCount ?: 0) != 1 || !visible(node)) return@filter false
            val component = requireNotNull(node.accessibleComponent)
            val point = requireNotNull(component.locationOnScreen)
            point.x + component.size.width / 2 < titlePosition.x &&
                kotlin.math.abs(point.y + component.size.height / 2 - center) <= 3
        }
        check(candidates.size == 1) { "Actual original header Back must be unique: ${candidates.size}" }
        check(candidates.single().accessibleAction.doAccessibleAction(0))
        record("original-playback-scoped-back-${rows.size}", mapOf("sameOriginalHeaderRow" to JsonPrimitive(true)))
    }
    private fun scanOriginalGroups() {
        val seen = linkedSetOf<String>()
        repeat(220) { index ->
            val newly = edt { headers.filter { header -> header !in seen && all().any {
                it.accessibleRole == AccessibleRole.LABEL && it.accessibleName == header && visible(it)
            } } }
            if (newly.isNotEmpty()) {
                seen.addAll(newly)
                record("groups-visible-$index", mapOf("originalHeaders" to JsonArray(newly.map(::JsonPrimitive)),
                    "actualBoundsInsideOwnedClient" to JsonPrimitive(true)))
                capture("group-${index.toString().padStart(3, '0')}")
            }
            if (seen.containsAll(headers)) {
                record("complete-original-groups", mapOf("originalHeaders" to JsonArray(headers.map(::JsonPrimitive)),
                    "actualFullyVisibleHeaders" to JsonArray(seen.map(::JsonPrimitive)), "count" to JsonPrimitive(9)))
                return
            }
            wheel(2); Thread.sleep(140)
        }
        error("Not every original group was actually visible: seen=$seen; expected=$headers")
    }
    private fun rewind() { repeat(75) { wheel(-5); Thread.sleep(35) }; Thread.sleep(400) }
    private fun toggle(label: String, key: String, target: Boolean, id: String) {
        ensureControlVisible(label)
        edt { click(window().accessibleContext, label, mouseSurface = window()) }
        await("original switch $label publishes $target in same actual Store") {
            (settings()[key] as? JsonPrimitive)?.booleanOrNull == target
        }
        Thread.sleep(300)
        record(id, mapOf("originalLabel" to JsonPrimitive(label), "canonicalKey" to JsonPrimitive(key),
            "actualDurableValue" to JsonPrimitive(target), "inputMechanism" to JsonPrimitive("OWNED_COMPOSE_AWT_MOUSE_EVENT")))
        capture(id)
    }
    private fun persisted(): Boolean = expected.all { (key, value) -> (settings()[key] as? JsonPrimitive)?.booleanOrNull == value } &&
        ((namespaces()["player_settings_cache"] as? JsonObject)?.get("hw_decode_enabled") as? JsonPrimitive)?.booleanOrNull == false
    private fun originalSwitchReadback(label: String, expectedChecked: Boolean) = edt {
        val row = control(label)
        check(visible(row))
        val switches = descendants(row).filter {
            it.accessibleRole == AccessibleRole.CHECK_BOX || it.accessibleRole == AccessibleRole.TOGGLE_BUTTON
        }
        check(switches.size == 1) { "Expected one original Switch in '$label', got ${switches.map { it.accessibleRole }}" }
        val actual = switches.single()
        val checked = actual.accessibleStateSet.contains(AccessibleState.CHECKED) ||
            actual.accessibleStateSet.contains(AccessibleState.SELECTED)
        check(checked == expectedChecked) { "Original '$label' UI readback differs from persisted setting" }
        record("cold-ui-$label", mapOf("expectedChecked" to JsonPrimitive(expectedChecked),
            "actualChecked" to JsonPrimitive(checked), "actualRole" to JsonPrimitive(actual.accessibleRole.toString()),
            "actualStates" to JsonPrimitive(actual.accessibleStateSet.toString())))
    }
    /** Failure evidence only: the cached actual Main window still identifies its own
     * native children after composition admission retires. Never grants a PASS owner. */
    private fun captureActualFailureSurfaces() = edt {
        val actual = diagnosticWindow ?: return@edt
        fun belongs(candidate: Window): Boolean {
            var value: Window? = candidate
            while (value != null) { if (value === actual) return true; value = value.owner }
            return false
        }
        val surfaces = Window.getWindows().filter { belongs(it) }
        val diagnostics = buildJsonObject {
            put("scope", "FAILURE_ONLY_CACHED_ACTUAL_MAIN_NATIVE_OWNED_SURFACES")
            put("passAdmissionGranted", false)
            put("cachedWindowIdentity", System.identityHashCode(actual))
            put("cachedWindowStillExpectedIdentity", System.identityHashCode(actual) == ownedWindowIdentity)
            put("observedOwnerActive", runCatching { owner.isActive() }.getOrDefault(false))
            put("observedRoutesOwn", runCatching { routes.owns() }.getOrDefault(false))
            put("surfaces", JsonArray(surfaces.mapIndexed { index, surface ->
                // Unlike PASS captures, this raw diagnostic has no current() call.
                // The original cached native owner chain is the sole inspection boundary.
                runCatching { dump("failure-owned-surface-$index", surface) }
                buildJsonObject {
                    put("identity", System.identityHashCode(surface))
                    put("ownerIdentity", surface.owner?.let { JsonPrimitive(System.identityHashCode(it)) } ?: JsonNull)
                    put("sameActualMainOrNativeOwnedChild", true)
                    put("kind", surface.javaClass.name); put("showing", surface.isShowing)
                    put("displayable", surface.isDisplayable)
                    put("title", when (surface) { is java.awt.Frame -> surface.title; is java.awt.Dialog -> surface.title; else -> "" })
                    put("accessibleText", JsonArray(all(surface).mapNotNull { node ->
                        val text = node.accessibleText ?: return@mapNotNull null
                        val count = text.charCount
                        if (count <= 0) return@mapNotNull null
                        buildJsonObject {
                            put("name", node.accessibleName.orEmpty()); put("role", node.accessibleRole.toString())
                            put("charCount", count); put("truncated", count > 131072)
                            put("text", buildString {
                                repeat(count.coerceAtMost(131072)) { offset ->
                                    append(text.getAtIndex(javax.accessibility.AccessibleText.CHARACTER, offset).orEmpty())
                                }
                            })
                        }
                    }))
                    put("nativeSwingText", JsonArray(nativeComponents(surface).mapNotNull { component ->
                        val text = (component as? javax.swing.text.JTextComponent)?.text ?: return@mapNotNull null
                        buildJsonObject {
                            put("kind", component.javaClass.name); put("text", text.take(131072))
                            put("truncated", text.length > 131072)
                        }
                    }))
                }
            }))
        }
        Files.writeString(report.resolve("failure-owned-surfaces.json"), diagnostics.toString(), CREATE_NEW, WRITE)
    }

    private fun fresh(home: DesktopOriginalRootValidationTap.Frame) {
        naturalEntry(home.serial)
        capture("130-complete-original-page-initial")
        toggle("启用硬件解码", "hw_decode", false, "140-original-hardware-disabled")
        toggle("启动自动续播", "startup_auto_play_enabled", true, "150-original-startup-enabled")
        rewind()
        scanOriginalGroups()
        val beforeBack = edt { current().serial }
        originalPlaybackBack()
        var category = awaitPage(BiliPaiNavKey.Settings, beforeBack, listOf("播放设置", "互动与评论"))
        val interactionTitle = com.android.purebilibili.feature.settings.settingsDestinationCopy(
            com.android.purebilibili.feature.settings.SettingsSearchTarget.INTERACTION_COMMENT).title
        edt { click(window().accessibleContext, interactionTitle, AccessibleRole.LABEL) }
        val interaction = awaitPage(BiliPaiNavKey.Settings, category.serial, listOf("详细评论时间显示", "互动与评论"))
        check(edt { all().any { it.accessibleName == "互动与评论" && visible(it) } })
        record("natural-interaction-focus-entry", mapOf("entryMechanism" to JsonPrimitive("ORIGINAL_ACCESSIBLE_ACTIONS"),
            "originalFocusEntrance" to JsonPrimitive("PLAYBACK_INTERACTION"), "focusControlForcedByFixture" to JsonPrimitive(false)))
        capture("160-interaction-focus-visible")
        toggle("详细评论时间显示", "detailed_comment_time_enabled", true, "170-detailed-comment-time-enabled")
        toggle("点按收藏进默认收藏夹", "favorite_quick_save_default_folder", true, "180-original-favorite-enabled")
        check(persisted())
        escape(edt { window() })
        category = awaitPage(BiliPaiNavKey.Settings, interaction.serial, listOf("播放设置", "互动与评论"))
        check(edt { all().none { it.accessibleName == "详细评论时间显示" } })
        record("original-playback-owned-escape", mapOf("escapeMechanism" to JsonPrimitive("OWNED_AWT_FOCUS_KEY_EVENT"),
            "noRootBackCommandUsed" to JsonPrimitive(true)))
        capture("190-owned-escape-returned-category")
    }
    private fun cold(home: DesktopOriginalRootValidationTap.Frame) {
        check(persisted())
        naturalEntry(home.serial)
        ensureControlVisible("启用硬件解码")
        originalSwitchReadback("启用硬件解码", false)
        record("cold-hardware-durable", mapOf("canonicalAndMirrorReadFromDisk" to JsonPrimitive(true), "expectedValue" to JsonPrimitive(false)))
        capture("220-cold-original-decoder-page")
        ensureControlVisible("启动自动续播")
        originalSwitchReadback("启动自动续播", true)
        capture("230-cold-startup-original-control")
        val beforeBack = edt { current().serial }
        originalPlaybackBack()
        val category = awaitPage(BiliPaiNavKey.Settings, beforeBack, listOf("播放设置", "互动与评论"))
        val interactionTitle = com.android.purebilibili.feature.settings.settingsDestinationCopy(
            com.android.purebilibili.feature.settings.SettingsSearchTarget.INTERACTION_COMMENT).title
        edt { click(window().accessibleContext, interactionTitle, AccessibleRole.LABEL) }
        awaitPage(BiliPaiNavKey.Settings, category.serial, listOf("详细评论时间显示", "互动与评论"))
        check(persisted())
        ensureControlVisible("详细评论时间显示")
        originalSwitchReadback("详细评论时间显示", true)
        ensureControlVisible("点按收藏进默认收藏夹")
        originalSwitchReadback("点按收藏进默认收藏夹", true)
        record("cold-interaction-durable", mapOf("allFourOriginalKeysAndHardwareMirrorReadFromDisk" to JsonPrimitive(true)))
        capture("240-cold-original-interaction-page")
        val beforeFinalBack = edt { current().serial }
        originalPlaybackBack()
        awaitPage(BiliPaiNavKey.Settings, beforeFinalBack, listOf("播放设置", "互动与评论"))
        capture("250-cold-original-back-returned-category")
    }
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 4 && args[0] in setOf("fresh", "cold"))
        val phase = args[0]
        report = Path.of(args[1]).toRealPath()
        Files.list(report).use { require(it.findAny().isEmpty) }
        val health = Path.of(args[2]).toAbsolutePath().normalize()
        require(!Files.exists(health))
        actions = OriginalOnboardingUiActions(latest::get, report, args[3])
        if (phase == "fresh") { actions.requireFreshGuestStartup(); actions.requireAcknowledged(false) }
        else { actions.requireAcknowledged(true); check(persisted()) }
        DesktopOriginalRootValidationTap.install { latest.set(it) }.use {
            val worker = Thread({
                try {
                    val home = if (phase == "fresh") actions.acceptFreshAgreementToHome(health) else actions.awaitHome()
                    actions.awaitActualHealth(health)
                    owner = home.handle; routes = home.routes; ownedWindowIdentity = edt { window().also { diagnosticWindow = it }.let { System.identityHashCode(it) } }
                    dismissActualDiagnosticPrompt(phase == "fresh")
                    if (phase == "fresh") fresh(home) else cold(home)
                    actions.requireAcknowledged(true)
                    check(persisted())
                    val receipt = buildJsonObject {
                        put("schema", 1); put("phase", phase); put("actualMainReturned", false)
                        put("observationPhase", "BEFORE_REQUESTED_ACTUAL_EXIT"); put("allPreExitAssertionsPassed", true)
                        put("externalExitAndFinalPersistenceRequired", true); put("defaultRenderer", "DIRECT3D")
                        put("actualMainInvocations", 1); put("sameLiveRootAndWindow", true)
                        put("realAccountUsed", false); put("fixturePreferenceWrites", false); put("physicalStackWrittenByFixture", false)
                        put("allRoutesAccepted", false); put("newExeDeployed", false); put("nativePlaybackExercised", false)
                        put("consumerGapsAccepted", false)
                        put("expectedFinalSettings", buildJsonObject { expected.forEach { (key, value) -> put(key, value) } })
                        put("observations", JsonArray(rows.toList()))
                    }
                    Files.writeString(report.resolve("observations.json"), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), receipt), CREATE_NEW, WRITE)
                    completed.set(true)
                    actions.closeOwnedWindow(edt { current() })
                } catch (failure: Throwable) {
                    failure.printStackTrace()
                    runCatching { captureActualFailureSurfaces() }.onFailure { diagnosticFailure -> diagnosticFailure.printStackTrace() }
                    runCatching { edt { dump("failure", window()) } }
                    runCatching { capture("failure-owned-window") }
                    runCatching { Files.writeString(report.resolve("failure-observations.json"), JsonArray(rows.toList()).toString(), CREATE_NEW, WRITE) }
                    kotlin.system.exitProcess(91)
                }
            }, "Original PlaybackSettings actual Root observer")
            worker.isDaemon = true; worker.start()
            com.bilipai.desktop.main(arrayOf("--update-health-file", health.toString(), "--update-health-token", args[3]))
            check(completed.get()) { "Actual Main returned before Playback assertions" }
        }
    }

}
