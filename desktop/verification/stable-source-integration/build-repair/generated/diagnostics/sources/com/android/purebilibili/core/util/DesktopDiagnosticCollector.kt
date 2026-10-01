// GENERATED from app/src/main/java/com/android/purebilibili/core/util/Logger.kt; do not edit.
// LF-normalized SHA-256: aa4af545b0c4a59fd7e57d5b2e64b3c1506b7732042a51d1643ca331a05a7c5e
package com.android.purebilibili.core.util
import java.text.SimpleDateFormat
import java.util.*

internal class DesktopDiagnosticCollector(
    private val clock: () -> Long = System::currentTimeMillis,
    private val persist: (LogEntry, Boolean) -> Unit,
) {
    private val MAX_ENTRIES = 1000
    private val DUPLICATE_SUPPRESS_WINDOW_MS = 250L
    private val MAX_PERSISTED_LOG_BYTES = 256 * 1024
    private val MAX_BASIC_LOG_BYTES = 64 * 1024
    private val MAX_LOG_MESSAGE_CHARS = 16 * 1024
    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)
    private var lastEntryFingerprint: String? = null
    private var lastEntryTimestamp: Long = 0L

data class LogEntry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String
    ) {
        fun format(): String {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
            return "[$time] $level/$tag: $message"
        }
    }

fun add(
    level: String,
    tag: String,
    message: String,
    persistToDisk: Boolean = true,
    basicDiagnostic: Boolean = false,
) {
    val now = clock()
    val sanitizedTag = sanitizeMessage(tag).take(80)
    val sanitizedMessage = sanitizeMessage(message).let { value ->
        if (value.length <= MAX_LOG_MESSAGE_CHARS) value
        else value.take(MAX_LOG_MESSAGE_CHARS) + "…[truncated]"
    }
    val fingerprint = "$level|$sanitizedTag|$sanitizedMessage"
    var entryToPersist: LogEntry? = null
    synchronized(lock) {
        // 高频重复日志直接抑制，避免日志风暴拖垮主线程
        if (fingerprint == lastEntryFingerprint &&
            now - lastEntryTimestamp <= DUPLICATE_SUPPRESS_WINDOW_MS) {
            lastEntryTimestamp = now
            return
        }

        lastEntryFingerprint = fingerprint
        lastEntryTimestamp = now

        entryToPersist = LogEntry(
            timestamp = now,
            level = level,
            tag = sanitizedTag,
            message = sanitizedMessage
        )
        buffer.addLast(entryToPersist)

        while (buffer.size > MAX_ENTRIES) {
            if (buffer.isNotEmpty()) {
                buffer.pollFirst()
            } else {
                break
            }
        }
    }

    if (persistToDisk) {
        entryToPersist?.let { persist(it, basicDiagnostic) }
    }
}

fun getEntries(): List<LogEntry> = synchronized(lock) { buffer.toList() }

fun getCount(): Int = synchronized(lock) { buffer.size }

fun clear() {
    synchronized(lock) {
        buffer.clear()
        lastEntryFingerprint = null
        lastEntryTimestamp = 0L
    }
}

fun clearRuntimeDiagnostics() {
    synchronized(lock) {
        buffer.removeAll { it.level != "W" && it.level != "E" && it.tag != "StartupDiagnostics" }
        lastEntryFingerprint = null
    }
}

    companion object {
        internal fun sanitizeMessage(message: String): String {
            var sanitized = message

            // ========== Cookie 脱敏 ==========
            sanitized = sanitized.replace(Regex("SESSDATA=[^;\\s]+"), "SESSDATA=***")
            sanitized = sanitized.replace(Regex("bili_jct=[^;\\s]+"), "bili_jct=***")
            sanitized = sanitized.replace(Regex("DedeUserID=[^;\\s]+"), "DedeUserID=***")
            sanitized = sanitized.replace(Regex("DedeUserID__ckMd5=[^;\\s]+"), "DedeUserID__ckMd5=***")
            sanitized = sanitized.replace(Regex("sid=[^;\\s]+"), "sid=***")
            sanitized = sanitized.replace(Regex("buvid3=[^;\\s]+"), "buvid3=***")
            sanitized = sanitized.replace(Regex("buvid4=[^;\\s]+"), "buvid4=***")
            sanitized = sanitized.replace(Regex("b_nut=[^;\\s]+"), "b_nut=***")
            sanitized = sanitized.replace(Regex("_uuid=[^;\\s]+"), "_uuid=***")

            // ========== Token / Key 脱敏 ==========
            sanitized = sanitized.replace(Regex("access_token=[^&\\s]+"), "access_token=***")
            sanitized = sanitized.replace(Regex("refresh_token=[^&\\s]+"), "refresh_token=***")
            sanitized = sanitized.replace(Regex("access_key=[^&\\s]+"), "access_key=***")
            sanitized = sanitized.replace(Regex("appkey=[^&\\s]+"), "appkey=***")
            sanitized = sanitized.replace(Regex("sign=[^&\\s]+"), "sign=***")
            sanitized = sanitized.replace(Regex("csrf=[^&\\s]+"), "csrf=***")
            sanitized = sanitized.replace(Regex("\"token\":\"[^\"]+\""), "\"token\":\"***\"")
            sanitized = sanitized.replace(Regex("\"csrf\":\"[^\"]+\""), "\"csrf\":\"***\"")
            sanitized = sanitized.replace(
                Regex("(?i)Authorization\\s*[:=]\\s*[^\\r\\n]+"),
                "Authorization: ***"
            )
            sanitized = sanitized.replace(Regex("Bearer\\s+[^\\s]+"), "Bearer ***")
            sanitized = sanitized.replace(
                Regex("(?i)(cookie|set-cookie)\\s*[:=]\\s*[^\\r\\n]+"),
                "$1: ***"
            )
            sanitized = sanitized.replace(
                Regex("(?i)(password|passwd|pwd|sms_code|captcha|challenge|validate)[=:]\\s*[^&\\s,}]+"),
                "$1=***"
            )

            // ========== 用户 ID 脱敏 ==========
            // Bilibili mid/uid (通常为 6-11 位数字，在特定上下文中)
            sanitized = sanitized.replace(Regex("mid[=:]\\s*\\d{4,}"), "mid=***")
            sanitized = sanitized.replace(Regex("\"mid\":\\s*\\d+"), "\"mid\":***")
            sanitized = sanitized.replace(Regex("uid[=:]\\s*\\d{4,}"), "uid=***")
            sanitized = sanitized.replace(Regex("\"uid\":\\s*\\d+"), "\"uid\":***")
            sanitized = sanitized.replace(Regex("vmid[=:]\\s*\\d+"), "vmid=***")

            // ========== 手机号脱敏 (11位中国手机号) ==========
            sanitized = sanitized.replace(Regex("\\b1[3-9]\\d{9}\\b"), "1**********")

            // ========== 邮箱脱敏 ==========
            sanitized = sanitized.replace(Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")) { 
                val email = it.value
                val atIndex = email.indexOf('@')
                if (atIndex > 2) {
                    email.substring(0, 2) + "***" + email.substring(atIndex)
                } else {
                    "***" + email.substring(atIndex)
                }
            }

            // ========== IP 地址脱敏 ==========
            // IPv4
            sanitized = sanitized.replace(Regex("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b")) {
                val parts = it.value.split(".")
                if (parts.size == 4 && parts.all { p -> p.toIntOrNull() in 0..255 }) {
                    "${parts[0]}.***.***.*"
                } else {
                    it.value
                }
            }
            // IPv6 (简化处理)
            sanitized = sanitized.replace(Regex("\\b[0-9a-fA-F:]{15,}\\b"), "***:***:***")

            // ========== MAC 地址脱敏 ==========
            sanitized = sanitized.replace(Regex("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}"), "**:**:**:**:**:**")

            // ========== 文件路径脱敏 (隐藏用户名) ==========
            // Android 路径
            sanitized = sanitized.replace(Regex("/data/user/\\d+/[^/]+/"), "/data/user/0/***/")
            sanitized = sanitized.replace(Regex("/storage/emulated/\\d+/"), "/storage/emulated/0/")
            // 通用 home 目录
            sanitized = sanitized.replace(Regex("/home/[^/]+/"), "/home/***/")
            sanitized = sanitized.replace(Regex("/Users/[^/]+/"), "/Users/***/")

            // ========== 设备标识脱敏 ==========
            sanitized = sanitized.replace(Regex("device_id=[^&\\s]+"), "device_id=***")
            sanitized = sanitized.replace(Regex("\"device_id\":\"[^\"]+\""), "\"device_id\":\"***\"")
            sanitized = sanitized.replace(Regex("android_id=[^&\\s]+"), "android_id=***")
            sanitized = sanitized.replace(Regex("imei=[^&\\s]+"), "imei=***")

            // ========== 敏感 JSON 字段脱敏 ==========
            sanitized = sanitized.replace(Regex("\"face\":\"[^\"]+\""), "\"face\":\"***\"")
            sanitized = sanitized.replace(Regex("\"tel\":\"[^\"]+\""), "\"tel\":\"***\"")
            sanitized = sanitized.replace(Regex("\"name\":\"[^\"]{2,}\"")) {
                // 保留名字首字符
                val name = it.value
                val start = name.indexOf(":\"") + 2
                val end = name.lastIndexOf("\"")
                if (end > start + 1) {
                    "\"name\":\"${name[start]}***\""
                } else {
                    it.value
                }
            }

            // ========== 🎬 视频内容脱敏（保护用户观看记录隐私） ==========
            // 视频 BVID
            sanitized = sanitized.replace(Regex("BV[0-9A-Za-z]{10}"), "BV***")
            // 视频 AID/AV 号
            sanitized = sanitized.replace(Regex("\\bav\\d{4,}\\b", RegexOption.IGNORE_CASE), "av***")
            sanitized = sanitized.replace(Regex("\"aid\":\\s*\\d+"), "\"aid\":***")
            // CID
            sanitized = sanitized.replace(Regex("\\bcid[=:]\\s*\\d+"), "cid=***")
            sanitized = sanitized.replace(Regex("\"cid\":\\s*\\d+"), "\"cid\":***")
            // 直播房间号
            sanitized = sanitized.replace(Regex("room_id[=:]\\s*\\d+"), "room_id=***")
            sanitized = sanitized.replace(Regex("roomId[=:]\\s*\\d+"), "roomId=***")
            // Season ID (番剧)
            sanitized = sanitized.replace(Regex("season_id[=:]\\s*\\d+"), "season_id=***")
            sanitized = sanitized.replace(Regex("ep_id[=:]\\s*\\d+"), "ep_id=***")

            // ========== 🔍 搜索关键词脱敏 ==========
            sanitized = sanitized.replace(Regex("keyword=[^&\\s]+"), "keyword=***")
            sanitized = sanitized.replace(Regex("\"keyword\":\"[^\"]+\""), "\"keyword\":\"***\"")
            sanitized = sanitized.replace(Regex("Search:\\s*[^\\n]+"), "Search: ***")

            // 私信、评论草稿等用户输入内容不进入诊断日志。
            sanitized = sanitized.replace(
                Regex("(?i)\\b(content|message_text|query)[=:]\\s*[^&\\r\\n]+"),
                "$1=***"
            )
            sanitized = sanitized.replace(
                Regex("(?i)\"(content|message_text|query)\"\\s*:\\s*\"[^\"]*\""),
                "\"$1\":\"***\""
            )

            // ========== 📝 视频标题脱敏（仅保留前两个字符） ==========
            sanitized = sanitized.replace(Regex("video_title=[^&\\s]{3,}")) { 
                val title = it.value.substringAfter("=")
                "video_title=${title.take(2)}***"
            }
            sanitized = sanitized.replace(Regex("\"title\":\"[^\"]{3,}\"")) {
                val content = it.value
                val titleStart = content.indexOf(":\"") + 2
                val title = content.substring(titleStart, content.length - 1)
                "\"title\":\"${title.take(2)}***\""
            }

            return sanitized
        }
    }
}
