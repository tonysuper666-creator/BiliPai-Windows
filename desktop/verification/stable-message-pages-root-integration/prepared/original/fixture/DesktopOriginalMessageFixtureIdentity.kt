package com.bilipai.desktop.ui
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal fun saveMessageLoadedIdentity(path:Path) {
    val names=listOf("com.android.purebilibili.feature.message.InboxViewModel","com.android.purebilibili.feature.message.ChatViewModel",
        "com.android.purebilibili.feature.message.InboxScreenKt","com.android.purebilibili.feature.message.ChatScreenKt",
        "com.android.purebilibili.feature.message.MessageCenterScreenKt","com.android.purebilibili.feature.message.feed.ReplyMeViewModel",
        "com.android.purebilibili.feature.message.feed.SystemNoticeViewModel","com.android.purebilibili.data.repository.DesktopOriginalMessageRepository",
        "com.bilipai.desktop.ui.DesktopOriginalMessagePagesRoot","com.bilipai.desktop.ui.DesktopMessagePageAdmission",
        "com.bilipai.desktop.data.DesktopCommunityRepository","com.bilipai.desktop.data.DesktopRepository")
    val rows=names.map { name ->
        val type=Class.forName(name);val source=Path.of(type.protectionDomain.codeSource.location.toURI()).toRealPath()
        check(source.fileName.toString()=="prospective.jar") {"Unexpected product class origin"}
        val bytes=type.getResourceAsStream("/"+name.replace('.','/')+".class")!!.use {it.readAllBytes()}
        buildJsonObject {put("class",name);put("codeSource",source.toString());put("sha256Bytes",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})}
    }
    Files.writeString(path,JsonArray(rows).toString())
}
