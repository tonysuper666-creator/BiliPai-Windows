package com.bilipai.desktop.data

import com.android.purebilibili.core.database.entity.BlockedUp
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

fun main(args: Array<String>) {
    val strict = args[0] == "strict"
    for (malformed in listOf<JsonElement>(JsonPrimitive("retained malformed namespace"), JsonArray(listOf(JsonPrimitive(71))), JsonNull)) {
        val root = Files.createTempDirectory("strict-blocked-")
        val document = buildJsonObject {
            put("blocked_ups", malformed)
            put("future_namespace", buildJsonObject { put("keep", "existing user data") })
        }
        val file = root.resolve("plugin-settings.json")
        Files.writeString(file, document.toString())
        val original = Files.readAllBytes(file)
        val store = DesktopBlockedUpStore(DesktopPluginContext(DesktopPluginStore(root)))
        val migration = store.migrateLegacyDiscoveryMids()
        if (strict) {
            assertTrue(migration.isFailure)
            assertContains(store.migrationError.value.orEmpty(), "重新启动应用")
            assertTrue(store.records.value.isEmpty())
            assertFails { store.upsert(BlockedUp(99, "must not be accepted", "")) }
            assertContentEquals(original, Files.readAllBytes(file))
        } else {
            assertTrue(migration.isSuccess) // Baseline silently masks this namespace as absent.
            assertNotEquals(document["blocked_ups"], Json.parseToJsonElement(Files.readString(file)).jsonObject["blocked_ups"])
        }
        assertEquals(document["future_namespace"], Json.parseToJsonElement(Files.readString(file)).jsonObject["future_namespace"])
    }
    val root = Files.createTempDirectory("strict-blocked-valid-")
    val context = DesktopPluginContext(DesktopPluginStore(root))
    val store = DesktopBlockedUpStore(context)
    assertTrue(store.migrateLegacyDiscoveryMids().isSuccess) // A genuinely absent namespace is allowed.
    store.upsert(BlockedUp(41, "valid", ""))
    assertEquals(setOf(41L), store.mids.value)
    val frozenBytes = Files.readAllBytes(root.resolve("plugin-settings.json"))
    context.store.freezeWrites()
    assertFails { store.upsert(BlockedUp(42, "retired", "")) }
    assertContentEquals(frozenBytes, Files.readAllBytes(root.resolve("plugin-settings.json")))
    val fresh = DesktopBlockedUpStore(DesktopPluginContext(DesktopPluginStore(root)))
    assertTrue(fresh.migrateLegacyDiscoveryMids().isSuccess)
    fresh.upsert(BlockedUp(43, "fresh", ""))
    assertEquals(setOf(41L, 43L), fresh.mids.value)
    val result = buildJsonObject {
        put("passed", true); put("variant", args[0]); put("uniqueChecks", 5)
        put("primitiveNamespaceChecked", true); put("arrayNamespaceChecked", true); put("nullNamespaceChecked", true)
        put("absentNamespaceRemainsUsable", true); put("frozenOldAndFreshNewGenerationChecked", true)
        put("originalMalformedNamespaceWouldBeOverwritten", !strict)
        put("strictMalformedNamespaceBytesPreserved", strict)
        put("noDiskHotReload", true); put("nativeWindowCreated", false); put("networkRequests", false)
    }
    Files.writeString(Path.of(args[1]), result.toString())
    println("$strict: cached primitive/array/null, genuine absence, old/fresh generation 5 boundary cases PASS")
}
