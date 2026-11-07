package com.bilipai.desktop.plugins.js

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

/** Only the build's explicit non-Windows marker disables the JS lane; every other digest still fails closed. */
class DesktopJsWorkerAvailabilityTest {
    @TempDir lateinit var root: Path
    private val windows = "Windows 11"
    private val mac = "Mac OS X"

    @Test fun `this build's generated constant matches its host`() {
        if (System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) {
            assertTrue(DESKTOP_JS_WORKER_CATALOG_SHA256.matches(Regex("[0-9a-f]{64}")))
            assertFalse(desktopJsWorkerUnavailableOnThisHost())
        } else assertTrue(desktopJsWorkerUnavailableOnThisHost(), "the non-Windows placeholder must stay recognized")
    }

    @Test fun `the explicit marker is unavailable only off Windows`() {
        assertTrue(desktopJsWorkerUnavailableOnThisHost(DESKTOP_JS_WORKER_UNAVAILABLE_MARKER, mac))
        assertTrue(desktopJsWorkerUnavailableOnThisHost(DESKTOP_JS_WORKER_UNAVAILABLE_MARKER, "Linux"))
        assertFalse(desktopJsWorkerUnavailableOnThisHost(DESKTOP_JS_WORKER_UNAVAILABLE_MARKER, windows))
    }

    @Test fun `malformed digests are never treated as an unavailable host`() {
        val malformed = listOf("", " ", "unavailable", "unavailable:", DESKTOP_JS_WORKER_UNAVAILABLE_MARKER + " ",
            " " + DESKTOP_JS_WORKER_UNAVAILABLE_MARKER, DESKTOP_JS_WORKER_UNAVAILABLE_MARKER.uppercase(),
            "0".repeat(63), "0".repeat(65), "A".repeat(64), "g".repeat(64))
        for (digest in malformed) for (os in listOf(mac, windows)) {
            assertFalse(desktopJsWorkerUnavailableOnThisHost(digest, os), "'$digest' on $os")
            val failure = assertFailsWith<IllegalArgumentException> { DesktopJsWorkerResources(root, digest).createProcess(root) }
            assertEquals("JS worker catalog digest is invalid", failure.message)
        }
    }

    @Test fun `the marker still fails closed when resources are verified directly`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            DesktopJsWorkerResources(root, DESKTOP_JS_WORKER_UNAVAILABLE_MARKER).createProcess(root)
        }
        assertEquals("JS worker catalog digest is invalid", failure.message)
    }

    @Test fun `a well-formed digest that differs from the catalog is rejected`() {
        val catalog = root.resolve("classpath.json")
        Files.writeString(catalog, "{}")
        val actual = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(catalog)).joinToString("") { "%02x".format(it) }
        val other = (if (actual[0] == '0') "1" else "0") + actual.drop(1)
        val failure = assertFailsWith<IllegalArgumentException> { DesktopJsWorkerResources(root, other).createProcess(root) }
        assertEquals("JS worker resource catalog differs from this application", failure.message)
        Files.delete(catalog)
        assertFailsWith<IllegalArgumentException> { DesktopJsWorkerResources(root, actual).createProcess(root) }
    }

    @Test fun `a host without a worker refuses execution and still shuts down`(): Unit = runBlocking {
        val host = DesktopJsPluginHost(null, root)
        host.replaceAuthorizations(emptyList(), 0)
        val failure = assertFailsWith<IllegalStateException> { host.previewManifest("export default {}") }
        assertEquals("此电脑没有 JS 插件运行环境", failure.message)
        host.shutdownForRestore()
        assertFailsWith<IllegalStateException> { host.replaceAuthorizations(emptyList(), 1) }
    }
}
