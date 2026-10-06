package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.core.network.DynamicRepostContentItem
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.viewmodel.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot
import com.bilipai.desktop.player.PlaybackSource as NativePlaybackSource
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Actual Root Ops -> generated full repository -> generated Retrofit/model.
 * Requests terminate at a memory interceptor; no socket/profile/window is used. */
class DesktopVideoDynamicShareTest {
    private data class Captured(val request: Request, val body: String)
    private class Fixture : AutoCloseable {
        val sessions = DesktopSessionStore.temporary()
        val repository = DesktopRepository(sessions)
        val subject = VideoSubjectSnapshot("BVfixture", 9_000_000_001, 17, 33, "Title", "", 60_000, 1)
        val source = DesktopOriginalVideoAcceptedPublication(
            PlaybackRequest.create(subject.bvid, aid = subject.aid, cid = subject.cid),
            OwnedPlaybackSourceSnapshot(1, NativePlaybackSource("https://fixture.invalid/video")))
        @Volatile var accepted = source
        @Volatile var popup = true
        val requests = CopyOnWriteArrayList<Captured>()
        var reply: (Request) -> String = { request -> when(request.url.encodedPath) {
            "/x/web-interface/view" -> """{"code":0,"data":{"aid":9000000001,"bvid":"BVfixture"}}"""
            "/x/dynamic/feed/create/dyn" -> """{"code":0}"""
            "/x/dynamic/feed/edit/dyn" -> """{"code":0}"""
            else -> error("Memory transport refuses ${request.method} ${request.url}")
        } }
        val operations: DesktopDynamicCardOperations
        val epoch: Long
        init {
            sessions.saveAccount(mapOf("SESSDATA" to "SYNTHETIC-NOT-A-CREDENTIAL", "bili_jct" to "fixture-csrf"),
                AccountSummary(123, "Fixture", ""))
            val transport = repository.httpClient.newBuilder().retryOnConnectionFailure(false).addInterceptor { chain ->
                val request = chain.request(); val bytes = Buffer(); request.body?.writeTo(bytes)
                requests += Captured(request, bytes.readUtf8())
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("memory")
                    .body(reply(request).toResponseBody("application/json".toMediaType())).build()
            }.build()
            // Same existing test seam as DesktopLiveOwnedStreamTest; no new production port.
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible=true }.set(repository,transport)
            DesktopRepository::class.java.getDeclaredField("visitorInitialized").apply { isAccessible=true }.set(repository,true)
            DesktopRepository::class.java.getDeclaredField("visitorGeneration").apply { isAccessible=true }.set(repository,repository.sessionEpoch)
            epoch = repository.sessionEpoch
            operations = DesktopDynamicCardOperations(repository, epoch)
        }
        fun sourceOwned() = accepted === source && repository.sessionEpoch == epoch
        fun sourceAdmit(action: () -> Unit): Boolean {
            if (!sourceOwned()) return false
            repository.withPrimaryPlaybackAdmission(epoch, ::sourceOwned) { action() }
            return true
        }
        fun current() = popup && accepted === source && repository.sessionEpoch == epoch
        fun admit(action: () -> Unit): Boolean {
            if (!current()) return false
            repository.withPrimaryPlaybackAdmission(epoch, ::current) { action() }
            return true
        }
        suspend fun share(text: String="retained draft") = operations.shareVideoToDynamic(subject.bvid,text,::current,::admit)
        fun posts() = requests.filter { it.request.method == "POST" }
        override fun close() { repository.httpClient.dispatcher.cancelAll(); repository.httpClient.connectionPool.evictAll() }
    }
    private val json=Json { ignoreUnknownKeys=true }

    @Test fun actualVideoRepostUsesAidLongAtTopLevelAndOriginalSceneFive(): Unit = runBlocking {
        withTimeout(5_000) { Fixture().use { f ->
            assertEquals("ok",f.share("  actual draft  ").getOrThrow())
            assertEquals(listOf("GET","POST"),f.requests.map{it.request.method})
            assertEquals("BVfixture",f.requests.first().request.url.queryParameter("bvid"))
            val request=json.parseToJsonElement(f.posts().single().body).jsonObject
            assertEquals(setOf("dyn_req","web_repost_src"),request.keys)
            assertEquals(5,request.getValue("dyn_req").jsonObject.getValue("scene").jsonPrimitive.int)
            val resource=request.getValue("web_repost_src").jsonObject.getValue("revs_id").jsonObject
            assertEquals(9_000_000_001L,resource.getValue("rid").jsonPrimitive.long)
            assertEquals(8,resource.getValue("dyn_type").jsonPrimitive.int)
            assertEquals("fixture-csrf",f.posts().single().request.url.queryParameter("csrf"))
            val content=request.getValue("dyn_req").jsonObject.getValue("content").jsonObject.getValue("contents").jsonArray.single().jsonObject
            assertEquals("actual draft",content.getValue("raw_text").jsonPrimitive.content)
        } }
    }

    @Test fun originalBlankAndTwoThousandLimitAreConsumedBeforeNetwork(): Unit = runBlocking {
        withTimeout(5_000) { Fixture().use { f ->
            assertTrue(f.share("").isSuccess)
            assertTrue(f.posts().single().body.contains("转发视频"))
            assertTrue(f.share("文".repeat(2000)).isSuccess)
            val before=f.requests.size
            assertEquals("分享文字不能超过 2000 字",f.share("文".repeat(2001)).exceptionOrNull()?.message)
            assertEquals(before,f.requests.size)
        } }
    }

    @Test fun failedVideoLookupOrMissingAidCannotPublish(): Unit = runBlocking {
        withTimeout(5_000) { for (response in listOf("""{"code":-1,"message":"lookup failed"}""","""{"code":0,"data":{"aid":0}}""")) Fixture().use { f ->
            f.reply={response}; assertTrue(f.share().isFailure); assertTrue(f.posts().isEmpty())
        } }
    }

    @Test fun originalPublishFailureKeepsTheTextForExplicitRetryAndMissingIdIsSuccess(): Unit = runBlocking {
        withTimeout(5_000) { Fixture().use { f ->
            val original=f.reply; var publishes=0
            f.reply={request->if(request.method=="POST" && ++publishes==1) """{"code":-1,"message":"please retry"}""" else original(request)}
            assertEquals("please retry",f.share("same retained text").exceptionOrNull()?.message)
            assertEquals("ok",f.share("same retained text").getOrThrow())
            val texts=f.posts().map { json.parseToJsonElement(it.body).jsonObject.getValue("dyn_req").jsonObject.getValue("content").jsonObject.getValue("contents").jsonArray.single().jsonObject.getValue("raw_text").jsonPrimitive.content }
            assertEquals(listOf("same retained text","same retained text"),texts)
        } }
    }

    @Test fun sameBvCidNumericVersionWithNewAcceptedAfterGetCannotSendPost(): Unit = runBlocking {
        withTimeout(5_000) { Fixture().use { f ->
            val original=f.reply;f.reply={request->original(request).also { if(request.method=="GET") f.accepted=DesktopOriginalVideoAcceptedPublication(f.source.request,f.source.nativeSource) }}
            assertFailsWith<CancellationException>{f.share()};assertEquals(listOf("GET"),f.requests.map{it.request.method})
        } }
    }

    @Test fun realAccountEpochRetirementAfterGetCannotSendPost(): Unit = runBlocking {
        withTimeout(5_000) { Fixture().use { f ->
            val original=f.reply;f.reply={request->original(request).also { if(request.method=="GET") f.sessions.logout() }}
            assertFailsWith<CancellationException>{f.share()};assertTrue(f.posts().isEmpty())
        } }
    }

    @Test fun cancelledActualGetCallerCannotSendPost(): Unit = runBlocking {
        withTimeout(5_000) { Fixture().use { f ->
            val entered=CountDownLatch(1);val release=CountDownLatch(1);val original=f.reply
            f.reply={request->original(request).also { if(request.method=="GET") {entered.countDown();check(release.await(3,TimeUnit.SECONDS))} }}
            val task=launch { f.share() }
            try { withContext(Dispatchers.IO){check(entered.await(3,TimeUnit.SECONDS))};task.cancel() }
            finally {release.countDown()}
            task.join();assertTrue(task.isCancelled);assertTrue(f.posts().isEmpty())
        } }
    }

    @Test fun existingMutationMutexQueueCannotBorrowSuccessorSource(): Unit = runBlocking {
        withTimeout(5_000) { Fixture().use { f ->
            val mutex=DesktopDynamicCardOperations::class.java.getDeclaredField("mutex").apply{isAccessible=true}.get(f.operations) as Mutex
            mutex.lock()
            val task=async(start=CoroutineStart.UNDISPATCHED){runCatching{f.share()}}
            try { assertFalse(task.isCompleted); f.accepted=DesktopOriginalVideoAcceptedPublication(f.source.request,f.source.nativeSource) }
            finally {mutex.unlock()}
            assertIs<CancellationException>(task.await().exceptionOrNull());assertTrue(f.requests.isEmpty())
        } }
    }

    @Test fun realFinalSessionAdmissionRaceAfterGetStillRejectsPost(): Unit = runBlocking {
        withTimeout(5_000) { Fixture().use { f ->
            var admissions=0
            assertFailsWith<CancellationException> {
                f.operations.shareVideoToDynamic(f.subject.bvid,"retained",f::current) { action ->
                    if (++admissions==2) f.sessions.logout()
                    // Exercise the actual throwing -101 admission, not a fake false gate.
                    f.repository.withPrimaryPlaybackAdmission(f.epoch,f::current){action()}
                    true
                }
            }
            assertEquals(2,admissions);assertEquals(listOf("GET"),f.requests.map{it.request.method})
        } }
    }

    @Test fun guestGuardRejectsBeforeAnyGetOrMutation(): Unit = runBlocking {
        withTimeout(5_000) { Fixture().use { f ->
            f.sessions.logout()
            val guest=DesktopDynamicCardOperations(f.repository,f.repository.sessionEpoch)
            assertFalse(guest.canShareVideoToDynamic())
            assertTrue(guest.shareVideoToDynamic("BVfixture","",{true},{action->action();true}).isFailure)
            assertTrue(f.requests.isEmpty())
        } }
    }

    @Test fun actualOrdinaryCreateAndEditSerializationStillOmitVideoRepostSource(): Unit = runBlocking {
        withTimeout(5_000) { Fixture().use { f ->
            val draft=DynamicPublishDraft("ordinary create")
            assertTrue(f.operations.publishDynamic(draft){error("text-only must not load an image")}.isSuccess)
            assertTrue(f.operations.editDynamic("111",draft){error("text-only must not load an image")}.isSuccess)
            assertEquals(2,f.posts().size)
            f.posts().forEach { captured ->
                val request=json.parseToJsonElement(captured.body).jsonObject
                assertFalse("web_repost_src" in request)
                assertEquals(1,request.getValue("dyn_req").jsonObject.getValue("scene").jsonPrimitive.int)
            }
            assertEquals("111",json.parseToJsonElement(f.posts().last().body).jsonObject.getValue("dyn_id_str").jsonPrimitive.content)
        } }
    }

    @Test fun optionalVideoFieldDoesNotAlterOriginalPictureSceneSerialization() {
        val request=DynamicCreateFeedRequest(DynamicCreateFeedReq(DynamicCreateFeedContent(listOf(
            DynamicRepostContentItem(raw_text="picture",type=1,biz_id=""))),scene=2,
            pics=listOf(DynamicCreatePic("https://fixture.invalid/p.png",10,20,1f)),upload_id="fixture"))
        assertEquals("""{"dyn_req":{"content":{"contents":[{"raw_text":"picture","type":1,"biz_id":""}]},"scene":2,"pics":[{"img_src":"https://fixture.invalid/p.png","img_width":10,"img_height":20,"img_size":1.0}],"upload_id":"fixture"}}""",Json.encodeToString(request))
    }

    private suspend fun feedbackCase(expectFailure: Boolean = false, change: (Fixture) -> Unit) {
        Fixture().use { f ->
            val folder=Files.createTempDirectory("video-share-feedback-")
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
            val actions=Proxy.newProxyInstance(VideoEngagementActions::class.java.classLoader,arrayOf(VideoEngagementActions::class.java)) {_,method,_->error("Share feedback must not call ${method.name}")} as VideoEngagementActions
            val vm=VideoEngagementViewModel(DesktopOriginalVideoEngagementEnvironment(
                DesktopPluginContext(DesktopPluginStore(folder)),scope,actions,VideoCoinBalanceLoader{0.0},
                {f.repository.sessionEpoch==f.epoch},{action->if(f.repository.sessionEpoch!=f.epoch)false else {action();true}}))
            vm.bindSubject(f.subject,VideoEngagementSeed(isLoggedIn=true))
            fun sourceOwned()=f.accepted===f.source&&f.repository.sessionEpoch==f.epoch
            fun sourceAdmit(action:()->Unit):Boolean {if(!sourceOwned())return false;f.repository.withPrimaryPlaybackAdmission(f.epoch,::sourceOwned){action()};return true}
            val presentation=DesktopOriginalVideoEngagementPresentation(f.source,f.subject,f::current,f::admit,::sourceOwned,::sourceAdmit)
            val files=DesktopVideoShareFiles(folder,{error("No image/file IO expected")},::sourceOwned,::sourceAdmit)
            val following=object:DesktopHomeFollowingRequests{override suspend fun getFollowings(mid:Long,page:Int,pageSize:Int)=error("No following request expected")}
            val order=mutableListOf<String>()
            if (expectFailure) { val original=f.reply;f.reply={request->if(request.method=="POST") """{"code":-1,"message":"retry"}""" else original(request)} }
            val bindings=DesktopVideoShareBindings(f.operations,following,{_,_->error("No friend send")},files,{123L},::sourceOwned,
                {error("No clipboard")},{order+="toast"},DesktopTextShareBindings{_,_,_->error("No system share")},
                {_,_,_,_,_->error("No native media")},{_,_->error("No save chooser")},{false},{1024},{768})
                .forPresentation(f::current,f::admit,::sourceOwned).withPreparedFeedback { bvid ->
                    withContext(presentation.context) {vm.showShareFeedback(bvid);order+="prepared"}
                }
            try {
                val caller=scope.async {
                    bindings.shareToDynamic(f.subject.bvid,"confirmed").onSuccess {
                        bindings.sharePrepared(f.subject.bvid);bindings.showFeedback("已分享到动态");order+="dismiss";f.popup=false
                    }
                }
                val result=caller.await();assertTrue(caller.isCompleted)
                if (expectFailure) {
                    assertTrue(result.isFailure);assertTrue(order.isEmpty());assertTrue(f.popup)
                    assertNull(vm.uiState.value.desktopMaidFeedbackOrigin);assertNull(vm.uiState.value.maidAction)
                    return@use
                }
                assertTrue(result.isSuccess)
                assertEquals(listOf("prepared","toast","dismiss"),order)
                val origin=assertNotNull(vm.uiState.value.desktopMaidFeedbackOrigin)
                assertEquals(VideoMaidAction.SHARE,vm.uiState.value.maidAction)
                assertTrue(origin.admitCurrent(f.source,f.subject){})
                assertFalse(f.popup,"Original sheet/caller have finished; the confirmed receipt still owns this source")
                change(f)
                assertFalse(origin.admitCurrent(f.accepted,f.subject){})
            } finally {scope.cancel();folder.toFile().deleteRecursively()}
        }
    }

    @Test fun actualPreparedReceiptSurvivesPopupCallerFinishAndRejectsSameValueSuccessor(): Unit = runBlocking {
        withTimeout(5_000){feedbackCase{f->f.accepted=DesktopOriginalVideoAcceptedPublication(f.source.request,f.source.nativeSource)}}
    }
    @Test fun actualFailedProtocolNeverPublishesShareFeedbackOrDismisses(): Unit = runBlocking {
        withTimeout(5_000){feedbackCase(expectFailure=true){error("No success receipt should exist")}}
    }
    @Test fun actualPreparedReceiptRejectsRealAccountRetirement(): Unit = runBlocking {
        withTimeout(5_000){feedbackCase{f->f.sessions.logout()}}
    }

    private fun retainedBindings(
        f: Fixture, files: DesktopVideoShareFiles,
        copy: (String) -> Unit = { error("No clipboard expected") },
        system: DesktopTextShareBindings = DesktopTextShareBindings { _, _, _ -> error("No system chooser expected") },
        guardedSave: (suspend (String, String, () -> Boolean) -> java.nio.file.Path?)? = null,
    ): DesktopVideoShareBindings {
        val following = object : DesktopHomeFollowingRequests {
            override suspend fun getFollowings(mid: Long, page: Int, pageSize: Int) = error("No following IO expected")
        }
        return DesktopVideoShareBindings(f.operations, following, { _, _ -> error("No friend send expected") }, files,
            { 123L }, f::sourceOwned, copy, {}, system,
            { _, _, _, _, _ -> error("No native media expected") }, { _, _ -> error("Guarded chooser is required") },
            { true }, { 1024 }, { 768 }, guardedChooseSave = guardedSave)
            .forPresentation(f::sourceOwned, f::sourceAdmit, f::sourceOwned).whilePresented { f.popup }
    }

    @Test fun retainedShareActuallyCompletesOriginalGetThenPostWhileSameOwnerIsHidden(): Unit = runBlocking {
        withTimeout(5_000) { Fixture().use { f ->
            val folder = Files.createTempDirectory("retained-share-")
            try {
                val bindings = retainedBindings(f, DesktopVideoShareFiles(folder, { error("No cover IO") }, f::sourceOwned, f::sourceAdmit))
                val original = f.reply
                f.reply = { request -> original(request).also { if (request.method == "GET") f.popup = false } }
                assertEquals("ok", bindings.shareToDynamic(f.subject.bvid, "draft across minimize").getOrThrow())
                assertEquals(listOf("GET", "POST"), f.requests.map { it.request.method })
                assertFalse(bindings.isPresented()); assertTrue(bindings.isOwned()); assertEquals(123L, bindings.currentMid())
                var localFinishes = 0
                assertTrue(bindings.withAdmission { localFinishes++ })
                f.popup = true
                assertTrue(bindings.isPresented()); assertEquals(1, localFinishes)
                assertTrue(f.posts().single().body.contains("draft across minimize"))
                f.accepted = DesktopOriginalVideoAcceptedPublication(f.source.request, f.source.nativeSource)
                assertFalse(bindings.isOwned())
                assertFalse(bindings.withAdmission { error("Retired draft may not publish") })
                assertFailsWith<CancellationException> { bindings.shareToDynamic(f.subject.bvid, "old draft") }
                assertEquals(1, f.posts().size)
            } finally { folder.toFile().deleteRecursively() }
        } }
    }

    @Test fun hiddenSourceLeaseCannotLaunchClipboardSystemOrSaveChooser(): Unit = runBlocking {
        withTimeout(5_000) { Fixture().use { f ->
            val folder = Files.createTempDirectory("retained-share-effects-")
            try {
                var effects = 0
                val bindings = retainedBindings(f, DesktopVideoShareFiles(folder, { error("No cover IO") }, f::sourceOwned, f::sourceAdmit),
                    copy = { effects++ }, system = DesktopTextShareBindings { _, _, _ -> effects++; true },
                    guardedSave = { _, _, _ -> effects++; null })
                val payload = com.android.purebilibili.feature.video.share.buildVideoSharePayload("Title", f.subject.bvid, "", "Owner", "0")
                f.popup = false
                assertTrue(bindings.isOwned()); assertFalse(bindings.isPresented())
                assertFailsWith<CancellationException> { bindings.copyText(payload.url) }
                assertFailsWith<CancellationException> { bindings.performTarget(com.android.purebilibili.feature.video.share.VideoShareTarget.SYSTEM_SHARE, payload, null) }
                val card = com.android.purebilibili.feature.video.share.VideoShareCoverFile(folder.resolve("unused.jpg"), "image/jpeg")
                assertFailsWith<CancellationException> { bindings.performTarget(com.android.purebilibili.feature.video.share.VideoShareTarget.SAVE_CARD, payload, card) }
                assertEquals(0, effects); assertTrue(f.requests.isEmpty())
            } finally { folder.toFile().deleteRecursively() }
        } }
    }

    @Test fun foregroundChosenSaveCanFinishOriginalOwnedFileCommitAfterHide(): Unit = runBlocking {
        withTimeout(5_000) { Fixture().use { f ->
            val folder = Files.createTempDirectory("retained-share-save-")
            val destinationFolder = Files.createTempDirectory("retained-share-destination-")
            try {
                val files = DesktopVideoShareFiles(folder, { error("No cover IO") }, f::sourceOwned, f::sourceAdmit)
                val card = files.publish("BiliPai_share_fixture.jpg", "image/jpeg") { java.nio.file.Files.write(it, byteArrayOf(1, 2, 3)) }
                val destination = destinationFolder.resolve("chosen.jpg")
                var chooserCalls = 0
                val bindings = retainedBindings(f, files, guardedSave = { _, _, owned ->
                    assertTrue(owned()); chooserCalls++; f.popup = false; destination
                })
                val payload = com.android.purebilibili.feature.video.share.buildVideoSharePayload("Title", f.subject.bvid, "", "Owner", "0")
                bindings.performTarget(com.android.purebilibili.feature.video.share.VideoShareTarget.SAVE_CARD, payload, card)
                assertEquals(1, chooserCalls); assertFalse(bindings.isPresented())
                assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(destination))
                assertFalse(Files.exists(card.path)); assertTrue(f.requests.isEmpty())
            } finally { folder.toFile().deleteRecursively(); destinationFolder.toFile().deleteRecursively() }
        } }
    }

    @Test fun hiddenBeforeActualSystemShowCannotPromoteSourceOnlyHandoff(): Unit = runBlocking {
        withTimeout(5_000) { Fixture().use { f ->
            val folder = Files.createTempDirectory("retained-share-native-")
            try {
                var observedNativeOwner: (() -> Boolean)? = null
                val bindings = retainedBindings(f, DesktopVideoShareFiles(folder, { error("No cover IO") }, f::sourceOwned, f::sourceAdmit),
                    system = DesktopTextShareBindings { _, _, owned ->
                        assertTrue(owned()); observedNativeOwner = owned; f.popup = false
                        assertFalse(owned()); false
                    })
                val payload = com.android.purebilibili.feature.video.share.buildVideoSharePayload("Title", f.subject.bvid, "", "Owner", "0")
                bindings.performTarget(com.android.purebilibili.feature.video.share.VideoShareTarget.SYSTEM_SHARE, payload, null)
                assertTrue(bindings.isOwned()); assertFalse(assertNotNull(observedNativeOwner).invoke())
                f.popup = true
                assertFalse(assertNotNull(observedNativeOwner).invoke(), "Failed show cannot revive after restore")
            } finally { folder.toFile().deleteRecursively() }
        } }
    }

    @Test fun actualSaveSelectorRejectsAtEdtBeforeConstructingAnyNativeChooser(): Unit = runBlocking {
        assertTrue(java.awt.GraphicsEnvironment.isHeadless())
        val checks = java.util.concurrent.atomic.AtomicInteger()
        withTimeout(5_000) {
            assertNull(selectDynamicSaveTarget("owned.jpg", "image/jpeg", stillOwned = { checks.incrementAndGet() == 1 }))
        }
        assertEquals(2, checks.get(), "IO preflight passed, exact EDT launch was retired")
    }

    @Test fun hiddenRetainedBindingRejectsActualAccountEpochReplacement(): Unit = runBlocking {
        withTimeout(5_000) { Fixture().use { f ->
            val folder = Files.createTempDirectory("retained-share-account-")
            try {
                val bindings = retainedBindings(f, DesktopVideoShareFiles(folder, { error("No cover IO") }, f::sourceOwned, f::sourceAdmit))
                f.popup = false; assertTrue(bindings.isOwned()); assertEquals(123L, bindings.currentMid())
                f.sessions.logout()
                assertFalse(bindings.isOwned())
                assertFailsWith<CancellationException> { bindings.currentMid() }
                assertFailsWith<CancellationException> { bindings.shareToDynamic(f.subject.bvid, "retired") }
                assertFalse(bindings.withAdmission { error("Retired cleanup may not publish") })
                assertTrue(f.requests.isEmpty())
            } finally { folder.toFile().deleteRecursively() }
        } }
    }

}
