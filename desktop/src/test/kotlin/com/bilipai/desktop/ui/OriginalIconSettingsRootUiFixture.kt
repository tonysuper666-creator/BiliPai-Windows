package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.AppIconAppearance
import com.android.purebilibili.core.store.DEFAULT_APP_ICON_KEY
import com.android.purebilibili.feature.settings.getIconGroups
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.DesktopLibrary
import com.bilipai.desktop.settings.resolveDesktopOriginalLauncherIconResource
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

/** Test-only inputs to unchanged Main. Every icon preference is selected in original UI.
 * Natural settings entry and actual typed Root entry are recorded separately. */
object OriginalIconSettingsRootUiFixture {
    private val latest = AtomicReference<DesktopOriginalRootValidationTap.Frame?>()
    private val completed = AtomicBoolean(false)
    private val rows = CopyOnWriteArrayList<JsonObject>()
    private lateinit var report: Path
    private lateinit var actions: OriginalOnboardingUiActions
    private lateinit var owner: DesktopReadyOriginalRootHandle
    private lateinit var routes: DesktopOriginalRootRouteAssembly
    private var ownedWindowIdentity = 0
    private val originalKeys = listOf("icon_blue_snow_maid", "icon_blue_snow_maid_announcement",
        "icon_blue_snow_maid_front", "icon_3d", "icon_bilipai", "icon_bilipai_pink",
        "icon_bilipai_white", "icon_bilipai_monet")
    private val labels = linkedMapOf(AppIconAppearance.FOLLOW_SYSTEM to "跟随系统",
        AppIconAppearance.LIGHT to "明亮", AppIconAppearance.DARK to "暗黑")

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
        error("Actual IconSettings condition timed out: $description; current=${latest.get()?.key}")
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
    private fun clickOriginalIconBack() {
        check(current().key == BiliPaiNavKey.IconSettings && routes.currentKey == BiliPaiNavKey.IconSettings)
        val names = getIconGroups().flatMap { it.icons }.map { it.name }
        val candidates = all().filter { root ->
            val nodes = descendants(root)
            nodes.any { it.accessibleName == "应用图标" && it.accessibleRole == AccessibleRole.LABEL &&
                (it.accessibleAction?.accessibleActionCount ?: 0) == 0 } &&
                names.all { name -> nodes.any { it.accessibleName == name &&
                    it.accessibleRole == AccessibleRole.LABEL && (it.accessibleAction?.accessibleActionCount ?: 0) == 1 } }
        }
        check(candidates.isNotEmpty()) { "No complete original IconSettings page with its own title" }
        val counts = candidates.associateWith { descendants(it).size }
        val smallest = requireNotNull(counts.values.minOrNull())
        val pages = candidates.filter { counts[it] == smallest }
        check(pages.size == 1) { "More than one complete original IconSettings page" }
        click(pages.single(), "返回", AccessibleRole.PUSH_BUTTON)
    }
    private fun ensureVisibleButton(label: String) {
        repeat(24) {
            val visible = edt {
                current()
                val control = all().filter { hasLabel(it, label) && it.accessibleRole == AccessibleRole.PUSH_BUTTON &&
                    (it.accessibleAction?.accessibleActionCount ?: 0) == 1 }.single()
                val component = requireNotNull(control.accessibleComponent)
                val point = component.locationOnScreen
                val size = component.size
                val viewport = window().bounds
                point != null && size.width > 0 && size.height > 0 &&
                    viewport.contains(Rectangle(point.x, point.y, size.width, size.height))
            }
            if (visible) return
            edt {
                current()
                val actual = requireNotNull(layer(window(), Class.forName("org.jetbrains.skiko.SkiaLayer"))) as Component
                // Same real Window input path used by original static-settings list fixtures.
                actual.dispatchEvent(MouseWheelEvent(actual, MouseWheelEvent.MOUSE_WHEEL, System.currentTimeMillis(),
                    0, actual.width / 2, actual.height / 2, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 5))
            }
            Thread.sleep(250)
        }
        error("Original accessible button could not be scrolled into the actual Window: $label")
    }
    /** Lowest actual accessibility subtree containing the whole declared group; this excludes
     * unrelated retained Home/Search tabs and preferences with coincident labels. */
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
    private fun persisted(key: String, appearance: AppIconAppearance): Boolean {
        val all = namespaces()
        val settings = all["settings"] as? JsonObject ?: return false
        val mirror = all["app_icon_cache"] as? JsonObject ?: return false
        return (settings["app_icon_key"] as? JsonPrimitive)?.contentOrNull == key &&
            (settings["app_icon_appearance"] as? JsonPrimitive)?.intOrNull == appearance.storedValue &&
            (mirror["current_icon"] as? JsonPrimitive)?.contentOrNull == key &&
            (mirror["appearance"] as? JsonPrimitive)?.intOrNull == appearance.storedValue
    }
    private fun pixels(image: BufferedImage): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ByteBuffer.allocate(8).putInt(image.width).putInt(image.height).array())
        val line = IntArray(image.width)
        repeat(image.height) { y ->
            image.getRGB(0, y, image.width, 1, line, 0, image.width)
            val bytes = ByteBuffer.allocate(image.width * 4)
            line.forEach { bytes.putInt(it) }
            digest.update(bytes.array())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun windowImage(key: String, appearance: AppIconAppearance): JsonObject? {
        val actual = edt {
            current()
            val images = window().iconImages
            check(images.size <= 1) { "Unexpected second actual Window icon writer" }
            images.singleOrNull() as? BufferedImage
        } ?: return null
        val actualPixels = pixels(actual)
        val expectedPaths = listOf(false, true).map { resolveDesktopOriginalLauncherIconResource(key, appearance, it) }.distinct()
        val matching = expectedPaths.mapNotNull { path ->
            val raw = requireNotNull(OriginalIconSettingsRootUiFixture::class.java.getResourceAsStream(path)).use { it.readBytes() }
            val expected = requireNotNull(ImageIO.read(raw.inputStream()))
            if (pixels(expected) != actualPixels) null else path to raw
        }
        if (matching.isEmpty()) return null
        return buildJsonObject {
            put("key", key); put("appearance", appearance.name); put("appearanceStoredValue", appearance.storedValue)
            put("actualArgbSha256", actualPixels); put("width", actual.width); put("height", actual.height)
            put("matchesOriginalLauncherPng", true); put("windowIconImagesCount", 1)
            put("matchedResourcePaths", buildJsonArray { matching.forEach { add(JsonPrimitive(it.first)) } })
            put("matchedResourceSha256", buildJsonArray { matching.forEach { add(JsonPrimitive(MessageDigest.getInstance("SHA-256").digest(it.second).joinToString("") { byte -> "%02x".format(byte) })) } })
            put("followSystemCoversOnlyActualOsState", appearance == AppIconAppearance.FOLLOW_SYSTEM)
        }
    }
    private fun awaitWindowAndStore(key: String, appearance: AppIconAppearance, id: String) {
        await("original settings/mirror durable and same Window icon PNG: $id") { persisted(key, appearance) && windowImage(key, appearance) != null }
        Thread.sleep(350)
        check(persisted(key, appearance) && windowImage(key, appearance) != null)
        record(id, mapOf("durableOriginalSettingsAndStartupMirror" to JsonPrimitive(true),
            "windowImage" to requireNotNull(windowImage(key, appearance))))
        capture(id)
    }
    private fun iconScope(): AccessibleContext {
        check(current().key == BiliPaiNavKey.IconSettings && routes.currentKey == BiliPaiNavKey.IconSettings)
        return completeScope(window().accessibleContext, listOf("女仆图标外观", "精选") + getIconGroups().flatMap { it.icons }.map { it.name })
    }
    private fun naturalEntry(after: Long): DesktopOriginalRootValidationTap.Frame {
        edt { click(window().accessibleContext, "设置") }
        val settings = awaitPage(BiliPaiNavKey.Settings, after, listOf("搜索设置", "外观与主题"))
        edt { click(window().accessibleContext, "外观与主题") }
        val appearance = awaitPage(BiliPaiNavKey.Settings, settings.serial, listOf("应用图标", "外观预览"))
        ensureVisibleButton("应用图标")
        capture("120-natural-appearance")
        record("natural-appearance-entry", mapOf("entryMechanism" to JsonPrimitive("ORIGINAL_ACCESSIBLE_ACTIONS")))
        edt { click(window().accessibleContext, "应用图标", AccessibleRole.PUSH_BUTTON) }
        return awaitPage(BiliPaiNavKey.IconSettings, appearance.serial, listOf("应用图标", "女仆图标外观", "精选"))
    }
    private fun appearanceChoice(value: AppIconAppearance, id: String = "appearance-choice-${value.name}") {
        edt { click(iconScope(), "女仆图标外观") }
        var surface: Window? = null
        var group: AccessibleContext? = null
        var role: AccessibleRole? = null
        await("scoped original three appearance options") { edt {
            current()
            val matches = Window.getWindows().filter { it.isShowing && ownedWindow(it) }.mapNotNull { candidate ->
                val nodes = all(candidate)
                if (!labels.values.all { label -> nodes.any { it.accessibleName.orEmpty().lineSequence().any { text -> text == label } } }) return@mapNotNull null
                // The preference summary also mentions all three choices. Only the real
                // three separate actionable option names define the popup's complete scope.
                val scope = completeScope(candidate.accessibleContext, labels.values.toList(), exactActionAnchors = true)
                val controls = labels.values.map { label -> descendants(scope).filter { hasLabel(it, label) && (it.accessibleAction?.accessibleActionCount ?: 0) == 1 } }
                if (controls.any { it.size != 1 }) return@mapNotNull null
                val roles = controls.map { it.single().accessibleRole }.distinct()
                // The original desktop popup exposes its three actual clickable rows as LABEL.
                // Require the complete group and exactly one action per label before accepting it.
                if (roles.size != 1 || roles.single() !in setOf(AccessibleRole.RADIO_BUTTON, AccessibleRole.MENU_ITEM, AccessibleRole.UNKNOWN, AccessibleRole.LABEL)) return@mapNotNull null
                Triple(candidate, scope, roles.single())
            }
            check(matches.size <= 1) { "More than one complete original appearance choice group" }
            matches.singleOrNull()?.let { surface = it.first; group = it.second; role = it.third }
            matches.size == 1
        } }
        // Popup semantics appear before its native opening animation has settled.
        // Reacquire the actual complete group after settling instead of invoking an
        // accessibility context retained from the first opening frame.
        Thread.sleep(1200)
        edt {
            current()
            val actual = requireNotNull(surface)
            check(actual.isShowing && ownedWindow(actual))
            val freshGroup = completeScope(actual.accessibleContext, labels.values.toList(), exactActionAnchors = true)
            labels.values.forEach { label ->
                val controls = descendants(freshGroup).filter { it.accessibleName == label &&
                    (it.accessibleAction?.accessibleActionCount ?: 0) == 1 }
                check(controls.size == 1 && controls.single().accessibleRole == role)
            }
            group = freshGroup
        }
        capture("settled-choice-${value.name}-${rows.size}", requireNotNull(surface))
        record(id, mapOf("label" to JsonPrimitive(requireNotNull(labels[value])),
            "actualChoiceRole" to JsonPrimitive(requireNotNull(role).toString()),
            "allThreeOptionsInSameOriginalScope" to JsonPrimitive(true), "surfaceClass" to JsonPrimitive(requireNotNull(surface).javaClass.name)))
        edt { click(requireNotNull(group), requireNotNull(labels[value]), requireNotNull(role), requireNotNull(surface)) }
        await("original appearance popup finished closing") { edt {
            current()
            val actual = requireNotNull(surface)
            !actual.isShowing || labels.values.none { label -> all(actual).any {
                it.accessibleName == label && (it.accessibleAction?.accessibleActionCount ?: 0) == 1
            } }
        } }
        record("$id-popup-closed", mapOf("originalChoiceControlsRetired" to JsonPrimitive(true),
            "popupSharesActualMainWindow" to JsonPrimitive(edt { surface === window() })))
    }
    private fun fresh(home: DesktopOriginalRootValidationTap.Frame) {
        val options = getIconGroups().flatMap { it.icons }
        check(options.map { it.key } == originalKeys && options.map { it.name }.distinct().size == 8)
        var page = naturalEntry(home.serial)
        val sourceStack = edt { current().routes.stack.dropLast(1).toList() }
        capture("130-original-eight-options")
        record("complete-original-options", mapOf("originalOptionKeys" to buildJsonArray { options.forEach { add(JsonPrimitive(it.key)) } }, "originalAppearanceCount" to JsonPrimitive(3)))
        // Every canonical key is actually changed through its original card. Default key is last.
        appearanceChoice(AppIconAppearance.DARK)
        // Before the first actual key change, the original default key can be absent on disk.
        // Persisting appearance must not be mistaken for having persisted an untouched key.
        await("original DARK appearance mirrors persisted before first key change") {
            (settings()["app_icon_appearance"] as? JsonPrimitive)?.intOrNull == 2 &&
                ((namespaces()["app_icon_cache"] as? JsonObject)?.get("appearance") as? JsonPrimitive)?.intOrNull == 2 &&
                windowImage(DEFAULT_APP_ICON_KEY, AppIconAppearance.DARK) != null
        }
        record("140-appearance-dark", mapOf("durableOriginalAppearanceAndMirror" to JsonPrimitive(true),
            "untouchedDefaultKeyMayBeAbsent" to JsonPrimitive(true), "windowImage" to requireNotNull(windowImage(DEFAULT_APP_ICON_KEY, AppIconAppearance.DARK))))
        capture("140-appearance-dark")
        for ((index, option) in (options.drop(1) + options.first()).withIndex()) {
            edt { click(iconScope(), option.name, AccessibleRole.LABEL, window()) }
            awaitWindowAndStore(option.key, AppIconAppearance.DARK, "icon-$index-${option.key}")
        }
        appearanceChoice(AppIconAppearance.LIGHT)
        awaitWindowAndStore(DEFAULT_APP_ICON_KEY, AppIconAppearance.LIGHT, "150-appearance-light")
        appearanceChoice(AppIconAppearance.FOLLOW_SYSTEM)
        awaitWindowAndStore(DEFAULT_APP_ICON_KEY, AppIconAppearance.FOLLOW_SYSTEM, "160-appearance-follow-system")
        appearanceChoice(AppIconAppearance.DARK, "appearance-choice-DARK-final")
        awaitWindowAndStore(DEFAULT_APP_ICON_KEY, AppIconAppearance.DARK, "170-final-dark")
        page = edt { current() }
        edt { clickOriginalIconBack() }
        awaitPage(BiliPaiNavKey.Settings, page.serial, listOf("应用图标", "外观预览"))
        check(edt { routes.stack.toList() == sourceStack })
        awaitWindowAndStore(DEFAULT_APP_ICON_KEY, AppIconAppearance.DARK, "180-natural-original-back-window-icon")
        // This second branch has no natural source entrance at the observed source revision.
        // Exercise the SAME actual Root command/admission; never write its physical stack.
        val beforeTyped = edt { current() }
        edt { check(routes.push(BiliPaiNavKey.AppearanceSettings)) }
        val typed = awaitPage(BiliPaiNavKey.AppearanceSettings, beforeTyped.serial, listOf("应用图标", "外观预览"))
        ensureVisibleButton("应用图标")
        record("typed-appearance-entry", mapOf("entryMechanism" to JsonPrimitive("ACTUAL_ROOT_COMMANDS_EDT"), "inventedNaturalEntrance" to JsonPrimitive(false)))
        capture("190-typed-appearance")
        edt { click(window().accessibleContext, "应用图标", AccessibleRole.PUSH_BUTTON) }
        val icon = awaitPage(BiliPaiNavKey.IconSettings, typed.serial, listOf("女仆图标外观", "精选"))
        capture("200-typed-original-icon-page")
        edt { clickOriginalIconBack() }
        awaitPage(BiliPaiNavKey.AppearanceSettings, icon.serial, listOf("应用图标", "外观预览"))
        record("typed-original-back", mapOf("returnedToTypedSource" to JsonPrimitive(true)))
        val beforeHome = edt { current() }
        edt { check(owner.navigation.requestBack()) }
        awaitPage(BiliPaiNavKey.Settings, beforeHome.serial, listOf("应用图标", "外观预览"))
        val settingsFrame = edt { current() }
        edt { check(owner.navigation.requestBack()) }
        awaitPage(BiliPaiNavKey.Home, settingsFrame.serial, listOf("推荐"))
        awaitWindowAndStore(DEFAULT_APP_ICON_KEY, AppIconAppearance.DARK, "210-home-window-icon-after-pages")
    }
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 4 && args[0] in setOf("fresh", "cold"))
        val phase = args[0]
        report = Path.of(args[1]).toRealPath()
        Files.list(report).use { require(it.findAny().isEmpty) }
        val health = Path.of(args[2]).toAbsolutePath().normalize()
        require(!Files.exists(health))
        actions = OriginalOnboardingUiActions(latest::get, report, args[3])
        if (phase == "fresh") { actions.requireFreshGuestStartup(); actions.requireAcknowledged(false)
            check((settings()["app_icon_key"] as? JsonPrimitive)?.contentOrNull == null)
            check((namespaces()["app_icon_cache"] as? JsonObject)?.isEmpty() != false)
        } else { actions.requireAcknowledged(true); check(persisted(DEFAULT_APP_ICON_KEY, AppIconAppearance.DARK)) }
        DesktopOriginalRootValidationTap.install { latest.set(it) }.use {
            val worker = Thread({
                try {
                    val home = if (phase == "fresh") actions.acceptFreshAgreementToHome(health) else actions.awaitHome()
                    actions.awaitActualHealth(health)
                    owner = home.handle; routes = home.routes; ownedWindowIdentity = edt { System.identityHashCode(window()) }
                    dismissActualDiagnosticPrompt(phase == "fresh")
                    if (phase == "fresh") fresh(home) else {
                        awaitWindowAndStore(DEFAULT_APP_ICON_KEY, AppIconAppearance.DARK, "220-cold-home-durable-window-icon")
                        val icon = naturalEntry(edt { current().serial })
                        capture("230-cold-original-icon-page")
                        awaitWindowAndStore(DEFAULT_APP_ICON_KEY, AppIconAppearance.DARK, "240-cold-icon-page-durable")
                        edt { clickOriginalIconBack() }
                        awaitPage(BiliPaiNavKey.Settings, icon.serial, listOf("应用图标", "外观预览"))
                    }
                    actions.requireAcknowledged(true)
                    check(persisted(DEFAULT_APP_ICON_KEY, AppIconAppearance.DARK))
                    val receipt = buildJsonObject {
                        put("schema", 1); put("phase", phase); put("actualMainReturned", false)
                        put("observationPhase", "BEFORE_REQUESTED_ACTUAL_EXIT"); put("allPreExitAssertionsPassed", true)
                        put("externalExitAndFinalPersistenceRequired", true); put("defaultRenderer", "DIRECT3D")
                        put("actualMainInvocations", 1); put("sameLiveRootAndWindow", true)
                        put("realAccountUsed", false); put("fixturePreferenceWrites", false); put("physicalStackWrittenByFixture", false)
                        put("allRoutesAccepted", false); put("newExeDeployed", false)
                        put("expectedFinalIconKey", DEFAULT_APP_ICON_KEY); put("expectedFinalAppearance", AppIconAppearance.DARK.storedValue)
                        put("observations", buildJsonArray { rows.forEach { add(it) } })
                    }
                    Files.writeString(report.resolve("observations.json"), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), receipt), CREATE_NEW, WRITE)
                    completed.set(true)
                    actions.closeOwnedWindow(edt { current() })
                } catch (failure: Throwable) {
                    failure.printStackTrace()
                    runCatching { edt { dump("failure", window()) } }
                    runCatching { capture("failure-owned-window") }
                    runCatching {
                        val all = namespaces()
                        val settings = all["settings"] as? JsonObject
                        val mirror = all["app_icon_cache"] as? JsonObject
                        val diagnostic = buildJsonObject {
                            put("accepted", false)
                            put("observations", JsonArray(rows.toList()))
                            put("originalIconKey", settings?.get("app_icon_key") ?: JsonNull)
                            put("originalAppearance", settings?.get("app_icon_appearance") ?: JsonNull)
                            put("startupMirrorKey", mirror?.get("current_icon") ?: JsonNull)
                            put("startupMirrorAppearance", mirror?.get("appearance") ?: JsonNull)
                        }
                        Files.writeString(report.resolve("failure-observations.json"), diagnostic.toString(), CREATE_NEW, WRITE)
                    }
                    kotlin.system.exitProcess(91)
                }
            }, "Original IconSettings actual Root observer")
            worker.isDaemon = true; worker.start()
            com.bilipai.desktop.main(arrayOf("--update-health-file", health.toString(), "--update-health-token", args[3]))
            check(completed.get()) { "Actual Main returned before IconSettings assertions" }
        }
    }
}
