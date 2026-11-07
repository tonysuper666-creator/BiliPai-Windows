package com.bilipai.desktop.ui

import com.android.purebilibili.core.plugin.PluginInfo
import com.android.purebilibili.core.plugin.PluginManager
import com.android.purebilibili.feature.plugin.TodayWatchPlugin
import com.android.purebilibili.feature.plugin.TodayWatchPluginConfig
import com.android.purebilibili.feature.plugin.TodayWatchCandidatePoolMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import com.bilipai.desktop.ui.DesktopHomeFollowTodayFixture.Companion.video

class DesktopHomeTodayWatchBuildDispatchTest {
    /** Execute the actual withContext runnable, without replacing the recommendation algorithm. */
    private class ComputeQueue : CoroutineDispatcher(), AutoCloseable {
        val queue = LinkedBlockingQueue<Runnable>()
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "today-compute") }
        var executions = 0
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        suspend fun awaitCalculation() = withTimeout(3_000) {
            while (queue.isEmpty()) delay(5)
        }
        suspend fun runNext() = withContext(Dispatchers.IO) {
            val task = queue.poll(3, java.util.concurrent.TimeUnit.SECONDS) ?: error("VM never dispatched its calculation")
            worker.submit { executions++; task.run() }.get(3, java.util.concurrent.TimeUnit.SECONDS)
        }
        override fun close() { worker.shutdownNow() }
    }

    private class PluginFixture(expanded: Boolean = false) : AutoCloseable {
        val registry = PluginManager::class.java.getDeclaredField("registry").also { it.isAccessible = true }.get(PluginManager)
        @Suppress("UNCHECKED_CAST")
        val flow = registry.javaClass.getDeclaredField("mutablePluginsFlow").also { it.isAccessible = true }
            .get(registry) as MutableStateFlow<List<PluginInfo>>
        val previous = flow.value
        val plugin = TodayWatchPlugin { error("Pure recommendation must not read plugin context") }
        init {
            if (expanded) {
                val config = TodayWatchPluginConfig(candidatePoolMode = TodayWatchCandidatePoolMode.EXPANDED)
                plugin.javaClass.getDeclaredField("config").also { it.isAccessible = true }.set(plugin, config)
                @Suppress("UNCHECKED_CAST")
                val state = plugin.javaClass.getDeclaredField("_configState").also { it.isAccessible = true }
                    .get(plugin) as MutableStateFlow<TodayWatchPluginConfig>
                state.value = config
            }
            flow.value = listOf(PluginInfo(plugin, true))
        }
        override fun close() { flow.value = previous }
    }

    private fun field(vm: Any, name: String) = vm.javaClass.getDeclaredField(name).also { it.isAccessible = true }
    private suspend fun DesktopHomeFollowTodayFixture.build() {
        onOwner { vm.refreshTodayWatchOnly() }
    }
    private suspend fun DesktopHomeFollowTodayFixture.buildJob(): Job = onOwner {
        field(vm, "todayWatchRebuildJob").get(vm) as Job
    }

    @Test fun actualConsumerDispatchesCalculationAndReturnsPlanToOwner() = runBlocking {
        PluginFixture().use { _ -> ComputeQueue().use { compute -> DesktopHomeFollowTodayFixture(compute).use { h ->
            h.start(); h.state { it.videos.isNotEmpty() }
            compute.awaitCalculation()
            while (h.vm.uiState.value.todayWatchPlan == null) { compute.runNext(); h.onOwner { } }
            val state = h.state { !it.todayWatchLoading && it.todayWatchPlan != null }
            assertEquals(listOf("BV_initial"), state.todayWatchPlan!!.videoQueue.map { it.bvid })
            assertTrue(compute.executions > 0)
        } } }
    }

    @Test fun generationChangedDuringComputationDoesNotSubmitEvenWithoutCancellation() = runBlocking {
        PluginFixture().use { _ -> ComputeQueue().use { compute -> DesktopHomeFollowTodayFixture(compute).use { h ->
            h.start(); h.state { it.videos.isNotEmpty() }; compute.awaitCalculation()
            val job = h.buildJob()
            // Model the generation boundary independently of Job cancellation, so the result guard is essential.
            h.onOwner {
                val generation = field(h.vm, "todayWatchBuildGeneration")
                generation.setLong(h.vm, generation.getLong(h.vm) + 1)
            }
            while (!job.isCompleted) { compute.runNext(); h.onOwner { } }
            assertNull(h.vm.uiState.value.todayWatchPlan)
        } } }
    }

    @Test fun retiredOwnerAndCancelledBuildCannotPublishComputedPlan() = runBlocking {
        for (retire in listOf(true, false)) PluginFixture().use { _ -> ComputeQueue().use { compute -> DesktopHomeFollowTodayFixture(compute).use { h ->
            h.start(); h.state { it.videos.isNotEmpty() }; compute.awaitCalculation()
            val job = h.buildJob()
            h.onOwner { if (retire) synchronized(h.gate) { h.current = false } else job.cancel() }
            while (!job.isCompleted) { compute.runNext(); h.onOwner { } }
            assertNull(h.vm.uiState.value.todayWatchPlan)
        } } }
    }

    @Test fun freshAndCachedExpandedResultsRecheckGenerationAfterCalculation() = runBlocking {
        for (cached in listOf(false, true)) PluginFixture(expanded = true).use { _ ->
            ComputeQueue().use { compute -> DesktopHomeFollowTodayFixture(compute).use { h ->
                h.recommend = { idx -> Result.success(listOf(video(if (idx == 0) "BV_initial" else "BV_expanded"))) }
                h.start(); h.state { it.videos.isNotEmpty() }; compute.awaitCalculation()
                // Configuration and initial feed can supersede an earlier build;
                // drain cancelled dispatches until the current local result commits.
                while (h.vm.uiState.value.todayWatchPlan == null) { compute.runNext(); h.onOwner { } }
                compute.awaitCalculation()
                if (cached) {
                    // Finish the first expansion to populate the actual VM cache, then rebuild.
                    compute.runNext()
                    h.state { it.todayWatchPlan?.videoQueue?.any { video -> video.bvid == "BV_expanded" } == true }
                    val requests = h.requests.size
                    h.build(); compute.awaitCalculation(); compute.runNext()
                    h.state { !it.todayWatchLoading && it.todayWatchPlan != null }
                    compute.awaitCalculation()
                    assertEquals(requests, h.requests.size, "Cached expansion must not refetch candidates")
                }
                val job = h.buildJob()
                val localPlan = h.vm.uiState.value.todayWatchPlan
                h.onOwner {
                    val generation = field(h.vm, "todayWatchBuildGeneration")
                    generation.setLong(h.vm, generation.getLong(h.vm) + 1)
                }
                compute.runNext(); withTimeout(3_000) { job.join() }
                assertEquals(localPlan, h.vm.uiState.value.todayWatchPlan, "cached=$cached")
            } }
        }
    }

    @Test fun historySampleStillUsesTenMinuteTtlThroughTheActualBuildConsumer() = runBlocking {
        PluginFixture().use { _ -> DesktopHomeFollowTodayFixture().use { h ->
            h.start(); h.state { it.todayWatchPlan != null && !it.todayWatchLoading }
            h.onOwner {
                field(h.vm, "historySampleCache").set(h.vm, listOf(video("history")))
                field(h.vm, "historySampleCacheComplete").setBoolean(h.vm, true)
                field(h.vm, "historySampleLoadedAtMs").setLong(h.vm, System.currentTimeMillis() - 9 * 60_000)
            }
            val before = h.historyCalls
            h.build(); withTimeout(3_000) { h.buildJob().join() }
            assertEquals(before, h.historyCalls, "A nine-minute cache remains reusable")
            h.onOwner { field(h.vm, "historySampleLoadedAtMs").setLong(h.vm, System.currentTimeMillis() - 11 * 60_000) }
            h.build(); withTimeout(3_000) { h.buildJob().join() }
            assertEquals(before + 1, h.historyCalls, "An eleven-minute cache must reload")
        } }
    }
}
