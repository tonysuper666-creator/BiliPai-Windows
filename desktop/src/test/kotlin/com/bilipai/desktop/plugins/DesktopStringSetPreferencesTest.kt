package com.bilipai.desktop.plugins

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class DesktopStringSetPreferencesTest {
    @TempDir lateinit var root: Path

    private fun preferences(store: DesktopPluginStore = DesktopPluginStore(root)) =
        DesktopPluginContext(store).getSharedPreferences("network_proxy_prefs", DesktopPluginContext.MODE_PRIVATE)

    @Test fun `editor captures the input set before later caller mutations`() {
        val prefs = preferences()
        val input = linkedSetOf("aicu.cc", "example.com")
        val editor = prefs.edit().putStringSet("domains", input)
        input.clear()
        editor.apply()
        assertEquals(setOf("aicu.cc", "example.com"), prefs.getStringSet("domains", null))
        val exposed = prefs.all.getValue("domains") as Set<*>
        @Suppress("UNCHECKED_CAST")
        (exposed as MutableSet<String>).clear()
        assertEquals(setOf("aicu.cc", "example.com"), prefs.getStringSet("domains", null))
    }

    @Test fun `empty sets persist and null removes only the selected key`() {
        val prefs = preferences()
        prefs.edit().putString("host", "127.0.0.1").putStringSet("domains", emptySet()).apply()
        assertEquals(emptySet(), prefs.getStringSet("domains", setOf("fallback")))
        prefs.edit().putStringSet("domains", null).apply()
        assertNull(prefs.getStringSet("domains", null))
        assertFalse(prefs.all.containsKey("domains"))
        assertEquals("127.0.0.1", prefs.getString("host", null))
    }

    @Test fun `malformed arrays use the fallback without coercing nonstring values`() {
        Files.writeString(root.resolve("plugin-settings.json"),
            """{"network_proxy_prefs":{"domains":["aicu.cc",42],"primitive":"aicu.cc"}}""")
        val prefs = preferences()
        assertEquals(setOf("fallback"), prefs.getStringSet("domains", setOf("fallback")))
        assertNull(prefs.getStringSet("primitive", null))
        assertFalse(prefs.all.containsKey("domains"))
        assertEquals("aicu.cc", prefs.all["primitive"])
    }

    @Test fun `frozen writers cannot publish a replacement and a new generation reloads the persisted set`() {
        val store = DesktopPluginStore(root)
        val prefs = preferences(store)
        prefs.edit().putStringSet("domains", setOf("aicu.cc")).apply()
        val before = Files.readAllBytes(root.resolve("plugin-settings.json"))
        store.freezeWrites()
        assertFailsWith<IllegalStateException> {
            prefs.edit().putStringSet("domains", setOf("replacement.example")).apply()
        }
        assertContentEquals(before, Files.readAllBytes(root.resolve("plugin-settings.json")))
        assertEquals(setOf("aicu.cc"), prefs.getStringSet("domains", null))
        assertEquals(setOf("aicu.cc"), preferences(DesktopPluginStore(root)).getStringSet("domains", null))
    }

    @Test fun `legacy primitive values coexist with sets and fallback values are copied`() {
        val prefs = preferences()
        prefs.edit().putBoolean("enabled", true).putInt("port", 7890).putString("host", "localhost")
            .putStringSet("domains", setOf("aicu.cc", "example.com")).apply()
        assertTrue(prefs.getBoolean("enabled", false))
        assertEquals(7890, prefs.getInt("port", 0))
        assertEquals("localhost", prefs.getString("host", null))
        assertEquals(setOf("aicu.cc", "example.com"), prefs.all["domains"])
        val fallback = linkedSetOf("fallback")
        val received = prefs.getStringSet("missing", fallback)
        fallback.clear()
        assertEquals(setOf("fallback"), received)
    }
}
