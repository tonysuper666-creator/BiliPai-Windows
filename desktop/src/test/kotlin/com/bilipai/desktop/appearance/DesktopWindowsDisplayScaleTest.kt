package com.bilipai.desktop.appearance

import androidx.compose.ui.unit.Density
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.awt.Canvas
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class DesktopWindowsDisplayScaleTest {
    private fun context(store: DesktopPluginStore, alive: AtomicBoolean = AtomicBoolean(true)) =
        DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), alive::get,
            { action -> if (!alive.get()) false else { action(); true } })

    @Test fun originalWindowsDensityIsMultipliedOnceAndFontScaleRemainsIndependent() {
        for (system in listOf(1f, 1.25f, 1.5f, 2f, 3f)) {
            for (percent in 50..200 step 5) {
                val scaled = desktopWindowsScaledDensity(Density(system, 1.2f), percent)
                assertEquals(system * percent / 100f, scaled.density, 0.00001f)
                assertEquals(1.2f, scaled.fontScale)
                val minimum = desktopWindowsSafeMinimumSize(system, percent, Rectangle(0, 0, 3840, 2100), system.toDouble(), system.toDouble())
                assertTrue(minimum.width in 1..3840 && minimum.height in 1..2100)
            }
        }
        // 150% Windows DPI + 125% user zoom is 1200x850 AWT logical, not 1800x1275.
        val actual150 = desktopWindowsSafeMinimumSize(1.5f, 125, Rectangle(0, 0, 2560, 1400), 1.5, 1.5)
        assertEquals(1200, actual150.width); assertEquals(850, actual150.height)
        val axisRatio = desktopWindowsSafeMinimumSize(1.5f, 125, Rectangle(0, 0, 5000, 3000), 1.5, 2.0)
        assertEquals(1200, axisRatio.width); assertEquals(638, axisRatio.height)
        val tiny = desktopWindowsSafeMinimumSize(3f, 200, Rectangle(0, 0, 800, 600), 3.0, 3.0)
        assertEquals(800, tiny.width); assertEquals(600, tiny.height)
        assertFailsWith<IllegalArgumentException> { desktopWindowsSafeMinimumSize(1f, 125, Rectangle(0, 0, 1000, 800), 0.0, 1.0) }
        assertEquals(125, desktopWindowsScaleAfter(200, DesktopWindowsScaleCommand.Reset))
        assertEquals(200, desktopWindowsScaleAfter(200, DesktopWindowsScaleCommand.Adjust(Int.MAX_VALUE)))
        assertEquals(50, desktopWindowsScaleAfter(50, DesktopWindowsScaleCommand.Adjust(Int.MIN_VALUE)))
        assertFailsWith<IllegalArgumentException> { desktopWindowsScaledDensity(Density(1f), 0) }
    }

    @Test fun shortcutDecoderLeavesTextShortcutsAndPlainKeysUntouched() {
        val canvas = Canvas()
        fun key(code: Int, modifiers: Int) = KeyEvent(canvas, KeyEvent.KEY_PRESSED, 1, modifiers, code, KeyEvent.CHAR_UNDEFINED)
        val ctrl = InputEvent.CTRL_DOWN_MASK
        for (code in listOf(KeyEvent.VK_C, KeyEvent.VK_V, KeyEvent.VK_A, KeyEvent.VK_F, KeyEvent.VK_SPACE))
            assertNull(desktopWindowsScaleKey(key(code, ctrl)))
        for (code in listOf(KeyEvent.VK_EQUALS, KeyEvent.VK_MINUS, KeyEvent.VK_0)) {
            assertNull(desktopWindowsScaleKey(key(code, 0)))
            assertNull(desktopWindowsScaleKey(key(code, ctrl or InputEvent.ALT_DOWN_MASK)))
        }
        assertEquals(DesktopWindowsScaleCommand.Adjust(1), desktopWindowsScaleKey(key(KeyEvent.VK_EQUALS, ctrl or InputEvent.SHIFT_DOWN_MASK)))
        assertEquals(DesktopWindowsScaleCommand.Adjust(-1), desktopWindowsScaleKey(key(KeyEvent.VK_SUBTRACT, ctrl)))
        assertEquals(DesktopWindowsScaleCommand.Reset, desktopWindowsScaleKey(key(KeyEvent.VK_NUMPAD0, ctrl)))
        val wheel = DesktopWindowsScaleWheelAccumulator()
        assertEquals(0, wheel.steps(-0.25)); assertEquals(0, wheel.steps(-0.25))
        assertEquals(1, wheel.steps(-0.5)); assertEquals(-2, wheel.steps(2.0))
        assertEquals(0, wheel.steps(Double.NaN)); wheel.reset(); assertEquals(0, wheel.steps(-0.2))
    }

    @Test fun freshDefaultsDoNotWriteAndExplicitScaleSurvivesNewControllerWithoutChangingMobilePreferences(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-windows-display-test-")
        val store = DesktopPluginStore(root)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = DesktopWindowsDisplayScaleController(store, scope)
        try {
            assertEquals(125, controller.settings.value.percent)
            assertFalse(Files.exists(root.resolve("plugin-settings.json")))
            store.update("settings", mapOf("app_ui_scale" to JsonPrimitive(3), "app_dpi_override_percent" to JsonPrimitive(115)))
            val oldMobile = store.preferences("settings")
            controller.setPercent(context(store), 150) { true }
            withTimeout(5000) { controller.settings.first { it.percent == 150 } }
            assertEquals(oldMobile, store.preferences("settings"))
            val reopened = DesktopWindowsDisplayScaleController(DesktopPluginStore(root), scope)
            try { assertEquals(150, reopened.settings.value.percent) } finally { reopened.close() }
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun queuedWindowCommandsUseCurrentStoreAndAReplacementRegistrationRejectsItsPredecessor(): Unit = runBlocking {
        val store = DesktopPluginStore(Files.createTempDirectory("bilipai-windows-zoom-queue-test-"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = DesktopWindowsDisplayScaleController(store, scope)
        try {
            val old = controller.registerWindowContext(context(store))
            val current = controller.registerWindowContext(context(store))
            old.close() // Must not clear the actual replacement.
            repeat(20) { assertTrue(controller.submitWindow(DesktopWindowsScaleCommand.Adjust(1)) { true }) }
            withTimeout(5000) { controller.settings.first { it.percent == 200 } }
            assertTrue(controller.submitWindow(DesktopWindowsScaleCommand.Reset) { true })
            withTimeout(5000) { controller.settings.first { it.percent == 125 } }
            // The awaited settings request follows the actual window queue and proves its order.
            controller.setPercent(context(store), 130) { true }
            withTimeout(5000) { controller.settings.first { it.percent == 130 } }
            current.close()
            assertFalse(controller.submitWindow(DesktopWindowsScaleCommand.Adjust(1)) { true })
            controller.close()
            assertFalse(controller.submitWindow(DesktopWindowsScaleCommand.Adjust(1)) { true })
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun finalOriginalPermitRechecksRetirementAndInvalidSavedValueIsVisible(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-windows-display-retire-test-")
        val store = DesktopPluginStore(root)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = DesktopWindowsDisplayScaleController(store, scope)
        try {
            val alive = AtomicBoolean(true)
            val retiring = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), alive::get,
                { _ -> alive.set(false); false })
            assertFailsWith<CancellationException> { controller.setPercent(retiring, 175) { true } }
            assertFalse(Files.exists(root.resolve("plugin-settings.json")))
            store.update(WINDOWS_DISPLAY_NAMESPACE, mapOf("scale_percent" to JsonPrimitive("150")))
            val invalid = withTimeout(5000) { controller.settings.first { it.error != null } }
            assertEquals(125, invalid.percent)
            assertNotNull(invalid.error)
            controller.setPercent(context(store), 125) { true }
            withTimeout(5000) { controller.settings.first { it.error == null } }
            assertEquals(125, store.preferences(WINDOWS_DISPLAY_NAMESPACE)["scale_percent"]?.jsonPrimitive?.intOrNull)
        } finally { controller.close(); scope.cancel() }
    }
}
