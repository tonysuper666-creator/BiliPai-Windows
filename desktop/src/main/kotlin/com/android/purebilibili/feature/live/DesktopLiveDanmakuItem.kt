package com.android.purebilibili.feature.live

// 上游把直播弹幕 UI 模型声明在 feature/live/LivePlayerViewModel.kt 内部，而那个文件是 Media3 播放器
// （桌面直播播放走 mpv），整份不能进桌面编译。模型本身没有 Android 依赖，桌面单独放在这里，
// 字段与上游 v0.6.7 逐项一致；上游改了模型就同步改这里，并用 verifyUniqueClassNames 保证只有一份。

@kotlinx.serialization.Serializable
data class LiveDanmakuItem(
    val text: String,
    val color: Int = 16777215, // Default White
    val mode: Int = 1,         // 1=Scroll, 4=Bottom, 5=Top
    val uid: Long = 0,
    val uname: String = "",
    val isSelf: Boolean = false, // 是否自己发送
    val emoticon: com.android.purebilibili.data.model.response.LiveMessageEmote? = null,
    val inlineEmotes: Map<String, com.android.purebilibili.data.model.response.LiveMessageEmote> = emptyMap(),
    val emoticonUrl: String? = null, // [NEW] B站自定义表情 URL
    // [新增] 视觉优化字段
    val medal: com.android.purebilibili.data.model.response.LiveMessageMedal? = null,
    val rawMessage: kotlinx.serialization.json.JsonObject? = null,
    val medalName: String = "",
    val medalLevel: Int = 0,
    val medalColor: Int = 0,
    val userLevel: Int = 0,
    val isAdmin: Boolean = false,
    val guardLevel: Int = 0, // 0=none, 1=总督, 2=提督, 3=舰长
    val replyToName: String = "",
    val replyToUid: Long = 0L,
    val isSuperChat: Boolean = false,
    val superChatId: Long = 0,
    val superChatPrice: String = "",
    val superChatBackgroundColor: Int = 0,
    val superChatHeaderColor: Int = 0,
    val superChatMessageColor: Int = 0,
    val superChatNameColor: Int = 0,
    val superChatFace: String = "",
    val superChatFaceFrame: String = "",
    val superChatHeaderImage: String = "",
    val superChatRawInfo: String = "",
    val dmType: Int = 0,
    val idStr: String = "",
    val reportTs: Long = 0,
    val reportSign: String = "",
    val superChatToken: String = "",
    val superChatReportTs: Long = 0,
    // SC 展示时长（秒），用于全屏大字浮层自动消失
    val superChatDuration: Int = 0,
    val superChatStartTime: Long = 0,
    val superChatEndTime: Long = 0,
    val isSystem: Boolean = false,
    val originRoomId: Long = 0L,
)
