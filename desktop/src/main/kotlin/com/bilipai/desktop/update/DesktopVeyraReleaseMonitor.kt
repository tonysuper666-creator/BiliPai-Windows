package com.bilipai.desktop.update

import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.DesktopPreferenceKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import java.security.MessageDigest
import java.time.Instant

@Serializable
internal data class VeyraReleaseEvidence(
    val releaseId: Long, val tag: String, val sourceCommit: String,
    val prerelease: Boolean, val publishedAt: String, val notes: String, val notesSha256: String,
) {
    val identity: String get() = "$releaseId:$tag:$sourceCommit:$notesSha256"
}

/** Actual integration must supply this from the packaged/loaded core, never from upstream. */
internal data class VeyraInstalledCore(
    val tag: String?, val sourceCommit: String, val adapterBuildId: String,
    val nativeBuildReceiptSha256: String, val mpvSourceCommit: String, val mpvDllSha256: String,
    val filterSourceManifestSha256: String, val sharedSourceManifestSha256: String, val profileSha256: String,
)

internal data class VeyraTrackingState(
    val installed: VeyraInstalledCore? = null,
    val latestPublished: VeyraReleaseEvidence? = null,
    val latestStable: VeyraReleaseEvidence? = null,
    val compatible: VerifiedVeyraCompatibleOffer? = null,
    val changedReleaseIdentities: List<String> = emptyList(),
    val checking: Boolean = false, val error: String? = null,
    val catalogError: String? = null,
)

/** Metadata only. Upstream releases never become WindowsUpdate or trigger a download. */
internal class DesktopVeyraReleaseMonitor(
    private val fetch: suspend (String) -> String?,
    private val store: DesktopPluginStore,
    private val owns: () -> Boolean,
    private val windowsState: () -> UpdateState,
    private val installed: () -> VeyraInstalledCore? = { null },
    // Only the source-pinned own-release resolver supplies full-application compatibility.
    private val compatibleCatalog: suspend (WindowsUpdate) -> VerifiedVeyraCompatibleOffer?,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val catalogMutex = Mutex()
    private var catalogTarget: WindowsUpdate? = null
    private var lastCatalogAttemptMs: Long? = null
    private var catalogRetryPending = false
    private val mutable = MutableStateFlow(VeyraTrackingState(installed = installed(),
        latestPublished = restored("latestPublished"), latestStable = restored("latestStable")))
    private fun restored(key: String): VeyraReleaseEvidence? {
        val value = store.preferences(NAMESPACE)[key]?.takeUnless { it is JsonNull } ?: return null
        return try { json.decodeFromJsonElement(VeyraReleaseEvidence.serializer(), value) }
        catch (_: kotlinx.serialization.SerializationException) { null }
    }
    val state: StateFlow<VeyraTrackingState> = mutable.asStateFlow()

    /** Refresh passive accepted-module information; never fetch, compare tags or download. */
    fun refreshInstalled() {
        if (owns()) mutable.update { it.copy(installed = installed()) }
    }

    /** Explicit download checks bypass catalog retry throttling, never the upstream clock. */
    suspend fun compatibleForUpdate(update: WindowsUpdate): VerifiedVeyraCompatibleOffer? =
        withContext(Dispatchers.IO) { resolveCompatibleCatalog(update, force = true) }

    /** One catalog lane. Source observation has its own durable success/dedup above this lane. */
    private suspend fun resolveCompatibleCatalog(update: WindowsUpdate, force: Boolean): VerifiedVeyraCompatibleOffer? {
        catalogMutex.lock()
        val caller = currentCoroutineContext()
        fun current() {
            caller.ensureActive()
            if (!owns()) throw CancellationException("Veyra catalog owner retired")
            require((windowsState() as? UpdateState.Available)?.update == update) { "Windows 更新目标已改变" }
        }
        try {
            current()
            val now = nowMs()
            val last = lastCatalogAttemptMs
            if (!force && catalogTarget == update && (!catalogRetryPending ||
                last != null && now >= last && now - last < CATALOG_RETRY_MS)) {
                return mutable.value.compatible?.takeIf { it.update == update }
            }
            catalogTarget = update
            lastCatalogAttemptMs = now
            catalogRetryPending = true
            mutable.update { it.copy(compatible = null, catalogError = null) }
            val offer = compatibleCatalog(update)
            current()
            require(offer == null || offer.update == update) { "Windows 兼容目录目标不匹配" }
            catalogRetryPending = false
            mutable.update { it.copy(compatible = offer?.takeIf {
                (windowsState() as? UpdateState.Available)?.update == it.update }, catalogError = null) }
            current()
            return offer
        } catch (cancelled: CancellationException) {
            if (owns()) mutable.update { it.copy(compatible = it.compatible?.takeUnless { value -> value.update == update }) }
            throw cancelled
        } catch (failure: Exception) {
            caller.ensureActive()
            if (owns()) {
                val stillAvailable = (windowsState() as? UpdateState.Available)?.update == update
                catalogRetryPending = stillAvailable
                mutable.update { it.copy(compatible = it.compatible?.takeUnless { value -> value.update == update },
                    catalogError = if (stillAvailable) failure.message ?: "Windows 兼容目录验证失败" else null) }
            }
            throw failure
        } finally { catalogMutex.unlock() }
    }

    /** Retry only the exact currently available own Windows catalog; never repeat upstream HTTP. */
    private suspend fun refreshCompatibleCatalog(force: Boolean) {
        val target = (windowsState() as? UpdateState.Available)?.update
        if (target == null) {
            if (owns()) mutable.update { it.copy(compatible = null) }
            return
        }
        try { resolveCompatibleCatalog(target, force) }
        catch (cancelled: CancellationException) { throw cancelled }
        // The catalog lane already recorded its failure. Accepted upstream metadata remains valid.
        catch (_: Exception) { currentCoroutineContext().ensureActive() }
    }

    /** Runs in the existing application's effect Job; consent changes cancel actual HTTP. */
    suspend fun followSettings() {
        store.snapshot("settings").map {
            it[DesktopPreferenceKey("auto_check_app_update") { raw ->
                (raw as? JsonPrimitive)?.takeUnless { value -> value.isString }?.booleanOrNull }] ?: true
        }.distinctUntilChanged().collectLatest { enabled ->
            if (enabled) while (true) {
                check()
                // Re-read the durable attempt time: a restart must wait only the unelapsed interval.
                val lastAttempt = (store.preferences(NAMESPACE)["lastAttemptMs"] as? JsonPrimitive)?.longOrNull
                // Short wakeups retry failed own catalogs only; check() retains upstream 10h debounce.
                delay(minOf(remainingDelayMs(lastAttempt, nowMs()), CATALOG_RETRY_MS))
            }
        }
    }

    suspend fun check(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext
        val caller = currentCoroutineContext()
        fun current() { caller.ensureActive(); if (!owns()) throw CancellationException("Veyra tracking owner retired") }
        fun persist(changes: Map<String, JsonElement?>) {
            store.updateOriginalFromSnapshot(NAMESPACE, ::current,
                { current(); DesktopPluginStore.OriginalPreferenceWritePermit(store) }) { Unit to changes }
        }
        try {
            current()
            val saved = store.preferences(NAMESPACE)
            val now = nowMs()
            val last = (saved["lastAttemptMs"] as? JsonPrimitive)?.longOrNull ?: 0L
            if (!force && last > 0 && now >= last && now - last < INTERVAL_MS) {
                refreshCompatibleCatalog(force = false)
                return@withContext
            }
            persist(mapOf("lastAttemptMs" to JsonPrimitive(now)))
            mutable.update { it.copy(checking = true, error = null, installed = installed()) }
            val rows = mutableListOf<JsonObject>()
            for (page in 1..3) {
                val batch = json.parseToJsonElement(requireNotNull(fetch(api("releases", page)))).jsonArray
                rows += batch.map { it.jsonObject }.filter { it["draft"]?.jsonPrimitive?.booleanOrNull != true }
                if (batch.size < 100) break
                require(page < 3) { "Veyra release inventory exceeds the bounded page budget" }
            }
            fun publication(row: JsonObject): Instant = Instant.parse(row["published_at"]?.jsonPrimitive?.content)
            val published = rows.filter { it["published_at"] is JsonPrimitive }
            val newest = published.maxByOrNull(::publication)
            val stable = published.filter { it["prerelease"]?.jsonPrimitive?.booleanOrNull != true }.maxByOrNull(::publication)
            // At most two distinct tags need source resolution per check, not hundreds of history calls.
            val evidence = mutableMapOf<Long, VeyraReleaseEvidence>()
            for (row in listOfNotNull(newest, stable).distinctBy { it["id"] }) {
                current()
                val id = row.getValue("id").jsonPrimitive.long
                val tag = row.getValue("tag_name").jsonPrimitive.content
                require(id > 0 && tag.isNotBlank() && tag.length <= 256 && tag.none(Char::isISOControl))
                var obj = json.parseToJsonElement(requireNotNull(fetch(api("git/ref/tags", tag = tag)))).jsonObject.getValue("object").jsonObject
                var commit: String? = null
                repeat(8) {
                    if (commit == null) {
                        val sha = obj.getValue("sha").jsonPrimitive.content
                        require(SHA.matches(sha)) { "Veyra tag object SHA is not complete" }
                        when (obj.getValue("type").jsonPrimitive.content) {
                            "commit" -> commit = sha.lowercase()
                            "tag" -> obj = json.parseToJsonElement(requireNotNull(fetch(api("git/tags/$sha")))).jsonObject.getValue("object").jsonObject
                            else -> error("Veyra release tag does not resolve to a commit")
                        }
                    }
                }
                val notes = (row["body"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                require(notes.length <= 256 * 1024) { "Veyra release notes exceed the storage budget" }
                evidence[id] = VeyraReleaseEvidence(id, tag, requireNotNull(commit) { "Annotated tag nesting exceeds limit" },
                    row["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false,
                    row.getValue("published_at").jsonPrimitive.content, notes, digest(notes))
            }
            val latest = newest?.let { evidence[it.getValue("id").jsonPrimitive.long] }
            val latestStable = stable?.let { evidence[it.getValue("id").jsonPrimitive.long] }
            val previous = (saved["observedIdentities"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
            val observed = evidence.values.map(VeyraReleaseEvidence::identity)
            // Catalog lookup follows accepted upstream persistence.
            current()
            persist(mapOf("lastSuccessMs" to JsonPrimitive(nowMs()),
                "latestPublished" to latest?.let { json.encodeToJsonElement(VeyraReleaseEvidence.serializer(), it) },
                "latestStable" to latestStable?.let { json.encodeToJsonElement(VeyraReleaseEvidence.serializer(), it) },
                "observedIdentities" to JsonArray((previous + observed).distinct().takeLast(128).map(::JsonPrimitive))))
            current()
            mutable.update { it.copy(installed = installed(), latestPublished = latest, latestStable = latestStable,
                compatible = it.compatible?.takeIf { value ->
                    (windowsState() as? UpdateState.Available)?.update == value.update },
                changedReleaseIdentities = observed.filterNot(previous::contains), error = null) }
            refreshCompatibleCatalog(force = true)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { current(); mutable.update { it.copy(error = failure.message ?: "Veyra release check failed") } }
        finally { mutable.update { it.copy(checking = false) }; mutex.unlock() }
    }

    private fun api(path: String, page: Int? = null, tag: String? = null): String {
        val builder = HttpUrl.Builder().scheme("https").host("api.github.com")
            .addPathSegments("repos/Likely7/Veyra-NRVideo").addPathSegments(path)
        tag?.let(builder::addPathSegment)
        if (page != null) builder.addQueryParameter("per_page", "100").addQueryParameter("page", page.toString())
        return builder.build().toString()
    }
    companion object {
        const val INTERVAL_MS = 10 * 60 * 60 * 1000L
        internal const val CATALOG_RETRY_MS = 15 * 60 * 1000L
        private const val MIN_DELAY_MS = 1000L
        /** check() re-bases a clock rollback; if it could not run, retry without a hot loop. */
        internal fun remainingDelayMs(lastAttemptMs: Long?, nowMs: Long): Long {
            if (lastAttemptMs == null || lastAttemptMs <= 0L || nowMs < lastAttemptMs) return MIN_DELAY_MS
            val elapsed = (nowMs - lastAttemptMs).coerceAtMost(INTERVAL_MS)
            return (INTERVAL_MS - elapsed).coerceAtLeast(MIN_DELAY_MS)
        }
        private const val NAMESPACE = "veyra_release_tracking"
        private val SHA = Regex("[0-9a-fA-F]{40}")
        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
