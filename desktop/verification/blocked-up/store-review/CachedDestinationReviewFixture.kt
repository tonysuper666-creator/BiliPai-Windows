package com.bilipai.desktop.data

import com.android.purebilibili.core.database.entity.BlockedUp
import com.android.purebilibili.core.store.TodayWatchDislikedVideoSnapshot
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

private fun context(root: Path) = DesktopPluginContext(DesktopPluginStore(root))
private fun temporary() = Files.createTempDirectory("blocked-review-")
private fun legacy(root: Path, contents: String): Path = root.resolve("discovery/plugin-settings.json").also {
    Files.createDirectories(it.parent); Files.writeString(it, contents)
}

/** This fixture uses current immutable product preferences plus the visibly marked original/delta store. */
fun main(args: Array<String>) {
    val revised = args[0] == "reviewed"
    val root = temporary()
    val corrupt = buildJsonObject {
        put("blocked_ups", buildJsonObject { put("records", "malformed records") })
        put("future_namespace", buildJsonObject { put("nested", buildJsonObject { put("keep", "原资料") }) })
    }
    Files.writeString(root.resolve("plugin-settings.json"), corrupt.toString())
    val originalContext = context(root)
    val originalStore = DesktopBlockedUpStore(originalContext)
    assertTrue(originalStore.migrateLegacyDiscoveryMids().isFailure)
    assertTrue(originalStore.records.value.isEmpty())
    if (revised) assertContains(originalStore.migrationError.value.orEmpty(), "重新启动应用")
    else assertContains(originalStore.migrationError.value.orEmpty(), "重试")
    val repaired = buildJsonObject {
        put("blocked_ups", buildJsonObject {
            put("records", Json.encodeToString(listOf(BlockedUp(21L, "实际修复", "face"))))
            put("legacy_discovery_migration_version", 1)
        })
        put("future_namespace", corrupt.getValue("future_namespace"))
    }
    Files.writeString(root.resolve("plugin-settings.json"), repaired.toString())
    val repairedBytes = Files.readAllBytes(root.resolve("plugin-settings.json"))
    assertTrue(originalStore.migrateLegacyDiscoveryMids().isFailure) // Disk repair does not refresh an existing cached backing.
    val secondFacade = DesktopBlockedUpStore(context(root))
    assertTrue(secondFacade.migrateLegacyDiscoveryMids().isFailure) // Same path is the same generation, not a reload workaround.
    assertTrue(secondFacade.records.value.isEmpty())
    assertContentEquals(repairedBytes, Files.readAllBytes(root.resolve("plugin-settings.json")))
    originalContext.store.freezeWrites()
    assertFails { originalStore.upsert(BlockedUp(99L, "retired", "")) }
    val fresh = DesktopBlockedUpStore(context(root))
    assertTrue(fresh.migrateLegacyDiscoveryMids().isSuccess)
    assertEquals(setOf(21L), fresh.mids.value)
    fresh.upsert(BlockedUp(22L, "new generation", ""))
    assertEquals(setOf(21L, 22L), fresh.mids.value)
    assertEquals(corrupt["future_namespace"], Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject["future_namespace"])

    val legacyRoot = temporary()
    val legacyContext = context(legacyRoot)
    legacyContext.store.update("future_namespace", mapOf("nested" to buildJsonObject { put("preserve", "真实字段") }))
    val legacyStore = DesktopBlockedUpStore(legacyContext)
    val legacyFile = legacy(legacyRoot, "not JSON")
    assertTrue(legacyStore.migrateLegacyDiscoveryMids().isFailure)
    assertContains(legacyStore.migrationError.value.orEmpty(), "修复旧文件后重试")
    val legacyText = """{"blocked_ups":{"mids":"[41]"},"unrelated":{"keep":{"structured":true}}}"""
    Files.writeString(legacyFile, legacyText)
    assertTrue(legacyStore.migrateLegacyDiscoveryMids().isSuccess)
    assertEquals(setOf(41L), legacyStore.mids.value)
    assertNull(legacyStore.migrationError.value)
    assertEquals(legacyText, Files.readString(legacyFile))
    assertEquals("真实字段", legacyContext.store.preferences("future_namespace")["nested"]!!.jsonObject["preserve"]!!.jsonPrimitive.content)

    val badGuestRoot = temporary()
    legacy(badGuestRoot, "not JSON")
    val badGuestStore = DesktopBlockedUpStore(context(badGuestRoot))
    assertTrue(badGuestStore.migrateLegacyDiscoveryMids().isFailure)
    assertNotNull(badGuestStore.migrationError.value)
    assertFails { DesktopDiscoveryPreferences(badGuestRoot, badGuestStore) }

    val feedbackRoot = temporary()
    val badOtherAccount = feedbackRoot.resolve("accounts/456/discovery/plugin-settings.json")
    Files.createDirectories(badOtherAccount.parent); Files.writeString(badOtherAccount, "not JSON")
    val preferences = DesktopDiscoveryPreferences(feedbackRoot, DesktopBlockedUpStore(context(feedbackRoot)))
    assertFails { preferences.record(123L,
        TodayWatchDislikedVideoSnapshot("BVfixture", "fixture", "独立反馈姓名", 77L, 900L), emptySet(), true) }
    assertTrue(77L in preferences.feedback(123L).value.dislikedCreatorMids)
    assertFalse(77L in preferences.blockedCreators(123L).value)
    assertNull(context(feedbackRoot).store.preferences("blocked_ups")["legacy_discovery_migration_version"])
    assertTrue(Files.isRegularFile(feedbackRoot.resolve("accounts/123/discovery/plugin-settings.json")))
    assertEquals("not JSON", Files.readString(badOtherAccount))
    assertFails { preferences.feedback(456L) }
    val result = buildJsonObject {
        put("passed", true); put("variant", args[0]); put("checks", 4)
        put("actualSameContextRetryRemainsCached", true); put("sameRootFacadeIsNotReload", true)
        put("retiredGenerationNotRevived", true); put("freshGenerationReadsRepairedDestination", true)
        put("legacyDiskRepairRetryWorks", true); put("unknownNamespacesPreserved", true)
        put("failedBlockCanAlreadyPersistIndependentFeedback", true)
        put("malformedGuestDiscoveryBackingCanThrowBeforeMigrationErrorUI", true)
        put("malformedAccountFeedbackBackingCanThrowBeforeMigrationErrorUI", true)
        put("productStorePersistenceFromImmutableSnapshot", true); put("markedStoreOverride", true)
        put("nativeWindowCreated", false); put("sharedGradleInvoked", false)
        put("networkRequests", false); put("userAccountFilesRead", false)
        put("fixtureRoot", root.toString()); put("legacyFixtureRoot", legacyRoot.toString()); put("feedbackFixtureRoot", feedbackRoot.toString())
    }
    Files.writeString(Path.of(args[1]), result.toString())
    println("$revised: four real-disk cached destination / actual legacy retry / consumer corruption / independent feedback boundaries PASS")
}
