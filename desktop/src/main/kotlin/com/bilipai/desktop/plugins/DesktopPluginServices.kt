package com.bilipai.desktop.plugins

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.SearchType
import com.android.purebilibili.data.model.response.SearchUpItem
import com.bilipai.desktop.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlin.coroutines.CoroutineContext
import okhttp3.OkHttpClient
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.IdentityHashMap

/** Original plugin public API calls use the active desktop session without copied credentials. */
object DesktopPluginRepositoryBinding {
    private data class Binding(val repository: DesktopRepository, val community: DesktopCommunityRepository,
        val discovery: DesktopDiscoveryRepository, val api: BilibiliApi)
    @Volatile private var binding: Binding? = null

    fun initialize(repository: DesktopRepository, community: DesktopCommunityRepository, discovery: DesktopDiscoveryRepository) {
        val api = Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(repository.httpClient)
            .addConverterFactory(Json { ignoreUnknownKeys = true; coerceInputValues = true }.asConverterFactory("application/json".toMediaType()))
            .build().create(BilibiliApi::class.java)
        binding = Binding(repository, community, discovery, api)
    }
    private fun active(): Binding = binding ?: error("插件账号服务尚未初始化")
    val api: BilibiliApi get() = active().api
    val playbackClient: OkHttpClient get() = active().repository.playbackHttpClient
    internal fun recommendationContext(): DesktopPluginContext = active().discovery.recommendationContext()

    // The original enrichment caller consumes only the first element; paging remains owned by Community.
    suspend fun searchUp(keyword: String, page: Int): Result<Pair<List<SearchUpItem>, Unit>> = try {
        val result = active().community.typedSearch(keyword, SearchType.UP, page).result as CommunitySearchResult.Users
        Result.success(result.data.result.orEmpty().map { it.cleanupFields() } to Unit)
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: Exception) { Result.failure(error) }
}

object DesktopPluginApplicationScope {
    private var scope = DesktopPluginScopeRegistry.create("application", Dispatchers.IO)
    val ioScope: CoroutineScope get() = synchronized(this) {
        if (!scope.isActive) scope = DesktopPluginScopeRegistry.create("application", Dispatchers.IO)
        scope
    }
    fun close() = synchronized(this) { scope.cancel() }
}

/** Original plugin jobs remain supervised; restore waits for cancellation and pending writes. */
object DesktopPluginScopeRegistry {
    private val lock = Any()
    private val scopes = mutableListOf<CoroutineScope>()
    private var stopped = false
    fun create(owner: String, dispatcher: CoroutineContext): CoroutineScope = synchronized(lock) {
        val job = SupervisorJob()
        if (stopped) job.cancel()
        CoroutineScope(job + dispatcher).also { scopes += it }
    }
    suspend fun shutdown() {
        val jobs = synchronized(lock) { stopped = true; scopes.mapNotNull { it.coroutineContext[Job] } }
        jobs.forEach { it.cancel() }
        jobs.forEach { it.cancelAndJoin() }
    }
}

object DesktopPluginResource {
    fun open(name: String): java.io.InputStream {
        require(name == "plugin/cdn_region_catalog.json") { "插件资源不存在" }
        val bytes = DesktopPluginResource::class.java.classLoader.getResourceAsStream(name)?.use {
            val raw = it.readNBytes(1_048_577)
            require(raw.size <= 1_048_576) { "插件资源超过大小限制" }
            raw.toString(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
        } ?: error("缺少插件资源：$name")
        val expected = DesktopPluginAssetHashes.hashes["app/src/main/res/raw/cdn_region_catalog.json"] ?: error("插件资源未登记")
        val actual = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(actual == expected) { "插件资源校验失败：$name" }
        return java.io.ByteArrayInputStream(bytes)
    }
}

/** File primitive for the original Android AtomicFile-based feed validator store. */
class DesktopPluginAtomicFile(val baseFile: File) {
    private val pending = IdentityHashMap<FileOutputStream, Path>()
    fun openRead(): FileInputStream = FileInputStream(baseFile)

    @Synchronized fun startWrite(): FileOutputStream {
        val parent = baseFile.toPath().toAbsolutePath().parent
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, ".plugin-atomic-", ".tmp")
        return try { FileOutputStream(temp.toFile()).also { pending[it] = temp } }
        catch (error: Throwable) { Files.deleteIfExists(temp); throw error }
    }

    @Synchronized fun finishWrite(stream: FileOutputStream) {
        val temp = pending[stream] ?: error("未知的原子写入事务")
        try {
            stream.flush(); stream.fd.sync(); stream.close()
            try { Files.move(temp, baseFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (unsupported: AtomicMoveNotSupportedException) { Files.move(temp, baseFile.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            pending.remove(stream)
        } catch (error: Throwable) { failWrite(stream); throw error }
    }

    @Synchronized fun failWrite(stream: FileOutputStream) {
        runCatching { stream.close() }
        pending.remove(stream)?.let { Files.deleteIfExists(it) }
    }
}
