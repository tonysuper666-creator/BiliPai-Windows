package com.bilipai.desktop.danmaku

import kotlin.math.abs
import com.android.purebilibili.danmaku.engine.*
import com.android.purebilibili.feature.video.danmaku.resolveDanmakuRenderLayerType
import com.android.purebilibili.feature.video.danmaku.desktopOriginalDanmakuPinnedLineCount
import com.android.purebilibili.feature.video.danmaku.desktopOriginalDanmakuItemMargin

data class DesktopDanmakuTextMetrics(val width:Int,val ascent:Double)

data class PositionedDanmaku(val comment: DanmakuComment, val x: Double, val baseline: Double, val textWidth: Int)

/** Media-time scheduler: pause freezes positions; seeking rebuilds only the visible window. */
class DanmakuScheduler(comments: List<DanmakuComment>, settings: DanmakuSettings, private val liveAdmission:Boolean, immediateLocalPhase:Any? = null) {
    private val originalComments = comments.sortedBy {it.timeSeconds}
    private var settings = settings.normalized()
    private var initialLocalPhase=immediateLocalPhase
    private var comments = prepareComments()
    internal val currentSettings:DanmakuSettings get()=settings
    private data class Scheduled(val comment: DanmakuComment, val layer: Int, val track: Int, val trackTop:Double, val baseline:Double, val width: Int, val duration: Double) {
        val end get() = comment.timeSeconds + duration
    }
    private val active = mutableListOf<Scheduled>()
    private var cursor = 0
    private var previousTime = Double.NaN
    private data class Geometry(val width:Int,val height:Int,val config:DanmakuRenderConfig) {
        fun onlyReservationChanged(next:Geometry):Boolean =
            width==next.width && height==next.height &&
                config.copy(topMarginPx=next.config.topMarginPx,lineCount=next.config.lineCount)==next.config
    }
    private var viewport:Geometry?=null

    fun resetTimeline() { active.clear(); previousTime=Double.NaN }

    fun applySettings(settings: DanmakuSettings) {
        val normalized = settings.normalized()
        if (normalized == this.settings) return
        this.settings = normalized
        initialLocalPhase=null
        comments = prepareComments()
        previousTime = Double.NaN
    }

    internal fun observeOriginalLocalInjectionPhase(current:Any) {
        if(initialLocalPhase!=null && initialLocalPhase!==current) {
            initialLocalPhase=null;comments=prepareComments();previousTime=Double.NaN
        }
    }

    private fun prepareComments(): List<DanmakuComment> {
        val injected=if(initialLocalPhase==null)emptyList() else originalComments.filter {it.originalLocalInjectionPhase===initialLocalPhase && it.originalLocalItem!=null}
        val visible=originalComments.filter {initialLocalPhase==null || it.originalLocalInjectionPhase!==initialLocalPhase || it.originalLocalItem==null}
            .filter {if(liveAdmission)settings.allowsLive(it) else settings.allows(it)}
        val ordinary=if(settings.mergeDuplicates)mergeDuplicateDanmaku(visible,settings.duplicateMergeWindowMs,settings.duplicateMergeCountThreshold) else visible
        return (ordinary+injected).sortedBy {it.timeSeconds}
    }

    fun frame(time: Double, width: Int, height: Int, config:DanmakuRenderConfig, measure: (DanmakuComment) -> DesktopDanmakuTextMetrics): List<PositionedDanmaku> {
        if (!time.isFinite() || time < 0 || width <= 0 || height <= 0 || config.lineHeightPx <= 0f) return emptyList()
        val geometry = Geometry(width, height, config)
        val previousGeometry=viewport
        if (!previousTime.isFinite() || time < previousTime || abs(time - previousTime) > 1.0 ||
            previousGeometry==null || !previousGeometry.onlyReservationChanged(geometry)) {
            active.clear()
            val longestDuration = maxOf(config.scrollDurationMs, config.pinnedDurationMs)/1000.0
            cursor = lowerBound((time - longestDuration).coerceAtLeast(0.0))
        } else if (previousGeometry!=geometry) {
            // The hot bar is a changing vertical budget, not a seek. Preserve each
            // admitted item's age/x/duration and the consumed document cursor.
            val shift=(config.topMarginPx-previousGeometry.config.topMarginPx).toDouble()
            for(index in active.indices) {
                val item=active[index]
                if(item.layer!=DANMAKU_LAYER_BOTTOM)
                    active[index]=item.copy(trackTop=item.trackTop+shift,baseline=item.baseline+shift)
            }
        }
        viewport=geometry
        if(config.lineCount<=0) {
            // Keep already admitted lifetimes (hidden) and consume arrivals while
            // there are no tracks. Restoring space must not replay dropped items.
            while(cursor<comments.size && comments[cursor].timeSeconds<=time)cursor++
            active.removeAll {it.end<=time}
            previousTime=time
            return emptyList()
        }
        val lineStep=(config.lineHeightPx+config.lineMarginPx).toDouble()
        // Exact original ByteDanceDanmakuEngine.updateConfig pinned-layer budget; no invented extra tracks.
        val pinnedLineCount=desktopOriginalDanmakuPinnedLineCount(config)
        while (cursor < comments.size && comments[cursor].timeSeconds <= time) {
            val comment = comments[cursor++]
            active.removeAll { it.end <= comment.timeSeconds }
            val layer=resolveDanmakuRenderLayerType(comment.mode,settings.staticDanmakuToScroll)
            val metrics=measure(comment)
            val textWidth=metrics.width.coerceAtLeast(1)
            val fixed=layer==DANMAKU_LAYER_TOP || layer==DANMAKU_LAYER_BOTTOM
            val duration=(if(fixed)config.pinnedDurationMs else config.scrollDurationMs)/1000.0
            val lineCount=if(fixed)pinnedLineCount else config.lineCount
            val track=(0 until lineCount).firstOrNull { candidate ->
                val trackTop=if(layer==DANMAKU_LAYER_BOTTOM)height-config.bottomMarginPx-config.lineHeightPx-candidate*lineStep
                    else config.topMarginPx+candidate*lineStep
                val occupants = active.filter { abs(it.trackTop-trackTop)<config.lineHeightPx }
                if (fixed) occupants.isEmpty() else occupants.all { previous ->
                    if (previous.layer==DANMAKU_LAYER_TOP || previous.layer==DANMAKU_LAYER_BOTTOM ||
                        (previous.layer==DANMAKU_LAYER_REVERSE) != (layer==DANMAKU_LAYER_REVERSE)) false else {
                        val age = comment.timeSeconds - previous.comment.timeSeconds
                        val oldSpeed = (width + previous.width) / previous.duration
                        val newSpeed = (width + textWidth) / duration
                        val gap = oldSpeed * age - previous.width
                        val catchUpDistance = (newSpeed - oldSpeed).coerceAtLeast(0.0) * (previous.duration - age)
                        gap >= desktopOriginalDanmakuItemMargin(config) + catchUpDistance
                    }
                }
            }
            if (track != null) {
                val top=if(layer==DANMAKU_LAYER_BOTTOM)height-config.bottomMarginPx-config.lineHeightPx-track*lineStep
                    else config.topMarginPx+track*lineStep
                active += Scheduled(comment,layer,track,top,top+metrics.ascent,textWidth,duration)
            }
        }
        active.removeAll { it.end <= time }
        previousTime = time
        return active.filter { scheduled ->
            val fixed=scheduled.layer==DANMAKU_LAYER_TOP || scheduled.layer==DANMAKU_LAYER_BOTTOM
            scheduled.track < if(fixed)pinnedLineCount else config.lineCount
        }.map { scheduled ->
            val progress = (time - scheduled.comment.timeSeconds) / scheduled.duration
            val x = when (scheduled.layer) {
                DANMAKU_LAYER_BOTTOM,DANMAKU_LAYER_TOP -> (width - scheduled.width) / 2.0
                DANMAKU_LAYER_REVERSE -> -scheduled.width + (width + scheduled.width) * progress
                else -> width - (width + scheduled.width) * progress
            }
            PositionedDanmaku(scheduled.comment, x, scheduled.baseline, scheduled.width)
        }
    }

    private fun lowerBound(time: Double): Int {
        var low = 0
        var high = comments.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (comments[middle].timeSeconds < time) low = middle + 1 else high = middle
        }
        return low
    }

    // Timing and item spacing are consumed from the sole original config/policies.
}
