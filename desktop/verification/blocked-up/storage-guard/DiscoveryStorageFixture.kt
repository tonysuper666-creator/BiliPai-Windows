package com.bilipai.desktop.ui

import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.*
import com.android.purebilibili.core.store.TodayWatchFeedbackSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.nio.file.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.*
import kotlin.test.*

private fun root()=Files.createTempDirectory("discovery-storage-fixture-")
private fun legacy(root:Path,mid:Long?=null)=
    (if(mid==null)root else root.resolve("accounts/$mid")).resolve("discovery/plugin-settings.json")
private fun write(path:Path,value:String){Files.createDirectories(path.parent);Files.writeString(path,value)}
private fun blocked(root:Path)=DesktopBlockedUpStore(DesktopPluginContext(DesktopPluginStore(root)))
private fun repository()=DesktopRepository(DesktopSessionStore.temporary())
private const val VALID_GUEST="""{"feed_api":{"type":0,"home_refresh_count":14},"blocked_ups":{"mids":"[41]"},"future_namespace":{"keep":{"原字段":true}}}"""
private const val VALID_FEEDBACK="""{"today_watch_feedback":{"feedback_payload_v1":"{\"dislikedBvids\":[\"BVfixtureOriginal\"],\"dislikedKeywords\":[\"  保留  \"]}"},"future_namespace":{"keep":"原资料"}}"""

class DiscoveryStorageFixture {
    @Test fun guestConstructorFailureIsRealAndPreservesBadBytes():Unit=runBlocking {
        val root=root();val bad=legacy(root);write(bad,"private fixture invalid JSON / token=NOT_AN_ACCOUNT")
        val bytes=Files.readAllBytes(bad);val authority=blocked(root)
        assertTrue(authority.migrateLegacyDiscoveryMids().isFailure)
        assertNotNull(authority.migrationError.value)
        assertFails{DesktopDiscoveryPreferences(root,authority)}
        var factories=0
        val guard=DesktopDiscoveryStorageGuard({factories++;openDesktopDiscoveryStorage(repository()){DesktopDiscoveryPreferences(root,authority)}})
        assertFalse(guard.load());assertEquals(1,factories)
        val failure=assertIs<DesktopDiscoveryStorageFailure>(guard.result.value?.exceptionOrNull())
        assertTrue(failure.failedConstructor);assertFalse(failure.accountFeedback);assertTrue(guard.canRetry)
        assertFalse(failure.message.orEmpty().contains(root.toString()))
        assertFalse(failure.message.orEmpty().contains("NOT_AN_ACCOUNT"))
        assertContentEquals(bytes,Files.readAllBytes(bad))
        assertTrue(DesktopPluginStore(root).preferences("blocked_ups").isEmpty())
        guard.close()
    }

    @Test fun actualGuestRepairRetriesFactoryAndKeepsOneSuccessfulRepository():Unit=runBlocking {
        val root=root();val file=legacy(root);write(file,"not JSON");val authority=blocked(root);val repo=repository()
        var factories=0
        val guard=DesktopDiscoveryStorageGuard({factories++;openDesktopDiscoveryStorage(repo){DesktopDiscoveryPreferences(root,authority)}})
        assertFalse(guard.load());assertTrue(guard.canRetry)
        write(file,VALID_GUEST);val repaired=Files.readAllBytes(file)
        assertTrue(guard.load());val loaded=assertNotNull(guard.result.value?.getOrNull())
        assertEquals(14,loaded.refreshCount.value);assertSame(authority,loaded.blockedUps)
        assertEquals(setOf(41L),authority.mids.value);assertNull(authority.migrationError.value)
        assertContentEquals(repaired,Files.readAllBytes(file));assertFalse(guard.canRetry)
        write(file,VALID_GUEST.replace("14","39"))
        assertTrue(guard.load());assertSame(loaded,guard.result.value?.getOrNull());assertEquals(2,factories)
        assertEquals(14,loaded.refreshCount.value) // No disk hot reload after a successful original backing.
        assertContains(Files.readString(file),"39");guard.close()
    }

    @Test fun corruptActiveFeedbackDoesNotDestroyValidGuestRepositoryAndRepairIsReal():Unit=runBlocking {
        val root=root();write(legacy(root),VALID_GUEST);val file=legacy(root,123);write(file,"NOT JSON fixture feedback")
        val original=Files.readAllBytes(file);val authority=blocked(root)
        val discovery=openDesktopDiscoveryStorage(repository()){DesktopDiscoveryPreferences(root,authority)}
        assertEquals(14,discovery.refreshCount.value);assertFails{discovery.feedback(123)}
        val guard=DesktopDiscoveryStorageGuard({openDesktopDiscoveryFeedback(discovery,123)})
        assertFalse(guard.load());val failure=assertIs<DesktopDiscoveryStorageFailure>(guard.result.value?.exceptionOrNull())
        assertTrue(failure.failedConstructor&&failure.accountFeedback&&guard.canRetry)
        assertContentEquals(original,Files.readAllBytes(file));assertEquals(14,discovery.refreshCount.value)
        write(file,VALID_FEEDBACK);val repaired=Files.readAllBytes(file)
        assertTrue(guard.load());val actual=assertNotNull(guard.result.value?.getOrNull()).value
        assertEquals(setOf("BVfixtureOriginal"),actual.dislikedBvids);assertEquals(setOf("保留"),actual.dislikedKeywords)
        assertContentEquals(repaired,Files.readAllBytes(file));assertSame(authority,discovery.blockedUps)
        assertTrue(discovery.blockedUps.migrationError.value.orEmpty().contains("修复旧文件后重试"))
        // Account repair did not silently retry the independently failed global migration.
        guard.close()
    }

    @Test fun initializedBackingRepairRequiresRestartAndIsNotFreshFacadeReload():Unit=runBlocking {
        val root=root();val corrupt="""{"blocked_ups":{"records":"malformed records"},"future_namespace":{"keep":"original"}}"""
        write(root.resolve("plugin-settings.json"),corrupt);val context=DesktopPluginContext(DesktopPluginStore(root))
        val authority=DesktopBlockedUpStore(context)
        assertTrue(authority.migrateLegacyDiscoveryMids().isFailure)
        assertContains(authority.migrationError.value.orEmpty(),"重新启动应用")
        write(root.resolve("plugin-settings.json"),"""{"blocked_ups":{"records":"[]","legacy_discovery_migration_version":1}}""")
        val repaired=Files.readAllBytes(root.resolve("plugin-settings.json"))
        assertTrue(authority.migrateLegacyDiscoveryMids().isFailure)
        assertTrue(blocked(root).migrateLegacyDiscoveryMids().isFailure)
        assertContentEquals(repaired,Files.readAllBytes(root.resolve("plugin-settings.json")))
        // This is a terminal lifecycle action, never a UI retry workaround.
        context.store.freezeWrites();assertFails{context.store.update("future_namespace",mapOf("late" to JsonPrimitive(true)))}
    }

    @Test fun embeddedFeedbackPayloadFallbackIsOriginalAndDoesNotRewriteFile():Unit=runBlocking {
        val root=root();val file=legacy(root,123)
        write(file,"""{"today_watch_feedback":{"feedback_payload_v1":"malformed embedded payload"},"future_namespace":{"keep":"original"}}""")
        val bytes=Files.readAllBytes(file);val prefs=DesktopDiscoveryPreferences(root,blocked(root))
        val discovery=DesktopDiscoveryRepository(repository(),prefs)
        val guard=DesktopDiscoveryStorageGuard({openDesktopDiscoveryFeedback(discovery,123)})
        assertTrue(guard.load());assertEquals(TodayWatchFeedbackSnapshot(),guard.result.value!!.getOrThrow().value)
        assertContentEquals(bytes,Files.readAllBytes(file));guard.close()
    }

    @Test fun epochRetirementRejectsActualLateFactoryResult():Unit=runBlocking {
        val root=root();val prefs=DesktopDiscoveryPreferences(root,blocked(root));val repo=repository()
        val epoch=AtomicLong(1);val entered=CountDownLatch(1);val release=CountDownLatch(1)
        var factoryCalls=0
        val guard=DesktopDiscoveryStorageGuard({factoryCalls++;val real=DesktopDiscoveryRepository(repo,prefs)
            entered.countDown();check(release.await(3,TimeUnit.SECONDS));real},{epoch.get()},{epoch.get()==1L})
        val pending=async{guard.load()};withContext(Dispatchers.IO){assertTrue(entered.await(3,TimeUnit.SECONDS))}
        epoch.set(2);release.countDown();assertFalse(pending.await());assertNull(guard.result.value)
        assertFalse(guard.isActive);assertFalse(guard.canRetry);assertFalse(guard.load());assertEquals(1,factoryCalls)
        guard.close()
    }

    @Test fun replacementPendingCannotBeClearedByRetiredFinally():Unit=runBlocking {
        val epoch=AtomicLong(1);val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val calls=AtomicInteger()
        val guard=DesktopDiscoveryStorageGuard({if(calls.incrementAndGet()==1){entered.countDown();check(release.await(3,TimeUnit.SECONDS));"old"}else "new"},{epoch.get()})
        val old=async{guard.load()};withContext(Dispatchers.IO){assertTrue(entered.await(3,TimeUnit.SECONDS))}
        epoch.set(2);assertTrue(guard.load());assertEquals("new",guard.result.value!!.getOrThrow())
        release.countDown();assertFalse(old.await());assertEquals("new",guard.result.value!!.getOrThrow());assertFalse(guard.canRetry)
        guard.close()
    }

    @Test fun closedGuardDoesNotReopenOrReviveFrozenOriginalStore():Unit=runBlocking {
        val root=root();val file=legacy(root);write(file,"not JSON");val context=DesktopPluginContext(DesktopPluginStore(root))
        val authority=DesktopBlockedUpStore(context);var calls=0;var owns=true
        val guard=DesktopDiscoveryStorageGuard({calls++;openDesktopDiscoveryStorage(repository()){DesktopDiscoveryPreferences(root,authority)}},stillOwned={owns})
        assertFalse(guard.load());assertTrue(guard.canRetry);guard.close();owns=false;context.store.freezeWrites()
        write(file,VALID_GUEST);val repaired=Files.readAllBytes(file)
        assertFalse(guard.load());assertEquals(1,calls);assertFalse(guard.canRetry);assertFalse(guard.isActive)
        assertFails{context.store.update("fixture",mapOf("late" to JsonPrimitive(true)))}
        assertContentEquals(repaired,Files.readAllBytes(file))
    }

    @Test fun closeRejectsActualInFlightResult():Unit=runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);var calls=0
        val root=root();val prefs=DesktopDiscoveryPreferences(root,blocked(root));val repo=repository()
        val guard=DesktopDiscoveryStorageGuard({calls++;val real=DesktopDiscoveryRepository(repo,prefs)
            entered.countDown();check(release.await(3,TimeUnit.SECONDS));real})
        val pending=async{guard.load()};withContext(Dispatchers.IO){assertTrue(entered.await(3,TimeUnit.SECONDS))}
        guard.close();release.countDown();assertFalse(pending.await());assertNull(guard.result.value);assertFalse(guard.load());assertEquals(1,calls)
    }

    @Test fun cancellationIsNotPresentedAsDiskError():Unit=runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val guard=DesktopDiscoveryStorageGuard({entered.countDown();check(release.await(3,TimeUnit.SECONDS));"not published"})
        val pending=async{guard.load()};withContext(Dispatchers.IO){assertTrue(entered.await(3,TimeUnit.SECONDS))}
        pending.cancel();release.countDown();assertFailsWith<CancellationException>{pending.await()}
        assertNull(guard.result.value);assertFalse(guard.canRetry);guard.close()
    }

    @Test fun explicitHeldDecodeFailureOnlyAllowsActualRestart():Unit=runBlocking {
        // Deliberately injected decode failure verifies transient guard UI classification, not original schema corruption.
        var calls=0
        val guard=DesktopDiscoveryStorageGuard<String>({calls++;throw DesktopDiscoveryStorageFailure(false,true,IllegalStateException("DO NOT DISPLAY / fixture secret"))})
        assertFalse(guard.load());assertFalse(guard.canRetry);assertFalse(guard.load());assertEquals(1,calls)
        assertContains(guard.result.value?.exceptionOrNull()?.message.orEmpty(),"重新启动")
        assertFalse(guard.result.value?.exceptionOrNull()?.message.orEmpty().contains("fixture secret"));guard.close()
    }
}

fun main(args:Array<String>) {
    val fixture=DiscoveryStorageFixture()
    val checks=listOf<Pair<String,()->Unit>>(
        "real guest constructor failure preserves original bad bytes" to fixture::guestConstructorFailureIsRealAndPreservesBadBytes,
        "actual repair retries factory once and successful repository stays retained" to fixture::actualGuestRepairRetriesFactoryAndKeepsOneSuccessfulRepository,
        "real account feedback failure and repair preserve guest and same global store" to fixture::corruptActiveFeedbackDoesNotDestroyValidGuestRepositoryAndRepairIsReal,
        "cached destination cannot reload or revive a frozen store" to fixture::initializedBackingRepairRequiresRestartAndIsNotFreshFacadeReload,
        "original embedded payload fallback preserved, not claimed fixed" to fixture::embeddedFeedbackPayloadFallbackIsOriginalAndDoesNotRewriteFile,
        "epoch rejects real late factory result" to fixture::epochRetirementRejectsActualLateFactoryResult,
        "replacement result survives retired finally" to fixture::replacementPendingCannotBeClearedByRetiredFinally,
        "closed guard cannot create a new original backing" to fixture::closedGuardDoesNotReopenOrReviveFrozenOriginalStore,
        "close rejects real in-flight repository" to fixture::closeRejectsActualInFlightResult,
        "cancellation stays cancellation" to fixture::cancellationIsNotPresentedAsDiskError,
        "injected held decode failure is restart-only" to fixture::explicitHeldDecodeFailureOnlyAllowsActualRestart,
    )
    checks.forEach{(label,test)->test();println("PASS: $label")}
    val output=Path.of(args[0]);Files.createDirectories(output.parent)
    Files.writeString(output,buildJsonObject{put("passed",true);put("executableChecks",checks.size)
        put("checks",JsonArray(checks.map{JsonPrimitive(it.first)}));put("sharedGradle",false);put("HWND",false)
        put("accountFiles",false);put("HTTP",false);put("originalEmbeddedPayloadFallbackRetained",true)
        put("markedStage1Overrides",true);put("restoreFenceClaimed",false)}.toString())
}
