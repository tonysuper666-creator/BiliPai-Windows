package com.bilipai.desktop.ui

import androidx.lifecycle.Lifecycle
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.DesktopLibrary
import com.bilipai.desktop.settings.desktopOriginalDonateQrBytes
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

/** Opt-in actual Main guest UI. The fixture uses original accessible actions and owned
 * AWT keyboard events; it never writes a navigation stack, ViewModel, account or record.
 * Escape assertions use the existing real focus/component event pipeline, not routes.back.
 * No fraud import/export/recheck/delete/clear/post or external payment/link action is invoked.
 */
object OriginalFraudDonateRootUiFixture {
    private const val QR_SHA = "ebd531d83556e51a92fdd5d4d64946f037730416cff44957a8018d063b5bb610"
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
    private fun completeScope(root: AccessibleContext, anchors: List<String>): AccessibleContext {
        val candidates = descendants(root).filter { node ->
            anchors.all { anchor -> descendants(node).any { it.accessibleName.orEmpty().contains(anchor) } }
        }
        check(candidates.isNotEmpty()) { "Complete actual original page scope absent: $anchors" }
        val sizes = candidates.associateWith { descendants(it).size }
        val smallest = requireNotNull(sizes.values.minOrNull())
        return candidates.filter { sizes[it] == smallest }.single()
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
    private fun originalFraudBack(): AccessibleContext {
        current()
        val scope = completeScope(actualWindow.accessibleContext, fraudAnchors)
        val titles = descendants(scope).filter { it.accessibleName == "发评反诈历史" &&
            it.accessibleRole == javax.accessibility.AccessibleRole.LABEL &&
            (it.accessibleAction?.accessibleActionCount ?: 0) == 0 && it.accessibleStateSet.contains(AccessibleState.SHOWING) }
        check(titles.size == 1) { "Expected original Fraud header title in complete leaf" }
        return backInOriginalHeader(scope, titles.single())
    }
    private fun originalPrivacyBack(): AccessibleContext {
        current()
        val scope = completeScope(actualWindow.accessibleContext,
            listOf("搜索设置", "隐私与权限", "发评反诈历史", "查看历史发评与风控状态"))
        val header = originalAction(scope, "搜索设置")
        return backInOriginalHeader(scope, header)
    }
    private fun clickOriginalFraudBack() = edt {
        val back = originalFraudBack()
        val bounds = controlBounds(back)
        record("fraud-scoped-original-back", mapOf("uniqueCompletePageHeaderAction" to JsonPrimitive(true),
            "x" to JsonPrimitive(bounds.x), "y" to JsonPrimitive(bounds.y)))
        check(back.accessibleAction.doAccessibleAction(0))
    }
    private fun clickOriginalPrivacyBack() = edt {
        val back = originalPrivacyBack()
        val bounds = controlBounds(back)
        record("privacy-scoped-original-back", mapOf("uniqueOriginalSearchHeaderRowAction" to JsonPrimitive(true),
            "x" to JsonPrimitive(bounds.x), "y" to JsonPrimitive(bounds.y)))
        check(back.accessibleAction.doAccessibleAction(0))
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
    private fun ensureVisible(label: String) {
        repeat(28) {
            val visible = edt {
                val node = originalAction(actualWindow.accessibleContext, label)
                val component = requireNotNull(node.accessibleComponent)
                val point = component.locationOnScreen
                val size = component.size
                val viewport = (actualWindow as RootPaneContainer).contentPane
                val origin = viewport.locationOnScreen
                point != null && size.width > 0 && size.height > 0 &&
                    Rectangle(origin.x, origin.y, viewport.width, viewport.height).contains(Rectangle(point.x, point.y, size.width, size.height))
            }
            if (visible) return
            edt {
                current()
                val actual = requireNotNull(layer(actualWindow, Class.forName("org.jetbrains.skiko.SkiaLayer"))) as Component
                actual.dispatchEvent(MouseWheelEvent(actual, MouseWheelEvent.MOUSE_WHEEL, System.currentTimeMillis(),
                    0, actual.width / 2, actual.height / 2, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 5))
            }
            Thread.sleep(150)
        }
        error("Original settings control not visible after actual wheel input: $label")
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
    }
    private val fraudFile: Path get() = DesktopLibrary.directoryForAccount(null).resolve("comment_fraud_records/0/records.json")
    private val fraudAnchors = listOf("发评反诈历史", "暂无发评记录", "发出的评论将在此处沉淀并追踪全生命周期状态", "导入备份", "导出备份")
    private fun assertEmptyFraud() = edt {
        val frame = current(); check(frame.key == BiliPaiNavKey.Settings && routes.currentKey == BiliPaiNavKey.Settings)
        check(fraudAnchors.all { text -> names().any { it.contains(text) } })
        val pageScope = completeScope(actualWindow.accessibleContext, fraudAnchors)
        listOf("导入备份", "导出备份").forEach { originalAction(pageScope, it) }
        originalFraudBack()
        check(names().none { it == "清空全部" || it == "复检" || it.contains("永久删除此评论") })
        check(!Files.exists(fraudFile, NOFOLLOW_LINKS)) { "Fresh guest fixture unexpectedly has a record file" }
    }
    private fun openFraud(after: Long): DesktopOriginalRootValidationTap.Frame {
        ensureVisible("发评反诈历史"); click("发评反诈历史")
        return awaitPage(BiliPaiNavKey.Settings, after, fraudAnchors).also { assertEmptyFraud() }
    }
    private fun fraudUi() {
        val initial = edt { current() }
        click("设置")
        var settings = awaitPage(BiliPaiNavKey.Settings, initial.serial, listOf("搜索设置", "外观与主题", "隐私与权限", "打赏作者"))
        capture("120-original-settings-root", anchors = listOf("搜索设置", "隐私与权限"))
        ensureVisible("隐私与权限"); click("隐私与权限")
        var privacy = awaitPage(BiliPaiNavKey.Settings, settings.serial, listOf("隐私与权限", "发评反诈历史", "查看历史发评与风控状态"))
        var fraud = openFraud(privacy.serial)
        capture("130-fraud-original-empty", anchors = fraudAnchors)
        val savedState = edt { actualWindow.extendedState }
        edt { current(); actualWindow.extendedState = savedState or Frame.ICONIFIED }
        await("actual Main iconified and navigation lifecycle stopped") { edt {
            actualWindow.extendedState and Frame.ICONIFIED != 0 && !owner.navigation.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        } }
        Thread.sleep(1200)
        edt { actualWindow.extendedState = savedState and Frame.ICONIFIED.inv(); actualWindow.toFront(); actualWindow.requestFocus() }
        await("actual Main restored same empty Fraud") { edt {
            actualWindow.extendedState and Frame.ICONIFIED == 0 && actualWindow.isShowing &&
                owner.navigation.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                current().key == BiliPaiNavKey.Settings && fraudAnchors.all { text -> names().any { it.contains(text) } }
        } }
        Thread.sleep(1800); assertEmptyFraud()
        capture("131-fraud-restored-empty", anchors = fraudAnchors)
        record("fraud-minimize-restore", mapOf("sameEmptyPage" to JsonPrimitive(true)))
        val after = edt { current().serial }; clickOriginalFraudBack()
        privacy = awaitPage(BiliPaiNavKey.Settings, after, listOf("隐私与权限", "查看历史发评与风控状态"))
        check(edt { names().none { it == "暂无发评记录" } })
        capture("132-fraud-original-back", anchors = listOf("隐私与权限", "查看历史发评与风控状态"))
        fraud = openFraud(privacy.serial)
        capture("133-fraud-reopened-empty", anchors = fraudAnchors)
        escape(actualWindow)
        privacy = awaitPage(BiliPaiNavKey.Settings, fraud.serial, listOf("隐私与权限", "查看历史发评与风控状态"))
        check(edt { names().none { it == "暂无发评记录" } })
        capture("134-fraud-owned-escape-back", anchors = listOf("隐私与权限", "查看历史发评与风控状态"))
        record("fraud-empty-back-escape", mapOf("remoteActionsInvoked" to JsonPrimitive(false), "recordsFileCreated" to JsonPrimitive(false), "escapeMechanism" to JsonPrimitive("OWNED_AWT_FOCUS_KEY_EVENT")))
        clickOriginalPrivacyBack()
        settings = awaitPage(BiliPaiNavKey.Settings, privacy.serial, listOf("搜索设置", "外观与主题", "打赏作者"))
        capture("135-returned-original-settings-root", anchors = listOf("外观与主题", "打赏作者"))
    }
    private fun sponsor(): JDialog? = edt {
        current()
        val matches = Window.getWindows().filterIsInstance<JDialog>().filter {
            it.isDisplayable && it.isShowing && it.owner === actualWindow && it.title == "支持 BiliPai" &&
                listOf("打赏二维码", "感谢您的支持！", "点击二维码或关闭按钮退出", "关闭").all { anchor -> names(it).any { it.contains(anchor) } }
        }
        check(matches.size <= 1); matches.singleOrNull()
    }
    private fun clientBounds(): Rectangle = edt {
        val anchor = (actualWindow as RootPaneContainer).contentPane
        check(anchor.isShowing && SwingUtilities.getWindowAncestor(anchor) === actualWindow)
        val point = anchor.locationOnScreen
        Rectangle(point.x, point.y, anchor.width, anchor.height)
    }
    /** Require the complete actual QR, original closing control and both original
     * footer texts inside this owned modal's native client. A semantic label alone
     * cannot accept a clipped wide-window original image or instruction. */
    private fun assertWholeOriginalSponsorVisible(dialog: JDialog, id: String) = edt {
        current(); check(dialog.owner === actualWindow && dialog.isShowing && dialog.isDisplayable)
        val pane = (dialog as RootPaneContainer).contentPane
        val origin = pane.locationOnScreen
        val viewport = Rectangle(origin.x, origin.y, pane.width, pane.height)
        check(viewport.width > 0 && viewport.height > 0)
        val controls = mutableListOf<Pair<String, AccessibleContext>>()
        controls.add("打赏二维码" to originalAction(dialog.accessibleContext, "打赏二维码"))
        controls.add("关闭" to originalAction(dialog.accessibleContext, "关闭"))
        for (label in listOf("感谢您的支持！", "点击二维码或关闭按钮退出")) {
            val matches = all(dialog).filter { it.accessibleName == label &&
                it.accessibleRole == javax.accessibility.AccessibleRole.LABEL &&
                (it.accessibleAction?.accessibleActionCount ?: 0) == 0 &&
                it.accessibleStateSet.contains(AccessibleState.SHOWING) }
            check(matches.size == 1) { "Expected one complete original Donate text: $label" }
            controls.add(label to matches.single())
        }
        val bounds = controls.map { (label, context) ->
            val actual = controlBounds(context)
            check(viewport.contains(actual)) { "Original Donate control is clipped: $label; actual=$actual; viewport=$viewport" }
            buildJsonObject { put("label", label); put("x", actual.x); put("y", actual.y)
                put("width", actual.width); put("height", actual.height); put("fullyInsideOwnedClient", true) }
        }
        record("$id-complete-visibility", mapOf("wholeOriginalQrAndCloseAndBothTextsInsideClient" to JsonPrimitive(true),
            "nativeControlBounds" to JsonArray(bounds)))
    }
    private fun geometry(dialog: JDialog, id: String) {
        await("original sponsor exact actual client bounds: $id") { edt {
            current(); dialog.isDisplayable && dialog.isShowing && dialog.owner === actualWindow &&
                dialog.modalityType == Dialog.ModalityType.DOCUMENT_MODAL && dialog.bounds == clientBounds() &&
                dialog.isAlwaysOnTop == actualWindow.isAlwaysOnTop
        } }
        Thread.sleep(1800)
        edt { check(sponsor() === dialog && dialog.bounds == clientBounds()) }
        assertWholeOriginalSponsorVisible(dialog, id)
        capture(id, dialog, listOf("打赏二维码", "感谢您的支持！", "关闭"))
        val bounds = edt { dialog.bounds }
        record(id, mapOf("ownerClientBoundsExact" to JsonPrimitive(true), "sameOwnedDialog" to JsonPrimitive(true),
            "documentModal" to JsonPrimitive(true), "x" to JsonPrimitive(bounds.x), "y" to JsonPrimitive(bounds.y),
            "width" to JsonPrimitive(bounds.width), "height" to JsonPrimitive(bounds.height)))
    }
    private fun openSponsor(id: String): JDialog {
        check(edt { current().key == BiliPaiNavKey.Settings && names().any { it.contains("打赏作者") } })
        ensureVisible("打赏作者"); click("打赏作者")
        await("complete original owned sponsor modal") { sponsor() != null }
        val dialog = requireNotNull(sponsor())
        geometry(dialog, id)
        check(MessageDigest.getInstance("SHA-256").digest(desktopOriginalDonateQrBytes()).joinToString("") { "%02x".format(it) } == QR_SHA)
        record("$id-original-resource", mapOf("qrRawSha256" to JsonPrimitive(QR_SHA), "networkQrSource" to JsonPrimitive(false)))
        return dialog
    }
    private fun closedSponsor(dialog: JDialog, id: String) {
        await("original sponsor disposed after $id") { edt { !dialog.isDisplayable && !dialog.isShowing && sponsor() == null } }
        check(edt { current().key == BiliPaiNavKey.Settings && names().any { it.contains("打赏作者") } })
        record(id, mapOf("nativeResourceDisposed" to JsonPrimitive(true), "settingsParentRetained" to JsonPrimitive(true)))
    }
    private fun donateUi() {
        val originalBounds = edt { actualWindow.bounds }
        val dialog = openSponsor("140-donate-original-qr")
        edt { current(); actualWindow.setLocation(originalBounds.x + 18, originalBounds.y + 14) }
        geometry(dialog, "141-donate-owner-moved")
        edt { current(); actualWindow.setSize((originalBounds.width - 160).coerceAtLeast(800), (originalBounds.height - 90).coerceAtLeast(600)) }
        geometry(dialog, "142-donate-owner-resized")
        click("关闭", dialog); closedSponsor(dialog, "143-donate-original-close")
        edt { actualWindow.bounds = originalBounds }
        await("real owner geometry restored") { edt { actualWindow.bounds == originalBounds } }
        val reopened = openSponsor("144-donate-reopened")
        check(reopened !== dialog)
        escape(reopened); closedSponsor(reopened, "145-donate-owned-escape-close")
        // The QR itself has the original clickable dismissal; no payment/link is opened.
        val qrClose = openSponsor("146-donate-qr-close")
        click("打赏二维码", qrClose); closedSponsor(qrClose, "147-donate-original-qr-click-close")
        capture("148-donate-parent-settings", anchors = listOf("搜索设置", "打赏作者"))
        record("donate-core-complete", mapOf("originalQrVisible" to JsonPrimitive(true), "ownerMoveResizeObserved" to JsonPrimitive(true),
            "imageLifetimeRejectionAccepted" to JsonPrimitive(false), "ownerRetirementMidPumpAccepted" to JsonPrimitive(false)))
    }
    private fun save() {
        assertEmptyGuestRecords()
        Files.writeString(report.resolve("observations.json"), buildJsonObject {
            put("schema", 1); put("observationPhase", "BEFORE_REQUESTED_ACTUAL_EXIT")
            put("allPreExitAssertionsPassed", true); put("actualMainReturned", false)
            put("externalNaturalExitRequired", true); put("sameActualMainRootOwner", true)
            put("entryMechanism", "ORIGINAL_ACCESSIBLE_HOME_SETTINGS_ACTIONS")
            put("keyboardMechanism", "OWNED_AWT_FOCUS_KEY_EVENT; not OS-global input")
            put("actualSourceSet", "full test.runtimeClasspath; unchanged production Main")
            put("defaultRenderer", "DIRECT3D"); put("guestPrivateProfileOnly", true)
            put("accountUsed", false); put("fraudRemoteActionsInvoked", false)
            put("fraudImportExportOrRecordMutationInvoked", false); put("fraudRecordFileExists", false)
            put("donateImageLifetimeRejectionAccepted", false); put("donateOwnerRetirementMidPumpAccepted", false)
            put("allRoutesAccepted", false); put("overallFeatureAccepted", false)
            put("checks", JsonArray(observations))
        }.toString() + "\n", CREATE_NEW, WRITE)
    }
    private fun assertEmptyGuestRecords() = check(!Files.exists(fraudFile, NOFOLLOW_LINKS))
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3)
        report = Path.of(args[0]).toRealPath(NOFOLLOW_LINKS)
        Files.list(report).use { require(it.findAny().isEmpty) { "Report must be empty before actual Main" } }
        val health = Path.of(args[1]); val token = args[2]
        DesktopOriginalRootValidationTap.install { latest.set(it) }.use {
            actions = OriginalOnboardingUiActions(latest::get, report, token)
            actions.requireFreshGuestStartup(); assertEmptyGuestRecords()
            val observer = Thread({
                try {
                    val home = actions.acceptFreshAgreementToHome(health)
                    owner = home.handle; routes = home.routes; actualWindow = edt { mainWindow() }
                    dismissOriginalDiagnosticPrompt(); fraudUi(); donateUi()
                    save(); completed.set(true)
                    actions.closeOwnedWindow(edt { current() })
                } catch (failure: Throwable) {
                    failure.printStackTrace(); kotlin.system.exitProcess(93)
                }
            }, "Actual original Fraud/Donate Root UI observer")
            observer.isDaemon = true; observer.start()
            com.bilipai.desktop.main(arrayOf("--update-health-file", health.toString(), "--update-health-token", token))
            // Natural Compose exit can end the JVM; the runner binds observation/log/external exit.
            check(completed.get()) { "Actual Main ended before original Fraud/Donate assertions" }
        }
    }
}
