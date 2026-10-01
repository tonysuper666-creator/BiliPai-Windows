package com.bilipai.desktop.player.cache

import com.bilipai.desktop.player.DesktopNativePlaybackPublication
import com.bilipai.desktop.player.PlaybackSource
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.xml.sax.InputSource

/** Sole byte-cache/platform ingress. Construct on application IO, not under UI/account locks.
 * Its only outbound network factory comes from captured same-Repository admissions. */
internal class DesktopMediaByteCache(root: Path, rootScope: CoroutineScope,
    maxBytes: Long = 128L * 1024 * 1024) : AutoCloseable {
    internal val store = DesktopMediaByteSpanStore(root, maxBytes)
    internal val scope = CoroutineScope(rootScope.coroutineContext + SupervisorJob(rootScope.coroutineContext[Job]))
    private val closed = AtomicBoolean(false)
    internal val upstream = AtomicLong()
    internal val cacheReads = AtomicLong()
    private val registrations = ConcurrentHashMap<String, DesktopMediaByteLease>()
    private val resourceLocks = Array(64) { Mutex() }
    private val server = Loopback()
    init { server.start(30_000, true) }

    fun bind(admission: DesktopMediaByteAdmission, tracks: List<DesktopMediaByteTrack>): DesktopBoundMediaByteCache {
        check(!closed.get()); admission.check(); require(tracks.isNotEmpty() && tracks.size <= 64)
        return DesktopBoundMediaByteCache(DesktopMediaByteLease(this, admission), tracks)
    }

    internal fun register(lease: DesktopMediaByteLease): String {
        check(!closed.get()); lease.check()
        registrations.entries.filter { !it.value.current() }.forEach { (token, old) ->
            registrations.remove(token, old); old.close()
        }
        require(registrations.size < 64) { "Too many live media registrations" }
        val token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
        registrations[token] = lease
        lease.token = token
        return "http://127.0.0.1:${server.listeningPort}/media/$token"
    }
    internal fun unregister(lease: DesktopMediaByteLease) { lease.token?.let { registrations.remove(it, lease) } }
    internal fun cleanupLater(lease: DesktopMediaByteLease) { scope.launch(Dispatchers.IO) { lease.close() } }
    internal fun mutex(id: String) = resourceLocks[(id.hashCode() and Int.MAX_VALUE) % resourceLocks.size]
    fun stats(): DesktopMediaByteCacheStats {
        val (bytes, spans) = store.stats()
        return DesktopMediaByteCacheStats(upstream.get(), cacheReads.get(), bytes, spans, registrations.size)
    }
    suspend fun clear(admission: DesktopMediaByteAdmission) {
        admission.check()
        var retired = emptyList<DesktopMediaByteLease>()
        admission.commit { retired=registrations.values.toList();retired.forEach { it.markRetired() }; registrations.clear() }
        retired.forEach { it.close() }
        // cancellation and join happen after account/entry admission; no IO in the commit above.
        scope.coroutineContext[Job]?.children?.toList()?.joinAll()
        withContext(Dispatchers.IO) { admission.check(); store.clear() }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        registrations.values.forEach { it.close() }; registrations.clear()
        server.stop(); scope.cancel(); store.close()
    }

    private inner class Loopback : NanoHTTPD("127.0.0.1", 0) {
        private val clients = ConcurrentHashMap.newKeySet<ClientHandler>()
        private val executor = ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(16), { task -> Thread(task, "BiliPai-media-loopback").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy())
        init {
            setAsyncRunner(object : AsyncRunner {
                override fun exec(handler: ClientHandler) {
                    clients += handler
                    try { executor.execute(handler) } catch (_: java.util.concurrent.RejectedExecutionException) { clients -= handler; handler.close() }
                }
                override fun closed(handler: ClientHandler) { clients -= handler }
                override fun closeAll() { clients.toList().forEach { it.close() }; clients.clear(); executor.shutdownNow() }
            })
        }
        override fun useGzipWhenAccepted(response: Response): Boolean = false
        override fun serve(session: IHTTPSession): Response {
            if (session.remoteIpAddress !in setOf("127.0.0.1", "0:0:0:0:0:0:0:1", "::1") ||
                session.headers["host"] != "127.0.0.1:$listeningPort") return fail(Response.Status.FORBIDDEN)
            if (session.method != Method.GET && session.method != Method.HEAD) return fail(Response.Status.METHOD_NOT_ALLOWED)
            val path = session.uri.split('/').filter(String::isNotEmpty)
            if (path.size != 3 || path[0] != "media" || !path[1].matches(Regex("[a-f0-9]{64}"))) return fail(Response.Status.NOT_FOUND)
            val lease = registrations[path[1]] ?: return fail(Response.Status.GONE)
            val job = SupervisorJob(scope.coroutineContext[Job])
            try {
                lease.check()
                if (path[2] == "manifest.mpd") {
                    val bytes = lease.manifest ?: return fail(Response.Status.NOT_FOUND)
                    return newFixedLengthResponse(Response.Status.OK, "application/dash+xml", ByteArrayInputStream(bytes), bytes.size.toLong()).apply { setGzipEncoding(false) }
                }
                val index = path[2].toIntOrNull() ?: return fail(Response.Status.NOT_FOUND)
                val track = lease.tracks.getOrNull(index) ?: return fail(Response.Status.NOT_FOUND)
                val metadata = runBlocking(job + Dispatchers.IO) { lease.metadata(track) }
                val requestRange = session.headers["range"]
                val range = parseMediaRange(requestRange, metadata.total)
                if (range == null) return fail(Response.Status.RANGE_NOT_SATISFIABLE).apply { addHeader("Content-Range", "bytes */${metadata.total}") }
                val stream = if (session.method == Method.HEAD) ByteArrayInputStream(byteArrayOf())
                    else lease.stream(track, metadata, range.first, range.second, job)
                val status = if (requestRange == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT
                return newFixedLengthResponse(status, "application/octet-stream", stream, range.second).apply {
                    setGzipEncoding(false); addHeader("Accept-Ranges", "bytes")
                    if (requestRange != null) addHeader("Content-Range", "bytes ${range.first}-${range.first + range.second - 1}/${metadata.total}")
                    if (session.method == Method.HEAD) job.complete()
                }
            } catch (_: CancellationException) { job.cancel(); return fail(Response.Status.GONE) }
            catch (_: Exception) { job.cancel(); return fail(Response.Status.SERVICE_UNAVAILABLE) }
        }
        private fun fail(status: Response.Status) = newFixedLengthResponse(status, MIME_PLAINTEXT, "Media transport unavailable").apply { closeConnection(true) }
    }
}

internal data class DesktopMediaResource(val id: String, val total: Long, val persistent: Boolean,
    val validatorName: String?, val validatorValue: String?)

/** One source's mutable ADMISSION lease, never mutable latest-account credentials.
 * Its transport Job stays Root-owned while exact native publication is transferred. */
internal class DesktopMediaByteLease(private val cache: DesktopMediaByteCache, initial: DesktopMediaByteAdmission) : AutoCloseable {
    private data class Frame(val admission: DesktopMediaByteAdmission, val version: Long?, val publication: DesktopNativePlaybackPublication?, val retired: Boolean = false)
    private val frame = AtomicReference(Frame(initial, null, null))
    private val closed = AtomicBoolean(false)
    private val cleanupDone = AtomicBoolean(false)
    private val leaseJob = SupervisorJob(cache.scope.coroutineContext[Job])
    private val metadata = ConcurrentHashMap<DesktopMediaByteTrack, DesktopMediaResource>()
    private val ownerHooks = ConcurrentHashMap.newKeySet<DisposableHandle>()
    internal var tracks: List<DesktopMediaByteTrack> = emptyList()
    internal var manifest: ByteArray? = null
    internal var token: String? = null
    init { watchOwner(frame.get()) }
    @OptIn(InternalCoroutinesApi::class)
    private fun watchOwner(value: Frame) {
        ownerHooks.forEach(DisposableHandle::dispose);ownerHooks.clear()
        ownerHooks += value.admission.ownerJob.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null && frame.compareAndSet(value,value.copy(retired=true))) {
                markRetired();cache.cleanupLater(this)
            }
        }
    }
    fun current(): Boolean = runCatching { check(); true }.getOrDefault(false)
    fun check() { val value=frame.get();if (closed.get()||value.retired) throw CancellationException("Media byte lease retired"); leaseJob.ensureActive(); value.admission.check() }
    internal fun checkReceipt(source: PlaybackSource) { check();require(source.authorizationReceipt==frame.get().admission.receipt) }
    internal fun prepareRegistration(values: List<DesktopMediaByteTrack>): String {
        check();require(token==null);tracks=values.toList();return cache.register(this)
    }
    fun attach(version: Long, publication: DesktopNativePlaybackPublication, admission: DesktopMediaByteAdmission) {
        require(version > 0); admission.check()
        val old = frame.get(); require(old.admission.receipt == admission.receipt && old.admission.persistentNamespace == admission.persistentNamespace)
        check(); val next = Frame(admission, version, publication)
        check(frame.compareAndSet(old, next)) { "Media byte lease changed" }; watchOwner(next)
    }
    fun adopt(version: Long, oldPublication: DesktopNativePlaybackPublication,
        replacement: DesktopNativePlaybackPublication, admission: DesktopMediaByteAdmission): Boolean {
        admission.check(); val old = frame.get()
        if (closed.get() || old.retired || old.version != version || old.publication !== oldPublication ||
            old.admission.receipt != admission.receipt || old.admission.persistentNamespace != admission.persistentNamespace) return false
        val next = Frame(admission, version, replacement)
        if (!frame.compareAndSet(old,next)) return false
        watchOwner(next); return true
    }
    fun retire(version: Long) { if (frame.get().version == version) { markRetired();cache.cleanupLater(this) } }
    internal fun markRetired() { closed.set(true);cache.unregister(this) }
    override fun close() {
        markRetired();if (!cleanupDone.compareAndSet(false,true)) return
        leaseJob.cancel(); ownerHooks.forEach(DisposableHandle::dispose); ownerHooks.clear(); cache.unregister(this)
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun <T> origin(track: DesktopMediaByteTrack, url: String, start: Long, length: Long,
        consume: (Response, DesktopMediaByteAdmission) -> T): T {
        currentCoroutineContext().ensureActive(); check(); require(url in track.urls)
        val ioJob = currentCoroutineContext()[Job] ?: error("Media byte caller Job required")
        val admitted = frame.get().admission
        val request = Request.Builder().url(url).apply {
            track.headers.forEach { (name,value) -> header(name,value) }
            header("Range", "bytes=$start-${DesktopMediaByteSpanStore.checkedEnd(start,length)-1}")
            header("Accept-Encoding", "identity")
            tag(DesktopMediaOriginHeaders::class.java, DesktopMediaOriginHeaders(track.headers, track.urls.map { it.toHttpUrl().origin() }.toSet()))
        }.build()
        val call = admitted.calls(::current, ioJob).newCall(request)
        val cancel = { _: Throwable? -> call.cancel() }
        val requestHook = ioJob.invokeOnCompletion(onCancelling=true,invokeImmediately=true,handler=cancel)
        val leaseHook = leaseJob.invokeOnCompletion(onCancelling=true,invokeImmediately=true,handler=cancel)
        try {
            return call.execute().use { response ->
                ioJob.ensureActive(); check()
                val encoding = response.header("Content-Encoding")
                if (encoding != null && !encoding.equals("identity",true)) throw IOException("Media byte encoding is not identity")
                if (response.code != 206) throw IOException("Origin did not honor media byte range")
                consume(response, frame.get().admission).also { ioJob.ensureActive(); check() }
            }
        } finally { requestHook.dispose(); leaseHook.dispose(); call.cancel() }
    }

    suspend fun metadata(track: DesktopMediaByteTrack): DesktopMediaResource {
        metadata[track]?.let { check(); return it }
        val key = resourceBase(track)
        return cache.mutex(key).withLock {
            metadata[track] ?: origin(track,track.url,0,1) { response, admission ->
                val content = parseContentRange(response.header("Content-Range"))
                if (content == null || content.first != 0L || content.second != 0L || content.third <= 0) throw IOException("Invalid media metadata range")
                val body = response.body ?: throw IOException("Media body missing")
                body.byteStream().use { input ->
                    check(); if (input.read() < 0 || input.read() >= 0) throw IOException("Media metadata byte count mismatch")
                    cache.upstream.incrementAndGet(); check()
                }
                val etag = response.header("ETag")?.takeIf { !it.startsWith("W/") }
                val modified = response.header("Last-Modified")
                val validator = etag ?: modified
                val persistent = validator != null
                val result = DesktopMediaResource(mediaDigest(key + "\n" + (validator ?: UUID.randomUUID().toString()) + "\n" + content.third), content.third, persistent,
                    if(etag!=null)"ETag" else if(modified!=null)"Last-Modified" else null,validator)
                admission.commit { check(); metadata[track] = result }; result
            }
        }
    }
    private fun resourceBase(track: DesktopMediaByteTrack): String {
        val sensitive = track.headers.filterKeys { it.lowercase() in SENSITIVE_HEADERS }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        return mediaDigest(frame.get().admission.persistentNamespace + "\n" + track.cacheKey + "\n" + track.representation + "\n" + sensitive.entries.joinToString { it.key.lowercase() + "=" + it.value })
    }

    suspend fun ensureRange(track: DesktopMediaByteTrack, meta: DesktopMediaResource, position: Long, length: Long, url: String = track.url) {
        val end = DesktopMediaByteSpanStore.checkedEnd(position,length)
        require(end <= meta.total)
        val key = resourceBase(track)
        cache.mutex(key).withLock {
            var cursor = position
            while (cursor < end) {
                currentCoroutineContext().ensureActive(); check()
                val size = minOf(BLOCK_BYTES, end - cursor)
                if (!cache.store.covers(meta.id,cursor,size)) writeSpan(track,meta,url,cursor,size)
                cursor += size
            }
        }
    }
    private suspend fun writeSpan(track: DesktopMediaByteTrack, meta: DesktopMediaResource, url: String, start: Long, length: Long) {
        val writeJob=currentCoroutineContext()[Job]?:error("Media write Job required")
        val reservation = cache.store.reserve(length)
        try {
            origin(track,url,start,length) { response, _ ->
                val range = parseContentRange(response.header("Content-Range"))
                if (range != Triple(start,start+length-1,meta.total)) throw IOException("Origin interval mismatch")
                if(meta.validatorName!=null&&response.header(meta.validatorName)!=meta.validatorValue)throw IOException("Media resource validator changed")
                cache.store.begin(reservation,meta.id,start,length,meta.persistent).use { output ->
                    val input = response.body?.byteStream() ?: throw IOException("Media body missing")
                    val bytes = ByteArray(64*1024); var left = length
                    while (left > 0) {
                        writeJob.ensureActive();check(); val n = input.read(bytes,0,minOf(bytes.size.toLong(),left).toInt())
                        if (n <= 0) throw IOException("Origin media interval truncated")
                        writeJob.ensureActive();check(); output.write(bytes,0,n);writeJob.ensureActive();check(); left -= n; cache.upstream.addAndGet(n.toLong())
                    }
                    if (input.read() != -1) throw IOException("Origin interval longer than advertised")
                    output.flush(); check()
                }
            }
            currentCoroutineContext().ensureActive(); check()
            cache.store.publish(reservation,meta.id,start,length) { short -> frame.get().admission.commit { check(); short() } }
        } catch (failure: Throwable) { cache.store.discard(reservation); throw failure }
    }
    internal fun requireCovered(meta: DesktopMediaResource, start: Long, length: Long) {
        check();if(!cache.store.covers(meta.id,start,length))throw IOException("Prefetch interval is not completely committed")
    }

    fun stream(track: DesktopMediaByteTrack, meta: DesktopMediaResource, start: Long, length: Long, requestJob: CompletableJob): InputStream = object : InputStream() {
        private var position = start
        private val end = start + length
        private var reader: DesktopMediaByteSpanStore.Reader? = null
        private var done = false
        private var leaseClose: DisposableHandle? = null
        init { leaseClose=leaseJob.invokeOnCompletion { if (it != null) close() };if(done)leaseClose?.dispose() }
        override fun read(): Int { val one=ByteArray(1); return if(read(one,0,1)<0)-1 else one[0].toInt() and 255 }
        override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
            if (done) throw IOException("Media transport stream closed")
            require(offset >= 0 && count >= 0 && offset <= bytes.size-count)
            if(count==0)return 0
            requestJob.ensureActive(); check(); if(position>=end)return -1
            if(reader?.remaining==0L){ reader?.close();reader=null }
            if(reader==null) {
                reader=cache.store.open(meta.id,position)
                if(reader==null) {
                    runBlocking(requestJob+Dispatchers.IO) { ensureRange(track,meta,position,minOf(BLOCK_BYTES,end-position)) }
                    reader=cache.store.open(meta.id,position) ?: throw IOException("Committed media interval unavailable")
                }
            }
            val n=reader!!.read(bytes,offset,minOf(count.toLong(),end-position,64*1024L).toInt())
            requestJob.ensureActive();check()
            frame.get().admission.commit { check();cache.cacheReads.addAndGet(n.toLong()) }
            position+=n;return n
        }
        override fun close() {
            if(done)return
            done=true;try { reader?.close();reader=null } finally { leaseClose?.dispose();requestJob.cancel() }
        }
    }
    companion object { const val BLOCK_BYTES = 1024L*1024L }
}

internal class DesktopBoundMediaByteCache(private val lease: DesktopMediaByteLease, private val tracks: List<DesktopMediaByteTrack>) {
    suspend fun prefetchRange(url: String, cacheKey: String, position: Long, length: Long, headers: Map<String,String>) = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive(); lease.check()
        val track = tracks.firstOrNull { url in it.urls && it.cacheKey == cacheKey } ?: throw IOException("Range is not in captured media tracks")
        require(headers.all { (name,value) -> track.headers.entries.any { it.key.equals(name,true) && it.value==value } })
        if(length > 128L*1024*1024-128)throw IOException("Prefetch interval exceeds byte cache capacity")
        val metadata=lease.metadata(track)
        require(DesktopMediaByteSpanStore.checkedEnd(position,length)<=metadata.total)
        lease.ensureRange(track,metadata,position,length,url)
        lease.requireCovered(metadata,position,length)
    }
    suspend fun prefetchHeadRange(url: String, cacheKey: String, upperLimit: Long, headers: Map<String,String>) {
        require(upperLimit>0)
        val track=tracks.firstOrNull { url in it.urls&&it.cacheKey==cacheKey }?:throw IOException("Head track not captured")
        val total=withContext(Dispatchers.IO){lease.metadata(track).total}
        prefetchRange(url,cacheKey,0,minOf(upperLimit,total),headers)
    }
    fun prepareNativeTransport(source: PlaybackSource, fullAdaptiveManifest: String? = null): DesktopNativeMediaTransport {
        lease.checkReceipt(source)
        require(source.authorizationReceipt != null) { "Online byte cache requires captured receipt" }
        val base=lease.prepareRegistration(tracks)
        val byUrl=tracks.mapIndexed { index,track -> track.urls.associateWith { "$base/$index" } }.flatMap { it.entries }.associate { it.key to it.value }
        val mode: DesktopNativeMediaTransportMode
        val video: String; val audio: String?; val progressive: List<String>
        when {
            fullAdaptiveManifest != null -> {
                mode=DesktopNativeMediaTransportMode.ADAPTIVE_MPD
                lease.manifest=rewriteMediaManifest(fullAdaptiveManifest,byUrl)
                video="$base/manifest.mpd";audio=null;progressive=emptyList()
            }
            source.progressiveSegments.isNotEmpty() -> {
                mode=DesktopNativeMediaTransportMode.PROGRESSIVE_EDL
                progressive=source.progressiveSegments.map { byUrl[it.url] ?: error("Progressive track missing") }
                video=progressive.first();audio=null
            }
            else -> {
                mode=DesktopNativeMediaTransportMode.SEPARATE_DASH
                video=byUrl[source.videoUrl] ?: error("Native video track missing")
                audio=source.audioUrl?.let { byUrl[it] ?: error("Native audio track missing") };progressive=emptyList()
            }
        }
        return DesktopNativeMediaTransport(lease,mode,video,audio,progressive,mediaSourceFingerprint(source))
    }
}

internal fun parseContentRange(value: String?): Triple<Long,Long,Long>? {
    val parts=value?.let { Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(it.trim()) }?.groupValues ?: return null
    val start=parts[1].toLongOrNull()?:return null;val end=parts[2].toLongOrNull()?:return null;val total=parts[3].toLongOrNull()?:return null
    return if(start>=0&&end>=start&&total>end)Triple(start,end,total)else null
}
internal fun parseMediaRange(value: String?, total: Long): Pair<Long,Long>? {
    if(total<=0)return null
    if(value==null)return 0L to total
    val match=Regex("bytes=(\\d*)-(\\d*)").matchEntire(value.trim())?:return null
    val a=match.groupValues[1];val b=match.groupValues[2]
    if(a.isEmpty()) { val suffix=b.toLongOrNull()?.takeIf{it>0}?:return null; val length=minOf(suffix,total);return total-length to length }
    val start=a.toLongOrNull()?.takeIf{it<total}?:return null
    val end=if(b.isEmpty())total-1 else b.toLongOrNull()?.takeIf{it>=start}?.coerceAtMost(total-1)?:return null
    return start to (end-start+1)
}
internal fun rewriteMediaManifest(manifest: String, urls: Map<String,String>): ByteArray {
    require(manifest.toByteArray().size<=1024*1024)
    val factory=DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware=true;setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
        setFeature("http://xml.org/sax/features/external-general-entities",false);setFeature("http://xml.org/sax/features/external-parameter-entities",false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"")
    }
    val document=factory.newDocumentBuilder().parse(InputSource(StringReader(manifest)))
    require(document.documentElement.localName=="MPD")
    val bases=document.getElementsByTagNameNS("*","BaseURL");require(bases.length in 1..64)
    for(index in 0 until bases.length){val node=bases.item(index);node.textContent=urls[node.textContent.trim()]?:error("Manifest origin not captured")}
    val transforms=TransformerFactory.newInstance().apply {setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET,"")}
    return ByteArrayOutputStream().use { out -> transforms.newTransformer().transform(DOMSource(document),StreamResult(out));out.toByteArray().also{require(it.size<=1024*1024)} }
}
