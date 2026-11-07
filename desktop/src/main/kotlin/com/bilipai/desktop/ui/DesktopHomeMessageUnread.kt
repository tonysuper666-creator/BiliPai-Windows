package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.grpc.ProtoWire
import kotlinx.coroutines.CancellationException

internal data class DesktopHomeGatewayUnread(val total: Int, val likes: Int)

/** Additional requests on the retained Home binding; every invocation borrows its real receipt. */
internal interface DesktopHomeMessageUnreadRequests : DesktopHomeMessageRequests {
    suspend fun getGatewayUnread(): Result<DesktopHomeGatewayUnread>
    suspend fun getImUnread(): Result<Int>
}

/** The upstream GetTotalUnread wire protocols, over Root's existing owned transport. */
internal class DesktopHomeMessageUnreadProtocol(private val request: suspend (String, ByteArray) -> ByteArray) {
    private suspend fun <T> result(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (failure: Exception) { Result.failure(failure) }

    suspend fun gateway(): Result<DesktopHomeGatewayUnread> {
        val gateway = result {
            parseGateway(request("/bilibili.im.gateway.interface.v1.ImGatewayApi/GetTotalUnread",
                ProtoWire.message(ProtoWire.int32(1, 0), ProtoWire.int32(2, 1), ProtoWire.int32(3, 0))))
        }
        if (gateway.isSuccess) return gateway
        return result {
            parseGateway(request("/bilibili.im.interface.v1.ImInterface/GetTotalUnread",
                ProtoWire.message(ProtoWire.int32(1, 0), ProtoWire.int32(2, 1))))
        }
    }

    suspend fun im(): Result<Int> = result {
        bounded(UnreadFields(UnreadFields(request("/bilibili.app.im.v1.im/GetTotalUnread", byteArrayOf())).bytes(1)).number(2))
    }

    private fun parseGateway(bytes: ByteArray): DesktopHomeGatewayUnread {
        val root = UnreadFields(bytes)
        val feed = UnreadFields(root.bytes(2)).all(1).associate { entry ->
            val item = UnreadFields(entry.bytes)
            item.text(1) to bounded(item.number(2))
        }
        val single = UnreadFields(root.bytes(1))
        val explicit = bounded(UnreadFields(root.bytes(6)).number(1))
        val total = if (explicit > 0) explicit else bounded(
            bounded(single.number(1)).toLong() + bounded(single.number(2)) +
                listOf("reply", "at", "like", "sys_msg").sumOf { (feed[it] ?: 0).toLong() })
        return DesktopHomeGatewayUnread(total, feed["like"] ?: 0)
    }
}

private fun bounded(value: Long): Int = value.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

/** Owned by ONE generated VM/epoch. Access only in its existing publication gate.
 * A clear invalidates in-flight polls; their late responses cannot establish the clean baseline. */
internal class DesktopHomeMessageUnread {
    var revision: Long = 0L
        private set
    private var cleaned = false
    private var lastTotal: Int? = null

    fun clean() { revision++; cleaned = true; lastTotal = null }
    fun resolve(requestRevision: Long, total: Int): Int? {
        if (requestRevision != revision) return null
        val count = total.coerceAtLeast(0)
        val previous = lastTotal
        lastTotal = count
        if (!cleaned) return count
        if (previous != null && count > previous) { cleaned = false; return count }
        return 0
    }

    suspend fun poll(requests: DesktopHomeMessageUnreadRequests, disableLikes: Boolean): Result<Int> {
        fun <T> Result<T>.uncancelled(): Result<T> = also {
            (exceptionOrNull() as? CancellationException)?.let { throw it }
        }
        val gateway = requests.getGatewayUnread().uncancelled()
        gateway.getOrNull()?.let {
            return Result.success(bounded(it.total.toLong() - if (disableLikes) it.likes.coerceAtLeast(0) else 0))
        }
        val im = requests.getImUnread().uncancelled()
        if (im.isFailure) return Result.failure(requireNotNull(im.exceptionOrNull()))
        val feed = requests.getFeedUnread().uncancelled().getOrNull()
        return Result.success(bounded(im.getOrThrow().coerceAtLeast(0).toLong() + (feed?.let {
            it.reply.coerceAtLeast(0).toLong() + it.at.coerceAtLeast(0) +
                (if (disableLikes) 0 else it.like.coerceAtLeast(0)) + it.sysMsg.coerceAtLeast(0)
        } ?: 0L)))
    }
}

/** Keep decoding local to Home: the upstream repository helper belongs to another module. */
private class UnreadFields(bytes: ByteArray) {
    private val fields = ProtoWire.parseFields(bytes)
    fun all(number: Int) = fields.filter { it.number == number }
    fun bytes(number: Int) = fields.lastOrNull { it.number == number && it.wireType == ProtoWire.WIRE_LENGTH_DELIMITED }?.bytes ?: byteArrayOf()
    fun text(number: Int) = fields.lastOrNull { it.number == number && it.wireType == ProtoWire.WIRE_LENGTH_DELIMITED }?.let(ProtoWire::stringValue).orEmpty()
    fun number(number: Int) = fields.lastOrNull { it.number == number && it.wireType == ProtoWire.WIRE_VARINT }?.varint ?: 0L
}
