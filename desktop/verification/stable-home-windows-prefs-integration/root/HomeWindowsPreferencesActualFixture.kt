package com.bilipai.desktop.ui

import com.bilipai.desktop.diagnostics.DesktopNativeDiagnosticShareAssetHash
import kotlinx.coroutines.CancellationException
import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JFrame

fun main(args: Array<String>) {
    val dll = Path.of(args[0])
    val expected = DesktopNativeDiagnosticShareAssetHash.sha256
    check(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dll))
        .joinToString("") { "%02x".format(it) } == expected)
    var assertions = 0
    fun verify(ok: Boolean) { assertions++; check(ok) }
    var frame: JFrame? = null
    var platform: DesktopHomeWindowsPreferencesPlatform? = null
    try {
        EventQueue.invokeAndWait {
            frame = JFrame().apply { setSize(480, 360); addNotify() }
            platform = DesktopHomeWindowsPreferencesPlatform(frame!!, { dll }, expected, { true })
        }
        val actual = platform!!
        val monitor = actual.deviceDefault
        verify(monitor.physicalWidthPixels > 0 && monitor.physicalHeightPixels > 0)
        verify(monitor.scaleX > 0 && monitor.scaleY > 0)
        verify(monitor.smallestConfigurationWidthDp > 0)
        verify(monitor.defaultTabletUseSidebar == (monitor.smallestConfigurationWidthDp >= 600))
        verify(monitor.hingeCapability == DesktopHomeHingeCapability.UNAVAILABLE_ON_THIS_WINDOWS_MAPPING)
        EventQueue.invokeAndWait { frame!!.setSize(1200, 720) }
        verify(actual.deviceDefault === monitor)
        var edtNetwork: DesktopHomeNetworkObservation? = null
        EventQueue.invokeAndWait { edtNetwork = actual.currentNetwork() }
        val network = actual.currentNetwork()
        for (observed in listOf(edtNetwork!!, network)) {
            verify(observed.connectivityLevel in 0..3)
            verify(observed.ianaInterfaceType in 0L..0xffffffffL)
            verify(!observed.profilePresent || observed.ianaInterfaceType > 0)
            verify(observed.isMobileNetwork == (observed.profilePresent &&
                (observed.isWwanProfile || observed.ianaInterfaceType == 243L || observed.ianaInterfaceType == 244L)))
        }
        // This one fixture thread only exercises caller-apartment lifetime. The product creates
        // no network actor, polling thread, cache or event subscription.
        var threadFailure: Throwable? = null
        val repeated = Thread {
            try { repeat(12) { actual.currentNetwork() } } catch (failure: Throwable) { threadFailure = failure }
        }.apply { name = "fixture-native-network-apartment"; start() }
        repeated.join(10000)
        verify(!repeated.isAlive && threadFailure == null)
        actual.close()
        verify(runCatching { actual.currentNetwork() }.exceptionOrNull() is CancellationException)
        val calls = AtomicInteger()
        var retirement: DesktopHomeWindowsPreferencesPlatform? = null
        EventQueue.invokeAndWait {
            retirement = DesktopHomeWindowsPreferencesPlatform(frame!!, { dll }, expected,
                { calls.incrementAndGet() <= 3 })
        }
        verify(runCatching { retirement!!.currentNetwork() }.exceptionOrNull() is CancellationException)
        retirement!!.close()
        val origins = listOf(DesktopHomeWindowsPreferencesPlatform::class.java,
            DesktopHomeDeviceDefaultObservation::class.java, DesktopHomeNetworkObservation::class.java,
            DesktopNativeDiagnosticShareAssetHash::class.java)
        verify(origins.all { it.protectionDomain.codeSource.location.toString().endsWith("main-kotlin.jar") })
        println("{\"passed\":true,\"assertions\":$assertions,\"actualProductClassOrigins\":${origins.size}," +
            "\"productionOverrides\":0,\"nativeDllSha256\":\"$expected\",\"preferredProfilePresent\":${network.profilePresent}," +
            "\"connectivityLevel\":${network.connectivityLevel},\"ianaInterfaceType\":${network.ianaInterfaceType}," +
            "\"isWwanProfile\":${network.isWwanProfile},\"isMobileNetwork\":${network.isMobileNetwork}," +
            "\"monitorPhysicalWidth\":${monitor.physicalWidthPixels},\"monitorPhysicalHeight\":${monitor.physicalHeightPixels}," +
            "\"scaleX\":${monitor.scaleX},\"scaleY\":${monitor.scaleY},\"smallestConfigurationWidthDp\":${monitor.smallestConfigurationWidthDp}," +
            "\"defaultTabletUseSidebar\":${monitor.defaultTabletUseSidebar},\"actualRootMounted\":false,\"actualAccountOrExeAccepted\":false}")
    } finally {
        platform?.close()
        EventQueue.invokeAndWait { frame?.dispose() }
    }
}
