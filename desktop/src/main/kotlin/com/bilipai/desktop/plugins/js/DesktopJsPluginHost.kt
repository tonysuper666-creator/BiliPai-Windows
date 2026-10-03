package com.bilipai.desktop.plugins.js

import com.android.purebilibili.core.plugin.PluginCapability
import com.android.purebilibili.core.plugin.js.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class DesktopJsAuthorizedPlugin(val installed: InstalledBiliPaiJsPlugin, val approvedScriptSha256: String)
private data class ExecutionAuthority(val id: String, val revision: Long, val accountEpoch: Long,
    val capabilities: Set<PluginCapability>, val plugin: DesktopJsAuthorizedPlugin?)

/** Original schemas, expressions and storage; Windows owns permissions and the child process. */
class DesktopJsPluginHost(
    private val transport: DesktopJsWorkerProcess,
    private val root: Path,
    // A fixture may constrain URLs to its own loopback server. Production NETWORK grants allow HTTP(S).
    private val acceptHttpUrl: (String) -> Boolean = { true },
    private val ownerEpoch: (() -> Long)? = null,
) {
    private val gate = Any()
    private val executionMutex = kotlinx.coroutines.sync.Mutex()
    private val moduleCache = BiliPaiJsModuleResultCache(root.resolve("cache/bilipai_js_plugin_module_cache").toFile())
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES)
        .callTimeout(8, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS).build()
    private var installed: Map<String, DesktopJsAuthorizedPlugin> = emptyMap()
    private var revision = 0L
    private val revisionFlow = MutableStateFlow(0L)
    val executionRevision = revisionFlow.asStateFlow()
    private var accountEpoch = 0L
    private var stopped = false
    private val active = mutableMapOf<DesktopJsWorkerProcess.Execution, ExecutionAuthority>()
    private val externalLaunches = mutableSetOf<String>()

    /** The installation adapter supplies exact preview-approved hashes, never hashes from guest JS. */
    fun replaceAuthorizations(records: List<DesktopJsAuthorizedPlugin>, currentAccountEpoch: Long) {
        val updated = records.associate { authorized ->
            require(authorized.approvedScriptSha256.matches(Regex("[0-9a-f]{64}"))) { "JS 插件授权摘要无效" }
            val detached = json.decodeFromString(InstalledBiliPaiJsPlugin.serializer(),
                json.encodeToString(InstalledBiliPaiJsPlugin.serializer(), authorized.installed))
            validateBiliPaiJsPluginManifest(detached.manifest)?.let { error(it) }
            validateFilename(detached.manifest.id)
            detached.manifest.id to authorized.copy(installed = detached)
        }
        require(updated.size == records.size && updated.keys.map { it.lowercase(Locale.ROOT) }.distinct().size == updated.size)
        val old = synchronized(gate) {
            check(!stopped) { "JS 插件宿主已停止" }
            if (updated == installed && currentAccountEpoch == accountEpoch) return@synchronized emptyList()
            moduleCache.clearAll()
            installed = updated; accountEpoch = currentAccountEpoch; revision++
            revisionFlow.value = revision
            externalLaunches.forEach(ExternalMediaLaunchStore::remove); externalLaunches.clear()
            active.keys.toList()
        }
        old.forEach { it.close() }
    }

    suspend fun previewManifest(script: String): BiliPaiJsPluginManifest {
        validateScript(script)
        val authority = synchronized(gate) {
            check(!stopped) { "JS 插件宿主已停止" }
            ExecutionAuthority("preview", revision, accountEpoch, emptySet(), null)
        }
        val payload = execute(authority, script, buildBiliPaiJsPreviewExpression())
        validatePayloadDepth(payload)
        return json.decodeFromString(BiliPaiJsPluginManifest.serializer(), payload).also {
            validateBiliPaiJsPluginManifest(it)?.let { error -> throw IllegalArgumentException(error) }
            validateFilename(it.id)
            require(it.modules.size <= 128 && it.title.length <= 512 && it.description.length <= 16_384 &&
                it.modules.all { module -> module.params.size <= 128 && module.title.length <= 512 && module.params.all { param -> param.options.size <= 256 && param.title.length <= 512 } }) { "JS 插件声明超过宿主限制" }
        }
    }

    suspend fun loadModuleItems(pluginId: String, moduleId: String, paramsJson: String = "{}", expectedRevision: Long? = null): List<BiliPaiJsMediaItem> {
        require(paramsJson.toByteArray().size <= 65_536 && json.parseToJsonElement(paramsJson) is JsonObject) { "JS 插件参数无效" }
        val (authority, script, module) = synchronized(gate) {
            check(!stopped) { "JS 插件宿主已停止" }
            if (expectedRevision != null) require(expectedRevision == revision) { "JS 插件授权已经变化" }
            val authorized = installed[pluginId] ?: error("JS 插件没有已批准的安装记录")
            val record = authorized.installed
            require(record.enabled) { "JS 插件未启用" }
            val selected = record.manifest.modules.singleOrNull { it.id.ifBlank { it.functionName } == moduleId }
                ?: error("JS 插件模块不存在或标识重复")
            require(!selected.kind.equals("feed", ignoreCase = true)) { "订阅模块由原 RSS/Atom 宿主读取" }
            val source = managedScript(record)
            require(sha256(source.toByteArray(Charsets.UTF_8)) == authorized.approvedScriptSha256) { "JS 插件脚本已变化，请重新预览授权" }
            val capabilities = record.grantedCapabilities intersect record.manifest.permissions
            Triple(ExecutionAuthority(pluginId, revision, accountEpoch, capabilities, authorized), source, selected)
        }
        currentCoroutineContext().ensureActive()
        val cached = synchronized(gate) {
            requireCurrent(authority)
            managedPath(root.resolve("cache/bilipai_js_plugin_module_cache"))
            moduleCache.read(pluginId, moduleId, paramsJson, module.cacheDuration)
        }
        val payload = cached ?: execute(authority, script, buildBiliPaiJsModuleExpression(module.functionName, paramsJson))
        validatePayloadDepth(payload)
        val media = json.decodeFromString(ListSerializer(BiliPaiJsMediaItem.serializer()), payload).also(::validateMedia)
        currentCoroutineContext().ensureActive()
        synchronized(gate) {
            requireCurrent(authority)
            if (cached == null) moduleCache.write(pluginId, moduleId, paramsJson, module.cacheDuration, payload)
        }
        return media
    }


    /** Same immutable approval, source bytes, account epoch and execution revision as ordinary modules. */
    private fun captureAuthorizedExecution(expected: InstalledBiliPaiJsPlugin, expectedRevision: Long,
        capability: PluginCapability? = null, requireEnabled: Boolean = true): Pair<ExecutionAuthority, String> = synchronized(gate) {
        check(!stopped && expectedRevision == revision) { "JS 插件授权已经变化" }
        val authorized = installed[expected.manifest.id] ?: error("JS 插件没有已批准的安装记录")
        require(authorized.installed == expected && (expected.enabled || !requireEnabled)) { "JS 插件安装记录已经变化" }
        val capabilities = expected.grantedCapabilities intersect expected.manifest.permissions
        if (capability != null) require(capability in capabilities) { "JS 插件没有 ${capability.name} 授权" }
        val authority = ExecutionAuthority(expected.manifest.id, revision, accountEpoch, capabilities, authorized)
        requireCurrent(authority)
        val script = managedScript(expected)
        require(sha256(script.toByteArray(Charsets.UTF_8)) == authorized.approvedScriptSha256) { "JS 插件脚本已变化，请重新预览授权" }
        authority to script
    }

    fun withOriginalPluginAdmission(expected: InstalledBiliPaiJsPlugin, expectedRevision: Long, allowDisabled: Boolean = false, commit: () -> Unit) = synchronized(gate) {
        captureAuthorizedExecution(expected, expectedRevision, requireEnabled = !allowDisabled)
        commit()
        captureAuthorizedExecution(expected, expectedRevision, requireEnabled = !allowDisabled)
        Unit
    }

    suspend fun loadOriginalModuleItems(installed: InstalledBiliPaiJsPlugin, module: BiliPaiJsModule,
        paramsJson: String = "{}", expectedRevision: Long = executionRevision.value): Result<List<BiliPaiJsMediaItem>> = ownedJsResult {
        captureAuthorizedExecution(installed, expectedRevision)
        loadModuleItems(installed.manifest.id, module.id.ifBlank { module.functionName }, paramsJson, expectedRevision)
    }

    private suspend fun <T> ownedJsResult(block: suspend () -> T): Result<T> {
        currentCoroutineContext().ensureActive()
        return try { Result.success(block()).also { currentCoroutineContext().ensureActive() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { currentCoroutineContext().ensureActive(); Result.failure(failure) }
    }

    private fun readOriginalCache(authority: ExecutionAuthority, pluginId: String, moduleId: String,
        paramsJson: String, cacheDurationSeconds: Long): String? = synchronized(gate) {
        requireCurrent(authority)
        managedPath(root.resolve("cache/bilipai_js_plugin_module_cache"))
        moduleCache.read(pluginId, moduleId, paramsJson, cacheDurationSeconds)
    }
    private fun writeOriginalCache(authority: ExecutionAuthority, pluginId: String, moduleId: String,
        paramsJson: String, cacheDurationSeconds: Long, payload: String) = synchronized(gate) {
        requireCurrent(authority)
        managedPath(root.resolve("cache/bilipai_js_plugin_module_cache"))
        moduleCache.write(pluginId, moduleId, paramsJson, cacheDurationSeconds, payload)
    }


    suspend fun loadDetailItems(
        installed: InstalledBiliPaiJsPlugin,
        link: String,
        expectedRevision: Long = executionRevision.value
    ): Result<List<BiliPaiJsMediaItem>> = ownedJsResult {
        val (authority, script) = captureAuthorizedExecution(installed, expectedRevision, null)
        val functionName = installed.manifest.detailFunctionName
        if (functionName.isBlank()) {
            throw IllegalArgumentException("插件未声明详情函数（detailFunctionName）")
        }
        val cacheKey = """{"link":${Json.encodeToString(link)}}"""
        readOriginalCache(authority,
            pluginId = installed.manifest.id,
            moduleId = DETAIL_CACHE_MODULE_ID,
            paramsJson = cacheKey,
            cacheDurationSeconds = installed.manifest.detailCacheDuration
        )?.let { cachedPayload ->
            return@ownedJsResult decodeMediaItems(cachedPayload)
        }
        val expression = buildBiliPaiJsModuleExpression(
            functionName = functionName,
            paramsJson = cacheKey
        )
        val payload = execute(authority, script, expression)
        writeOriginalCache(authority,
            pluginId = installed.manifest.id,
            moduleId = DETAIL_CACHE_MODULE_ID,
            paramsJson = cacheKey,
            cacheDurationSeconds = installed.manifest.detailCacheDuration,
            payload = payload
        )
        currentCoroutineContext().ensureActive()
        synchronized(gate) { requireCurrent(authority) }
        decodeMediaItems(payload)
    }

    suspend fun loadDanmuComments(
        installed: InstalledBiliPaiJsPlugin,
        title: String,
        expectedRevision: Long = executionRevision.value
    ): Result<List<BiliPaiJsDanmuComment>> = ownedJsResult {
        val (authority, script) = captureAuthorizedExecution(installed, expectedRevision, PluginCapability.DANMAKU_STREAM)
        val functionName = installed.manifest.danmakuFunctionName
        if (functionName.isBlank()) {
            throw IllegalArgumentException("插件未声明弹幕函数（danmakuFunctionName）")
        }
        if (PluginCapability.DANMAKU_STREAM !in installed.grantedCapabilities) {
            throw SecurityException("插件未获得 DANMAKU_STREAM 弹幕流权限")
        }
        val cacheKey = """{"title":${Json.encodeToString(title)}}"""
        readOriginalCache(authority,
            pluginId = installed.manifest.id,
            moduleId = DANMAKU_CACHE_MODULE_ID,
            paramsJson = cacheKey,
            cacheDurationSeconds = installed.manifest.danmakuCacheDuration
        )?.let { cachedPayload ->
            return@ownedJsResult decodeDanmuComments(cachedPayload)
        }
        val expression = buildBiliPaiJsModuleExpression(
            functionName = functionName,
            paramsJson = cacheKey
        )
        val payload = execute(authority, script, expression)
        writeOriginalCache(authority,
            pluginId = installed.manifest.id,
            moduleId = DANMAKU_CACHE_MODULE_ID,
            paramsJson = cacheKey,
            cacheDurationSeconds = installed.manifest.danmakuCacheDuration,
            payload = payload
        )
        currentCoroutineContext().ensureActive()
        synchronized(gate) { requireCurrent(authority) }
        decodeDanmuComments(payload)
    }

    private fun decodeMediaItems(payload: String): List<BiliPaiJsMediaItem> {
        validatePayloadDepth(payload)
        return json.decodeFromString(ListSerializer(BiliPaiJsMediaItem.serializer()), payload).also(::validateMedia)
    }

    private fun decodeDanmuComments(payload: String): List<BiliPaiJsDanmuComment> {
        validatePayloadDepth(payload)
        return json.decodeFromString(ListSerializer(BiliPaiJsDanmuComment.serializer()), payload).also { comments ->
            require(comments.size <= 25_000 && comments.all { it.text.length <= 32_768 }) { "JS 插件弹幕超过宿主限制" }
        }
    }

    /** Refresh remains a command on this same approved account/plugin owner. */
    fun clearPluginCache(pluginId: String, expectedRevision: Long) = synchronized(gate) {
        check(!stopped && expectedRevision == revision) { "JS 插件授权已经变化" }
        if (ownerEpoch?.invoke()?.let { it != accountEpoch } == true)
            throw CancellationException("JS 插件账号已经变化")
        require(installed.containsKey(pluginId)) { "JS 插件不存在" }
        managedPath(root.resolve("cache/bilipai_js_plugin_module_cache"))
        moduleCache.clearPlugin(pluginId)
    }

    /** Root consumes the original full stream list; this does not claim the native header adapter exists. */
    fun createExternalLaunch(pluginId: String, item: BiliPaiJsMediaItem, selectedStreamIndex: Int = 0, expectedRevision: Long? = null): String = synchronized(gate) {
        check(!stopped)
        if (ownerEpoch?.invoke()?.let { it != accountEpoch } == true)
            throw CancellationException("JS 插件账号已经变化")
        if (expectedRevision != null) require(expectedRevision == revision) { "JS 插件授权已经变化" }
        val record = installed[pluginId]?.installed ?: error("JS 插件不存在")
        captureAuthorizedExecution(record, revision)
        require(record.enabled && PluginCapability.EXTERNAL_MEDIA_PLAYBACK in record.grantedCapabilities &&
            PluginCapability.EXTERNAL_MEDIA_PLAYBACK in record.manifest.permissions) { "JS 插件没有外部媒体播放授权" }
        val streams = resolveBiliPaiJsMediaStreams(item)
        require(streams.isNotEmpty() && streams.size <= 128) { "JS 插件媒体线路无效" }
        require(externalLaunches.size < 16) { "JS 外部播放请求超过限制" }
        require(streams.all { stream -> stream.url.length <= 8192 && '\u0000' !in stream.url && stream.headers.size <= 64 &&
            stream.headers.all { (key, value) -> key.length <= 256 && value.length <= 8192 && key.none { it < ' ' || it == ':' } && value.none { it == '\r' || it == '\n' || it == '\u0000' } } })
        ExternalMediaLaunchStore.put(item.title, item.coverUrl, streams, selectedStreamIndex,
            danmakuPluginId = record.manifest.id.takeIf { record.manifest.supportsDanmaku &&
                PluginCapability.DANMAKU_STREAM in (record.grantedCapabilities intersect record.manifest.permissions) }).also { externalLaunches += it }
    }
    fun releaseExternalLaunch(launchId: String) = synchronized(gate) {
        if (externalLaunches.remove(launchId)) ExternalMediaLaunchStore.remove(launchId)
    }

    private suspend fun execute(authority: ExecutionAuthority, script: String, expression: String): String = executionMutex.withLock {
        currentCoroutineContext().ensureActive()
        synchronized(gate) { requireCurrent(authority) }
        executeSerial(authority, script, expression)
    }
    private suspend fun executeSerial(authority: ExecutionAuthority, script: String, expression: String): String = coroutineScope {
        val callId = UUID.randomUUID().toString()
        val request = buildJsonObject {
            put("callId", callId); put("executionScript", buildBiliPaiJsExecutionScript(callId, script, expression))
        }.toString()
        val execution = synchronized(gate) {
            requireCurrent(authority)
            require(active.values.none { it.id == authority.id }) { "此 JS 插件已有运行任务" }
            transport.create().also { active[it] = authority }
        }
        val handler = Bridge(authority)
        val running = async(Dispatchers.IO) { execution.execute(request, handler, 15_000) }
        try {
            running.await().also { synchronized(gate) { requireCurrent(authority) } }
        } finally {
            execution.close()
            withContext(NonCancellable + Dispatchers.IO) {
                check(execution.awaitStopped(Duration.ofSeconds(5))) { "JS 插件子进程未退出" }
                running.cancelAndJoin()
                synchronized(gate) { active.remove(execution) }
            }
        }
    }

    private inner class Bridge(private val authority: ExecutionAuthority) : DesktopJsWorkerProcess.FrameHandler {
        private val cancelled = AtomicBoolean()
        private val http = AtomicReference<Call?>()
        private var expectedId = 1L
        private var operations = 0
        private var logBytes = 0
        private var storage: DesktopBiliPaiJsStorageBridge? = null
        // Execution-local HTML nodes. These cannot fetch URLs, write files or outlive the existing worker authority.
        private val domNodes = java.util.IdentityHashMap<org.jsoup.nodes.Element, Int>()
        private val domById = mutableMapOf<Int, org.jsoup.nodes.Element>()
        private var domDocuments = 0
        private var domOperations = 0

        override fun cancel() { cancelled.set(true); http.get()?.cancel() }
        override fun handle(workerFrame: String): DesktopJsWorkerProcess.FrameResult {
            checkLive()
            val message = json.parseToJsonElement(workerFrame).jsonObject
            return when (message["type"]?.jsonPrimitive?.content) {
                "resolved" -> DesktopJsWorkerProcess.FrameResult.complete(message.getValue("payload").jsonPrimitive.content)
                "rejected" -> error(message["message"]?.jsonPrimitive?.content?.take(2048)?.ifBlank { "JS 插件执行失败" } ?: "JS 插件执行失败")
                "failed" -> error("JS 插件执行失败（${message["category"]?.jsonPrimitive?.content?.take(64) ?: "worker"}）")
                "bridge" -> {
                    val id = message.getValue("id").jsonPrimitive.long
                    require(id == expectedId++) { "JS 插件桥接序号无效" }
                    val name = message.getValue("name").jsonPrimitive.content
                    val args = message.getValue("args").jsonArray.map { it.jsonPrimitive.content }
                    val reply = try {
                        if (name.startsWith("dom.")) check(++domOperations <= 8192) { "JS HTML 桥接操作超过限制" }
                        else check(++operations <= 128) { "JS 插件桥接操作超过限制" }
                        val value = when (name) {
                            "http.get", "http.post" -> request(name, args)
                            "storage.get", "storage.set", "storage.remove" -> stored(name, args)
                            "dom.parse", "dom.select", "dom.selectOne" -> dom(name, args)
                            "log.write" -> {
                                require(args.size == 1)
                                logBytes += args[0].toByteArray(Charsets.UTF_8).size
                                require(logBytes <= 32_768) { "JS 插件日志超过限制" }
                                // Guest payloads never enter the app's ordinary diagnostics.
                                null
                            }
                            else -> error("JS 插件桥接方法无效")
                        }
                        checkLive()
                        buildJsonObject { put("id", id); put("value", value?.let(::JsonPrimitive) ?: JsonNull) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { buildJsonObject { put("id", id); put("error", "JS 宿主拒绝操作（${failure.javaClass.simpleName}）") } }
                    DesktopJsWorkerProcess.FrameResult.reply(reply.toString())
                }
                else -> error("JS 插件回传类型无效")
            }
        }
        private fun checkLive() {
            if (cancelled.get()) throw CancellationException("JS 插件运行已取消")
            synchronized(gate) { requireCurrent(authority) }
        }
        private fun permit(capability: PluginCapability) {
            checkLive()
            require(capability in authority.capabilities) { "JS 插件没有 ${capability.name} 授权" }
        }
        private fun request(name: String, args: List<String>): String {
            permit(PluginCapability.NETWORK)
            require(args.size == if (name == "http.get") 2 else 3)
            require(args[0].length <= 8192 && acceptHttpUrl(args[0])) { "JS 插件请求地址无效" }
            val headerElement = json.parseToJsonElement(args.last()).jsonObject
            require(headerElement.size <= 64 && args.last().toByteArray().size <= 32_768) { "JS 插件请求头超过限制" }
            val builder = Request.Builder().url(args[0])
            require(builder.build().url.scheme in setOf("http", "https"))
            headerElement.forEach { (key, value) -> builder.addHeader(key, value.jsonPrimitive.content) }
            builder.header("User-Agent", "BiliPai JS Plugin")
            if (name == "http.post") {
                require(args[1].toByteArray().size <= 1_048_576) { "JS 插件请求体超过限制" }
                builder.post(args[1].toRequestBody("application/json; charset=utf-8".toMediaType()))
            } else builder.get()
            val call = client.newCall(builder.build())
            check(http.compareAndSet(null, call))
            try {
                checkLive()
                if (cancelled.get()) call.cancel()
                return call.execute().use { response ->
                    val body = response.body
                    require(body.contentLength() <= 1_048_576) { "JS 插件响应超过限制" }
                    val bytes = body.byteStream().readNBytes(1_048_577)
                    require(bytes.size <= 1_048_576) { "JS 插件响应超过限制" }
                    checkLive()
                    buildJsonObject {
                        put("code", response.code)
                        put("body", bytes.toString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8))
                        put("headers", buildJsonObject { response.headers.toMap().forEach { (name, value) -> put(name, value) } })
                    }.toString()
                }
            } finally { http.compareAndSet(call, null) }
        }
        private fun dom(name: String, args: List<String>): String {
            checkLive()
            val result = when (name) {
                "dom.parse" -> {
                    require(args.size == 1 && args[0].toByteArray(Charsets.UTF_8).size <= 262_144 && ++domDocuments <= 4) { "JS HTML 输入超过宿主限制" }
                    val document = org.jsoup.Jsoup.parse(args[0])
                    encodeDomNode(document, document = true)
                }
                else -> {
                    require(args.size == 2 && args[1].length <= 1024) { "JS HTML 选择器无效" }
                    val node = domById[args[0].toIntOrNull()] ?: error("JS HTML 节点已经失效")
                    val selected = node.select(args[1]).filter { it !== node }
                    require(selected.size <= 1024) { "JS HTML 选择结果超过宿主限制" }
                    if (name == "dom.selectOne") selected.firstOrNull()?.let { encodeDomNode(it) } ?: JsonNull
                    else JsonArray(selected.map { encodeDomNode(it) })
                }
            }
            checkLive()
            return result.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= 524_288) { "JS HTML 返回值超过宿主限制" } }
        }
        private fun encodeDomNode(node: org.jsoup.nodes.Element, document: Boolean = false): JsonObject {
            val id = domNodes[node] ?: (domNodes.size + 1).also {
                require(it <= 4096) { "JS HTML 节点超过宿主限制" }
                domNodes[node] = it; domById[it] = node
            }
            require(node.attributes().size() <= 128) { "JS HTML 节点属性超过宿主限制" }
            val text = domTextContent(node); val html = node.html()
            require(text.toByteArray(Charsets.UTF_8).size <= 262_144 && html.toByteArray(Charsets.UTF_8).size <= 262_144)
            return buildJsonObject {
                put("id", id); put("tagName", node.tagName()); put("textContent", text); put("innerHTML", html)
                put("attributes", buildJsonObject { node.attributes().forEach {
                    require(it.key.length <= 1024 && it.value.length <= 8192)
                    put(it.key, it.value)
                } })
                if (document) {
                    val root = node as org.jsoup.nodes.Document
                    put("title", root.title()); put("body", encodeDomNode(root.body()))
                }
            }
        }
        private fun domTextContent(node: org.jsoup.nodes.Node, depth: Int = 0): String {
            require(depth <= 64) { "JS HTML 节点层级超过宿主限制" }
            return when (node) {
                is org.jsoup.nodes.TextNode -> node.wholeText
                is org.jsoup.nodes.DataNode -> node.wholeData
                else -> node.childNodes().joinToString("") { domTextContent(it, depth + 1) }
            }
        }

        private fun stored(name: String, args: List<String>): String? = synchronized(gate) {
            permit(PluginCapability.PLUGIN_STORAGE)
            require(args.size == if (name == "storage.set") 2 else 1)
            val key = args[0]
            require(key.toByteArray().size <= 1024) { "JS 插件存储键超过限制" }
            if (key.isBlank() && name == "storage.set") return@synchronized null // original no-op
            val filename = key.safeStorageName()
            validateFilename(filename)
            val directory = managedPath(root.resolve("bilipai_js_plugin_storage/${authority.id}"))
            Files.createDirectories(directory)
            val target = managedPath(directory.resolve(filename))
            val files = Files.list(directory).use { it.toList() }
            require(files.size <= 256 && files.all { Files.isRegularFile(managedPath(it), NOFOLLOW_LINKS) }) { "JS 插件存储目录无效" }
            require(files.none { it.fileName.toString() != filename && it.fileName.toString().equals(filename, ignoreCase = true) }) { "JS 插件存储键与 Windows 文件名冲突" }
            val oldBytes = files.sumOf { Files.size(it) }
            require(oldBytes <= 524_288 && files.all { Files.size(it) <= 131_072 }) { "JS 插件存储超过限制" }
            if (name == "storage.set") {
                val bytes = args[1].toByteArray(Charsets.UTF_8).size
                val replaced = if (Files.exists(target, NOFOLLOW_LINKS)) Files.size(target) else 0
                require(bytes <= 131_072 && oldBytes - replaced + bytes <= 524_288 && (replaced > 0 || Files.exists(target) || files.size < 256)) { "JS 插件存储超过限制" }
            }
            val bridge = storage ?: DesktopBiliPaiJsStorageBridge(directory.toFile()).also { storage = it }
            when (name) {
                "storage.get" -> bridge.get(key)
                "storage.set" -> { bridge.set(key, args[1]); null }
                else -> { bridge.remove(key); null }
            }
        }
    }

    internal fun requireCurrentContext(expectedRevision: Long, expectedAccountEpoch: Long): Unit = synchronized(gate) {
        requireCurrent(ExecutionAuthority("media-image", expectedRevision, expectedAccountEpoch, emptySet(), null))
    }

    private fun requireCurrent(authority: ExecutionAuthority) {
        if (stopped || authority.revision != revision || authority.accountEpoch != accountEpoch ||
            ownerEpoch?.invoke()?.let { it != authority.accountEpoch } == true)
            throw CancellationException("JS 插件授权或账号已经变化")
        if (authority.plugin != null && installed[authority.id] != authority.plugin)
            throw CancellationException("JS 插件安装记录已经变化")
    }
    private fun managedScript(record: InstalledBiliPaiJsPlugin): String {
        val expected = managedPath(root.resolve("bilipai_js_plugins/packages/${record.manifest.id}/plugin.js"))
        require(Path.of(record.scriptPath).toAbsolutePath().normalize() == expected && Files.isRegularFile(expected, NOFOLLOW_LINKS)) { "JS 插件脚本路径无效" }
        require(Files.size(expected) <= 1_048_576) { "JS 插件脚本超过限制" }
        val bytes = Files.newInputStream(expected).use { it.readNBytes(1_048_577) }
        require(bytes.size <= 1_048_576)
        return bytes.toString(Charsets.UTF_8).also(::validateScript)
    }
    private fun managedPath(path: Path): Path {
        val directory = root.toAbsolutePath().normalize()
        val candidate = path.toAbsolutePath().normalize()
        require(candidate.startsWith(directory)) { "JS 插件文件路径越界" }
        var cursor = candidate
        while (cursor.startsWith(directory)) {
            require(!Files.isSymbolicLink(cursor)) { "JS 插件文件路径为符号链接" }
            if (Files.exists(cursor, NOFOLLOW_LINKS)) {
                require(cursor.toRealPath().startsWith(directory.toRealPath())) { "JS 插件文件路径为外部联接" }
            }
            if (cursor == directory) break
            cursor = cursor.parent
        }
        return candidate
    }
    suspend fun shutdownForRestore(): Unit = withContext(NonCancellable + Dispatchers.IO) {
        synchronized(gate) {
            stopped = true; revision++
            revisionFlow.value = revision
            externalLaunches.forEach(ExternalMediaLaunchStore::remove); externalLaunches.clear()
        }
        transport.shutdownAndJoin(Duration.ofSeconds(5))
        synchronized(gate) { active.clear() }
        client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
    }
    private fun validateScript(script: String) {
        require(script.isNotBlank() && script.toByteArray(Charsets.UTF_8).size <= 1_048_576) { "JS 插件脚本无效或超过限制" }
    }
    private fun validateMedia(items: List<BiliPaiJsMediaItem>) {
        val pending = ArrayDeque<Pair<BiliPaiJsMediaItem, Int>>()
        items.forEach { pending.add(it to 0) }
        var count = 0
        while (pending.isNotEmpty()) {
            val (item, depth) = pending.removeFirst()
            require(++count <= 4096 && depth <= 8 && item.childItems.size <= 1024 && item.streams.size <= 128 &&
                item.title.length <= 2048 && item.description.length <= 32_768) { "JS 插件媒体列表超过宿主限制" }
            require(item.streams.all { it.url.length <= 8192 && it.headers.size <= 64 && it.headers.all { (key, value) -> key.length <= 256 && value.length <= 8192 } })
            item.childItems.forEach { pending.add(it to depth + 1) }
        }
    }
    /** Bound nesting before the original recursive DTO deserializer touches a guest-controlled payload. */
    private fun validatePayloadDepth(payload: String) {
        var quoted = false; var escaped = false; var depth = 0
        for (character in payload) {
            if (quoted) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> require(++depth <= 32) { "JS 插件回传层级超过限制" }
                '}', ']' -> require(--depth >= 0) { "JS 插件回传结构无效" }
            }
        }
        require(!quoted && depth == 0) { "JS 插件回传结构无效" }
    }
    companion object {
        private const val DETAIL_CACHE_MODULE_ID = "__detail__"
        private const val DANMAKU_CACHE_MODULE_ID = "__danmaku__"
        fun scriptSha256(script: String) = sha256(script.toByteArray(Charsets.UTF_8))
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun validateFilename(value: String) {
            require(value.isNotBlank() && value != "." && value != ".." && value.none { it < ' ' || it in "<>:\"/\\|?*" } && !value.endsWith('.') && !value.endsWith(' ')) { "JS 插件 Windows 文件名无效" }
            val base = value.substringBefore('.').uppercase(Locale.ROOT)
            require(base !in setOf("CON", "PRN", "AUX", "NUL") && !base.matches(Regex("(?:COM|LPT)[1-9¹²³]"))) { "JS 插件 Windows 文件名被系统保留" }
        }
    }
}
