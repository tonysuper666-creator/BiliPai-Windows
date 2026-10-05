package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.grpc.ProtoWire
import kotlinx.serialization.json.*
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Only a transport script for the existing private guest Main. No comment VM,
 * player, account, protocol implementation or application state is constructed
 * or modified. All bytes go through the installed original gRPC/REST parsers. */
internal class WindowsCommentSearchReplay : AutoCloseable {
    enum class Phase { HOLD_FOR_CANCEL, FAIL_READS, COMPLETE_TWO_PAGES }
    private val phase = AtomicReference(Phase.HOLD_FOR_CANCEL)
    private val alive = AtomicBoolean(true)
    private val firstTimeRequest = CountDownLatch(1)
    private val secondPageRequest = CountDownLatch(1)
    private val releaseSecondPage = CountDownLatch(1)
    private val cancelledCall = AtomicBoolean(false)
    private val reads = CopyOnWriteArrayList<JsonObject>()
    private val failedRestRead = AtomicBoolean(false)
    private val completedSecondPage = AtomicBoolean(false)
    private val detailRoot = AtomicReference<Long?>(null)

    companion object {
        const val AID = 170001L
        const val ROOT_A = 91001L
        const val ROOT_B = 91002L
        const val ROOT_C = 91003L
        const val CHILD_A = 91101L
        const val CHILD_B = 91102L
        const val QUERY = "搜索验收"
        const val ROOT_A_TEXT = "搜索验收 普通根"
        const val ROOT_B_TEXT = "搜索验收 UP根"
        const val ROOT_C_TEXT = "搜索验收 充电根"
        const val CHILD_A_TEXT = "搜索验收 子回复"
        const val CHILD_B_TEXT = "搜索验收 UP子"
        const val MAIN_RPC = "/bilibili.main.community.reply.v1.Reply/MainList"
        const val DETAIL_RPC = "/bilibili.main.community.reply.v1.Reply/DetailList"
        private const val NEXT = "owned-search-page-2"
        fun isReadRpc(path: String): Boolean = path == MAIN_RPC || path == DETAIL_RPC
    }

    fun firstSearchReadObserved(): Boolean = firstTimeRequest.count == 0L
    fun cancelledSearchCallObserved(): Boolean = cancelledCall.get()
    fun secondSearchPageObserved(): Boolean = secondPageRequest.count == 0L
    fun failNextSearch() {
        check(alive.get() && cancelledCall.get())
        phase.set(Phase.FAIL_READS)
    }
    fun completeNextSearch() {
        check(alive.get() && failedRestRead.get())
        phase.set(Phase.COMPLETE_TWO_PAGES)
    }
    fun releaseFinalPage() {
        check(alive.get() && secondSearchPageObserved())
        releaseSecondPage.countDown()
    }
    fun releaseOnFailure() { releaseSecondPage.countDown() }

    fun intercept(chain: Interceptor.Chain, stillOwned: () -> Boolean): Response? {
        val request = chain.request()
        val path = request.url.encodedPath
        val rpc = request.url.host == "app.bilibili.com" && isReadRpc(path)
        val rest = request.url.host == "api.bilibili.com" && path in setOf(
            "/x/v2/reply/wbi/main", "/x/v2/reply/main", "/x/v2/reply")
        if (!rpc && !rest) return null
        check(alive.get() && stillOwned()) { "Owned comment replay retired" }
        require(request.url.scheme == "https" && request.url.port == 443 && request.url.username.isEmpty() && request.url.password.isEmpty())
        require(request.header("authorization") == null) { "This fixture is guest-only" }
        if (rest) {
            require(request.method == "GET" && request.url.queryParameter("oid")?.toLongOrNull() == AID &&
                request.url.queryParameter("type") == "1")
            require(phase.get() == Phase.FAIL_READS) { "Unexpected REST fallback outside the explicit error stage" }
            failedRestRead.set(true)
            reads += buildJsonObject { put("transport", "REST"); put("path", path); put("method", "GET"); put("phase", phase.get().name) }
            return response(chain, """{"code":-500,"message":"Owned synthetic comment read failure"}""".toByteArray(), false)
        }
        require(request.method == "POST" && request.url.encodedQuery == null && request.url.encodedFragment == null)
        val buffer = Buffer()
        requireNotNull(request.body).writeTo(buffer)
        val fields = ProtoWire.parseFields(ProtoWire.unframe(buffer.readByteArray()))
        fun number(n: Int): Long = fields.single { it.number == n }.varint
        require(number(1) == AID && number(2) == 1L) { "Comment request does not belong to the actual replay video" }
        val mode = number(if (path == MAIN_RPC) 9 else 7).toInt()
        require(mode in setOf(2, 3))
        val paging = fields.singleOrNull { it.number == if (path == MAIN_RPC) 10 else 8 }
        val offset = paging?.let { ProtoWire.parseFields(it.bytes).singleOrNull { it.number == 2 } }
            ?.let(ProtoWire::stringValue).orEmpty()
        val capturedPhase = phase.get()
        reads += buildJsonObject { put("transport", "gRPC"); put("path", path); put("method", "POST")
            put("mode", mode); put("nextOffset", offset); put("phase", capturedPhase.name); put("oid", AID) }
        if (path == DETAIL_RPC) {
            require(number(3) == ROOT_A && number(4) == 0L && offset.isEmpty())
            detailRoot.set(ROOT_A)
            return response(chain, detailResponse(), true)
        }
        if (mode == 3) {
            // The main list is a separate, completed HOT read. Search's optional
            // TIME request cannot alter or block its state or paging cursor.
            require(offset.isEmpty())
            return response(chain, mainResponse(listOf(rootA()), end = true, next = ""), true)
        }
        firstTimeRequest.countDown()
        when (capturedPhase) {
            Phase.HOLD_FOR_CANCEL -> {
                require(offset.isEmpty())
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                while (alive.get() && stillOwned() && !chain.call().isCanceled() && System.nanoTime() < deadline)
                    Thread.sleep(20)
                if (chain.call().isCanceled()) cancelledCall.set(true)
                throw IOException(if (cancelledCall.get()) "Actual optional search call cancelled" else "Owned held search deadline/owner retired")
            }
            Phase.FAIL_READS -> return response(chain, ByteArray(0), true, grpcStatus = "14")
            Phase.COMPLETE_TWO_PAGES -> {
                if (offset.isEmpty()) return response(chain, mainResponse(listOf(rootA(), rootB()), false, NEXT), true)
                require(offset == NEXT)
                secondPageRequest.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                while (releaseSecondPage.count != 0L && alive.get() && stillOwned() && !chain.call().isCanceled() &&
                    System.nanoTime() < deadline) releaseSecondPage.await(20, TimeUnit.MILLISECONDS)
                check(releaseSecondPage.count == 0L && alive.get() && stillOwned() && !chain.call().isCanceled()) {
                    "Owned second search page was not released by the visible progress assertion"
                }
                completedSecondPage.set(true)
                return response(chain, mainResponse(listOf(rootC()), true, ""), true)
            }
        }
    }

    private fun response(chain: Interceptor.Chain, payload: ByteArray, grpc: Boolean, grpcStatus: String = "0"): Response {
        val contentType = if (grpc) "application/grpc" else "application/json"
        val bytes = if (grpc) ProtoWire.frame(payload, gzipMinLength = Int.MAX_VALUE) else payload
        return Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Owned comment transport replay")
            .apply { if (grpc) header("grpc-status", grpcStatus) }
            .body(bytes.toResponseBody(contentType.toMediaType())).build()
    }
    private fun cursor(end: Boolean) = ProtoWire.message(ProtoWire.int32(1, if (end) 0 else 1), ProtoWire.bool(4, end))
    private fun subject(count: Int) = ProtoWire.message(ProtoWire.int64(1, 1L), ProtoWire.bool(11, true), ProtoWire.int32(16, count))
    private fun mainResponse(roots: List<ByteArray>, end: Boolean, next: String): ByteArray = ProtoWire.message(
        ProtoWire.bytes(1, cursor(end)), ProtoWire.bytes(3, subject(3)),
        ProtoWire.bytes(20, ProtoWire.message(ProtoWire.string(1, next))),
        *roots.map { ProtoWire.bytes(2, it) }.toTypedArray())
    private fun detailResponse() = ProtoWire.message(ProtoWire.bytes(1, cursor(true)),
        ProtoWire.bytes(2, subject(1)), ProtoWire.bytes(3, rootA()))
    private fun rootA() = reply(ROOT_A, 2L, ROOT_A_TEXT, 10, 1_700_000_100L,
        children = listOf(reply(CHILD_A, 3L, CHILD_A_TEXT, 90, 1_700_000_400L, ROOT_A)))
    private fun rootB() = reply(ROOT_B, 1L, ROOT_B_TEXT, 70, 1_700_000_500L,
        children = listOf(reply(CHILD_B, 1L, CHILD_B_TEXT, 60, 1_700_000_300L, ROOT_B)))
    private fun rootC() = reply(ROOT_C, 4L, ROOT_C_TEXT, 100, 1_700_000_200L, charged = true)
    private fun reply(id: Long, mid: Long, text: String, likes: Int, time: Long, root: Long = 0L,
        charged: Boolean = false, children: List<ByteArray> = emptyList()): ByteArray = ProtoWire.message(
        ProtoWire.int64(2, id), ProtoWire.int64(3, AID), ProtoWire.int32(4, 1), ProtoWire.int64(5, mid),
        ProtoWire.int64(6, root), ProtoWire.int64(7, root), ProtoWire.int32(9, likes), ProtoWire.int64(10, time),
        ProtoWire.int32(11, children.size), ProtoWire.bytes(12, ProtoWire.string(1, text)),
        ProtoWire.bytes(13, ProtoWire.message(ProtoWire.int64(1, mid), ProtoWire.string(2, "验收作者$mid"))),
        ProtoWire.bytes(14, ProtoWire.message(ProtoWire.string(11, "IP属地：本地验收"),
            ProtoWire.string(31, if (charged) "充电专属" else ""))),
        *children.map { ProtoWire.bytes(1, it) }.toTypedArray())

    fun receipt(): JsonObject {
        check(cancelledCall.get() && failedRestRead.get() && completedSecondPage.get() && detailRoot.get() == ROOT_A)
        check(reads.all { it["method"]?.jsonPrimitive?.content == "GET" || isReadRpc(requireNotNull(it["path"]?.jsonPrimitive?.content)) })
        return buildJsonObject {
            put("syntheticGuestTransportOnly", true); put("actualOptionalCallCancellationObserved", cancelledCall.get())
            put("grpcAndRestErrorStageObserved", failedRestRead.get()); put("originalTwoPageLoadCompleted", completedSecondPage.get())
            put("chargedControlProtobufField", 31); put("subReplyOriginalRootRequested", requireNotNull(detailRoot.get()))
            put("accountMutationSubmitted", false); put("newCommentVmCreated", false); put("originalCommentVmStateWritten", false)
            put("readRequests", JsonArray(reads.toList()))
        }
    }
    override fun close() { alive.set(false); releaseSecondPage.countDown() }
}
