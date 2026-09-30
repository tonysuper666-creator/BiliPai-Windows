package com.bilipai.desktop.ui.productcacheproof

import com.android.purebilibili.data.model.response.DynamicItem
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.*

internal fun fixtureDynamic(id:String,label:String,mid:Long=77):DynamicItem = Json.decodeFromString(
    """{"id_str":"$id","type":"DYNAMIC_TYPE_WORD","modules":{"module_author":{"mid":$mid,"name":"User-77","face":"","pub_ts":1,"pub_time":"合成时间"},"module_dynamic":{"desc":{"text":"$label","rich_text_nodes":[]}}}}""")

/** A separate task-only JVM creates the original cache through its real actor, then exits. */
fun main(args:Array<String>):Unit=runBlocking {
    val root=Path.of(args[1]);Files.createDirectories(root)
    val sessions=DesktopSessionStore(root.resolve("task-session.json"))
    val store=DesktopPluginStore(root.resolve("actual-global"))
    val cache=DesktopDynamicCache(sessions,store){1L}
    try {
        when(args[0]) {
            "seed" -> {
                sessions.saveAccount(mapOf("SESSDATA" to "task-only-cache-owner-a","bili_jct" to "task-only-cache-csrf"),AccountSummary(42,"Fixture", ""))
                val session=checkNotNull(cache.openCurrent())
                session.saveTimeline(listOf(fixtureDynamic("2","Cached-2"),fixtureDynamic("1","Cached-1")))
                cache.flush();check(session.cachedAllItems.value.map{it.id_str}==listOf("2","1"))
                println("PASS independent cold seed writer, actual 2 raw DynamicItems, synthetic owner")
            }
            "read-guest" -> {
                check(sessions.account.value==null)
                val session=checkNotNull(cache.openCurrent());check(session.owner.mid==0L)
                check(session.cachedAllItems.value.isEmpty())
                check(session.notInterestedIds.value==setOf("guest-local-hidden"))
                Files.writeString(root.resolve("independent-cold-guest-read.json"),buildJsonObject{
                    put("passed",true);put("separateJvm",true);put("actualActor",true);put("guestMid",0)
                    put("rawCacheEmpty",true);put("originalNormalizedIds",JsonArray(session.notInterestedIds.value.map(::JsonPrimitive)))
                    put("socketOrCredential",false)
                }.toString())
                println("PASS independent cold guest reader, original normalized local ID and no previous private raw cache")
            }
            else -> error("Unknown task fixture mode")
        }
    } finally {cache.shutdownForRestore();store.freezeWrites()}
}
