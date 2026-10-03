package com.bilipai.desktop.ui

import androidx.lifecycle.Lifecycle
import androidx.compose.ui.semantics.getOrNull
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.DesktopLibrary
import com.android.purebilibili.feature.settings.*
import com.android.purebilibili.feature.agreement.UserAgreementText
import com.android.purebilibili.feature.onboarding.UserAgreementClause
import javax.accessibility.AccessibleRole
import java.awt.event.MouseEvent
import java.awt.event.MouseAdapter
import java.awt.event.InputEvent
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
import kotlinx.serialization.json.*

/** Opt-in actual Main guest UI. Accessibility identifies unique original controls;
 * actual owned Compose AWT mouse/keyboard input invokes the production handlers.
 * External browser/default-app/community links and APK/Windows installation are never
 * invoked. Search action results must land on the real owning category without invoking
 * the action. Durable agreement flags are not cleared to simulate replay.
 */
object OriginalSystemAboutRootUiFixture {
    private val latest = AtomicReference<DesktopOriginalRootValidationTap.Frame?>()
    private val completed = AtomicBoolean(false)
    private lateinit var owner: DesktopReadyOriginalRootHandle
    private lateinit var routes: DesktopOriginalRootRouteAssembly
    private lateinit var actualWindow: JFrame
    private lateinit var report: Path
    private lateinit var actions: OriginalOnboardingUiActions
    private val observations = mutableListOf<JsonObject>()
    private var mouseSerial = 0
    private var lastOriginalModalDismissedNanos = 0L

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
    private fun completeScope(root: AccessibleContext, anchors: List<String>): AccessibleContext {
        val candidates = descendants(root).filter { node ->
            // A retained exit entry can still report SHOWING after it moves outside the
            // actual client. Require the complete original page's native viewport inside
            // this owned Window; two complete visible pages must still fail uniqueness.
            visible(node) && anchors.all { anchor -> descendants(node).any { it.accessibleName.orEmpty().contains(anchor) } }
        }
        check(candidates.isNotEmpty()) { "Complete actual original page scope absent: $anchors" }
        val sizes = candidates.associateWith { descendants(it).size }
        val smallest = requireNotNull(sizes.values.minOrNull())
        val scopes = candidates.filter { sizes[it] == smallest }
        if (scopes.size != 1) {
            val identities = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<AccessibleContext, Boolean>())
            identities.addAll(scopes)
            Files.writeString(report.resolve("ambiguous-complete-original-scope.json"), buildJsonObject {
                put("actualOwnedWindowIdentity", System.identityHashCode(actualWindow))
                put("actualRootRoute", routes.currentKey.toString())
                put("actualFrameSerial", current().serial)
                put("anchors", JsonArray(anchors.map(::JsonPrimitive)))
                put("minimalCandidates", scopes.size)
                put("distinctContextReferences", identities.size)
                put("candidates", JsonArray(scopes.map { scope -> buildJsonObject {
                    put("identity", System.identityHashCode(scope))
                    put("sameReferenceOccurrences", scopes.count { it === scope })
                    put("contextClass", scope.javaClass.name)
                    put("name", scope.accessibleName)
                    put("role", scope.accessibleRole.toString())
                    put("states", scope.accessibleStateSet.toString())
                    put("bounds", runCatching { controlBounds(scope).toString() }.getOrElse { it.toString() })
                    put("descendantCount", descendants(scope).size)
                    put("anchorNodes", JsonArray(descendants(scope).filter { node ->
                        anchors.any { node.accessibleName.orEmpty().contains(it) }
                    }.map { node -> buildJsonObject {
                        put("identity", System.identityHashCode(node)); put("name", node.accessibleName)
                        put("role", node.accessibleRole.toString()); put("states", node.accessibleStateSet.toString())
                        put("bounds", runCatching { controlBounds(node).toString() }.getOrElse { it.toString() })
                    } }))
                } }))
            }.toString() + "\n", CREATE_NEW, WRITE)
        }
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
    /** Deliver Escape only into a focus owner in the same owned JVM window. Never Robot/OS global input. */
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
        // Semantic dialog disappearance is not a guarantee that the original inline
        // overlay/input transition is already retired. Await a settled actual Home
        // before acquiring its next original control; no navigation/action is retried.
        Thread.sleep(1800)
        check(edt { current().key == BiliPaiNavKey.Home && routes.currentKey == BiliPaiNavKey.MainHost })
        check(diagnosticSurface() == null)
        capture("115-settled-post-diagnostic-home", anchors = listOf("推荐", "设置"))
    }
    private val aboutKey = BiliPaiNavKey.SettingsCategory(SettingsRootCategory.SYSTEM_ABOUT)
    private val aboutAnchors = listOf("系统与关于", "用户协议与隐私政策", "源码与验证", "趣味彩蛋", "官方渠道：GitHub · 频道 · 群组")
    private val searchTargets = listOf(SettingsSearchTarget.OPEN_SOURCE_HOME, SettingsSearchTarget.CHECK_UPDATE,
        SettingsSearchTarget.VIEW_RELEASE_NOTES, SettingsSearchTarget.REPLAY_ONBOARDING, SettingsSearchTarget.OPEN_LINKS,
        SettingsSearchTarget.TELEGRAM, SettingsSearchTarget.TWITTER, SettingsSearchTarget.DISCLAIMER, SettingsSearchTarget.DONATE)
    /** The original first-Home diagnostic helper shares the same owned modal path.
     * Exact original control identity, current Root, native bounds and renderer input
     * remain mandatory for both inline Main and owned native dialog surfaces. */
    private fun click(label: String, surface: Window = actualWindow) {
        val control = edt {
            current(); check(surface.isShowing && owned(surface))
            originalAction(surface.accessibleContext, label)
        }
        mouse(control, surface)
    }
    private fun input(surface: Window): Component = edt { composeKeyboardComponent(surface).also { target ->
        check(target.mouseListeners.any { it.javaClass.name.startsWith("androidx.compose.ui.scene.ComposeSceneMediator\$") })
    } }
    /** One genuine owned pointer sequence, never retrying the original action.
     * A passive listener is appended after the installed Compose listener and removed
     * before return. Its receipt proves delivery only; the page/Store oracles remain
     * the authority on whether the original handler performed the requested action. */
    private fun mouse(node: AccessibleContext, surface: Window = actualWindow) {
        val attempt = mouseSerial++
        val target = input(surface)
        val snapshots = mutableListOf<JsonObject>()
        val received = mutableListOf<JsonObject>()
        var pressed: MouseEvent? = null
        var released: MouseEvent? = null
        var installed = false
        fun rectangle(bounds: Rectangle) = buildJsonObject {
            put("x", bounds.x); put("y", bounds.y)
            put("width", bounds.width); put("height", bounds.height)
        }
        fun snapshot(id: String, bounds: Rectangle?, point: java.awt.Point?) = buildJsonObject {
            check(EventQueue.isDispatchThread())
            val frame = sameActualRootFrame()
            put("id", id); put("sameActualRootOwnerAndRoutes", true)
            put("frameKey", frame.key.toString()); put("serial", frame.serial)
            put("frameMatchesPhysicalRoute", belongsToCurrentRoute(frame))
            put("physicalRoute", routes.currentKey.toString())
            put("physicalStack", JsonArray(routes.stack.map { JsonPrimitive(it.toString()) }))
            put("originalName", node.accessibleName.orEmpty())
            put("originalRole", node.accessibleRole.toString())
            put("originalActionCount", node.accessibleAction?.accessibleActionCount ?: 0)
            put("originalControlIdentity", System.identityHashCode(node))
            put("actualSurfaceClass", surface.javaClass.name)
            put("actualSurfaceIdentity", System.identityHashCode(surface))
            put("actualInputClass", target.javaClass.name)
            put("actualInputIdentity", System.identityHashCode(target))
            put("inputBelongsToRequestedSurface", SwingUtilities.getWindowAncestor(target) === surface)
            put("surfaceShowing", surface.isShowing); put("inputShowing", target.isShowing)
            put("activeWindowIsRequestedSurface", KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow === surface)
            put("surfaceNativeBounds", rectangle(surface.bounds))
            bounds?.let { put("originalNativeBounds", rectangle(it)) }
            point?.let { put("inputX", it.x); put("inputY", it.y) }
            put("actualMouseListenerNames", JsonArray(target.mouseListeners.map { JsonPrimitive(it.javaClass.name) }))
            if (id == "after-release" && node.accessibleName == "搜索设置")
                put("ownedSearchAfterReleaseDiagnostic", ownedSearchDiagnostic(node, surface, target, point))
        }
        val receipt = object : MouseAdapter() {
            private fun observe(event: MouseEvent) {
                if (event !== pressed && event !== released) return
                received.add(buildJsonObject {
                    put("id", event.id); put("sameEventIdentity", true)
                    put("sourceIsActualOwnedInput", event.component === target)
                    put("consumedAfterInstalledComposeListener", event.isConsumed)
                    put("x", event.x); put("y", event.y); put("button", event.button)
                    put("modifiersEx", event.modifiersEx); put("clickCount", event.clickCount)
                })
            }
            override fun mousePressed(event: MouseEvent) = observe(event)
            override fun mouseReleased(event: MouseEvent) = observe(event)
        }
        val geometry = edt {
            current(); check(owned(surface) && surface.isShowing)
            check((node.accessibleAction?.accessibleActionCount ?: 0) == 1 &&
                node.accessibleStateSet.contains(AccessibleState.ENABLED))
            val bounds = controlBounds(node)
            val pane = (surface as RootPaneContainer).contentPane
            val origin = pane.locationOnScreen
            check(Rectangle(origin.x, origin.y, pane.width, pane.height).contains(bounds)) { "Original control not wholly visible: $bounds" }
            val point = java.awt.Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)
            SwingUtilities.convertPointFromScreen(point, target)
            check(target.contains(point))
            snapshots.add(snapshot("before-input", bounds, point))
            bounds to point
        }
        val (bounds, point) = geometry
        try {
            edt {
                current(); check(target.isShowing && SwingUtilities.getWindowAncestor(target) === surface)
                check(controlBounds(node) == bounds) { "Original control moved before pointer press" }
                target.addMouseListener(receipt); installed = true
                val now = System.currentTimeMillis()
                target.dispatchEvent(MouseEvent(target, MouseEvent.MOUSE_MOVED, now, 0, point.x, point.y, 0, false))
                val down = MouseEvent(target, MouseEvent.MOUSE_PRESSED, now + 1, InputEvent.BUTTON1_DOWN_MASK,
                    point.x, point.y, 1, false, MouseEvent.BUTTON1)
                pressed = down; target.dispatchEvent(down)
                snapshots.add(snapshot("after-press", bounds, point))
            }
            Thread.sleep(100)
            edt {
                current(); check(surface.isShowing && target.isShowing && SwingUtilities.getWindowAncestor(target) === surface)
                val up = MouseEvent(target, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0,
                    point.x, point.y, 1, false, MouseEvent.BUTTON1)
                released = up; target.dispatchEvent(up)
                snapshots.add(snapshot("after-release", bounds, point))
            }
        } finally {
            edt {
                if (installed) target.removeMouseListener(receipt)
                check(target.mouseListeners.none { it === receipt })
            }
            Files.writeString(report.resolve("owned-mouse-input-$attempt.json"), buildJsonObject {
                put("scope", "ACTUAL_OWNED_COMPOSE_AWT_MOUSE_INPUT")
                put("oneOriginalActionAttemptOnly", true); put("testObserverRemoved", true)
                put("snapshots", JsonArray(snapshots)); put("received", JsonArray(received))
                put("receiptIsNotActionSuccess", true)
            }.toString() + "\n", CREATE_NEW, WRITE)
        }
        check(received.count { it["id"]?.jsonPrimitive?.int == MouseEvent.MOUSE_PRESSED &&
            it["sourceIsActualOwnedInput"]?.jsonPrimitive?.boolean == true } == 1) {
            "Actual owned Compose input did not receive the one original mouse press"
        }
        check(received.count { it["id"]?.jsonPrimitive?.int == MouseEvent.MOUSE_RELEASED &&
            it["sourceIsActualOwnedInput"]?.jsonPrimitive?.boolean == true } == 1) {
            "Actual owned Compose input did not receive the one original mouse release"
        }
    }
    /** Diagnostic reads occur only after the one genuine mouse release. They never
     * invoke OnClick, navigate, retry input, admit a write or alter a PASS assertion.
     * Reflection is confined to the actual pinned Compose accessibility wrapper and
     * bounded Function capture fields. Only settings route/owner identities are logged.
     */
    private fun ownedSearchDiagnostic(node: AccessibleContext, surface: Window, target: Component,
        point: java.awt.Point?): JsonObject = buildJsonObject {
        check(EventQueue.isDispatchThread())
        put("scope", "AFTER_REAL_RELEASE_READ_ONLY_SETTINGS_HEADER_DIAGNOSTIC")
        put("observedAfterActionOnly", true); put("callbackInvokedByDiagnostic", false)
        put("navigationInvokedByDiagnostic", false); put("inputRetried", false)
        put("monotonicNanos", System.nanoTime())
        if (lastOriginalModalDismissedNanos != 0L)
            put("millisSinceOriginalModalSemanticDismiss", (System.nanoTime() - lastOriginalModalDismissedNanos) / 1_000_000)
        put("sameRootRetainerReference", owner.retainer.root.value === routes.root)
        put("actualRootEntryOwns", routes.root.entry.gate.owns())
        put("actualRootEntryJobActive", routes.root.entry.gate.scope.coroutineContext[kotlinx.coroutines.Job]?.isActive == true)
        put("actualWindowNavigationOwns", owner.navigation.owns())
        put("actualCurrentRoute", routes.currentKey.toString())
        put("actualContextClass", node.javaClass.name)
        put("actualControlIdentity", System.identityHashCode(node))
        put("showingOwnedWindows", JsonArray(Window.getWindows().filter { owned(it) && it.isShowing }.map { window ->
            buildJsonObject {
                put("class", window.javaClass.name); put("identity", System.identityHashCode(window))
                put("isActualMain", window === actualWindow); put("enabled", window.isEnabled)
                put("focusable", window.isFocusableWindow); put("active", window.isActive)
                put("width", window.width); put("height", window.height)
                if (window is Dialog) put("modality", window.modalityType.name)
            }
        }))
        point?.let { inputPoint ->
            val surfacePoint = SwingUtilities.convertPoint(target, inputPoint, surface)
            val hit = SwingUtilities.getDeepestComponentAt(surface, surfacePoint.x, surfacePoint.y)
            put("deepestOwnedNativeComponentAfterRelease", hit?.javaClass?.name.orEmpty())
            put("deepestNativeComponentIsDispatchedInput", hit === target)
            put("deepestNativeComponentBelongsToSurface", hit != null && SwingUtilities.getWindowAncestor(hit) === surface)
        }
        val failures = mutableListOf<String>()
        val captures = mutableListOf<JsonObject>()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        fun trace(value: Any?, path: String, depth: Int) {
            if (value == null || depth > 7 || seen.size >= 72 || !seen.add(value)) return
            when {
                value == BiliPaiNavKey.Settings || value == BiliPaiNavKey.SettingsSearch || value is BiliPaiNavKey.SettingsCategory ->
                    captures.add(buildJsonObject {
                        put("path", path); put("kind", "CAPTURED_SETTINGS_ENTRY_KEY")
                        put("class", value.javaClass.name); put("identity", System.identityHashCode(value))
                        put("settingsKey", value.toString()); put("matchesActualPhysicalRoute", value == routes.currentKey)
                    })
                value is DesktopOriginalRootRouteCommands -> captures.add(buildJsonObject {
                    put("path", path); put("kind", "CAPTURED_ROUTE_COMMAND_OWNER")
                    put("class", value.javaClass.name); put("sameActualRoutes", value === routes)
                })
                value.javaClass.name == "androidx.compose.foundation.ClickableNode" -> {
                    try {
                        val base = value.javaClass.superclass
                        check(base.name == "androidx.compose.foundation.AbstractClickableNode")
                        val enabled = base.getDeclaredField("enabled")
                        val onClick = base.getDeclaredField("onClick")
                        check(enabled.type == java.lang.Boolean.TYPE)
                        check(onClick.type.name == "kotlin.jvm.functions.Function0")
                        check(enabled.trySetAccessible() && onClick.trySetAccessible())
                        val actualOnClick = onClick.get(value) as? Function<*>
                        captures.add(buildJsonObject {
                            put("path", path); put("kind", "PINNED_FOUNDATION_CLICKABLE_NODE_FIELDS_ONLY")
                            put("class", value.javaClass.name); put("identity", System.identityHashCode(value))
                            put("actualEnabled", enabled.getBoolean(value))
                            put("actualOnClickPresent", actualOnClick != null)
                            put("actualOnClickClass", actualOnClick?.javaClass?.name.orEmpty())
                            put("callbackInvokedByDiagnostic", false)
                        })
                        trace(actualOnClick, "$path.actualClickableOnClick", depth + 1)
                    } catch (failure: Exception) {
                        failures.add("PinnedFoundationClickableFields:${failure.javaClass.name}")
                    }
                }
                value is Function<*> -> {
                    captures.add(buildJsonObject {
                        put("path", path); put("kind", "FUNCTION_REFERENCE_ONLY")
                        put("class", value.javaClass.name); put("identity", System.identityHashCode(value))
                    })
                    for (field in value.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }) {
                        try {
                            if (field.trySetAccessible()) trace(field.get(value), "$path.${field.name}", depth + 1)
                            else failures.add("${field.declaringClass.name}.${field.name}:inaccessible")
                        } catch (failure: ReflectiveOperationException) {
                            failures.add("${field.declaringClass.name}.${field.name}:${failure.javaClass.name}")
                        }
                    }
                }
            }
        }
        try {
            check(node.javaClass.name == "androidx.compose.ui.platform.a11y.ComposeAccessible\$ComposeAccessibleComponent")
            val outerField = node.javaClass.getDeclaredField("this\$0")
            check(outerField.trySetAccessible())
            val wrapper = requireNotNull(outerField.get(node))
            check(wrapper.javaClass.name == "androidx.compose.ui.platform.a11y.ComposeAccessible")
            val semanticsField = wrapper.javaClass.getDeclaredField("semanticsNode")
            check(semanticsField.trySetAccessible())
            val semantics = semanticsField.get(wrapper) as androidx.compose.ui.semantics.SemanticsNode
            put("actualSemanticsId", semantics.id)
            trace(semantics.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.action, "currentSemanticsOnClick", 0)
            val cacheField = wrapper.javaClass.getDeclaredField("cachedSemanticsConfig")
            check(cacheField.trySetAccessible())
            val cached = cacheField.get(wrapper) as? androidx.compose.ui.semantics.SemanticsConfiguration
            cached?.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.action?.let {
                trace(it, "actualAccessibleCachedOnClick", 0)
            }
            put("actualPinnedComposeReadSucceeded", true)
        } catch (failure: Exception) {
            put("actualPinnedComposeReadSucceeded", false)
            failures.add(failure.javaClass.name)
        }
        put("boundedCaptureReferencesRead", seen.size)
        put("settingsCallbackCaptures", JsonArray(captures))
        put("diagnosticReadFailures", JsonArray(failures.map(::JsonPrimitive)))
        put("capturedPrimitiveStringsOrCredentialsDumped", false)
    }

    private fun aboutScope(): AccessibleContext = edt {
        check(current().key == aboutKey && routes.currentKey == aboutKey)
        completeScope(actualWindow.accessibleContext, aboutAnchors + "搜索设置")
    }
    private fun visible(node: AccessibleContext, surface: Window = actualWindow): Boolean {
        val component = node.accessibleComponent ?: return false
        val point = component.locationOnScreen ?: return false
        val size = component.size
        val pane = (surface as RootPaneContainer).contentPane
        val origin = pane.locationOnScreen
        return node.accessibleStateSet.contains(AccessibleState.SHOWING) && size.width > 0 && size.height > 0 &&
            Rectangle(origin.x, origin.y, pane.width, pane.height).contains(Rectangle(point.x, point.y, size.width, size.height))
    }
    private fun scrollTo(scope: () -> AccessibleContext, label: String): AccessibleContext {
        repeat(40) {
            val control = edt { originalAction(scope(), label) }
            if (edt { visible(control) }) { Thread.sleep(400); return edt { originalAction(scope(), label).also { check(visible(it)) } } }
            edt {
                current()
                val target = input(actualWindow)
                val component = requireNotNull(control.accessibleComponent)
                val origin = requireNotNull(component.locationOnScreen)
                val size = component.size
                val bounds = Rectangle(origin.x, origin.y, size.width, size.height)
                val pane = (actualWindow as RootPaneContainer).contentPane
                val center = pane.locationOnScreen.y + pane.height / 2
                val rotation = if (bounds.centerY < center) -5 else 5
                target.dispatchEvent(MouseWheelEvent(target, MouseWheelEvent.MOUSE_WHEEL, System.currentTimeMillis(),
                    0, target.width / 2, target.height / 2, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation))
            }
            Thread.sleep(200)
        }
        error("Actual original UI timed out: original control not wholly visible: $label")
    }
    private fun press(scope: () -> AccessibleContext, label: String) = mouse(scrollTo(scope, label))
    private fun aboutBack() {
        val node = edt {
            val scope = aboutScope()
            backInOriginalHeader(scope, originalAction(scope, "搜索设置"))
        }
        mouse(node)
    }
    private fun modal(anchors: List<String>, close: String): Window? = edt {
        current()
        val matches = Window.getWindows().filter { it.isShowing && owned(it) }.filter { surface ->
            anchors.all { anchor -> names(surface).any { it.contains(anchor) } } &&
                all(surface).count { hasLabel(it, close) && (it.accessibleAction?.accessibleActionCount ?: 0) == 1 } == 1
        }
        check(matches.size <= 1) { "Original dialog matched multiple owned surfaces" }
        matches.singleOrNull()
    }
    private fun dialog(id: String, opener: String, anchors: List<String>, close: String) {
        press(::aboutScope, opener)
        await("complete original $id dialog surface") { modal(anchors, close) != null }
        val surface = requireNotNull(modal(anchors, close))
        Thread.sleep(1800)
        check(edt { modal(anchors, close) === surface })
        capture(id, surface, anchors)
        val node = edt { originalAction(surface.accessibleContext, close) }
        mouse(node, surface)
        await("original $id dialog dismiss") { modal(anchors, close) == null }
        lastOriginalModalDismissedNanos = System.nanoTime()
        check(edt { current().key == aboutKey && routes.currentKey == aboutKey })
        record(id, mapOf("wholeOriginalBodyAnchors" to JsonArray(anchors.map(::JsonPrimitive)),
            "ownedSurfaceClass" to JsonPrimitive(surface.javaClass.name), "externalActionsInvoked" to JsonPrimitive(false)))
    }
    private fun naturalAbout() {
        val before = edt { current().serial }
        val settingsAction = edt { originalAction(actualWindow.accessibleContext, "设置") }
        mouse(settingsAction)
        awaitPage(BiliPaiNavKey.Settings, before, listOf("搜索设置", "系统与关于", "打赏作者"))
        press({ actualWindow.accessibleContext }, "系统与关于")
        awaitPage(aboutKey, before, aboutAnchors)
        // Original NavDisplay retains both entries during its transition; assert uniqueness only after it settles.
        Thread.sleep(1800)
        val sourceContributors = AboutContributors.map { it.name }
        check(sourceContributors.size == 12 && sourceContributors.distinct().size == 12)
        edt {
            val scope = aboutScope()
            // Whole original contributor contentDescription overrides its merged text caption.
            for (name in sourceContributors) originalAction(scope, "$name 头像")
            listOf("开源主页", "开源许可证", "检查更新", "查看更新日志", "重看使用须知", "默认打开链接",
                "完整声明", "Twitter / X", "打赏作者").forEach { originalAction(scope, it) }
            // The source row also has a GitHub summary. These three original official-card
            // controls have exact button semantics, distinct from that clickable source row.
            listOf("GitHub", "频道", "群组").forEach { label ->
                val controls = descendants(scope).filter { it.accessibleName == label &&
                    it.accessibleRole == AccessibleRole.PUSH_BUTTON &&
                    (it.accessibleAction?.accessibleActionCount ?: 0) == 1 }
                check(controls.size == 1 && controls.single().accessibleStateSet.contains(AccessibleState.ENABLED)) {
                    "Expected one original official-card '$label' button"
                }
            }
        }
        // Different settled viewports of the actual full original overview, retained list,
        // source/update rows and release card; remote avatar success is not inferred.
        presslessCapture("200-about-first", "用户协议与隐私政策")
        presslessCapture("201-about-contributors", "${sourceContributors.first()} 头像")
        presslessCapture("202-about-contributors-last", "${sourceContributors.last()} 头像")
        presslessCapture("203-about-updates", "查看更新日志")
        presslessCapture("204-about-official-card", "完整声明")
        presslessCapture("205-about-original-targets", "打赏作者")
        record("full-original-about-mounted", mapOf("originalContributorNames" to JsonArray(sourceContributors.map(::JsonPrimitive)),
            "contributorProfilesLaunched" to JsonPrimitive(false), "remoteAvatarLoadAccepted" to JsonPrimitive(false)))
        dialog("210-original-agreement-review", "用户协议与隐私政策",
            listOf(UserAgreementText.TITLE) + UserAgreementText.SECTIONS.map { it.title }, "关闭")
        dialog("211-original-release-disclaimer", "完整声明", listOf("免责声明", "本应用仅用于学习与交流。",
            "不存在任何其他官方发布途径", "请勿安装来源不明的安装包"), "我已知晓")
        actions.requireAcknowledged(true)
    }
    private fun presslessCapture(id: String, label: String) {
        scrollTo(::aboutScope, label); Thread.sleep(1800)
        capture(id, anchors = listOf(label))
    }
    private fun searchScope(): AccessibleContext = edt {
        check(current().key == BiliPaiNavKey.SettingsSearch && routes.currentKey == BiliPaiNavKey.SettingsSearch)
        val candidates = descendants(actualWindow.accessibleContext).filter { node ->
            val children = descendants(node)
            children.any { it.accessibleName == "搜索结果" && it.accessibleRole == AccessibleRole.LABEL &&
                (it.accessibleAction?.accessibleActionCount ?: 0) == 0 } &&
                children.any { it.accessibleEditableText != null && it.accessibleStateSet.contains(AccessibleState.SHOWING) }
        }
        check(candidates.isNotEmpty()) { "Actual original settings search page absent" }
        val sizes = candidates.associateWith { descendants(it).size }
        val size = requireNotNull(sizes.values.minOrNull())
        candidates.filter { sizes[it] == size }.single()
    }
    private fun searchField(): AccessibleContext = edt {
        descendants(searchScope()).filter { it.accessibleEditableText != null &&
            it.accessibleStateSet.contains(AccessibleState.SHOWING) && it.accessibleStateSet.contains(AccessibleState.ENABLED) }.single()
    }
    private fun fieldValue(): String = edt {
        val text = requireNotNull(searchField().accessibleText)
        (0 until text.charCount).joinToString("") { text.getAtIndex(javax.accessibility.AccessibleText.CHARACTER, it).orEmpty() }
    }
    private fun searchUi() {
        val before = edt { current().serial }
        mouse(edt { originalAction(aboutScope(), "搜索设置") })
        awaitPage(BiliPaiNavKey.SettingsSearch, before, listOf("搜索结果", "搜索设置功能"))
        for ((index, target) in searchTargets.withIndex()) {
            val copy = settingsDestinationCopy(target)
            val expected = resolveSettingsSearchResults(copy.title).filter { it.target == target }
            check(expected.size == 1) { "Original index query must uniquely resolve requested action target" }
            val result = expected.single()
            edt { searchField().accessibleEditableText.setTextContents(copy.title) }
            await("original setting query/result $target") { edt {
                current().key == BiliPaiNavKey.SettingsSearch && fieldValue() == copy.title &&
                    descendants(searchScope()).any { hasLabel(it, result.title) && (it.accessibleAction?.accessibleActionCount ?: 0) == 1 }
            } }
            Thread.sleep(1200)
            capture("220-search-$index-result", anchors = listOf("搜索结果", result.title))
            val after = edt { current().serial }
            press(::searchScope, result.title)
            awaitPage(aboutKey, after, aboutAnchors)
            // Original NavDisplay retains both entries during its transition; assert uniqueness only after it settles.
            Thread.sleep(1800)
            check(edt { Window.getWindows().none { it is JDialog && it.isShowing && owned(it) && it.title == "支持 BiliPai" } })
            check(edt { names().none { it == "免责声明" || it == "Windows 更新" || it == UserAgreementText.TITLE } })
            capture("221-search-$index-owning-category", anchors = listOf("系统与关于", "源码与验证"))
            val returningAfter = edt { current().serial }
            aboutBack()
            awaitPage(BiliPaiNavKey.SettingsSearch, returningAfter, listOf("搜索结果", result.title))
            check(fieldValue() == copy.title) { "Search query/history owner lost on typed category Back" }
            record("search-landing-$target", mapOf("originalTarget" to JsonPrimitive(target.name),
                "originalTypedOwningCategory" to JsonPrimitive(aboutKey.toString()), "originalQueryRestored" to JsonPrimitive(copy.title),
                "actionInvokedBySearch" to JsonPrimitive(false), "externalDispatchAccepted" to JsonPrimitive(false)))
        }
        val after = edt { current().serial }
        val back = edt {
            val scope = searchScope()
            val title = descendants(scope).filter { it.accessibleName == "搜索结果" && it.accessibleRole == AccessibleRole.LABEL &&
                (it.accessibleAction?.accessibleActionCount ?: 0) == 0 }.single()
            backInOriginalHeader(scope, title)
        }
        mouse(back); awaitPage(aboutKey, after, aboutAnchors)
        // Original NavDisplay retains both entries during its transition; assert uniqueness only after it settles.
        Thread.sleep(1800)
    }
    private fun replay() {
        val acknowledged = actions.welcomeFlags(); actions.requireAcknowledged(true)
        val before = edt { current().serial }
        press(::aboutScope, settingsDestinationCopy(SettingsSearchTarget.REPLAY_ONBOARDING).title)
        val frame = awaitPage(BiliPaiNavKey.Onboarding, before, listOf("使用须知", "官方渠道") + UserAgreementClause.entries.map { it.title })
        edt { check(!replayAck().accessibleStateSet.contains(AccessibleState.ENABLED)) }
        capture("300-replay-original-gate", anchors = listOf("使用须知", "官方渠道"))
        for ((index, clause) in UserAgreementClause.entries.withIndex()) {
            actions.clickClause(frame, clause)
            await("original replay ACK enabled state $index") { edt {
                replayAck().accessibleStateSet.contains(AccessibleState.ENABLED) ==
                    (index == UserAgreementClause.entries.lastIndex)
            } }
            check(actions.welcomeFlags() == acknowledged) { "Replay must not revoke durable consent" }
        }
        capture("301-replay-all-clauses", anchors = listOf("使用须知", "我已知晓"))
        mouse(edt { originalAction(actualWindow.accessibleContext, "我已知晓") })
        actions.awaitHome(frame.serial, owner)
        actions.requireAcknowledged(true); check(actions.welcomeFlags() == acknowledged)
        record("replay-real-original-completion", mapOf("durableAcknowledgementsUnchanged" to JsonPrimitive(true),
            "sameActualRootOwner" to JsonPrimitive(true), "originalClausesRequiredAgain" to JsonPrimitive(true)))
        capture("302-replay-returned-home", anchors = listOf("推荐"))
    }
    private fun replayAck(): AccessibleContext {
        check(current().key == BiliPaiNavKey.Onboarding && routes.currentKey == BiliPaiNavKey.Onboarding)
        return all().filter { it.accessibleName == "我已知晓" &&
            (it.accessibleAction?.accessibleActionCount ?: 0) == 1 &&
            it.accessibleStateSet.contains(AccessibleState.SHOWING) }.single()
    }
    private fun save() {
        actions.requireAcknowledged(true)
        Files.writeString(report.resolve("observations.json"), buildJsonObject {
            put("schema",1); put("observationPhase","BEFORE_REQUESTED_ACTUAL_EXIT")
            put("allPreExitAssertionsPassed",true); put("actualMainReturned",false); put("externalNaturalExitRequired",true)
            put("actualSourceSet","full test.runtimeClasspath; unchanged production Main"); put("defaultRenderer","DIRECT3D")
            put("sameActualMainRootOwner",true); put("guestPrivateProfileOnly",true); put("accountUsed",false)
            put("entryMechanism","NATURAL_ORIGINAL_HOME_SETTINGS_ABOUT_AND_SEARCH")
            put("mouseMechanism","OWNED_COMPOSE_AWT_INPUT; no OS-global mouse")
            put("searchActionLandingCount",searchTargets.size); put("originalContributorCount",AboutContributors.size)
            put("actualExternalLinkDispatchAccepted",false); put("liveMetadataOrNotesHttpAccepted",false)
            put("channelPopupAccepted",false); put("windowsPackageInstallationInvoked",false); put("apkInstallationInvoked",false)
            put("allRoutesAccepted",false); put("overallFeatureAccepted",false)
            put("checks",JsonArray(observations))
        }.toString()+"\n",CREATE_NEW,WRITE)
    }
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3)
        report=Path.of(args[0]).toRealPath(NOFOLLOW_LINKS)
        Files.list(report).use { require(it.findAny().isEmpty) { "Report must be empty before actual Main" } }
        val health=Path.of(args[1]); val token=args[2]
        DesktopOriginalRootValidationTap.install { latest.set(it) }.use {
            actions=OriginalOnboardingUiActions(latest::get,report,token); actions.requireFreshGuestStartup()
            val observer=Thread({
                try {
                    val home=actions.acceptFreshAgreementToHome(health)
                    owner=home.handle; routes=home.routes; actualWindow=edt { mainWindow() }
                    dismissOriginalDiagnosticPrompt(); naturalAbout(); searchUi(); replay()
                    save(); completed.set(true); actions.closeOwnedWindow(edt { current() })
                } catch(failure: Throwable) {
                    // These are diagnostics from this owned process, never acceptance.
                    // Preserve the original throwable even if a retired owner prevents
                    // a diagnostic capture; current()/ownership guards stay strict.
                    runCatching { capture("failure-owned-window", anchors = emptyList()) }
                        .onFailure { it.printStackTrace() }
                    runCatching { Files.writeString(report.resolve("failure-observations.json"), buildJsonObject {
                        put("allPreExitAssertionsPassed", false)
                        put("throwableClass", failure.javaClass.name); put("throwableMessage", failure.message.orEmpty())
                        put("checks", JsonArray(observations))
                    }.toString() + "\n", CREATE_NEW, WRITE) }.onFailure { it.printStackTrace() }
                    failure.printStackTrace(); kotlin.system.exitProcess(94)
                }
            },"Actual original About/Search Root UI observer")
            observer.isDaemon=true;observer.start()
            com.bilipai.desktop.main(arrayOf("--update-health-file",health.toString(),"--update-health-token",token))
            check(completed.get()) { "Actual Main ended before original About/Search assertions" }
        }
    }
}
