package com.bilipai.desktop.votefixture

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.SimpleApiResponse
import com.bilipai.desktop.data.*
import java.lang.reflect.Proxy
import java.nio.file.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Actual Operations grade member, actual SessionStore epoch/csrf/mutation guard.
 * Fake API is injected into that member; visitor readiness is test-only warmed.
 * Session is persistent=false and all credentials are fake. No transport runs. */
fun ownedGradeMain(args:Array<String>): Unit = runBlocking {
    val root = Files.createTempDirectory("bilipai-grade-epoch-task-")
    val sessions = DesktopSessionStore(root.resolve("fake-session.json"),persistent=false)
    fun install(suffix:String) = sessions.saveAccount(mapOf("SESSDATA" to "not-real-$suffix","bili_jct" to "fixture-csrf-$suffix"),
        AccountSummary(501L,"fixture",""))
    install("one")
    val repository = DesktopRepository(sessions)
    fun warm() {
        DesktopRepository::class.java.getDeclaredField("visitorInitialized").apply { isAccessible=true }.setBoolean(repository,true)
        DesktopRepository::class.java.getDeclaredField("visitorGeneration").apply { isAccessible=true }.setLong(repository,sessions.generation)
    }
    warm()
    var calls=0
    var fields:List<Any?> = emptyList()
    val pending = AtomicReference<Continuation<SimpleApiResponse>?>(null)
    var hold = false
    var entered = CompletableDeferred<Unit>()
    val api = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader,arrayOf(BilibiliApi::class.java)) { _, method, params ->
        check(method.name=="gradeDanmaku") { "Unexpected API ${method.name}" }
        calls++; fields=params!!.dropLast(1)
        if (hold) {
            @Suppress("UNCHECKED_CAST")
            pending.set(params.last() as Continuation<SimpleApiResponse>); entered.complete(Unit)
            COROUTINE_SUSPENDED
        } else SimpleApiResponse()
    } as BilibiliApi
    var alive = true
    fun operations():DesktopDynamicCardOperations {
        val value = DesktopDynamicCardOperations(repository,stillOwned={alive})
        DesktopDynamicCardOperations::class.java.getDeclaredField("api").apply { isAccessible=true }.set(value,api)
        return value
    }
    val checks=mutableListOf<String>()
    fun gate(name:String,value:Boolean) { check(value) { name }; checks+=name }
    val first = operations()
    gate("new grade member uses the actual same SessionStore CSRF and original fields",
        first.submitGradeDanmaku(201,301,4500,"991",8).isSuccess && calls==1 &&
            fields==listOf(201L,301L,4500L,991L,8,"fixture-csrf-one"))
    alive=false
    var sourceCancelled=false
    try { first.submitGradeDanmaku(201,301,4500,"991",8) }
    catch (expected:CancellationException) { sourceCancelled=true }
    gate("retired native/page owner rejects before API invocation",sourceCancelled && calls==1)
    alive=true; hold=true
    val inflight=async { first.submitGradeDanmaku(201,301,4500,"991",10) }
    withTimeout(4000) { entered.await() }
    val previousEpoch=repository.sessionEpoch
    install("two"); warm()
    gate("same MID credentials actually advance epoch",repository.account.value?.mid==501L && repository.sessionEpoch>previousEpoch)
    pending.getAndSet(null)!!.resume(SimpleApiResponse())
    var epochCancelled=false
    try { inflight.await() } catch (expected:CancellationException) { epochCancelled=true }
    gate("late successful grade response is rejected by actual retired epoch guard",epochCancelled)
    hold=false
    val second=operations()
    gate("replacement epoch can use its new CSRF through same repository without reviving old owner",
        second.submitGradeDanmaku(201,301,4500,"991",4).isSuccess && fields.last()=="fixture-csrf-two" && !first.isOwned() && second.isOwned())
    val json=buildJsonObject {
        put("passed",true);put("preparedCandidateOnly",true);put("MainAcceptance",false)
        put("HTTP",false);put("persistentStore",false);put("fakeCredentials",true)
        put("actualOperationsAndSessionEpoch",true);put("visitorReadinessTestOnly",true)
        put("checks",JsonArray(checks.map(::JsonPrimitive)))
    }
    Files.writeString(Path.of(args[0]),json.toString()); println(json)
}

object OwnedGradeLauncher { @JvmStatic fun main(args:Array<String>) = ownedGradeMain(args) }
