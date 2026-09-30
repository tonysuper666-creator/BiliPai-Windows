package com.bilipai.desktop.data

import com.android.purebilibili.core.database.entity.BlockedUp
import com.android.purebilibili.core.store.TodayWatchDislikedVideoSnapshot
import com.android.purebilibili.data.repository.*
import com.bilipai.desktop.backup.DesktopBackupArchive
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DesktopBlockedUpStoreTest {
    private fun context(root: Path) = DesktopPluginContext(DesktopPluginStore(root))
    private fun root() = Files.createTempDirectory("bp-blocked-").also {
        Files.writeString(it.resolve("fixture-owner.json"), "{\"owner\":\"settings-blocked-up\"}")
    }
    private fun legacy(root: Path, path: String, ids: String): Path {
        val target = root.resolve(path);Files.createDirectories(target.parent)
        Files.writeString(target, buildJsonObject { put("blocked_ups", buildJsonObject { put("mids", ids) }) }.toString())
        return target
    }
    private fun marker(context: DesktopPluginContext) = context.store.preferences("blocked_ups")["legacy_discovery_migration_version"]
    private fun seedExisting(context: DesktopPluginContext, vararg rows: BlockedUp) {
        context.store.update("blocked_ups",mapOf("records" to JsonPrimitive(Json.encodeToString(rows.toList()))))
    }

    @Test fun `whitelisted legacy scopes migrate atomically with full metadata and original invalid UID policy`(): Unit {
        val root = root();val context = context(root);val store = DesktopBlockedUpStore(context) { 900L }
        val full = BlockedUp(7, "完整资料", "face", 123, level = 6, sign = "签名", vipLabel = "VIP", officialTitle = "认证",
            follower = 777, archiveCount = 33, isDeleted = true, lastSyncedAt = 456)
        seedExisting(context,full)
        context.store.update("settings", mapOf("untouched" to JsonPrimitive("theme")))
        val guest = legacy(root, "discovery/plugin-settings.json", "[11,0,-2,11]")
        val account = legacy(root, "accounts/123/discovery/plugin-settings.json", "[7,22]")
        val ignored = root.resolve("accounts/123/account.json");Files.writeString(ignored, "not credential JSON and must never be read")
        val ignoredOther = root.resolve("accounts/not-mid/discovery/plugin-settings.json")
        Files.createDirectories(ignoredOther.parent);Files.writeString(ignoredOther, "not parsed")
        val before = listOf(guest,account,ignored,ignoredOther).associateWith(Files::readAllBytes)
        assertEquals(2, store.migrateLegacyDiscoveryMids().getOrThrow())
        assertEquals(setOf(7L,11L,22L),store.mids.value)
        assertEquals(full,store.records.value.single { it.mid == 7L })
        assertEquals("UP主11",store.records.value.single { it.mid == 11L }.name)
        assertNull(store.records.value.single { it.mid == 11L }.lastSyncedAt)
        assertEquals(1,marker(context)?.jsonPrimitive?.int)
        assertEquals("theme",context.store.preferences("settings")["untouched"]?.jsonPrimitive?.content)
        before.forEach { (path,bytes) -> assertContentEquals(bytes,Files.readAllBytes(path)) }
        val published = context.store.snapshot("blocked_ups").value
        assertNotNull(published[DesktopPreferenceKey("records") { it.jsonPrimitive.content }])
        assertNull(store.migrationError.value)
    }

    @Test fun `retired legacy MID cannot resurrect after unblock and a fresh disk generation`(): Unit {
        val root=root();val original=context(root);val store=DesktopBlockedUpStore(original)
        legacy(root,"discovery/plugin-settings.json","[81]")
        store.migrateLegacyDiscoveryMids().getOrThrow();store.remove(81)
        original.store.freezeWrites()
        val fresh=DesktopBlockedUpStore(context(root))
        assertEquals(0,fresh.migrateLegacyDiscoveryMids().getOrThrow())
        assertTrue(fresh.records.value.isEmpty())
    }

    @Test fun `corrupt scoped input never commits partial records or marker and repaired input can retry`(): Unit {
        val root=root();val context=context(root);val store=DesktopBlockedUpStore(context)
        seedExisting(context,BlockedUp(7,"existing",""))
        legacy(root,"discovery/plugin-settings.json","[11]")
        val broken=root.resolve("accounts/123/discovery/plugin-settings.json")
        Files.createDirectories(broken.parent);Files.writeString(broken,"not JSON")
        val destination=Files.readAllBytes(root.resolve("plugin-settings.json"))
        assertTrue(store.migrateLegacyDiscoveryMids().isFailure)
        assertEquals(setOf(7L),store.mids.value);assertNull(marker(context))
        assertNotNull(store.migrationError.value)
        assertFails { store.remove(7) } // Pending input cannot later resurrect an intentional unblock.
        assertFails { store.upsert(BlockedUp(88,"not accepted while migration failed","")) }
        assertContentEquals(destination,Files.readAllBytes(root.resolve("plugin-settings.json")))
        assertEquals("not JSON",Files.readString(broken))
        legacy(root,"accounts/123/discovery/plugin-settings.json","[22]")
        assertEquals(2,store.migrateLegacyDiscoveryMids().getOrThrow())
        assertEquals(setOf(7L,11L,22L),store.mids.value);assertNull(store.migrationError.value)
    }

    @Test fun `unknown source or destination schema is visible and cannot be marked successful`(): Unit {
        val root=root();val context=context(root);val store=DesktopBlockedUpStore(context)
        val malformed=legacy(root,"discovery/plugin-settings.json","[11]")
        Files.writeString(malformed,"""{"blocked_ups":{"mids":[11]}}""")
        assertTrue(store.migrateLegacyDiscoveryMids().isFailure);assertNull(marker(context))
        Files.writeString(malformed,"""{"blocked_ups":{"unknown_records":"[11]"}}""")
        assertTrue(store.migrateLegacyDiscoveryMids().isFailure);assertNull(marker(context))
        Files.writeString(malformed,"""{"mids":[11]}""")
        assertTrue(store.migrateLegacyDiscoveryMids().isFailure);assertNull(marker(context))
        legacy(root,"discovery/plugin-settings.json","[11]")
        context.store.update("blocked_ups",mapOf("legacy_discovery_migration_version" to JsonPrimitive(2)))
        assertTrue(store.migrateLegacyDiscoveryMids().isFailure)
        assertEquals(2,marker(context)?.jsonPrimitive?.int)
        assertTrue(store.records.value.isEmpty())
    }

    @Test fun `failed atomic persistence publishes neither a migrated marker nor updated records`(): Unit {
        val root=root();val context=context(root);val store=DesktopBlockedUpStore(context)
        seedExisting(context,BlockedUp(7,"existing",""));legacy(root,"discovery/plugin-settings.json","[11]")
        val file=root.resolve("plugin-settings.json");val saved=Files.readAllBytes(file)
        Files.delete(file);Files.createDirectory(file)
        val obstacle=file.resolve("task-owned-obstruction.txt");Files.writeString(obstacle,"fixture")
        try {
            assertTrue(store.migrateLegacyDiscoveryMids().isFailure)
            assertEquals(setOf(7L),store.mids.value);assertNull(marker(context))
            assertFails { store.upsert(BlockedUp(88,"failure","")) }
            assertEquals(setOf(7L),store.mids.value)
        }finally{Files.delete(obstacle);Files.delete(file);Files.write(file,saved)}
        store.migrateLegacyDiscoveryMids().getOrThrow()
        assertEquals(setOf(7L,11L),store.mids.value)
    }

    @Test fun `original share format preserves profiles while import deduplicates and keeps existing records`(): Unit {
        val original=BlockedUp(7,"中文 😀","face",55,6,"sign","VIP","official",555,123,true,999)
        val share=buildBlockedUpShareText(listOf(original))
        val parsed=parseBlockedUpShareText(share)
        assertEquals(1,parsed.size);assertEquals(original.name,parsed.single().name)
        assertEquals(original.isDeleted,parsed.single().isDeleted)
        assertEquals(original.follower,parsed.single().follower)
        val store=DesktopBlockedUpStore(context(root())) { 900 }
        val result=store.import(parsed+listOf(BlockedUpImportItem(7),BlockedUpImportItem(0),BlockedUpImportItem(8)))
        assertEquals(2,result.importedCount);assertEquals(2,result.failedCount)
        assertEquals("UP主8",store.records.value.single { it.mid==8L }.name)
        val second=store.import(listOf(BlockedUpImportItem(7,"replacement")))
        assertEquals(1,second.existingCount);assertEquals(original.name,store.records.value.single { it.mid==7L }.name)
        assertEquals(parsed,parseBlockedUpShareText(buildBlockedUpShareJson(listOf(original))))
        assertEquals(listOf(99L),parseBlockedUpShareText("UID: 99\n").map { it.mid })
    }

    @Test fun `different global facades cannot lose concurrent local writes or fork by account`(): Unit {
        val root=root();val first=DesktopBlockedUpStore(context(root));val second=DesktopBlockedUpStore(context(root))
        val threads=(1..12).map { id -> Thread { (if(id%2==0) first else second).upsert(BlockedUp(id.toLong(),"UP$id","")) }.also(Thread::start) }
        threads.forEach { it.join(3_000);assertFalse(it.isAlive) }
        assertEquals((1L..12L).toSet(),first.mids.value);assertEquals(first.records.value,second.records.value)
        val preferences=DesktopDiscoveryPreferences(root,first)
        assertSame(preferences.blockedCreators(null),preferences.blockedCreators(123))
        assertSame(preferences.blockedCreators(123),preferences.blockedCreators(456))
        preferences.record(123,TodayWatchDislikedVideoSnapshot("BV1","fixture","真实名称",777,900),emptySet(),true)
        assertEquals("真实名称",first.records.value.single { it.mid==777L }.name)
        assertTrue(777L in preferences.blockedCreators(456).value)
        preferences.changeBlocked(123,777,false)
        assertFalse(777L in preferences.blockedCreators(null).value)
    }

    @Test fun `actual archive restore fences a queued old IO writer and permits a new store generation`(): Unit {
        val root=root();val oldContext=context(root);val old=DesktopBlockedUpStore(oldContext)
        old.upsert(BlockedUp(7,"before restore",""))
        val incomingRoot=root();val incoming=DesktopBlockedUpStore(context(incomingRoot))
        incoming.upsert(BlockedUp(55,"restored",""))
        val bytes=DesktopBackupArchive(incomingRoot).create(123)
        val backingField=DesktopPluginStore::class.java.getDeclaredField("backing").apply { isAccessible=true }
        val backing=backingField.get(oldContext.store)
        val entered=CountDownLatch(1);var failure:Throwable?=null
        lateinit var queued:Thread
        synchronized(backing) {
            queued=Thread { entered.countDown();try { old.upsert(BlockedUp(99,"obsolete queued writer","")) }catch(caught:Throwable){failure=caught} }
            queued.start();assertTrue(entered.await(3,TimeUnit.SECONDS))
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
            while(queued.state!=Thread.State.BLOCKED && System.nanoTime()<deadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED,queued.state)
            DesktopBackupArchive(root) { oldContext.store.freezeWrites() }.restore(bytes)
        }
        queued.join(3_000);assertFalse(queued.isAlive);assertNotNull(failure)
        val fresh=DesktopBlockedUpStore(context(root))
        assertEquals(setOf(55L),fresh.mids.value)
        assertFails { old.remove(7) }
        fresh.upsert(BlockedUp(66,"new generation",""))
        assertEquals(setOf(55L,66L),fresh.mids.value)
        assertTrue(java.awt.Window.getWindows().none { it.isDisplayable })
    }
}
