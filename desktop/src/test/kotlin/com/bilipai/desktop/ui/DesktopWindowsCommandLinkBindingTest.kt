package com.bilipai.desktop.ui

import com.android.purebilibili.danmaku.parser.DanmakuProto
import com.android.purebilibili.feature.video.danmaku.*
import com.android.purebilibili.feature.video.ui.overlay.CommandDanmakuOverlayState
import com.bilipai.desktop.data.BiliApiException
import kotlin.test.*

/** Metadata -> the real generated original LINK callbacks -> the mounted binding -> original state.
 * No network, account/profile, native player or window is used. */
class DesktopWindowsCommandLinkBindingTest {
    private fun link(id: Long = 1, extra: String = """{"aid":170001,"bvid":"BVexplicit","title":"原关联视频"}""") =
        assertNotNull(buildCommandDanmakuItem(DanmakuProto.CommandDm(id = id, command = "#LINK#",
            content = "原标题后备", progress = 1000, extra = extra)))

    private class Harness {
        var source: Any = Any()
        var epoch = 4L
        var entryCurrent = true
        var beforeFinal: () -> Unit = {}
        var permitFailure: Throwable? = null
        val routes = mutableListOf<String>()
        val feedback = mutableListOf<String>()
        val state = CommandDanmakuOverlayState()
        fun bind(captured: Any = source, capturedEpoch: Long = epoch,
                 navigate: (String) -> Unit = { routes += it }): DesktopWindowsCommandLinkBinding {
            val current = { entryCurrent && source === captured && epoch == capturedEpoch }
            return DesktopWindowsCommandLinkBinding(captured, state, current, { action ->
                beforeFinal()
                permitFailure?.let { throw it }
                if (current()) { action(); true } else false
            }, navigate, { feedback += it })
        }
    }

    @Test fun metadataUsesBvidFirstAndConfirmationConsumesOriginalDismissBeforeTypedRoute() {
        val h = Harness()
        val binding = h.bind(navigate = { bvid -> assertTrue(h.state.isDismissed("cmd_1")); h.routes += bvid })
        val item = link()
        assertSame(item, desktopWindowsVisibleCommandCards(listOf(item), false, false).single())
        assertTrue(binding.open(item))
        val request = assertNotNull(binding.pending.value)
        val mount = assertNotNull(binding.mount(request) { true })
        assertSame(item, request.item)
        assertEquals("BVexplicit", request.targetBvid)
        assertFalse(h.state.isDismissed(item.id))
        assertTrue(binding.confirm(request, mount, request.targetBvid))
        assertEquals(listOf("BVexplicit"), h.routes)
        assertNull(binding.pending.value)
        assertFalse(binding.confirm(request, mount, request.targetBvid))
    }

    @Test fun avOnlyMetadataUsesTheOriginalAvToBvAndContentFallback() {
        val h = Harness(); val binding = h.bind()
        val item = link(extra = """{"aid":170001}""")
        assertTrue(binding.open(item))
        val request = assertNotNull(binding.pending.value)
        val mount = assertNotNull(binding.mount(request) { true })
        assertEquals("BV17x411w7KC", request.targetBvid)
        assertEquals("原标题后备", request.item.content)
        assertTrue(binding.confirm(request, mount, request.targetBvid))
        assertEquals(listOf("BV17x411w7KC"), h.routes)
    }

    @Test fun originalMissingTargetFeedbackAndNonLinkCardsNeverNavigate() {
        val h = Harness(); val binding = h.bind()
        assertTrue(binding.open(link(extra = "{}")))
        assertNull(binding.pending.value)
        assertEquals(listOf("关联视频信息缺失，无法跳转"), h.feedback)
        val up = assertNotNull(buildCommandDanmakuItem(DanmakuProto.CommandDm(id = 8, command = "#UP#", content = "提示")))
        assertFalse(binding.open(up))
        assertTrue(h.routes.isEmpty())
    }

    @Test fun cancelKeepsOriginalCardAndOldDialogCannotDismissOrConfirmItsSuccessor() {
        val h = Harness(); val binding = h.bind(); val item = link()
        binding.open(item); val old = assertNotNull(binding.pending.value)
        val oldMount = assertNotNull(binding.mount(old) { true })
        binding.dismiss(old)
        assertFalse(h.state.isDismissed(item.id))
        binding.open(item); val next = assertNotNull(binding.pending.value)
        val nextMount = assertNotNull(binding.mount(next) { true })
        assertNotSame(old, next)
        binding.dismiss(old)
        assertSame(next, binding.pending.value)
        assertFalse(binding.confirm(old, oldMount, old.targetBvid))
        assertFalse(binding.confirm(next, nextMount, "BVforged"))
        assertTrue(h.routes.isEmpty())
        assertTrue(binding.confirm(next, nextMount, next.targetBvid))
    }

    @Test fun sourceAccountAndEntryRetireAtFinalAdmissionWithoutDismissingCard() {
        for (retire in listOf<(Harness) -> Unit>({ it.source = Any() }, { it.epoch++ }, { it.entryCurrent = false })) {
            val h = Harness(); val binding = h.bind(); val item = link()
            binding.open(item); val request = assertNotNull(binding.pending.value)
            val mount = assertNotNull(binding.mount(request) { true })
            h.beforeFinal = { retire(h) }
            assertFalse(binding.confirm(request, mount, request.targetBvid))
            assertTrue(h.routes.isEmpty())
            assertFalse(h.state.isDismissed(item.id))
            binding.retireIfUnowned()
            assertNull(binding.pending.value)
        }
    }

    @Test fun sameValueNewLeaseDoesNotReauthorizeOldCallbackAndOldCloseCannotClearNewBinding() {
        data class Stamp(val bv: String, val cid: Long, val numericVersion: Long)
        val h = Harness(); h.source = Stamp("BVsame", 7, 1)
        val old = h.bind(); old.open(link()); val request = assertNotNull(old.pending.value)
        val oldMount = assertNotNull(old.mount(request) { true })
        h.source = Stamp("BVsame", 7, 1)
        val next = h.bind(); next.open(link(2)); val newRequest = assertNotNull(next.pending.value)
        val nextMount = assertNotNull(next.mount(newRequest) { true })
        assertFalse(old.confirm(request, oldMount, request.targetBvid))
        old.close()
        assertSame(newRequest, next.pending.value)
        assertTrue(next.confirm(newRequest, nextMount, newRequest.targetBvid))
        assertEquals(1, h.routes.size)
    }

    @Test fun retiredPrimaryAccountPermitContractIsNoOpButActiveApiErrorIsPreserved() {
        val h = Harness(); val binding = h.bind()
        binding.open(link()); val request = assertNotNull(binding.pending.value)
        val mount = assertNotNull(binding.mount(request) { true })
        h.beforeFinal = { h.epoch++; h.permitFailure = BiliApiException(-101, "账号已切换") }
        assertFalse(binding.confirm(request, mount, request.targetBvid))
        assertTrue(h.routes.isEmpty())
        val active = Harness(); val liveBinding = active.bind()
        active.permitFailure = BiliApiException(-403, "原错误")
        assertFailsWith<BiliApiException> { liveBinding.open(link()) }
    }

    @Test fun navigationFailureIsNeverMistakenForRetiredPermit() {
        val h = Harness(); val binding = h.bind(navigate = { h.entryCurrent = false; throw BiliApiException(-101, "动作错误") })
        binding.open(link()); val request = assertNotNull(binding.pending.value)
        val mount = assertNotNull(binding.mount(request) { true })
        assertFailsWith<BiliApiException> { binding.confirm(request, mount, request.targetBvid) }
        assertTrue(h.state.isDismissed("cmd_1"))
        assertNull(binding.pending.value)
    }

    @Test fun actualContentUnmountHiddenPeerAndOldMountCannotAuthorizeConfirmationOrClearSuccessor() {
        val h = Harness(); val binding = h.bind(); val item = link()
        binding.open(item); val request = assertNotNull(binding.pending.value)
        var peerShowing = true
        val oldMount = assertNotNull(binding.mount(request) { peerShowing })
        peerShowing = false
        assertFalse(binding.confirm(request, oldMount, request.targetBvid))
        peerShowing = true
        val successorMount = assertNotNull(binding.mount(request) { peerShowing })
        binding.unmount(oldMount)
        assertSame(request, binding.pending.value)
        assertFalse(binding.confirm(request, oldMount, request.targetBvid))
        binding.unmount(successorMount)
        assertNull(binding.pending.value)
        assertFalse(binding.confirm(request, successorMount, request.targetBvid))
        assertFalse(h.state.isDismissed(item.id))
        assertTrue(h.routes.isEmpty())
        // Disposal during the final permit must also reject an already captured click.
        binding.open(item); val next = assertNotNull(binding.pending.value)
        val mounted = assertNotNull(binding.mount(next) { peerShowing })
        h.beforeFinal = { peerShowing = false }
        assertFalse(binding.confirm(next, mounted, next.targetBvid))
        assertFalse(h.state.isDismissed(item.id))
    }
}
