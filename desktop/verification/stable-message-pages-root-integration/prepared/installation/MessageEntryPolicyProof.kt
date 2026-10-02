package com.bilipai.desktop.ui

import com.android.purebilibili.feature.home.components.BottomNavItem
import com.android.purebilibili.navigation.resolveBottomPagerPageForRoute
import com.android.purebilibili.navigation3.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

fun main(args:Array<String>) {
    val keys=listOf<BiliPaiNavKey>(BiliPaiNavKey.Inbox,BiliPaiNavKey.ReplyMe,BiliPaiNavKey.AtMe,
        BiliPaiNavKey.LikeMe,BiliPaiNavKey.SystemNotice,BiliPaiNavKey.Chat(7,1,"Fixture"))
    val all=BottomNavItem.entries.toList()
    val configurations=listOf(all,all.reversed(),listOf(BottomNavItem.PROFILE,BottomNavItem.HOME),emptyList())
    for(key in keys)for(items in configurations)check(resolveBottomPagerPageForRoute(key.toLegacyRoute(),items)==null)
    val inbox=BiliPaiNavBackStackController(listOf(BiliPaiNavKey.MainHost)).push(BiliPaiNavKey.Inbox)
    check(inbox.backStack==listOf(BiliPaiNavKey.MainHost,BiliPaiNavKey.Inbox))
    for(key in keys.drop(1)) {
        val deeper=inbox.push(key)
        check(deeper.currentKey==key)
        val returned=deeper.pop()
        check(returned.backStack==inbox.backStack)
        val removed=resolveRemovedNavigation3SaveableStateKeys(deeper.backStack,returned.backStack)
        check(removed.size==1)
        check(resolveRemovedNavigation3SaveableStateKeys(inbox.backStack,returned.backStack).isEmpty())
    }
    check(inbox.pop().backStack==listOf(BiliPaiNavKey.MainHost))
    val classes=listOf(BottomNavItem::class.java,BiliPaiNavKey::class.java,
        BiliPaiNavBackStackController::class.java,
        Class.forName("com.android.purebilibili.navigation.DesktopOriginalRootPagerPolicyKt"),
        Class.forName("com.android.purebilibili.navigation3.BiliPaiNavKeyMappingPolicyKt"))
    val report=buildJsonObject {
        put("passed",true);put("actualProductSnapshot",86);put("messageKeyTypes",6);put("visibleTabConfigurations",4)
        put("physicalInputStartingKey","MainHost (actual Profile page is hosted, not a duplicate physical Profile entry)")
        put("pageStateAcceptance",false);put("rootWindowAcceptance",false);put("productionOverrides",0)
        put("loadedClasses",JsonArray(classes.map { type->buildJsonObject {
            val resource="/"+type.name.replace('.','/')+".class"
            val bytes=checkNotNull(type.getResourceAsStream(resource)).use {it.readBytes()}
            put("name",type.name);put("codeSource",type.protectionDomain.codeSource.location.toString())
            put("sha256Bytes",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})
        } }))
    }
    Files.writeString(Path.of(args.single()),report.toString())
    println("PASS actual original pager exclusion and typed Inbox/back/saveable-key policies; no Root runtime claim")
}
