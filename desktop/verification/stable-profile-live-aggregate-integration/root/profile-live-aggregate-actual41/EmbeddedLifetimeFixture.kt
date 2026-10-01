package com.bilipai.desktop.ui

import com.bilipai.desktop.data.*
import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.feature.partition.PartitionFeedViewModel
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The real actual39 SessionStore/gate/Partition VM; terminal video API is deliberately synthetic.
 * No Runtime/JS worker/client is constructed, nor any Compose/HTTP/file-save/UI service invoked. */
fun main() = runBlocking {
    val productJar=java.nio.file.Path.of(System.getProperty("actualProductJar")).toUri().toURL()
    for(type in listOf(DesktopHomeEmbeddedLifetime::class.java, DesktopHomeRetainedGate::class.java,
        DesktopSessionStore::class.java, PartitionFeedViewModel::class.java)) {
        check(type.protectionDomain.codeSource.location==productJar)
        println("ORIGIN ${type.name} ${type.protectionDomain.codeSource.location}")
    }

    val temporary = Files.createTempDirectory("four-home-pages-proof-")
    val sessions = DesktopSessionStore(temporary.resolve("session.json"), persistent = false)
    val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    fun gate(): DesktopHomeRetainedGate = DesktopHomeRetainedGate(sessions,
        requireNotNull(sessions.dynamicCacheOwner()), { sessions.generation }, { sessions.account.value?.mid }, { true }, parent)
    val gate = gate()
    val life = DesktopHomeEmbeddedLifetime(gate.scope, gate::owns, gate::commit)
    val started = CompletableDeferred<Unit>()
    val answer = CompletableDeferred<List<VideoItem>>()
    var calls = 0
    val requests = object : DesktopHomeVideoRequests {
        override suspend fun getPopularVideos(page: Int): Result<List<VideoItem>> {
            check(page == 1); calls++;started.complete(Unit)
            return Result.success(answer.await())
        }
        override suspend fun getRegionVideos(tid: Int, page: Int): Result<List<VideoItem>> = error("unexpected region")
        override suspend fun getHomeVideos(idx: Int): Result<List<VideoItem>> = error("unexpected home")
        override suspend fun getRankingVideos(rid: Int, type: String): Result<List<VideoItem>> = error("unexpected rank")
        override suspend fun getWeeklyMustWatchVideos(): Result<List<VideoItem>> = error("unexpected weekly")
        override suspend fun getPreciousVideos(): Result<List<VideoItem>> = error("unexpected precious")
        override suspend fun getNavInfo(): Result<com.android.purebilibili.data.model.response.NavData> = error("unexpected nav")
        override suspend fun getPreviewVideoUrl(bvid: String, cid: Long): String? = error("unexpected preview")
    }
    val partition = PartitionFeedViewModel(DesktopPartitionEnvironment(requests, life.scope, life::owns, life::commit))
    started.await()
    val samePartition = partition
    // The UI's covering section does not appear in either gate or lifetime admission.
    var renderedHome = true
    renderedHome = false
    check(!renderedHome && life.owns() && partition === samePartition)
    answer.complete(listOf(VideoItem(bvid = "BV-FIXTURE", cid = 501, title = "fixture")))
    withTimeout(3_000) { while (partition.uiState.value.videos.isEmpty()) yield() }
    check(calls == 1 && partition.uiState.value.videos.single().cid == 501L)
    renderedHome = true
    check(renderedHome && partition === samePartition && partition.uiState.value.videos.single().bvid == "BV-FIXTURE")
    println("PASS actual original partition instance and admitted request survive UI coverage")

    val waiting = CompletableDeferred<Unit>()
    val completion = life.scope.launch { waiting.complete(Unit); awaitCancellation() }
    waiting.await()
    life.closeAndJoin()
    check(completion.isCompleted && !life.owns())
    check(!life.commit { error("closed local commit") })
    check(gate.owns() && parent.isActive)
    println("PASS closing aggregate drains only descendant tasks, leaves shared Home/parent active")

    sessions.saveAccount(mapOf("SESSDATA" to "fixture-one", "bili_jct" to "fixture"), AccountSummary(71, "fixture", ""))
    val old = gate()
    val previous = DesktopHomeEmbeddedLifetime(old.scope, old::owns, old::commit)
    sessions.saveAccount(mapOf("SESSDATA" to "fixture-two", "bili_jct" to "fixture"), AccountSummary(71, "fixture", ""))
    check(!previous.owns() && !previous.commit { error("foreign same-MID publication") })
    previous.closeAndJoin();old.closeAndJoin()
    println("PASS same-MID credential epoch change retires old embedded page admission")

    val raceGate = gate()
    val racing = DesktopHomeEmbeddedLifetime(raceGate.scope, raceGate::owns, raceGate::commit)
    val entered=CountDownLatch(1);val release=CountDownLatch(1);val closing=CountDownLatch(1);val closed=CountDownLatch(1)
    var committed = 0
    val mutating = thread { check(racing.commit { entered.countDown();check(release.await(3,TimeUnit.SECONDS));committed++ }) }
    check(entered.await(3,TimeUnit.SECONDS))
    val retiring = thread { closing.countDown();racing.close();closed.countDown() }
    check(closing.await(3,TimeUnit.SECONDS) && !closed.await(80,TimeUnit.MILLISECONDS))
    release.countDown();mutating.join(3_000);retiring.join(3_000)
    check(committed==1 && !mutating.isAlive && !retiring.isAlive && !racing.commit { committed++ })
    racing.closeAndJoin();raceGate.closeAndJoin()
    println("PASS Store-to-page commit completes before local close; future publication rejected")
    gate.closeAndJoin();parent.cancel()
    println("RESULT 4 lifecycle groups; actual39 original VM/gate/temp store; aggregate UI/Runtime/gallery/network=false")
}
