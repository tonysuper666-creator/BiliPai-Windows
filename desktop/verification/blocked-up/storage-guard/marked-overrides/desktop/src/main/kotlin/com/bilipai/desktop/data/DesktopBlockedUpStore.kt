package com.bilipai.desktop.data

import com.android.purebilibili.core.database.entity.BlockedUp
import com.android.purebilibili.data.repository.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.update.UpdateStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** One global original UP block model; account-scoped discovery lists are retired migration inputs. */
class DesktopBlockedUpStore(val context: DesktopPluginContext, private val clock: () -> Long = System::currentTimeMillis) {
    private val mutationLock = locks.computeIfAbsent(context.store.root.toAbsolutePath().normalize()) { Any() }
    private val source = context.store.snapshot(NAMESPACE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutableMigrationError = MutableStateFlow<String?>(null)
    val migrationError = mutableMigrationError.asStateFlow()
    val records: StateFlow<List<BlockedUp>> = BlockedPreferenceState(source) { readableRecords() }
    val mids: StateFlow<Set<Long>> = BlockedPreferenceState(source) { readableRecords().map { it.mid }.toSet() }

    private fun readableRecords(): List<BlockedUp> = try { readRecords() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) {
        mutableMigrationError.value = "本地黑名单资料无法读取，原数据保持不变；请修复文件后重新启动应用。"
        emptyList()
    }

    private fun readRecords(): List<BlockedUp> {
        val preferences = context.store.preferences(NAMESPACE)
        val raw = preferences[RECORDS] ?: run {
            require(preferences[MIGRATION] == null) { "本地黑名单迁移标记缺少资料记录" }
            return emptyList()
        }
        require(raw is JsonPrimitive && raw.isString) { "本地黑名单资料格式无效" }
        val rows = json.decodeFromString<List<BlockedUp>>(raw.content)
        require(rows.all { it.mid > 0 } && rows.map { it.mid }.distinct().size == rows.size) { "本地黑名单 UID 格式无效" }
        return rows.sortedByDescending { it.blockedAt }
    }

    fun upsert(up: BlockedUp): Unit = synchronized(mutationLock) {
        require(up.mid > 0) { "UP 主 UID 必须为正整数" }
        migrateLegacyDiscoveryMids().getOrThrow()
        persist((readRecords().filterNot { it.mid == up.mid } + up))
    }
    fun remove(mid: Long): Unit = synchronized(mutationLock) {
        require(mid > 0) { "UP 主 UID 必须为正整数" }
        migrateLegacyDiscoveryMids().getOrThrow()
        persist(readRecords().filterNot { it.mid == mid })
    }
    fun import(items: List<BlockedUpImportItem>): BlockedUpImportResult = synchronized(mutationLock) {
        migrateLegacyDiscoveryMids().getOrThrow()
        val previous = readRecords()
        val plan = buildBlockedUpImportPlan(previous.map { it.mid }.toSet(), items)
        val now = clock()
        val added = plan.itemsToInsert.map { item -> BlockedUp(item.mid, item.name, item.face, blockedAt = now,
            sign = item.sign, level = item.level, vipLabel = item.vipLabel, officialTitle = item.officialTitle,
            follower = item.follower, archiveCount = item.archiveCount, isDeleted = item.isDeleted) }
        if (added.isNotEmpty()) persist(previous + added)
        BlockedUpImportResult(added.size, plan.existingCount, plan.failedCount,
            buildBlockedUpImportMessage(added.size, plan.existingCount, plan.failedCount))
    }

    /** Records and completion marker share one backing's atomic replacement; failure preserves every input. */
    fun migrateLegacyDiscoveryMids(): Result<Int> = synchronized(mutationLock) {
        var readingDestination = true
        try {
            val namespace = context.store.preferences(NAMESPACE)
            val marker = namespace[MIGRATION]
            if (marker != null) {
                require(marker is JsonPrimitive && marker.intOrNull == 1) { "黑名单迁移版本无法识别" }
                readRecords() // A marker must never hide a corrupt destination.
                mutableMigrationError.value = null
                return@synchronized Result.success(0)
            }
            val previous = readRecords()
            readingDestination = false
            val paths = legacyDiscoveryFiles()
            val incoming = paths.flatMap { path ->
                val file = UpdateStorage.existingPathWithoutLinks(path)
                val canonicalRoot = UpdateStorage.existingPathWithoutLinks(context.store.root)
                require(file.startsWith(canonicalRoot) && Files.isRegularFile(file, NOFOLLOW_LINKS)) { "旧黑名单路径无效" }
                require(Files.size(file) <= 8L * 1024 * 1024) { "旧黑名单文件过大" }
                val document = Json.parseToJsonElement(Files.readString(file)).jsonObject
                require(document.values.all { it is JsonObject }) { "旧设置存储格式无法识别" }
                val old = document[NAMESPACE] ?: return@flatMap emptyList()
                require(old is JsonObject) { "旧黑名单数据格式无效" }
                val encoded = old["mids"] ?: run {
                    require(old.isEmpty()) { "旧黑名单字段无法识别" }
                    return@flatMap emptyList()
                }
                require(encoded is JsonPrimitive && encoded.isString) { "旧黑名单 UID 数据格式无效" }
                json.decodeFromString<List<Long>>(encoded.content).map { BlockedUpImportItem(it) }
            }
            // Preserve original invalid/duplicate-ID policy; all files must be readable before any commit.
            val plan = buildBlockedUpImportPlan(previous.map { it.mid }.toSet(), incoming)
            val now = clock()
            val added = plan.itemsToInsert.map { BlockedUp(it.mid, it.name, "", blockedAt = now) }
            context.store.update(NAMESPACE, mapOf(RECORDS to JsonPrimitive(json.encodeToString(previous + added)),
                MIGRATION to JsonPrimitive(1)))
            mutableMigrationError.value = null
            Result.success(added.size)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            val error = IllegalStateException(if (readingDestination)
                "本地黑名单资料无法读取，原数据保持不变；请修复文件后重新启动应用。"
                else "旧屏蔽名单迁移失败，原文件和现有黑名单保持不变；请修复旧文件后重试。")
            mutableMigrationError.value = error.message
            Result.failure(error)
        }
    }

    /** Strict guest + positive account MID whitelist. Credential files are never opened. */
    private fun legacyDiscoveryFiles(): List<Path> {
        val root = context.store.root.toAbsolutePath().normalize()
        val candidates = mutableListOf(root.resolve("discovery/plugin-settings.json"))
        val accounts = root.resolve("accounts")
        if (Files.exists(accounts, NOFOLLOW_LINKS)) {
            val checked = UpdateStorage.existingPathWithoutLinks(accounts)
            require(Files.isDirectory(checked, NOFOLLOW_LINKS)) { "旧账号目录无效" }
            Files.list(checked).use { children -> children.forEach { child ->
                val name = child.fileName.toString()
                if (name.toLongOrNull()?.let { it > 0 && it.toString() == name } == true) {
                    require(Files.isDirectory(child, NOFOLLOW_LINKS)) { "旧账号 MID 目录无效" }
                    UpdateStorage.existingPathWithoutLinks(child)
                    candidates.add(child.resolve("discovery/plugin-settings.json"))
                }
            } }
        }
        return candidates.filter { Files.exists(it, NOFOLLOW_LINKS) }
    }
    private fun persist(rows: List<BlockedUp>) {
        context.store.update(NAMESPACE, mapOf(RECORDS to JsonPrimitive(json.encodeToString(rows))))
    }
    private companion object {
        const val NAMESPACE = "blocked_ups"
        const val RECORDS = "records"
        const val MIGRATION = "legacy_discovery_migration_version"
        val locks = ConcurrentHashMap<Path, Any>()
    }
}

/** Derive from the shared backing after commit; no second persisted document or list cache. */
private class BlockedPreferenceState<T : Any>(private val source: StateFlow<*>, private val read: () -> T) : StateFlow<T> {
    override val value: T get() = read()
    override val replayCache: List<T> get() = listOf(value)
    @OptIn(InternalCoroutinesApi::class)
    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        source.map { read() }.distinctUntilChanged().collect(collector)
        awaitCancellation()
    }
}
