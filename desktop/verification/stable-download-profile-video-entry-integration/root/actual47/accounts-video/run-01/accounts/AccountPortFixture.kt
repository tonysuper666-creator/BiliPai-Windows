package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.NavData
import com.android.purebilibili.data.model.response.VipInfo
import com.android.purebilibili.data.model.response.VipLabel
import com.bilipai.desktop.data.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private var assertions=0
private fun verify(value:Boolean,reason:String){check(value){reason};assertions++}
private fun seed(store:DesktopSessionStore){
    store.saveAccount(mapOf("SESSDATA" to "synthetic-secondary","bili_jct" to "csrf-two"),AccountSummary(2,"Secondary","",true),imported=true,
        credentials=DesktopAppCredentials("token-two","refresh-two","tv",0))
    store.saveAccount(mapOf("SESSDATA" to "synthetic-primary","bili_jct" to "csrf-one"),AccountSummary(1,"Primary",""),imported=true,
        credentials=DesktopAppCredentials("token-one","refresh-one","tv",0))
}
private class Entry(private val repository:DesktopRepository){
    val job=Job();var alive=true
    val epoch=repository.sessionEpoch;val mid=repository.activeAccountMid()
    val port=DesktopProfileAccountsBinding(repository,epoch,mid,job,{alive&&repository.sessionEpoch==epoch}){action->
        if(!alive||!job.isActive||repository.sessionEpoch!=epoch)false else{action();true}}
}
private suspend fun cancelled(action:suspend()->Unit):Boolean=try{action();false}catch(_:CancellationException){true}

private suspend fun metadata(root:Path){
    val file=root.resolve("private-session.json");val store=DesktopSessionStore(file);seed(store)
    val repository=DesktopRepository(store);val entry=Entry(repository);val port=entry.port
    verify(port.getAccounts().map{it.mid}.toSet()==setOf(1L,2L),"sole complete original catalog")
    verify(port.currentMid()==1L&&port.getActiveAccountMid()==1L&&port.hasSession(),"real primary snapshot")
    verify(port.accessTokenCredentials()==("token-one" to "tv"),"same original access token platform")
    verify(port.getPlaybackAccountMid()==null&&port.setPlaybackAccountMid(2)&&port.getPlaybackAccountMid()==2L,"real dedicated playback selection")
    val epoch=repository.sessionEpoch;val before=store.currentCookies()
    port.saveMid(1);port.saveVipStatus(true)
    port.upsertCurrentAccount(NavData(isLogin=true,mid=1,uname="Server name",face="https://synthetic.invalid/face",vip=VipInfo(1,label=VipLabel("Annual"))))
    val session=port.getAccounts().single{it.mid==1L}
    verify(session.name=="Server name"&&session.face=="https://synthetic.invalid/face"&&session.isVip&&session.vipLabel=="Annual","full original nav fields and label persist")
    verify(store.currentCookies()==before&&port.accessTokenCredentials()==("token-one" to "tv"),"nav cannot rewrite credentials")
    verify(repository.sessionEpoch==epoch&&port.getPlaybackAccountMid()==2L,"metadata cannot switch primary or playback MID")
    port.upsertCurrentAccount(null);port.saveVipStatus(false)
    verify(port.getAccounts().single{it.mid==1L}.let{it.name==session.name&&it.face==session.face&&it.vipLabel=="Annual"&&!it.isVip},"null upsert and later canonical projection retain original previous fields")
    val reopened=DesktopSessionStore(file).storedAccountSessions().single{it.mid==1L}
    verify(reopened.name==session.name&&reopened.vipLabel=="Annual"&&!reopened.isVip,"same encrypted file restores actual metadata")
    verify(!Files.readString(file).contains("synthetic-primary")&&!Files.readString(file).contains("token-one"),"credentials remain DPAPI encrypted")
    val document=Files.readAllBytes(file)
    verify(cancelled{port.saveMid(3)},"foreign nav MID is rejected")
    verify(cancelled{port.upsertCurrentAccount(NavData(mid=3,uname="Foreign"))},"foreign upsert is rejected")
    verify(Files.readAllBytes(file).contentEquals(document),"foreign metadata writes no document")
    entry.alive=false
    verify(cancelled{port.saveVipStatus(true)}&&Files.readAllBytes(file).contentEquals(document),"retired same-epoch entry rejects final metadata")
    entry.job.cancel()
}
private suspend fun callerOnlyCancellation(root:Path)=coroutineScope{
    val file=root.resolve("cancel-session.json");val store=DesktopSessionStore(file);seed(store)
    val repository=DesktopRepository(store);val entry=Entry(repository);val before=Files.readAllBytes(file)
    val lock=store.javaClass.getDeclaredField("lock").apply{isAccessible=true}.get(store)
    val entered=CountDownLatch(1);val release=CountDownLatch(1)
    val holder=Thread{ synchronized(lock){entered.countDown();check(release.await(5,TimeUnit.SECONDS))} }.apply{start()}
    check(entered.await(3,TimeUnit.SECONDS))
    val thread=AtomicReference<Thread>()
    val task=async(Dispatchers.Default){thread.set(Thread.currentThread());entry.port.saveVipStatus(true)}
    withTimeout(3000){while(thread.get()?.state!=Thread.State.BLOCKED)delay(1)}
    verify(thread.get().state==Thread.State.BLOCKED,"actual caller waits on existing Store monitor")
    task.cancel();release.countDown();holder.join(3000)
    verify(cancelled{task.await()},"only captured caller cancellation is surfaced after Store wait")
    verify(entry.alive&&entry.job.isActive&&repository.sessionEpoch==entry.epoch,"entry and same account remain active")
    verify(Files.readAllBytes(file).contentEquals(before),"cancelled caller emits no final metadata")
    entry.port.saveVipStatus(true)
    verify(store.storedAccountSessions().single{it.mid==1L}.isVip,"fresh request still writes normally")
    entry.job.cancel()
}
private suspend fun selectionAndTerminal(root:Path){
    val store=DesktopSessionStore(root.resolve("terminal-session.json"));seed(store);val repository=DesktopRepository(store)
    val original=Entry(repository);verify(original.port.setPlaybackAccountMid(2),"playback account selected without primary change")
    val epoch=repository.sessionEpoch
    verify(original.port.removeAccount(2),"original synchronous remove reaches same catalog")
    verify(store.getPlaybackAccountMid()==null&&repository.sessionEpoch==epoch&&store.activeAccountMid()==1L,"remove clears only selected playback and preserves primary epoch")
    original.job.cancel();verify(cancelled{original.port.removeAccount(1)},"cancelled entry refuses synchronous click")
    seed(store);val activate=Entry(repository)
    verify(activate.port.activateAccount(2),"original stored credentials activate through same Store")
    verify(store.activeAccountMid()==2L&&store.accessTokenCredentials().first=="token-two","full saved token platform restored")
    verify(cancelled{activate.port.getAccounts()},"old primary owner cannot read replacement catalog for stale UI")
    activate.job.cancel()
    val logout=Entry(repository);logout.port.upsertCurrentAccount(null);logout.port.clearCurrentSession()
    val accepted=repository.sessionEpoch;logout.port.clearActiveAccount()
    verify(store.activeAccountMid()==null&&store.currentCookies()["SESSDATA"].isNullOrBlank(),"full real logout clears active credentials")
    verify(repository.sessionEpoch==accepted&&accepted==logout.epoch+1,"terminal completion never repeats epoch mutation")
    verify(cancelled{logout.port.saveVipStatus(true)}&&cancelled{logout.port.getAccounts()},"terminal receipt grants no new-epoch mutation/read")
    store.activateAccount(1)
    verify(cancelled{logout.port.clearActiveAccount()}&&store.activeAccountMid()==1L,"superseded terminal cannot clear a newly activated account")
    logout.job.cancel()
}
fun main(args:Array<String>)=runBlocking{
    val root=Path.of(args.single());Files.createDirectories(root)
    metadata(root);callerOnlyCancellation(root);selectionAndTerminal(root)
    val origins=listOf(DesktopProfileAccountsBinding::class.java,DesktopSessionStore::class.java,DesktopRepository::class.java,
        com.android.purebilibili.core.store.StoredAccountSession::class.java,NavData::class.java)
    val rows=origins.joinToString(","){type->
        val bytes=type.getResourceAsStream("/"+type.name.replace('.','/')+".class")!!.use{it.readBytes()}
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        "{\"class\":\"${type.name}\",\"codeSource\":\"${type.protectionDomain.codeSource.location}\",\"classSha256\":\"$hash\"}"
    }
    Files.writeString(root.resolve("result.json"),"{\"passed\":true,\"groups\":3,\"assertions\":$assertions,\"origins\":[$rows],\"HTTP\":false,\"RootMounted\":false}")
    println("PASS profile account 3 groups $assertions assertions; explicit Store/Repository candidate overrides, no HTTP/socket/RootWindow")
}
