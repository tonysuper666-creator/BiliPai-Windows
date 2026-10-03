package com.bilipai.desktop.settings

import com.android.purebilibili.core.database.entity.CommentFraudRecord
import com.android.purebilibili.data.model.CommentFraudStatus
import com.android.purebilibili.data.repository.DesktopOriginalCommentFraudRepository
import com.bilipai.desktop.data.DesktopCommentFraudJsonDao
import com.android.purebilibili.feature.settings.SettingsRootCategory
import com.android.purebilibili.feature.settings.SettingsSearchFocusController
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Every record/API in this fixture is synthetic; no real credential or remote call. */
class DesktopCommentFraudHistoryTest {
    @TempDir lateinit var directory: Path
    private fun record(rpid: Long, postTime: Long = 1000L) = CommentFraudRecord(
        rpid=rpid, oid=9876L, type=17, root=543L, parent=544L, uid=123456L,
        source_id="synthetic-dynamic", origin_url="bilibili://synthetic", message="原文[doge]与表情😀\n第二行",
        initial_status="NORMAL", status="NORMAL", post_time=postTime, timestamp=2000L,
    )
    private suspend fun cancelled(block: suspend () -> Unit) {
        try { block(); throw AssertionError("Expected owner cancellation") }
        catch (_: CancellationException) { }
    }

    @Test fun fullMetadataImportExportAndFlowUseTheExistingDao(): Unit = runBlocking {
        val dao=DesktopCommentFraudJsonDao(directory,123456L,{true},{it();true})
        try {
            val original=DesktopOriginalCommentFraudRepository(dao,{_,_,_->Result.success(CommentFraudStatus.NORMAL)},{_,_->Result.success(Unit)})
            val page=DesktopCommentFraudHistoryRecords(original,{true},{it();true})
            val earlier=record(1L,1000L);val later=record(2L,3000L)
            assertEquals(2,page.importFromJson(Json.encodeToString(listOf(earlier,later))).getOrThrow())
            assertEquals(listOf(later,earlier),dao.getAllRecords())
            assertEquals(dao.getAllRecords(),page.getAllRecordsFlow().first())
            val exported=page.exportToJson()
            assertEquals(listOf(later,earlier),Json.decodeFromString<List<CommentFraudRecord>>(exported))
            val actualFile=directory.resolve("comment_fraud_records/123456/records.json")
            val persisted=Json.decodeFromString<List<CommentFraudRecord>>(Files.readString(actualFile))
            assertEquals(2,persisted.size)
            // Disk storage has no ordering contract; DAO/Flow/export ordering is checked above.
            assertEquals(listOf(later,earlier).associateBy { it.rpid },persisted.associateBy { it.rpid })
            // The original import policy replaces the same RPID, preserving other records.
            assertEquals(1,page.importFromJson(Json.encodeToString(listOf(earlier.copy(message="updated")))).getOrThrow())
            assertEquals(2,dao.getAllRecords().size)
            assertEquals("updated",dao.getRecordByRpid(1L)!!.message)
        } finally { dao.close() }
    }

    @Test fun recheckKeepsOriginalRequestFieldsUnknownFallbackAndMetadata(): Unit = runBlocking {
        val dao=DesktopCommentFraudJsonDao(directory,123456L,{true},{it();true})
        try {
            val value=record(3L);dao.insertOrUpdate(value)
            val requests=mutableListOf<Triple<Long,Long,Long>>()
            val original=DesktopOriginalCommentFraudRepository(dao,{oid,rpid,root->requests+=Triple(oid,rpid,root);Result.failure(IllegalStateException("synthetic lookup failure"))},{_,_->Result.success(Unit)})
            val page=DesktopCommentFraudHistoryRecords(original,{true},{it();true})
            // Original repository intentionally converts probe failure to UNKNOWN success.
            assertEquals(CommentFraudStatus.UNKNOWN,page.recheckRecord(value).getOrThrow())
            assertEquals(listOf(Triple(value.oid,value.rpid,value.root)),requests)
            val actual=dao.getRecordByRpid(value.rpid)!!
            assertEquals(value.copy(status="UNKNOWN",timestamp=actual.timestamp),actual)
            assertTrue(actual.timestamp>=value.timestamp)
        } finally { dao.close() }
    }

    @Test fun remoteDeleteUsesOriginalCallbackAndOnlySuccessRemovesLocalRecord(): Unit = runBlocking {
        val dao=DesktopCommentFraudJsonDao(directory,123456L,{true},{it();true})
        try {
            val value=record(4L);dao.insertOrUpdate(value)
            val allow=AtomicBoolean(false);val calls=mutableListOf<Pair<Long,Long>>()
            val original=DesktopOriginalCommentFraudRepository(dao,{_,_,_->Result.success(CommentFraudStatus.NORMAL)},{oid,rpid->calls+=oid to rpid;if(allow.get())Result.success(Unit) else Result.failure(IllegalStateException("synthetic delete failure"))})
            val page=DesktopCommentFraudHistoryRecords(original,{true},{it();true})
            assertTrue(page.deleteBiliComment(value).isFailure)
            assertEquals(value,dao.getRecordByRpid(value.rpid))
            allow.set(true)
            assertTrue(page.deleteBiliComment(value).isSuccess)
            assertNull(dao.getRecordByRpid(value.rpid))
            assertEquals(listOf(value.oid to value.rpid,value.oid to value.rpid),calls)
        } finally { dao.close() }
    }

    @Test fun rejectedFinalPagePublicationKeepsRootRecordsAndCleansOwnedStage(): Unit = runBlocking {
        val dao=DesktopCommentFraudJsonDao(directory,123456L,{true},{it();true})
        try {
            val old=record(5L);dao.insertOrUpdate(old)
            val recordsDirectory=directory.resolve("comment_fraud_records/123456")
            val recordsFile=recordsDirectory.resolve("records.json")
            val priorBytes=Files.readAllBytes(recordsFile)
            val pageAlive=AtomicBoolean(true)
            val page=DesktopCommentFraudHistoryRecords(
                DesktopOriginalCommentFraudRepository(dao,{_,_,_->Result.success(CommentFraudStatus.NORMAL)},{_,_->Result.success(Unit)}),
                pageAlive::get,
                { action ->
                    val hasStage=Files.list(recordsDirectory).use { paths->paths.anyMatch { it.fileName.toString().endsWith(".tmp") } }
                    if(hasStage) { pageAlive.set(false);false } else { action();true }
                },
            )
            cancelled { page.importFromJson(Json.encodeToString(listOf(record(6L)))) }
            assertArrayEquals(priorBytes,Files.readAllBytes(recordsFile))
            assertEquals(listOf(old),dao.getAllRecords())
            assertEquals(listOf("records.json"),Files.list(recordsDirectory).use { it.map { path->path.fileName.toString() }.toList() })
            // Root-originated capture still uses this same repository/DAO after page exit.
            dao.insertOrUpdate(record(7L))
            assertEquals(setOf(5L,7L),dao.getAllRecords().map { it.rpid }.toSet())
        } finally { dao.close() }
    }

    @Test fun cancellationAndAccountRetirementRejectPageMutations(): Unit = runBlocking {
        val account=AtomicBoolean(true)
        val dao=DesktopCommentFraudJsonDao(directory,123456L,account::get,{action->if(account.get()){action();true}else false})
        try {
            val original=DesktopOriginalCommentFraudRepository(dao,{_,_,_->Result.success(CommentFraudStatus.NORMAL)},{_,_->Result.success(Unit)})
            val page=DesktopCommentFraudHistoryRecords(original,{true},{it();true})
            dao.insertOrUpdate(record(8L))
            val recordsFile=directory.resolve("comment_fraud_records/123456/records.json")
            val bytes=Files.readAllBytes(recordsFile)
            val cancelledJob=Job().apply { cancel() }
            cancelled { withContext(cancelledJob) { page.clearAllRecords() } }
            assertArrayEquals(bytes,Files.readAllBytes(recordsFile))
            account.set(false)
            cancelled { page.deleteLocalRecord(8L) }
            assertArrayEquals(bytes,Files.readAllBytes(recordsFile))
        } finally { dao.close() }
    }

    @Test fun pageBackReturnsToPrivacyAndReentryGetsUniqueOwnerIdentity() {
        val navigator=DesktopSettingsNavigator()
        try {
            navigator.openCategory(SettingsRootCategory.PRIVACY_PERMISSION)
            val privacy=navigator.state.value.current
            navigator.openCommentFraudHistory()
            val first=navigator.state.value.current as DesktopSettingsPage.CommentFraudHistory
            assertTrue(navigator.pop());assertSame(privacy,navigator.state.value.current)
            navigator.openCommentFraudHistory()
            val second=navigator.state.value.current as DesktopSettingsPage.CommentFraudHistory
            assertNotEquals(first.entryToken,second.entryToken);assertNotSame(first,second)
            navigator.leave();assertSame(DesktopSettingsPage.Root,navigator.state.value.current)
        } finally { SettingsSearchFocusController.clear() }
    }
}
