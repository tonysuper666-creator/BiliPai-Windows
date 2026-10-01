package com.bilipai.desktop.ui.editorStreamProof

import com.bilipai.desktop.data.*
import com.bilipai.desktop.ui.*
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.DynamicPublishDraft
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Sink
import okio.Timeout
import okio.buffer
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.nio.file.Files
import java.nio.file.Path
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private class CountingSink(private val afterFirst: (() -> Unit)? = null) : Sink {
    var writes = 0
    var bytes = 0L
    var maximum = 0L
    override fun write(source: Buffer, byteCount: Long) {
        maximum = maxOf(maximum, byteCount); writes++; bytes += byteCount; source.skip(byteCount)
        if (writes == 1) afterFirst?.invoke()
    }
    override fun flush() {}
    override fun timeout() = Timeout.NONE
    override fun close() {}
}

fun main(args: Array<String>): Unit = runBlocking {
    val root = Path.of(args[0]).toRealPath()
    val candidate = Path.of(args[1]).toRealPath()
    val main = Path.of(args[2]).toRealPath()
    var assertions = 0
    fun prove(value: Boolean, text: String) { check(value) { text }; assertions++ }
    fun waitFor(text: String, predicate: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate()) { check(System.nanoTime() < until) { text }; Thread.sleep(5) }
    }
    val sessions = DesktopSessionStore.temporary()
    sessions.saveAccount(mapOf("SESSDATA" to "declared-stream-fixture", "bili_jct" to "declared-fixture-csrf"), AccountSummary(42L, "Declared stream fixture", ""))
    val repository = DesktopRepository(sessions)
    val requests = AtomicInteger()
    val uploadRequests = AtomicInteger()
    val uploadFields = AtomicReference(false)
    val createFields = AtomicReference(false)
    val expectedUploadBytes = AtomicReference(ByteArray(0))
    val client = repository.httpClient.newBuilder().addInterceptor { chain ->
        val request = chain.request(); check(request.url.host == "api.bilibili.com") { "Outside fixture host denied" }
        val path = request.url.encodedPath
        check(path in setOf("/x/dynamic/feed/draw/upload_bfs", "/x/dynamic/feed/create/dyn")) { "Outside fixture endpoint denied" }
        requests.incrementAndGet()
        val body = Buffer().also { request.body?.writeTo(it) }.readByteArray()
        val response = if (path.endsWith("upload_bfs")) {
            uploadRequests.incrementAndGet()
            val text = body.toString(Charsets.UTF_8)
            uploadFields.set(request.body!!.isOneShot() && "name=\"file_up\"; filename=\"selected.png\"" in text &&
                "Content-Type: image/png" in text && "\r\n\r\ndaily\r\n" in text && "\r\n\r\nnew_dyn\r\n" in text &&
                "\r\n\r\ndeclared-fixture-csrf\r\n" in text && text.contains(expectedUploadBytes.get().toString(Charsets.US_ASCII)))
            """{"code":0,"data":{"image_url":"https://fixture.invalid/uploaded.png","image_width":2,"image_height":3,"img_size":4.5}}"""
        } else {
            val text = body.toString(Charsets.UTF_8)
            createFields.set("https://fixture.invalid/uploaded.png" in text && "\"img_width\":2" in text && "\"img_height\":3" in text)
            """{"code":0,"data":{"dyn_id_str":"9001"}}"""
        }
        // Terminal declared transport: no chain.proceed, socket, DNS or credential file.
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("declared fixture")
            .body(response.toResponseBody("application/json".toMediaType())).build()
    }.build()
    fun replaceField(name: String, value: Any) = repository.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(repository, value)
    replaceField("client", client)
    val api = Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client)
        .addConverterFactory(Json { ignoreUnknownKeys = true; coerceInputValues = true }.asConverterFactory("application/json".toMediaType()))
        .build().create(BilibiliApi::class.java)
    replaceField("api", api); replaceField("visitorInitialized", true); replaceField("visitorGeneration", sessions.generation)
    val operations = DesktopDynamicCardOperations(repository)
    fun selection() = DesktopDynamicEditorSelectedImages(operations::isOwned, operations::withOwnedEditorImageAdmission)
    fun openStreams(selected: DesktopDynamicEditorSelectedImages): Int {
        val lock = selected.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(selected)
        return synchronized(lock) { (selected.javaClass.getDeclaredField("streams").apply { isAccessible = true }.get(selected) as Set<*>).size }
    }
    val actualStoreLock = sessions.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(sessions)
    fun storeBlocked() = Thread.getAllStackTraces().any { (thread, stack) ->
        thread.state == Thread.State.BLOCKED && stack.any { it.className == sessions.javaClass.name && it.methodName == "withCurrentDynamicCacheOwner" }
    }
    val cases = mutableListOf<String>()
    val payload = ByteArray(173_011) { 'a'.code.toByte() }
    val path = root.resolve("selected.png"); Files.write(path, payload)
    try {
        for ((clazz, expected) in listOf(DesktopDynamicCardOperations::class.java to candidate,
            DesktopDynamicEditorSelectedImages::class.java to candidate, DesktopSessionStore::class.java to main, DesktopRepository::class.java to main)) {
            prove(Path.of(clazz.protectionDomain.codeSource.location.toURI()).toRealPath() == expected, "declared code source: ${clazz.name}")
        }
        prove(repository.dynamicCacheSessionGuard === sessions, "actual SessionStore is sole account owner authority")
        selection().use { selected ->
            val uri = selected.accept(listOf(path)).single()
            val prepared = selected.read(uri)
            prove(openStreams(selected) == 0 && prepared.third.contentLength() == payload.size.toLong(), "preflight has exact known size and opens no image input")
            prove(prepared.third.isOneShot() && prepared.third.contentType().toString() == "image/png", "stable one-shot MIME and length")
            val replacement = ByteArray(payload.size) { 'b'.code.toByte() }; Files.write(path, replacement)
            val buffer = Buffer(); prepared.third.writeTo(buffer)
            prove(buffer.readByteArray().contentEquals(replacement), "writer opens real path at write time, no prepared whole-file cache")
            prove(openStreams(selected) == 0, "successful writer closes registered input")
            expectedUploadBytes.set(replacement)
            prove(operations.publishDynamic(DynamicPublishDraft("Declared stream publish", imageUris = listOf(uri)), selected::read).getOrThrow() == "9001", "actual candidate publish through sole stable upload body")
            prove(uploadRequests.get() == 1 && requests.get() == 2 && uploadFields.get(), "stable multipart file/name/MIME/daily/new_dyn/CSRF fields and isOneShot")
            prove(createFields.get() && openStreams(selected) == 0, "original image metadata reaches original publish payload, input closed")
        }
        cases += "known-size-no-input-preflight-and-actual-candidate-multipart-publish"

        val beforeBad = requests.get()
        for ((name, length, message) in listOf(Triple("empty.png",0L,"图片内容为空"),Triple("oversize.png",15L*1024*1024+1,"图片过大（单张最大 15MB）"))) {
            val file = root.resolve(name); RandomAccessFile(file.toFile(),"rw").use { it.setLength(length) }
            selection().use { selected ->
                val uri=selected.accept(listOf(file)).single()
                val failure=operations.publishDynamic(DynamicPublishDraft("invalid file",imageUris=listOf(uri)),selected::read).exceptionOrNull()
                prove(failure?.message==message && openStreams(selected)==0, "$name exact stable preflight error before input open")
            }
        }
        val exact=root.resolve("exact-15MiB.png");RandomAccessFile(exact.toFile(),"rw").use{it.setLength(15L*1024*1024)}
        selection().use { selected ->
            val body=selected.read(selected.accept(listOf(exact)).single()).third
            prove(body.contentLength()==15L*1024*1024 && openStreams(selected)==0,"exact 15MiB accepted without full read")
        }
        prove(requests.get()==beforeBad,"empty/over-limit preflight sends no upload or publish")
        cases += "stable-empty-over15MiB-and-exact15MiB-before-open"

        selection().use { selected ->
            val uri=selected.accept(listOf(path)).single();val currentJob=AtomicReference<Job>()
            val sink=CountingSink { prove(openStreams(selected)==1,"cancel case actual registered stream active");currentJob.get().cancel() }
            val saving=async(Dispatchers.IO,start=CoroutineStart.LAZY) {
                selected.read(uri).third.writeTo(sink.buffer())
            };currentJob.set(saving);saving.start()
            prove(runCatching { saving.await() }.exceptionOrNull() is CancellationException,"save job cancellation propagates through real writer")
            saving.join()
            prove(sink.writes==1 && sink.maximum<=64L*1024 && openStreams(selected)==0,"cancel retires next bounded slice and closes input")
            prove(operations.isOwned(),"cancel-only writer preserves account owner")
        }
        cases += "cancel-only-upload-job-after-first-bounded-slice"

        selection().use { selected ->
            val uri=selected.accept(listOf(path)).single();val body=selected.read(uri).third
            val sink=CountingSink { prove(openStreams(selected)==1,"close case actual stream registered"); selected.close() }
            prove(runCatching { body.writeTo(sink.buffer()) }.exceptionOrNull() is CancellationException,"selected-owner close aborts writer")
            prove(sink.writes==1 && openStreams(selected)==0 && operations.isOwned(),"closed owner closes real input; account remains active")
        }
        cases += "selected-owner-close-closes-real-input-and-stops-next-slice"

        selection().use { selected ->
            val uri=selected.accept(listOf(path)).single();val prepared=CountDownLatch(1);val release=CountDownLatch(1)
            val sink=CountingSink()
            val saving=async(Dispatchers.IO) {
                val body=selected.read(uri).third;prepared.countDown();check(release.await(5,TimeUnit.SECONDS));body.writeTo(sink.buffer())
            }
            prove(withContext(Dispatchers.IO){prepared.await(5,TimeUnit.SECONDS)},"captured live publishing job prepares body")
            synchronized(actualStoreLock) {
                release.countDown();waitFor("writer must block at actual SessionStore admission"){storeBlocked()}
                saving.cancel();prove(operations.isOwned() && sink.writes==0,"only job retired while actual account/selected owner stay current")
            }
            prove(runCatching{saving.await()}.exceptionOrNull() is CancellationException,"Store release rechecks cancelled publishing job inside gate")
            saving.join();prove(sink.writes==0 && openStreams(selected)==0,"cancelled Store-blocked source opens no input and consumes no bytes")
        }
        cases += "cancelled-live-job-at-actual-SessionStore-source-open-gate"

        selection().use { selected ->
            val uri=selected.accept(listOf(path)).single();val body=selected.read(uri).third
            val sink=CountingSink { sessions.saveAccount(mapOf("SESSDATA" to "declared-second-fixture", "bili_jct" to "declared-second-csrf"),AccountSummary(42L,"same MID replacement","")) }
            prove(runCatching{body.writeTo(sink.buffer())}.exceptionOrNull() is CancellationException,"same-MID changed actual SessionStore generation rejects old input")
            prove(sink.writes==1 && openStreams(selected)==0 && !operations.isOwned(),"same-MID owner transition stops next slice and closes stream")
        }
        cases += "actual-SessionStore-same-MID-replacement-retires-old-stream"
        Files.writeString(root.resolve("result.json"),"""{"passed":true,"cases":${cases.size},"assertions":$assertions,"caseNames":[${cases.joinToString(","){"\"$it\""}}],"terminalDeclaredRequests":${requests.get()},"uploads":${uploadRequests.get()},"sourceSockets":0,"outsideNetwork":false,"credentialsSerialized":false,"MainInstalled":false,"actualMain04StoreClasses":true,"HWND":false,"realChooser":false}""")
        println("PASS ${cases.size} new streaming cases / $assertions assertions; sole actual Store, candidate product overrides declared")
    } finally { client.dispatcher.executorService.shutdown();client.connectionPool.evictAll() }
}
