package com.bilipai.desktop.ui

import com.android.purebilibili.feature.onboarding.APP_WELCOME_PREFS_NAME
import com.android.purebilibili.feature.onboarding.USER_AGREEMENT_ACK_KEY
import com.android.purebilibili.feature.onboarding.UserAgreementClause
import com.android.purebilibili.feature.onboarding.userAgreementIntroText
import com.android.purebilibili.feature.settings.RELEASE_DISCLAIMER_ACK_KEY
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.DesktopLibrary
import com.bilipai.desktop.update.UpdateStorage
import kotlinx.serialization.json.*
import java.awt.Component
import java.awt.Container
import java.awt.EventQueue
import java.awt.Window
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.time.Duration
import java.util.UUID
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.accessibility.AccessibleState
import javax.swing.JFrame

/** Test-only inputs into one actual isolated Main window. Never edits preferences or routes. */
internal class OriginalOnboardingUiActions(
    private val latest: () -> DesktopOriginalRootValidationTap.Frame?,
    private val report: Path,
    private val token: String,
) {
    val local: Path
    private val storeFile: Path

    init {
        require(UUID.fromString(token).toString() == token)
        require(System.getProperty("bilipai.rootValidationToken") == token)
        local = UpdateStorage.existingPathWithoutLinks(Path.of(requireNotNull(System.getenv("LOCALAPPDATA"))))
        val temp = UpdateStorage.existingPathWithoutLinks(Path.of(System.getProperty("java.io.tmpdir")))
        require(local.startsWith(temp) && local != temp && local.fileName.toString().startsWith("BiliPai-v025-root-routes-"))
        val marker = local.resolve(".bilipai-root-validation")
        require(Files.isRegularFile(marker, NOFOLLOW_LINKS) && !Files.isSymbolicLink(marker))
        require(Files.readString(marker) == token)
        require(UpdateStorage.existingPathWithoutLinks(report) == report.toAbsolutePath().normalize())
        require(Files.isDirectory(report, NOFOLLOW_LINKS))
        val storeRoot = DesktopLibrary.directoryForAccount(null).toAbsolutePath().normalize()
        require(storeRoot.startsWith(local) && storeRoot != local)
        storeFile = storeRoot.resolve("plugin-settings.json")
        // The runner owns a fresh private directory. A cold second JVM may see guest data
        // written by the first actual Main; this helper never reads the encrypted session.
    }

    fun requireFreshGuestStartup() {
        // Match the actual DesktopSessionStore.defaultPath, before the first Main starts.
        require(!Files.exists(local.resolve("BiliPai").resolve("session.json"), NOFOLLOW_LINKS))
        require(!Files.exists(DesktopLibrary.directoryForAccount(null).resolve("accounts"), NOFOLLOW_LINKS))
    }

    fun welcomeFlags(): Map<String, Boolean?> {
        if (!Files.exists(storeFile, NOFOLLOW_LINKS)) return ackKeys.associateWith { null }
        require(UpdateStorage.existingPathWithoutLinks(storeFile) == storeFile)
        require(Files.isRegularFile(storeFile, NOFOLLOW_LINKS))
        val welcome = Json.parseToJsonElement(Files.readString(storeFile)).jsonObject[APP_WELCOME_PREFS_NAME] as? JsonObject
        return ackKeys.associateWith { (welcome?.get(it) as? JsonPrimitive)?.booleanOrNull }
    }

    fun requireAcknowledged(expected: Boolean) {
        val flags = welcomeFlags()
        if (expected) check(flags.values.all { it == true }) { "Actual saved original ACK fields incomplete: $flags" }
        else check(flags.values.none { it == true }) { "Unaccepted agreement was persisted: $flags" }
    }

    private fun owned(frame: DesktopOriginalRootValidationTap.Frame) {
        check(EventQueue.isDispatchThread())
        check(frame.handle.isActive() && frame.routes.owns() && frame.handle.route.get() === frame.routes)
        val current = requireNotNull(latest())
        check(current.handle === frame.handle && current.routes === frame.routes && current.key == frame.key)
    }

    private fun window(): JFrame {
        check(EventQueue.isDispatchThread())
        return Window.getWindows().filterIsInstance<JFrame>()
            .filter { it.isShowing && it.title == "BiliPai Windows" }.single()
    }

    private fun nodes(context: AccessibleContext?, result: MutableList<AccessibleContext>, depth: Int = 0) {
        if (context == null || depth > 80) return
        result.add(context)
        repeat(context.accessibleChildrenCount) { nodes(context.getAccessibleChild(it)?.accessibleContext, result, depth + 1) }
    }

    private fun accessible(): List<AccessibleContext> = mutableListOf<AccessibleContext>().also {
        nodes(window().accessibleContext, it)
    }

    private fun action(label: String, checkbox: Boolean = false): AccessibleContext {
        val matches = accessible().filter {
            (if (checkbox) it.accessibleName.orEmpty().contains(label) else it.accessibleName == label) &&
                (!checkbox || it.accessibleRole == AccessibleRole.CHECK_BOX) &&
                (it.accessibleAction?.accessibleActionCount ?: 0) == 1
        }
        check(matches.size == 1) { "Expected one original accessible action for '$label', got ${matches.size}" }
        return matches.single()
    }

    private fun await(id: String, predicate: (DesktopOriginalRootValidationTap.Frame) -> Boolean,
        anchors: List<String> = emptyList()): DesktopOriginalRootValidationTap.Frame {
        val deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos()
        while (System.nanoTime() < deadline) {
            val frame = latest()
            if (frame != null && predicate(frame)) {
                var present = false
                EventQueue.invokeAndWait {
                    owned(frame)
                    val names = accessible().map { it.accessibleName.orEmpty() }
                    present = anchors.all { anchor -> names.any { it.contains(anchor) } }
                }
                if (present) {
                    Thread.sleep(1800) // Let the original entrance animation settle before the screenshot.
                    val current = requireNotNull(latest())
                    check(predicate(current) && current.handle === frame.handle && current.routes === frame.routes)
                    capture(id, current)
                    return current
                }
            }
            Thread.sleep(100)
        }
        EventQueue.invokeAndWait { dump("$id-timeout") }
        error("Actual original page/frame timed out: $id; latest=${latest()?.key}")
    }

    fun awaitFreshAgreement(): DesktopOriginalRootValidationTap.Frame {
        requireAcknowledged(false)
        val frame = await("000-agreement", { it.key == BiliPaiNavKey.Onboarding },
            listOf("使用须知", userAgreementIntroText(), "官方渠道") + UserAgreementClause.entries.map { it.title })
        EventQueue.invokeAndWait {
            owned(frame)
            check(frame.routes.stack.toList() == listOf(BiliPaiNavKey.Onboarding)) { "Mandatory gate has an unexpected physical stack" }
            check(!action("我已知晓").accessibleStateSet.contains(AccessibleState.ENABLED))
        }
        return frame
    }

    fun awaitHome(after: Long = 0L, owner: DesktopReadyOriginalRootHandle? = null): DesktopOriginalRootValidationTap.Frame =
        await("100-home", { it.serial > after && it.key == BiliPaiNavKey.Home && (owner == null || it.handle === owner) }, listOf("推荐"))

    fun awaitVideo(after: Long, owner: DesktopReadyOriginalRootHandle, bvid: String): DesktopOriginalRootValidationTap.Frame =
        await("100-initial-video", {
            it.serial > after && it.handle === owner && when (val key = it.key) {
                is BiliPaiNavKey.VideoDetail -> key.bvid == bvid
                is BiliPaiNavKey.Story -> key.seedBvid == bvid
                else -> false
            }
        })

    fun awaitActualHealth(health: Path) {
        val root = local.resolve("BiliPai").resolve("updates")
        val marker = health.toAbsolutePath().normalize()
        require(marker.startsWith(root) && marker.fileName.toString() == "startup-health.txt")
        val relative = root.relativize(marker)
        require(relative.nameCount == 3 && relative.getName(0).toString().startsWith("staged-"))
        val launch = relative.getName(1).toString()
        require(launch.startsWith("launch-") && UUID.fromString(launch.removePrefix("launch-")).toString() == launch.removePrefix("launch-"))
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (!Files.isRegularFile(marker, NOFOLLOW_LINKS)) {
            check(System.nanoTime() < deadline) { "Actual Main startup health was not written" }
            Thread.sleep(100)
        }
        require(UpdateStorage.existingPathWithoutLinks(marker) == marker)
        check(Files.readString(marker) == token)
        check(Files.readString(marker.resolveSibling("startup-version.txt")).isNotBlank())
    }

    private fun enabledEventually(frame: DesktopOriginalRootValidationTap.Frame, expected: Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos()
        while (System.nanoTime() < deadline) {
            var enabled = false
            EventQueue.invokeAndWait {
                owned(frame)
                check(frame.routes.currentKey == BiliPaiNavKey.Onboarding)
                enabled = action("我已知晓").accessibleStateSet.contains(AccessibleState.ENABLED)
            }
            if (enabled == expected) return
            Thread.sleep(100)
        }
        error("Original ACK button enabled state did not become $expected")
    }

    fun clickClause(frame: DesktopOriginalRootValidationTap.Frame, clause: UserAgreementClause) {
        EventQueue.invokeAndWait {
            owned(frame)
            check(frame.routes.currentKey == BiliPaiNavKey.Onboarding)
            val actual = action(clause.title, checkbox = true)
            check(actual.accessibleStateSet.contains(AccessibleState.ENABLED))
            check(actual.accessibleAction.doAccessibleAction(0))
        }
        Thread.sleep(300)
    }

    fun acknowledgeThroughOriginalUi(frame: DesktopOriginalRootValidationTap.Frame, beforeSubmit: () -> Unit = {}) {
        for ((index, clause) in UserAgreementClause.entries.withIndex()) {
            clickClause(frame, clause)
            enabledEventually(frame, index == UserAgreementClause.entries.lastIndex)
            capture("01${index + 1}-clause", requireNotNull(latest()))
            requireAcknowledged(false)
        }
        // Revoke one check, prove disabled, then recheck before invoking the actual original button.
        clickClause(frame, UserAgreementClause.entries.last())
        enabledEventually(frame, false)
        capture("014-unchecked", requireNotNull(latest()))
        clickClause(frame, UserAgreementClause.entries.last())
        enabledEventually(frame, true)
        capture("015-all-checked", requireNotNull(latest()))
        requireAcknowledged(false)
        EventQueue.invokeAndWait {
            owned(frame)
            val actual = action("我已知晓")
            check(actual.accessibleStateSet.contains(AccessibleState.ENABLED))
            beforeSubmit()
            check(actual.accessibleAction.doAccessibleAction(0))
        }
    }

    /** Reusable in a fresh isolated OriginalStaticSettingsUiFixture, before its first Home await. */
    fun acceptFreshAgreementToHome(health: Path): DesktopOriginalRootValidationTap.Frame {
        val initial = awaitFreshAgreement()
        awaitActualHealth(health)
        acknowledgeThroughOriginalUi(initial)
        val home = awaitHome(initial.serial, initial.handle)
        requireAcknowledged(true)
        return home
    }

    fun disagree(frame: DesktopOriginalRootValidationTap.Frame, useActualWindowBack: Boolean,
        beforeExit: () -> Unit = {}) {
        clickClause(frame, UserAgreementClause.entries.first())
        enabledEventually(frame, false)
        capture("020-partial-reject", requireNotNull(latest()))
        requireAcknowledged(false)
        EventQueue.invokeAndWait {
            owned(frame)
            if (useActualWindowBack) {
                beforeExit()
                check(frame.handle.navigation.requestBack())
            }
            else {
                val actual = action("我不同意")
                check(actual.accessibleStateSet.contains(AccessibleState.ENABLED))
                beforeExit()
                check(actual.accessibleAction.doAccessibleAction(0))
            }
        }
    }

    fun closeOwnedWindow(frame: DesktopOriginalRootValidationTap.Frame) = EventQueue.invokeAndWait {
        owned(frame)
        val actual = window()
        actual.dispatchEvent(WindowEvent(actual, WindowEvent.WINDOW_CLOSING))
    }

    private fun layer(component: Component, type: Class<*>): Any? {
        if (type.isInstance(component)) return component
        if (component is Container) component.components.forEach { layer(it, type)?.let { found -> return found } }
        return null
    }

    private fun dump(id: String) = Files.writeString(report.resolve("$id-accessibility.tsv"), accessible().joinToString("\n") {
        "${it.accessibleName}\t${it.accessibleRole}\t${it.accessibleStateSet}\t${it.accessibleAction?.accessibleActionCount ?: 0}"
    }, CREATE_NEW, WRITE)

    fun capture(id: String, observation: DesktopOriginalRootValidationTap.Frame) = EventQueue.invokeAndWait {
        owned(observation)
        dump(id)
        val actualWindow = window()
        val type = Class.forName("org.jetbrains.skiko.SkiaLayer")
        val actualLayer = requireNotNull(layer(actualWindow, type))
        val renderer = type.getMethod("getRenderApi").invoke(actualLayer).toString()
        check(renderer == "DIRECT3D") { "Actual Main default renderer changed: $renderer" }
        actualWindow.javaClass.getMethod("renderImmediately").invoke(actualWindow)
        val bitmap = requireNotNull(type.getMethod("screenshot").invoke(actualLayer))
        try {
            val imageType = Class.forName("org.jetbrains.skia.Image")
            val companion = imageType.getField("Companion").get(null)
            val image = companion.javaClass.getMethod("makeFromBitmap", bitmap.javaClass).invoke(companion, bitmap)
            try {
                val formatType = Class.forName("org.jetbrains.skia.EncodedImageFormat")
                val png = formatType.getField("PNG").get(null)
                val data = requireNotNull(imageType.getMethod("encodeToData", formatType, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType).invoke(image, png, 100, 6))
                try { Files.write(report.resolve("$id.png"), data.javaClass.getMethod("getBytes").invoke(data) as ByteArray, CREATE_NEW, WRITE) }
                finally { data.javaClass.getMethod("close").invoke(data) }
            } finally { imageType.getMethod("close").invoke(image) }
        } finally { bitmap.javaClass.getMethod("close").invoke(bitmap) }
        Files.writeString(report.resolve("$id-frame.txt"), "${observation.key}\n${observation.serial}\n$renderer\n" +
            observation.routes.stack.joinToString("\n"), CREATE_NEW, WRITE)
    }

    companion object {
        private val ackKeys = listOf(USER_AGREEMENT_ACK_KEY, "first_launch_shown", RELEASE_DISCLAIMER_ACK_KEY)
    }
}
