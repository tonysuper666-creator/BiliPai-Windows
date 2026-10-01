from pathlib import Path
import importlib.util
H=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('prep',H/'prepare.py');p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)
old=p.read(H.parent/'stable-original-video-byte-cache-actual73-proof/InstalledCacheConsumerProof.kt').decode().replace('\r\n','\n')
before=old[:old.index('    try {\n        player.startSoftwareTransport()')]
before=before.replace('import com.bilipai.desktop.data.DesktopRepository','import com.bilipai.desktop.plugins.*\nimport com.bilipai.desktop.data.DesktopCommunityRepository\nimport com.bilipai.desktop.data.DesktopBlockedUpStore\nimport com.bilipai.desktop.data.DesktopDiscoveryRepository\nimport com.bilipai.desktop.data.DesktopDiscoveryPreferences\nimport com.bilipai.desktop.data.DesktopRepository')
before=before.replace('owned73-local-origin','owned-byte-failure-origin')
# Same typed Factory/Repository/Intent helpers, but this cohort ONLY injects real
# HTTP503 native byte failures; the old 73 fixtures and their raw outcomes stay closed.
before=before.replace('    suspend fun ready(value:', '''    val pluginStore = DesktopPluginStore(owned.resolve("plugins"))
    val pluginContext = DesktopPluginContext(pluginStore)
    val blocked = DesktopBlockedUpStore(pluginContext)
    val community = DesktopCommunityRepository(repo, blocked)
    val discovery = DesktopDiscoveryRepository(repo, DesktopDiscoveryPreferences(owned.resolve("discovery"), blocked))
    val runtime = DesktopPluginRuntime(pluginStore, repo, community, discovery)
    val statusBinding = binding()
    val statuses = object : DesktopOriginalVideoPlaybackStatus {
        override fun isPlaybackLoggedIn() = statusBinding.rawRepository.isPlaybackLoggedIn()
        override fun isPlaybackVip() = statusBinding.rawRepository.isPlaybackVip()
        override fun isUsingDedicatedPlaybackAccount() = statusBinding.rawRepository.isUsingDedicatedPlaybackAccount()
        override fun isAppApiCoolingDown() = statusBinding.rawRepository.isAppApiCoolingDown()
    }
    val invocations = DesktopOriginalVideoPlaybackInvocationPorts(CoroutineScope(entryJob + Dispatchers.Default),
        { live.get() }, {
            val b = binding(); val requestJob = checkNotNull(currentCoroutineContext()[Job])
            val mediaPort = factory(b.captureMediaBytes(cache), null) { source, _ ->
                owner.publish(subject, source, player.currentSourceVersion, requestJob) { live.get() }
            }.media
            DesktopOriginalVideoPlaybackInvocation(b.rawRepository, mediaPort, b::assertCurrent)
        }, statuses, { owner.acceptedMedia { expected -> factory(acceptedRequest(expected), expected) { _, _ ->
            error("accepted NativeOwner, not delegate publisher, owns recovery")
        }.media } })
    // No Runtime onVideoLoad was performed: this is the actual absent generation,
    // while observer is intentionally independent of enabled plugins/dispatch.
    val runtimeGeneration = AtomicReference<Long?>()
    val bridge = DesktopOriginalVideoOwnerPluginBridge(runtime, owner, invocations,
        { runtimeGeneration.get() }, { statusBinding::admitCurrentMutation }, { expected ->
            repo.ownedPlaybackCallFactory(auth) { owner.isCurrent(expected) }
        })
    suspend fun ready(value:''')
body='''    try {
        player.startSoftwareTransport()
        failBody.set(true)
        val resolver = async(Dispatchers.IO) {
            val b = binding(); val job = checkNotNull(currentCoroutineContext()[Job])
            val f = factory(b.captureMediaBytes(cache), null) { source, _ ->
                owner.publish(subject, source, player.currentSourceVersion, job) { live.get() }
            }
            f.media.withPlaybackIntent(1_375, false) {
                f.media.accept(f.media.prepareProgressive("$base/fail/dash-stream0.mp4"))
            }
            checkNotNull(owner.current())
        }
        val failed = resolver.await()
        val carrier = checkNotNull(failed.nativeSource.source.nativeTransport)
        await("real HTTP503 byte read publishes a typed current native lease event") {
            failures.get() > 0 && carrier.lease.latestNativeFailure() != null
        }
        val event = checkNotNull(carrier.lease.latestNativeFailure())
        val readback = player.state.value
        observe("real503EventBeforeObserver")
        verify("503 event is body IO, not manufactured MPV demux failure", event.stage == DesktopNativeByteFailureStage.BODY_READ && readback.failure == null)
        verify("normal completed resolver leaves accepted source job and failure frame live", resolver.isCompleted && owner.current() === failed && checkNotNull(sourceJobs[failed.sourceVersion]).isActive)
        verify("event fixes exact source version, publication, receipt and current lease", event.sourceVersion == failed.sourceVersion &&
            event.stamp.publication === failed.nativeSource.source.nativePublication && event.stamp.receipt == auth.receipt && carrier.lease.ownsNativeFailure(event))
        verify("bounded event carries no origin URL, cookies or exception text", !event.toString().contains(base) && !event.toString().contains("Cookie") && !event.toString().contains("Origin did not"))
        verify("buffering Reader has real requested pause and valid position", readback.paused && readback.positionSeconds.isFinite() && readback.positionSeconds >= 0.0)

        failBody.set(false)
        // Exercise actual Bridge entry through its actual captured Invocation
        // scope, like the audited original observer before plugin/isPlaying tests.
        val observer = invocations.launch { bridge.observeInheritedPluginMute() }
        observer.join()
        verify("actual Bridge observer recovers with no native failure attempt", !observer.isCancelled && owner.current() !== failed)
        val direct = checkNotNull(owner.current()); ready(direct, false)
        observe("503DirectRealAck")
        verify("direct native ACK keeps semantic URL, source version and authorization", direct.sourceVersion == failed.sourceVersion &&
            direct.nativeSource.source.nativeTransport == null && direct.nativeSource.source.videoUrl == failed.nativeSource.source.videoUrl &&
            direct.nativeSource.source.authorizationReceipt == failed.nativeSource.source.authorizationReceipt)
        verify("direct source uses actual buffered position and desired pause", direct.nativeSource.source.startPositionSeconds == readback.positionSeconds && direct.nativeSource.source.startPaused == readback.paused)
        verify("real native readback preserves requested pause and position", player.state.value.nativePaused == readback.paused && abs(player.state.value.positionSeconds - readback.positionSeconds) < 0.20)
        verify("recover retires failed capability and event, without cache retry", status(carrier.videoUri) == 410 && cache.stats().registrations == 0 && carrier.lease.latestNativeFailure() == null)
        verify("same failure cannot recover twice or target direct source", !owner.recoverDirectAfterByteFailure(failed, event) && !owner.recoverDirectAfterByteFailure(direct, event))
        val before = player.currentSourceSnapshot()
        bridge.observeInheritedPluginMute()
        verify("observer after direct ACK cannot recreate cache/load loop", before != null && player.ownsSourceSnapshot(before) && owner.current() === direct && cache.stats().registrations == 0)

        // A second real failure is captured before a new native source replaces
        // it. The old event must not mutate the subsequent healthy publication.
        failBody.set(true)
        val second = async(Dispatchers.IO) {
            val b = binding(); val job = checkNotNull(currentCoroutineContext()[Job])
            val f = factory(b.captureMediaBytes(cache), null) { source, _ -> owner.publish(subject, source, player.currentSourceVersion, job) { live.get() } }
            f.media.withPlaybackIntent(500, false) { f.media.accept(f.media.prepareProgressive("$base/fail/dash-stream1.mp4")) }
            checkNotNull(owner.current())
        }.await()
        val oldCarrier = checkNotNull(second.nativeSource.source.nativeTransport)
        await("second real failed IO produces its own fixed publication event") { oldCarrier.lease.latestNativeFailure() != null }
        val oldEvent = checkNotNull(oldCarrier.lease.latestNativeFailure())
        failBody.set(false)
        val finalResolver = async(Dispatchers.IO) {
            val b = binding(); val job = checkNotNull(currentCoroutineContext()[Job])
            val f = factory(b.captureMediaBytes(cache), null) { source, _ -> owner.publish(subject, source, player.currentSourceVersion, job) { live.get() } }
            f.media.withPlaybackIntent(500, false) { f.media.accept(f.media.prepareProgressive("$base/dash-stream1.mp4")) }
            checkNotNull(owner.current())
        }
        val final = finalResolver.await(); ready(final, false)
        verify("new load retires old typed event and local capability", final.sourceVersion > second.sourceVersion && status(oldCarrier.videoUri) == 410 && oldCarrier.lease.latestNativeFailure() == null)
        verify("retired event cannot recover newer accepted identity", !owner.recoverDirectAfterByteFailure(final, oldEvent) && !owner.recoverDirectAfterByteFailure(second, oldEvent) && owner.current() === final)
        owner.close()
        verify("closed owner cannot recover retained old IO event", !owner.recoverDirectAfterByteFailure(second, oldEvent) && owner.current() == null)
        observe("ownerRetired")
        println("{\\"snapshot\\":$snapshot,\\"groups\\":3,\\"assertions\\":$checks,\\"declaredProductionFamilies\\":3,\\"real503\\":true,\\"actualBridge\\":true,\\"actualDirectNativeAck\\":true,\\"wholeRootVM\\":false,\\"MainShell\\":false,\\"realAccount\\":false,\\"HTTPExternal\\":false,\\"OSInput\\":false}")
    } finally {
        owner.close();player.close();cache.close();live.set(false);entryJob.cancel();sourceJobs.values.forEach { it.cancel() }
        invocations.close();runtime.shutdownForRestore();origin.stop(0);threads.shutdownNow();root.cancel()
    }
}
'''
p.write(H/'NativeByteFailureProof.kt',before+body)
print('fixture prepared')
