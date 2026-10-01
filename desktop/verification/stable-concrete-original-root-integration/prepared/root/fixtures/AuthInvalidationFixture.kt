package com.bilipai.desktop.data

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real in-memory product SessionStore and prepared same Repository, synthetic terminal
 * application interceptor. No socket, disk account, real credentials or actual Root UI. */
fun main(): Unit = runBlocking {
    val store = DesktopSessionStore(Path.of("unused-synthetic-session.json"), persistent = false)
    store.saveAccount(mapOf("SESSDATA" to "fixture-only", "bili_jct" to "fixture-only"), AccountSummary(501, "Fixture", ""))
    val repository = DesktopRepository(store)
    val lock = store.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(store)
    val client = repository.javaClass.getDeclaredField("client").apply { isAccessible = true }.get(repository) as OkHttpClient
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val completed = CountDownLatch(1)
    val cancelled = AtomicInteger()
    val cancelledOutsideStore = AtomicBoolean(false)
    val terminal = client.newBuilder().eventListener(object : EventListener() {
        override fun canceled(call: Call) {
            cancelled.incrementAndGet()
            cancelledOutsideStore.set(!Thread.holdsLock(lock))
        }
    }).addInterceptor { chain ->
        started.countDown()
        check(release.await(10, TimeUnit.SECONDS))
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
            .body("{}".toResponseBody()).build()
    }.build()
    check(terminal.dispatcher === client.dispatcher)
    val call = terminal.newCall(Request.Builder().url("https://api.bilibili.com/x/fixture-only").build())
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { completed.countDown() }
        override fun onResponse(call: Call, response: Response) { response.close(); completed.countDown() }
    })
    try {
        check(started.await(10, TimeUnit.SECONDS))
        val epoch = repository.sessionEpoch
        check(!repository.logoutHomeAuthenticationInvalidated(epoch - 1L, 501L) { true })
        check(!repository.logoutHomeAuthenticationInvalidated(epoch, 502L) { true })
        check(!repository.logoutHomeAuthenticationInvalidated(epoch, 501L) { false })
        check(repository.account.value?.mid == 501L && cancelled.get() == 0 && !call.isCanceled())
        println("PASS retired epoch, foreign primary MID and retired caller preserve same account and dispatcher")
        val events = Channel<Pair<Long, Long>>(Channel.UNLIMITED)
        check(repository.updateHomeNavIdentity(epoch, 501L, null, false) { capturedEpoch, capturedMid ->
            check(Thread.holdsLock(lock))
            check(events.trySend(capturedEpoch to capturedMid).isSuccess)
            check(cancelled.get() == 0)
        })
        check(repository.account.value?.mid == 501L && !Thread.holdsLock(lock))
        val (expectedEpoch, expectedMid) = events.receive()
        check(repository.logoutHomeAuthenticationInvalidated(expectedEpoch, expectedMid) { true })
        check(repository.account.value == null && repository.sessionEpoch == epoch + 1L)
        check(call.isCanceled() && cancelled.get() == 1 && cancelledOutsideStore.get())
        check(!repository.logoutHomeAuthenticationInvalidated(expectedEpoch, expectedMid) { true })
        println("PASS real original Store invalidation enqueues under lock; accepted logout cancels existing dispatcher only after unlock")
        println("ORIGIN repository=" + repository.javaClass.protectionDomain.codeSource.location)
        println("ORIGIN sameStore=" + store.javaClass.protectionDomain.codeSource.location)
        println("RESULT prepared2 groups; socket=false realRootUI=false diskAccount=false")
        events.close()
        Unit
    } finally {
        release.countDown()
        check(completed.await(10, TimeUnit.SECONDS))
        terminal.connectionPool.evictAll()
        terminal.dispatcher.executorService.shutdown()
    }
}
