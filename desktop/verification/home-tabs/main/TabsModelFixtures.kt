package com.bilipai.desktop.ui
import com.android.purebilibili.data.model.response.*
import kotlinx.serialization.json.Json
internal fun tabsDynamic(id:String,mid:Long=42,type:String="DYNAMIC_TYPE_WORD",visible:Boolean=true):DynamicItem=Json.decodeFromString(
    """{"id_str":"$id","type":"$type","visible":$visible,"modules":{"module_author":{"mid":$mid,"name":"User-$mid","face":"","pub_ts":100},"module_dynamic":{"desc":{"text":"Dynamic-$id"}}}}""")
internal fun tabsResponse(rows:List<DynamicItem>,offset:String="",more:Boolean=false)=DynamicFeedResponse(data=DynamicFeedData(rows,offset,more))
