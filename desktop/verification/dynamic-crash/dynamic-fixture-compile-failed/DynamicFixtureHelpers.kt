package com.bilipai.desktop.ui
import com.android.purebilibili.data.model.response.DynamicItem
import kotlinx.serialization.json.Json

internal fun fixtureDynamic(id:String,ts:Long,mid:Long=0,visible:Boolean=true):DynamicItem=Json.decodeFromString(
    """{"id_str":"$id","type":"DYNAMIC_TYPE_WORD","visible":$visible,"modules":{
      "module_author":{"mid":$mid,"name":"Fixture","face":"","pub_ts":$ts},
      "module_dynamic":{"desc":{"text":"Dynamic-$id"}}}}""")

internal fun assertActualDynamicProductOrigins(output:java.nio.file.Path) {
    val names=listOf(
        "com.bilipai.desktop.ui.DesktopDynamicTimelineState",
        "com.bilipai.desktop.ui.DesktopDynamicTimelineKt",
        "com.bilipai.desktop.ui.CommunityDynamicScreensKt",
        "com.bilipai.desktop.settings.DesktopDynamicTimelinePreferences",
        "com.bilipai.desktop.settings.DesktopDynamicTimelineSettingsKt",
        "com.bilipai.desktop.settings.DesktopHomeRecommendationSettingsKt",
        "com.android.purebilibili.core.store.DesktopDynamicSettings",
        "com.android.purebilibili.data.repository.DesktopOriginalDynamicTimelineRepository",
        "com.android.purebilibili.core.ui.components.FeedVerticalStaggeredGridKt",
        "com.android.purebilibili.feature.dynamic.DesktopOriginalDynamicTimelinePolicyKt",
        "com.bilipai.desktop.plugins.DesktopPluginStore",
        "com.bilipai.desktop.data.DesktopRepository",
        "com.bilipai.desktop.data.DesktopCommunityRepository",
        "com.android.purebilibili.core.network.DynamicApi")
    val pins=names.map {name->
        val cls=Class.forName(name);val file=java.nio.file.Path.of(cls.protectionDomain.codeSource.location.toURI())
        check(file.fileName.toString()=="main-kotlin.jar")
        val resource=name.replace('.','/')+".class"
        val bytes=cls.classLoader.getResourceAsStream(resource)!!.use {it.readBytes()}
        kotlinx.serialization.json.buildJsonObject {
            put("class",kotlinx.serialization.json.JsonPrimitive(name))
            put("jar",kotlinx.serialization.json.JsonPrimitive(file.toString()))
            put("sha256Bytes",kotlinx.serialization.json.JsonPrimitive(java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}))
        }
    }
    java.nio.file.Files.createDirectories(output)
    java.nio.file.Files.writeString(output.resolve("actual-product-class-identities.json"),kotlinx.serialization.json.JsonArray(pins).toString())
}
