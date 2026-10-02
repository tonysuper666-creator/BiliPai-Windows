package com.bilipai.desktop.settings
import com.android.purebilibili.core.store.DesktopOriginalStorageSettings as Settings
import com.android.purebilibili.core.util.*
import com.android.purebilibili.feature.video.subtitle.SubtitleTrackMeta
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.ui.*
import com.bilipai.desktop.player.*
import com.bilipai.desktop.player.cache.*
import com.bilipai.desktop.diagnostics.DesktopNativeTextShare
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import java.nio.file.*
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private class FixtureCall(private val request: Request, private val json: String?=null): Call {
    private val tags=mutableMapOf<Class<*>,Any>()
    override fun <T:Any> tag(type:kotlin.reflect.KClass<T>):T? = tag(type.java)
    override fun <T> tag(type:Class<out T>):T? = tags[type]?.let(type::cast)
    override fun <T:Any> tag(type:kotlin.reflect.KClass<T>,computeIfAbsent:()->T):T = tag(type.java,computeIfAbsent)
    override fun <T:Any> tag(type:Class<T>,computeIfAbsent:()->T):T = tag(type) ?: computeIfAbsent().also {tags[type]=it}
    private val cancelled=AtomicBoolean()
    private var callback:Callback?=null
    override fun request()=request
    override fun timeout()=Timeout.NONE
    override fun execute():Response=error("No network in deterministic Call seam")
    override fun enqueue(callback: Callback) {
        this.callback=callback
        if(cancelled.get())callback.onFailure(this,IOException("cancelled"))
        else if(json!=null)callback.onResponse(this,Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(json.toResponseBody("application/json".toMediaType())).build())
    }
    override fun cancel() {if(cancelled.compareAndSet(false,true))callback?.onFailure(this,IOException("cancelled"))}
    override fun isExecuted()=callback!=null
    override fun isCanceled()=cancelled.get()
    override fun clone():Call=FixtureCall(request,json)
}
fun main(args:Array<String>)=runBlocking {
    val root=Path.of(args[0]).toAbsolutePath().normalize();require(root.fileName.toString().startsWith("bp-storage-fixture-"))
    require(root.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
    val checks=mutableListOf<String>()
    fun assert(name:String,value:Boolean) {check(value){name};checks+=name}
    fun fails(name:String,block:()->Unit) {assert(name,runCatching(block).isFailure)}
    suspend fun failsSuspend(name:String,block:suspend()->Unit) {assert(name,runCatching {block()}.isFailure)}
    val alive=AtomicBoolean(true)
    val store=DesktopPluginStore(root.resolve("settings"))
    val context=DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store),alive::get,{block->if(alive.get()){block();true}else false})
    val destination=Files.createDirectory(root.resolve("future-downloads"))
    Settings.setDownloadPath(context,destination.toString())
    assert("canonical-directory-and-sync-mirror-same",Settings.getDownloadPath(context).first()==Settings.getDownloadPathSync(context))
    Settings.setDownloadPath(context,null)
    assert("null-reset-removes-canonical-and-mirror",Settings.getDownloadPath(context).first()==null && Settings.getDownloadPathSync(context)==null)
    Settings.setAutoCacheClearThresholdGb(context,90)
    assert("original-threshold-clamps20",Settings.getAutoCacheClearThresholdGb(context).first()==20)
    Settings.setLastAutoCacheClearAt(context,1777777777777L)
    assert("original-Long-timestamp-preserved",Settings.getLastAutoCacheClearAt(context)==1777777777777L)
    Settings.setLastAutoCacheClearAt(context,-1L)
    assert("original-negative-timestamp-clamps0",Settings.getLastAutoCacheClearAt(context)==0L)
    assert("original-never-below-threshold-no-auto",!shouldAutomaticallyClearCache(Settings.AutoCacheClearInterval.NEVER,0,1000,1,2))
    assert("original-weekly-first-start-due",shouldAutomaticallyClearCache(Settings.AutoCacheClearInterval.WEEKLY,0,1000,1,2))
    assert("original-threshold-at-limit-due",shouldAutomaticallyClearCache(Settings.AutoCacheClearInterval.NEVER,0,1000,2,2))
    val stableNamespace=store.preferences("settings");val stableMirror=store.preferences("download_prefs")
    val backing=root.resolve("settings/plugin-settings.json");val saved=root.resolve("settings/saved-backing")
    Files.move(backing,saved);Files.createDirectory(backing)
    try {failsSuspend("failed-replacement-rejects-publication") { Settings.setDownloadPath(context,destination.toString()) }
        assert("failure-retains-both-in-memory-snapshots",store.preferences("settings")==stableNamespace && store.preferences("download_prefs")==stableMirror)
    } finally {Files.delete(backing);Files.move(saved,backing)}
    assert("failure-cleans-only-owned-settings-temporaries",Files.list(root.resolve("settings")).use {it.filter {p->p.fileName.toString().startsWith("plugin-settings-")}.count()}==0L)
    alive.set(false)
    failsSuspend("retired-window-rejects-original-setter") {Settings.setDownloadPath(context,destination.toString())}
    alive.set(true)
    val mediaRoot=Files.createDirectory(root.resolve("media"));val unknown=Files.writeString(mediaRoot.resolve("user-note.txt"),"preserve unknown")
    val spans=DesktopMediaByteSpanStore(mediaRoot,4096L)
    assert("media-index-does-not-delete-unowned-startup-file",Files.readString(unknown)=="preserve unknown")
    val reservation=spans.reserve(4)
    spans.begin(reservation,"a".repeat(64),0,4,true).use {it.write(byteArrayOf(1,2,3,4))}
    fails("media-active-reservation-refuses-clear") {spans.clearIdle {}}
    assert("active-reservation-file-retained",Files.exists(reservation.temp))
    spans.publish(reservation,"a".repeat(64),0,4) {it()}
    val reader=checkNotNull(spans.open("a".repeat(64),0))
    fails("media-pinned-reader-refuses-clear") {spans.clearIdle {}}
    val output=ByteArray(4);assert("pinned-reader-still-reads",reader.read(output,0,4)==4 && output.contentEquals(byteArrayOf(1,2,3,4)))
    reader.close();spans.clearIdle {}
    assert("idle-media-clears-owned-spans-only",spans.stats()==(0L to 0) && Files.exists(unknown))
    val after=spans.reserve(2);spans.begin(after,"b".repeat(64),0,2,true).use {it.write(byteArrayOf(5,6))};spans.publish(after,"b".repeat(64),0,2){it()}
    assert("media-owner-reusable-after-clear",spans.stats().second==1);spans.clearIdle {};spans.close()
    val client=OkHttpClient()
    val subtitles=DesktopSubtitleAssets(client)
    val cueJson="""{"body":[{"from":0.0,"to":3.0,"content":"isolated cue"}]}"""
    val track=SubtitleTrackMeta(lan="en",lanDoc="fixture",subtitleUrl="https://aisubtitle.hdslb.com/subtitle/cache-fixture.json")
    val subtitleFile=subtitles.import(track,Call.Factory {FixtureCall(it,cueJson)})
    val localUserSubtitle=Files.writeString(root.resolve("user-selected.srt"),"never cache-owned")
    assert("same-subtitle-owner-has-cues-and-actual-file",subtitles.cues(subtitleFile).single().content=="isolated cue" && Files.size(subtitleFile)>0)
    val entered=CountDownLatch(1);val release=CountDownLatch(1)
    val pending=launch(Dispatchers.IO) {
        subtitles.import(track.copy(subtitleUrl="https://aisubtitle.hdslb.com/subtitle/blocked-fixture.json"),Call.Factory {
            entered.countDown();check(release.await(5,TimeUnit.SECONDS));FixtureCall(it)
        })
    }
    check(withContext(Dispatchers.IO) {entered.await(5,TimeUnit.SECONDS)})
    fails("subtitle-pre-newCall-import-counter-refuses-clear") {subtitles.clearIdle {}}
    assert("busy-subtitle-retains-existing-file",Files.exists(subtitleFile))
    pending.cancel();release.countDown();pending.join()
    val player=MpvPlayer(useNullAudioOutput=true) // No Canvas attach or software transport; native actor is intentionally absent.
    try {
        player.withIdleCacheMaintenance({}) {
            val version=player.currentSourceVersion
            fails("same-player-central-load-refuses-during-maintenance") {player.load(com.bilipai.desktop.player.PlaybackSource("https://fixture.invalid/media"))}
            assert("denied-load-did-not-mutate-source-version",player.currentSourceVersion==version && player.currentSourceSnapshot()==null)
            fails("same-player-local-subtitle-install-refuses-during-maintenance") {player.addSubtitle(localUserSubtitle)}
            subtitles.clearIdle {}
        }
        assert("subtitle-clear-keeps-user-local-SRT",!Files.exists(subtitleFile) && Files.exists(localUserSubtitle))
        val again=subtitles.import(track,Call.Factory {FixtureCall(it,cueJson)})
        assert("subtitle-owner-reimports-after-maintenance",Files.exists(again) && subtitles.cues(again).isNotEmpty())
        failsSuspend("maintenance-body-failure-is-reported") {player.withIdleCacheMaintenance({}) {error("fixture IO failure")}}
        player.load(com.bilipai.desktop.player.PlaybackSource("https://fixture.invalid/media"));assert("maintenance-finally-reopens-same-player-admission",player.currentSourceSnapshot()!=null);player.stop()
        val repository=DesktopRepository(DesktopSessionStore(root.resolve("isolated-session"),persistent=false))
        assert("actual-repository-http-disk-cache-absent",repository.httpClient.cache==null)
        val images=DesktopApplicationImageLoader(repository,root.resolve("coil"),alive::get)
        val disk=checkNotNull(images.imageLoader.diskCache)
        val editor=checkNotNull(disk.openEditor("storage-fixture"))
        editor.metadata.toFile().writeText("metadata");editor.data.toFile().writeText("pinned original bytes");editor.commit()
        val pinned=checkNotNull(disk.openSnapshot("storage-fixture"));images.clearManagedCaches {}
        assert("same-Coil-clear-preserves-active-snapshot-read",pinned.data.toFile().readText()=="pinned original bytes")
        pinned.close();assert("same-Coil-zombie-retires-on-snapshot-close",disk.openSnapshot("storage-fixture")==null)
        val appScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val media=DesktopMediaByteCache(root.resolve("actual-media"),appScope,repository,maxBytes=4096)
        val pool=DesktopVideoShareFiles(root.resolve("share-cache"),{byteArrayOf(1)},alive::get,{block->if(alive.get()){block();true}else false})
        val native=DesktopNativeTextShare({error("No native DLL or receiver invoked by this fixture")},"0".repeat(64),{null})
        val owner=DesktopStorageSettingsOwner(context,repository,images,{media},subtitles,null,player,null,native,{pool},{{alive.get()}},alive::get)
        try {
            owner.selectDownloadPath(destination)
            assert("real-storage-owner-atomic-directory-and-mirror",owner.defaultDestination()==destination && Settings.getDownloadPathSync(context)==destination.toString())
            assert("Windows-directory-update-clears-old-SAF-mirror",Settings.getDownloadExportTreeUriSync(context)==null)
            failsSuspend("relative-folder-selection-rejected") {owner.selectDownloadPath(Path.of("relative"))}
            val ownedShare=pool.publish("BiliPai_share_fixture.gif","image/gif") {Files.write(it,byteArrayOf(71,73,70))}
            val unownedShare=Files.writeString(ownedShare.path.parent.resolve("user-image.gif"),"not share-owned")
            pool.mayExpose(ownedShare)
            fails("pool-clear-refuses-live-file-lease") {pool.clearExplicit()}
            assert("live-share-lease-remains-on-failure",Files.exists(ownedShare.path))
            pool.retire(ownedShare,false)
            owner.clear(setOf(CacheClearTarget.TEMP_FILES_AND_LOGS))
            assert("real-entry-calls-same-native-actor-and-same-pool-clear",!Files.exists(ownedShare.path) && Files.exists(unownedShare))
            store.update("history",mapOf("sentinel" to JsonPrimitive("keep")))
            store.update("following_cache",mapOf("following_payload_v1" to JsonPrimitive("synthetic fixture"),"other_key" to JsonPrimitive("keep")))
            owner.clear(setOf(CacheClearTarget.APP_METADATA,CacheClearTarget.PLAYBACK_QUALITY))
            assert("metadata-clear-only-original-payload-key",store.preferences("following_cache")["following_payload_v1"]==null && store.preferences("following_cache")["other_key"]==JsonPrimitive("keep"))
            assert("cache-clear-excludes-history",store.preferences("history")["sentinel"]==JsonPrimitive("keep"))
            owner.saveInterval(Settings.AutoCacheClearInterval.WEEKLY);owner.saveThreshold(5)
            assert("original-startup-policy-consumed-once",owner.clearAutomaticallyAtStartup(1777777777777L) && !owner.clearAutomaticallyAtStartup(1777777778888L))
            assert("successful-startup-records-original-timestamp",Settings.getLastAutoCacheClearAt(context)==1777777777777L)
            val kept=pool.publish("BiliPai_share_restore.gif","image/gif") {Files.write(it,byteArrayOf(1,2,3))}
            val nativeEntered=CountDownLatch(1);val nativeRelease=CountDownLatch(1)
            val occupying=launch(Dispatchers.IO) {native.clearVideoShareFiles {nativeEntered.countDown();check(nativeRelease.await(5,TimeUnit.SECONDS))}}
            check(withContext(Dispatchers.IO) {nativeEntered.await(5,TimeUnit.SECONDS)})
            val clearing=launch { runCatching {owner.clear(setOf(CacheClearTarget.TEMP_FILES_AND_LOGS))} }
            delay(100)
            failsSuspend("settings-maintenance-reentry-rejected") {owner.selectDownloadPath(null)}
            val retirement=launch {owner.shutdownForRestore()};delay(50)
            assert("restore-waits-for-accepted-maintenance",!retirement.isCompleted)
            nativeRelease.countDown();occupying.join();clearing.join();retirement.join()
            assert("retired-clear-keeps-uncommitted-share-files",Files.exists(kept.path))
            failsSuspend("retired-owner-rejects-new-directory-update") {owner.selectDownloadPath(null)}
        } finally {owner.shutdownForRestore();native.shutdown();media.close();appScope.cancel();images.close()}
    } finally {player.close();subtitles.close();client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()}
    Files.writeString(Path.of(args[1]),Json.encodeToString(JsonObject(mapOf("passed" to JsonPrimitive(true),"assertions" to JsonArray(checks.map(::JsonPrimitive)),
        "nativeActor" to JsonPrimitive(false),"RootMounted" to JsonPrimitive(false),"receiverUI" to JsonPrimitive(false),"userDataTouched" to JsonPrimitive(false)))))
    println("StorageOwnerFixture PASS ${checks.size} assertions; isolated owned temp only; native actor/Root/receiver pending")
}
