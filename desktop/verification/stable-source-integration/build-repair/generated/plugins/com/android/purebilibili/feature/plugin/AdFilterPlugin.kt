// GENERATED from app/src/main/java/com/android/purebilibili/feature/plugin/AdFilterPlugin.kt; do not edit.
// LF-normalized SHA-256: b096b031d53aa9078a1f6e6a3c519efa0f0e68f254ed7dde6126429de6fe42fa
// 文件路径: feature/plugin/AdFilterPlugin.kt
package com.android.purebilibili.feature.plugin


import com.bilipai.desktop.plugins.DesktopPluginContext as Context
//  Material Icons
import com.android.purebilibili.core.plugin.FeedPlugin
import com.android.purebilibili.core.plugin.PluginCapability
import com.android.purebilibili.core.plugin.PluginCapabilityManifest
import com.android.purebilibili.core.plugin.PluginManager
import com.android.purebilibili.core.plugin.PluginStore
import com.bilipai.desktop.plugins.DesktopPluginLog as Logger
import com.android.purebilibili.data.model.response.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "AdFilterPlugin"
internal const val ADFILTER_PLUGIN_ID = "adfilter"
private const val AD_FILTER_CUSTOM_LIST_PREVIEW_LIMIT = 3
private const val AD_FILTER_PROFILE_REFRESH_LIMIT = 10
private val AD_FILTER_EVENT_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

internal data class AdFilterCustomListSummary(
    val countText: String,
    val previewText: String,
    val hiddenCountText: String?
)

internal fun resolveAdFilterCustomListSummary(
    items: List<String>,
    emptyText: String,
    previewLimit: Int = AD_FILTER_CUSTOM_LIST_PREVIEW_LIMIT
): AdFilterCustomListSummary {
    val safePreviewLimit = previewLimit.coerceAtLeast(1)
    val previewItems = items.take(safePreviewLimit)
    val hiddenCount = (items.size - previewItems.size).coerceAtLeast(0)
    return AdFilterCustomListSummary(
        countText = "${items.size} 个",
        previewText = if (items.isEmpty()) emptyText else previewItems.joinToString("、"),
        hiddenCountText = if (hiddenCount > 0) {
            "还有 ${hiddenCount} 个，展开查看全部"
        } else {
            null
        }
    )
}

internal fun resolveAdFilterCustomListVisibleItems(
    items: List<String>,
    expanded: Boolean,
    previewLimit: Int = AD_FILTER_CUSTOM_LIST_PREVIEW_LIMIT
): List<String> {
    return if (expanded) items else items.take(previewLimit.coerceAtLeast(1))
}

internal fun removeAdFilterCustomListItem(
    items: List<String>,
    item: String
): List<String> {
    return items.filterNot { it == item }
}

/**
 * 🚫 去广告增强插件 v2.0
 * 
 * 功能：
 * 1. 过滤广告/推广/商业合作内容
 * 2. 过滤标题党视频
 * 3. 过滤低质量视频（播放量低）
 * 4. UP主拉黑（按名称或MID）
 * 5. 自定义关键词屏蔽
 */
class AdFilterPlugin : FeedPlugin {
    
    override val id = ADFILTER_PLUGIN_ID
    override val name = "去广告增强"
    override val description = "过滤广告、拉黑UP主、屏蔽关键词"
    override val version = "2.0.1"
    override val author = "BiliPai项目组"
    override val capabilityManifest: PluginCapabilityManifest = PluginCapabilityManifest(
        pluginId = id,
        displayName = name,
        version = version,
        apiVersion = 1,
        entryClassName = "com.android.purebilibili.feature.plugin.AdFilterPlugin",
        capabilities = setOf(
            PluginCapability.RECOMMENDATION_CANDIDATES,
            PluginCapability.LOCAL_FEEDBACK_READ,
            PluginCapability.PLUGIN_STORAGE
        )
    )
    
    private var config: AdFilterConfig = AdFilterConfig()
    private var filteredCount = 0
    
    //  配置版本号，用于检测是否需要重载
    @Volatile
    private var configVersion = 0
    @Volatile
    private var lastConfigReloadMs = 0L
    private val ioScope = com.bilipai.desktop.plugins.DesktopPluginScopeRegistry.create("com/android/purebilibili/feature/plugin/AdFilterPlugin.kt:1", Dispatchers.IO)
    
    //  内置广告关键词（强化版）
    private val AD_KEYWORDS = listOf(
        // 商业合作类
        "商业合作", "恰饭", "推广", "广告", "赞助", "植入",
        "合作推广", "品牌合作", "本期合作", "本视频由",
        // 平台推广类
        "官方活动", "官方推荐", "平台活动", "创作激励",
        // 淘宝/电商类
        "淘宝", "天猫", "京东", "拼多多", "双十一", "双11",
        "优惠券", "领券", "限时优惠", "好物推荐", "种草",
        // 游戏推广类
        "新游推荐", "游戏推广", "首发", "公测", "不删档"
    )
    
    //  标题党关键词（强化版）
    private val CLICKBAIT_KEYWORDS = listOf(
        "震惊", "惊呆了", "太厉害了", "绝了", "离谱", "疯了",
        "价值几万", "价值百万", "价值千万", "一定要看", "必看",
        "看哭了", "泪目", "破防了", "DNA动了", "YYDS",
        "封神", "炸裂", "神作", "预定年度", "史诗级",
        "99%的人不知道", "你一定不知道", "居然是这样",
        "原来是这样", "真相了", "曝光", "揭秘", "独家"
    )
    
    override suspend fun onEnable() {
        filteredCount = 0
        loadConfigSuspend()
        Logger.d(TAG, " 去广告增强v2.0已启用")
        Logger.d(TAG, " 拉黑UP主: ${config.blockedUpNames.size}个, 屏蔽关键词: ${config.blockedKeywords.size}个")
    }
    
    override suspend fun onDisable() {
        Logger.d(TAG, "🔴 去广告增强已禁用，本次过滤了 $filteredCount 条内容")
        filteredCount = 0
    }
    
    override fun shouldShowItem(item: VideoItem): Boolean {
        //  每次过滤前确保配置是最新的
        reloadConfigAsync()
        
        val title = item.title
        val upName = item.owner.name
        val upMid = item.owner.mid
        val viewCount = item.stat.view
        
        // 1️⃣ 检查UP主拉黑列表（按名称） - 支持模糊匹配和简繁体
        val blockedName = findBlockedUpName(upName)
        if (blockedName != null) {
            filteredCount++
            recordFilteredItem(
                item = item,
                reasonType = AdFilterReasonType.BLOCKED_UP,
                matchedText = blockedName
            )
            Logger.d(TAG, "🚫 拉黑UP主[名称]: $upName - $title (列表: ${config.blockedUpNames})")
            return false
        }
        
        // 2️⃣ 检查UP主拉黑列表（按MID）
        if (config.blockedUpMids.contains(upMid)) {
            filteredCount++
            recordFilteredItem(
                item = item,
                reasonType = AdFilterReasonType.BLOCKED_UP,
                matchedText = upMid.toString()
            )
            Logger.d(TAG, "🚫 拉黑UP主[MID]: $upMid - $title")
            return false
        }
        
        // 3️⃣ 检测广告/推广关键词
        if (config.filterSponsored) {
            val keyword = AD_KEYWORDS.firstOrNull { title.contains(it, ignoreCase = true) }
            if (keyword != null) {
                filteredCount++
                recordFilteredItem(
                    item = item,
                    reasonType = AdFilterReasonType.SPONSORED,
                    matchedText = keyword
                )
                Logger.d(TAG, "🚫 过滤广告: $title (UP: $upName)")
                return false
            }
        }
        
        // 4️⃣ 检测标题党
        if (config.filterClickbait) {
            val keyword = CLICKBAIT_KEYWORDS.firstOrNull { title.contains(it, ignoreCase = true) }
            if (keyword != null) {
                filteredCount++
                recordFilteredItem(
                    item = item,
                    reasonType = AdFilterReasonType.CLICKBAIT,
                    matchedText = keyword
                )
                Logger.d(TAG, "🚫 过滤标题党: $title")
                return false
            }
        }
        
        // 5️⃣ 检测自定义屏蔽关键词
        if (config.blockedKeywords.isNotEmpty()) {
            for (keyword in config.blockedKeywords) {
                if (keyword.isNotBlank() && title.contains(keyword, ignoreCase = true)) {
                    filteredCount++
                    recordFilteredItem(
                        item = item,
                        reasonType = AdFilterReasonType.CUSTOM_KEYWORD,
                        matchedText = keyword
                    )
                    Logger.d(TAG, "🚫 自定义屏蔽: $title (关键词: $keyword)")
                    return false
                }
            }
        }
        
        // 6️⃣ 过滤低质量视频（播放量过低）
        if (config.filterLowQuality && viewCount > 0 && viewCount < config.minViewCount) {
            filteredCount++
            recordFilteredItem(
                item = item,
                reasonType = AdFilterReasonType.LOW_VIEW,
                matchedText = "${viewCount} 播放"
            )
            Logger.d(TAG, "🚫 低播放量: $title (播放: $viewCount)")
            return false
        }
        
        return true
    }
    
    /**
     *  检查UP主名称是否在拉黑列表中
     * 支持：精确匹配、模糊匹配(contains)、简繁体转换
     */
    private fun isUpNameBlocked(upName: String): Boolean {
        return findBlockedUpName(upName) != null
    }

    private fun findBlockedUpName(upName: String): String? {
        val normalizedUpName = normalizeChineseChars(upName.lowercase())
        
        return config.blockedUpNames.firstOrNull { blockedName ->
            val normalizedBlocked = normalizeChineseChars(blockedName.lowercase())
            
            // 精确匹配（忽略大小写和简繁体）
            normalizedUpName == normalizedBlocked ||
            // 模糊匹配：UP名包含拉黑词
            normalizedUpName.contains(normalizedBlocked) ||
            // 模糊匹配：拉黑词包含UP名
            normalizedBlocked.contains(normalizedUpName)
        }
    }

    private fun recordFilteredItem(
        item: VideoItem,
        reasonType: AdFilterReasonType,
        matchedText: String
    ) {
        val record = buildAdFilterRecord(
            item = item,
            reasonType = reasonType,
            matchedText = matchedText
        )
        ioScope.launch {
            runCatching {
                AdFilterInsightStore.appendRecord(
                    PluginManager.getContext(),
                    enrichAdFilterRecordUpProfile(record)
                )
            }.onFailure { error ->
                Logger.w(TAG, "记录过滤历史失败: ${error.message}")
            }
        }
    }

    private suspend fun enrichAdFilterRecordUpProfile(record: AdFilterRecord): AdFilterRecord {
        if (record.upFaceUrl.isNotBlank() || record.upMid <= 0L) return record
        val profile = fetchAdFilterUpProfileByMid(
            mid = record.upMid,
            fallbackName = record.upName
        ) ?: return record
        return record.copy(
            upName = record.upName.ifBlank { profile.name },
            upFaceUrl = profile.faceUrl,
            upMid = record.upMid.takeIf { it > 0L } ?: profile.mid
        )
    }
    
    /**
     *  简繁体字符转换表
     * 常用字符的简体→繁体映射，方便双向比较
     */
    private val SIMPLIFIED_TO_TRADITIONAL = mapOf(
        '说' to '說', '话' to '話', '语' to '語', '请' to '請', '让' to '讓',
        '这' to '這', '那' to '那', '哪' to '哪', '谁' to '誰', '什' to '什',
        '时' to '時', '间' to '間', '门' to '門', '网' to '網', '电' to '電',
        '视' to '視', '频' to '頻', '机' to '機', '会' to '會', '员' to '員',
        '学' to '學', '习' to '習', '写' to '寫', '画' to '畫', '图' to '圖',
        '书' to '書', '读' to '讀', '听' to '聽', '看' to '看', '见' to '見',
        '现' to '現', '发' to '發', '开' to '開', '关' to '關', '头' to '頭',
        '脑' to '腦', '乐' to '樂', '欢' to '歡', '爱' to '愛', '国' to '國',
        '华' to '華', '东' to '東', '车' to '車', '马' to '馬', '鸟' to '鳥'
    )
    
    /**
     * 将字符串中的繁体字统一转换为简体字（用于比较）
     */
    private fun normalizeChineseChars(text: String): String {
        val traditionalToSimplified = SIMPLIFIED_TO_TRADITIONAL.entries.associate { it.value to it.key }
        return text.map { char ->
            traditionalToSimplified[char] ?: char
        }.joinToString("")
    }
    
    //  公开方法：添加UP主到拉黑列表
    fun blockUploader(name: String, mid: Long) {
        if (name.isNotBlank() && !config.blockedUpNames.contains(name)) {
            config = config.copy(blockedUpNames = config.blockedUpNames + name)
        }
        if (mid > 0 && !config.blockedUpMids.contains(mid)) {
            config = config.copy(blockedUpMids = config.blockedUpMids + mid)
        }
        saveConfig()
        Logger.d(TAG, "➕ 已拉黑UP主: $name (MID: $mid)")
    }
    
    //  公开方法：移除UP主拉黑
    fun unblockUploader(name: String, mid: Long) {
        config = config.copy(
            blockedUpNames = config.blockedUpNames - name,
            blockedUpMids = config.blockedUpMids - mid
        )
        saveConfig()
        Logger.d(TAG, "➖ 已解除拉黑: $name (MID: $mid)")
    }
    
    private fun saveConfig() {
        ioScope.launch {
            try {
                val context = PluginManager.getContext()
                PluginStore.setConfigJson(context, id, Json.encodeToString(config))
            } catch (e: Exception) {
                Logger.e(TAG, "保存配置失败", e)
            }
        }
    }
    
    private suspend fun loadConfigSuspend() {
        try {
            val context = PluginManager.getContext()
            val jsonStr = PluginStore.getConfigJson(context, id)
            if (jsonStr != null) {
                config = Json.decodeFromString<AdFilterConfig>(jsonStr)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "加载配置失败", e)
        }
    }
    
    /**
     *  同步重载配置
     * 确保每次过滤使用最新的拉黑列表
     */
    private fun reloadConfigAsync() {
        val now = System.currentTimeMillis()
        if (now - lastConfigReloadMs < 1000L) return
        lastConfigReloadMs = now
        
        ioScope.launch {
            try {
                val context = PluginManager.getContext()
                val jsonStr = PluginStore.getConfigJson(context, id)
                if (jsonStr != null) {
                    val newConfig = Json.decodeFromString<AdFilterConfig>(jsonStr)
                    // 只有配置真的变了才更新
                    if (newConfig != config) {
                        config = newConfig
                        configVersion++
                        Logger.d(TAG, " 配置已重载 v$configVersion: 拉黑UP主=${config.blockedUpNames}")
                    }
                }
            } catch (_: Exception) {
                // 静默失败，使用现有配置
            }
        }
    }
    
}

private suspend fun fetchAdFilterUpProfileByMid(
    mid: Long,
    fallbackName: String
): AdFilterUpProfile? {
    if (mid <= 0L) return null
    val response = runCatching {
        com.bilipai.desktop.plugins.DesktopPluginRepositoryBinding.api.getUserCard(mid = mid, photo = true)
    }.getOrNull()
    val card = response?.data?.card
    if (response?.code != 0 || card == null) return null
    val name = card.name.ifBlank { fallbackName }
    if (name.isBlank() && card.face.isBlank()) return null
    return AdFilterUpProfile(
        name = name,
        faceUrl = card.face,
        mid = card.mid.toLongOrNull() ?: mid,
        updatedAtMs = System.currentTimeMillis()
    )
}

private suspend fun fetchAdFilterUpProfileByName(name: String): AdFilterUpProfile? {
    val trimmedName = name.trim()
    if (trimmedName.isBlank()) return null
    val result = com.bilipai.desktop.plugins.DesktopPluginRepositoryBinding.searchUp(keyword = trimmedName, page = 1).getOrNull()?.first.orEmpty()
    val matched = result.firstOrNull { it.uname.equals(trimmedName, ignoreCase = true) }
        ?: result.firstOrNull { item ->
            item.uname.contains(trimmedName, ignoreCase = true) ||
                trimmedName.contains(item.uname, ignoreCase = true)
        }
        ?: return null
    val searchProfile = AdFilterUpProfile(
        name = matched.uname.ifBlank { trimmedName },
        faceUrl = matched.upic,
        mid = matched.mid,
        updatedAtMs = System.currentTimeMillis()
    )
    if (searchProfile.faceUrl.isNotBlank() || searchProfile.mid <= 0L) {
        return searchProfile
    }
    return fetchAdFilterUpProfileByMid(
        mid = searchProfile.mid,
        fallbackName = searchProfile.name
    ) ?: searchProfile
}

private fun findAdFilterProfileForRefresh(
    profiles: List<AdFilterUpProfile>,
    name: String,
    mid: Long
): AdFilterUpProfile? {
    return profiles.firstOrNull { profile ->
        mid > 0L && profile.mid == mid
    } ?: profiles.firstOrNull { profile ->
        profile.name.equals(name, ignoreCase = true)
    }
}

private suspend fun refreshMissingAdFilterUpProfiles(
    context: Context,
    records: List<AdFilterRecord>,
    blockedUpNames: List<String>,
    cachedUpProfiles: List<AdFilterUpProfile>
): List<AdFilterUpProfile> = withContext(Dispatchers.IO) {
    val knownProfiles = resolveAdFilterKnownUpProfiles(
        records = records,
        cachedUpProfiles = cachedUpProfiles
    )
    val recordCandidates = records
        .sortedByDescending { it.timestampMs }
        .filter { record ->
            record.upName.isNotBlank() &&
                resolveAdFilterRecordUpFaceUrl(record, knownProfiles).isBlank()
        }
        .map { record -> record.upName to record.upMid }
    val blockedCandidates = blockedUpNames.map { name -> name to 0L }
    val missingCandidates = (recordCandidates + blockedCandidates)
        .distinctBy { (name, mid) ->
            if (mid > 0L) "mid:$mid" else "name:${name.lowercase()}"
        }
        .filter { (name, mid) ->
            findAdFilterProfileForRefresh(knownProfiles, name, mid)?.faceUrl.isNullOrBlank()
        }
        .take(AD_FILTER_PROFILE_REFRESH_LIMIT)

    val refreshedProfiles = missingCandidates.mapNotNull { (name, mid) ->
        when {
            mid > 0L -> fetchAdFilterUpProfileByMid(mid = mid, fallbackName = name)
            else -> fetchAdFilterUpProfileByName(name)
        }
    }
    if (refreshedProfiles.isNotEmpty()) {
        AdFilterInsightStore.upsertUpProfiles(context, refreshedProfiles)
    }
    AdFilterInsightStore.readUpProfiles(context)
}

@Serializable
data class AdFilterConfig(
    // 基础过滤开关
    val filterSponsored: Boolean = true,    // 过滤广告推广
    val filterClickbait: Boolean = true,    // 过滤标题党
    val filterLowQuality: Boolean = false,  // 过滤低质量
    val minViewCount: Int = 1000,           // 最低播放量
    
    // UP主拉黑
    val blockedUpNames: List<String> = emptyList(),  // 拉黑UP主名称
    val blockedUpMids: List<Long> = emptyList(),     // 拉黑UP主MID
    
    // 自定义关键词
    val blockedKeywords: List<String> = emptyList()  // 自定义屏蔽词
)
