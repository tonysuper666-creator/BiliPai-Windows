package com.bilipai.desktop.danmaku

import androidx.compose.ui.graphics.Color
import com.android.purebilibili.core.plugin.DanmakuStyle
import com.android.purebilibili.danmaku.parser.bas.BasDanmaku
import com.android.purebilibili.danmaku.parser.bas.BasScriptParser
import java.util.concurrent.CancellationException
import kotlin.test.*

class DesktopBasPluginFilterTest {
    private fun script(source: String = "def text title { content = \"visible\" }") =
        BasDanmaku(77L, 2_000L, source, BasScriptParser.parse(source), userHash = "owner", weight = 1)

    private fun filter(
        items: List<BasDanmaku>,
        settings: DanmakuSettings = DanmakuSettings(),
        processor: DesktopBasPluginProcessor? = null,
        budget: DesktopBasDocumentBudget = DesktopBasDocumentBudget(),
        rejected: MutableList<Pair<Long, DesktopBasRejection>> = mutableListOf(),
    ) = filterDesktopBasDanmaku(items, settings, processor, budget) { id, reason -> rejected.add(id to reason) }

    @Test fun `empty and unrestricted no-plugin batches preserve original identities`() {
        val empty = mutableListOf<BasDanmaku>()
        assertSame(empty, filter(empty))
        val item = script()
        val original = listOf(item)
        assertSame(original, filter(original))
        assertSame(item, filter(original).single())
    }

    @Test fun `mode9 supplies complete long DSL and stable original metadata without ordinary mode clamp`() {
        val item = script("def text title { content = \"${"x".repeat(2_200)}\" }")
        var filters = 0
        var styles = 0
        val output = filter(listOf(item), processor = DesktopBasPluginProcessor({ input ->
            filters++
            assertEquals(item.source, input.content)
            assertEquals(9, input.type)
            assertEquals(item.id, input.id)
            assertEquals(item.startTimeMs, input.timeMs)
            assertEquals(item.userHash, input.userId)
            input.copy(id = 999, type = 1, userId = "other", timeMs = 3_500)
        }, { input ->
            styles++
            assertEquals(999L, input.id)
            DanmakuStyle(scale = 5f)
        })).single()
        assertEquals(1, filters)
        assertEquals(1, styles)
        assertEquals(item.id, output.id)
        assertEquals(item.userHash, output.userHash)
        assertEquals(item.weight, output.weight)
        assertEquals(item.source, output.source)
        assertSame(item.program, output.program)
        assertEquals(3_500L, output.startTimeMs)
        assertEquals(5f, output.fontScale)
    }

    @Test fun `unchanged plugin payload retains item and compiled program identity`() {
        val item = script()
        val output = filter(listOf(item), processor = DesktopBasPluginProcessor({ it }, { null })).single()
        assertSame(item, output)
        assertSame(item.program, output.program)
    }

    @Test fun `edited DSL is parsed before style and later animated text is filtered`() {
        val item = script()
        val changed = "def text title { content = \"initial\" } set title {} 1s then set title { content = \"blocked later\" } 2s"
        val order = mutableListOf<String>()
        val processor = DesktopBasPluginProcessor({ order.add("filter"); it.copy(content = changed) }, {
            order.add("style")
            assertEquals(changed, it.content)
            null
        })
        val visible = filter(listOf(item), processor = processor).single()
        assertEquals(listOf("filter", "style"), order)
        assertNotSame(item.program, visible.program)
        assertTrue(visible.content.contains("blocked later"))
        assertTrue(filter(listOf(item), DanmakuSettings(blockedKeywords = listOf("blocked")), processor).isEmpty())
        assertEquals("visible", item.content)
    }

    @Test fun `DROP does not invoke style or consume output budget`() {
        val item = script()
        var styles = 0
        val budget = DesktopBasDocumentBudget()
        assertTrue(filter(listOf(item), processor = DesktopBasPluginProcessor({ null }, { styles++; null }), budget = budget).isEmpty())
        assertEquals(0, styles)
        assertTrue(budget.reserve(DesktopBasParseBudget.Estimate(DesktopBasDocumentBudget.MAX_SOURCE_CHARS, 0)))
    }

    @Test fun `malformed edited DSL is reported and never passed to style`() {
        val rejected = mutableListOf<Pair<Long, DesktopBasRejection>>()
        var styles = 0
        assertTrue(filter(listOf(script()), processor = DesktopBasPluginProcessor({
            it.copy(content = "def text title { content = }")
        }, { styles++; null }), rejected = rejected).isEmpty())
        assertEquals(0, styles)
        assertEquals(listOf(77L to DesktopBasRejection.INVALID_SCRIPT), rejected)
    }

    @Test fun `oversized edited DSL is rejected intact before parsing and style`() {
        val rejected = mutableListOf<Pair<Long, DesktopBasRejection>>()
        var styles = 0
        val changed = "def text title { content = \"${"x".repeat(16_384)}\" }"
        assertTrue(filter(listOf(script()), processor = DesktopBasPluginProcessor({
            it.copy(content = changed)
        }, { styles++; null }), rejected = rejected).isEmpty())
        assertEquals(0, styles)
        assertEquals(listOf(77L to DesktopBasRejection.SOURCE_PREFLIGHT), rejected)
    }

    @Test fun `one shared budget spans calls and rejects changed DSL before style`() {
        val item = script()
        val budget = DesktopBasDocumentBudget()
        assertTrue(budget.reserve(DesktopBasParseBudget.Estimate(DesktopBasDocumentBudget.MAX_SOURCE_CHARS - item.source.length, 0)))
        assertSame(item, filter(listOf(item), budget = budget).single())
        var styles = 0
        val rejected = mutableListOf<Pair<Long, DesktopBasRejection>>()
        assertTrue(filter(listOf(item), processor = DesktopBasPluginProcessor({
            it.copy(content = "def text other { content = \"changed\" }")
        }, { styles++; null }), budget = budget, rejected = rejected).isEmpty())
        assertEquals(0, styles)
        assertEquals(listOf(item.id to DesktopBasRejection.DOCUMENT_BUDGET), rejected)
    }

    @Test fun `aggregate output element budget is shared across every item`() {
        val item = script()
        val expanded = item.copy(program = item.program.copy(elements = List(256) { item.program.elements.single() }))
        val items = List(17) { expanded.copy(id = it.toLong()) }
        val rejected = mutableListOf<Pair<Long, DesktopBasRejection>>()
        val output = filter(items, rejected = rejected)
        assertEquals(16, output.size)
        assertEquals(listOf(16L to DesktopBasRejection.DOCUMENT_BUDGET), rejected)
        assertNotSame(items, output)
    }

    @Test fun `disabled special and global switches avoid plugin side effects`() {
        var calls = 0
        val processor = DesktopBasPluginProcessor({ calls++; it }, { calls++; null })
        assertTrue(filter(listOf(script()), DanmakuSettings(allowSpecial = false), processor).isEmpty())
        assertTrue(filter(listOf(script()), DanmakuSettings(enabled = false), processor).isEmpty())
        assertEquals(0, calls)
        assertEquals(1, filter(listOf(script()), DanmakuSettings(allowScroll = false, allowTop = false, allowBottom = false)).size)
    }

    @Test fun `refiltering original input applies new settings without retaining prior plugin output`() {
        val item = script()
        val original = listOf(item)
        val observed = mutableListOf<String>()
        val processor = DesktopBasPluginProcessor({ observed.add(it.content); it.copy(content = "def text title { content = \"translated\" }") }, { null })
        assertEquals("translated", filter(original, processor = processor).single().content)
        assertTrue(filter(original, DanmakuSettings(blockedKeywords = listOf("translated")), processor).isEmpty())
        assertEquals(listOf(item.source, item.source), observed)
        assertSame(item, original.single())
        assertEquals("visible", item.content)
    }

    @Test fun `original self weight exception does not bypass user or displayed text rules`() {
        val item = script().copy(isSelf = true)
        assertEquals(1, filter(listOf(item), DanmakuSettings(weightFilterLevel = 5)).size)
        assertTrue(filter(listOf(item), DanmakuSettings(weightFilterLevel = 5, blockedRules = listOf("uid:owner"))).isEmpty())
        assertTrue(filter(listOf(item), DanmakuSettings(blockedRules = listOf("regex:vis.*"))).isEmpty())
        val named = script("def text blocked { content = \"clean\" }")
        assertEquals(1, filter(listOf(named), DanmakuSettings(blockedKeywords = listOf("blocked"))).size)
    }

    @Test fun `plugin color and style override are consumed by original color policy`() {
        val item = script()
        val processor = DesktopBasPluginProcessor({ it.copy(color = 0xff0000) }, { null })
        assertTrue(filter(listOf(item), DanmakuSettings(allowColorful = false), processor).isEmpty())
        val whiteOverride = DesktopBasPluginProcessor({ it.copy(color = 0xff0000) }, { DanmakuStyle(textColor = Color.White) })
        val output = filter(listOf(item), DanmakuSettings(allowColorful = false), whiteOverride).single()
        assertEquals(0xffffff, output.colorOverride)
        assertEquals(0xff0000, output.color)
    }

    @Test fun `invalid compiled programs and nonfinite styles have observable safe rejection`() {
        val item = script()
        val rejected = mutableListOf<Pair<Long, DesktopBasRejection>>()
        assertTrue(filter(listOf(item.copy(program = item.program.copy(elements = emptyList()))), rejected = rejected).isEmpty())
        assertEquals(listOf(item.id to DesktopBasRejection.PROGRAM_LIMIT), rejected)
        for (scale in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            rejected.clear()
            assertTrue(filter(listOf(item), processor = DesktopBasPluginProcessor({ it }, { DanmakuStyle(scale = scale) }), rejected = rejected).isEmpty())
            assertEquals(listOf(item.id to DesktopBasRejection.INVALID_STYLE), rejected)
        }
    }

    @Test fun `filter and style cancellation propagate without fallback or error substitution`() {
        val cancelled = CancellationException("retired original source")
        assertSame(cancelled, assertFailsWith<CancellationException> {
            filter(listOf(script()), processor = DesktopBasPluginProcessor({ throw cancelled }, { null }))
        })
        assertSame(cancelled, assertFailsWith<CancellationException> {
            filter(listOf(script()), processor = DesktopBasPluginProcessor({ it }, { throw cancelled }))
        })
    }
}
