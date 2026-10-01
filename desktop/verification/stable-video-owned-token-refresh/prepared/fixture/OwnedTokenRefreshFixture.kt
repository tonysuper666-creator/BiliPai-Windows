package com.bilipai.desktop.ui

import com.bilipai.desktop.data.*
import com.android.purebilibili.core.network.AppSignUtils
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

object OwnedTokenRefreshFixture {
    private var assertions = 0
    private val checks = mutableListOf<String>()
    private fun verify(value: Boolean, label: String) { check(value) { label }; assertions++; checks += label }
    private class Rig(platform: String) {
        val store = DesktopSessionStore.temporary().also { it.saveAccount(mapOf("SESSDATA" to "private-fake-session", "bili_jct" to "private-fake-csrf"),
            AccountSummary(801, "Memory fixture", "", true), imported = true,
            credentials = DesktopAppCredentials("private-fake-old-token", "private-fake-refresh", platform, 0)) }
        val repository = DesktopRepository(store)
        val login = DesktopLoginRepository(repository)
        val entryJob = Job()
        var owned = true
        var refreshCalls = 0
        var navCalls = 0
        var onNav: () -> Unit = {}
        var commitCalls = 0
        var onCommit: () -> Unit = {}
        val tokenBinding = DesktopOriginalVideoTokenRefreshBinding(login, entryJob, { owned }, { block ->
            if (!owned) false else { commitCalls++; onCommit(); block(); true }
        })
        val receipt = repository.capturePlaybackAuthorization(store.generation) { true }.receipt
        init {
            val memory = Interceptor { chain ->
                val request = chain.request()
                val body = when (request.url.encodedPath) {
                    "/x/passport-tv-login/h5/refresh" -> {
                        refreshCalls++
                        val form = request.body as FormBody
                        val values = (0 until form.size).associate { form.name(it) to form.value(it) }
                        verify(values["appkey"] == AppSignUtils.TV_APP_KEY && values["access_key"] == "private-fake-old-token" &&
                            values["refresh_token"] == "private-fake-refresh" && !values["sign"].isNullOrBlank(), "Actual TV refresh request retains original fields and signing")
                        verify(request.tag(Class.forName("com.bilipai.desktop.data.DesktopSessionEpoch")) != null,
                            "Actual refresh Call is tagged before same Repository transport")
                        """{"code":0,"data":{"mid":801,"access_token":"private-fake-new-token","refresh_token":"private-fake-new-refresh","expires_in":3600}}"""
                    }
                    "/x/web-interface/nav" -> {
                        navCalls++
                        verify(request.tag(Class.forName("com.bilipai.desktop.data.DesktopSessionEpoch")) != null,
                            "Actual no-cookie validation Call is tagged before existing validation transport")
                        onNav()
                        """{"code":0,"data":{"isLogin":true,"mid":801,"uname":"Memory fixture","vip":{"status":1}}}"""
                    }
                    else -> error("Unexpected network path; memory transport must close all requests")
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Memory only")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }
            // Explicit fixture-only no-socket interceptors on the two EXISTING transports.
            // Root production code/client/Store authority are not replaced by a new schema.
            val field = DesktopRepository::class.java.getDeclaredField("client").also { it.isAccessible = true }
            val old = field.get(repository) as OkHttpClient
            field.set(repository, old.newBuilder().addInterceptor(memory).build())
            val validation = DesktopRepository::class.java.getDeclaredField("validationPassportRetrofit").also { it.isAccessible = true }
            val oldValidation = validation.get(repository) as Retrofit
            val oldValidationClient = oldValidation.callFactory() as OkHttpClient
            validation.set(repository, oldValidation.newBuilder().callFactory(oldValidationClient.newBuilder().addInterceptor(memory).build()).build())
        }
        fun close() { entryJob.cancel(); repository.httpClient.dispatcher.executorService.shutdownNow(); repository.httpClient.connectionPool.evictAll() }
    }
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val android = Rig("android")
        verify(!android.tokenBinding.available(), "Actual non-TV credentials report original refresh unavailability")
        verify(!android.tokenBinding.refresh(android.receipt) { true } && android.refreshCalls == 0 && android.navCalls == 0,
            "Original non-TV returns false without any refresh or validation request")
        android.close()

        val success = Rig("tv")
        verify(success.tokenBinding.available(), "Actual primary TV credentials enable the required refresh actor")
        verify(success.tokenBinding.refresh(success.receipt) { true }, "Owned existing login actor refresh succeeds through memory Retrofit")
        verify(success.refreshCalls == 1 && success.navCalls == 1, "Exactly one original refresh plus one existing nav validation")
        verify(success.repository.accessTokenCredentials().first == "private-fake-new-token", "Actual same Store receives replacement credentials")
        verify(!success.repository.isPlaybackReceiptCurrent(success.receipt), "Credential replacement retires old raw-load receipt")
        verify(success.entryJob.isActive && success.owned, "Credential replacement does not close page entry or native owner")
        verify(success.commitCalls == 1, "Credential replacement uses exactly one supplied entry commit under Store admission")
        success.close()

        val stale = Rig("tv")
        stale.onNav = { stale.store.setPlaybackAccountMid(null, stale.store.generation) { true } }
        try { stale.tokenBinding.refresh(stale.receipt) { true }; error("Expected stale receipt cancellation") }
        catch (_: CancellationException) { assertions++; checks += "Same-epoch authorization revision change during validation cancels install" }
        verify(stale.repository.accessTokenCredentials().first == "private-fake-old-token" && stale.commitCalls == 0,
            "Stale response cannot install credentials or enter final entry commit")
        verify(stale.refreshCalls == 1 && stale.navCalls == 1, "Cancellation does not retry refresh or fall back to another account")
        stale.close()

        val cancel = Rig("tv")
        val callerReady = CompletableDeferred<Job>()
        cancel.onCommit = { callerReady.getCompleted().cancel() }
        val worker = async {
            callerReady.complete(currentCoroutineContext().job)
            cancel.tokenBinding.refresh(cancel.receipt) { true }
        }
        try { worker.await(); error("Expected cancelled final commit") }
        catch (_: CancellationException) { assertions++; checks += "Only captured caller Job cancellation at final Store-entry gate rejects install" }
        verify(cancel.repository.accessTokenCredentials().first == "private-fake-old-token", "Cancelled final gate cannot replace credentials")
        verify(cancel.entryJob.isActive && cancel.owned && cancel.repository.isPlaybackReceiptCurrent(cancel.receipt),
            "Only caller cancellation preserves current page, primary Store and authorization")
        verify(cancel.refreshCalls == 1 && cancel.navCalls == 1, "Cancelled final gate performs no retry or fallback")
        cancel.close()

        val origins = listOf(DesktopRepository::class.java, DesktopLoginRepository::class.java, DesktopSessionStore::class.java,
            DesktopPlaybackAuthorizationReceipt::class.java, DesktopOriginalVideoTokenRefreshBinding::class.java).map { cls ->
            val bytes = requireNotNull(cls.getResourceAsStream("/" + cls.name.replace('.', '/') + ".class")).use { it.readBytes() }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            "{\"class\":\"${cls.name}\",\"codeSource\":\"${cls.protectionDomain.codeSource.location.toURI()}\",\"sha256ClassBytes\":\"$hash\"}"
        }
        Files.writeString(Path.of(args.single()), "{\"passed\":true,\"assertions\":$assertions,\"checks\":[${checks.joinToString(",") { "\"$it\"" }}],\"origins\":[${origins.joinToString(",")}],\"scope\":\"prepared Repo/Login override, actual Store/receipt, explicit memory-only existing transports; no sockets/accounts/native\"}\n")
        println("PASS $assertions original TV/owned transport/terminal cancellation assertions; no sockets/native")
    }
}
