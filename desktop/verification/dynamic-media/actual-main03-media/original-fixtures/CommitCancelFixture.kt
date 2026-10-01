package com.bilipai.desktop.ui.commitcancelproof

import com.bilipai.desktop.ui.*
import com.bilipai.desktop.data.*
import kotlinx.coroutines.*
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** One concern only: cancel the save Job while the unchanged actual Assets
 * commit is BLOCKED on the unchanged Main Store monitor. Downloaded fixture
 * files are already complete; no transport, picker, Window or account service
 * is invoked. The same existing Assets owner remains open throughout. */
fun main(args: Array<String>) = runBlocking {
    val root = Path.of(args[0]); Files.createDirectories(root)
    val image = Path.of(args[1]); val video = Path.of(args[2])
    val mainJar = Path.of(args[3]).toRealPath()
    val frozenAssetsJar = Path.of(args[4]).toRealPath()
    val filesJar = Path.of(args[5]).toRealPath()
    val mode = args[6]; check(mode == "candidate" || mode == "baseline")
    var assertions = 0
    fun prove(value: Boolean, description: String) { check(value) { description }; assertions++ }
    val loaded = mutableListOf<String>()
    fun checkSource(clazz: Class<*>, expected: Path) {
        val code = Path.of(clazz.protectionDomain.codeSource.location.toURI()).toRealPath()
        prove(code == expected, "actual class source for ${clazz.name}: $code")
        val bytes = requireNotNull(clazz.getResourceAsStream("/" + clazz.name.replace('.', '/') + ".class")).use { it.readAllBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        loaded += """{"className":"${clazz.name}","codeSource":"${code.toString().replace("\\", "\\\\")}","classSha256Bytes":"$hash"}"""
    }
    for (clazz in listOf(DesktopSessionStore::class.java, DesktopRepository::class.java,
        DesktopDynamicCardOperations::class.java, DesktopDynamicSaveTarget::class.java)) checkSource(clazz, mainJar)
    checkSource(DesktopDynamicImageAssets::class.java, frozenAssetsJar)
    checkSource(DesktopDynamicMotionPhotoFiles::class.java, filesJar)
    checkSource(Class.forName("com.bilipai.desktop.ui.DesktopDynamicMotionPhotoFilesKt"), filesJar)
    checkSource(Class.forName("com.android.purebilibili.feature.dynamic.components.DesktopOriginalMotionPhotoPackingKt"), frozenAssetsJar)

    val sessions = DesktopSessionStore.temporary()
    sessions.saveAccount(mapOf("SESSDATA" to "declared-task-only-cancel-fixture"), AccountSummary(120L, "Declared task fixture", ""))
    val repository = DesktopRepository(sessions)
    val operations = DesktopDynamicCardOperations(repository)
    val owner = requireNotNull(repository.dynamicCacheSessionGuard.dynamicCacheOwner())
    prove(repository.dynamicCacheSessionGuard === sessions, "exact actual Main Store guard")
    prove(owner.epoch == operations.expectedEpoch && operations.isOwned(), "live same epoch before commit wait")
    val forbiddenRequests = AtomicInteger()
    val forbiddenPicker = AtomicInteger()
    val transport = repository.httpClient.newBuilder().addInterceptor { _ ->
        forbiddenRequests.incrementAndGet(); error("This concern has no HTTP request")
    }.build()
    val assets = DesktopDynamicImageAssets(transport, operations::isOwned, repository.dynamicCacheSessionGuard, owner,
        selectTarget = { _, _ -> forbiddenPicker.incrementAndGet(); error("This concern has no picker") },
        selectDirectory = { forbiddenPicker.incrementAndGet(); error("This concern has no picker") })
    // Test-only access to the unchanged production binding; no copy of commitOwned.
    val files = DesktopDynamicImageAssets::class.java.getDeclaredField("motionPhoto").apply { isAccessible = true }.get(assets) as DesktopDynamicMotionPhotoFiles
    val monitor = DesktopSessionStore::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(sessions)
    val target = root.resolve("existing-target.jpg")
    val sentinel = "keep-existing-target-on-save-job-cancel".toByteArray()
    Files.write(target, sentinel)
    val entered = CountDownLatch(1); val release = CountDownLatch(1)
    val holderFailure = AtomicReference<Throwable?>()
    val holder = Thread({
        try { synchronized(monitor) { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) { "Store monitor release timeout" } } }
        catch (failure: Throwable) { holderFailure.set(failure) }
    }, "task-only-actual-store-monitor-holder").apply { isDaemon = true; start() }
    prove(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) }, "actual Store monitor held")
    val terminal = AtomicReference<Throwable?>()
    val save = launch(Dispatchers.IO) {
        try { files.composeDownloaded(image, video, DesktopDynamicSaveTarget(target, true)) }
        catch (failure: Throwable) { terminal.set(failure) }
    }
    var blockedThread = -1L
    var blockedFrames = emptyList<String>()
    val beans = ManagementFactory.getThreadMXBean()
    try {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (blockedThread < 0L) {
            val blocked = beans.getThreadInfo(beans.allThreadIds, 32).filterNotNull().firstOrNull { info ->
                info.threadState == Thread.State.BLOCKED && info.lockInfo?.identityHashCode == System.identityHashCode(monitor) &&
                    info.lockOwnerId == holder.threadId() &&
                    info.stackTrace.any { it.className == DesktopSessionStore::class.java.name && it.methodName == "withCurrentDynamicCacheOwner" } &&
                    info.stackTrace.any { it.className == DesktopDynamicImageAssets::class.java.name && it.methodName == "commitOwned" }
            }
            if (blocked != null) { blockedThread = blocked.threadId; blockedFrames = blocked.stackTrace.map { it.toString() } }
            else { check(System.nanoTime() < deadline) { "Save did not reach actual Store monitor through unchanged Assets commit: ${terminal.get()}" }; delay(10) }
        }
        prove(blockedThread > 0L, "actual saved coroutine thread blocked in Store -> Assets production commit")
        prove(Files.readAllBytes(target).contentEquals(sentinel), "target unchanged before cancel")
        prove(Files.list(root).use { stream -> stream.anyMatch { it.fileName.toString().startsWith(".bilipai-motion-photo-") } }, "complete packed scratch exists before actual commit wait")
        save.cancel(CancellationException("task-only save Job cancellation; all owners remain live"))
        prove(save.isCancelled && !save.isCompleted, "only save Job canceled while still blocked")
        prove(operations.isOwned() && repository.sessionEpoch == owner.epoch, "original Ops/account epoch stays live during cancellation")
        prove(Files.readAllBytes(target).contentEquals(sentinel), "canceled wait does not yet replace target")
    } finally { release.countDown() }
    save.join()
    withContext(Dispatchers.IO) { holder.join(5000) }
    prove(!holder.isAlive && holderFailure.get() == null, "real Store monitor holder released")
    prove(terminal.get() is CancellationException, "save completes canceled")
    val preserved = Files.readAllBytes(target).contentEquals(sentinel)
    prove(preserved == (mode == "candidate"), "candidate must retain target; baseline must reproduce canceled replacement")
    prove(Files.list(root).use { stream -> stream.noneMatch { it.fileName.toString().startsWith(".bilipai-") } }, "joined save has no scratch")
    prove(operations.isOwned() && sessions.dynamicCacheOwner() == owner, "original owner remains live after join")
    prove(!DesktopDynamicImageAssets::class.java.getDeclaredField("closed").apply { isAccessible = true }.getBoolean(assets), "actual unchanged Assets owner was never closed")
    prove(forbiddenRequests.get() == 0 && forbiddenPicker.get() == 0, "no HTTP or chooser invocation")
    assets.close()
    val stack = blockedFrames.joinToString(",") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }
    Files.writeString(root.resolve("result.json"), """{"passed":true,"mode":"$mode","assertions":$assertions,"cases":1,"correctCancellationTargetPreserved":$preserved,"baselineRegressionObserved":${mode == "baseline" && !preserved},"actualStoreMonitorBlockedThreadId":$blockedThread,"actualBlockedFrames":[$stack],"onlySaveJobCanceled":true,"accountEpochAndAssetsStayedLive":true,"unchangedAssetsCommitBindingExecuted":true,"scratchDrainedAfterJoin":true,"HTTP":false,"HWND":false,"chooser":false,"MainInstalled":false,"loadedClasses":[${loaded.joinToString(",")}] }""")
    println("PASS $mode $assertions assertions /1 focused case: actual Store monitor wait; only save Job cancellation; targetPreserved=$preserved")
}
