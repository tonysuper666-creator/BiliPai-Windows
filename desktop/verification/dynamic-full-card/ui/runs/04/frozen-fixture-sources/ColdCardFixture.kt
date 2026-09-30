package com.bilipai.desktop.ui.productdynamiccardproof

import com.android.purebilibili.data.model.response.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.*

/** Synthetic server payloads, not a second application model. */
internal fun rawCards():List<DynamicItem> = Json.decodeFromString("""[
 {"id_str":"123","type":"DYNAMIC_TYPE_WORD","basic":{"comment_type":17,"comment_id_str":"123"},"modules":{"module_author":{"mid":77,"name":"User-77","face":"","pub_time":"合成时间"},"module_dynamic":{"desc":{"text":"Counter-A","rich_text_nodes":[{"type":"RICH_TEXT_NODE_TYPE_TEXT","text":"Counter-A"}]}},"module_stat":{"like":{"count":17,"status":false},"comment":{"count":5},"forward":{"count":9}}}},
 {"id_str":"124","type":"DYNAMIC_TYPE_WORD","modules":{"module_author":{"mid":77,"name":"User-77","face":"","pub_time":"合成时间"},"module_dynamic":{"desc":{"text":"Sibling-B","rich_text_nodes":[{"type":"RICH_TEXT_NODE_TYPE_TEXT","text":"Sibling-B"}]}},"module_stat":{"like":{"count":31,"status":false},"comment":{"count":6},"forward":{"count":12}}}},
 {"id_str":"125","type":"DYNAMIC_TYPE_WORD","modules":{"module_author":{"mid":42,"name":"Self-42","face":"","pub_time":"合成时间"},"module_dynamic":{"desc":{"text":"Delete-Self","rich_text_nodes":[{"type":"RICH_TEXT_NODE_TYPE_TEXT","text":"Delete-Self"}]}},"module_stat":{"like":{"count":23,"status":false},"comment":{"count":7},"forward":{"count":8}},"module_more":{"three_point_items":[{"type":"THREE_POINT_DELETE","label":"删除","params":{"dyn_id_str":"125","dyn_type":4,"rid_str":"125"},"modal":{"title":"Fixture delete confirm","content":"合成数据删除确认","confirm":"确认删除 fixture125","cancel":"取消"}}]}}},
 {"id_str":"126","type":"DYNAMIC_TYPE_WORD","modules":{"module_author":{"mid":77,"name":"User-77","face":"","pub_time":"合成时间"},"module_dynamic":{"desc":{"text":"Fold-Anchor","rich_text_nodes":[{"type":"RICH_TEXT_NODE_TYPE_TEXT","text":"Fold-Anchor"}]}},"module_stat":{"like":{"count":41,"status":false},"comment":{"count":2},"forward":{"count":15}},"module_fold":{"ids":["127"],"statement":"展开1条相关动态"}}},
 {"id_str":"127","type":"DYNAMIC_TYPE_WORD","visible":false,"modules":{"module_author":{"mid":77,"name":"User-77","face":"","pub_time":"合成时间"},"module_dynamic":{"desc":{"text":"Hidden-Related","rich_text_nodes":[{"type":"RICH_TEXT_NODE_TYPE_TEXT","text":"Hidden-Related"}]}},"module_stat":{"like":{"count":51,"status":false},"comment":{"count":3},"forward":{"count":16}}}}
 ]""")

fun main(args:Array<String>):Unit=runBlocking {
    val root=Path.of(args[1]);Files.createDirectories(root)
    val sessions=DesktopSessionStore(root.resolve("task-session.json"))
    val store=DesktopPluginStore(root.resolve("actual-global"));val cache=DesktopDynamicCache(sessions,store){1L}
    try {
        when(args[0]){
            "seed"->{sessions.saveAccount(mapOf("SESSDATA" to "task-only-dynamic-owner","bili_jct" to "task-only-dynamic-csrf"),AccountSummary(42,"Fixture",""))
                val owner=checkNotNull(cache.openCurrent());owner.saveTimeline(rawCards());cache.flush()
                check(owner.cachedAllItems.value.size==5);println("PASS independent original cache seed 5 synthetic raw cards")}
            "read"->{check(sessions.account.value?.mid==42L);val owner=checkNotNull(cache.openCurrent());val rows=owner.cachedAllItems.value
                check(rows.map{it.id_str}==listOf("123","124","126","127"));check(owner.notInterestedIds.value==setOf("124"))
                val a=rows.first{it.id_str=="123"};check(a.modules.module_stat!!.like.count==17&&!a.modules.module_stat!!.like.status)
                check(a.modules.module_stat!!.forward.count==10)
                val b=rows.first{it.id_str=="124"};check(b.modules.module_stat!!.like.count==31&&b.modules.module_stat!!.forward.count==12)
                check(rows.first{it.id_str=="126"}.modules.module_fold==null);check(rows.first{it.id_str=="127"}.visible)
                Files.writeString(root.resolve("independent-cold-card-read.json"),buildJsonObject{
                    put("passed",true);put("separateJvm",true);put("actualCacheActor",true);put("rawIds",JsonArray(rows.map{JsonPrimitive(it.id_str)}))
                    put("likeAfterUnlike",17);put("likedAfterUnlike",false);put("forwardAfterConfirmedRepost",10)
                    put("siblingUnchanged",true);put("deletedSelfAbsent",true);put("hiddenRelatedVisible",true);put("foldBarRemoved",true)
                    put("notInterestedRetainsRawSibling",true);put("notInterestedIds",JsonArray(owner.notInterestedIds.value.map(::JsonPrimitive)))
                    put("realAccountOrSocket",false)}.toString())
                println("PASS cold raw like/unlike/repost/delete/unfold/not-interest persistence")}
            else->error("Unknown task mode")
        }
    }finally{cache.shutdownForRestore();store.freezeWrites()}
}
