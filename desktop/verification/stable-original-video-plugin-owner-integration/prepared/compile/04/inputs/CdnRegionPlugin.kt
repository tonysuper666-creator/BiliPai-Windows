// GENERATED from app/src/main/java/com/android/purebilibili/feature/plugin/CdnRegionPlugin.kt; do not edit.
// LF-normalized SHA-256: d0e1c74436524d943c6b044ea984ad5564b6f3b4ee1b686883ead67a095094b9
package com.android.purebilibili.feature.plugin

import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.android.purebilibili.core.plugin.Plugin
import com.android.purebilibili.core.plugin.PluginCapability
import com.android.purebilibili.core.plugin.PluginCapabilityManifest
import com.android.purebilibili.core.plugin.PluginManager
import com.android.purebilibili.core.plugin.PluginStore
import com.bilipai.desktop.plugins.DesktopPluginLog as Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket

const val CDN_REGION_PLUGIN_ID = "cdn_region"
private const val TAG = "CdnRegionPlugin"
private const val CDN_PROBE_SAMPLE_BYTES = 32 * 1024
private const val CDN_REALTIME_PROBE_INTERVAL_MS = 30_000L
private const val CDN_ACTIVE_PLAYBACK_SESSION_TTL_MS = 10 * 60_000L

data class PlaybackCdnRewriteResult(
    val candidates: List<PlaybackCdnCandidate>,
    val regionLabel: String?,
    val cacheKeysByUrl: Map<String, String> = emptyMap()
) {
    val videoUrls: List<String> get() = candidates.map { it.videoUrl }
    val audioUrls: List<String> get() = candidates.map { it.audioUrl.orEmpty() }
    val sources: List<PlaybackCdnCandidateSource> get() = candidates.map { it.source }
}

private data class CdnProbeMeasure(
    val success: Boolean,
    val latencyMs: Long?,
    val speedKbps: Long?
)

private data class CdnSettingsProbeTarget(
    val region: String,
    val hosts: List<String>
)

/**
 * Kept in memory only. Signed playurl addresses must never be written to plugin storage because
 * they expire and would otherwise look like a reusable CDN rule on the next playback session.
 */
private data class CdnActivePlaybackSession(
    val candidates: List<PlaybackCdnCandidate>,
    val updatedAtMs: Long
)

interface PlaybackCdnPlugin : Plugin {
    fun rewritePlaybackCandidates(
        videoUrls: List<String>,
        audioUrls: List<String>
    ): PlaybackCdnRewriteResult

    fun buildPlaybackCdnDiagnostics(
        videoUrls: List<String>,
        sources: List<PlaybackCdnCandidateSource> = emptyList()
    ): List<CdnLineDiagnostic> = emptyList()

    suspend fun probePlaybackCdnCandidates(
        videoUrls: List<String>,
        sources: List<PlaybackCdnCandidateSource> = emptyList()
    ): List<CdnLineDiagnostic> = emptyList()

    fun recordPlaybackCdnEvent(
        url: String,
        event: CdnHealthEvent
    ) = Unit

    fun isAdaptivePrefetchEnabled(): Boolean = false
}

class CdnRegionPlugin : PlaybackCdnPlugin {
    override val id: String = CDN_REGION_PLUGIN_ID
    override val name: String = "CDN 智能选线"
    override val description: String = "在 B 站当前授权的签名 CDN 候选中选线，并可选预缓存未来 DASH 分片"
    override val version: String = "1.4.0"
    override val author: String = "BiliPai项目组"
    override val capabilityManifest: PluginCapabilityManifest = PluginCapabilityManifest(
        pluginId = id,
        displayName = name,
        version = version,
        apiVersion = 1,
        entryClassName = "com.android.purebilibili.feature.plugin.CdnRegionPlugin",
        capabilities = setOf(
            PluginCapability.PLAYBACK_CDN,
            PluginCapability.NETWORK,
            PluginCapability.PLUGIN_STORAGE
        )
    )

    @Volatile
    private var cache: CdnRegionPluginCache = CdnRegionPluginCache()

    @Volatile
    private var catalog: Map<String, List<String>> = emptyMap()

    @Volatile
    private var compiledCustomRules: List<Pair<CdnCustomRule, Regex>> = emptyList()

    @Volatile
    private var activePlaybackSession: CdnActivePlaybackSession? = null

    override suspend fun onEnable() {
        val context = PluginManager.getContext()
        catalog = loadCdnRegionCatalog(context)
        cache = CdnRegionPluginStore.read(context)
        compiledCustomRules = compileCdnCustomRules(cache.customRules)
        com.bilipai.desktop.plugins.DesktopPluginApplicationScope.ioScope.launch {
            delay(1_500L)
            refreshIpLocationIfNeeded()
        }
        Logger.d(TAG, "CDN 属地优选已启用，缓存地区=${cache.selectedRegion.ifBlank { "未命中" }}")
    }

    override suspend fun onDisable() {
        activePlaybackSession = null
        Logger.d(TAG, "CDN 属地优选已禁用")
    }

    override fun rewritePlaybackCandidates(
        videoUrls: List<String>,
        audioUrls: List<String>
    ): PlaybackCdnRewriteResult {
        val snapshot = cache
        val originalCandidates = buildPlaybackCdnCandidates(videoUrls, audioUrls)
        publishActivePlaybackCandidates(originalCandidates)
        if (!snapshot.experimentalRewriteEnabled) {
            val safeCandidates = sortSafeSignedPlaybackCandidates(
                candidates = originalCandidates,
                healthByHost = snapshot.healthByHost
            )
            return PlaybackCdnRewriteResult(
                candidates = safeCandidates,
                regionLabel = null,
                cacheKeysByUrl = buildPlaybackCdnCacheKeys(safeCandidates)
            )
        }
        val customCandidates = rewritePlaybackCdnCandidatesForCompiledCustomRules(
            candidates = originalCandidates,
            compiledRules = compiledCustomRules
        )
        val hosts = if (snapshot.fallbackUsed) emptyList() else resolveCdnRegionHosts(
            region = snapshot.selectedRegion,
            cachedHosts = snapshot.selectedHosts,
            catalog = catalog,
            isp = snapshot.location.isp
        )
        val regionCandidates = rewritePlaybackCdnCandidatesForRegion(originalCandidates, hosts)
        val candidates = selectPlaybackCdnCandidatesForMode(
                customCandidates = customCandidates,
                regionCandidates = regionCandidates,
                originalCandidates = originalCandidates,
                strictCustomCdn = snapshot.strictCustomCdn,
                healthByHost = snapshot.healthByHost
            )
        return PlaybackCdnRewriteResult(
            candidates = candidates,
            regionLabel = snapshot.selectedRegion.takeIf { hosts.isNotEmpty() },
            cacheKeysByUrl = buildPlaybackCdnCacheKeys(candidates)
        )
    }

    override fun buildPlaybackCdnDiagnostics(
        videoUrls: List<String>,
        sources: List<PlaybackCdnCandidateSource>
    ): List<CdnLineDiagnostic> {
        return buildCdnLineDiagnostics(videoUrls, cache.healthByHost, sources)
    }

    override suspend fun probePlaybackCdnCandidates(
        videoUrls: List<String>,
        sources: List<PlaybackCdnCandidateSource>
    ): List<CdnLineDiagnostic> {
        return probePlaybackCdnCandidatesInternal(
            videoUrls = videoUrls,
            sources = sources,
            ignoreCooldown = false
        )
    }

    private suspend fun probePlaybackCdnCandidatesInternal(
        videoUrls: List<String>,
        sources: List<PlaybackCdnCandidateSource>,
        ignoreCooldown: Boolean
    ): List<CdnLineDiagnostic> {
        val context = com.bilipai.desktop.plugins.DesktopPlayerPluginWriteAdmission.contextOrOriginal { PluginManager.getContext() }
        val now = System.currentTimeMillis()
        val current = CdnRegionPluginStore.read(context).also { value -> com.bilipai.desktop.plugins.DesktopPlayerPluginWriteAdmission.mutateOrOriginal { cache = value } }
        val candidates = resolveCdnProbeCandidates(
            urls = videoUrls,
            healthByHost = current.healthByHost,
            nowMs = now,
            limit = CdnProbeLimit(
                maxCandidates = 3,
                cooldownMs = if (ignoreCooldown) 0L else CDN_MANUAL_PROBE_COOLDOWN_MS
            )
        )
        var nextHealth = current.healthByHost
        candidates.filter { it.allowed }.forEach { candidate ->
            val result = probePlaybackUrl(candidate.url)
            val previous = nextHealth[candidate.host] ?: CdnCandidateHealth(host = candidate.host)
            nextHealth = nextHealth + (
                candidate.host to recordCdnProbeResult(
                    current = previous,
                    latencyMs = result.latencyMs,
                    speedKbps = result.speedKbps,
                    success = result.success,
                    nowMs = System.currentTimeMillis()
                )
            )
        }
        val next = current.copy(healthByHost = nextHealth)
        com.bilipai.desktop.plugins.DesktopPlayerPluginWriteAdmission.mutateOrOriginal { cache = next }
        CdnRegionPluginStore.write(context, next)
        return buildCdnLineDiagnostics(videoUrls, next.healthByHost, sources)
    }

    override fun recordPlaybackCdnEvent(url: String, event: CdnHealthEvent) {
        val host = hostFromCdnUrl(url)
        if (host.isBlank()) return
        val now = System.currentTimeMillis()
        val current = cache
        val previous = current.healthByHost[host] ?: CdnCandidateHealth(host = host)
        val next = current.copy(
            healthByHost = current.healthByHost + (host to recordCdnHealthEvent(previous, event, now))
        )
        com.bilipai.desktop.plugins.DesktopPlayerPluginWriteAdmission.mutateOrOriginal { cache = next }
        com.bilipai.desktop.plugins.DesktopPlayerPluginWriteAdmission.launchOrOriginal(com.bilipai.desktop.plugins.DesktopPluginApplicationScope.ioScope) {
            CdnRegionPluginStore.write(com.bilipai.desktop.plugins.DesktopPlayerPluginWriteAdmission.contextOrOriginal { PluginManager.getContext() }, next)
        }
    }

    override fun isAdaptivePrefetchEnabled(): Boolean {
        return cache.prefetchEnabled && !cache.experimentalRewriteEnabled
    }



    private fun publishActivePlaybackCandidates(candidates: List<PlaybackCdnCandidate>) {
        activePlaybackSession = CdnActivePlaybackSession(
            candidates = candidates.distinctBy { it.videoUrl }.take(3),
            updatedAtMs = System.currentTimeMillis()
        )
    }

    private fun activePlaybackSessionOrNull(): CdnActivePlaybackSession? {
        val session = activePlaybackSession ?: return null
        return session.takeIf {
            System.currentTimeMillis() - it.updatedAtMs <= CDN_ACTIVE_PLAYBACK_SESSION_TTL_MS
        }
    }

    private fun formatCdnLineDiagnostic(diagnostic: CdnLineDiagnostic): String {
        return buildList {
            diagnostic.latencyMs?.let { add("${it}ms") }
            diagnostic.speedKbps?.let { add("${it}Kbps") }
            add(diagnostic.statusLabel)
            if (diagnostic.errorCount > 0) add("失败 ${diagnostic.errorCount}")
            if (diagnostic.bufferingCount > 0) add("缓冲 ${diagnostic.bufferingCount}")
        }.distinct().joinToString(" · ")
    }



    private suspend fun probeSelectedHosts(
        context: Context,
        current: CdnRegionPluginCache,
        target: CdnSettingsProbeTarget
    ): CdnRegionPluginCache {
        var nextHealth = current.healthByHost
        val now = System.currentTimeMillis()
        target.hosts.take(5).forEach { host ->
            val previous = nextHealth[host] ?: CdnCandidateHealth(host = host)
            val elapsed = now - previous.lastProbeAtMs
            if (previous.lastProbeAtMs > 0L && elapsed < CDN_MANUAL_PROBE_COOLDOWN_MS) {
                return@forEach
            }
            val result = probeHostTlsPort(host)
            nextHealth = nextHealth + (
                host to recordCdnProbeResult(
                    current = previous,
                    latencyMs = result.latencyMs,
                    speedKbps = null,
                    success = result.success,
                    nowMs = System.currentTimeMillis()
                )
            )
        }
        val nextRegion = current.selectedRegion.ifBlank { target.region }
        val nextHosts = current.selectedHosts.ifEmpty { target.hosts }
        return current.copy(
            selectedRegion = nextRegion,
            selectedHosts = nextHosts,
            fallbackUsed = current.fallbackUsed && nextRegion.isBlank(),
            healthByHost = nextHealth
        ).also {
            cache = it
            CdnRegionPluginStore.write(context, it)
        }
    }

    private fun resolveSettingsProbeTarget(
        snapshot: CdnRegionPluginCache,
        catalogSnapshot: Map<String, List<String>>
    ): CdnSettingsProbeTarget {
        val selectedHosts = resolveCdnRegionHosts(
            region = snapshot.selectedRegion,
            cachedHosts = snapshot.selectedHosts,
            catalog = catalogSnapshot,
            isp = snapshot.location.isp
        )
        if (selectedHosts.isNotEmpty()) {
            return CdnSettingsProbeTarget(
                region = snapshot.selectedRegion,
                hosts = selectedHosts
            )
        }

        val selection = selectCdnRegionForLocation(
            location = snapshot.location,
            catalog = catalogSnapshot
        )
        return CdnSettingsProbeTarget(
            region = selection.region,
            hosts = selection.hosts
        )
    }

    private fun formatCdnHostDiagnostic(diagnostic: CdnHostDiagnostic): String {
        val parts = buildList {
            if (diagnostic.latencyMs != null) {
                add("延迟 ${diagnostic.latencyMs}ms")
            }
            if (diagnostic.speedKbps != null) {
                add("速度 ${diagnostic.speedKbps}Kbps")
            }
            when {
                diagnostic.lastProbeAtMs > 0L && diagnostic.latencyMs == null -> add("检测失败")
                diagnostic.lastProbeAtMs <= 0L -> add("未检测")
            }
            add(diagnostic.statusLabel)
            if (diagnostic.errorCount > 0) {
                add("失败 ${diagnostic.errorCount} 次")
            }
        }
        return parts.distinct().joinToString(" · ")
    }

    private suspend fun probePlaybackUrl(url: String): CdnProbeMeasure {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val startedAt = System.nanoTime()
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-${CDN_PROBE_SAMPLE_BYTES - 1}")
                    .header("Referer", "https://www.bilibili.com")
                    .build()
                val call = com.bilipai.desktop.plugins.DesktopPlayerPluginWriteAdmission.playbackCallsOrOriginal { com.bilipai.desktop.plugins.DesktopPluginRepositoryBinding.playbackClient }.newCall(request)
                com.bilipai.desktop.plugins.DesktopPlayerPluginWriteAdmission.executeOrOriginal(call) { response ->
                    val bytesRead = response.body.byteStream().use { input ->
                        val buffer = ByteArray(8 * 1024)
                        var total = 0
                        while (total < CDN_PROBE_SAMPLE_BYTES) {
                            val read = input.read(buffer, 0, minOf(buffer.size, CDN_PROBE_SAMPLE_BYTES - total))
                            if (read <= 0) break
                            total += read
                        }
                        total
                    }
                    val elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
                    CdnProbeMeasure(
                        success = response.isSuccessful || response.code == 206,
                        latencyMs = elapsedMs,
                        speedKbps = if (bytesRead > 0) (bytesRead * 8L / elapsedMs).coerceAtLeast(1L) else null
                    )
                }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                Logger.w(TAG, "CDN 播放候选检测失败: ${hostFromCdnUrl(url)} ${error.message}")
                CdnProbeMeasure(success = false, latencyMs = null, speedKbps = null)
            }
        }
    }

    private suspend fun probeHostTlsPort(host: String): CdnProbeMeasure {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val startedAt = System.nanoTime()
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, 443), 3_000)
                }
                val elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
                CdnProbeMeasure(success = true, latencyMs = elapsedMs, speedKbps = null)
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                Logger.w(TAG, "CDN host 检测失败: $host ${error.message}")
                CdnProbeMeasure(success = false, latencyMs = null, speedKbps = null)
            }
        }
    }

    private suspend fun refreshIpLocationIfNeeded() {
        val context = PluginManager.getContext()
        val enabled = PluginStore.isEnabled(context, id)
        val loadedCatalog = catalog.ifEmpty {
            loadCdnRegionCatalog(context).also { catalog = it }
        }
        if (loadedCatalog.isEmpty()) return
        val current = CdnRegionPluginStore.read(context).also { cache = it }
        val hasSelection = hasUsableCdnRegionSelection(
            region = current.selectedRegion,
            cachedHosts = current.selectedHosts,
            catalog = loadedCatalog
        )

        if (!shouldRefreshCdnIpLocation(
                enabled = enabled,
                nowMs = System.currentTimeMillis(),
                lastRefreshMs = current.refreshedAtMs,
                hasSelection = hasSelection && !current.fallbackUsed
            )
        ) {
            val resolvedHosts = resolveCdnRegionHosts(
                region = current.selectedRegion,
                cachedHosts = current.selectedHosts,
                catalog = loadedCatalog,
                isp = current.location.isp
            )
            if (current.selectedHosts != resolvedHosts) {
                val corrected = current.copy(
                    selectedHosts = resolvedHosts
                )
                cache = corrected
                CdnRegionPluginStore.write(context, corrected)
            }
            return
        }

        try {
            val response = com.bilipai.desktop.plugins.DesktopPluginRepositoryBinding.api.getIpZone()
            val data = response.data
            if (response.code != 0 || data == null) {
                error(response.message.ifBlank { "IP 属地接口返回 code=${response.code}" })
            }

            val location = IpLocationSnapshot(
                addr = data.addr,
                country = data.country,
                province = data.province,
                city = data.city,
                isp = data.isp
            )
            val selection = selectCdnRegionForLocation(
                location = location,
                catalog = loadedCatalog
            )
            val next = current.copy(
                location = location,
                selectedRegion = selection.region,
                selectedHosts = selection.hosts,
                fallbackRegion = "",
                fallbackUsed = selection.fallbackUsed,
                refreshedAtMs = System.currentTimeMillis(),
                lastError = null
            )
            cache = next
            CdnRegionPluginStore.write(context, next)
            Logger.d(
                TAG,
                "CDN 属地刷新成功: ip=${maskIpAddressForLog(location.addr)}, " +
                    "${location.country}/${location.province}/${location.city}, isp=${location.isp.ifBlank { "未知" }} -> " +
                    (selection.region.ifBlank { "未命中" })
            )
        } catch (e: Exception) {
            val preserved = current.copy(lastError = e.message ?: e.javaClass.simpleName)
            cache = preserved
            CdnRegionPluginStore.write(context, preserved)
            Logger.w(TAG, "CDN 属地刷新失败，保留旧缓存: ${e.message}")
        }
    }
}

@Serializable
internal data class CdnRegionPluginCache(
    val location: IpLocationSnapshot = IpLocationSnapshot(),
    val selectedRegion: String = "",
    val selectedHosts: List<String> = emptyList(),
    val fallbackRegion: String = "",
    val fallbackUsed: Boolean = false,
    val refreshedAtMs: Long = 0L,
    val lastError: String? = null,
    val healthByHost: Map<String, CdnCandidateHealth> = emptyMap(),
    val customRules: List<CdnCustomRule> = emptyList(),
    val strictCustomCdn: Boolean = false,
    val prefetchEnabled: Boolean = false,
    val experimentalRewriteEnabled: Boolean = false
)

internal object CdnRegionPluginStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun read(context: Context): CdnRegionPluginCache {
        val raw = PluginStore.getConfigJson(context, CDN_REGION_PLUGIN_ID) ?: return CdnRegionPluginCache()
        return runCatching { json.decodeFromString<CdnRegionPluginCache>(raw) }
            .getOrDefault(CdnRegionPluginCache())
    }

    suspend fun write(context: Context, cache: CdnRegionPluginCache) {
        PluginStore.setConfigJson(
            context = context,
            pluginId = CDN_REGION_PLUGIN_ID,
            configJson = json.encodeToString(cache)
        )
    }
}

internal fun loadCdnRegionCatalog(context: Context): Map<String, List<String>> {
    return runCatching {
        com.bilipai.desktop.plugins.DesktopPluginResource.open("plugin/cdn_region_catalog.json").bufferedReader().use { reader ->
            Json.decodeFromString<Map<String, List<String>>>(reader.readText())
        }.filterValues { hosts -> hosts.any { it.isNotBlank() } }
            .mapValues { (_, hosts) -> hosts.filter { it.isNotBlank() }.distinct() }
    }.getOrElse { error ->
        Logger.w(TAG, "读取 CDN catalog 失败: ${error.message}")
        emptyMap()
    }
}
