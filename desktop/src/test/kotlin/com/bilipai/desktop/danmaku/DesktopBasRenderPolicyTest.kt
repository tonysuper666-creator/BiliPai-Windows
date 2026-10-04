package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.parser.bas.*
import kotlin.test.*

/** Pure memory/geometry admission, no Skia allocation, window, video or user profile. */
class DesktopBasRenderPolicyTest {
    private fun item(attributes:Map<String,BasValue>)=BasDanmaku(1,0,"fixture",
        BasProgram(listOf(BasElement("text",BasElementType.TEXT,attributes,4000)),emptyList(),4000,""))

    @Test fun actual5kFitsButOverflowAndExcessiveRasterAreRejected() {
        assertEquals(14_745_600L,DesktopBasRenderLimits.pixels(5120,2880))
        assertFailsWith<DesktopBasRenderRejected>{DesktopBasRenderLimits.pixels(Int.MAX_VALUE,Int.MAX_VALUE)}
        assertFailsWith<DesktopBasRenderRejected>{DesktopBasRenderLimits.pixels(8192,8192)}
    }
    @Test fun nonFiniteNumbersAndLongSvgAreRejectedBeforeFontOrPathCreation() {
        val budget=DesktopBasRenderBudget()
        assertFailsWith<DesktopBasRenderRejected>{budget.validateScene(item(mapOf("fontSize" to BasValue.Number(Double.POSITIVE_INFINITY))))}
        assertFailsWith<DesktopBasRenderRejected>{budget.validateSvg("M1e999 0 L0 0")}
        assertFailsWith<DesktopBasRenderRejected>{budget.validateSvg("M".repeat(DesktopBasRenderLimits.MAX_PATH_CHARS+1))}
        assertFailsWith<DesktopBasRenderRejected>{budget.validateSvg("Z".repeat(DesktopBasRenderLimits.MAX_PATH_VERBS+1))}
    }
    @Test fun layoutAndAggregateTextBudgetsApplyToAnimatedMeasuredState() {
        val budget=DesktopBasRenderBudget();val state=BasElementState(item(emptyMap()).program.elements.single())
        state.content="x".repeat(DesktopBasRenderLimits.MAX_TEXT_CHARS+1)
        assertFailsWith<DesktopBasRenderRejected>{budget.text(state,20f)}
        state.content="x";assertFailsWith<DesktopBasRenderRejected>{budget.text(state,513f)}
        state.width=1f;state.height=Float.NaN
        assertFailsWith<DesktopBasRenderRejected>{budget.measured(state)}
    }
    @Test fun perspectiveHorizonIsRejectedBeforeRasterConcat() {
        val budget=DesktopBasRenderBudget();val state=BasElementState(item(emptyMap()).program.elements.single())
        state.width=10f;state.height=10f
        val matrix=DesktopBasMatrix().apply {setValues(floatArrayOf(1f,0f,0f,0f,1f,0f,-1f,0f,5f))}
        assertFailsWith<DesktopBasRenderRejected>{budget.projection(state,matrix)}
    }
    @Test fun projectedAreaBudgetRejectsExcessiveWorkWithoutWindowSizedPerNodeImages() {
        val budget=DesktopBasRenderBudget();val state=BasElementState(item(emptyMap()).program.elements.single())
        state.width=8000f;state.height=16000f
        assertFailsWith<DesktopBasRenderRejected>{budget.projection(state,DesktopBasMatrix())}
    }
    @Test fun retainedSceneAdmissionAndReleaseAreBounded() {
        val budget=DesktopBasRenderBudget();val one=item(emptyMap())
        repeat(DesktopBasRenderLimits.MAX_RETAINED_ELEMENTS){budget.reserveScene(one)}
        assertFailsWith<DesktopBasRenderRejected>{budget.reserveScene(one)}
        budget.releaseScene(one);assertEquals(DesktopBasRenderLimits.MAX_RETAINED_ELEMENTS-1,budget.retainedElements)
    }
    @Test fun directProgramTextCostIncludesAnimatedStringsBeforeAnyFontAllocation() {
        val one=item(mapOf("content" to BasValue.Text("x".repeat(1000))))
        val value=BasValue.Array(listOf(BasValue.Text("y".repeat(2000)),BasValue.Text("linear")))
        val transition=BasTransition("text",mapOf("content" to value),0,1000,"linear",0,0)
        val program=one.program.copy(transitions=listOf(transition))
        val budget=DesktopBasRenderBudget();budget.validateScene(one.copy(program=program))
        assertEquals(3006L,budget.textCost(program))
    }
    @Test fun homographyInversePreservesPerspectiveAndRejectsSingularMap() {
        val matrix=DesktopBasMatrix().apply {setValues(floatArrayOf(1f,0.2f,10f,0.1f,1f,20f,0.001f,0.002f,1f))}
        val inverse=DesktopBasMatrix();assertTrue(matrix.invert(inverse))
        val point=floatArrayOf(40f,30f);matrix.mapPoints(point);inverse.mapPoints(point)
        assertEquals(40f,point[0],0.001f);assertEquals(30f,point[1],0.001f)
        assertFalse(DesktopBasMatrix().apply {setScale(0f,0f)}.invert(inverse))
    }
    @Test fun openGlTransformOrderAndSvgPostTranslationMatchAuthoredCoordinates() {
        val matrix=FloatArray(16);DesktopBasMatrix4.setIdentityM(matrix,0)
        DesktopBasMatrix4.translateM(matrix,0,10f,20f,0f);DesktopBasMatrix4.rotateM(matrix,0,90f,0f,0f,1f)
        DesktopBasMatrix4.translateM(matrix,0,2f,0f,0f)
        assertEquals(10f,matrix[12],0.001f);assertEquals(22f,matrix[13],0.001f)
        val svg=DesktopBasMatrix().apply {setScale(0.4f,0.4f);postTranslate(20f,10f)}
        val point=floatArrayOf(10f,20f);svg.mapPoints(point)
        assertEquals(24f,point[0],0.001f);assertEquals(18f,point[1],0.001f)
    }
}
