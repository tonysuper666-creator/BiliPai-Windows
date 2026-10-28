package com.bilipai.desktop.integration

import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files
import java.net.Socket
import java.net.InetAddress
import java.io.DataOutputStream

class DesktopSingleInstanceTest {
    @Test fun secondLaunchActivatesExistingOwnerAndShutdownReleasesLock() {
        val directory = Files.createTempDirectory("instance-test")
        try {
            val owner = requireNotNull(DesktopSingleInstance.acquire(directory))
            owner.use {
                assertNull(DesktopSingleInstance.acquire(directory, "downloads"))
                assertEquals("downloads", owner.pendingRoute())
                owner.consumed("old-route")
                assertEquals("downloads", owner.pendingRoute())
                owner.consumed("downloads")
                assertNull(owner.pendingRoute())
            }
            requireNotNull(DesktopSingleInstance.acquire(directory)).close()
        } finally { directory.toFile().deleteRecursively() }
    }
    @Test fun invalidLoopbackTokenCannotRouteOrDisplaceOwner() {
        val directory = Files.createTempDirectory("instance-auth")
        try {
            requireNotNull(DesktopSingleInstance.acquire(directory)).use { owner ->
                val port = Files.readAllLines(directory.resolve("activation.endpoint"))[0].toInt()
                Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                    DataOutputStream(socket.getOutputStream()).apply { writeUTF("wrong"); writeUTF("downloads"); flush() }
                    socket.soTimeout = 2_000
                    assertEquals(-1, socket.getInputStream().read())
                }
                assertNull(owner.pendingRoute())
                assertNull(DesktopSingleInstance.acquire(directory, "message"))
                assertEquals("message", owner.pendingRoute())
            }
        } finally { directory.toFile().deleteRecursively() }
    }
}
