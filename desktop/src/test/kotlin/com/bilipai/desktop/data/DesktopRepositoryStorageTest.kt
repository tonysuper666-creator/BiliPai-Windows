package com.bilipai.desktop.data

import com.android.purebilibili.core.database.entity.BlockedUp
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.MessageApi
import com.android.purebilibili.data.model.response.MessageFeedUnreadData
import com.android.purebilibili.data.model.response.MessageFeedUnreadResponse
import com.android.purebilibili.core.store.MessageCenterPreferences
import com.android.purebilibili.data.model.CommentFraudStatus
import com.android.purebilibili.data.repository.BlockedUpRepository
import com.android.purebilibili.data.repository.CommentFraudRepository
import com.android.purebilibili.data.repository.MessageRepository
import com.bilipai.desktop.compat.UnsupportedDesktopCapability
import com.bilipai.desktop.plugins.*
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class DesktopRepositoryStorageTest {
    @TempDir lateinit var directory: Path
    private fun plugin(name: String) = DesktopPluginContext(DesktopPluginStore(directory.resolve(name)))
    private fun noNetwork() = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader,
        arrayOf(BilibiliApi::class.java)) { _, method, _ -> error("Unexpected network call: ${method.name}") } as BilibiliApi

    @Test fun upstreamBlockRepositoryWritesExistingStoreAndSurvivesReopen(): Unit = runBlocking {
        val plugin = plugin("global")
        val store = DesktopBlockedUpStore(plugin)
        val context = DesktopRepositoryStorageContext(plugin.asAndroidContext(), store)
        val upstream = BlockedUpRepository(context, noNetwork())
        upstream.blockUp(83, "synthetic UP", "face")
        assertEquals(83L, store.records.value.single().mid)
        assertTrue(upstream.isBlocked(83).first())
        val reopened = DesktopBlockedUpStore(plugin("global"))
        assertEquals(store.records.value, reopened.records.value)
        assertEquals(store.records.value, upstream.getAllBlockedUps().first())
        upstream.unblockUp(83)
        assertTrue(store.records.value.isEmpty())
        assertTrue(DesktopBlockedUpStore(plugin("global")).records.value.isEmpty())
    }

    @Test fun daoSharesMetadataReplacementAndDeleteWithDesktopConsumers(): Unit = runBlocking {
        val store = DesktopBlockedUpStore(plugin("metadata"))
        val dao = DesktopBlockedUpDao(store)
        val first = BlockedUp(9, "name", "face", blockedAt = 10, sign = "sign", level = 6)
        store.upsert(first)
        assertEquals(first, dao.getBlockedUp(9))
        val replacement = first.copy(name = "changed", follower = 42)
        dao.insert(replacement)
        assertEquals(listOf(replacement), store.records.value)
        assertEquals(listOf(replacement), dao.getAllBlockedUpsSnapshot())
        dao.delete(9)
        assertFalse(dao.isBlocked(9).first())
    }

    @Test fun blockDaoMigratesLegacyAndRejectsCorruptDestination(): Unit = runBlocking {
        val plugin = plugin("migration")
        val legacy = plugin.store.root.resolve("discovery/plugin-settings.json")
        Files.createDirectories(legacy.parent)
        Files.writeString(legacy, """{"blocked_ups":{"mids":"[13]"}}""")
        val store = DesktopBlockedUpStore(plugin)
        val dao = DesktopBlockedUpDao(store)
        assertEquals(13L, dao.getAllBlockedUpsSnapshot().single().mid)
        plugin.store.update("blocked_ups", mapOf("records" to kotlinx.serialization.json.JsonPrimitive("broken")))
        assertFailsWith<IllegalStateException> { dao.getAllBlockedUpsSnapshot() }
        assertFailsWith<IllegalStateException> { dao.getAllBlockedUps() }
    }

    @Test fun upstreamFraudRepositoryUsesAccountJsonDaoInBothDirections(): Unit = runBlocking {
        val dao = DesktopCommentFraudJsonDao(directory, 71, { true }, { it(); true })
        val context = DesktopRepositoryStorageContext(plugin("prefs").asAndroidContext(), commentFraudDao = dao)
        try {
            CommentFraudRepository.saveRecord(rpid = 17, oid = 19, message = "synthetic comment",
                status = CommentFraudStatus.NORMAL, postTime = 100, context = context)
            val saved = assertNotNull(dao.getRecordByRpid(17))
            assertEquals(saved, CommentFraudRepository.getAllRecordsFlow(context).first().single())
            assertEquals(1, CommentFraudRepository.importFromJson(CommentFraudRepository.exportToJson(context), context).getOrThrow())
            DesktopCommentFraudJsonDao(directory, 71, { true }, { it(); true }).use { reopened ->
                assertEquals(listOf(saved), reopened.getAllRecords())
            }
            CommentFraudRepository.deleteLocalRecord(17, context)
            assertTrue(dao.getAllRecords().isEmpty())
        } finally { dao.close() }
    }

    @Test fun missingStorageFailsAtUpstreamEntrypoints(): Unit = runBlocking {
        val plain = plugin("unbound").asAndroidContext()
        val missing = DesktopRepositoryStorageContext(plain)
        for (context in listOf(plain, missing)) {
            assertFailsWith<UnsupportedDesktopCapability> { BlockedUpRepository(context, noNetwork()) }
            assertFailsWith<UnsupportedDesktopCapability> { CommentFraudRepository.getAllRecordsFlow(context) }
            assertFailsWith<UnsupportedDesktopCapability> {
                CommentFraudRepository.saveRecord(17, 19, message = "synthetic", status = CommentFraudStatus.NORMAL, context = context)
            }
            assertFailsWith<UnsupportedDesktopCapability> { CommentFraudRepository.exportToJson(context) }
            assertFailsWith<UnsupportedDesktopCapability> { CommentFraudRepository.importFromJson("""[{"rpid":17,"oid":19}]""", context).getOrThrow() }
            assertFailsWith<UnsupportedDesktopCapability> { CommentFraudRepository.importFromJson("[]", context) }
            val record = com.android.purebilibili.core.database.entity.CommentFraudRecord(17, 19)
            assertFailsWith<UnsupportedDesktopCapability> { CommentFraudRepository.recheckRecord(record, context) }
            assertFailsWith<UnsupportedDesktopCapability> { CommentFraudRepository.deleteBiliComment(record, context) }
        }
    }

    @Test fun retiredRecordOwnerCannotWriteThroughUpstreamCatch(): Unit = runBlocking {
        var owned = true
        val dao = DesktopCommentFraudJsonDao(directory, 72, { owned }, { if (owned) { it(); true } else false })
        val context = DesktopRepositoryStorageContext(plugin("retired").asAndroidContext(), commentFraudDao = dao)
        owned = false
        try {
            assertFailsWith<CancellationException> {
                CommentFraudRepository.saveRecord(17, 19, message = "synthetic", status = CommentFraudStatus.NORMAL, context = context)
            }
            assertFalse(Files.exists(directory.resolve("comment_fraud_records/72/records.json")))
        } finally { dao.close() }
    }

    @Test fun storageContextPreservesCallerMessagePreferencesAndApplicationContext(): Unit = runBlocking {
        val first = plugin("one").asAndroidContext()
        val second = plugin("two").asAndroidContext()
        val wrapped = DesktopRepositoryStorageContext(first)
        MessageCenterPreferences.setDisabledLikes(wrapped, true)
        assertTrue(MessageCenterPreferences.disabledLikes(first).first())
        assertTrue(MessageCenterPreferences.disabledLikes(wrapped.applicationContext).first())
        assertFalse(MessageCenterPreferences.disabledLikes(second).first())
        val response = MessageFeedUnreadData(reply = 2, at = 3, like = 8, sysMsg = 4)
        val api = Proxy.newProxyInstance(MessageApi::class.java.classLoader, arrayOf(MessageApi::class.java)) { _, method, _ ->
            check(method.name == "getFeedUnread")
            MessageFeedUnreadResponse(data = response)
        } as MessageApi
        assertEquals(response.copy(like = 0), MessageRepository.getFeedUnread(wrapped, api).getOrThrow())
        assertEquals(response, MessageRepository.getFeedUnread(second, api).getOrThrow())
    }
}
