package com.bilipai.desktop.ui

import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Real temporary DesktopSessionStore/Repository, same MID and changed fixture credential; no HTTP. */
fun main(args:Array<String>):Unit=runBlocking {
    val root=Files.createTempDirectory("same-mid-discovery-")
    val sessions=DesktopSessionStore(root.resolve("fixture-session.json"));val account=AccountSummary(42,"fixture","")
    sessions.saveAccount(mapOf("SESSDATA" to "fixture-first-not-real"),account)
    val repository=DesktopRepository(sessions)
    val authority=DesktopBlockedUpStore(DesktopPluginContext(DesktopPluginStore(root)))
    val discovery=openDesktopDiscoveryStorage(repository){DesktopDiscoveryPreferences(root,authority)}
    val feedback=root.resolve("accounts/42/discovery/plugin-settings.json");Files.createDirectories(feedback.parent)
    Files.writeString(feedback,"""{"today_watch_feedback":{"feedback_payload_v1":"{\"dislikedBvids\":[\"BVfixtureOriginal\"]}"},"future_namespace":{"keep":"原资料"}}""")
    val before=Files.readAllBytes(feedback)
    val epochCollected=repository.sessionEpoch
    // This is an immutable value. Never use a delegated 'val capturedEpoch by State' inside stillOwned.
    val capturedEpoch=epochCollected
    val capturedMid=repository.account.value?.mid
    val entered=CountDownLatch(1);val release=CountDownLatch(1);var oldCalls=0
    val guard=DesktopDiscoveryStorageGuard({oldCalls++;val source=openDesktopDiscoveryFeedback(discovery,capturedMid)
        entered.countDown();check(release.await(3,TimeUnit.SECONDS));source},
        sessionEpoch={repository.sessionEpoch},stillOwned={repository.sessionEpoch==capturedEpoch&&repository.account.value?.mid==capturedMid})
    val old=async{guard.load()}
    withContext(Dispatchers.IO){assertTrue(entered.await(3,TimeUnit.SECONDS))}
    sessions.saveAccount(mapOf("SESSDATA" to "fixture-second-not-real"),account)
    assertEquals(account,repository.account.value);assertEquals(capturedMid,repository.account.value?.mid)
    assertEquals(capturedEpoch+1,repository.sessionEpoch)
    release.countDown();assertFalse(old.await());assertFalse(guard.isActive);assertNull(guard.result.value)
    assertFalse(guard.load());assertEquals(1,oldCalls)
    val nextCapturedEpoch=repository.sessionEpoch
    val next=DesktopDiscoveryStorageGuard({openDesktopDiscoveryFeedback(discovery,capturedMid)},
        sessionEpoch={repository.sessionEpoch},stillOwned={repository.sessionEpoch==nextCapturedEpoch&&repository.account.value?.mid==capturedMid})
    assertTrue(next.load());assertTrue(next.isActive)
    assertEquals(setOf("BVfixtureOriginal"),next.result.value!!.getOrThrow().value.dislikedBvids)
    assertContentEquals(before,Files.readAllBytes(feedback));assertSame(authority,discovery.blockedUps)
    guard.close();next.close();assertNull(next.result.value);assertFalse(next.isActive)
    val output=Path.of(args[0]);Files.createDirectories(output.parent)
    Files.writeString(output,buildJsonObject{put("passed",true);put("realTemporarySessionStore",true);put("sameMidCredentialEpochIncrement",true)
        put("capturedEpochImmutableValue",true);put("actualLateFeedbackRejected",true);put("newOwnerUsesOriginalFeedback",true)
        put("feedbackBytesUnchanged",true);put("HWND",false);put("HTTP",false);put("realAccountData",false)}.toString())
    println("PASS: real temp session same-MID credential epoch change rejects old feedback, next owner keeps actual original data")
}
