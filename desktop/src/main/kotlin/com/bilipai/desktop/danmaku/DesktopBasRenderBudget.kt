package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.parser.bas.*
import kotlin.math.abs

/** Separate from parse admission: no raster, path or font is allocated before these checks. */
internal class DesktopBasRenderRejected(val reason:String):Exception(reason)

internal object DesktopBasRenderLimits {
    const val MAX_PIXELS=24_000_000L // 5K allowed; two retained raster buffers <=192MB; a requested copy adds <=96MB.
    const val MAX_DIMENSION=8192
    const val MAX_COORDINATE=1_000_000f
    const val MAX_FONT_SIZE=512f
    const val MAX_LAYOUT_WIDTH=65_536
    const val MAX_LAYOUT_HEIGHT=16_384f
    const val MAX_TEXT_CHARS=32_768
    const val MAX_PATH_CHARS=65_536
    const val MAX_PATH_VERBS=8192
    const val MAX_RETAINED_ELEMENTS=1024
    const val MAX_ACTIVE_ELEMENTS=512
    const val MAX_DRAW_AREA=96_000_000.0
    fun pixels(width:Int,height:Int):Long {
        if(width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION || width.toLong()*height>MAX_PIXELS)
            throw DesktopBasRenderRejected("BAS raster exceeds the physical-pixel budget")
        return width.toLong()*height
    }
    fun finite(value:Float,bound:Float=MAX_COORDINATE) {
        if(!value.isFinite() || abs(value)>bound)throw DesktopBasRenderRejected("BAS non-finite or excessive geometry")
    }
}

internal class DesktopBasRenderBudget {
    private var measuredCharacters=0
    private var layoutArea=0.0
    var retainedElements:Int=0
        private set
    fun beginFrame(){measuredCharacters=0;layoutArea=0.0}
    fun reserveScene(item:BasDanmaku):BasProgram {
        val program=validateScene(item)
        if(retainedElements+program.elements.size>DesktopBasRenderLimits.MAX_RETAINED_ELEMENTS)
            reject("BAS retained drawing objects exceed the budget")
        retainedElements+=program.elements.size;return program
    }
    fun releaseScene(item:BasDanmaku){retainedElements-=item.program.elements.size;check(retainedElements>=0)}
    /** Called after validateScene, hence recursion/work is already bounded. Includes animated values,
     * even for a directly constructed program whose display-text projection is incomplete. */
    fun textCost(program:BasProgram):Long {
        fun count(value:BasValue):Long=when(value) {
            is BasValue.Text -> value.value.length.toLong()
            is BasValue.Reference -> value.name.length.toLong()
            is BasValue.Array -> value.values.sumOf(::count)
            is BasValue.Object -> value.attributes.values.sumOf(::count)
            is BasValue.Number -> 0L
        }
        return program.elements.sumOf {it.attributes.values.sumOf(::count)} +
            program.transitions.sumOf {it.properties.values.sumOf(::count)}
    }
    fun validateScene(item:BasDanmaku):BasProgram {
        val program=item.program
        if(item.startTimeMs<0L || program.durationMs<0L || program.elements.size>256 || program.transitions.size>2048)
            reject("BAS scene exceeds the retained-scene budget")
        DesktopBasRenderLimits.finite(item.fontScale,8f)
        if(item.fontScale<=0f)reject("BAS font scale must be positive")
        var chars=0L;var paths=0L;var work=0L
        fun value(v:BasValue,depth:Int=0) {
            if(depth>32 || ++work>32768)reject("BAS drawing attributes exceed the budget")
            when(v) {
                is BasValue.Number -> if(!v.value.isFinite() || abs(v.value)>1_000_000_000.0)reject("BAS invalid drawing number")
                is BasValue.Text -> {chars+=v.value.length;if(chars>131072)reject("BAS retained text exceeds the budget")}
                is BasValue.Array -> v.values.forEach {value(it,depth+1)}
                is BasValue.Object -> v.attributes.values.forEach {value(it,depth+1)}
                is BasValue.Reference -> if(v.name.length>128)reject("BAS invalid reference")
            }
        }
        for(element in program.elements) {
            element.attributes.values.forEach {value(it)}
            if(element.attributes.text("fontFamily").length>128)reject("BAS font family exceeds the budget")
            if(element.durationMs<0L)reject("BAS invalid scene duration")
            val path=element.attributes.text("d");paths+=path.length
            if(path.length>DesktopBasRenderLimits.MAX_PATH_CHARS || paths>131072)reject("BAS SVG input exceeds the budget")
            if(path.isNotEmpty())validateSvg(path)
        }
        val names=program.elements.associateBy {it.name}
        for(element in program.elements) {
            val seen=hashSetOf<String>();var parent=element.parentName
            while(parent!=null){if(!seen.add(parent)||seen.size>32)reject("BAS parent depth exceeds the budget");parent=names[parent]?.parentName}
        }
        var compileWork=0L
        for(group in program.transitions.groupBy {it.elementName}.values) {
            val events=group.sumOf {it.properties.size.toLong()};compileWork+=events*events
            if(compileWork>1_000_000L)reject("BAS timeline compile work exceeds the drawing budget")
        }
        for(transition in program.transitions) {
            if(transition.startTimeMs<0L || transition.durationMs<0L || transition.startTimeMs>Long.MAX_VALUE-transition.durationMs)
                reject("BAS invalid transition duration")
            transition.properties.values.forEach {value(it)}
        }
        return program
    }
    fun validateSvg(path:String) {
        if(path.length>DesktopBasRenderLimits.MAX_PATH_CHARS)reject("BAS SVG input exceeds the budget")
        if(path.count {it in "MmZzLlHhVvCcSsQqTtAa"}>DesktopBasRenderLimits.MAX_PATH_VERBS)
            reject("BAS SVG commands exceed the budget")
        val numbers=Regex("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")
        var count=0
        for(match in numbers.findAll(path)) {
            if(++count>DesktopBasRenderLimits.MAX_PATH_VERBS*6)reject("BAS SVG coordinates exceed the budget")
            val number=match.value.toDoubleOrNull()
            if(number==null || !number.isFinite() || abs(number)>DesktopBasRenderLimits.MAX_COORDINATE)
                reject("BAS invalid SVG coordinate")
        }
    }
    fun text(state:BasElementState,size:Float) {
        DesktopBasRenderLimits.finite(size,DesktopBasRenderLimits.MAX_FONT_SIZE)
        if(size<=0f || state.content.length>DesktopBasRenderLimits.MAX_TEXT_CHARS || state.content.count {it=='\n'}>128)
            reject("BAS text layout exceeds the budget")
        measuredCharacters+=state.content.length
        if(measuredCharacters>131072)reject("BAS frame text exceeds the budget")
    }
    fun measured(state:BasElementState) {
        DesktopBasRenderLimits.finite(state.width,DesktopBasRenderLimits.MAX_LAYOUT_WIDTH.toFloat())
        DesktopBasRenderLimits.finite(state.height,DesktopBasRenderLimits.MAX_LAYOUT_HEIGHT)
        if(state.width<0f || state.height<0f)reject("BAS invalid layout bounds")
    }
    fun projection(state:BasElementState,matrix:DesktopBasMatrix) {
        measured(state)
        val v=matrix.values;v.forEach {DesktopBasRenderLimits.finite(it)}
        val points=floatArrayOf(0f,0f,state.width,0f,state.width,state.height,0f,state.height)
        var sign=0f
        for(i in points.indices step 2) {
            val w=v[6]*points[i]+v[7]*points[i+1]+v[8]
            if(!w.isFinite() || abs(w)<0.0001f || (sign!=0f && sign*w<=0f))reject("BAS projection crosses its perspective horizon")
            sign=w
        }
        matrix.mapPoints(points);points.forEach {DesktopBasRenderLimits.finite(it)}
        val xs=listOf(points[0],points[2],points[4],points[6]);val ys=listOf(points[1],points[3],points[5],points[7])
        layoutArea+=(xs.max()-xs.min()).toDouble()*(ys.max()-ys.min())
        if(layoutArea>DesktopBasRenderLimits.MAX_DRAW_AREA)reject("BAS projected drawing area exceeds the budget")
    }
    private fun reject(reason:String):Nothing=throw DesktopBasRenderRejected(reason)
}
