package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.parser.bas.BasScriptParser
import com.android.purebilibili.danmaku.parser.bas.BasTarget
import com.android.purebilibili.danmaku.parser.bas.BasDanmaku
import com.android.purebilibili.danmaku.parser.bas.text
import kotlin.test.*

class DesktopBasParseBudgetTest {
    @Test fun `common template defaults named positional arguments and aggregate properties remain original`() {
        val source="""
            def button T(label="default" destination=1m position=10% curve="ease-in") {
                text=label x=position target=seek {time=destination}
                metadata=custom {label=label values=[1,curve]}
            }
            let a=T("hello",position=50%,destination=20.5s500ms,)
            let b=(a {text="copied" x=25%})
        """.trimIndent()
        assertNotNull(DesktopBasParseBudget.estimate(source))
        val program=BasScriptParser.parse(source)
        assertEquals(listOf("hello","copied"),program.elements.map {it.attributes.text("text")})
        assertEquals(BasTarget.Seek(21_000),program.elements[0].target)
    }

    @Test fun `scalar alias defaults and explicit argument override stay bounded`() {
        val source="def text T(a=b;b=\"safe\"){content=a} let x=T() let y=T(b=\"override\")"
        assertNotNull(DesktopBasParseBudget.estimate(source))
        assertEquals(listOf("safe","override"),BasScriptParser.parse(source).elements.map {it.attributes.text("content")})
    }

    @Test fun `small aggregate alias example is estimated without expanding its arrays`() {
        val source="def text T(a=[\"\",b,b];b=[\"\",c,c];c=\"x\"){content=a} let x=T()"
        assertNotNull(DesktopBasParseBudget.estimate(source))
        // This is intentionally not sent to the original parser: its content type
        // is invalid, but its pre-typing expansion demonstrates the resource path.
    }

    @Test fun `exponential defaults and caller overrides are rejected before original parsing`() {
        val chain=(0 until 25).joinToString(";") {"p$it=[\"\",p${it+1},p${it+1}]"}+";p25=\"x\""
        val explosive="def text T($chain){content=p0} let x=T()"
        assertTrue(explosive.length<16_384)
        assertNull(DesktopBasParseBudget.estimate(explosive))
        val safe=(0..25).joinToString(" ") {"p$it=\"x\""}
        val overrides=(0 until 25).joinToString(",") {"p$it=[\"\",p${it+1},p${it+1}]"}
        assertNull(DesktopBasParseBudget.estimate("def text T($safe){content=p0} let x=T($overrides)"))
        // Never construct the rejected expansions, even in a regression test.
    }

    @Test fun `parameter cycles and excessive dependency depth are rejected independently of bracket depth`() {
        assertNull(DesktopBasParseBudget.estimate("def text T(a=b;b=a){content=a} let x=T()"))
        val chain=(0 until 70).joinToString(";") {"p$it=p${it+1}"}+";p70=\"x\""
        assertNull(DesktopBasParseBudget.estimate("def text T($chain){content=p0} let x=T()"))
        // Traversing the short tail first must not let memoization conceal the
        // later full reference chain from the original recursive binder.
        assertNull(DesktopBasParseBudget.estimate("def text T($chain){metadata=p35 content=p0} let x=T()"))
    }

    @Test fun `literal delimiter text is not syntax and cloned metadata maps are charged`() {
        val delimiters="def text T(c=\",\" d=\";\"){content=c metadata=d} let x=T(\";\",d=\",\")"
        assertNotNull(DesktopBasParseBudget.estimate(delimiters))
        assertEquals(";",BasScriptParser.parse(delimiters).elements.single().attributes.text("content"))
        val small="def text base {"+(0 until 8).joinToString(" ") {"k$it=$it"}+"} "+
            (0 until 4).joinToString(" ") {"let c$it=(base {})"}
        assertNotNull(DesktopBasParseBudget.estimate(small))
        assertEquals(5,BasScriptParser.parse(small).elements.size)
        val boundedInput="def text base {"+(0 until 96).joinToString(" ") {"k$it=$it"}+"} "+
            (0 until 80).joinToString(" ") {"let c$it=(base {})"}
        assertTrue(boundedInput.length<16_384)
        assertNull(DesktopBasParseBudget.estimate(boundedInput))
        // The rejected large-map clone workload never enters original parsing.
    }

    @Test fun `admitted malformed work still consumes the document attempt budget`() {
        val estimate=assertNotNull(DesktopBasParseBudget.estimate("def text T(a=[\"\",b,b];b=\"x\"){content=a} let x=T()"))
        val budget=DesktopBasDocumentBudget()
        val allowed=DesktopBasDocumentBudget.MAX_PARSE_WORK/estimate.workUnits
        repeat(allowed) {assertTrue(budget.reserve(estimate))}
        assertFalse(budget.reserve(estimate))
    }

    @Test fun `joined clone text has a separate document retention budget`() {
        val source="def text base {content=\""+"x".repeat(8_192)+"\"} "+
            (0 until 63).joinToString(" ") {"let c$it=(base {})"}
        assertNotNull(DesktopBasParseBudget.estimate(source))
        // Only a small, bounded original parse; its shared literal is repeated
        // in the original joined text, independently of map/element counts.
        val program=BasScriptParser.parse(source)
        assertEquals(64,program.elements.size)
        val item=BasDanmaku(1,0,source,program)
        val budget=DesktopBasDocumentBudget()
        repeat(3) {assertTrue(budget.retain(item))}
        assertFalse(budget.retain(item))
    }
}
