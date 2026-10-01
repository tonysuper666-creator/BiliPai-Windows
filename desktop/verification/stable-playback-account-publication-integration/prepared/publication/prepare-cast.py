# Executed by prepare.py with its exact hunk/inventory helpers. Task-only producer.
JAVA='desktop/src/main/java/su/litvak/chromecast/api/v2/'
write(HERE/'prepared'/JAVA/'DesktopCastPublication.java','''package su.litvak.chromecast.api.v2;
/** Caller admission only; not another session authority. */
public final class DesktopCastPublication {
    public interface Frame { void admit(Runnable publication); boolean isCurrent(); }
    private static final ThreadLocal<Frame> current = new ThreadLocal<>();
    public static Frame current() { return current.get(); }
    public static Frame replace(Frame frame) {
        Frame previous=current.get();if(frame==null)current.remove();else current.set(frame);return previous;
    }
    private DesktopCastPublication() {}
}
''')
write(HERE/'prepared'/JAVA/'DesktopCastWriter.java','''package su.litvak.chromecast.api.v2;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Sole Channel writer. Enqueue/start admission only; I/O/flush/ACK waits never run inside admission. */
public final class DesktopCastWriter implements AutoCloseable {
    @FunctionalInterface public interface Write { void run() throws IOException; }
    private final Object gate=new Object();
    private volatile Thread ownedThread;
    private boolean closing;
    private final ThreadPoolExecutor writer=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(),task->{Thread thread=new Thread(task,"cast-writer");thread.setDaemon(true);ownedThread=thread;return thread;});
    public void write(Write command,BooleanSupplier stillOwned,DesktopCastPublication.Frame frame) throws IOException {
        FutureTask<Void> task=new FutureTask<>(()->{
            Runnable start=()->{synchronized(gate){if(closing||!stillOwned.getAsBoolean())throw new CancellationException("Cast channel closed");}};
            if(frame==null)start.run();else frame.admit(start);
            if(!stillOwned.getAsBoolean()||frame!=null&&!frame.isCurrent())throw new IOException("Cast publication retired");
            // Accepted start is in-flight; selection after this point cannot recall emitted bytes.
            command.run();return null;
        });
        Runnable enqueue=()->{synchronized(gate){
            if(closing||!stillOwned.getAsBoolean())throw new CancellationException("Cast channel closed");writer.execute(task);
        }};
        try{
            if(frame==null)enqueue.run();else frame.admit(enqueue);
            task.get(); // no Store/Channel monitor is held during write/flush/completion wait
        }catch(InterruptedException failure){task.cancel(true);Thread.currentThread().interrupt();throw new IOException("Cast publication interrupted",failure);
        }catch(ExecutionException failure){Throwable cause=failure.getCause();if(cause instanceof IOException io)throw io;throw new IOException("Cast publication rejected",cause);
        }catch(RuntimeException failure){task.cancel(true);throw new IOException("Cast publication rejected",failure);}
    }
    public void cancelPending(){synchronized(gate){closing=true;for(Runnable task:writer.shutdownNow())if(task instanceof Future<?> future)future.cancel(false);}}
    @Override public void close() throws IOException{
        cancelPending();if(Thread.currentThread()==ownedThread)return;
        try{if(!writer.awaitTermination(1500,TimeUnit.MILLISECONDS))throw new IOException("Cast writer shutdown timed out");}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IOException("Cast writer shutdown interrupted",failure);}
    }
}
''')
modify(JAVA+'Channel.java',[(
 '    private volatile Socket socket;','    private volatile Socket socket;\n    private final DesktopCastWriter writer = new DesktopCastWriter();',1),(
 '    private synchronized void write(CastChannel.CastMessage message) throws IOException {\n        Socket current = socket;\n        if (current == null || cancelled) throw new IOException("Cast operation cancelled");\n        BoundedCastFrame.write(current, message);\n    }',
 '    private void write(CastChannel.CastMessage message) throws IOException {\n        writer.write(() -> {\n            Socket current = socket;\n            if (current == null || cancelled) throw new IOException("Cast operation cancelled");\n            BoundedCastFrame.write(current, message);\n        }, () -> !cancelled && socket != null, DesktopCastPublication.current());\n    }',1),(
 '        cancelled = true; closed = true;','        cancelled = true; closed = true;\n        writer.cancelPending();',1),(
 '        pings.shutdownNow(); deadlines.shutdownNow();','        pings.shutdownNow(); deadlines.shutdownNow();\n        writer.close();',1)])
cast='''package com.bilipai.desktop.cast
import com.android.purebilibili.core.plugin.CastPluginMediaRequest
import com.bilipai.desktop.player.*
import kotlinx.coroutines.*
import okhttp3.Call
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import su.litvak.chromecast.api.v2.DesktopCastPublication

public class DesktopCastMediaPublication internal constructor(private val frame: DesktopCastPublicationFrame,
    private val resolve: suspend () -> CastPluginMediaRequest) {
    internal suspend fun <T> use(block: suspend (CastPluginMediaRequest) -> T): T = withContext(frame) {
        currentCoroutineContext().ensureActive();frame.admit { Unit }
        val media=resolve();frame.admit { Unit };block(media)
    }
}
internal class DesktopCastPublicationFrame(private val publication: DesktopPlaybackPublication,
    private val source: PlaybackSource, private val stillOwned: () -> Boolean, private val callerJob: Job? = null) :
    ThreadContextElement<DesktopCastPublication.Frame?>,AbstractCoroutineContextElement(Key),DesktopCastPublication.Frame {
    companion object Key:CoroutineContext.Key<DesktopCastPublicationFrame>
    override fun updateThreadContext(context:CoroutineContext)=DesktopCastPublication.replace(this)
    override fun restoreThreadContext(context:CoroutineContext,oldState:DesktopCastPublication.Frame?){DesktopCastPublication.replace(oldState)}
    override fun isCurrent()=stillOwned()&&publication.isCurrent(source)
    override fun admit(publication:Runnable){this.publication.admit(source,stillOwned){publication.run()}}
    fun <T> admit(block:()->T):T=publication.admit(source,stillOwned,block)
    fun calls(delegate:Call.Factory):Call.Factory=publication.calls(delegate,source,stillOwned,callerJob)
}
'''
write(HERE/'prepared'/BASE/'cast/DesktopCastMediaPublication.kt',cast)
modify(BASE+'cast/DesktopCastController.kt',[(
 '    suspend fun cast(route: CastPluginRoute, media: suspend () -> CastPluginMediaRequest?): Result<Unit> = execute {\n        val request = media() ?: error("当前媒体没有可投屏的播放地址")\n        plugin.cast(context, route, request).getOrThrow()\n    }',
 '    suspend fun cast(route: CastPluginRoute, media: suspend () -> DesktopCastMediaPublication?): Result<Unit> = execute {\n        val publication = media() ?: error("当前媒体没有可投屏的播放地址")\n        publication.use { request -> plugin.cast(context, route, request).getOrThrow() }\n    }',1),(
 '    suspend fun cast(route: CastPluginRoute, media: CastPluginMediaRequest): Result<Unit> = cast(route) { media }',
 '    /** Explicit bare/local compatibility; Root account sources use the required publication wrapper. */\n    suspend fun cast(route: CastPluginRoute, media: CastPluginMediaRequest): Result<Unit> = execute { plugin.cast(context, route, media).getOrThrow() }',1)])
for leaf in ['DesktopCastDialog.kt','DesktopGoogleCastDialog.kt']:
 modify(BASE+'cast/'+leaf,[('media: suspend () -> CastPluginMediaRequest?','media: suspend () -> DesktopCastMediaPublication?',1)])
google=bodies[BASE+'cast/DesktopGoogleCastDialog.kt']
for old in ['controller.cast(context, route, request)','plugin.cast(context, route, request)']:
 if old in google:modify(BASE+'cast/DesktopGoogleCastDialog.kt',[(old,'request.use { '+old.replace('request','it')+' }',1)]);break
else:raise AssertionError('Google actual media call anchor')
modify(BASE+'cast/DesktopCastPlatform.kt',[(
 '    val call = client.newCall(request)',
 '    val frame = currentCoroutineContext()[DesktopCastPublicationFrame]\n    val call = (frame?.calls(client) ?: client).newCall(request)',1)])
modify(BASE+'cast/DesktopCastProxySessions.kt',[(
 'internal class DesktopCastProxyTarget(val url: HttpUrl, val headers: Map<String, String>) {',
 'internal class DesktopCastProxyTarget(val url: HttpUrl, val headers: Map<String, String>, val publication: DesktopCastPublicationFrame?) {',1),(
 '        registrations[id] = DesktopCastProxyTarget(url, snapshot)',
 '        val frame = su.litvak.chromecast.api.v2.DesktopCastPublication.current() as? DesktopCastPublicationFrame\n        val publish = { registrations[id] = DesktopCastProxyTarget(url, snapshot, frame) }\n        if (frame == null) publish() else frame.admit(publish)',1)])
modify(BASE+'cast/DesktopCastMediaResolver.kt',[(
 '            val response = api.getTvPlayUrl(AppSignUtils.signForTvLogin(params))',
 '            val receipt = source.authorizationReceipt ?: throw kotlinx.coroutines.CancellationException("Missing cast authorization")\n            val authorization = repository.capturePlaybackAuthorization(receipt.accountEpoch) { repository.isPlaybackSourceCurrent(source) }\n            if (authorization.receipt != receipt) throw kotlinx.coroutines.CancellationException("Cast authorization retired")\n            val ownedApi = repository.ownedPlaybackService(BilibiliApi::class.java, authorization) { repository.isPlaybackSourceCurrent(source) }\n            val response = ownedApi.getTvPlayUrl(AppSignUtils.signForTvLogin(params))',1)])
shell=BASE+'DesktopShell.kt';s=bodies[shell];begin=s.index('    suspend fun currentCastMedia():');end=s.index('    fun prepareUpdate(',begin);old=s[begin:end]
new=old.replace('com.android.purebilibili.core.plugin.CastPluginMediaRequest {','com.bilipai.desktop.cast.DesktopCastMediaPublication {',1)
new=new.replace('        val media = when (owner) {','''        val callerJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        val owned = {
            val latest = player?.currentSourceSnapshot()
            callerJob?.isCancelled != true && scope.isActive && !isClosing() && !activatingUpdate && epoch == repository.sessionEpoch &&
                nativeSource?.sourceVersion == latest?.sourceVersion && nativeSource?.source == latest?.source &&
                retainedMedia.current === owner && !systemTargetAudio &&
                (owner != null || playback.state.value.details?.bvid == current.details?.bvid && playback.state.value.currentPart == current.currentPart) &&
                (owner !== retainedMedia.external || retainedMedia.external.authorizationCurrent)
        }
        val resolvedVideo = if (owner == null) {
            val info = current.details ?: error("请先打开需要投屏的视频")
            if (nativeSource == null) repository.playback(info, current.currentPart, current.quality)
            else playback.currentCastSource(nativeSource.sourceVersion) ?: error("当前视频播放源已经变化，请重新开始投屏")
        } else null
        val admittedSource = when (owner) {
            retainedMedia.bangumi -> retainedMedia.bangumi.playback?.source?.toNativePlayback() ?: error("剧集播放源已经变化")
            null -> resolvedVideo!!.toNativePlayback()
            else -> nativeSource?.source?.copy(primaryAccountEpoch = epoch) ?: error("当前播放源已经变化")
        }
        val frame = com.bilipai.desktop.cast.DesktopCastPublicationFrame(
            com.bilipai.desktop.player.DesktopRepositoryPlaybackPublication(repository, allowPrimaryAccountSource = true), admittedSource, owned, callerJob)
        return com.bilipai.desktop.cast.DesktopCastMediaPublication(frame) {
        val media = when (owner) {''',1)
a=new.index('                val source = if (nativeSource == null) repository.playback(');b=new.index('                castResolver.video(',a)
new=new[:a]+'                val source = resolvedVideo!!\n'+new[b:]
new=new.replace('        return media\n    }','        media\n        }\n    }',1)
modify(shell,[(old,new,1),(
 '    fun prepareUpdate(update: WindowsUpdate, manual: Boolean) {',
 '    val castMediaFactory: suspend () -> com.bilipai.desktop.cast.DesktopCastMediaPublication? = { currentCastMedia() }\n    fun prepareUpdate(update: WindowsUpdate, manual: Boolean) {',1),(
 'media = { currentCastMedia() }','media = castMediaFactory',2)])
proxyrel='desktop/build/generated/cast/com/android/purebilibili/feature/cast/LocalProxyServer.kt'
proxy=read(REPO/proxyrel)
generated_hunks=[dict(before='val upstreamResponse = client.newCall(upstreamRequest).execute()',after='val upstreamResponse = (registration?.publication?.calls(client) ?: client).newCall(upstreamRequest).execute()',occurrences=1),dict(before='            manifestStore[key] = manifest',after='            val frame = su.litvak.chromecast.api.v2.DesktopCastPublication.current() as? com.bilipai.desktop.cast.DesktopCastPublicationFrame\n            if (frame == null) manifestStore[key] = manifest else frame.admit { manifestStore[key] = manifest }',occurrences=1)]
for h in generated_hunks:assert proxy.count(h['before'])==h['occurrences'];proxy=proxy.replace(h['before'],h['after'])
write(HERE/'prepared/generated/com/android/purebilibili/feature/cast/LocalProxyServer.kt',proxy)
write(HERE/'cast-generated-required.json',json.dumps(dict(proxySource=proxyrel,hunks=generated_hunks),ensure_ascii=False,indent=2)+'\n')
