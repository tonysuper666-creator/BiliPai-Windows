// 文件路径: data/model/response/SendDanmakuResponse.kt
package com.android.purebilibili.data.model.response

import kotlinx.serialization.Serializable

/**
 * 发送弹幕响应
 */
@Serializable
data class SendDanmakuResponse(
    val code: Int = 0,
    val message: String = "",
    val data: SendDanmakuData? = null
)

@Serializable
data class SendDanmakuData(
    val dmid: Long = 0,        // 弹幕 ID
    val dmid_str: String = "", // 弹幕 ID (字符串)
    val visible: Boolean = true
)

@Serializable
data class CommandDanmakuResponse(
    val code: Int = 0,
    val message: String = "",
    val ttl: Int = 1,
    val data: CommandDanmakuData? = null
)

@Serializable
data class CommandDanmakuData(
    val command: String = "",
    val content: String = "",
    val extra: String = "",
    val id: Long = 0,
    val idStr: String = "",
    val mid: Long = 0,
    val oid: Long = 0,
    val progress: Long = 0,
    val type: Int = 0
)

/**
 * 弹幕操作响应 (撤回/点赞/举报)
 */
@Serializable
data class DanmakuActionResponse(
    val code: Int = 0,
    val message: String = "",
    val ttl: Int = 1
)

/**
 * 云端弹幕屏蔽规则 (x/dm/filter/user 系列)
 * type: 0=关键词, 1=正则, 2=UID(crc32 hex)
 */
@Serializable
data class DanmakuFilterRuleItem(
    val id: Long = 0,
    val type: Int = 0,
    val filter: String = ""
)

@Serializable
data class DanmakuFilterRulesData(
    val rule: List<DanmakuFilterRuleItem> = emptyList(),
    val rule1: List<DanmakuFilterRuleItem> = emptyList(),
    val rule2: List<DanmakuFilterRuleItem> = emptyList(),
    val toast: String? = null
)

@Serializable
data class DanmakuFilterRulesResponse(
    val code: Int = 0,
    val message: String = "",
    val data: DanmakuFilterRulesData? = null
)

@Serializable
data class DanmakuFilterAddData(
    val id: Long = 0,
    val type: Int = 0,
    val filter: String = ""
)

@Serializable
data class DanmakuFilterAddResponse(
    val code: Int = 0,
    val message: String = "",
    val data: DanmakuFilterAddData? = null
)
