package com.bilipai.desktop.ui

import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.android.purebilibili.feature.settings.SettingsRootCategory
import com.android.purebilibili.feature.settings.SettingsSearchTarget
import com.android.purebilibili.feature.settings.settingsDestinationCopy
import com.bilipai.desktop.appearance.DesktopLanguageRuntime
import com.bilipai.desktop.appearance.DesktopStrings
import java.awt.Component
import java.awt.Container
import java.awt.EventQueue
import java.awt.Window
import java.awt.event.WindowEvent
import java.awt.event.MouseWheelEvent
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import javax.accessibility.AccessibleContext
import javax.swing.JFrame

/** Runs the unchanged Main and invokes only its own live Root's real navigation commands. */
object OriginalStaticSettingsUiFixture {
    private val latest = AtomicReference<DesktopOriginalRootValidationTap.Frame?>()
    private val completed = AtomicReference(false)
    private lateinit var report: Path

    private fun window(): JFrame = Window.getWindows().filterIsInstance<JFrame>()
        .filter { it.isShowing && it.title == "BiliPai Windows" }.single()

    private fun nodes(context: AccessibleContext?, result: MutableList<AccessibleContext>, depth: Int = 0) {
        if (context == null || depth > 80) return
        result.add(context)
        repeat(context.accessibleChildrenCount) {
            nodes(context.getAccessibleChild(it)?.accessibleContext, result, depth + 1)
        }
    }

    private fun accessible(): List<AccessibleContext> = mutableListOf<AccessibleContext>().also {
        nodes(window().accessibleContext, it)
    }

    private fun layer(component: Component, type: Class<*>): Any? {
        if (type.isInstance(component)) return component
        if (component is Container) component.components.forEach { layer(it, type)?.let { found -> return found } }
        return null
    }

    private fun capture(id: String, anchor: String) {
        val frame = window()
        val rows = accessible()
        Files.writeString(report.resolve("$id-accessibility.tsv"), rows.joinToString("\n") {
            "${it.accessibleName}\t${it.accessibleRole}\t${it.accessibleAction?.accessibleActionCount ?: 0}"
        })
        check(rows.any { it.accessibleName.orEmpty().contains(anchor) }) { "Original page anchor missing: $anchor" }
        check(rows.none { it.accessibleName.orEmpty().contains("该原版设置页面仍在移植中") })
        val type = Class.forName("org.jetbrains.skiko.SkiaLayer")
        val actual = requireNotNull(layer(frame, type))
        val renderer = type.getMethod("getRenderApi").invoke(actual).toString()
        check(renderer == "DIRECT3D") { "Unexpected default renderer: $renderer" }
        frame.javaClass.getMethod("renderImmediately").invoke(frame)
        val bitmap = requireNotNull(type.getMethod("screenshot").invoke(actual))
        try {
            val imageType = Class.forName("org.jetbrains.skia.Image")
            val companion = imageType.getField("Companion").get(null)
            val image = companion.javaClass.getMethod("makeFromBitmap", bitmap.javaClass).invoke(companion, bitmap)
            try {
                val formatType = Class.forName("org.jetbrains.skia.EncodedImageFormat")
                val format = formatType.getField("PNG").get(null)
                val data = requireNotNull(imageType.getMethod("encodeToData", formatType, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType).invoke(image, format, 100, 6))
                try { Files.write(report.resolve("$id.png"), data.javaClass.getMethod("getBytes").invoke(data) as ByteArray) }
                finally { data.javaClass.getMethod("close").invoke(data) }
            } finally { imageType.getMethod("close").invoke(image) }
        } finally { bitmap.javaClass.getMethod("close").invoke(bitmap) }
        Files.writeString(report.resolve("$id-frame.txt"), "${latest.get()?.key}\n${latest.get()?.serial}\n$renderer\n")
    }

    private fun awaitPage(key: BiliPaiNavKey, after: Long, anchor: String, id: String,
        bodyAnchor: String? = null): DesktopOriginalRootValidationTap.Frame {
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (System.nanoTime() < deadline) {
            val observation = latest.get()
            if (observation != null && observation.serial > after && observation.key == key && observation.handle.isActive() && observation.routes.owns()) {
                var present = false
                EventQueue.invokeAndWait {
                    val names = accessible().map { it.accessibleName.orEmpty() }
                    present = names.any { it.contains(anchor) } && (bodyAnchor == null || names.any { it.contains(bodyAnchor) })
                }
                if (present) {
                    // Accessibility can expose text while its original entrance/route animation is still transparent.
                    Thread.sleep(1800)
                    val settled = requireNotNull(latest.get())
                    check(settled.key == key && settled.handle === observation.handle && settled.routes.owns())
                    EventQueue.invokeAndWait { capture(id, anchor) }
                    return settled
                }
            }
            Thread.sleep(100)
        }
        EventQueue.invokeAndWait {
            Files.writeString(report.resolve("$id-timeout-accessibility.tsv"), accessible().joinToString("\n") { it.accessibleName.orEmpty() })
        }
        error("Real active page/frame/anchor timed out: $key / $anchor; latest=${latest.get()?.key}")
    }

    private fun clickBack() {
        EventQueue.invokeAndWait {
            val label = DesktopStrings.forLanguage(DesktopLanguageRuntime.requested.value)["common_back"]
            val buttons = accessible().filter { it.accessibleName == label && (it.accessibleAction?.accessibleActionCount ?: 0) == 1 }
            check(buttons.size == 1) { "Expected exactly one original accessible Back action: ${buttons.size}" }
            check(buttons.single().accessibleAction.doAccessibleAction(0))
        }
    }

    private fun clickEntry(label: String) {
        EventQueue.invokeAndWait {
            val entries = accessible().filter {
                it.accessibleName.orEmpty().contains(label) && (it.accessibleAction?.accessibleActionCount ?: 0) == 1
            }
            check(entries.size == 1) { "Expected one original settings entry: $label / ${entries.size}" }
            check(entries.single().accessibleAction.doAccessibleAction(0))
        }
    }

    private fun scrollTo(anchor: String, id: String) {
        repeat(20) {
            var present = false
            EventQueue.invokeAndWait { present = accessible().any { it.accessibleName.orEmpty().contains(anchor) } }
            if (present) {
                Thread.sleep(1800)
                EventQueue.invokeAndWait { capture(id, anchor) }
                return
            }
            EventQueue.invokeAndWait {
                val actual = layer(window(), Class.forName("org.jetbrains.skiko.SkiaLayer")) as Component
                actual.dispatchEvent(MouseWheelEvent(actual, MouseWheelEvent.MOUSE_WHEEL, System.currentTimeMillis(),
                    0, actual.width / 2, actual.height / 2, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 8))
            }
            Thread.sleep(250)
        }
        error("Original list did not scroll to its last item: $anchor")
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3)
        report = Path.of(args[0]).toRealPath()
        val health = Path.of(args[1])
        val token = args[2]
        DesktopOriginalRootValidationTap.install { latest.set(it) }.use {
            val observer = Thread({
                try {
                    val initial = awaitPage(BiliPaiNavKey.Home, 0, "推荐", "000-home")
                    val healthDeadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
                    while (!Files.isRegularFile(health)) {
                        check(System.nanoTime() < healthDeadline) { "Actual startup health acknowledgement timed out" }
                        Thread.sleep(100)
                    }
                    check(Files.readString(health).trim() == token)
                    val owner = initial.handle
                    var current = initial
                    val strings = DesktopStrings.forLanguage(DesktopLanguageRuntime.requested.value)
                    for ((index, destination) in listOf(Triple(BiliPaiNavKey.TipsSettings, strings["tips_title"], "摸鱼模式"),
                        Triple(BiliPaiNavKey.OpenSourceLicenses, strings["open_source_licenses_title"], "Kotlin")).withIndex()) {
                        EventQueue.invokeAndWait { check(current.routes.push(destination.first)) }
                        current = awaitPage(destination.first, current.serial, destination.second, "${index + 1}-detail", destination.third)
                        check(current.handle === owner)
                        scrollTo(if (index == 0) "12. 链接默认打开" else "AndroidX Benchmark / Tracing / UIAutomator",
                            "${index + 1}-last-item")
                        clickBack()
                        current = awaitPage(BiliPaiNavKey.Home, current.serial, "推荐", "${index + 1}-back")
                        check(current.handle === owner)
                    }
                    val about = BiliPaiNavKey.SettingsCategory(SettingsRootCategory.SYSTEM_ABOUT)
                    EventQueue.invokeAndWait { check(current.routes.push(about)) }
                    current = awaitPage(about, current.serial, "系统与关于", "3-system-about")
                    for ((index, target) in listOf(SettingsSearchTarget.TIPS, SettingsSearchTarget.OPEN_SOURCE_LICENSES).withIndex()) {
                        clickEntry(settingsDestinationCopy(target).title)
                        current = awaitPage(about, current.serial,
                            strings[if (index == 0) "tips_title" else "open_source_licenses_title"],
                            "${index + 4}-settings-entry", if (index == 0) "摸鱼模式" else "Kotlin")
                        check(current.handle === owner)
                        clickBack()
                        current = awaitPage(about, current.serial, "系统与关于", "${index + 4}-settings-back")
                    }
                    EventQueue.invokeAndWait { check(current.routes.back()) }
                    awaitPage(BiliPaiNavKey.Home, current.serial, "推荐", "6-final-home")
                    Files.writeString(report.resolve("observations.json"), "{\"twoPhysicalOriginalPagesRendered\":true,\"originalBackActionsPassed\":true,\"lastOriginalListItemsReached\":true,\"systemAboutEntriesAndInternalBackPassed\":true,\"sameLiveRootOwner\":true,\"defaultRenderer\":\"DIRECT3D\",\"allRoutesAccepted\":false,\"realAccountUsed\":false}")
                    completed.set(true)
                    EventQueue.invokeAndWait { window().dispatchEvent(WindowEvent(window(), WindowEvent.WINDOW_CLOSING)) }
                } catch (failure: Throwable) {
                    failure.printStackTrace()
                    kotlin.system.exitProcess(91)
                }
            }, "Original settings actual Root observer")
            observer.isDaemon = true
            observer.start()
            com.bilipai.desktop.main(arrayOf("--update-health-file", health.toString(), "--update-health-token", token))
            check(completed.get()) { "Window closed before original settings observations completed" }
        }
    }
}
