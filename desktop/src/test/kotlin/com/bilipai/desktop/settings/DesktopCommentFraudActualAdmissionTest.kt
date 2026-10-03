package com.bilipai.desktop.settings

import com.android.purebilibili.core.database.entity.CommentFraudRecord
import com.android.purebilibili.data.model.CommentFraudStatus
import com.android.purebilibili.data.repository.DesktopOriginalCommentFraudRepository
import com.bilipai.desktop.data.AccountSummary
import com.bilipai.desktop.data.DesktopCommentFraudJsonDao
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.ui.DesktopDynamicImageAssets
import com.bilipai.desktop.ui.DesktopImageSaveLifetime
import com.bilipai.desktop.ui.DesktopImageSaveLocations
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Uses the real account monitor, real Root ImageLifetime and real image Assets
 * commitOwned method. The only credentials, records and directories are synthetic. */
class DesktopCommentFraudActualAdmissionTest {
    @TempDir lateinit var directory: Path
    private class Fixture(root: Path) {
        val sessions=DesktopSessionStore(root.resolve("synthetic-session.json"),persistent=false)
        init { sessions.saveAccount(mapOf("SESSDATA" to "synthetic-only"),AccountSummary(42L,"fixture","")) }
        val owner=checkNotNull(sessions.dynamicCacheOwner())
        val alive=AtomicBoolean(true)
        val lifetime=DesktopImageSaveLifetime { !alive.get() }
        val preferences=DesktopImageSaveLocationPreferences(DesktopPluginStore(root.resolve("synthetic-settings")),lifetime::withCommit)
        val locations=DesktopImageSaveLocations(preferences,lifetime::isActive,lifetime::withCommit,
            resolveDefaultVideoDirectory={error("No real KnownFolder used")},resolveDefaultDirectory={error("No real KnownFolder used")})
        val assets=DesktopDynamicImageAssets(OkHttpClient(),{alive.get() && sessions.generation==owner.epoch},sessions,owner,
            selectTarget={_,_->error("No chooser used")},selectDirectory={error("No chooser used")},imageSaveLocations=locations)
        private val method=DesktopDynamicImageAssets::class.java.getDeclaredMethod("commitOwned",Function0::class.java).apply { isAccessible=true }
        fun imageCommit(block:()->Unit):Boolean {
            try { return method.invoke(assets,block) as Boolean }
            catch (error: InvocationTargetException) { throw error.targetException }
        }
        fun close() { assets.close();lifetime.close();alive.set(false) }
    }
    private fun record(rpid: Long)=CommentFraudRecord(rpid=rpid,oid=765L,uid=42L,message="synthetic $rpid",status="NORMAL",timestamp=1000L)
    private fun worker(name:String,result:AtomicReference<Throwable?>,action:()->Unit):Thread = Thread({
        try { action() } catch(error:Throwable) { result.set(error) }
    },name).apply { isDaemon=true;start() }
    private fun await(latch:CountDownLatch,message:String) = assertTrue(latch.await(3L,SECONDS),message)

    @Test fun concurrentRealImageExportAndRecordImportUseAccountThenImageOrder(): Unit = runBlocking {
        val fixture=Fixture(directory)
        val accountHeld=AtomicBoolean(false)
        val enteredExport=CountDownLatch(1);val releaseExport=CountDownLatch(1);val recordTryingAccount=CountDownLatch(1)
        val imageCommits=AtomicInteger(0);val pageCommits=AtomicInteger(0)
        val exportFailure=AtomicReference<Throwable?>();val pageFailure=AtomicReference<Throwable?>()
        val dao=DesktopCommentFraudJsonDao(directory,42L,{fixture.alive.get() && fixture.sessions.generation==fixture.owner.epoch},{action->
            if(accountHeld.get())recordTryingAccount.countDown()
            fixture.sessions.withCurrentDynamicCacheOwner(fixture.owner,action)
        })
        val original=DesktopOriginalCommentFraudRepository(dao,{_,_,_->Result.success(CommentFraudStatus.NORMAL)},{_,_->Result.success(Unit)})
        val page=DesktopCommentFraudHistoryRecords(original,{fixture.alive.get() && fixture.sessions.generation==fixture.owner.epoch},{action->
            fixture.lifetime.withCommit {
                // This oracle makes the former inversion fail without leaving deadlocked
                // JVM threads. Actual old ordering reaches Image while export owns Session.
                check(!accountHeld.get()) { "Record page entered ImageLifetime before waiting for actual account admission" }
                pageCommits.incrementAndGet();action()
            }
        })
        dao.insertOrUpdate(record(1L))
        val export=worker("actual-image-export",exportFailure) {
            assertTrue(fixture.sessions.withCurrentDynamicCacheOwner(fixture.owner) {
                accountHeld.set(true);enteredExport.countDown()
                try {
                    await(releaseExport,"export release")
                    assertTrue(fixture.imageCommit { imageCommits.incrementAndGet() })
                } finally { accountHeld.set(false) }
            })
        }
        var importing:Thread?=null
        try {
            await(enteredExport,"actual exporter did not own SessionStore")
            importing=worker("actual-record-import",pageFailure) {
                runBlocking { assertEquals(1,page.importFromJson(Json.encodeToString(listOf(record(2L)))).getOrThrow()) }
            }
            await(recordTryingAccount,"record operation must wait on actual account monitor before entering ImageLifetime")
            assertEquals(0,pageCommits.get())
        } finally { releaseExport.countDown();export.join(5000L);importing?.join(5000L) }
        try {
            assertFalse(export.isAlive);assertFalse(importing?.isAlive==true)
            assertNull(exportFailure.get());assertNull(pageFailure.get())
            assertEquals(1,imageCommits.get());assertTrue(pageCommits.get()>=2)
            assertEquals(setOf(1L,2L),dao.getAllRecords().map { it.rpid }.toSet())
        } finally { dao.close();if(!export.isAlive && importing?.isAlive!=true)fixture.close() }
    }

    @Test fun realImageLifetimeCloseWhileImportWaitsOnAccountRejectsLateRecordPublication(): Unit = runBlocking {
        val fixture=Fixture(directory)
        val accountHeld=AtomicBoolean(false);val enteredExport=CountDownLatch(1);val releaseExport=CountDownLatch(1)
        val recordTryingAccount=CountDownLatch(1);val closeDone=CountDownLatch(1)
        val exportFailure=AtomicReference<Throwable?>();val pageFailure=AtomicReference<Throwable?>();val closeFailure=AtomicReference<Throwable?>()
        val dao=DesktopCommentFraudJsonDao(directory,42L,{fixture.alive.get() && fixture.sessions.generation==fixture.owner.epoch},{action->
            if(accountHeld.get())recordTryingAccount.countDown()
            fixture.sessions.withCurrentDynamicCacheOwner(fixture.owner,action)
        })
        dao.insertOrUpdate(record(3L))
        val file=directory.resolve("comment_fraud_records/42/records.json");val prior=Files.readAllBytes(file)
        val page=DesktopCommentFraudHistoryRecords(
            DesktopOriginalCommentFraudRepository(dao,{_,_,_->Result.success(CommentFraudStatus.NORMAL)},{_,_->Result.success(Unit)}),
            {fixture.alive.get() && fixture.lifetime.isActive() && fixture.sessions.generation==fixture.owner.epoch},
            {action->fixture.lifetime.withCommit {
                check(!accountHeld.get()) { "Record page held ImageLifetime while waiting for actual account monitor" }
                action()
            }},
        )
        val export=worker("actual-export-awaiting-close",exportFailure) {
            fixture.sessions.withCurrentDynamicCacheOwner(fixture.owner) {
                accountHeld.set(true);enteredExport.countDown()
                try { await(releaseExport,"export release");fixture.imageCommit { error("retired image publication ran") } }
                finally { accountHeld.set(false) }
            }
        }
        var importing:Thread?=null;var closing:Thread?=null
        try {
            await(enteredExport,"actual exporter did not own SessionStore")
            importing=worker("actual-import-awaiting-close",pageFailure) {
                runBlocking { page.importFromJson(Json.encodeToString(listOf(record(4L)))).getOrThrow() }
            }
            await(recordTryingAccount,"record import did not wait outside ImageLifetime")
            closing=worker("actual-image-lifetime-close",closeFailure) { fixture.lifetime.close();closeDone.countDown() }
            await(closeDone,"ImageLifetime close must finish while exporter owns account and record waits on account")
        } finally { releaseExport.countDown();export.join(5000L);importing?.join(5000L);closing?.join(5000L) }
        try {
            assertFalse(export.isAlive);assertFalse(importing?.isAlive==true);assertFalse(closing?.isAlive==true)
            assertNull(closeFailure.get());assertTrue(exportFailure.get() is CancellationException)
            assertTrue(pageFailure.get() is CancellationException)
            assertArrayEquals(prior,Files.readAllBytes(file));assertEquals(listOf(record(3L)),dao.getAllRecords())
            assertEquals(listOf("records.json"),Files.list(file.parent).use { it.map { p->p.fileName.toString() }.toList() })
        } finally { dao.close();if(!export.isAlive && importing?.isAlive!=true && closing?.isAlive!=true)fixture.close() }
    }

    @Test fun actualSameMidCredentialReplacementRejectsOldRecordPageBeforeImageAdmission(): Unit = runBlocking {
        val fixture=Fixture(directory);val pageAdmissions=AtomicInteger(0)
        val dao=DesktopCommentFraudJsonDao(directory,42L,{fixture.sessions.generation==fixture.owner.epoch},{action->fixture.sessions.withCurrentDynamicCacheOwner(fixture.owner,action)})
        try {
            dao.insertOrUpdate(record(5L));val file=directory.resolve("comment_fraud_records/42/records.json");val prior=Files.readAllBytes(file)
            val page=DesktopCommentFraudHistoryRecords(
                DesktopOriginalCommentFraudRepository(dao,{_,_,_->Result.success(CommentFraudStatus.NORMAL)},{_,_->Result.success(Unit)}),
                {fixture.sessions.generation==fixture.owner.epoch},
                {action->fixture.lifetime.withCommit { pageAdmissions.incrementAndGet();action() }},
            )
            fixture.sessions.saveAccount(mapOf("SESSDATA" to "synthetic-replacement"),AccountSummary(42L,"fixture",""))
            assertNotEquals(fixture.owner.epoch,fixture.sessions.generation)
            try { page.importFromJson(Json.encodeToString(listOf(record(6L))));fail<Unit>("Retired credential import must cancel") }
            catch (_:CancellationException) { }
            assertEquals(0,pageAdmissions.get());assertArrayEquals(prior,Files.readAllBytes(file))
        } finally { dao.close();fixture.close() }
    }
}
