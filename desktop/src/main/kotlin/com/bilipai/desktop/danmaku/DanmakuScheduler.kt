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
    private var originalComments = comments.sortedBy {it.timeSeconds}
    private var settings = settings.normalized()
    private var initialLocalPhase=immediateLocalPhase
    private var comments = prepareComments()
    internal val currentSettings:DanmakuSettings get()=settings
    private data class Scheduled(val comment: DanmakuComment, val layer: Int, val track: Int, val trackTop:Double, val baseline:Double, val width: Int, val duration: Double) {
        val end get() = comment.timeSeconds + duration
    }
    private val active = mutableListOf<Scheduled>()
    // A late local insertion can precede the already consumed cursor. Admit that
    // insertion once without winding back through consumed/collision-dropped items.
    private val pendingLocalComments = mutableListOf<DanmakuComment>()
    private var cursor = 0
    private var previousTime = Double.NaN
    private data class Geometry(val width:Int,val height:Int,val config:DanmakuRenderConfig) {
        fun onlyReservationChanged(next:Geometry):Boolean =
            width==next.width && height==next.height &&
                config.copy(topMarginPx=next.config.topMarginPx,lineCount=next.config.lineCount)==next.config
    }
    private var viewport:Geometry?=null

    fun resetTimeline() { active.clear(); pendingLocalComments.clear(); previousTime=Double.NaN }

    /** Append downstream of ordinary filtering/merging, as original controller.append.
     * All access belongs to the existing renderer EDT. A conflicting identity or
     * a retired preprocessing phase requires normal filtered-timeline preparation. */
    internal fun appendOriginalLocalComments(next:List<DanmakuComment>,phase:Any,
        currentSettings:DanmakuSettings=this.settings,allowPhaseRetirement:Boolean=false):List<DanmakuComment>? {
        if(!allowPhaseRetirement && initialLocalPhase!=null && initialLocalPhase!==phase)return null
        if(next.any {it.originalLocalItem==null || it.originalLocalInjectionPhase==null ||
                (!allowPhaseRetirement && it.originalLocalInjectionPhase!==phase)})return null
        val known=originalComments.associateBy {it.id}
        val unique=next.distinctBy {it.id}
        if(unique.size!=next.size || next.any {known[it.id]?.let {old->old!=it}==true})return null
        val added=unique.filter {it.id !in known}.sortedBy {it.timeSeconds}
        val normalized=currentSettings.normalized()
        val rebuild=normalized!=settings || (initialLocalPhase!=null && initialLocalPhase!==phase) ||
            added.any {it.originalLocalInjectionPhase!==phase}
        if(added.isEmpty() && !rebuild)return emptyList()
        originalComments=(originalComments+added).sortedBy {it.timeSeconds}
        if(rebuild) {
            // Only a real settings/phase retirement takes the normal rebuild path.
            // Old local items now pass filters; new-phase items stay downstream.
            settings=normalized;initialLocalPhase=phase;pendingLocalComments.clear()
            comments=prepareComments();previousTime=Double.NaN
            return added
        }
        initialLocalPhase=phase
        val timeline=comments.toMutableList()
        added.forEach {comment->
            // Stable insertion after equal timestamps retains the ordinary order.
            var low=0;var high=timeline.size
            while(low<high) {
                val middle=(low+high) ushr 1
                if(timeline[middle].timeSeconds<=comment.timeSeconds)low=middle+1 else high=middle
            }
            timeline.add(low,comment)
            if(low<cursor) {cursor++;pendingLocalComments+=comment}
        }
        pendingLocalComments.sortBy {it.timeSeconds}
        comments=timeline
        return added
    }

    fun applySettings(settings: DanmakuSettings) {
        val normalized = settings.normalized()
        if (normalized == this.settings) return
        this.settings = normalized
        initialLocalPhase=null
        pendingLocalComments.clear()
        comments = prepareComments()
        previousTime = Double.NaN
    }

    internal fun observeOriginalLocalInjectionPhase(current:Any) {
        if(initialLocalPhase!=null && initialLocalPhase!==current) {
            initialLocalPhase=null;pendingLocalComments.clear();comments=prepareComments();previousTime=Double.NaN
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
            pendingLocalComments.clear()
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
            pendingLocalComments.removeAll {it.timeSeconds<=time}
            active.removeAll {it.end<=time}
            previousTime=time
            return emptyList()
        }
        val lineStep=(config.lineHeightPx+config.lineMarginPx).toDouble()
        // Exact original ByteDanceDanmakuEngine.updateConfig pinned-layer budget; no invented extra tracks.
        val pinnedLineCount=desktopOriginalDanmakuPinnedLineCount(config)
        fun admit(comment:DanmakuComment) {
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
        while(pendingLocalComments.isNotEmpty() && pendingLocalComments.first().timeSeconds<=time)
            admit(pendingLocalComments.removeAt(0))
        while (cursor < comments.size && comments[cursor].timeSeconds <= time)
            admit(comments[cursor++])
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
