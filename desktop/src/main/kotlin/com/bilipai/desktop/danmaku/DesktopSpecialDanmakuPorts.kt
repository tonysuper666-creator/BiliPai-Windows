package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.parser.AdvancedDanmakuData
import com.android.purebilibili.danmaku.parser.DanmakuProto
import com.android.purebilibili.danmaku.parser.SpecialDanmakuSource
import com.android.purebilibili.danmaku.parser.bas.BasDanmaku
import com.android.purebilibili.danmaku.parser.bas.BasScriptParser
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.ResponseBody
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

/** DTO adaptation only. The whole original window owns lifetimes and absolute time. */
data class DesktopSpecialParsedDanmaku(
    val standardList: List<DanmakuComment> = emptyList(),
    val advancedList: List<AdvancedDanmakuData> = emptyList(),
    val basList: List<BasDanmaku> = emptyList(),
)

/** One context propagates through the original four parallel record tasks. Existing
 * BAS limits are reused unchanged; no parser/core algorithm or limit is relaxed. */
internal object DesktopSpecialParseScope {
    private class Budget {
        val attempts=DesktopBasDocumentBudget()
        val retained=DesktopBasDocumentBudget()
        val seen=Collections.newSetFromMap(IdentityHashMap<BasDanmaku,Boolean>())
        var bytes=0L
    }
    private val current=ThreadLocal<Budget?>()
    private fun budget()=checkNotNull(current.get()) {"Special parser requires a window budget"}
    suspend fun <T> withWindow(block:suspend ()->T):T =
        if(current.get()!=null)block() else withContext(current.asContextElement(Budget())) {block()}
    suspend fun <T> withCombined(seedItems:List<BasDanmaku>,block:suspend ()->T):T = withWindow {
        seedItems.forEach {seed(DesktopSpecialParsedDanmaku(basList=listOf(it)))}
        block()
    }
    fun beforeRead(count:Int) {
        val budget=budget()
        synchronized(budget) {
            if(count !in 1..4*1024*1024 || budget.bytes+count>DanmakuParser.MAX_DOCUMENT_BYTES)
                throw IOException("Special playback window exceeds its byte budget")
            budget.bytes+=count
        }
    }
    fun seed(parsed:DesktopSpecialParsedDanmaku) {
        val budget=budget()
        synchronized(budget) {
            for(item in parsed.basList) {
                if(item in budget.seen)continue
                val estimate=DesktopBasParseBudget.estimate(item.source)
                    ?: throw IOException("Cached special program failed resource admission")
                if(!budget.retained.reserve(estimate) || !budget.retained.retain(item))
                    throw IOException("Special playback window exceeds its retained program budget")
                budget.seen+=item
            }
        }
    }
    fun duration(source:String):Long {
        val estimate=DesktopBasParseBudget.estimate(source) ?: return 0L
        val budget=budget()
        synchronized(budget) {
            if(!budget.attempts.reserve(estimate))throw IOException("Special lifetime scan exceeds its parse budget")
        }
        return BasScriptParser.parseDurationMs(source)
    }
    fun parse(bytes:ByteArray,element:DanmakuProto.DanmakuElem):DesktopSpecialParsedDanmaku {
        if(element.progress<0 || element.mode==8)return DesktopSpecialParsedDanmaku()
        val budget=budget()
        if(element.mode==9) {
            val estimate=DesktopBasParseBudget.estimate(element.content) ?: return DesktopSpecialParsedDanmaku()
            synchronized(budget) {
                if(!budget.attempts.reserve(estimate) || !budget.retained.reserve(estimate))
                    throw IOException("Special playback window exceeds its global parse budget")
            }
        }
        // Existing bounded Windows element adapter invokes the unchanged original
        // Mode7/BAS parser, preserving full originalElement/pool metadata.
        val parsed=DanmakuParser.parseProtobuf(listOf(bytes))
        synchronized(budget) {
            for(item in parsed.bas) {
                if(!budget.retained.retain(item))throw IOException("Special playback window exceeds its program budget")
                budget.seen+=item
            }
        }
        return DesktopSpecialParsedDanmaku(parsed.comments,parsed.advanced,parsed.bas)
    }
}

/** Bounded legacy XML fixtures/downloads only; online protobuf uses real ranges. */
internal interface DesktopLegacySpecialXmlSource
internal class DesktopMemorySpecialSource(private val bytes:ByteArray):SpecialDanmakuSource,DesktopLegacySpecialXmlSource {
    init {require(bytes.size<=2*1024*1024) {"Legacy special asset is too large"}}
    override val byteLength:Long=bytes.size.toLong()
    override suspend fun readRange(offset:Long,byteCount:Int):ByteArray {
        require(offset>=0 && byteCount>0 && byteCount<=byteLength-offset)
        return bytes.copyOfRange(offset.toInt(),offset.toInt()+byteCount)
    }
}

internal object DesktopSpecialSourceLimits {
    const val MAX_SOURCES=8
    const val MAX_ENTRIES=50_000
    const val MAX_FILE_BYTES=512L*1024*1024
    const val MAX_TRANSFER_BYTES=64L*1024*1024
    const val LOOK_AHEAD_MS=3_000L
    const val REFRESH_GUARD_MS=1_500L
}

/** Cancellation closes the actual body before waiting for its blocking IO child. */
internal object DesktopSpecialBodyRead {
    suspend fun <T> use(body:ResponseBody,block:suspend (ResponseBody)->T):T = coroutineScope {
        val reading=async(Dispatchers.IO) {block(body)}
        try {reading.await()} finally {body.close()}
    }
}
