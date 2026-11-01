package com.bilipai.desktop.hardware

import kotlin.test.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*

class DesktopHardwarePolicyTest {
    @TempDir lateinit var directory:Path
    private fun machine(name:String="NVIDIA GeForce RTX 5080", vendor:HardwareVendor=HardwareVendor.NVIDIA,
        codecs:Set<VideoCodec> = VideoCodec.entries.toSet(), integrated:Boolean=false):HardwareSnapshot {
        val adapter=HardwareAdapter("0000000012345678",vendor,0x10de,0x2c02,name,"32.0.16.2101",16L shl 30,integrated,true,
            codecs.map {DecodeCapability(it,3840,2160,8,HardwareSupport.YES,"profile")})
        return HardwareSnapshot(true,listOf(adapter),listOf(HardwareDisplay(adapter.luid,1,5120,2160,165,1,150,
            HardwareSupport.YES,HardwareSupport.YES,"DISPLAY1",true)),"CPU",16,32L shl 30,
            listOf(HardwareAudioEndpoint("endpoint",HardwareSupport.YES)),"endpoint")
    }
    private fun learning(s:HardwareSnapshot)=HardwareLearning(s.fingerprint(),s.adapters.flatMap { a -> a.decoders.map { d ->
        DecodeMeasurement(a.luid,d.codec,d.width,d.height,"d3d11va-copy",42,0,200,true,"passed")
    } },selfTestComplete=true,selfTestReason="completed")
    private fun proof(s:HardwareSnapshot,quality:Int=4)=EnhancementMeasurement(s.adapters.first().luid,
        if(s.adapters.first().vendor==HardwareVendor.INTEL)VideoEnhancement.INTEL_VPE else VideoEnhancement.NVIDIA_VSR,
        true,quality,600,0,10_000,true,false)

    @TestFactory fun representativeMachines() = listOf(
        Triple("RTX5080 5K 165Hz",HardwareVendor.NVIDIA,VideoCodec.entries.toSet()),
        Triple("GTX1060",HardwareVendor.NVIDIA,setOf(VideoCodec.HEVC,VideoCodec.AVC)),
        Triple("RTX2060",HardwareVendor.NVIDIA,setOf(VideoCodec.HEVC,VideoCodec.AVC)),
        Triple("RTX3060",HardwareVendor.NVIDIA,VideoCodec.entries.toSet()),
        Triple("RTX4060",HardwareVendor.NVIDIA,VideoCodec.entries.toSet()),
        Triple("RX5700",HardwareVendor.AMD,setOf(VideoCodec.HEVC,VideoCodec.AVC)),
        Triple("RX6600",HardwareVendor.AMD,VideoCodec.entries.toSet()),
        Triple("RX6500XT",HardwareVendor.AMD,setOf(VideoCodec.HEVC,VideoCodec.AVC)),
        Triple("RX7800XT",HardwareVendor.AMD,VideoCodec.entries.toSet()),
        Triple("RX9070",HardwareVendor.AMD,VideoCodec.entries.toSet()),
        Triple("Iris Xe",HardwareVendor.INTEL,VideoCodec.entries.toSet()),
        Triple("Arc A770",HardwareVendor.INTEL,VideoCodec.entries.toSet()),
        Triple("Core Ultra",HardwareVendor.INTEL,VideoCodec.entries.toSet()),
        Triple("UHD630",HardwareVendor.INTEL,setOf(VideoCodec.HEVC,VideoCodec.AVC)),
        Triple("Sandbox Basic",HardwareVendor.SOFTWARE,emptySet()),
    ).map { (name,vendor,codecs) -> DynamicTest.dynamicTest(name) {
        val integrated=name in listOf("Iris Xe","Core Ultra","UHD630","Sandbox Basic")
        val s=machine(name,vendor,codecs,integrated)
        val p=decideHardwarePolicy(s,learning(s))
        assertEquals(if(VideoCodec.AV1 in codecs)VideoCodec.AV1 else if(VideoCodec.HEVC in codecs)VideoCodec.HEVC else VideoCodec.AVC,p.codecPreference.first())
        assertEquals(if(codecs.isEmpty())720 else 2160,p.maxHeight)
        assertEquals(VideoEnhancement.NONE,p.enhancement,"Hidden decoding never proves enhancement")
        assertFalse(p.hdr)
        assertEquals(if(integrated)GlassEffects.REDUCED else GlassEffects.FULL,p.glass)
    } }
    @Test fun rtx5080ProofEnablesEnhancementWithoutChangingDisplay() {
        val s=machine(); val p=decideHardwarePolicy(s,learning(s).copy(enhancements=listOf(proof(s))))
        assertEquals(VideoEnhancement.NVIDIA_VSR,p.enhancement);assertTrue(p.hdr);assertEquals(4,p.enhancementQuality)
        assertEquals(165.0,s.displays.first().refreshHz);assertEquals(150,s.displays.first().scalePercent)
        val off=s.copy(displays=s.displays.map {it.copy(hdrEnabled=HardwareSupport.NO)})
        assertFalse(decideHardwarePolicy(off,learning(off).copy(enhancements=listOf(proof(off)))).hdr)
    }
    @Test fun knownDriverFloorsAvoidUnsupportedEnhancementTrials() {
        val s=machine("NVIDIA GeForce RTX 2060").let {it.copy(adapters=it.adapters.map {a->a.copy(driver="31.0.15.3118")})}
        assertEquals(VideoEnhancement.NONE,decideHardwarePolicy(s,learning(s)).enhancementCandidate)
        val supported=s.copy(adapters=s.adapters.map {it.copy(driver="31.0.15.4584")})
        val p=decideHardwarePolicy(supported,learning(supported).copy(enhancements=listOf(proof(supported))))
        assertEquals(VideoEnhancement.NVIDIA_VSR,p.enhancement);assertFalse(p.hdr)
    }
    @Test fun optimusSeparatesDisplayAndObservedRenderer() {
        val intel=machine("Iris Xe",HardwareVendor.INTEL,integrated=true)
        val nvidia=machine().adapters.first().copy(luid="0000000087654321")
        val s=intel.copy(adapters=intel.adapters+nvidia)
        assertEquals(intel.adapters.first().luid,decideHardwarePolicy(s,learning(s)).adapterLuid)
        val p=decideHardwarePolicy(s,learning(s),nvidia.luid)
        assertEquals(nvidia.luid,p.adapterLuid);assertEquals(intel.adapters.first().luid,s.displays.first().adapterLuid)
        assertEquals(VideoEnhancement.NONE,p.enhancement)
    }
    @Test fun noFourKAv1BeforeSuccessfulHardwareMeasurement() {
        val s=machine()
        assertEquals(listOf(VideoCodec.AVC),decideHardwarePolicy(s).codecPreference)
        val bad=learning(s).copy(decodes=learning(s).decodes.map {it.copy(hardwareDecoder="no")})
        val p=decideHardwarePolicy(s,bad)
        assertEquals(720,p.maxHeight);assertEquals("no",p.hwdec);assertFalse(VideoCodec.AV1 in p.codecPreference)
    }
    @Test fun failuresDoNotAllowSoftwareOrSlowAv1() {
        val s=machine();val l=learning(s).copy(decodes=learning(s).decodes.map {if(it.codec==VideoCodec.AV1)it.copy(passed=false,reason="too_slow") else it})
        assertEquals(VideoCodec.HEVC,decideHardwarePolicy(s,l).codecPreference.first())
    }
    @Test fun topologyDriverAndAudioInvalidateMeasurements() {
        val s=machine();val l=learning(s)
        for(changed in listOf(s.copy(adapters=s.adapters.map {it.copy(driver="32.0.16.9999")}),
            s.copy(displays=s.displays.map {it.copy(scalePercent=125)}),s.copy(defaultAudioIdHash="other"))) {
            assertNotEquals(s.fingerprint(),changed.fingerprint());assertEquals(720,decideHardwarePolicy(changed,l).maxHeight)
        }
    }
    @Test fun unknownRendererAndIncompleteInventoryUseSafePolicy() {
        val s=machine().copy(complete=false)
        assertEquals(720,decideHardwarePolicy(s,learning(s)).maxHeight)
        assertEquals(GlassEffects.REDUCED,decideHardwarePolicy(s).glass)
        val mixed=machine().copy(adapters=machine().adapters+machine().adapters.first().copy(luid="other"),displays=emptyList())
        assertNull(decideHardwarePolicy(mixed,learning(mixed)).adapterLuid)
    }
    @Test fun manualPreferencesCannotForceUnsupportedFeatures() {
        val s=machine("GTX1060",codecs=setOf(VideoCodec.AVC))
        val l=learning(s).copy(overrides=HardwareOverrides(false,VideoCodec.AV1,2160,VideoEnhancement.NVIDIA_VSR,true,true,true))
        val p=decideHardwarePolicy(s,l)
        assertEquals(listOf(VideoCodec.AVC),p.codecPreference);assertFalse(p.hdr);assertEquals(VideoEnhancement.NONE,p.enhancement)
    }
    @Test fun exclusiveNeedsPositiveCurrentEndpointSupport() {
        val s=machine();val l=learning(s).copy(overrides=HardwareOverrides(automatic=false,exclusiveAudio=true))
        assertTrue(decideHardwarePolicy(s,l).exclusiveRequested)
        val unknown=s.copy(audio=s.audio.map {it.copy(exclusivePcm48k=HardwareSupport.UNKNOWN)})
        assertFalse(decideHardwarePolicy(unknown,learning(unknown).copy(overrides=l.overrides)).exclusiveAllowed)
    }
    @Test fun cacheCorruptionIsSafeAndRoundTripPreservesFailures() {
        val file=directory.resolve("cache.json");val cache=DesktopHardwareCache(file);val s=machine()
        val value=learning(s).copy(enhancements=listOf(proof(s).copy(failed=true)))
        cache.put(value);assertEquals(value,cache.read().single())
        Files.writeString(file,"broken");assertTrue(cache.read().isEmpty())
    }
    @Test fun rebootLuidChangesKeepMachineFailureMemory() {
        val original=machine().let {s->s.copy(adapters=s.adapters.map {it.copy(stableId="physical-card")},
            displays=s.displays.map {it.copy(stableId="monitor")})}
        val after=original.copy(adapters=original.adapters.map {it.copy(luid="0000000099999999")},
            displays=original.displays.map {it.copy(adapterLuid="0000000099999999")})
        val learned=learning(original).copy(decodes=learning(original).decodes.map {it.copy(adapterIdentity="physical-card")},
            enhancements=listOf(proof(original).copy(failed=true,adapterIdentity="physical-card")))
        assertEquals(original.fingerprint(),after.fingerprint())
        val policy=decideHardwarePolicy(after,learned)
        assertEquals(VideoCodec.AV1,policy.codecPreference.first());assertEquals(VideoEnhancement.NONE,policy.enhancement)
        assertTrue(policy.validatedDecodes.all {it.adapterLuid==after.adapters.first().luid})
    }
    @Test fun firstLaunchRunsOnceAndRestartRetainsResult() {
        val s=machine();val cache=DesktopHardwareCache(directory.resolve("cache.json"));var runs=0
        repeat(2) {DesktopHardwareOptimizer({s},{_,_->runs++;learning(s).decodes},cache).use {it.refreshOnce();it.refreshOnce()}}
        assertEquals(1,runs)
    }
    @Test fun deferredTestsRetryWhenIdle() {
        var allowed=false;var runs=0;val s=machine()
        DesktopHardwareOptimizer({s},{_,_->runs++;learning(s).decodes},DesktopHardwareCache(null),{allowed}).use {
            it.refreshOnce();assertEquals(0,runs);allowed=true;it.refreshOnce();assertEquals(1,runs)
        }
    }
    @Test fun sustainedOverloadLearnsFailureAndRestoreAutoKeepsIt() {
        val s=machine();val cache=DesktopHardwareCache(directory.resolve("cache.json"));cache.put(learning(s))
        DesktopHardwareOptimizer({s},{_,_->error("must not repeat")},cache).use { service ->
            service.refreshOnce();service.reportPlayback(s.fingerprint(),proof(s))
            assertTrue(service.state.value.policy.hdr)
            val bad=proof(s).copy(droppedFrames=60)
            service.reportPlayback(s.fingerprint(),bad);service.reportPlayback(s.fingerprint(),bad)
            service.reportPlayback(s.fingerprint(),proof(s));service.restoreAutomatic()
            assertEquals(VideoEnhancement.NONE,service.state.value.policy.enhancement)
            assertTrue(cache.read().single().enhancements.any {it.failed})
            assertFalse(service.reportPlayback("stale",proof(s)))
        }
    }
    @Test fun diagnosticOmitsRawDevicePathsAndSourceNames() {
        val s=machine().copy(displays=machine().displays.map {it.copy(sourceName="private-monitor-path")})
        val json=hardwareDiagnosticJson(HardwareOptimizationState(s,learning(s)))
        assertFalse(json.contains("private-monitor-path"));assertTrue(json.contains("selfTest"));assertTrue(json.contains("decoders"))
    }
}
