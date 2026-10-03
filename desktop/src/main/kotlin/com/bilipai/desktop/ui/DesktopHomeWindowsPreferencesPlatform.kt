package com.bilipai.desktop.ui

import com.android.purebilibili.core.util.resolveLargeScreenOrFoldableConfiguration
import com.bilipai.desktop.update.UpdateStorage
import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.CancellationException
import java.awt.EventQueue
import java.awt.Insets
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor
import kotlin.math.min

/** Capability failures are not a successful observation of a non-cellular network. */
internal class DesktopHomePreferenceCapabilityUnavailable(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal enum class DesktopHomeHingeCapability { UNAVAILABLE_ON_THIS_WINDOWS_MAPPING }

/** Actual Root monitor configuration, never the current resizable Window's width/height. */
internal data class DesktopHomeDeviceDefaultObservation(
    val deviceId: String,
    val monitorBounds: Rectangle,
    val physicalWidthPixels: Int,
    val physicalHeightPixels: Int,
    val scaleX: Double,
    val scaleY: Double,
    val reservedScreenInsets: Insets,
    val rootWindowInsets: Insets,
    val usableMonitorWidthDp: Double,
    val usableMonitorHeightDp: Double,
    val smallestConfigurationWidthDp: Int,
    val hingeCapability: DesktopHomeHingeCapability,
    val defaultTabletUseSidebar: Boolean,
)

internal fun desktopHomeActualDeviceDefault(window: Window): DesktopHomeDeviceDefaultObservation {
    check(EventQueue.isDispatchThread()) { "Read the actual Root graphics configuration on its EDT" }
    check(window.isDisplayable) { "The actual Root window must have its own display peer" }
    val configuration = window.graphicsConfiguration
        ?: throw DesktopHomePreferenceCapabilityUnavailable("Root显示器配置暂不可用")
    val transform = configuration.defaultTransform
    val scaleX = transform.scaleX
    val scaleY = transform.scaleY
    check(transform.shearX == 0.0 && transform.shearY == 0.0 && scaleX.isFinite() && scaleY.isFinite() && scaleX > 0 && scaleY > 0)
    val mode = configuration.device.displayMode
    check(mode.width > 0 && mode.height > 0)
    val reserved = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
    val width = mode.width.toDouble() / scaleX - reserved.left - reserved.right
    val height = mode.height.toDouble() / scaleY - reserved.top - reserved.bottom
    check(width.isFinite() && height.isFinite() && width > 0 && height > 0)
    val smallest = floor(min(width, height)).toInt()
    // Windows exposes no Android FEATURE_SENSOR_HINGE_ANGLE through this mapping.
    // The explicit unavailable capability contributes no hinge term; no sensor result is fabricated.
    val originalDefault = resolveLargeScreenOrFoldableConfiguration(smallest, hasHingeAngleSensor = false)
    return DesktopHomeDeviceDefaultObservation(configuration.device.iDstring, Rectangle(configuration.bounds),
        mode.width, mode.height, scaleX, scaleY,
        Insets(reserved.top, reserved.left, reserved.bottom, reserved.right),
        window.insets.let { Insets(it.top, it.left, it.bottom, it.right) },
        width, height, smallest, DesktopHomeHingeCapability.UNAVAILABLE_ON_THIS_WINDOWS_MAPPING, originalDefault)
}

/** Connectivity and cellular transport are separate, as in the original Android policy. */
internal data class DesktopHomeNetworkObservation(
    val profilePresent: Boolean,
    val connectivityLevel: Int,
    val ianaInterfaceType: Long,
    val isWwanProfile: Boolean,
    val identityHash: String,
) {
    val hasInternetAccess: Boolean get() = profilePresent && connectivityLevel == 3
    val isMobileNetwork: Boolean get() = profilePresent &&
        (isWwanProfile || ianaInterfaceType == 243L || ianaInterfaceType == 244L)
}

/** ONE app/Root lifetime. Every decision re-queries the preferred connection in the same native DLL.
 * No account binding, cached ConnectionProfile, network client, polling actor or event subscription.
 */
internal class DesktopHomeWindowsPreferencesPlatform(
    window: Window,
    private val nativeDll: () -> Path,
    private val expectedSha256: String,
    private val stillOwned: () -> Boolean,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    val deviceDefault = run {
        if (!stillOwned()) throw CancellationException("Root preference platform retired")
        val observed = desktopHomeActualDeviceDefault(window)
        if (!stillOwned()) throw CancellationException("Root preference platform retired")
        observed
    }
    val defaultTabletUseSidebar: Boolean get() = deviceDefault.defaultTabletUseSidebar
    private interface Api : StdCallLibrary {
        fun BilipaiHomeNetworkSnapshot(profile: IntByReference, connectivity: IntByReference,
            interfaceType: IntByReference, wwan: IntByReference): Int
        fun BilipaiHomeNetworkIdentitySnapshot(profile: IntByReference, connectivity: IntByReference,
            interfaceType: IntByReference, wwan: IntByReference, identity: ByteArray,
            capacity: Int, written: IntByReference): Int
    }
    private val api: Api by lazy {
        try {
            check(System.getProperty("os.name").startsWith("Windows") && Native.POINTER_SIZE == 8)
            require(expectedSha256.matches(Regex("[a-f0-9]{64}")))
            val path = UpdateStorage.existingPathWithoutLinks(nativeDll().toAbsolutePath())
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) in 1..16L * 1024 * 1024)
            val actual = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
                .joinToString("") { "%02x".format(it) }
            check(actual == expectedSha256) { "原生网络状态资源校验失败" }
            Native.load(path.toString(), Api::class.java)
        } catch (failure: Exception) {
            throw DesktopHomePreferenceCapabilityUnavailable("Windows网络状态能力暂不可用", failure)
        } catch (failure: LinkageError) {
            throw DesktopHomePreferenceCapabilityUnavailable("Windows网络状态能力暂不可用", failure)
        }
    }
    private fun assertOwned() {
        if (closed.get() || !stillOwned()) throw CancellationException("Root preference platform retired")
    }
    fun currentNetwork(): DesktopHomeNetworkObservation {
        assertOwned()
        try {
            val profile = IntByReference(-1); val level = IntByReference(-1)
            val kind = IntByReference(-1); val wwan = IntByReference(-1)
            // The private bounded bytes contain actual profile/adapter/connected SSID.
            // Hash before returning, wipe in every path; no raw identity enters UI/receipts.
            val identity = ByteArray(4096); val written = IntByReference(-1)
            val identityHash = try {
                val hr = api.BilipaiHomeNetworkIdentitySnapshot(profile, level, kind, wwan,
                    identity, identity.size, written)
                assertOwned()
                if (hr < 0) throw DesktopHomePreferenceCapabilityUnavailable("Windows网络状态读取失败 (HRESULT 0x${hr.toUInt().toString(16)})")
                check(written.value in 1..identity.size)
                MessageDigest.getInstance("SHA-256").apply { update(identity, 0, written.value) }
                    .digest().joinToString("") { "%02x".format(it) }
            } finally { identity.fill(0) }
            assertOwned()
            check(profile.value in 0..1 && level.value in 0..3 && wwan.value in 0..1)
            if (profile.value == 0) check(level.value == 0 && kind.value == 0 && wwan.value == 0)
            return DesktopHomeNetworkObservation(profile.value != 0, level.value, kind.value.toUInt().toLong(), wwan.value != 0, identityHash)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: DesktopHomePreferenceCapabilityUnavailable) { throw failure }
          catch (failure: Exception) { throw DesktopHomePreferenceCapabilityUnavailable("Windows网络状态能力暂不可用", failure) }
          catch (failure: LinkageError) { throw DesktopHomePreferenceCapabilityUnavailable("Windows网络状态能力暂不可用", failure) }
    }
    fun isMobileNetwork(): Boolean = currentNetwork().isMobileNetwork
    override fun close() { closed.set(true) }
}
