package com.bilipai.desktop.ui

import androidx.lifecycle.Lifecycle
import com.android.purebilibili.core.ui.resolveAppAlertDialogRenderer
import com.bilipai.desktop.appearance.DesktopThemePrefs
import com.bilipai.desktop.appearance.buildDesktopAppThemeConfig
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.DesktopLibrary
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import java.awt.Component
import java.awt.Container
import java.awt.EventQueue
import java.awt.Frame as AwtFrame
import java.awt.Window
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.accessibility.AccessibleState
import javax.accessibility.AccessibleText
import javax.swing.JDialog
import javax.swing.JFrame
import kotlinx.serialization.json.*

/** Observe unchanged Main with real stores, windows, original controls, and live Root navigation.
 * No ViewModel/stack writes, test servers, account credentials, synthetic frames or renderer override.
 * Run decline/accept/cold as separate JVMs over one exclusively owned LOCALAPPDATA directory.
 */
object OriginalAicuRootUiFixture {
    private const val DISCLAIMER = "第三方查询免责声明"
    private const val CONFIRM = "我已知晓并继续"
    private const val IDLE = "输入 UID 后点击查询。"
    private const val CONSENT_KEY = "aicu_disclaimer_accepted_version"
    private val latest = AtomicReference<DesktopOriginalRootValidationTap.Frame?>()
    private val completed = AtomicBoolean(false)
    private lateinit var report: Path
    private lateinit var context: DesktopPluginContext
    private var visibleUpperBoundStart = 0L
    private var verifiedPausedNanos = 0L
    private val observations = linkedMapOf<String, JsonElement>()

    private fun <T> edt(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        val result = AtomicReference<Result<T>?>()
        EventQueue.invokeAndWait { result.set(runCatching(block)) }
        return requireNotNull(result.get()).getOrThrow()
    }

    private fun mainWindow(): JFrame = Window.getWindows().filterIsInstance<JFrame>()
        .filter { it.isDisplayable && it.title == "BiliPai Windows" }.single()

    private fun ownedByMain(window: Window): Boolean {
        val main = mainWindow()
        var current: Window? = window
        while (current != null) { if (current === main) return true; current = current.owner }
        return false
    }

    private fun nodes(context: AccessibleContext?, result: MutableList<AccessibleContext>, depth: Int = 0) {
        if (context == null || depth > 80) return
        result.add(context)
        repeat(context.accessibleChildrenCount) { nodes(context.getAccessibleChild(it)?.accessibleContext, result, depth + 1) }
    }

    private fun accessible(window: Window): List<AccessibleContext> = mutableListOf<AccessibleContext>().also {
        nodes(window.accessibleContext, it)
    }

    private fun names(window: Window) = accessible(window).map { it.accessibleName.orEmpty() }

    private data class OriginalDialogSurface(val window: Window, val title: String, val renderer: String, val uiStyle: String) {
        val kind: String get() = if (window is JDialog) "owned-JDialog" else "inline-main-JFrame"
    }

    /** AppAlertDialog -> original AdaptiveAlertDialog -> Material AlertDialog / Miuix
     * WindowDialog / hinge-safe Dialog. Compose Desktop may host a Dialog layer in its
     * actual main window. The complete original content and actions must exist together
     * on exactly one owned window; this is not a fallback to a title-only placeholder.
     */
    private fun originalDialogSurface(title: String, body: List<String>, confirm: (String) -> Boolean,
        cancel: String? = null, showing: Boolean = true): OriginalDialogSurface? {
        val main = mainWindow()
        val windows = listOf<Window>(main) + Window.getWindows().filterIsInstance<JDialog>()
            .filter { it.isDisplayable && ownedByMain(it) }
        val matches = windows.filter { window ->
            window.isDisplayable && (!showing || window.isShowing) && accessible(window).let { rows ->
                val names = rows.map { it.accessibleName.orEmpty() }
                names.any { it == title } && body.all { anchor -> names.any { it.contains(anchor) } } &&
                    rows.count { confirm(it.accessibleName.orEmpty()) && (it.accessibleAction?.accessibleActionCount ?: 0) > 0 } == 1 &&
                    (cancel == null || rows.count { it.accessibleName == cancel && (it.accessibleAction?.accessibleActionCount ?: 0) > 0 } == 1)
            }
        }
        check(matches.size <= 1) { "More than one owned complete original dialog surface: $title" }
        val window = matches.singleOrNull() ?: return null
        val actualTheme = DesktopThemePrefs(context.store).initialSettings()
        val renderer = resolveAppAlertDialogRenderer(actualTheme.uiStyle,
            buildDesktopAppThemeConfig(actualTheme).nativeMiuixPopupsEnabled)
        return OriginalDialogSurface(window, title, renderer.name, actualTheme.uiStyle.name)
    }

    private fun disclaimer(showing: Boolean = true): OriginalDialogSurface? = originalDialogSurface(
        DISCLAIMER,
        listOf("查询数据由 Aicu 提供", "查询会将目标 UID 和筛选条件发送给 Aicu",
            "请勿将查询结果用于骚扰", "服务可能排队、限流或不可用"),
        confirm = { it.startsWith(CONFIRM) }, cancel = "取消", showing = showing,
    )

    private fun recordSurface(id: String, surface: OriginalDialogSurface) = record(id, mapOf(
        "originalTitle" to JsonPrimitive(surface.title), "windowClass" to JsonPrimitive(surface.window.javaClass.name),
        "surfaceKind" to JsonPrimitive(surface.kind), "originalUiStyle" to JsonPrimitive(surface.uiStyle),
        "originalRendererPolicy" to JsonPrimitive(surface.renderer), "ownedByActualMain" to JsonPrimitive(true),
        "completeOriginalBodyAndActions" to JsonPrimitive(true), "windowShowing" to JsonPrimitive(surface.window.isShowing),
    ))

    private fun diagnosticPrompt(): OriginalDialogSurface? = originalDialogSurface(
        "帮助改进应用", listOf("Windows 版本仅在本地保存脱敏的基础错误与崩溃快照", "启用增强本地诊断",
            "关闭后不保留增强日志；基础错误与崩溃快照仍保留"), confirm = { it == "确定" },
    )

    private fun closeInitialDiagnosticPrompt(phase: String) {
        val settings = context.getSharedPreferences("settings", DesktopPluginContext.MODE_PRIVATE)
        check(!settings.getBoolean("enhanced_diagnostic_logging_enabled", false))
        check(!settings.getBoolean("crash_tracking_enabled", false))
        if (phase == "decline") {
            check(!settings.getBoolean("crash_tracking_consent_shown", false))
            awaitCondition("complete original first-Home diagnostic prompt") { edt { diagnosticPrompt() != null } }
            Thread.sleep(1800) // Original dialog entrance settles before this full-window screenshot.
            edt {
                val surface = requireNotNull(diagnosticPrompt())
                recordSurface("home-diagnostic-original-surface", surface)
                capture("home-diagnostic-default-off", surface.window, "帮助改进应用")
                // Invoke only the original confirm. The original switch stays in its actual false state.
                click(surface.window, "确定")
            }
            awaitCondition("original diagnostic confirm saved false and dismissed") { edt { diagnosticPrompt() == null } &&
                settings.getBoolean("crash_tracking_consent_shown", false) &&
                !settings.getBoolean("enhanced_diagnostic_logging_enabled", true) && !settings.getBoolean("crash_tracking_enabled", true) }
            check(consentVersion() == 0 && diskConsentVersion() == 0)
            edt { capture("home-diagnostic-dismissed", mainWindow(), "推荐") }
        } else {
            check(settings.getBoolean("crash_tracking_consent_shown", false))
            check(edt { diagnosticPrompt() == null })
        }
        observations["originalDiagnosticConfirmPrecondition"] = JsonPrimitive(true)
        observations["enhancedDiagnosticLoggingStillDisabled"] = JsonPrimitive(true)
        observations["diagnosticSwitchInvoked"] = JsonPrimitive(false)
    }

    private data class Gate(val label: String, val seconds: Int, val enabled: Boolean)

    private fun gate(): Gate = edt {
        val surface = requireNotNull(disclaimer(false)) { "Owned complete original consent surface missing" }
        val matches = accessible(surface.window).filter { it.accessibleName.orEmpty().startsWith(CONFIRM) &&
            (it.accessibleAction?.accessibleActionCount ?: 0) > 0 }
        check(matches.size == 1) { "Expected one original confirm control, found ${matches.size}" }
        val button = matches.single()
        val label = button.accessibleName.orEmpty()
        val seconds = if (label == CONFIRM) 0 else requireNotNull(Regex("（(\\d+) 秒）").find(label)) { "Unexpected confirm label: $label" }.groupValues[1].toInt()
        Gate(label, seconds, button.accessibleStateSet.contains(AccessibleState.ENABLED))
    }

    private fun visibleUpperBoundMs(): Long = (System.nanoTime() - visibleUpperBoundStart - verifiedPausedNanos) / 1_000_000

    private fun checkGateTiming(gate: Gate) {
        if (gate.enabled) check(visibleUpperBoundMs() >= 5000) { "Accept became enabled before five possible visible foreground seconds" }
        if (gate.seconds > 0) check(!gate.enabled) { "Original countdown label still pending but control enabled" }
    }

    private fun record(id: String, values: Map<String, JsonElement>) {
        Files.writeString(report.resolve("$id.json"), JsonObject(values).toString()+"\n")
    }

    private fun layer(component: Component, type: Class<*>): Any? {
        if (type.isInstance(component)) return component
        if (component is Container) component.components.forEach { layer(it, type)?.let { found -> return found } }
        return null
    }

    /** Skia capture is limited to this process's main or original owned dialog surface. */
    private fun capture(id: String, target: Window, anchor: String) {
        check(ownedByMain(target) && target.isShowing) { "Capture target is not a visible fixture-owned window" }
        val rows = accessible(target)
        Files.writeString(report.resolve("$id-accessibility.tsv"), rows.joinToString("\n") {
            "${it.accessibleName}\t${it.accessibleRole}\t${it.accessibleStateSet}\t${it.accessibleAction?.accessibleActionCount ?: 0}\t${it.accessibleEditableText != null}"
        })
        check(rows.any { it.accessibleName.orEmpty().contains(anchor) }) { "Original anchor missing: $anchor" }
        check(rows.none { it.accessibleName.orEmpty().contains("该原版设置页面仍在移植中") })
        val type = Class.forName("org.jetbrains.skiko.SkiaLayer")
        val actual = requireNotNull(layer(target, type)) { "Owned real Skia surface missing" }
        val renderer = type.getMethod("getRenderApi").invoke(actual).toString()
        check(renderer == "DIRECT3D") { "Unexpected default renderer: $renderer" }
        target.javaClass.methods.firstOrNull { it.name == "renderImmediately" && it.parameterCount == 0 }?.invoke(target)
        val bitmap = requireNotNull(type.getMethod("screenshot").invoke(actual))
        try {
            val imageType = Class.forName("org.jetbrains.skia.Image")
            val companion = imageType.getField("Companion").get(null)
            val image = companion.javaClass.getMethod("makeFromBitmap", bitmap.javaClass).invoke(companion, bitmap)
            try {
                val formatType = Class.forName("org.jetbrains.skia.EncodedImageFormat")
                val data = requireNotNull(imageType.getMethod("encodeToData", formatType, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType).invoke(image, formatType.getField("PNG").get(null), 100, 6))
                try { Files.write(report.resolve("$id.png"), data.javaClass.getMethod("getBytes").invoke(data) as ByteArray) }
                finally { data.javaClass.getMethod("close").invoke(data) }
            } finally { imageType.getMethod("close").invoke(image) }
        } finally { bitmap.javaClass.getMethod("close").invoke(bitmap) }
        record("$id-window", mapOf("targetClass" to JsonPrimitive(target.javaClass.name),
            "ownedByActualMain" to JsonPrimitive(true), "originalDialog" to JsonPrimitive(target is JDialog),
            "renderer" to JsonPrimitive(renderer), "rootSerial" to JsonPrimitive(latest.get()?.serial)))
    }

    private fun awaitCondition(description: String, seconds: Long = 20, condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos()
        while (System.nanoTime() < deadline) { if (condition()) return; Thread.sleep(40) }
        edt {
            Window.getWindows().filter { it.isDisplayable && ownedByMain(it) }.forEachIndexed { i, window ->
                Files.writeString(report.resolve("timeout-$i-accessibility.txt"), names(window).joinToString("\n"))
            }
        }
        error("Actual Root UI timed out: $description; latest=${latest.get()?.key}")
    }

    private fun awaitPage(key: BiliPaiNavKey, after: Long, anchor: String, settle: Boolean): DesktopOriginalRootValidationTap.Frame {
        awaitCondition("$key / $anchor") {
            val current = latest.get()
            current != null && current.serial > after && current.key == key && current.handle.isActive() && current.routes.owns() &&
                edt { names(mainWindow()).any { it.contains(anchor) } }
        }
        if (settle) Thread.sleep(1800)
        return requireNotNull(latest.get()).also { check(it.key == key && it.handle.isActive() && it.routes.owns()) }
    }

    private fun click(window: Window, label: String) = edt {
        check(ownedByMain(window) && window.isShowing)
        val buttons = accessible(window).filter { it.accessibleName == label && (it.accessibleAction?.accessibleActionCount ?: 0) > 0 }
        check(buttons.size == 1) { "Expected one original action '$label', found ${buttons.size}" }
        check(buttons.single().accessibleStateSet.contains(AccessibleState.ENABLED)) { "Original action disabled: $label" }
        check(buttons.single().accessibleAction.doAccessibleAction(0))
    }

    private fun systemBack(frame: DesktopOriginalRootValidationTap.Frame) = edt { check(frame.handle.navigation.requestBack()) }

    private fun consentVersion(): Int = context.getSharedPreferences("settings", DesktopPluginContext.MODE_PRIVATE).getInt(CONSENT_KEY, 0)

    private fun diskConsentVersion(): Int {
        val file = DesktopLibrary.directoryForAccount(null).resolve("plugin-settings.json")
        if (!Files.exists(file)) return 0
        check(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file))
        val json = Json.parseToJsonElement(Files.readString(file)).jsonObject
        return (json["settings"] as? JsonObject)?.get(CONSENT_KEY)?.jsonPrimitive?.intOrNull ?: 0
    }

    private fun openAicu(current: DesktopOriginalRootValidationTap.Frame): DesktopOriginalRootValidationTap.Frame {
        val key = BiliPaiNavKey.AicuQuery(uid = 0L)
        visibleUpperBoundStart = System.nanoTime(); verifiedPausedNanos = 0L
        edt { check(current.routes.push(key)) }
        val page = awaitPage(key, current.serial, "评论与弹幕查询", false)
        check(page.handle === current.handle && page.routes === current.routes)
        return page
    }

    private fun awaitInitialGate(): Gate {
        awaitCondition("owned complete original consent dialog surface") { edt { disclaimer() != null } }
        val initial = gate()
        check(initial.seconds in 1..5 && !initial.enabled) { "Initial real consent control was not disabled: $initial" }
        checkGateTiming(initial)
        record("initial-gate", mapOf("label" to JsonPrimitive(initial.label), "disabled" to JsonPrimitive(true),
            "visibleUpperBoundMs" to JsonPrimitive(visibleUpperBoundMs()), "ownedOriginalSurface" to JsonPrimitive(true)))
        edt { recordSurface("initial-consent-original-surface", requireNotNull(disclaimer())) }
        return initial
    }

    private fun assertNoAicuModalSurfaces() = edt {
        Window.getWindows().filter { it.isDisplayable && ownedByMain(it) }.forEach { window ->
            val actualNames = names(window)
            check(actualNames.none { it == DISCLAIMER || it.startsWith(CONFIRM) || it == "Aicu 24 小时热搜" }) {
                "A retained Aicu modal is still exposed on a covered or minimized leaf"
            }
            check(!(actualNames.any { it == "使用说明" } && actualNames.any { it.contains("查询数据由 Aicu 提供") })) {
                "A retained Aicu information modal is still exposed"
            }
        }
    }

    private fun decline(page: DesktopOriginalRootValidationTap.Frame) {
        awaitInitialGate()
        // Progress once before hiding: a recreated five-second state must not pass as retention.
        awaitCondition("original consent countdown progresses before minimize") {
            val current = gate(); checkGateTiming(current)
            !current.enabled && current.seconds in 2..4
        }
        val beforeMinimize = gate(); check(!beforeMinimize.enabled && beforeMinimize.seconds in 2..4)
        // Minimize the actual main window. The production owner derives CREATED from real state.
        edt { mainWindow().extendedState = mainWindow().extendedState or AwtFrame.ICONIFIED }
        awaitCondition("actual window iconified and Root below RESUMED") { edt {
            mainWindow().extendedState and AwtFrame.ICONIFIED != 0 && !page.handle.navigation.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        } }
        Thread.sleep(180) // Allow the original lifecycle/foreground observer to retire the actual surface.
        awaitCondition("minimized original Aicu modal is absent") { edt { disclaimer(false) == null } }
        assertNoAicuModalSurfaces()
        val pauseStart = System.nanoTime()
        Thread.sleep(3100)
        val pauseEnd = System.nanoTime()
        check(pauseEnd - pauseStart >= 3_100_000_000L)
        check(edt { mainWindow().extendedState and AwtFrame.ICONIFIED != 0 &&
            !page.handle.navigation.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) })
        check(edt { page.routes.currentKey == BiliPaiNavKey.AicuQuery(uid = 0L) && page.handle.route.get() === page.routes })
        assertNoAicuModalSurfaces()
        check(consentVersion() == 0 && diskConsentVersion() == 0)
        verifiedPausedNanos += pauseEnd - pauseStart
        edt { mainWindow().extendedState = AwtFrame.NORMAL; mainWindow().toFront(); mainWindow().requestFocus() }
        awaitCondition("actual Root resumes with owned complete consent surface") { edt { page.handle.navigation.lifecycle.currentState == Lifecycle.State.RESUMED && disclaimer() != null } }
        val restoredAfterMinimize = gate(); checkGateTiming(restoredAfterMinimize)
        check(!restoredAfterMinimize.enabled && restoredAfterMinimize.seconds in (beforeMinimize.seconds - 1)..beforeMinimize.seconds) {
            "Minimized hidden time consumed or reset original countdown: $beforeMinimize -> $restoredAfterMinimize"
        }
        check(edt { page.handle.isActive() && page.routes.owns() && page.handle.route.get() === page.routes &&
            page.routes.currentKey == BiliPaiNavKey.AicuQuery(uid = 0L) })
        record("minimize-pause", mapOf("originalLabelBefore" to JsonPrimitive(beforeMinimize.label),
            "originalLabelAfter" to JsonPrimitive(restoredAfterMinimize.label), "verifiedPauseMs" to JsonPrimitive((pauseEnd-pauseStart)/1_000_000),
            "actualRootLifecycleBelowResumed" to JsonPrimitive(true), "hiddenOriginalModalAbsent" to JsonPrimitive(true),
            "sameLiveOriginalOwnerAndRoute" to JsonPrimitive(true), "transitionFractionToleranceSeconds" to JsonPrimitive(1)))
        val beforeCover = gate(); checkGateTiming(beforeCover); check(!beforeCover.enabled)
        edt { check(page.routes.push(BiliPaiNavKey.TipsSettings)) }
        val covered = awaitPage(BiliPaiNavKey.TipsSettings, page.serial, "摸鱼模式", false)
        check(covered.handle === page.handle && covered.routes === page.routes)
        awaitCondition("covered original Aicu modal is absent") { edt { disclaimer(false) == null } }
        assertNoAicuModalSurfaces()
        check(edt { covered.routes.currentKey == BiliPaiNavKey.TipsSettings &&
            covered.handle.route.get() === covered.routes && mainWindow().isShowing &&
            covered.handle.navigation.lifecycle.currentState == Lifecycle.State.RESUMED })
        val coverStart = System.nanoTime()
        Thread.sleep(3100)
        val visibleDialogWhileCovered = edt { disclaimer() != null }
        val coveredSurfaceKind = edt { disclaimer(false)?.kind }
        check(!visibleDialogWhileCovered && coveredSurfaceKind == null)
        assertNoAicuModalSurfaces()
        check(consentVersion() == 0 && diskConsentVersion() == 0)
        edt { capture("covered-original-tips", mainWindow(), "摸鱼模式") }
        val coverEnd = System.nanoTime()
        verifiedPausedNanos += coverEnd-coverStart
        systemBack(covered)
        val returned = awaitPage(BiliPaiNavKey.AicuQuery(uid = 0L), covered.serial, "评论与弹幕查询", false)
        awaitCondition("original Aicu consent returns after actual Back") { edt { disclaimer() != null } }
        check(returned.handle === page.handle && returned.routes === page.routes)
        check(edt { returned.routes.currentKey == BiliPaiNavKey.AicuQuery(uid = 0L) && returned.handle.route.get() === returned.routes })
        val returnedGate = gate(); checkGateTiming(returnedGate)
        check(!returnedGate.enabled && returnedGate.seconds in (beforeCover.seconds - 1)..beforeCover.seconds) {
            "Real covered countdown consumed hidden time or reset retained consent state"
        }
        record("route-cover-pause", mapOf("originalLabelBefore" to JsonPrimitive(beforeCover.label),
            "originalLabelAfter" to JsonPrimitive(returnedGate.label), "verifiedCoverMs" to JsonPrimitive((coverEnd-coverStart)/1_000_000),
            "ownedConsentDialogVisibleWhileCovered" to JsonPrimitive(visibleDialogWhileCovered),
            "coveredConsentSurfaceKind" to JsonPrimitive(coveredSurfaceKind),
            "coveredOriginalModalAbsent" to JsonPrimitive(true),
            "originalConsentStateRetainedWithoutReset" to JsonPrimitive(true),
            "sameRootAndRouteAssembly" to JsonPrimitive(true)))
        edt {
            val surface = requireNotNull(disclaimer())
            recordSurface("decline-consent-original-surface", surface)
            capture("decline-consent-dialog", surface.window, DISCLAIMER)
        }
        // Deliver the production completed-back dispatcher, rather than directly popping its stack.
        systemBack(returned)
        val home = awaitPage(BiliPaiNavKey.Home, returned.serial, "推荐", true)
        check(home.handle === page.handle)
        check(edt { disclaimer(false) == null })
        check(consentVersion() == 0 && diskConsentVersion() == 0) { "Declining persisted Aicu consent" }
        edt { capture("decline-home", mainWindow(), "推荐") }
        observations["minimizeCountdownPaused"] = JsonPrimitive(true)
        observations["coveredCountdownPaused"] = JsonPrimitive(true)
        observations["declineCompletedBackWithoutConsentWrite"] = JsonPrimitive(true)
        observations["coveredDialogStillVisible"] = JsonPrimitive(visibleDialogWhileCovered)
    }

    private fun field(label: String): AccessibleContext {
        val all = accessible(mainWindow())
        val fields = all.filter { node -> node.accessibleEditableText != null &&
            (node.accessibleName.orEmpty().contains(label) || mutableListOf<AccessibleContext>().also { nodes(node, it) }.any { it.accessibleName.orEmpty().contains(label) }) }
        check(fields.size == 1) { "Expected one original editable '$label', found ${fields.size}; no ViewModel fallback is permitted" }
        return fields.single()
    }

    private fun fieldValue(label: String): String = edt {
        val text = requireNotNull(field(label).accessibleText)
        buildString { repeat(text.charCount) { append(text.getAtIndex(AccessibleText.CHARACTER, it).orEmpty()) } }
    }

    private fun editField(label: String, value: String) {
        edt { requireNotNull(field(label).accessibleEditableText).setTextContents(value) }
        awaitCondition("original editable '$label' value") { fieldValue(label) == value }
    }

    private fun assertIdle() = edt {
        val names = names(mainWindow())
        check(names.any { it.contains(IDLE) }) { "UID zero unexpectedly left original idle state" }
        check(names.none { it.contains("正在申请查询") || it.contains("排队中，前面还有") || it == "正在加载…" || it.startsWith("第 ") && it.contains(" 页") })
    }

    private fun selected(label: String, role: AccessibleRole) = edt {
        // Actual original category tabs and comment-mode radios have different roles.
        // Retained background Home/Search content can expose an unrelated tab named 全部.
        val named = accessible(mainWindow()).filter { it.accessibleName == label && (it.accessibleAction?.accessibleActionCount ?: 0) > 0 }
        val controls = named.filter { it.accessibleRole == role }
        check(controls.size == 1) {
            "Expected one original '$label' with semantic role $role; matching=${controls.size}; namedRoles=${named.map { it.accessibleRole }}"
        }
        controls.single().accessibleStateSet.contains(AccessibleState.SELECTED) || controls.single().accessibleStateSet.contains(AccessibleState.CHECKED)
    }

    private fun filters() {
        val categories = listOf("评论" to "fixture-comment", "视频弹幕" to "fixture-video", "直播弹幕" to "fixture-live")
        check(fieldValue("B 站用户 UID").isBlank())
        for ((index, category) in categories.withIndex()) {
            if (index > 0) edt { click(mainWindow(), category.first) }
            awaitCondition("selected original category ${category.first}") { selected(category.first, AccessibleRole.PAGE_TAB) }
            check(fieldValue("关键词").isBlank() && fieldValue("开始日期").isBlank() && fieldValue("结束日期").isBlank())
            editField("关键词", category.second)
            editField("开始日期", "2026-10-01")
            editField("结束日期", "2026-10-03")
            if (index == 0) {
                edt { click(mainWindow(), "一级") }
                awaitCondition("selected original comment mode") { selected("一级", AccessibleRole.RADIO_BUTTON) }
            } else check(edt { names(mainWindow()).none { it == "评论类型" } })
            assertIdle()
            Thread.sleep(350)
            edt {
                // Editable values are original AccessibleText content, not accessibleName.
                // fieldValue requires exactly one actual editable control for each original label.
                val actualUid = fieldValue("B 站用户 UID")
                val actualKeyword = fieldValue("关键词")
                val actualStartDate = fieldValue("开始日期")
                val actualEndDate = fieldValue("结束日期")
                check(actualUid.isBlank())
                check(actualKeyword == category.second)
                check(actualStartDate == "2026-10-01" && actualEndDate == "2026-10-03")
                check(selected(category.first, AccessibleRole.PAGE_TAB))
                record("filters-$index-ui-readback", mapOf(
                    "categoryLabel" to JsonPrimitive(category.first), "categorySelected" to JsonPrimitive(true),
                    "uid" to JsonPrimitive(actualUid), "keyword" to JsonPrimitive(actualKeyword),
                    "startDate" to JsonPrimitive(actualStartDate), "endDate" to JsonPrimitive(actualEndDate),
                    "uniqueOriginalEditableControls" to JsonPrimitive(true),
                    "valuesReadFromOriginalAccessibleText" to JsonPrimitive(true),
                ))
                capture("filters-$index", mainWindow(), "关键词")
            }
        }
        edt { click(mainWindow(), "评论") }
        awaitCondition("comment draft restored") { fieldValue("关键词") == "fixture-comment" }
        check(fieldValue("开始日期") == "2026-10-01" && fieldValue("结束日期") == "2026-10-03" && selected("一级", AccessibleRole.RADIO_BUTTON))
        assertIdle()
        // Original reset submits the empty UID. This proves its local validation without Aicu traffic.
        edt { click(mainWindow(), "重置筛选") }
        awaitCondition("original filter reset") { fieldValue("关键词").isBlank() && fieldValue("开始日期").isBlank() && fieldValue("结束日期").isBlank() && selected("全部", AccessibleRole.RADIO_BUTTON) }
        awaitCondition("original invalid UID message") { edt { names(mainWindow()).any { it.contains("请输入有效的正整数 UID。") } } }
        assertIdle()
        // The original selected pill animates independently from its semantic state.
        Thread.sleep(1800)
        check(selected("评论", AccessibleRole.PAGE_TAB) && selected("全部", AccessibleRole.RADIO_BUTTON))
        check(fieldValue("B 站用户 UID").isBlank() && fieldValue("关键词").isBlank() &&
            fieldValue("开始日期").isBlank() && fieldValue("结束日期").isBlank())
        assertIdle()
        edt { capture("filters-reset-local-validation", mainWindow(), "请输入有效的正整数 UID。") }
        observations["threeOriginalCategoryTabsAndDrafts"] = JsonPrimitive(true)
        observations["originalCommentModeDraftRetained"] = JsonPrimitive(true)
        observations["originalResetAndInvalidUidValidation"] = JsonPrimitive(true)
    }

    private fun acceptOrCold(page: DesktopOriginalRootValidationTap.Frame, cold: Boolean) {
        if (cold) {
            check(consentVersion() == 1 && diskConsentVersion() == 1)
        } else {
            awaitInitialGate()
            edt {
                val surface = requireNotNull(disclaimer())
                recordSurface("accept-disabled-consent-original-surface", surface)
                capture("accept-disabled-owned-dialog", surface.window, DISCLAIMER)
            }
            awaitCondition("five actual visible foreground seconds before original confirm enabled", 15) {
                val current = gate(); checkGateTiming(current)
                if (!current.enabled) false else {
                    check(current.seconds == 0 && edt { page.handle.navigation.lifecycle.currentState == Lifecycle.State.RESUMED })
                    true
                }
            }
            record("enabled-gate", mapOf("visibleUpperBoundMs" to JsonPrimitive(visibleUpperBoundMs()), "defaultRenderer" to JsonPrimitive("DIRECT3D")))
            edt { click(requireNotNull(disclaimer()).window, CONFIRM) }
        }
        awaitCondition("original accepted UI and UID-zero idle") { edt { disclaimer(false) == null && names(mainWindow()).any { it.contains(IDLE) } } }
        check(consentVersion() == 1 && diskConsentVersion() == 1)
        assertIdle()
        Thread.sleep(1800)
        assertIdle()
        edt { capture(if (cold) "cold-accepted-without-dialog" else "accepted-uid-zero-idle", mainWindow(), IDLE) }
        filters()
        val afterFilters = requireNotNull(latest.get())
        systemBack(afterFilters)
        val home = awaitPage(BiliPaiNavKey.Home, afterFilters.serial, "推荐", true)
        check(home.handle === page.handle)
        check(consentVersion() == 1 && diskConsentVersion() == 1)
        observations[if (cold) "coldOriginalConsentReadBack" else "originalEnabledConfirmPersistedConsent"] = JsonPrimitive(true)
        observations["uidZeroAcceptedOriginalIdleWithoutAutoQueryObserved"] = JsonPrimitive(true)
        observations["acceptedActualSystemBack"] = JsonPrimitive(true)
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 4)
        report = Path.of(args[0]).toRealPath()
        val health = Path.of(args[1]); val token = args[2]; val phase = args[3]
        require(phase in listOf("decline", "accept", "cold"))
        // install verifies the private temp marker and token before any fixture persistence.
        DesktopOriginalRootValidationTap.install { latest.set(it) }.use {
            context = DesktopPluginContext(DesktopPluginStore(DesktopLibrary.directoryForAccount(null)))
            if (phase == "decline") {
                check(consentVersion() == 0 && diskConsentVersion() == 0)
                context.getSharedPreferences("app_welcome", DesktopPluginContext.MODE_PRIVATE).edit()
                    .putBoolean("user_agreement_ack_v1", true).putBoolean("first_launch_shown", true)
                    .putBoolean("release_disclaimer_ack_v1", true).apply()
            }
            check(consentVersion() == if (phase == "cold") 1 else 0)
            observations["phase"] = JsonPrimitive(phase)
            observations["unrelatedWelcomeAckSeededPrecondition"] = JsonPrimitive(true)
            observations["onboardingAcceptanceCovered"] = JsonPrimitive(false)
            observations["aicuConsentPreseeded"] = JsonPrimitive(false)
            val observer = Thread({
                try {
                    val home = awaitPage(BiliPaiNavKey.Home, 0, "推荐", true)
                    awaitCondition("actual Main healthy Root ACK", 10) { Files.isRegularFile(health) }
                    check(Files.readString(health).trim() == token)
                    closeInitialDiagnosticPrompt(phase)
                    val page = openAicu(requireNotNull(latest.get()).also { check(it.key == BiliPaiNavKey.Home && it.handle === home.handle) })
                    if (phase == "decline") decline(page) else acceptOrCold(page, phase == "cold")
                    observations["sameLiveRootOwner"] = JsonPrimitive(true)
                    observations["actualSourceSet"] = JsonPrimitive("test.runtimeClasspath; unchanged production Main")
                    observations["defaultRenderer"] = JsonPrimitive("DIRECT3D")
                    observations["realAccountUsed"] = JsonPrimitive(false)
                    observations["publicAicuResponseSuccessCovered"] = JsonPrimitive(false)
                    observations["allRoutesAccepted"] = JsonPrimitive(false)
                    Files.writeString(report.resolve("observations.json"), JsonObject(observations).toString()+"\n")
                    completed.set(true)
                    edt { mainWindow().dispatchEvent(WindowEvent(mainWindow(), WindowEvent.WINDOW_CLOSING)) }
                } catch (failure: Throwable) {
                    failure.printStackTrace()
                    kotlin.system.exitProcess(91)
                }
            }, "Original Aicu actual Root observer $phase")
            observer.isDaemon = true; observer.start()
            com.bilipai.desktop.main(arrayOf("--update-health-file", health.toString(), "--update-health-token", token))
            check(completed.get()) { "Window closed before original Aicu observations completed" }
        }
    }
}
