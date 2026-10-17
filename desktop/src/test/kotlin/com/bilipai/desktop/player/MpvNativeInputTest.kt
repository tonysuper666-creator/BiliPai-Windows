package com.bilipai.desktop.player

import com.sun.jna.Memory
import java.awt.EventQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** Actual borrowed client-message ABI and the production EDT delivery owner.
 * These tests create no Window, native runtime, decoder or audio device.
 */
class MpvNativeInputTest {
    @Test fun copiesTheNativeChildClickBeforeItsBorrowedBuffersAreReleased() = source { snapshot ->
        val lease=MpvNativeWindowInputLease(snapshot,7)
        val event=Message(lease.token,"reveal").use { assertNotNull(lease.decode(it.data)) }
        assertSame(lease,event.lease);assertEquals(MpvNativeWindowInputKind.REVEAL,event.kind)
        assertSame(snapshot,event.lease.source);assertEquals(7L,event.lease.playbackRevision)
    }
    @Test fun decodesOnlyTheThreeNativeActions() = source { snapshot ->
        val lease=MpvNativeWindowInputLease(snapshot,1)
        for((word,kind) in listOf("motion" to MpvNativeWindowInputKind.MOTION,"toggle" to MpvNativeWindowInputKind.TOGGLE))
            Message(lease.token,word).use { assertEquals(kind,lease.decode(it.data)?.kind) }
        for(word in listOf("quit","seek","toggle extra","TOGGLE",""))
            Message(lease.token,word).use { assertNull(lease.decode(it.data)) }
    }
    @Test fun wrongArgumentCountsAreRejectedBeforeFollowingAnyNativePointer() = source { snapshot ->
        val lease=MpvNativeWindowInputLease(snapshot,1)
        assertNull(lease.decode(null))
        Memory(4).use { data ->
            for(count in listOf(-1,0,1,3,Int.MAX_VALUE)) {data.setInt(0,count);assertNull(lease.decode(data))}
        }
    }
    @Test fun nullUnterminatedOrNonAsciiFieldsNeverBecomeCommands() = source { snapshot ->
        val lease=MpvNativeWindowInputLease(snapshot,1)
        Message(lease.token,"toggle").use { message ->
            message.args.setPointer(8,null);assertNull(lease.decode(message.data))
            message.args.setPointer(8,message.action)
            message.action.setMemory(0,128,65.toByte());assertNull(lease.decode(message.data))
            message.action.clear();message.action.setByte(0,128.toByte());assertNull(lease.decode(message.data))
            message.args.setPointer(0,null);assertNull(lease.decode(message.data))
            message.data.setPointer(8,null);assertNull(lease.decode(message.data))
        }
    }
    @Test fun sameFileReplayGetsANewOpaqueBindingAndRejectsQueuedPredecessorInput() = source { snapshot ->
        val first=MpvNativeWindowInputLease(snapshot,1)
        val replay=MpvNativeWindowInputLease(snapshot,2)
        assertNotEquals(first.token,replay.token)
        Message(first.token,"toggle").use {assertNotNull(first.decode(it.data));assertNull(replay.decode(it.data))}
        assertTrue(replay.bindings.lines().all {"nonrepeatable script-message ${replay.token} " in it})
        assertEquals(setOf("MBTN_LEFT","MOUSE_MOVE","SPACE"),replay.bindings.lines().map {it.substringBefore(' ')}.toSet())
    }
    @Test fun disposingARecipientRejectsAlreadyQueuedEventsAndCannotRetireItsSuccessor() = source { snapshot ->
        val pending=mutableListOf<()->Unit>();val delivery=MpvNativeWindowInputDelivery {pending+=it}
        val event=MpvNativeWindowInputEvent(MpvNativeWindowInputLease(snapshot,1),MpvNativeWindowInputKind.TOGGLE)
        var oldCalls=0;var newCalls=0
        val old=delivery.register {oldCalls++};delivery.emit(event);old.close()
        val next=delivery.register {newCalls++};old.close();delivery.emit(event)
        pending.toList().forEach {it()}
        assertEquals(0,oldCalls);assertEquals(1,newCalls);next.close()
    }
    @Test fun simultaneousRecipientsAreRejectedWithoutReplacingTheCurrentOwner() = source { snapshot ->
        val pending=mutableListOf<()->Unit>();val delivery=MpvNativeWindowInputDelivery {pending+=it}
        var calls=0;val owner=delivery.register {calls++}
        assertFailsWith<IllegalStateException> {delivery.register {error("Foreign recipient")}}
        delivery.emit(MpvNativeWindowInputEvent(MpvNativeWindowInputLease(snapshot,1),MpvNativeWindowInputKind.REVEAL))
        pending.single()();assertEquals(1,calls);owner.close()
    }
    @Test fun motionFloodIsCoalescedWithoutDroppingOrReorderingClickAndSpace() = source { snapshot ->
        val pending=mutableListOf<()->Unit>();val actual=mutableListOf<MpvNativeWindowInputKind>()
        val delivery=MpvNativeWindowInputDelivery {pending+=it}
        val lease=MpvNativeWindowInputLease(snapshot,1);val owner=delivery.register {actual+=it.kind}
        repeat(1000) {delivery.emit(MpvNativeWindowInputEvent(lease,MpvNativeWindowInputKind.MOTION))}
        delivery.emit(MpvNativeWindowInputEvent(lease,MpvNativeWindowInputKind.REVEAL))
        delivery.emit(MpvNativeWindowInputEvent(lease,MpvNativeWindowInputKind.TOGGLE))
        assertEquals(3,pending.size);pending.toList().forEach {it()}
        assertEquals(listOf(MpvNativeWindowInputKind.MOTION,MpvNativeWindowInputKind.REVEAL,MpvNativeWindowInputKind.TOGGLE),actual)
        delivery.emit(MpvNativeWindowInputEvent(lease,MpvNativeWindowInputKind.MOTION));assertEquals(4,pending.size)
        owner.close()
    }
    @Test fun defaultDeliveryActuallyRunsOnTheAwtEventDispatchThread() = source { snapshot ->
        val delivered=CountDownLatch(1);val onEdt=AtomicReference(false)
        val delivery=MpvNativeWindowInputDelivery()
        val owner=delivery.register {onEdt.set(EventQueue.isDispatchThread());delivered.countDown()}
        try {
            delivery.emit(MpvNativeWindowInputEvent(MpvNativeWindowInputLease(snapshot,1),MpvNativeWindowInputKind.TOGGLE))
            assertTrue(delivered.await(5,TimeUnit.SECONDS));assertTrue(onEdt.get())
        } finally {owner.close()}
    }
    @Test fun runtimeProbeIsSilentUnlessExplicitlyEnabled() {
        val lines=mutableListOf<String>(); val probe=MpvRuntimeProbeRecorder(false) {lines+=it}
        repeat(20) {probe.record(MpvRuntimeProbeStage.AWT_MOUSE, it.toLong())}
        probe.recordChanged(MpvRuntimeProbeStage.NVIDIA_PRESENTATION, flags=127)
        assertTrue(lines.isEmpty())
    }
    @Test fun runtimeProbeBoundsRepeatedEventsAndKeepsIndependentStages() {
        val lines=mutableListOf<String>(); val probe=MpvRuntimeProbeRecorder(true) {lines+=it}
        repeat(10000) {probe.record(MpvRuntimeProbeStage.AWT_MOUSE, it.toLong())}
        probe.record(MpvRuntimeProbeStage.WINDOW_SPACE)
        assertEquals(9,lines.size)
        assertTrue(lines.all {it.matches(Regex("BILIPAI_RUNTIME_PROBE stage=[A-Z_]+ first=-?[0-9]+ second=-?[0-9]+ flags=-?[0-9]+"))})
    }
    @Test fun repeatedPresentationPollsDoNotExhaustTransitionEvidence() {
        val lines=mutableListOf<String>(); val probe=MpvRuntimeProbeRecorder(true) {lines+=it}
        repeat(1000) {probe.recordChanged(MpvRuntimeProbeStage.NVIDIA_PRESENTATION, -1, 0, 32)}
        probe.recordChanged(MpvRuntimeProbeStage.NVIDIA_PRESENTATION, 0, 0, 127)
        assertEquals(2,lines.size); assertTrue(lines.last().endsWith("flags=127"))
    }

    @Test fun optedSynchronizationProbeReportsOnlyBoundedNumericModesAndFlags() {
        val lines=mutableListOf<String>(); val probe=MpvRuntimeProbeRecorder(true) {lines+=it}
        probe.nvidiaSynchronization("display-resample", "yes", "no", "12")
        assertEquals(listOf("BILIPAI_RUNTIME_PROBE stage=NVIDIA_SYNCHRONIZATION first=1 second=12 flags=11"), lines)
        probe.nvidiaSynchronization("audio", "no", "yes", "0")
        assertTrue(lines.last().endsWith("first=0 second=0 flags=14"))
        probe.nvidiaSynchronization("display-resample-vdrop", "yes", "no", "1")
        assertTrue(lines.last().endsWith("first=7 second=1 flags=11"))
        probe.nvidiaSynchronization("display-resample-desync", "yes", "no", "2")
        assertTrue(lines.last().endsWith("first=8 second=2 flags=11"))
    }
    @Test fun unknownNativeSynchronizationStringsCannotLeakTextOrBecomeValidCounts() {
        val lines=mutableListOf<String>(); val probe=MpvRuntimeProbeRecorder(true) {lines+=it}
        for (count in listOf("-1", "2147483648", "9999999999999999999999999999", "https://example.invalid/account"))
            probe.nvidiaSynchronization("untrusted\ntext", "unexpected", null, count)
        assertEquals(listOf("BILIPAI_RUNTIME_PROBE stage=NVIDIA_SYNCHRONIZATION first=-1 second=-1 flags=0"), lines)
    }
    @Test fun synchronizationProbeRemainsSilentByDefaultAndBoundsChangingDropCounts() {
        val lines=mutableListOf<String>(); val disabled=MpvRuntimeProbeRecorder(false) {lines+=it}
        disabled.nvidiaSynchronization("display-resample", "yes", "yes", "1"); assertTrue(lines.isEmpty())
        val enabled=MpvRuntimeProbeRecorder(true) {lines+=it}
        repeat(1000) {enabled.nvidiaSynchronization("audio", "no", "no", it.toString())}
        assertEquals(8,lines.size)
    }

    @Test fun nativeOutputGateIsSilentUnlessExplicitlyEnabled() {
        val lines=mutableListOf<String>();val probe=MpvRuntimeProbeRecorder(false) {lines+=it}
        probe.nvidiaOutputGate("vo/gpu/d3d11", "BILIPAI_RTX_PRESENT_GATE 1\n")
        assertTrue(lines.isEmpty())
    }
    @Test fun nativeOutputGateReportsOnlyThePinnedSwapchainNumericDiagnostic() {
        val lines=mutableListOf<String>();val probe=MpvRuntimeProbeRecorder(true) {lines+=it}
        probe.nvidiaOutputGate("vo/gpu/d3d11", "BILIPAI_RTX_PRESENT_GATE 1\n")
        probe.nvidiaOutputGate("vo/gpu/d3d11", "BILIPAI_RTX_PRESENT_GATE 0\r\n")
        probe.nvidiaOutputGate("vo/gpu/d3d11", "BILIPAI_RTX_PRESENT_GATE 11")
        assertEquals(listOf(1,0,11).map {
            "BILIPAI_RUNTIME_PROBE stage=NVIDIA_NATIVE_OUTPUT_GATE first=$it second=0 flags=0"
        },lines)
        // Even gate zero states only that output predicates passed; it grants no presentation proof.
        assertTrue(lines.none {"stage=NVIDIA_PRESENTATION " in it})
    }
    @Test fun foreignAndMalformedNativeGateMessagesCannotBecomeDiagnosticsOrLeakPayloads() {
        val lines=mutableListOf<String>();val probe=MpvRuntimeProbeRecorder(true) {lines+=it}
        for (prefix in listOf("vf", "bilipai-rtx", "vo/gpu", "vo/gpu/d3d11/foreign", " vo/gpu/d3d11"))
            probe.nvidiaOutputGate(prefix, "BILIPAI_RTX_PRESENT_GATE 0\n")
        for (word in listOf("-1", "+1", "01", "12", "2147483648", "", "1 private-token", "1\nextra", "1\u0000"))
            probe.nvidiaOutputGate("vo/gpu/d3d11", "BILIPAI_RTX_PRESENT_GATE $word\n")
        probe.nvidiaOutputGate("vo/gpu/d3d11", "https://example.invalid/private-token")
        assertTrue(lines.isEmpty())
    }
    @Test fun repeatedAndChangingNativeGateMessagesRemainBoundedIndependentlyOfPresentation() {
        val lines=mutableListOf<String>();val probe=MpvRuntimeProbeRecorder(true) {lines+=it}
        repeat(1000) {probe.nvidiaOutputGate("vo/gpu/d3d11", "BILIPAI_RTX_PRESENT_GATE 1\n")}
        assertEquals(1,lines.size)
        repeat(1000) {probe.nvidiaOutputGate("vo/gpu/d3d11", "BILIPAI_RTX_PRESENT_GATE ${it%12}\n")}
        assertEquals(8,lines.size)
        probe.recordChanged(MpvRuntimeProbeStage.NVIDIA_PRESENTATION, -1, 0, 32)
        assertEquals(9,lines.size)
        assertTrue(lines.last().endsWith("first=-1 second=0 flags=32"))
    }

    @Test fun nativeRendererFlagsAreSilentUnlessExplicitlyEnabled() {
        val lines=mutableListOf<String>();val probe=MpvRuntimeProbeRecorder(false) {lines+=it}
        probe.nvidiaRenderFlags("vo/gpu", "BILIPAI_RTX_RENDER_FLAGS 32767\n")
        assertTrue(lines.isEmpty())
    }
    @Test fun pinnedRendererFlagsRemainNumbersAndNeverAcknowledgePresentation() {
        val lines=mutableListOf<String>();val probe=MpvRuntimeProbeRecorder(true) {lines+=it}
        probe.nvidiaRenderFlags("vo/gpu", "BILIPAI_RTX_RENDER_FLAGS 0\n")
        probe.nvidiaRenderFlags("vo/gpu", "BILIPAI_RTX_RENDER_FLAGS 32767\r\n")
        probe.nvidiaRenderFlags("vo/gpu", "BILIPAI_RTX_RENDER_FLAGS 22531")
        assertEquals(listOf(0,32767,22531).map {
            "BILIPAI_RUNTIME_PROBE stage=NVIDIA_NATIVE_RENDER_FLAGS first=$it second=0 flags=0"
        },lines)
        assertTrue(lines.none {"stage=NVIDIA_PRESENTATION " in it})
    }
    @Test fun foreignMalformedAndFutureRendererBitsNeverLeakNativeText() {
        val lines=mutableListOf<String>();val probe=MpvRuntimeProbeRecorder(true) {lines+=it}
        for(prefix in listOf("vo/gpu/d3d11","vf","bilipai-rtx","vo/gpu/foreign"," vo/gpu"))
            probe.nvidiaRenderFlags(prefix, "BILIPAI_RTX_RENDER_FLAGS 0\n")
        for(word in listOf("-1","+1","01","32768","2147483648","","1 private-token","1\nextra","1\u0000"))
            probe.nvidiaRenderFlags("vo/gpu", "BILIPAI_RTX_RENDER_FLAGS $word\n")
        probe.nvidiaRenderFlags("vo/gpu", "https://example.invalid/private-token")
        assertTrue(lines.isEmpty())
    }
    @Test fun rendererFlagFloodCannotExhaustOutputGateOrPresentationEvidence() {
        val lines=mutableListOf<String>();val probe=MpvRuntimeProbeRecorder(true) {lines+=it}
        repeat(1000) {probe.nvidiaRenderFlags("vo/gpu", "BILIPAI_RTX_RENDER_FLAGS 1\n")}
        assertEquals(1,lines.size)
        repeat(1000) {probe.nvidiaRenderFlags("vo/gpu", "BILIPAI_RTX_RENDER_FLAGS ${it%32768}\n")}
        assertEquals(8,lines.size)
        probe.nvidiaOutputGate("vo/gpu/d3d11", "BILIPAI_RTX_PRESENT_GATE 1\n")
        probe.recordChanged(MpvRuntimeProbeStage.NVIDIA_PRESENTATION, -1, 0, 32)
        assertEquals(10,lines.size)
        assertTrue(lines[8].contains("stage=NVIDIA_NATIVE_OUTPUT_GATE first=1"))
        assertTrue(lines.last().endsWith("first=-1 second=0 flags=32"))
    }

    private class Message(token:String,word:String) : AutoCloseable {
        val data=Memory(16);val args=Memory(16);val name=Memory(128);val action=Memory(128)
        init {data.clear();args.clear();name.clear();action.clear();name.setString(0,token,"UTF-8");action.setString(0,word,"UTF-8")
            data.setInt(0,2);data.setPointer(8,args);args.setPointer(0,name);args.setPointer(8,action)}
        override fun close(){action.close();name.close();args.close();data.close()}
    }
    private fun source(block:(OwnedPlaybackSourceSnapshot)->Unit) {
        MpvPlayer().use {native ->
            native.loadVersioned(PlaybackSource("file:///C:/synthetic-native-window-input.media"))
            block(assertNotNull(native.currentSourceSnapshot()))
        }
    }
}
