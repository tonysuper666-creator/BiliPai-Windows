package com.android.purebilibili.data.repository

import com.android.purebilibili.core.database.entity.BlockedUp
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val BILIBILI_RELATION_ACT_BLOCK = 5
private const val BILIBILI_RELATION_ACT_UNBLOCK = 6
private const val BILIBILI_RELATION_PROFILE_BLOCK_RE_SRC = 11
private const val BILIBILI_RELATION_COMMENT_BLOCK_RE_SRC = 15
private const val BLOCKED_UP_PROFILE_REFRESH_DELAY_MS = 120L
private const val BLOCKED_UP_SHARE_MARKER = "BILIPAI_BLOCKED_UPS_V1"

data class BlockedUpImportItem(
    val mid: Long,
    val name: String = "",
    val face: String = "",
    val sign: String = "",
    val level: Int? = null,
    val vipLabel: String = "",
    val officialTitle: String = "",
    val follower: Long? = null,
    val archiveCount: Int? = null,
    val isDeleted: Boolean = false
)

data class BlockedUpImportResult(
    val importedCount: Int,
    val existingCount: Int,
    val failedCount: Int,
    val message: String
)







data class BlockedUpMetadataRefreshResult(
    val updatedCount: Int,
    val deletedCount: Int,
    val failedCount: Int,
    val message: String
)



@Serializable
private data class BlockedUpSharePayload(
    val version: Int = 1,
    val items: List<BlockedUpShareItem>
)

@Serializable
private data class BlockedUpShareItem(
    val mid: Long,
    val name: String = "",
    val face: String = "",
    val sign: String = "",
    val level: Int? = null,
    val vipLabel: String = "",
    val officialTitle: String = "",
    val follower: Long? = null,
    val archiveCount: Int? = null,
    val isDeleted: Boolean = false
)

private val blockedUpShareJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

internal data class BlockedUpImportPlan(
    val itemsToInsert: List<BlockedUpImportItem>,
    val existingCount: Int,
    val failedCount: Int
)

internal fun buildBlockedUpImportPlan(
    existingMids: Set<Long>,
    items: List<BlockedUpImportItem>
): BlockedUpImportPlan {
    var existingCount = 0
    var failedCount = 0
    val seenMids = mutableSetOf<Long>()
    val itemsToInsert = mutableListOf<BlockedUpImportItem>()

    items.forEach { item ->
        val mid = item.mid
        if (mid <= 0L || !seenMids.add(mid)) {
            failedCount += 1
            return@forEach
        }
        if (mid in existingMids) {
            existingCount += 1
            return@forEach
        }
        itemsToInsert += item.copy(
            name = item.name.ifBlank { "UP主$mid" }
        )
    }

    return BlockedUpImportPlan(
        itemsToInsert = itemsToInsert,
        existingCount = existingCount,
        failedCount = failedCount
    )
}

internal fun buildBlockedUpImportMessage(
    importedCount: Int,
    existingCount: Int,
    failedCount: Int
): String {
    return when {
        importedCount > 0 -> "已导入 $importedCount 个 B站黑名单用户，$existingCount 个已存在"
        existingCount > 0 && failedCount == 0 -> "B站黑名单已同步，$existingCount 个用户已在本地黑名单中"
        failedCount > 0 -> "导入完成，$importedCount 个新增，$existingCount 个已存在，$failedCount 个无效条目已跳过"
        else -> "没有可导入的 B站黑名单用户"
    }
}



internal fun buildBlockedUpMetadataRefreshMessage(
    updatedCount: Int,
    deletedCount: Int,
    failedCount: Int
): String {
    return when {
        updatedCount == 0 && deletedCount == 0 && failedCount == 0 -> "黑名单为空，无需刷新资料"
        failedCount == 0 && deletedCount == 0 -> "已刷新 $updatedCount 个黑名单用户资料"
        failedCount == 0 -> "已刷新 $updatedCount 个用户资料，$deletedCount 个账号疑似已注销"
        else -> "刷新完成：$updatedCount 个成功，$deletedCount 个疑似已注销，$failedCount 个失败"
    }
}

fun buildBlockedUpShareText(blockedUps: List<BlockedUp>): String {
    if (blockedUps.isEmpty()) {
        return "BiliPai 黑名单导出\n暂无黑名单用户\n\n$BLOCKED_UP_SHARE_MARKER\n${buildBlockedUpShareJson(blockedUps)}"
    }

    val body = blockedUps.mapIndexed { index, up ->
        val name = up.name.ifBlank { "UP主${up.mid}" }
        val statusText = if (up.isDeleted) "疑似已注销" else "正常"
        val profileUrl = "https://space.bilibili.com/${up.mid}"
        buildString {
            append("${index + 1}. $name\n")
            append("UID: ${up.mid}\n")
            append("状态: $statusText")
            up.level?.let { append(" · LV$it") }
            up.vipLabel.takeIf { it.isNotBlank() }?.let { append(" · $it") }
            up.officialTitle.takeIf { it.isNotBlank() }?.let { append(" · $it") }
            append('\n')
            up.follower?.let { append("粉丝: $it\n") }
            up.archiveCount?.let { append("投稿: $it\n") }
            up.sign.takeIf { it.isNotBlank() }?.let { append("签名: ${it.trim()}\n") }
            append("主页: $profileUrl")
        }
    }.joinToString(separator = "\n\n")

    return "BiliPai 黑名单导出（${blockedUps.size} 个用户）\n\n$body\n\n$BLOCKED_UP_SHARE_MARKER\n" +
        buildBlockedUpShareJson(blockedUps)
}

fun buildBlockedUpShareJson(blockedUps: List<BlockedUp>): String {
    return blockedUpShareJson.encodeToString(buildBlockedUpSharePayload(blockedUps))
}

fun parseBlockedUpShareText(text: String): List<BlockedUpImportItem> {
    parseBlockedUpSharePayload(text.trim())?.let { payload ->
        return payload.toImportItems()
    }

    val markerIndex = text.indexOf(BLOCKED_UP_SHARE_MARKER)
    if (markerIndex >= 0) {
        val payloadText = text
            .substring(markerIndex + BLOCKED_UP_SHARE_MARKER.length)
            .trim()
        parseBlockedUpSharePayload(payloadText)?.let { payload ->
            return payload.toImportItems()
        }
    }

    return Regex("""(?m)^\s*UID:\s*(\d+)\s*$""")
        .findAll(text)
        .mapNotNull { match ->
            val mid = match.groupValues.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            BlockedUpImportItem(mid = mid)
        }
        .toList()
}

private fun buildBlockedUpSharePayload(blockedUps: List<BlockedUp>): BlockedUpSharePayload {
    return BlockedUpSharePayload(
        items = blockedUps.map { up ->
            BlockedUpShareItem(
                mid = up.mid,
                name = up.name,
                face = up.face,
                sign = up.sign,
                level = up.level,
                vipLabel = up.vipLabel,
                officialTitle = up.officialTitle,
                follower = up.follower,
                archiveCount = up.archiveCount,
                isDeleted = up.isDeleted
            )
        }
    )
}

private fun parseBlockedUpSharePayload(text: String): BlockedUpSharePayload? {
    if (!text.startsWith("{")) return null
    return runCatching {
        blockedUpShareJson.decodeFromString<BlockedUpSharePayload>(text)
    }.getOrNull()
}

private fun BlockedUpSharePayload.toImportItems(): List<BlockedUpImportItem> {
    return items.map { item ->
        BlockedUpImportItem(
            mid = item.mid,
            name = item.name,
            face = item.face,
            sign = item.sign,
            level = item.level,
            vipLabel = item.vipLabel,
            officialTitle = item.officialTitle,
            follower = item.follower,
            archiveCount = item.archiveCount,
            isDeleted = item.isDeleted
        )
    }
}

