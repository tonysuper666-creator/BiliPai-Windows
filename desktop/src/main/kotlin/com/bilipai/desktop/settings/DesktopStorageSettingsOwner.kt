package com.bilipai.desktop.settings

import com.android.purebilibili.core.store.DesktopOriginalStorageSettings
import com.android.purebilibili.core.store.FollowingCacheStore
import com.android.purebilibili.core.util.*
import com.android.purebilibili.feature.following.DesktopFollowingCacheContext
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.danmaku.DanmakuOverlay
import com.bilipai.desktop.diagnostics.DesktopDiagnostics
import com.bilipai.desktop.diagnostics.DesktopNativeTextShare
import com.bilipai.desktop.download.DesktopDownloadManager
import com.bilipai.desktop.player.DesktopSubtitleAssets
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.cache.DesktopMediaByteCache
import com.bilipai.desktop.ui.*
import com.bilipai.desktop.update.UpdateStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** Transient physical Window settings orchestration. Every byte/cache/preferences owner is supplied
 * by the real Root. The mutex drains accepted maintenance before restore, without owning cache state. */
internal class DesktopStorageSettingsOwner(
    val context: DesktopOriginalPlayerSettingsContext,
    private val repository: DesktopRepository,
    private val images: DesktopApplicationImageLoader,
    private val media: () -> DesktopMediaByteCache?,
    private val subtitle: DesktopSubtitleAssets,
    private val danmaku: DanmakuOverlay?,
    private val player: MpvPlayer?,
    private val diagnostics: DesktopDiagnostics?,
    private val nativeShare: DesktopNativeTextShare,
    private val sharePool: () -> DesktopVideoShareFiles?,
    private val captureEntryOwnership: () -> (() -> Boolean)?,
    private val windowOwned: () -> Boolean,
) {
    private val closed=AtomicBoolean()
    private val startup=AtomicBoolean()
    private val operations=Mutex()
    fun isActive()= !closed.get() && windowOwned()
    private fun requireActive() { if(!isActive())throw CancellationException("存储设置窗口已结束") }
    fun defaultDestination(): Path {
        requireActive();context.requireCurrent()
        val value=(context.pluginContext.store.preferences("settings")["download_path"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return resolveDesktopOriginalDownloadDestination(value,DesktopDownloadManager.defaultDownloadRoot())
    }
    suspend fun saveInterval(value: DesktopOriginalStorageSettings.AutoCacheClearInterval) = exclusive(requireEntry=false) { checkRequest ->
        checkRequest();DesktopOriginalStorageSettings.setAutoCacheClearInterval(context,value);checkRequest()
    }
    suspend fun saveThreshold(value: Int) = exclusive(requireEntry=false) { checkRequest ->
        checkRequest();DesktopOriginalStorageSettings.setAutoCacheClearThresholdGb(context,value);checkRequest()
    }
    suspend fun selectDownloadPath(selected: Path?) = exclusive(requireEntry=false) { checkRequest ->
        val path=selected?.let {
            require(it.isAbsolute) { "请选择绝对 Windows 下载目录" }
            val normalized=it.toAbsolutePath().normalize()
            UpdateStorage.existingPathWithoutLinks(normalized)
            require(Files.isDirectory(normalized,LinkOption.NOFOLLOW_LINKS)) { "请选择普通文件夹" }
            normalized
        }
        checkRequest()
        // Both original canonical keys and synchronous mirrors publish in one actual Store replacement.
        withContext(Dispatchers.IO) {
            context.pluginContext.store.updateOriginalNamespacesFromSnapshot("settings",checkRequest,
                {context.preferenceWritePermit(checkRequest)}) { Unit to mapOf(
                "settings" to mapOf("download_path" to path?.toString()?.let(::JsonPrimitive),"download_export_tree_uri" to null),
                "download_prefs" to mapOf("path" to path?.toString()?.let(::JsonPrimitive),"tree_uri" to null)) }
        }
        checkRequest()
    }
    suspend fun breakdown(includeProtectedShare: Boolean=true): CacheUtils.CacheBreakdown = withContext(Dispatchers.IO) {
        requireActive();context.requireCurrent()
        val (imageDisk,imageMemory)=images.cacheSizes()
        val (subtitleDisk,subtitleMemory)=subtitle.cacheSizes()
        val shareBytes=if(includeProtectedShare)sharePool()?.managedBytes() ?: 0L else 0L
        CacheUtils.CacheBreakdown(imageDiskCache=imageDisk,imageMemoryCache=imageMemory,
            httpCache=repository.httpClient.cache?.size() ?: 0L,
            playbackMediaCache=media()?.stats()?.diskBytesIncludingStaging ?: 0L,
            otherCache=subtitleDisk+shareBytes+(diagnostics?.cacheBytes() ?: 0L),
            playUrlMemoryCache=repository.storagePlaybackMemoryBytes(),
            subtitleDanmakuMemoryCache=subtitleMemory+(danmaku?.cacheMemoryEstimate() ?: 0L))
    }
    suspend fun clear(selected: Set<CacheClearTarget>, explicitUser: Boolean=true) = exclusive(requireEntry=explicitUser) { checkRequest ->
        require(selected.isNotEmpty()) { "请选择至少一项缓存" }
        suspend fun action() {
            if(CacheClearTarget.NETWORK in selected) {
                checkRequest();checkNotNull(media()) { "媒体缓存尚未初始化" }.clearIdle(checkRequest)
                withContext(Dispatchers.IO) { checkRequest();repository.httpClient.cache?.evictAll();checkRequest() }
            }
            if(CacheClearTarget.SUBTITLE_DANMAKU in selected || CacheClearTarget.TEMP_FILES_AND_LOGS in selected) {
                withContext(Dispatchers.IO) { subtitle.clearIdle(checkRequest) }
                if(CacheClearTarget.SUBTITLE_DANMAKU in selected)danmaku?.clearIdleCache(checkRequest)
            }
            if(CacheClearTarget.IMAGE_PREVIEW in selected)withContext(Dispatchers.IO) { images.clearManagedCaches(checkRequest) }
            if(CacheClearTarget.PLAYBACK_QUALITY in selected)repository.clearStoragePlaybackCache(checkRequest)
            if(CacheClearTarget.TEMP_FILES_AND_LOGS in selected) {
                // Protected recipient files require an explicit user confirmation; startup never revokes shares.
                if(explicitUser) {
                    val pool=checkNotNull(sharePool()) { "当前 Root 分享文件池不可用" }
                    checkRequest();nativeShare.clearVideoShareFiles { checkRequest();pool.clearExplicit();checkRequest() }
                }
                checkRequest();diagnostics?.clearAll();checkRequest()
            }
            if(CacheClearTarget.APP_METADATA in selected)withContext(Dispatchers.IO) {
                checkRequest();FollowingCacheStore.clear(DesktopFollowingCacheContext(context.pluginContext.store,
                    {isActive()}, {block->context.commit {checkRequest();block()};true}))
                repository.invalidateStorageWbi(checkRequest)
            }
            checkRequest()
        }
        val nativeSensitive=CacheClearTarget.SUBTITLE_DANMAKU in selected || CacheClearTarget.TEMP_FILES_AND_LOGS in selected
        if(nativeSensitive)checkNotNull(player) { "实际原生播放器不可用，不能确认字幕文件已释放" }.withIdleCacheMaintenance(checkRequest) { action() }
        else action()
    }
    /** Exactly once per physical Window, after actual media cache initialization and before Root mount. */
    suspend fun clearAutomaticallyAtStartup(now: Long=System.currentTimeMillis()): Boolean {
        if(!startup.compareAndSet(false,true))return false
        requireActive();checkNotNull(media()) { "启动媒体缓存尚未初始化" }
        val interval=DesktopOriginalStorageSettings.getAutoCacheClearInterval(context).first()
        val threshold=DesktopOriginalStorageSettings.getAutoCacheClearThresholdGb(context).first()*1024L*1024*1024
        val last=DesktopOriginalStorageSettings.getLastAutoCacheClearAt(context)
        val bytes=breakdown(includeProtectedShare=false).reclaimableDiskSize
        if(!shouldAutomaticallyClearCache(interval,last,now,bytes,threshold))return false
        clear(CacheClearTarget.entries.toSet(),explicitUser=false)
        exclusive(requireEntry=false) { checkRequest -> checkRequest();DesktopOriginalStorageSettings.setLastAutoCacheClearAt(context,now);checkRequest() }
        return true
    }
    private suspend fun <T> exclusive(requireEntry: Boolean, block: suspend (() -> Unit) -> T): T {
        requireActive();check(operations.tryLock()) { "存储设置正在处理，请稍后重试" }
        try {
            val caller=currentCoroutineContext()
            val epoch=repository.sessionEpoch
            val entry=if(requireEntry)checkNotNull(captureEntryOwnership()) { "当前设置页面已退役" } else null
            val checkRequest={caller.ensureActive();requireActive();context.requireCurrent()
                if(repository.sessionEpoch!=epoch || entry?.invoke()==false)throw CancellationException("存储请求的账号或 Root 已变更")}
            checkRequest();return block(checkRequest)
        } finally { operations.unlock() }
    }
    suspend fun shutdownForRestore()=withContext(NonCancellable) { closed.set(true);operations.withLock { Unit } }
}
