package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.DesktopOriginalFavoriteFolderProtocol
import com.android.purebilibili.data.repository.DesktopOriginalVideoEngagementProtocol
import com.android.purebilibili.feature.list.DesktopFavoriteEnvironment
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.usecase.VideoInteractionUseCase
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackViewModel
import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.*

/** Complete generated playback VM, real invocation/settings adapters and original
 * group protocol. Only asynchronous API responses are controlled. No media, DLL,
 * account credentials, user profile or real relationship request is opened. */
class DesktopWindowsVideoFollowGroupViewModelTest {
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java)
    ) { _, method, _ -> error("Unexpected ${T::class.java.simpleName}.${method.name}") } as T

    private inner class Harness : AutoCloseable {
        val folder = Files.createTempDirectory("original-group-vm-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val context = DesktopPluginContext(DesktopPluginStore(folder))
        var account = 1L
        var entry = true
        var admissionDepth = 0
        var beforeAdmission: () -> Unit = {}
        val original = publication()
        var accepted = original
        val idsCalls = mutableListOf<Long>()
        val saves = mutableListOf<Pair<Set<Long>, Set<Long>>>()
        var tags: suspend () -> Result<List<RelationTagItem>> = {
            Result.success(listOf(RelationTagItem(0, "默认"), RelationTagItem(8, "测试组")))
        }
        var ids: suspend (Long) -> Result<Set<Long>> = { Result.success(setOf(0, 8)) }
        var save: suspend (Set<Long>, Set<Long>) -> Result<Boolean> = { _, _ -> Result.success(true) }
        fun publication() = DesktopOriginalVideoAcceptedPublication(
            PlaybackRequest.create("BVfixture", aid = 17, cid = 70),
            OwnedPlaybackSourceSnapshot(1, PlaybackSource("https://fixture.invalid/no-media")))
        fun entryCurrent() = entry && account == 1L
        fun sourceCurrent(expected: DesktopOriginalVideoAcceptedPublication) = entryCurrent() && accepted === expected
        fun commit(action: () -> Unit): Boolean {
            if (!entryCurrent()) return false
            action(); return true
        }
        fun admit(expected: DesktopOriginalVideoAcceptedPublication, action: () -> Unit): Boolean {
            beforeAdmission()
            if (!sourceCurrent(expected)) return false
            admissionDepth++
            return try { if (!sourceCurrent(expected)) false else { action(); true } }
            finally { admissionDepth-- }
        }
        val actions = object : DesktopOriginalVideoOwnerActions {
            override suspend fun getFollowGroupTags(): Result<List<RelationTagItem>> {
                assertEquals(0, admissionDepth); return tags()
            }
            override suspend fun getUserFollowGroupIds(mid: Long): Result<Set<Long>> {
                assertEquals(0, admissionDepth); idsCalls += mid; return ids(mid)
            }
            override suspend fun overwriteFollowGroupIds(targetMids: Set<Long>, selectedTagIds: Set<Long>): Result<Boolean> {
                assertEquals(0, admissionDepth); saves += targetMids.toSet() to selectedTagIds.toSet()
                return save(targetMids, selectedTagIds)
            }
            override suspend fun checkFollowStatus(mid: Long) = error("No initial load")
            override suspend fun checkFavoriteStatus(aid: Long) = error("No initial load")
            override suspend fun checkLikeStatus(aid: Long) = error("No initial load")
            override suspend fun checkCoinStatus(aid: Long) = error("No initial load")
            override suspend fun checkWatchLaterStatus(aid: Long) = error("No initial load")
            override suspend fun checkDislikeStatus(aid: Long) = error("No initial load")
            override suspend fun createFavFolder(title: String, intro: String, isPrivate: Boolean) = error("No folder creation")
        }
        val plugins = Proxy.newProxyInstance(DesktopOriginalVideoOwnerPlugins::class.java.classLoader,
            arrayOf(DesktopOriginalVideoOwnerPlugins::class.java)) { _, method, args ->
            when (method.name) {
                "capturePlaybackDispatch" -> accepted.takeIf { entryCurrent() }
                "isPlaybackDispatchCurrent" -> sourceCurrent(args!![0] as DesktopOriginalVideoAcceptedPublication)
                "admitPlaybackDispatch" -> {
                    @Suppress("UNCHECKED_CAST")
                    admit(args!![0] as DesktopOriginalVideoAcceptedPublication, args[1] as () -> Unit)
                }
                else -> error("No plugin execution: ${method.name}")
            }
        } as DesktopOriginalVideoOwnerPlugins
        val repository = unused<DesktopOriginalVideoOwnerRepository>()
        val media = unused<DesktopOriginalVideoMediaPort>()
        val invocations = DesktopOriginalVideoPlaybackInvocationPorts(scope, ::entryCurrent, {
            val caller = currentCoroutineContext().job
            val epoch = account
            DesktopOriginalVideoPlaybackInvocation(repository, media) {
                if (!entry || account != epoch || !caller.isActive) throw CancellationException("Captured API caller retired")
            }
        }, unused<DesktopOriginalVideoPlaybackStatus>(), { media })
        private val api = unused<BilibiliApi>()
        private val interaction = VideoInteractionUseCase(DesktopOriginalVideoEngagementProtocol(
            api, { "fixture-csrf" }, { 1L }, { "fixture-session" }, { null }, {}, {},
            DesktopOriginalFavoriteFolderProtocol(api, { 1L }, { "fixture-csrf" }, {})),
            unused<DesktopOriginalVideoInteractionAnalytics>())
        private val settings = DesktopOriginalPlayerSettingsContext(context, ::entryCurrent, ::commit,
            largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { false })
        private val useCase = DesktopOriginalVideoPlaybackUseCaseEnvironment(context, repository, actions,
            unused<DesktopOriginalVideoProgressPort>(), unused<DesktopOriginalVideoPlaybackCapabilities>(), media,
            { error("No volume effect") }, { emptyMap() }, {}, { false }, { _, _, _, _ -> }, ::entryCurrent)
        val vm = VideoPlaybackViewModel(DesktopOriginalVideoPlaybackOwnerEnvironment(
            scope, settings, invocations, repository, unused(), actions, useCase, interaction,
            unused(), unused(), unused(), plugins, unused(), unused(), unused(), { null },
            unused(), unused(), unused(), unused(), MutableStateFlow(false), ::entryCurrent, ::commit,
            DesktopTodayWatchFeedbackWriteBinding(context, ::entryCurrent, ::commit)))
        fun open(mid: Long = 33): DesktopWindowsVideoFollowGroupRequest {
            vm.showFollowGroupDialogForUser(mid)
            return assertNotNull(vm.desktopFollowGroupRequest.value)
        }
        suspend fun loaded() = withTimeout(2_000) { vm.isFollowGroupsLoading.first { !it } }
        suspend fun idleSave() = withTimeout(2_000) { vm.isSavingFollowGroups.first { !it } }
        suspend fun noToast() = assertNull(withTimeoutOrNull(60) { vm.toastEvent.first() })
        override fun close() {
            vm.desktopFollowGroupRequest.value?.close()
            scope.cancel()
            folder.toFile().deleteRecursively()
        }
    }

    @Test fun lateLoadAfterSameMidCloseAndReopenCannotChangeSuccessorState() = runBlocking {
        Harness().use { h ->
            val aTags = CompletableDeferred<Result<List<RelationTagItem>>>()
            h.tags = { withContext(NonCancellable) { aTags.await() } }
            val a = h.open(); assertTrue(h.vm.isFollowGroupsLoading.value)
            assertTrue(a.dispatch { h.vm.dismissFollowGroupDialog() })
            val bTags = CompletableDeferred<Result<List<RelationTagItem>>>()
            h.tags = { bTags.await() }
            val b = h.open(); assertNotSame(a, b)
            aTags.complete(Result.success(listOf(RelationTagItem(9, "旧组"))))
            yield()
            assertSame(b, h.vm.desktopFollowGroupRequest.value)
            assertTrue(h.vm.isFollowGroupsLoading.value); assertTrue(h.idsCalls.isEmpty())
            assertFalse(a.dispatch { h.vm.dismissFollowGroupDialog() })
            bTags.complete(Result.success(listOf(RelationTagItem(8, "新组")))); h.loaded()
            assertEquals(listOf("新组"), h.vm.followGroupTags.value.map { it.name })
            assertEquals(setOf(8L), h.vm.followGroupSelectedTagIds.value)
            assertEquals(listOf(33L), h.idsCalls)
        }
    }

    @Test fun oldSecondAwaitAndCallbacksCannotMixDifferentTargetOrMutableSaveArguments() = runBlocking {
        Harness().use { h ->
            val aIds = CompletableDeferred<Result<Set<Long>>>()
            h.ids = { withContext(NonCancellable) { aIds.await() } }
            val a = h.open(33)
            h.ids = { Result.success(setOf(8)) }
            val b = h.open(44); h.loaded()
            assertFalse(a.dispatch { h.vm.toggleFollowGroupSelection(9); h.vm.saveFollowGroupSelection(); h.vm.dismissFollowGroupDialog() })
            aIds.complete(Result.success(setOf(9))); yield()
            assertSame(b, h.vm.desktopFollowGroupRequest.value)
            assertEquals(setOf(8L), h.vm.followGroupSelectedTagIds.value)
            assertTrue(b.dispatch { h.vm.saveFollowGroupSelection() }); h.idleSave()
            assertEquals(listOf(setOf(44L) to setOf(8L)), h.saves)
        }
    }

    @Test fun accountAndEqualNumericSourceReplacementRejectBeforeSecondApiAndFinalCommit() = runBlocking {
        for (retire in listOf<(Harness) -> Unit>({ it.account = 2 }, { it.accepted = it.publication() })) {
            Harness().use { h ->
                val tags = CompletableDeferred<Result<List<RelationTagItem>>>()
                h.tags = { withContext(NonCancellable) { tags.await() } }
                val a = h.open(); retire(h)
                tags.complete(Result.success(listOf(RelationTagItem(9, "旧组")))); yield()
                assertTrue(h.idsCalls.isEmpty()); assertTrue(h.vm.followGroupTags.value.isEmpty())
                assertFalse(a.dispatch { h.vm.saveFollowGroupSelection() }); assertTrue(h.saves.isEmpty())
                h.noToast()
            }
        }
        Harness().use { h ->
            val ids = CompletableDeferred<Result<Set<Long>>>()
            h.ids = { ids.await() }; h.open()
            h.beforeAdmission = { h.accepted = h.publication() }
            ids.complete(Result.success(setOf(8))); yield()
            assertTrue(h.vm.followGroupTags.value.isEmpty())
            assertTrue(h.vm.followGroupSelectedTagIds.value.isEmpty())
            h.beforeAdmission = {}; h.ids = { Result.success(setOf(8)) }
            h.open(); h.loaded(); assertEquals(setOf(8L), h.vm.followGroupSelectedTagIds.value)
        }
    }

    @Test fun busyPrecedesStartAndCancellationResultCannotClearNewSaveOrReportSuccess() = runBlocking {
        Harness().use { h ->
            val aResult = CompletableDeferred<Result<Boolean>>()
            h.save = { _, _ -> withContext(NonCancellable) { aResult.await() } }
            val a = h.open(); h.loaded()
            assertTrue(a.dispatch { h.vm.saveFollowGroupSelection() })
            assertTrue(h.vm.isSavingFollowGroups.value)
            assertTrue(a.dispatch { h.vm.saveFollowGroupSelection() }); assertEquals(1, h.saves.size)
            assertTrue(a.dispatch { h.vm.dismissFollowGroupDialog() })
            val bResult = CompletableDeferred<Result<Boolean>>()
            h.save = { _, _ -> bResult.await() }
            val b = h.open(); h.loaded(); assertTrue(b.dispatch { h.vm.saveFollowGroupSelection() })
            aResult.complete(Result.success(true)); yield()
            assertSame(b, h.vm.desktopFollowGroupRequest.value); assertTrue(h.vm.isSavingFollowGroups.value)
            bResult.complete(Result.failure(CancellationException("cancelled response"))); h.idleSave()
            assertTrue(h.vm.followGroupDialogVisible.value); h.noToast()
            h.save = { _, _ -> Result.success(true) }
            assertTrue(b.dispatch { h.vm.saveFollowGroupSelection() }); h.idleSave()
            assertFalse(h.vm.followGroupDialogVisible.value)
        }
    }

    @Test fun loadCancellationFailureReleasesOnlyCurrentBusyAndDoesNotStartIdsOrToast() = runBlocking {
        Harness().use { h ->
            h.tags = { Result.failure(CancellationException("tags cancelled")) }
            h.open(); h.loaded()
            assertTrue(h.idsCalls.isEmpty()); assertTrue(h.vm.followGroupTags.value.isEmpty()); h.noToast()
            h.tags = { Result.success(listOf(RelationTagItem(8, "恢复"))) }
            h.open(); h.loaded(); assertEquals(listOf("恢复"), h.vm.followGroupTags.value.map { it.name })
        }
    }

    @Test fun queuedOriginalSaveFeedbackIsDroppedWhenSourceOrDialogChangesBeforeConsumption() = runBlocking {
        for (replace in listOf<(Harness) -> Unit>({ it.accepted = it.publication() }, { it.open(44) })) {
            Harness().use { h ->
                val request = h.open(); h.loaded()
                assertTrue(request.dispatch { h.vm.saveFollowGroupSelection() }); h.idleSave()
                assertFalse(h.vm.followGroupDialogVisible.value)
                replace(h); h.noToast()
            }
        }
        Harness().use { h ->
            val request = h.open(); h.loaded()
            assertTrue(request.dispatch { h.vm.saveFollowGroupSelection() }); h.idleSave()
            val toast = withTimeout(2_000) { h.vm.toastEvent.first() }
            assertEquals("分组设置已保存", toast.message)
        }
    }

    @Test fun realOverwriteProtocolKeepsResetApplyFailureRetryAndEmptyDefaultSelection() = runBlocking {
        Harness().use { h ->
            val calls = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
            var failApply = true
            val api = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader, arrayOf(BilibiliApi::class.java)) { _, method, args ->
                assertEquals("addUsersToRelationTags", method.name)
                calls += (args!![0] as String) to (args[1] as String)
                if (failApply && args[1] != "0") SimpleApiResponse(code = -400, message = "拒绝应用")
                else SimpleApiResponse()
            } as BilibiliApi
            h.save = { mids, selected ->
                val caller = currentCoroutineContext().job
                DesktopFavoriteEnvironment.forFolderDrawer(h.scope, api,
                    { h.entryCurrent() && caller.isActive }, { "fixture-csrf" }, { 1L }, {}).actions
                    .overwriteFollowGroupIds(mids, selected)
            }
            val request = h.open(); h.loaded()
            assertTrue(request.dispatch { h.vm.saveFollowGroupSelection() }); h.idleSave()
            assertEquals(listOf("33" to "0", "33" to "8"), calls.toList())
            assertTrue(h.vm.followGroupDialogVisible.value)
            assertTrue(withTimeout(2_000) { h.vm.toastEvent.first() }.message.startsWith("分组设置失败:"))
            failApply = false
            assertTrue(request.dispatch { h.vm.saveFollowGroupSelection() }); h.idleSave()
            assertEquals(listOf("33" to "0", "33" to "8", "33" to "0", "33" to "8"), calls.toList())
            val next = h.open(44); h.loaded()
            assertTrue(next.dispatch { h.vm.toggleFollowGroupSelection(8); h.vm.saveFollowGroupSelection() }); h.idleSave()
            assertEquals("44" to "0", calls.last()); assertEquals(5, calls.size)
            assertEquals(setOf(44L) to emptySet(), h.saves.last())
        }
    }

    @Test fun cancellingRealProtocolAfterResetCannotApplySelectionOrPublishSuccess() = runBlocking {
        Harness().use { h ->
            val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
            var caller: Job? = null
            val api = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader, arrayOf(BilibiliApi::class.java)) { _, method, args ->
                assertEquals("addUsersToRelationTags", method.name)
                calls += args!![1] as String
                caller!!.cancel(); SimpleApiResponse()
            } as BilibiliApi
            h.save = { mids, selected ->
                val captured = currentCoroutineContext().job; caller = captured
                DesktopFavoriteEnvironment.forFolderDrawer(h.scope, api,
                    { h.entryCurrent() && captured.isActive }, { "fixture-csrf" }, { 1L }, {}).actions
                    .overwriteFollowGroupIds(mids, selected)
            }
            val request = h.open(); h.loaded()
            assertTrue(request.dispatch { h.vm.saveFollowGroupSelection() }); h.idleSave()
            assertEquals(listOf("0"), calls.toList()); assertTrue(h.vm.followGroupDialogVisible.value)
            h.noToast()
        }
    }
}
