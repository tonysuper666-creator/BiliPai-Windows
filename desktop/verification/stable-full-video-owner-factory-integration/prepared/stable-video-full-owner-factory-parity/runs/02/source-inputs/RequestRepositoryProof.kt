package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.VideoNoteSavePayload
import com.android.purebilibili.feature.video.playback.dash.AdaptiveDashPlaybackSource
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

// Synthetic terminal services only. Real SessionStore, request Binding, Invocation,
// original protocols and global Assets are executed; no socket/native/UI/account IO.
private var checks = 0
private fun expect(value: Boolean, label: String) { checks++; check(value) { label } }
private fun replaceField(owner: Any, name: String, value: Any) {
    owner.javaClass.getDeclaredField(name).also { it.isAccessible = true }.set(owner, value)
}
private class Terminal {
    var reply: (String, List<Any?>, Continuation<Any?>) -> Any? = { name, _, _ -> error("Unexpected API $name") }
    var assertCurrent: () -> Unit = { error("Terminal was not bound") }
    @Suppress("UNCHECKED_CAST")
    val api = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader, arrayOf(BilibiliApi::class.java)) { proxy, method, args ->
        when (method.name) {
            "toString" -> "task-only terminal"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            else -> {
                assertCurrent()
                val fields = args!!.toList()
                reply(method.name, fields.dropLast(1), fields.last() as Continuation<Any?>)
            }
        }
    } as BilibiliApi
}
private val unusedMedia = object : DesktopOriginalVideoMediaPort {
    override fun prepareLegacyDash(v: String, a: String?, k: Map<String, String>): PlaybackSource = error("No media in terminal proof")
    override fun prepareAdaptiveDash(s: AdaptiveDashPlaybackSource, k: Map<String, String>): PlaybackSource? = error("No media in terminal proof")
    override fun prepareProgressive(url: String): PlaybackSource = error("No media in terminal proof")
    override fun accept(source: PlaybackSource) = error("No native acceptance in terminal proof")
}
private class Fixture : AutoCloseable {
    val directory = Files.createTempDirectory("bilipai-request-proof-")
    val sessions = DesktopSessionStore(directory.resolve("memory-session.json"), persistent = false)
    val repository = DesktopRepository(sessions)
    val job = SupervisorJob()
    val scope = CoroutineScope(job + Dispatchers.Default)
    val assets = DesktopSubtitleAssets(OkHttpClient.Builder().addInterceptor { error("Default subtitle client must not run") }.build())
    val privacy = DesktopSearchPreferences(directory.resolve("global-privacy"))
    val terminal = Terminal()
    val bound = AtomicReference<DesktopOriginalVideoRepositoryBinding>()
    val vipEvent = AtomicReference<DesktopPlaybackAuthorizationReceipt?>()
    var fakeSubtitleCalls: Call.Factory? = null
    init { sessions.saveAccount(mapOf("SESSDATA" to "task-owned-session", "bili_jct" to "task-owned-csrf", "DedeUserID" to "701"), AccountSummary(701, "synthetic", "", false)) }
    val ports = DesktopOriginalVideoPlaybackInvocationPorts(scope, { job.isActive }, capture = {
        val binding = DesktopOriginalVideoRepositoryBinding.capture(repository, repository.sessionEpoch, job,
            { job.isActive }, { block -> if (job.isActive) { block(); true } else false }, PlayerPreferences(),
            null, emptySet(), true, { false }, { false }, { false }, { false }, { _, _ -> error("No token refresh") })
        bound.set(binding)
        terminal.assertCurrent = binding::assertCurrent
        replaceField(binding, "capturedPrimaryApi", terminal.api)
        replaceField(binding.environment, "api", terminal.api)
        replaceField(binding.environment, "playbackApi", terminal.api)
        fakeSubtitleCalls?.let { replaceField(binding, "metadataPlaybackCalls", it) }
        val repo = createDesktopOriginalVideoOwnerRequestRepository(repository, binding, assets, privacy,
            { _, _ -> false }, { old -> vipEvent.set(old) })
        DesktopOriginalVideoPlaybackInvocation(repo, unusedMedia, binding::assertCurrent)
    }, status = object : DesktopOriginalVideoPlaybackStatus {
        override fun isPlaybackLoggedIn() = true
        override fun isPlaybackVip() = sessions.account.value?.isVip == true
        override fun isUsingDedicatedPlaybackAccount() = false
        override fun isAppApiCoolingDown() = false
    }, acceptedMedia = { error("No accepted source in terminal proof") })
    override fun close() { ports.close(); job.cancel(); assets.close() }
}
private suspend fun fieldsAndLifetime() {
    Fixture().use { f ->
        val admission = AtomicReference<(() -> Unit) -> Boolean>()
        val notes = DesktopOriginalVideoOwnerNotesView(f.ports)
        f.terminal.reply = { name, args, _ -> when (name) {
            "saveVideoNote" -> {
                @Suppress("UNCHECKED_CAST") val fields = args[0] as Map<String, String>
                expect(fields["csrf"] == "task-owned-csrf" && fields["oid"] == "12" && fields["note_id"] == "0012", "same primary csrf/String note identity")
                VideoNoteSaveResponse(data = VideoNoteSaveData(9007199254740993))
            }
            "reportHeartbeat" -> {
                @Suppress("UNCHECKED_CAST") val fields = args[0] as Map<String, String>
                expect(fields["mid"] == "701" && fields["csrf"] == "task-owned-csrf" && fields["played_time"] == "7", "original heartbeat primary MID/csrf/seconds")
                BaseResponse()
            }
            else -> error(name)
        } }
        f.ports.launch {
            admission.set(f.ports.captureCurrentRequestAdmission())
            expect(admission.get().invoke { } , "active fixed request admits short mutation")
            withContext(Dispatchers.IO) {
                expect(notes.savePrivateNote(VideoNoteSavePayload(12, "0012", "title", "summary", "content", "[]", 7, false)).getOrThrow() == "9007199254740993", "Notes forwarding survives real dispatcher switch")
                val request = f.ports.requireRequestRepository() as DesktopOriginalVideoOwnerRequestRepository
                expect(request.reportPlayHeartbeat("BVfixture", 701, 7, 5, 9, 12), "captured original heartbeat succeeds")
            }
        }.join()
        var changed = false
        expect(!admission.get().invoke { changed = true } && !changed, "completed request cannot mutate via retained closure")
    }
}
private suspend fun lateEpoch() {
    Fixture().use { f ->
        val pending = CompletableDeferred<Continuation<Any?>>()
        val cancelled = AtomicBoolean(false)
        f.terminal.reply = { name, _, continuation -> check(name == "saveVideoNote"); pending.complete(continuation); COROUTINE_SUSPENDED }
        val job = f.ports.launch {
            try { DesktopOriginalVideoOwnerNotesView(f.ports).savePrivateNote(VideoNoteSavePayload(12, null, "t", "s", "c", "[]", 1, false)); error("Late epoch result escaped") }
            catch (_: CancellationException) { cancelled.set(true) }
        }
        val continuation = withTimeout(3000) { pending.await() }
        f.sessions.saveAccount(mapOf("SESSDATA" to "task-owned-new-session", "bili_jct" to "new-task-csrf", "DedeUserID" to "702"), AccountSummary(702, "synthetic-next", ""))
        continuation.resume(VideoNoteSaveResponse(data = VideoNoteSaveData(1)))
        withTimeout(3000) { job.join() }
        expect(cancelled.get(), "noncooperative late Notes response rejects actual changed epoch")
        expect(!f.bound.get().admitCurrentMutation { error("retired mutation ran") }, "same captured admission rejects new account")
    }
}
private suspend fun vipRevision() {
    Fixture().use { f ->
        val old = AtomicReference<DesktopPlaybackAuthorizationReceipt>()
        val rejected = AtomicBoolean(false)
        f.terminal.reply = { name, _, _ -> check(name == "getNavInfo"); NavResponse(data = NavData(isLogin = true, mid = 701, vip = VipInfo(status = 1))) }
        f.ports.launch {
            old.set(f.bound.get().receipt)
            try { (f.ports.requireRequestRepository() as DesktopOriginalVideoOwnerRequestRepository).refreshVipStatusForPreferredQualityIfNeeded(true, false, 120, false) }
            catch (_: CancellationException) { rejected.set(true) }
        }.join()
        expect(f.repository.account.value?.isVip == true, "original VIP write updates same actual Store")
        expect(!f.repository.isPlaybackReceiptCurrent(old.get()) && f.vipEvent.get() == old.get(), "receipt revision retirement publishes exact old receipt for fresh Root capture")
        expect(rejected.get(), "old VIP request is not silently retagged")
    }
}
private class SubtitleCall(private val req: Request, private val starts: AtomicInteger) : Call {
    private val cancelled = AtomicBoolean(false)
    override fun request() = req
    override fun enqueue(callback: Callback) {
        starts.incrementAndGet()
        callback.onResponse(this, Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
            .body("{\"body\":[{\"from\":0.5,\"to\":2.0,\"content\":\"captured subtitle\"}]}".toResponseBody()).build())
    }
    override fun execute(): Response = error("Only task-owned async transport is allowed")
    override fun cancel() { cancelled.set(true) }
    override fun isExecuted() = starts.get() > 0
    override fun isCanceled() = cancelled.get()
    override fun timeout() = Timeout()
    override fun clone(): Call = SubtitleCall(req, starts)
}
private suspend fun assetsReuse() {
    Fixture().use { f ->
        val starts = AtomicInteger()
        f.fakeSubtitleCalls = Call.Factory { request -> SubtitleCall(request, starts) }
        f.ports.launch {
            val repo = f.ports.requireRequestRepository() as DesktopOriginalVideoOwnerRequestRepository
            val url = "https://aisubtitle.hdslb.com/bfs/subtitle/task-owned-proof.json"
            val first = repo.getSubtitleCues(url, "BVfixture", 701).getOrThrow()
            val second = repo.getSubtitleCues(url, "BVfixture", 701).getOrThrow()
            expect(first == second && first.single().content == "captured subtitle", "actual global Assets document/cues reused")
            expect(starts.get() == 1, "owned Call.Factory supplied once, second import reuses global file")
        }.join()
    }
}
fun main() = runBlocking {
    fieldsAndLifetime(); lateEpoch(); vipRevision(); assetsReuse()
    println("REQUEST_COMPOSITION_PASS checks=$checks groups=4 HTTP=false native=false mounted=false")
    listOf(DesktopOriginalVideoOwnerRequestRepository::class.java, DesktopOriginalVideoRepositoryBinding::class.java,
        DesktopSubtitleAssets::class.java, DesktopOriginalVideoPlaybackInvocationPorts::class.java,
        com.android.purebilibili.data.repository.DesktopOriginalVideoNoteProtocol::class.java).forEach {
        println("ORIGIN ${it.name} ${it.protectionDomain.codeSource.location}")
    }
}
