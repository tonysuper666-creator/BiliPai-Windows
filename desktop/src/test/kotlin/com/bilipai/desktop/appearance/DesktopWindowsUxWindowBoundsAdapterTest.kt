package com.bilipai.desktop.appearance

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The thin part of the window bounds restore: the store over a real DesktopPluginStore in a temporary folder, the
 * reading of a WindowState value and the monitor probe. A WindowState is a plain object here, no window is created.
 * No Windows, no GPU, no network, no real window.
 */
class DesktopWindowsUxWindowBoundsAdapterTest {
    private val bounds = DesktopWindowsUxWindowBounds(-1920, 25, 1360, 900, true)

    @Test fun aFreshStoreHasNoBoundsAndSavingWritesTheWindowKeysOnly() {
        val root = Files.createTempDirectory("bilipai-windows-ux-bounds-fresh-")
        val store = DesktopPluginStore(root)
        val boundsStore = DesktopWindowsUxWindowBoundsStore(store)
        assertNull(boundsStore.load())
        assertFalse(Files.exists(root.resolve("plugin-settings.json")))
        assertTrue(boundsStore.save(bounds))
        val stored = store.preferences(WINDOWS_UX_NAMESPACE)
        assertEquals(UX_WINDOW_KEYS.toSet(), stored.keys)
        assertEquals(-1920, stored.getValue(UX_KEY_WINDOW_X).jsonPrimitive.intOrNull)
        assertEquals(true, stored.getValue(UX_KEY_WINDOW_MAXIMIZED).jsonPrimitive.booleanOrNull)
        assertEquals(bounds, boundsStore.load())
        assertEquals(bounds, DesktopWindowsUxWindowBoundsStore(DesktopPluginStore(root)).load())
    }

    @Test fun savingTheSameBoundsAgainNeedsNoWriteAndAChangedOneIsRefusedOnceTheStoreIsFrozen() {
        val root = Files.createTempDirectory("bilipai-windows-ux-bounds-frozen-")
        val store = DesktopPluginStore(root)
        val boundsStore = DesktopWindowsUxWindowBoundsStore(store)
        assertTrue(boundsStore.save(bounds))
        val before = Files.readAllBytes(root.resolve("plugin-settings.json"))
        store.freezeWrites()
        assertTrue(boundsStore.save(bounds))
        assertFalse(boundsStore.save(bounds.copy(x = 10)))
        assertTrue(before.contentEquals(Files.readAllBytes(root.resolve("plugin-settings.json"))))
        assertEquals(bounds, boundsStore.load())
    }

    @Test fun otherFlagsAndOtherNamespacesSurviveASave() {
        val root = Files.createTempDirectory("bilipai-windows-ux-bounds-neighbours-")
        val store = DesktopPluginStore(root)
        store.update(WINDOWS_UX_NAMESPACE, mapOf(UX_KEY_RAIL_MODE to JsonPrimitive("COLLAPSED")))
        store.update(WINDOWS_DISPLAY_NAMESPACE, mapOf("scale_percent" to JsonPrimitive(150)))
        assertTrue(DesktopWindowsUxWindowBoundsStore(store).save(bounds))
        assertEquals("COLLAPSED", store.preferences(WINDOWS_UX_NAMESPACE).getValue(UX_KEY_RAIL_MODE).jsonPrimitive.content)
        assertEquals(150, store.preferences(WINDOWS_DISPLAY_NAMESPACE).getValue("scale_percent").jsonPrimitive.intOrNull)
        assertEquals(setOf("scale_percent"), store.preferences(WINDOWS_DISPLAY_NAMESPACE).keys)
    }

    @Test fun boundsThatAreNotSaneAreNeverWritten() {
        val root = Files.createTempDirectory("bilipai-windows-ux-bounds-insane-")
        val store = DesktopPluginStore(root)
        assertFalse(DesktopWindowsUxWindowBoundsStore(store).save(DesktopWindowsUxWindowBounds(0, 0, 10, 10)))
        assertFalse(Files.exists(root.resolve("plugin-settings.json")))
    }

    @Test fun aNamespaceThatIsNotAnObjectIsNeverOverwrittenAndReadsAsNoBounds() {
        val root = Files.createTempDirectory("bilipai-windows-ux-bounds-corrupt-")
        val file = root.resolve("plugin-settings.json")
        Files.writeString(file, "{\"windows_ux\": 5}")
        val before = Files.readAllBytes(file)
        val boundsStore = DesktopWindowsUxWindowBoundsStore(DesktopPluginStore(root))
        assertNull(boundsStore.load())
        assertFalse(boundsStore.save(bounds))
        assertTrue(before.contentEquals(Files.readAllBytes(file)))
    }

    @Test fun aPartialSavedGroupReadsAsNoBounds() {
        val root = Files.createTempDirectory("bilipai-windows-ux-bounds-partial-")
        val store = DesktopPluginStore(root)
        store.update(WINDOWS_UX_NAMESPACE, mapOf(UX_KEY_WINDOW_X to JsonPrimitive(10), UX_KEY_WINDOW_Y to JsonPrimitive(20)))
        assertNull(DesktopWindowsUxWindowBoundsStore(store).load())
    }

    @Test fun theMonitorProbeNeverThrowsAndMarksAtMostOnePrimary() {
        val screens = desktopWindowsUxProbeScreens()
        assertTrue(screens.count { it.primary } <= 1)
    }

    @Test fun aWindowStateIsReadAsASampleInAwtUnits() {
        val floating = WindowState(position = WindowPosition(10.dp, 20.dp), size = DpSize(1360.dp, 900.dp))
        assertEquals(
            DesktopWindowsUxWindowSample(DesktopWindowsUxPlacement.FLOATING, false, 10, 20, 1360, 900),
            desktopWindowsUxSampleOf(floating))
        val maximized = WindowState(
            placement = WindowPlacement.Maximized, isMinimized = true, position = WindowPosition(-8.dp, -8.dp), size = DpSize(3429.dp, 1400.dp))
        assertEquals(
            DesktopWindowsUxWindowSample(DesktopWindowsUxPlacement.MAXIMIZED, true, -8, -8, 3429, 1400),
            desktopWindowsUxSampleOf(maximized))
        val fullscreen = WindowState(placement = WindowPlacement.Fullscreen, position = WindowPosition(0.dp, 0.dp), size = DpSize(3413.dp, 1440.dp))
        assertEquals(DesktopWindowsUxPlacement.FULLSCREEN, desktopWindowsUxSampleOf(fullscreen)?.placement)
    }

    @Test fun aWindowStateWithoutAPositionOrASizeIsNoSampleYet() {
        assertNull(desktopWindowsUxSampleOf(WindowState(size = DpSize(1360.dp, 900.dp))))
        assertNull(desktopWindowsUxSampleOf(WindowState(position = WindowPosition(Alignment.Center), size = DpSize(1360.dp, 900.dp))))
        assertNull(desktopWindowsUxSampleOf(WindowState(position = WindowPosition(10.dp, 20.dp), size = DpSize.Unspecified)))
    }
}
