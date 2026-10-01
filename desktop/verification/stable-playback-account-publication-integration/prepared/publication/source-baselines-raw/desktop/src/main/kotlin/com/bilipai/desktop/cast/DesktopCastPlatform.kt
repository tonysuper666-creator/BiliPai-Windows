package com.bilipai.desktop.cast

import com.android.purebilibili.feature.cast.SsdpDiscovery
import com.android.purebilibili.feature.cast.scoreLocalNetwork
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginLifecycle
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.*
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

object DesktopCastLog {
    fun d(tag: String, message: String) = Unit
    fun i(tag: String, message: String) = Unit
    fun w(tag: String, message: String) = Unit
    fun e(tag: String, message: String, error: Throwable) = System.err.println("DLNA $tag: ${error.javaClass.simpleName}")
}

object DesktopCastLifecycle { val isInBackground: Boolean get() = !DesktopPluginLifecycle.isAppVisible() }

inline fun <T> castCatching(block: () -> T): Result<T> = try { Result.success(block()) }
catch (cancelled: CancellationException) { throw cancelled }
catch (error: Throwable) { Result.failure(error) }

@OptIn(InternalCoroutinesApi::class)
internal suspend inline fun <T> withDesktopCastResponse(client: OkHttpClient, request: Request, block: (Response) -> T): T {
    val call = client.newCall(request)
    val cancellation = currentCoroutineContext().job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
        if (cause != null) call.cancel()
    }
    try {
        val response = awaitDesktopCastResponse(call)
        return response.use { block(it) }
    } finally { cancellation.dispose() }
}

internal suspend fun awaitDesktopCastResponse(call: Call): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) { if (continuation.isActive) continuation.resumeWith(Result.failure(error)) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, abandoned, _ -> abandoned.close() }
        }
    })
}

data class DesktopCastInterface(val name: String, val label: String, val address: Inet4Address, val networkInterface: NetworkInterface)
internal data class DesktopCastNetworkBinding(val address: Inet4Address, val networkInterface: NetworkInterface?,
    val discoveryTarget: InetSocketAddress? = null, val listenPort: Int? = null)

/** Real interface selection replaces Android Network.bindSocket and MulticastLock. */
object DesktopCastNetwork {
    private val lock = Any()
    private val selected = WeakHashMap<DesktopPluginContext, DesktopCastNetworkBinding>()
    private val targets = ConcurrentHashMap.newKeySet<String>()
    @Volatile internal var proxyPort: Int = 8901
    @Volatile internal var proxyBindHost: String? = null

    fun interfaces(): List<DesktopCastInterface> = Collections.list(NetworkInterface.getNetworkInterfaces()).flatMap { network ->
        if (!network.isUp || network.isLoopback || !network.supportsMulticast()) return@flatMap emptyList()
        Collections.list(network.inetAddresses).filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .map { address -> DesktopCastInterface(network.name, "${network.displayName} · ${address.hostAddress}", address, network) }
    }.sortedByDescending { entry ->
        val name = (entry.name + " " + entry.networkInterface.displayName).lowercase()
        scoreLocalNetwork(name.contains("wi-fi") || name.contains("wireless") || name.contains("wlan"), true,
            listOf("vpn", "tun", "tap", "virtual", "hyper-v").any(name::contains), !entry.networkInterface.isVirtual)
    }

    fun select(context: DesktopPluginContext, network: DesktopCastInterface) = synchronized(lock) {
        selected[context] = DesktopCastNetworkBinding(network.address, network.networkInterface)
    }
    internal fun binding(context: DesktopPluginContext): DesktopCastNetworkBinding = synchronized(lock) {
        selected[context] ?: interfaces().firstOrNull()?.let { DesktopCastNetworkBinding(it.address, it.networkInterface) }
            ?: throw IOException("没有可用于 DLNA 的本地 IPv4 网卡")
    }
    internal fun configureForFixture(context: DesktopPluginContext, target: InetSocketAddress, listenPort: Int = 0) = synchronized(lock) {
        require(target.address.isLoopbackAddress)
        selected[context] = DesktopCastNetworkBinding(InetAddress.getByName("127.0.0.1") as Inet4Address, null, target, listenPort)
        proxyPort = 0; proxyBindHost = "127.0.0.1"
    }
    internal fun clearFixture(context: DesktopPluginContext) = synchronized(lock) {
        selected.remove(context); proxyPort = 8901; proxyBindHost = null; clearProxyTargets()
    }
    fun proxyAddress(context: DesktopPluginContext): String = binding(context).address.hostAddress
    fun registerProxyTarget(target: String) {
        val url = target.toHttpUrlOrNull() ?: throw IllegalArgumentException("无效的投屏媒体地址")
        require(url.scheme == "http" || url.scheme == "https")
        targets += url.toString()
    }
    fun isRegisteredProxyTarget(target: String): Boolean = target in targets
    fun clearProxyTargets() { targets.clear(); DesktopCastProxySessions.clear() }
}

fun hasRawLocalNetworkAccess(context: DesktopPluginContext): Boolean = runCatching { DesktopCastNetwork.binding(context) }.isSuccess

object DesktopSsdpNetwork {
    @OptIn(InternalCoroutinesApi::class)
    suspend fun discover(context: DesktopPluginContext, timeoutMs: Int, multicastHost: String, multicastPort: Int,
        resendIntervalMs: Long, receiveSliceMs: Int, payloads: List<String>, parse: (String) -> SsdpDiscovery.SsdpDevice?): List<SsdpDiscovery.SsdpDevice> = withContext(Dispatchers.IO) {
        require(timeoutMs in 1..60_000)
        val binding = DesktopCastNetwork.binding(context)
        val destination = binding.discoveryTarget ?: InetSocketAddress(InetAddress.getByName(multicastHost), multicastPort)
        val group = InetSocketAddress(InetAddress.getByName(multicastHost), multicastPort)
        val found = linkedMapOf<String, SsdpDiscovery.SsdpDevice>()
        val socket = MulticastSocket(null)
        val closing = currentCoroutineContext().job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) socket.close() }
        var joined = false
        try {
            socket.reuseAddress = true; socket.broadcast = true; socket.timeToLive = 4
            socket.bind(InetSocketAddress(if (binding.discoveryTarget == null) "0.0.0.0" else binding.address.hostAddress, binding.listenPort ?: multicastPort))
            binding.networkInterface?.let { network ->
                socket.networkInterface = network
                if (binding.discoveryTarget == null) { joined = runCatching { socket.joinGroup(group, network); true }.getOrDefault(false) }
            }
            val start = System.nanoTime()
            var nextSendAt = 0L
            while ((System.nanoTime() - start) / 1_000_000 < timeoutMs) {
                currentCoroutineContext().ensureActive()
                val elapsed = (System.nanoTime() - start) / 1_000_000
                if (elapsed >= nextSendAt) {
                    payloads.forEach { payload -> val bytes = payload.toByteArray(Charsets.UTF_8); socket.send(DatagramPacket(bytes, bytes.size, destination)) }
                    nextSendAt = elapsed + resendIntervalMs
                }
                socket.soTimeout = (timeoutMs - elapsed).toInt().coerceAtLeast(1).coerceAtMost(receiveSliceMs)
                try {
                    val packet = DatagramPacket(ByteArray(4096), 4096); socket.receive(packet)
                    parse(String(packet.data, 0, packet.length, Charsets.UTF_8))?.let { device ->
                        val location = device.location.toHttpUrlOrNull()
                        if (location != null && location.username.isEmpty() && location.password.isEmpty()) {
                            val key = device.usn.ifBlank { device.location }
                            if (key.isNotBlank()) found.putIfAbsent(key, device)
                        }
                    }
                } catch (_: SocketTimeoutException) { }
            }
            found.values.toList()
        } catch (error: SocketException) { currentCoroutineContext().ensureActive(); throw error }
        finally {
            if (joined) runCatching { socket.leaveGroup(group, requireNotNull(binding.networkInterface)) }
            closing.dispose(); socket.close()
        }
    }
}
