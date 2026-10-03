package com.bilipai.desktop.ui

import androidx.lifecycle.Lifecycle
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.DesktopLibrary
import java.awt.Component
import java.awt.Container
import java.awt.Dialog
import java.awt.EventQueue
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.KeyAdapter
import java.awt.event.MouseWheelEvent
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleState
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JTextField
import javax.swing.JButton
import javax.swing.UIManager
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import kotlinx.serialization.json.*

import com.android.purebilibili.core.store.HomeWallpaperEffectMode
import com.android.purebilibili.core.store.HomeWallpaperEffectScope
import com.android.purebilibili.core.store.HomeHeaderCollapseMode
import com.android.purebilibili.feature.settings.SettingsSearchTarget
import com.android.purebilibili.feature.settings.SettingsSearchFocusController
import javax.accessibility.AccessibleRole
/** Opt-in actual unchanged Main guest UI. Preferences are selected only through original
 * accessible controls. The existing Root frame exposes its actual Home port read-only;
 * no synthetic Home VM/Store, navigation stack write or new observer seam is introduced.
 * This independent slice selects two private PNGs through the original owned JFileChooser.
 * Strict external PNG pattern/restore/cold oracle is required; image/video/owner-retire
 * acceptance must never be inferred from a stored URI or a Swing diagnostic paint.
 */
object OriginalHomeWallpaperRootUiFixture {
    private val latest = AtomicReference<DesktopOriginalRootValidationTap.Frame?>()
    private val completed = AtomicBoolean(false)
    private lateinit var owner: DesktopReadyOriginalRootHandle
    private lateinit var routes: DesktopOriginalRootRouteAssembly
    private lateinit var actualWindow: JFrame
    private lateinit var report: Path
    private lateinit var actions: OriginalOnboardingUiActions
    private val observations = mutableListOf<JsonObject>()
    private fun <T> edt(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        val answer = AtomicReference<Result<T>?>()
        EventQueue.invokeAndWait { answer.set(runCatching(block)) }
        return requireNotNull(answer.get()).getOrThrow()
    }
    private fun mainWindow(): JFrame = Window.getWindows().filterIsInstance<JFrame>()
        .filter { it.isDisplayable && it.title == "BiliPai Windows" }.single()
    /** Same-owner admission remains a hard assertion even while a real route is drawing. */
    private fun sameActualRootFrame(): DesktopOriginalRootValidationTap.Frame {
        check(EventQueue.isDispatchThread())
        val frame = requireNotNull(latest.get())
        check(frame.handle === owner && frame.routes === routes && owner.isActive() && routes.owns() && owner.route.get() === routes) {
            "Actual Root owner/routes retired or replaced while awaiting the original UI"
        }
        check(mainWindow() === actualWindow) { "Actual Root JFrame was replaced" }
        return frame
    }
    private fun belongsToCurrentRoute(frame: DesktopOriginalRootValidationTap.Frame): Boolean =
        if (frame.pagerHosted) routes.currentKey == BiliPaiNavKey.MainHost else frame.key == routes.currentKey
    private fun current(): DesktopOriginalRootValidationTap.Frame = sameActualRootFrame().also { frame ->
        check(belongsToCurrentRoute(frame)) {
            "Latest owned frame is not the current route: frame=${frame.key}; physical=${routes.currentKey}; serial=${frame.serial}"
        }
    }
    private fun owned(candidate: Window): Boolean {
        var value: Window? = candidate
        while (value != null) { if (value === actualWindow) return true; value = value.owner }
        return false
    }
    private fun nodes(context: AccessibleContext?, result: MutableList<AccessibleContext>, depth: Int = 0) {
        if (context == null || depth > 80) return
        result.add(context)
        repeat(context.accessibleChildrenCount) { nodes(context.getAccessibleChild(it)?.accessibleContext, result, depth + 1) }
    }
    private fun descendants(context: AccessibleContext): List<AccessibleContext> = mutableListOf<AccessibleContext>().also { nodes(context, it) }
    private fun all(window: Window = actualWindow) = descendants(window.accessibleContext)
    private fun names(window: Window = actualWindow) = all(window).map { it.accessibleName.orEmpty() }
    private fun hasLabel(context: AccessibleContext, label: String): Boolean =
        Regex("(^|[\\r\\n,，])\\s*${Regex.escape(label)}\\s*($|[\\r\\n,，])").containsMatchIn(context.accessibleName.orEmpty()) ||
            descendants(context).any { it.accessibleName == label }
    private fun originalAction(scope: AccessibleContext, label: String): AccessibleContext {
        current()
        val matches = descendants(scope).filter { hasLabel(it, label) && (it.accessibleAction?.accessibleActionCount ?: 0) == 1 }
        check(matches.size == 1) { "Expected unique original '$label' action, got ${matches.map { it.accessibleRole }}" }
        return matches.single().also { check(it.accessibleStateSet.contains(AccessibleState.ENABLED)) }
    }
    /** Lowest real accessibility subtree containing the complete original page anchors.
     * The Shell's physical Settings Back sits outside this leaf. If Compose flattens
     * layout semantics, exact native header-row geometry still excludes that outer Back.
     * No first/last/index selection and no duplicate-action tolerance is allowed. */
    private var completeScopeReceiptNumber = 0L
    private fun completeScope(root: AccessibleContext, anchors: List<String>): AccessibleContext {
        current()
        val complete = descendants(root).filter { node ->
            anchors.all { anchor -> descendants(node).any { it.accessibleName.orEmpty().contains(anchor) } }
        }
        val completeSizes = complete.associateWith { descendants(it).size }
        val unfilteredMinimum = completeSizes.values.minOrNull()
        // Retained off-client entries may still expose SHOWING. Filter only by the
        // real owned client geometry; two complete visible scopes remain an error.
        val candidates = complete.filter { whollyVisible(it) }
        val sizes = candidates.associateWith { completeSizes.getValue(it) }
        val smallest = sizes.values.minOrNull()
        val scopes = candidates.filter { sizes[it] == smallest }
        val frame = current()
        val receipt = buildJsonObject {
            put("actualOwnedWindowIdentity", System.identityHashCode(actualWindow))
            put("actualRootRoute", routes.currentKey.toString()); put("actualFrameSerial", frame.serial)
            put("actualStack", JsonArray(routes.stack.map { JsonPrimitive(it.toString()) }))
            put("actualOwnedClientViewport", viewport().toString())
            put("requiredCompleteAnchors", JsonArray(anchors.map(::JsonPrimitive)))
            put("unfilteredCompleteCandidates", complete.size)
            put("unfilteredSmallestScopes", complete.count { completeSizes[it] == unfilteredMinimum })
            put("visibleCompleteCandidates", candidates.size); put("visibleSmallestScopes", scopes.size)
            put("candidates", JsonArray(complete.map { node -> buildJsonObject {
                put("identity", System.identityHashCode(node)); put("name", node.accessibleName)
                put("role", node.accessibleRole.toString()); put("states", node.accessibleStateSet.toString())
                put("descendantCount", completeSizes.getValue(node))
                put("unfilteredSmallest", completeSizes[node] == unfilteredMinimum)
                put("whollyInsideOwnedClient", whollyVisible(node))
                put("filteredSmallest", candidates.contains(node) && sizes[node] == smallest)
                put("actualNativeBounds", runCatching { controlBounds(node).toString() }
                    .getOrElse { "${it.javaClass.name}: ${it.message}" })
                put("requiredAnchors", JsonArray(anchors.map { anchor -> buildJsonObject {
                    put("anchor", anchor)
                    put("actualMatches", JsonArray(descendants(node).filter {
                        it.accessibleName.orEmpty().contains(anchor)
                    }.map { match -> buildJsonObject {
                        put("identity", System.identityHashCode(match)); put("name", match.accessibleName)
                        put("states", match.accessibleStateSet.toString())
                        put("actualNativeBounds", runCatching { controlBounds(match).toString() }
                            .getOrElse { "${it.javaClass.name}: ${it.message}" })
                        put("whollyInsideOwnedClient", whollyVisible(match))
                    } }))
                } }))
            } }))
        }
        Files.writeString(report.resolve("complete-original-scope-${frame.serial}-${completeScopeReceiptNumber++}.json"),
            receipt.toString() + "\n", CREATE_NEW, WRITE)
        check(candidates.isNotEmpty()) { "Complete actual original page scope absent inside owned client: $anchors" }
        check(scopes.size == 1) { "Expected one smallest complete visible original page scope, got ${scopes.size}: $anchors" }
        return scopes.single()
    }
    private fun controlBounds(node: AccessibleContext): Rectangle {
        check(node.accessibleStateSet.contains(AccessibleState.SHOWING))
        val component = requireNotNull(node.accessibleComponent)
        val point = requireNotNull(component.locationOnScreen)
        val size = component.size
        check(size.width > 0 && size.height > 0)
        return Rectangle(point.x, point.y, size.width, size.height)
    }
    private fun backInOriginalHeader(scope: AccessibleContext, anchor: AccessibleContext): AccessibleContext {
        val header = controlBounds(anchor)
        val matches = descendants(scope).filter { node ->
            hasLabel(node, "返回") && (node.accessibleAction?.accessibleActionCount ?: 0) == 1 &&
                node.accessibleStateSet.contains(AccessibleState.SHOWING) &&
                node.accessibleStateSet.contains(AccessibleState.ENABLED) && controlBounds(node).let { bounds ->
                    // Original AppTopBar title/navigation icon, or SettingsTree Return/Search
                    // row, share vertical centre. Outer Shell Return is a separate higher row.
                    kotlin.math.abs(bounds.centerY - header.centerY) <= maxOf(2.0, minOf(bounds.height, header.height) / 2.0) &&
                        bounds.maxX <= header.minX
                }
        }
        check(matches.size == 1) { "Expected one original same-header Back, got ${matches.size}" }
        return matches.single()
    }
    private fun click(label: String, surface: Window = actualWindow) = edt {
        check(surface.isShowing && owned(surface))
        check(originalAction(surface.accessibleContext, label).accessibleAction.doAccessibleAction(0))
    }
    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos()
        while (System.nanoTime() < deadline) { if (condition()) return; Thread.sleep(75) }
        edt {
            Window.getWindows().filter { it.isDisplayable && owned(it) }.forEachIndexed { index, window ->
                Files.writeString(report.resolve("timeout-$index-accessibility.tsv"), all(window).joinToString("\n") {
                    "${it.accessibleName}\t${it.accessibleRole}\t${it.accessibleStateSet}\t${it.accessibleAction?.accessibleActionCount ?: 0}"
                }, CREATE_NEW, WRITE)
            }
        }
        error("Actual original UI timed out: $description; frame=${latest.get()?.key}; stack=${routes.stack}")
    }
    private fun awaitPage(key: BiliPaiNavKey, after: Long, anchors: List<String>): DesktopOriginalRootValidationTap.Frame {
        val transitions = mutableListOf<JsonObject>()
        var previousRouteAndFrame: String? = null
        try {
            await("new active $key frame and original anchors $anchors") { edt {
                // The original action may update routes synchronously before its new draw.
                // Only that same-owned stale frame is pending; owner replacement never is.
                val frame = sameActualRootFrame()
                val identity = "${frame.key}|${frame.pagerHosted}|${routes.currentKey}"
                if (identity != previousRouteAndFrame) {
                    previousRouteAndFrame = identity
                    transitions.add(buildJsonObject {
                        put("observedFrameKey", frame.key.toString()); put("serial", frame.serial)
                        put("pagerHosted", frame.pagerHosted); put("actualPhysicalRoute", routes.currentKey.toString())
                        put("sameActualRootOwnerAndRoutes", true); put("matchesCurrentRoute", belongsToCurrentRoute(frame))
                        put("actualStack", JsonArray(routes.stack.map { JsonPrimitive(it.toString()) }))
                    })
                }
                belongsToCurrentRoute(frame) && frame.key == key && frame.serial > after &&
                    anchors.all { label -> names().any { it.contains(label) } }
            } }
        } finally {
            Files.writeString(report.resolve("transition-${key.javaClass.simpleName}-$after.json"),
                JsonArray(transitions).toString() + "\n", CREATE_NEW, WRITE)
        }
        Thread.sleep(1800)
        return edt { current().also { frame ->
            check(frame.key == key && frame.serial > after && anchors.all { label -> names().any { it.contains(label) } })
        } }
    }
    private fun record(id: String, values: Map<String, JsonElement> = emptyMap()) {
        val frame = edt { current() }
        observations.add(buildJsonObject {
            put("id", id); put("key", frame.key.toString()); put("serial", frame.serial)
            put("sameActualRootOwnerAndRoutes", true)
            values.forEach { (key, value) -> put(key, value) }
        })
    }
    private fun layer(component: Component, type: Class<*>): Any? {
        if (type.isInstance(component)) return component
        if (component is Container) component.components.forEach { layer(it, type)?.let { result -> return result } }
        return null
    }
    private fun capture(id: String, surface: Window = actualWindow, anchors: List<String>) = edt {
        val frame = current()
        check(surface.isShowing && owned(surface))
        val entries = all(surface)
        check(anchors.all { label -> entries.any { it.accessibleName.orEmpty().contains(label) } })
        Files.writeString(report.resolve("$id-accessibility.tsv"), entries.joinToString("\n") {
            "${it.accessibleName}\t${it.accessibleRole}\t${it.accessibleStateSet}\t${it.accessibleAction?.accessibleActionCount ?: 0}"
        }, CREATE_NEW, WRITE)
        val type = Class.forName("org.jetbrains.skiko.SkiaLayer")
        val actualLayer = requireNotNull(layer(surface, type)) { "Actual owned Skia layer absent" }
        val renderer = type.getMethod("getRenderApi").invoke(actualLayer).toString()
        check(renderer == "DIRECT3D") { "Default renderer changed: $renderer" }
        surface.javaClass.methods.firstOrNull { it.name == "renderImmediately" && it.parameterCount == 0 }?.invoke(surface)
        val bitmap = requireNotNull(type.getMethod("screenshot").invoke(actualLayer))
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
        Files.writeString(report.resolve("$id-frame.txt"), "${frame.key}\n${frame.serial}\n$renderer\n${surface.javaClass.name}\n" + routes.stack.joinToString("\n"), CREATE_NEW, WRITE)
    }
    private fun nativeComponents(component: Component): List<Component> {
        val result = mutableListOf(component)
        if (component is Container) component.components.forEach { result.addAll(nativeComponents(it)) }
        return result
    }
    /** Pinned ComposeSceneMediator installs its real AWT key listener on its contentComponent.
     * Find that unique owned renderer input without changing focusable flags or using a fake
     * dispatcher/OS-global input. The private Window alone may have no focused child. */
    private fun composeKeyboardComponent(surface: Window): Component {
        current(); check(surface.isShowing && surface.isDisplayable && owned(surface))
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
            current(); check(owned(surface) && surface.isShowing)
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
                current(); check(owned(surface) && surface.isShowing && composeKeyboardComponent(surface) === target)
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
            Files.writeString(report.resolve("owned-keyboard-focus-${observations.size}.json"),
                JsonArray(snapshots).toString() + "\n", CREATE_NEW, WRITE)
            Files.writeString(report.resolve("owned-keyboard-delivery-${observations.size}.json"), buildJsonObject {
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
    private fun settingsJson(): JsonObject {
        val file = DesktopLibrary.directoryForAccount(null).resolve("plugin-settings.json")
        check(Files.isRegularFile(file, NOFOLLOW_LINKS) && !Files.isSymbolicLink(file))
        return Json.parseToJsonElement(Files.readString(file)).jsonObject["settings"] as? JsonObject ?: JsonObject(emptyMap())
    }
    private fun diagnosticSurface(): Window? = edt {
        current()
        val bodies = listOf("Windows 版本仅在本地保存脱敏的基础错误与崩溃快照", "启用增强本地诊断", "关闭后不保留增强日志；基础错误与崩溃快照仍保留")
        val matches = Window.getWindows().filter { it.isShowing && owned(it) }.filter { window ->
            names(window).contains("帮助改进应用") && bodies.all { body -> names(window).any { it.contains(body) } } &&
                all(window).count { it.accessibleName == "确定" && (it.accessibleAction?.accessibleActionCount ?: 0) == 1 } == 1
        }
        check(matches.size <= 1); matches.singleOrNull()
    }
    private fun dismissOriginalDiagnosticPrompt() {
        check((settingsJson()["enhanced_diagnostic_logging_enabled"] as? JsonPrimitive)?.booleanOrNull != true)
        check((settingsJson()["crash_tracking_enabled"] as? JsonPrimitive)?.booleanOrNull != true)
        await("original first Home diagnostic prompt") { diagnosticSurface() != null }
        val surface = requireNotNull(diagnosticSurface())
        Thread.sleep(1800)
        capture("110-diagnostic-default-off", surface, listOf("帮助改进应用"))
        click("确定", surface)
        await("original diagnostic confirm saves disabled and dismisses") {
            diagnosticSurface() == null &&
                (settingsJson()["crash_tracking_consent_shown"] as? JsonPrimitive)?.booleanOrNull == true &&
                (settingsJson()["enhanced_diagnostic_logging_enabled"] as? JsonPrimitive)?.booleanOrNull == false &&
                (settingsJson()["crash_tracking_enabled"] as? JsonPrimitive)?.booleanOrNull == false
        }
        record("original-diagnostic-confirm-default-off", mapOf("switchInvoked" to JsonPrimitive(false)))
    }

    private fun viewport(): Rectangle {
        val pane = actualWindow.contentPane
        val point = pane.locationOnScreen
        return Rectangle(point.x, point.y, pane.width, pane.height)
    }
    private fun whollyVisible(node: AccessibleContext): Boolean {
        if (!node.accessibleStateSet.contains(AccessibleState.SHOWING)) return false
        val component = node.accessibleComponent ?: return false
        val point = component.locationOnScreen ?: return false
        val size = component.size
        return size.width > 0 && size.height > 0 && viewport().contains(Rectangle(point.x, point.y, size.width, size.height))
    }
    private fun wheel(rotation: Int) = edt {
        current()
        val actual = requireNotNull(layer(actualWindow, Class.forName("org.jetbrains.skiko.SkiaLayer"))) as Component
        actual.dispatchEvent(MouseWheelEvent(actual, MouseWheelEvent.MOUSE_WHEEL, System.currentTimeMillis(),
            0, actual.width / 2, actual.height / 2, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation))
    }
    /** LazyColumn is allowed to omit unrealized items. Missing is only pending; a positive
     * observation always requires a unique actual node completely inside the client viewport. */
    private fun scrollUntil(label: String, action: Boolean = false, direction: Int = 1): AccessibleContext {
        repeat(48) {
            val found = edt {
                current()
                val candidates = all().filter { node ->
                    (if (action) hasLabel(node, label) else node.accessibleName == label) &&
                        (!action || (node.accessibleAction?.accessibleActionCount ?: 0) == 1) && whollyVisible(node)
                }
                check(candidates.size <= 1) { "Ambiguous realized original Home control '$label': ${candidates.map { it.accessibleRole }}" }
                candidates.singleOrNull()
            }
            if (found != null) return found
            wheel(direction * 3); Thread.sleep(180)
        }
        error("Original lazy Home item never became completely visible: $label")
    }
    private fun clickVisible(label: String) {
        scrollUntil(label, action = true)
        edt { click(label) }
    }
    private fun assertHomePort(id: String, final: Boolean = false) {
        val actual = edt {
            current()
            val port = routes.root.environment.settings
            check(port === owner.retainer.current()?.environment?.settings) { "Home consumer port changed owner" }
            port.homeSettings.value
        }
        if (final) {
            check(actual.homeHeroCarouselEnabled && actual.homeHeroCarouselAutoplayEnabled)
            check(actual.homeWallpaperEffectMode == HomeWallpaperEffectMode.ORIGINAL)
            check(actual.homeWallpaperEffectScope == HomeWallpaperEffectScope.GLOBAL)
            check(!actual.isHeaderCollapseEnabled && actual.homeHeaderCollapseMode == HomeHeaderCollapseMode.OFF)
        }
        record(id, mapOf("readActualSameRootHomePort" to JsonPrimitive(true),
            "heroEnabled" to JsonPrimitive(actual.homeHeroCarouselEnabled),
            "heroAutoplay" to JsonPrimitive(actual.homeHeroCarouselAutoplayEnabled),
            "wallpaperEffect" to JsonPrimitive(actual.homeWallpaperEffectMode.value),
            "wallpaperScope" to JsonPrimitive(actual.homeWallpaperEffectScope.value),
            "headerCollapse" to JsonPrimitive(actual.isHeaderCollapseEnabled),
            "actualHeroMediaRendered" to JsonPrimitive(false), "actualWallpaperMediaRendered" to JsonPrimitive(false)))
    }
    private fun awaitPreference(name: String, expected: JsonElement) {
        await("original durable preference $name=$expected") { settingsJson()[name] == expected }
    }
    private fun setSwitch(label: String, key: String, expected: Boolean, readActual: () -> Boolean) {
        scrollUntil(label, action = true)
        if (edt { current(); readActual() } == expected) {
            // An untouched default can be absent on disk. Select the opposite through the
            // original control first; do not call absence a persisted successful write.
            edt { click(label) }
            awaitPreference(key, JsonPrimitive(!expected))
            await("actual Home opposite intent before selecting final $key") { edt { current(); readActual() != expected } }
            Thread.sleep(250)
        }
        clickVisible(label)
        awaitPreference(key, JsonPrimitive(expected))
        await("same actual Home preference consumer $key") { edt { current(); readActual() == expected } }
        record("switch-$key-$expected", mapOf("originalAccessibleActionOnly" to JsonPrimitive(true),
            "durablePreference" to JsonPrimitive(true), "sameActualHomePort" to JsonPrimitive(true)))
    }
    private fun choice(title: String, options: List<String>, selected: String, key: String, expected: Int) {
        clickVisible(title)
        var surface: Window? = null
        await("complete original '$title' options") { edt {
            current()
            val matches = Window.getWindows().filter { it.isShowing && owned(it) }.filter { candidate ->
                options.all { label -> all(candidate).count { node -> node.accessibleName == label &&
                    (node.accessibleAction?.accessibleActionCount ?: 0) == 1 } == 1 }
            }
            check(matches.size <= 1); surface = matches.singleOrNull(); surface != null
        } }
        Thread.sleep(1200)
        val actual = requireNotNull(surface)
        val identifier = "choice-$key-$expected-${observations.size}"
        capture(identifier, actual, options)
        edt {
            current(); check(owned(actual) && actual.isShowing)
            val choices = all(actual).filter { it.accessibleName == selected && (it.accessibleAction?.accessibleActionCount ?: 0) == 1 }
            check(choices.size == 1 && choices.single().accessibleStateSet.contains(AccessibleState.ENABLED))
            check(choices.single().accessibleAction.doAccessibleAction(0))
        }
        awaitPreference(key, JsonPrimitive(expected))
        await("original choice surface retired") { edt {
            !actual.isShowing || options.none { label -> all(actual).any { it.accessibleName == label && (it.accessibleAction?.accessibleActionCount ?: 0) == 1 } }
        } }
        record(identifier, mapOf("completeOriginalOptionGroup" to JsonPrimitive(true), "selected" to JsonPrimitive(selected)))
    }
    private fun originalHomeBack() = edt {
        current()
        val titles = all().filter { it.accessibleName == "首页设置" && it.accessibleRole == AccessibleRole.LABEL &&
            (it.accessibleAction?.accessibleActionCount ?: 0) == 0 && whollyVisible(it) }
        check(titles.size == 1) { "Original Home header title not unique" }
        val back = backInOriginalHeader(actualWindow.accessibleContext, titles.single())
        check(back.accessibleAction.doAccessibleAction(0))
    }
    private fun enterHomeSettings(after: Long): DesktopOriginalRootValidationTap.Frame {
        click("设置")
        val root = awaitPage(BiliPaiNavKey.Settings, after, listOf("首页与推荐", "搜索设置"))
        click("首页与推荐")
        val category = awaitPage(BiliPaiNavKey.Settings, root.serial, listOf("首页展示", "首页样式与推荐卡片", "推荐流与动态"))
        capture("category-${observations.size}", anchors = listOf("首页展示", "首页样式与推荐卡片"))
        click("首页样式与推荐卡片")
        val page = awaitPage(BiliPaiNavKey.Settings, category.serial, listOf("首页设置", "首页与列表", "展示样式"))
        record("natural-home-page-${observations.size}", mapOf("naturalOriginalSettingsCategoryEntry" to JsonPrimitive(true),
            "physicalRoute" to JsonPrimitive(routes.currentKey.toString())))
        return page
    }
    private fun allGroupsAndLastItem() {
        val groups = listOf("首页与列表", "首页壁纸与氛围", "内容与推荐流", "浏览交互与手势")
        for ((index, group) in groups.withIndex()) {
            scrollUntil(group)
            capture("group-$index", anchors = listOf("首页设置", group))
            record("group-$index", mapOf("section" to JsonPrimitive(group), "fullyVisibleInActualClient" to JsonPrimitive(true)))
        }
        scrollUntil("编辑资料按钮", action = true)
        capture("last-original-item", anchors = listOf("首页设置", "编辑资料按钮"))
        record("last-original-item", mapOf("fullyVisibleInActualClient" to JsonPrimitive(true), "actualWheelPath" to JsonPrimitive(true)))
    }
    /** The retained pager can expose other editors. Scope only the complete original search
     * page, with a real header and fully visible editor, never a global first/last editor. */
    private fun searchScope(): AccessibleContext = edt {
        check(current().key == BiliPaiNavKey.SettingsSearch && routes.currentKey == BiliPaiNavKey.SettingsSearch)
        val candidates = all().filter { node ->
            val children = descendants(node)
            children.any { it.accessibleName == "搜索结果" && it.accessibleRole == AccessibleRole.LABEL &&
                (it.accessibleAction?.accessibleActionCount ?: 0) == 0 && whollyVisible(it) } &&
                children.any { it.accessibleRole == AccessibleRole.TEXT && it.accessibleEditableText != null &&
                    it.accessibleStateSet.contains(AccessibleState.ENABLED) && whollyVisible(it) }
        }
        check(candidates.isNotEmpty()) { "Complete visible original SettingsSearch page absent" }
        val sizes = candidates.associateWith { descendants(it).size }
        val minimum = requireNotNull(sizes.values.minOrNull())
        val scopes = candidates.filter { sizes[it] == minimum }
        if (scopes.size != 1) {
            val diagnosis = buildJsonObject {
                put("actualRootRoute", routes.currentKey.toString()); put("actualFrameSerial", current().serial)
                put("completeVisibleSearchScopes", scopes.size)
                put("scopes", JsonArray(scopes.map { scope -> buildJsonObject {
                    put("descendantCount", descendants(scope).size)
                    put("editors", JsonArray(descendants(scope).filter { it.accessibleEditableText != null }.map { field -> buildJsonObject {
                        put("name", field.accessibleName); put("states", field.accessibleStateSet.toString())
                        put("bounds", runCatching { controlBounds(field).toString() }.getOrElse { it.toString() })
                        put("fullyVisible", runCatching { whollyVisible(field) }.getOrDefault(false))
                    } }))
                } }))
            }
            Files.writeString(report.resolve("ambiguous-original-search-scope.json"), diagnosis.toString(), CREATE_NEW, WRITE)
        }
        check(scopes.size == 1) { "Expected one complete visible original search page, got ${scopes.size}" }
        scopes.single()
    }
    private fun searchField(): AccessibleContext = edt {
        val text = descendants(searchScope()).filter { it.accessibleRole == AccessibleRole.TEXT &&
            it.accessibleEditableText != null && it.accessibleStateSet.contains(AccessibleState.ENABLED) && whollyVisible(it) }
        check(text.size == 1) { "Expected one visible original settings search editor, got ${text.size}" }
        text.single()
    }
    private fun searchFieldValue(): String = edt {
        val text = requireNotNull(searchField().accessibleText)
        (0 until text.charCount).joinToString("") { text.getAtIndex(javax.accessibility.AccessibleText.CHARACTER, it).orEmpty() }
    }
    private fun searchOverviewFocus(after: Long) {
        click("搜索设置")
        awaitPage(BiliPaiNavKey.SettingsSearch, after, listOf("搜索结果", "搜索设置功能"))
        edt { check(searchField().accessibleStateSet.contains(AccessibleState.ENABLED))
            searchField().accessibleEditableText.setTextContents("首页样式") }
        await("original Home scene category result in the same actual search page") { edt {
            current(); searchFieldValue() == "首页样式" && descendants(searchScope()).any {
                hasLabel(it, "首页与推荐") && it.accessibleRole == AccessibleRole.LABEL &&
                    (it.accessibleAction?.accessibleActionCount ?: 0) == 1 && whollyVisible(it)
            }
        } }
        val before = edt { current() }
        edt {
            val result = originalAction(searchScope(), "首页与推荐")
            check(result.accessibleRole == AccessibleRole.LABEL && whollyVisible(result))
            check(result.accessibleAction.doAccessibleAction(0))
        }
        // Original SettingsSearchScreen dispatches HOME_FEED to its category.
        // The actual original category entry submits HOME_OVERVIEW before opening Home.
        val categoryAnchors = listOf("首页展示", "首页样式与推荐卡片", "推荐流与动态")
        val category = awaitPage(BiliPaiNavKey.SettingsSearch, before.serial, categoryAnchors)
        check(SettingsSearchFocusController.request.value == null) { "Scene category search unexpectedly submitted a detail focus" }
        capture("search-home-category", anchors = categoryAnchors)
        record("search-home-category", mapOf("originalSearchEditableControl" to JsonPrimitive(true),
            "uniqueVisibleOriginalSearchPageScope" to JsonPrimitive(true),
            "originalSceneCategoryDispatch" to JsonPrimitive(true),
            "originalQuery" to JsonPrimitive("首页样式")))
        edt {
            val scope = completeScope(actualWindow.accessibleContext, categoryAnchors)
            val entry = originalAction(scope, "首页样式与推荐卡片")
            check(entry.accessibleRole == AccessibleRole.LABEL && whollyVisible(entry))
            check(entry.accessibleAction.doAccessibleAction(0))
        }
        awaitPage(BiliPaiNavKey.SettingsSearch, category.serial, listOf("首页设置", "首页与列表", "展示样式"))
        scrollUntil("首页与列表")
        check(SettingsSearchFocusController.request.value == null) { "Original overview focus request did not clear" }
        capture("search-overview-focus", anchors = listOf("首页设置", "首页与列表", "展示样式"))
        record("search-overview-focus", mapOf("originalSearchEditableControl" to JsonPrimitive(true),
            "uniqueVisibleOriginalSearchPageScope" to JsonPrimitive(true),
            "originalOverviewOnlyFocus" to JsonPrimitive(true), "focusRequestClearedByActualPage" to JsonPrimitive(true),
            "actualOriginalCategoryOverviewEntry" to JsonPrimitive(true)))
        val page = edt { current() }
        escape(actualWindow)
        val returnedCategory = awaitPage(BiliPaiNavKey.SettingsSearch, page.serial, categoryAnchors)
        record("owned-escape-home-page-to-category", mapOf("actualOwnedComposeKeyListener" to JsonPrimitive(true),
            "originalSceneCategoryReturned" to JsonPrimitive(true)))
        escape(actualWindow)
        awaitPage(BiliPaiNavKey.SettingsSearch, returnedCategory.serial, listOf("搜索结果"))
        check(edt { searchFieldValue() == "首页样式" }) { "Original query changed after owned Home/category Back" }
        record("owned-escape-home-page", mapOf("actualOwnedComposeKeyListener" to JsonPrimitive(true),
            "originalQueryRestored" to JsonPrimitive(true), "actualHomeAndCategoryEscapes" to JsonPrimitive(2)))
    }
    private fun leaveSettingsToHome() {
        var frame = edt { current() }
        repeat(4) { index ->
            if (frame.key == BiliPaiNavKey.Home) return
            escape(actualWindow)
            await("owned Escape route draw") { edt { val fresh = sameActualRootFrame(); belongsToCurrentRoute(fresh) && fresh.serial > frame.serial } }
            frame = edt { current() }
            record("exit-settings-owned-escape-${observations.size}-$index", mapOf("actualOwnedComposeKeyListener" to JsonPrimitive(true)))
        }
        check(frame.key == BiliPaiNavKey.Home) { "Actual Esc did not return original Settings stack to Home" }
    }
    private val pattern = intArrayOf(0x5a, 0xa5, 0x36, 0xc9, 0x71, 0x8e, 0x2d, 0xd2)
    private val magenta = 0xe614e6
    private val green = 0x14e614
    private fun privateAssets(): Path {
        val local = Path.of(requireNotNull(System.getenv("LOCALAPPDATA"))).toRealPath()
        check(local.fileName.toString().startsWith("BiliPai-v025-root-routes-"))
        check(Files.readString(local.resolve(".bilipai-root-validation")) == System.getProperty("bilipai.rootValidationToken"))
        return local.resolve("home-wallpaper-ui-assets")
    }
    private fun createPrivatePngs() {
        val folder = privateAssets()
        check(!Files.exists(folder)); Files.createDirectory(folder)
        for (phase in listOf("A", "B")) {
            val image = BufferedImage(1024, 1024, BufferedImage.TYPE_INT_RGB)
            try {
                for (y in 0 until 1024) for (x in 0 until 1024) {
                    val bit = ((pattern[y / 128] ushr (7 - x / 128)) and 1) == 1
                    image.setRGB(x, y, if (bit xor (phase == "B")) magenta else green)
                }
                Files.newOutputStream(folder.resolve("wallpaper-$phase.png"), CREATE_NEW, WRITE).use {
                    check(ImageIO.write(image, "png", it))
                }
            } finally { image.flush() }
        }
        record("private-two-png-fixture-assets", mapOf("fixtureAssetsOnly" to JsonPrimitive(true),
            "noPreferenceOrAccountSeed" to JsonPrimitive(true), "identicalColorHistogram" to JsonPrimitive(true)))
    }
    private fun homeFiles(): Set<Path> {
        val dir = DesktopLibrary.directoryForAccount(null).resolve("home_wallpaper").toAbsolutePath().normalize()
        if (!Files.exists(dir, NOFOLLOW_LINKS)) return emptySet()
        check(Files.isDirectory(dir, NOFOLLOW_LINKS) && !Files.isSymbolicLink(dir))
        return Files.list(dir).use { files -> files.toList().map { file ->
            check(Files.isRegularFile(file, NOFOLLOW_LINKS) && !Files.isSymbolicLink(file)); file
        }.toSet() }
    }
    private fun rawSha(file: Path): String = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }
    private fun pickerSurface(): Window? = edt {
        current()
        val matches = Window.getWindows().filter { it.isShowing && owned(it) }.filter { candidate ->
            names(candidate).any { it == "选择首页壁纸" } &&
                all(candidate).count { hasLabel(it, "设为首页壁纸") && (it.accessibleAction?.accessibleActionCount ?: 0) == 1 } == 1
        }
        check(matches.size <= 1); matches.singleOrNull()
    }
    private fun openPicker(): Window {
        clickVisible("选择首页壁纸")
        await("whole original HOME wallpaper modal") { pickerSurface() != null }
        return requireNotNull(pickerSurface())
    }
    /** The real production JFileChooser owns its existing modal pump. Launch only the
     * original accessibility action asynchronously so a modal implementation cannot
     * block the worker's next observation. This is never approveSelection/callback IO. */
    private fun openActualChooser(surface: Window): Pair<JDialog, JFileChooser> {
        val failure = AtomicReference<Throwable?>()
        val action = edt {
            current(); check(surface.isShowing && owned(surface))
            originalAction(surface.accessibleContext, "从相册选择")
        }
        EventQueue.invokeLater {
            try {
                current(); check(surface.isShowing && owned(surface))
                check(originalAction(surface.accessibleContext, "从相册选择") === action)
                check(action.accessibleAction.doAccessibleAction(0))
            } catch (error: Throwable) { failure.set(error) }
        }
        var actual: Pair<JDialog, JFileChooser>? = null
        await("actual production owned JFileChooser") {
            failure.get()?.let { throw it }
            edt {
                current()
                val matches = Window.getWindows().filterIsInstance<JDialog>().filter {
                    it.isShowing && it.isDisplayable && owned(it) && it.title == "选择壁纸图片 / 视频"
                }
                check(matches.size <= 1)
                actual = matches.singleOrNull()?.let { dialog ->
                    val choices = nativeComponents(dialog).filterIsInstance<JFileChooser>()
                    check(choices.size == 1)
                    choices.single().also { chooser ->
                        check(chooser.dialogType == JFileChooser.OPEN_DIALOG && !chooser.isMultiSelectionEnabled &&
                            chooser.fileSelectionMode == JFileChooser.FILES_ONLY)
                        check(SwingUtilities.getWindowAncestor(chooser) === dialog)
                        check(dialog.owner === actualWindow && dialog.isModal)
                    }.let { chooser -> dialog to chooser }
                }
                actual != null
            }
        }
        return requireNotNull(actual)
    }
    private fun chooserSnapshot(id: String, dialog: JDialog, chooser: JFileChooser) = edt {
        current(); check(owned(dialog) && dialog.isShowing && SwingUtilities.getWindowAncestor(chooser) === dialog)
        Files.writeString(report.resolve("$id-accessibility.tsv"), all(dialog).joinToString("\n") {
            "${it.accessibleName}\t${it.accessibleRole}\t${it.accessibleStateSet}\t${it.accessibleAction?.accessibleActionCount ?: 0}"
        }, CREATE_NEW, WRITE)
        // Actual Swing component paint is diagnostic only; it is not a GPU/OS screenshot.
        val image = BufferedImage(dialog.width, dialog.height, BufferedImage.TYPE_INT_ARGB)
        try {
            val graphics = image.createGraphics()
            try { dialog.printAll(graphics) } finally { graphics.dispose() }
            Files.newOutputStream(report.resolve("$id-swing-diagnostic.png"), CREATE_NEW, WRITE).use {
                check(ImageIO.write(image, "png", it))
            }
        } finally { image.flush() }
        Files.writeString(report.resolve("$id-native-chooser.json"), buildJsonObject {
            put("scope", "ACTUAL_PRODUCTION_OWNED_SWING_JFILECHOOSER")
            put("windowIdentity", System.identityHashCode(dialog)); put("chooserIdentity", System.identityHashCode(chooser))
            put("actualMainOwnerWindow", dialog.owner === actualWindow); put("modal", dialog.isModal)
            put("actualSelectedFile", chooser.selectedFile?.absolutePath ?: "null")
            put("diagnosticPaintOnly", true); put("osScreenCaptureClaimed", false)
            put("bounds", JsonArray(listOf(dialog.x, dialog.y, dialog.width, dialog.height).map { JsonPrimitive(it) }))
        }.toString(), CREATE_NEW, WRITE)
    }
    private fun normalizedUiLabel(value: String): String = value.trim().trimEnd(':', '：').replace("&", "")
    private fun nativeChooserButton(chooser: JFileChooser, cancel: Boolean): JButton {
        val label = if (cancel) UIManager.getString("FileChooser.cancelButtonText", chooser.locale)
            else chooser.approveButtonText ?: UIManager.getString("FileChooser.openButtonText", chooser.locale)
        check(!label.isNullOrBlank())
        val buttons = nativeComponents(chooser).filterIsInstance<JButton>().filter {
            it.isShowing && it.isEnabled && normalizedUiLabel(it.text.orEmpty()) == normalizedUiLabel(label)
        }
        check(buttons.size == 1) { "Actual localized ${if (cancel) "Cancel" else "Open"} button not unique: $label" }
        return buttons.single()
    }
    private fun operateChooser(dialog: JDialog, chooser: JFileChooser, source: Path?, id: String) {
        chooserSnapshot(id, dialog, chooser)
        edt {
            current(); check(dialog.isShowing && owned(dialog))
            if (source != null) {
                check(Files.isRegularFile(source, NOFOLLOW_LINKS) && !Files.isSymbolicLink(source))
                val label = requireNotNull(UIManager.getString("FileChooser.fileNameLabelText", chooser.locale))
                val labels = nativeComponents(chooser).filterIsInstance<JLabel>().filter {
                    it.labelFor is JTextField && normalizedUiLabel(it.text.orEmpty()) == normalizedUiLabel(label)
                }
                check(labels.size == 1) { "Real localized File-name editor label absent or ambiguous: $label" }
                val editor = labels.single().labelFor as JTextField
                check(editor.isShowing && editor.isEnabled && editor.isEditable)
                check(SwingUtilities.getWindowAncestor(editor) === dialog)
                requireNotNull(editor.accessibleContext.accessibleEditableText).setTextContents(source.toAbsolutePath().toString())
                check(editor.text == source.toAbsolutePath().toString())
            }
            val button = nativeChooserButton(chooser, cancel = source == null)
            val action = requireNotNull(button.accessibleContext.accessibleAction)
            check(action.accessibleActionCount == 1 && action.doAccessibleAction(0))
        }
        await("original JFileChooser modal disposed after its real control") { edt { !dialog.isDisplayable && !dialog.isShowing } }
        if (source != null) check(edt { chooser.selectedFile?.toPath()?.toAbsolutePath()?.normalize() } == source.toAbsolutePath().normalize())
        record(id, mapOf("actualOwnedJFileChooser" to JsonPrimitive(true),
            "originalNativeWidgetActionsOnly" to JsonPrimitive(true), "cancel" to JsonPrimitive(source == null),
            "fixtureSelectedFileAssigned" to JsonPrimitive(false), "directApproveSelectionInvoked" to JsonPrimitive(false)))
    }
    private fun cancelChooser() {
        val before = homeFiles(); val originalUri = settingsJson()["home_wallpaper_uri"]
        val surface = openPicker(); val (dialog, chooser) = openActualChooser(surface)
        operateChooser(dialog, chooser, null, "chooser-original-cancel")
        await("canceled original picker remains owned and original home URI unchanged") {
            pickerSurface() === surface && settingsJson()["home_wallpaper_uri"] == originalUri && homeFiles() == before
        }
        capture("picker-cancel-returned", surface, listOf("选择首页壁纸", "设为首页壁纸"))
        click("关闭", surface)
        await("whole original sheet close after cancel") { pickerSurface() == null }
        check(homeFiles() == before)
        record("chooser-cancel-no-import-no-pref-change", mapOf("actualFilesUnchanged" to JsonPrimitive(true), "originalUriUnchanged" to JsonPrimitive(true)))
    }
    private fun importAndSave(phase: String) {
        val source = privateAssets().resolve("wallpaper-$phase.png").toRealPath()
        val expected = rawSha(source); val before = homeFiles()
        val surface = openPicker(); val (dialog, chooser) = openActualChooser(surface)
        operateChooser(dialog, chooser, source, "chooser-original-open-$phase")
        var preview: Path? = null
        await("real imported preview, raw PNG exact and original save action enabled") {
            val added = homeFiles() - before
            check(added.size <= 1) { "Unexpected multiple preview files before saving: $added" }
            preview = added.singleOrNull()
            preview != null && preview!!.fileName.toString().startsWith("wallpaper_") && rawSha(preview!!) == expected &&
                edt { current(); pickerSurface() === surface && originalAction(surface.accessibleContext, "设为首页壁纸").accessibleStateSet.contains(AccessibleState.ENABLED) }
        }
        Thread.sleep(1800)
        capture("import-preview-$phase", surface, listOf("选择首页壁纸", "设为首页壁纸"))
        check(edt { all(surface).none { hasLabel(it, "同时保存到相册") && (it.accessibleAction?.accessibleActionCount ?: 0) == 1 } })
        click("设为首页壁纸", surface)
        var durable: Path? = null
        await("original save completes, original sheet disposes, and preview lease is removed") {
            val text = (settingsJson()["home_wallpaper_uri"] as? JsonPrimitive)?.content ?: return@await false
            if (!text.startsWith("file:")) return@await false
            durable = Path.of(java.net.URI(text)).toAbsolutePath().normalize()
            val ready = requireNotNull(durable)
            pickerSurface() == null && ready != preview && ready.parent == DesktopLibrary.directoryForAccount(null).resolve("home_wallpaper").toAbsolutePath().normalize() &&
                ready !in before && Files.isRegularFile(ready, NOFOLLOW_LINKS) && !Files.isSymbolicLink(ready) &&
                rawSha(ready) == expected && !Files.exists(requireNotNull(preview), NOFOLLOW_LINKS) && homeFiles() == before + setOf(ready) &&
                edt { current(); routes.root.environment.settings.homeWallpaperUri.value == text }
        }
        record("original-import-save-$phase", mapOf("actualNativeChooser" to JsonPrimitive(true),
            "actualSourceBytesPreserved" to JsonPrimitive(true), "sourceSha256" to JsonPrimitive(expected),
            "actualPreviewLeaseDeleted" to JsonPrimitive(true), "previewFile" to JsonPrimitive(requireNotNull(preview).toString()),
            "durableFile" to JsonPrimitive(requireNotNull(durable).toString()),
            "sameActualRootHomeUriFlow" to JsonPrimitive(true), "galleryWriteRequested" to JsonPrimitive(false)))
    }
    private fun actualHomeCapture(id: String) {
        val beforeHomeReturn = edt { current().serial }
        leaveSettingsToHome()
        val frame = awaitPage(BiliPaiNavKey.Home, after = beforeHomeReturn, anchors = listOf("推荐"))
        check(frame.handle === owner && frame.routes === routes)
        // Original Coil crossfade is 180ms; palette/network state is allowed to settle.
        Thread.sleep(3500)
        capture(id, anchors = listOf("推荐"))
        record(id, mapOf("actualHomeFrame" to JsonPrimitive(true), "defaultRenderer" to JsonPrimitive("DIRECT3D"),
            "pixelOracleRequiredExternally" to JsonPrimitive(true), "uriReadbackIsNotRenderProof" to JsonPrimitive(true)))
    }
    private fun prepareWallpaperControls(home: DesktopOriginalRootValidationTap.Frame) {
        enterHomeSettings(home.serial)
        choice("首页壁纸效果", listOf("关闭", "柔和", "强模糊", "原图"), "原图", "home_wallpaper_effect_mode", HomeWallpaperEffectMode.ORIGINAL.value)
        // Original single-choice dispatch deliberately skips an already selected default.
        // Select a different original value, then HOME_ONLY, to prove a real explicit write.
        check(edt { current(); routes.root.environment.settings.homeSettings.value.homeWallpaperEffectScope == HomeWallpaperEffectScope.HOME_ONLY })
        choice("壁纸作用范围", listOf("仅首页", "首页与聊天"), "首页与聊天", "home_wallpaper_effect_scope", HomeWallpaperEffectScope.GLOBAL.value)
        await("same actual Home consumer observes original GLOBAL scope") { edt {
            current(); routes.root.environment.settings.homeSettings.value.homeWallpaperEffectScope == HomeWallpaperEffectScope.GLOBAL
        } }
        choice("壁纸作用范围", listOf("仅首页", "首页与聊天"), "仅首页", "home_wallpaper_effect_scope", HomeWallpaperEffectScope.HOME_ONLY.value)
        await("same actual Home consumer observes explicitly written HOME_ONLY scope") { edt {
            current(); routes.root.environment.settings.homeSettings.value.homeWallpaperEffectScope == HomeWallpaperEffectScope.HOME_ONLY
        } }
        record("original-default-home-scope-real-transition", mapOf(
            "originalGlobalThenHomeOnlyAccessibleChoices" to JsonPrimitive(true),
            "explicitCanonicalZeroRequired" to JsonPrimitive(true), "sameActualHomePort" to JsonPrimitive(true)))
        setSwitch("首页顶部轮播封面", "home_hero_carousel_enabled", false) { routes.root.environment.settings.homeSettings.value.homeHeroCarouselEnabled }
    }
    private fun fresh(home: DesktopOriginalRootValidationTap.Frame) {
        createPrivatePngs(); prepareWallpaperControls(home); cancelChooser()
        importAndSave("A"); actualHomeCapture("home-wallpaper-A")
        enterHomeSettings(edt { current().serial }); importAndSave("B"); actualHomeCapture("home-wallpaper-B")
        enterHomeSettings(edt { current().serial })
        choice("首页壁纸效果", listOf("关闭", "柔和", "强模糊", "原图"), "关闭", "home_wallpaper_effect_mode", HomeWallpaperEffectMode.OFF.value)
        actualHomeCapture("home-wallpaper-OFF")
        enterHomeSettings(edt { current().serial })
        choice("首页壁纸效果", listOf("关闭", "柔和", "强模糊", "原图"), "原图", "home_wallpaper_effect_mode", HomeWallpaperEffectMode.ORIGINAL.value)
        actualHomeCapture("home-wallpaper-B-restored")
    }
    private fun cold() {
        val expected = rawSha(privateAssets().resolve("wallpaper-B.png"))
        val uri = requireNotNull(settingsJson()["home_wallpaper_uri"]).jsonPrimitive.content
        check(rawSha(Path.of(java.net.URI(uri))) == expected)
        edt {
            current(); val port = routes.root.environment.settings
            check(port === owner.retainer.current()?.environment?.settings && port.homeWallpaperUri.value == uri)
            check(port.homeSettings.value.homeWallpaperEffectMode == HomeWallpaperEffectMode.ORIGINAL)
            check(port.homeSettings.value.homeWallpaperEffectScope == HomeWallpaperEffectScope.HOME_ONLY)
            check(!port.homeSettings.value.homeHeroCarouselEnabled)
        }
        Thread.sleep(3500); capture("cold-home-wallpaper-B", anchors = listOf("推荐"))
        record("cold-home-wallpaper-B", mapOf("sameActualRootHomeUriFlow" to JsonPrimitive(true),
            "actualHomeFrame" to JsonPrimitive(true), "pixelOracleRequiredExternally" to JsonPrimitive(true),
            "storedPngRawSha256" to JsonPrimitive(expected)))
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
        else actions.requireAcknowledged(true)
        DesktopOriginalRootValidationTap.install { latest.set(it) }.use {
            val worker = Thread({
                try {
                    val home = if (phase == "fresh") actions.acceptFreshAgreementToHome(health) else actions.awaitHome()
                    actions.awaitActualHealth(health)
                    owner = home.handle; routes = home.routes; actualWindow = edt { mainWindow() }
                    if (phase == "fresh") dismissOriginalDiagnosticPrompt() else {
                        check((settingsJson()["crash_tracking_consent_shown"] as? JsonPrimitive)?.booleanOrNull == true)
                        check(diagnosticSurface() == null)
                        check((settingsJson()["enhanced_diagnostic_logging_enabled"] as? JsonPrimitive)?.booleanOrNull == false)
                        check((settingsJson()["crash_tracking_enabled"] as? JsonPrimitive)?.booleanOrNull == false)
                    }
                    if (phase == "fresh") fresh(home) else cold()
                    actions.requireAcknowledged(true)
                    val receipt = buildJsonObject {
                        put("schema", 1); put("phase", phase); put("actualMainReturned", false)
                        put("observationPhase", "BEFORE_REQUESTED_ACTUAL_EXIT"); put("allPreExitAssertionsPassed", true)
                        put("externalExitAndFinalPersistenceRequired", true); put("defaultRenderer", "DIRECT3D")
                        put("actualMainInvocations", 1); put("sameLiveRootAndWindow", true)
                        put("realAccountUsed", false); put("fixturePreferenceWrites", false); put("physicalStackWrittenByFixture", false)
                        put("actualHeroMediaRendered", false); put("actualWallpaperMediaRendered", false)
                         put("wallpaperPixelOracleRequiredExternally", true); put("staticPngOnly", true)
                        put("nativeWallpaperChooserExecuted", phase == "fresh"); put("compositionAbaRuntimeExecuted", false)
                         put("actualMainImageRetirementExecuted", false); put("actualMainLateChooserRejectionExecuted", false)
                        put("allHomeFeaturesAccepted", false); put("newExeDeployed", false)
                        put("observations", JsonArray(observations.toList()))
                    }
                    Files.writeString(report.resolve("observations.json"), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), receipt), CREATE_NEW, WRITE)
                    completed.set(true)
                    actions.closeOwnedWindow(edt { current() })
                } catch (failure: Throwable) {
                    failure.printStackTrace()
                    runCatching { capture("failure-home", anchors = emptyList()) }
                    runCatching { Files.writeString(report.resolve("failure-observations.json"), JsonArray(observations.toList()).toString(), CREATE_NEW, WRITE) }
                    kotlin.system.exitProcess(91)
                }
            }, "Original native HOME wallpaper actual Root observer")
            worker.isDaemon = true; worker.start()
            com.bilipai.desktop.main(arrayOf("--update-health-file", health.toString(), "--update-health-token", args[3]))
            check(completed.get()) { "Actual Main returned before HomeSettings assertions" }
        }
    }
}
