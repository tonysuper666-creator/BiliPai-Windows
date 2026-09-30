package com.bilipai.desktop.player

import com.android.purebilibili.feature.anime4k.*
import com.bilipai.desktop.plugins.DesktopVideoShaderResources
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.bilipai.desktop.plugins.*
import com.android.purebilibili.feature.plugin.Anime4KPlugin
import com.android.purebilibili.core.plugin.PluginStore
import java.util.concurrent.atomic.AtomicLong
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.max

/** Only the root-coordinated exclusive real D3D11 slot runs this fixture. No Internet/accounts. */
internal object RuntimeFsrNativeSmoke {
    fun run(player: MpvPlayer, video: File, pqVideo: File, hlgVideo: File, output: File, runtime: DesktopPluginRuntime, scope: CoroutineScope, epoch: AtomicLong): Map<String, String> {
        val checks = linkedMapOf<String, String>()
        val configuration = runtime.videoEnhancement.configState
        val enabled = runtime.plugins.map { plugins -> plugins.any { it.plugin === runtime.videoEnhancement && it.enabled } }
            .stateIn(scope, SharingStarted.Eagerly, false)
        runBlocking {
            runtime.enhancementConfiguration.setAlgorithm(VideoEnhancementAlgorithm.FSR_1_0).await()
            runtime.enhancementConfiguration.setFsrSharpness(0f).await()
            runtime.enhancementConfiguration.setRememberAcrossVideos(false, false).await()
        }
        check(runtime.plugins.value.count { it.plugin.id == Anime4KPlugin.PLUGIN_ID } == 1)
        check(runtime.plugins.value.single { it.plugin.id == Anime4KPlugin.PLUGIN_ID }.plugin === runtime.videoEnhancement)
        checks["uniqueOriginalRuntimeProviderAndOriginalConfigFlow"] = "passed"
        val pip = MutableStateFlow(false)
        val started = MutableStateFlow(true)
        val resources = DesktopVideoShaderResources(File(output, "cnn-cache").toPath())
        DesktopVideoEnhancementSession(player, configuration, enabled, resources, File(output, "fsr-cache").toPath(),
            pip, started, { runtime.setEnabled(Anime4KPlugin.PLUGIN_ID, true) },
            { runtime.enhancementConfiguration.rememberCurrentVideoEnabled(it) }, sessionEpoch = epoch::get,
            enablePluginGuarded = { stillOwned -> runtime.setEnabled(Anime4KPlugin.PLUGIN_ID, true, stillOwned) }).use { enhancement ->
            fun load(file: File, identity: String): Long {
                val version = player.loadVersioned(PlaybackSource(file.absolutePath, referer = "", title = "FSR native fixture", startPositionSeconds = 2.0, startPaused = true))
                enhancement.bindVideoIdentity(identity, version)
                waitFor(player, "decoded fixture and real video metadata") {
                    !it.loading && it.firstVideoFrameReady && it.nativePaused == true && it.videoCodec != null &&
                        player.videoOutput.value.sourceVersion == version && player.videoOutput.value.maximumTextureDimension != null &&
                        player.videoOutput.value.inputWidth > 0 && player.videoOutput.value.displayWidth > 0
                }
                return version
            }
            var owner = load(video, "BV-FSR-native-one")
            waitForShaders(player, "initial original default disabled") { it.requestedFiles.isEmpty() && it.appliedFiles.isEmpty() }
            val position = player.state.value.positionSeconds
            val completedSeek = player.state.value.seekCompletedId
            Thread.sleep(200)
            val baseline = capture(player)
            Thread.sleep(150)
            val noise = difference(baseline, capture(player)).first
            ImageIO.write(baseline, "png", File(output, "native-fsr-before.png"))

            runBlocking { requireNotNull(enhancement.setCurrentVideoEnabled(true)).join() }
            waitForFsr(player, enhancement, owner, "original EASU and RCAS at weak sharpness")
            val weak = changedCapture(player, baseline, noise, "FSR reconstruction")
            ImageIO.write(weak, "png", File(output, "native-fsr-weak.png"))
            checks["originalFsrEasuAndRcasExecuted"] = "passed"
            checks["actualFsrIntermediateFormat"] = requireNotNull(player.videoShaderState.value.actualIntermediateFormat)
            checks["actualGpuMaximumTextureDimension"] = requireNotNull(player.videoOutput.value.maximumTextureDimension).toString()
            checks["originalFsrVisibleReconstruction"] = "passed"

            val weakVersion = player.videoShaderState.value.configurationVersion
            val weakFiles = player.videoShaderState.value.requestedFiles
            runBlocking { runtime.enhancementConfiguration.setFsrSharpness(1f).await() }
            waitForFsr(player, enhancement, owner, "original strongest RCAS parameter")
            waitForShaders(player, "a fresh sharpness configuration") { it.configurationVersion > weakVersion && it.active }
            check(player.videoShaderState.value.requestedFiles == weakFiles) { "A uniform update rewrote the original FSR resources." }
            val strong = changedCapture(player, weak, noise, "original RCAS sharpness parameter")
            ImageIO.write(strong, "png", File(output, "native-fsr-strong.png"))
            checks["originalRcasUniformChangesPixelsWithoutRewritingAssets"] = "passed"
            check(player.currentSourceVersion == owner && player.state.value.paused && player.state.value.nativePaused == true &&
                abs(player.state.value.positionSeconds - position) < 0.15 && player.state.value.seekCompletedId == completedSeek) {
                "Enhancement changed paused position, source ownership or a tracked user seek."
            }
            checks["fsrPausedOwnerPositionAndTrackedSeekPreserved"] = "passed"

            // Real original Runtime setters and real native output for both Anime4K presets.
            runBlocking { runtime.setEnabled(Anime4KPlugin.PLUGIN_ID, false) }
            waitForShaders(player, "Runtime disable clears FSR") { it.requestedFiles.isEmpty() && it.appliedFiles.isEmpty() && !it.active }
            waitForRestored(player, baseline, noise, File(output,"runtime-disabled-fsr.png"))
            runBlocking { runtime.enhancementConfiguration.setAlgorithm(VideoEnhancementAlgorithm.ANIME4K).await() }
            var previousDescriptions = emptySet<String>()
            for (preset in listOf(Anime4KPreset.FAST, Anime4KPreset.QUALITY)) {
                runBlocking { runtime.enhancementConfiguration.setPreset(preset).await();runtime.setEnabled(Anime4KPlugin.PLUGIN_ID,true) }
                val prepared = prepareVideoShaders(runBlocking { resources.resolveAnime4KPaths(preset) })
                val exclusive = prepared.descriptions-previousDescriptions;check(exclusive.isNotEmpty())
                waitForShaders(player,"Runtime original ${preset.name} GPU passes and rgba16hf") { actual ->
                    actual.active && actual.actualIntermediateFormat=="rgba16hf" && actual.appliedFiles==prepared.paths &&
                        actual.executedPasses.any { pass -> exclusive.any { pass.contains(it) } } && enhancement.state.value.active }
                ImageIO.write(changedCapture(player,baseline,noise,"Runtime ${preset.name} reconstruction"),"png",File(output,"runtime-anime4k-${preset.name.lowercase()}.png"))
                check(player.currentSourceVersion==owner && player.state.value.paused && player.state.value.nativePaused==true &&
                    abs(player.state.value.positionSeconds-position)<0.15 && player.state.value.seekCompletedId==completedSeek)
                previousDescriptions=prepared.descriptions
            }
            checks["originalRuntimeAnime4KFastQualityRgba16hfPixelsAndPausedSeek"]="passed"
            pip.value=true
            waitForShaders(player,"Runtime Anime4K PiP bypass") { it.requestedFiles.isEmpty() && it.appliedFiles.isEmpty() }
            waitForRestored(player,baseline,noise,File(output,"runtime-anime4k-pip-bypass.png"));check(enhancement.state.value.bypassReason==Anime4KBypassReason.PICTURE_IN_PICTURE)
            pip.value=false
            waitForShaders(player,"Runtime Anime4K PiP resume") { it.active && it.actualIntermediateFormat=="rgba16hf" }
            started.value=false
            waitForShaders(player,"Runtime Anime4K host bypass") { it.requestedFiles.isEmpty() && it.appliedFiles.isEmpty() }
            check(enhancement.state.value.bypassReason==Anime4KBypassReason.HOST_NOT_STARTED)
            started.value=true
            waitForShaders(player,"Runtime Anime4K host resume") { it.active && it.actualIntermediateFormat=="rgba16hf" }
            for ((file,gamma) in listOf(pqVideo to "pq",hlgVideo to "hlg")) {
                val hdr=load(file,"BV-runtime-anime-$gamma");check(player.videoOutput.value.gamma==gamma)
                runBlocking { requireNotNull(enhancement.setCurrentVideoEnabled(true)).join() }
                waitFor(player,"Runtime Anime4K $gamma bypass") { enhancement.state.value.sourceVersion==hdr && enhancement.state.value.requested && enhancement.state.value.bypassReason==Anime4KBypassReason.HDR_OR_DOLBY_VISION }
                waitForShaders(player,"Runtime Anime4K HDR direct output") { it.requestedFiles.isEmpty() && it.appliedFiles.isEmpty() && !it.active }
                ImageIO.write(capture(player),"png",File(output,"runtime-anime4k-$gamma-bypass.png"))
            }
            checks["originalRuntimeAnime4KPipHostAndPqHlgBypassResume"]="passed"
            runBlocking { runtime.enhancementConfiguration.setAlgorithm(VideoEnhancementAlgorithm.FSR_1_0).await() }
            owner=load(video,"BV-FSR-native-one")
            runBlocking { requireNotNull(enhancement.setCurrentVideoEnabled(true)).join() }
            waitForFsr(player,enhancement,owner,"Runtime switch from Anime4K back to original FSR")
            val persisted=runBlocking { PluginStore.getConfigJson(DesktopPluginContext(DesktopPluginStore(runtime.store.root)),Anime4KPlugin.PLUGIN_ID) }
            check(persisted==runBlocking { runtime.configuration(Anime4KPlugin.PLUGIN_ID) })
            checks["runtimeSetterOffOnAlgorithmSwitchAndSharedStore"]="passed"

            pip.value = true
            waitForShaders(player, "original PiP bypass") { it.requestedFiles.isEmpty() && it.appliedFiles.isEmpty() && !it.active }
            waitForRestored(player, baseline, noise, File(output, "native-fsr-pip-bypass.png"))
            check(enhancement.state.value.bypassReason == Anime4KBypassReason.PICTURE_IN_PICTURE)
            pip.value = false
            waitForFsr(player, enhancement, owner, "FSR after PiP exit")
            started.value = false
            waitForShaders(player, "original host-stopped bypass") { it.requestedFiles.isEmpty() && it.appliedFiles.isEmpty() }
            check(enhancement.state.value.bypassReason == Anime4KBypassReason.HOST_NOT_STARTED)
            started.value = true
            waitForFsr(player, enhancement, owner, "FSR after host resumes")
            checks["originalPipAndLifecycleBypassAndResume"] = "passed"

            val sameBvPart = load(video, "BV-FSR-native-one")
            check(sameBvPart != owner)
            waitForFsr(player, enhancement, sameBvPart, "same-BV part preserves the original override")
            val nextBv = load(video, "BV-FSR-native-two")
            waitForShaders(player, "new BV follows original disabled default") { it.requestedFiles.isEmpty() && it.appliedFiles.isEmpty() }
            check(!enhancement.state.value.requested)
            checks["originalSameBvPartsAndNewBvSwitchPolicy"] = "passed"

            for ((file, gamma) in listOf(pqVideo to "pq", hlgVideo to "hlg")) {
                val hdrOwner = load(file, "BV-FSR-native-$gamma")
                check(player.videoOutput.value.gamma == gamma) { "The synthetic HDR fixture did not report its real transfer function." }
                runBlocking { requireNotNull(enhancement.setCurrentVideoEnabled(true)).join() }
                waitFor(player, "original $gamma HDR bypass") { enhancement.state.value.sourceVersion == hdrOwner &&
                    enhancement.state.value.requested && enhancement.state.value.bypassReason == Anime4KBypassReason.HDR_OR_DOLBY_VISION }
                waitForShaders(player, "HDR preserves direct native output") { it.requestedFiles.isEmpty() && it.appliedFiles.isEmpty() && !it.active }
                ImageIO.write(capture(player), "png", File(output, "native-fsr-$gamma-bypass.png"))
            }
            checks["realPqAndHlgMetadataUsesOriginalHdrBypass"] = "passed"

            val finalOwner = load(video, "BV-FSR-native-final")
            runBlocking { requireNotNull(enhancement.setCurrentVideoEnabled(true)).join() }
            waitForFsr(player, enhancement, finalOwner, "final owned FSR configuration")
            enhancement.close()
            waitForShaders(player, "closing renderer releases only its enhancement") { it.requestedFiles.isEmpty() && it.appliedFiles.isEmpty() }
            check(player.currentSourceVersion == finalOwner && player.state.value.videoCodec != null)
            checks["rendererClosePreservesNativeMediaOwner"] = "passed"
            check(nextBv < finalOwner)

            runBlocking { runtime.enhancementConfiguration.setRememberAcrossVideos(true,true).await() }
            DesktopVideoEnhancementSession(player, configuration, enabled, resources, File(output, "fsr-cache").toPath(),
                pip, started, { runtime.setEnabled(Anime4KPlugin.PLUGIN_ID,true) },
                { runtime.enhancementConfiguration.rememberCurrentVideoEnabled(it) },sessionEpoch=epoch::get,
                enablePluginGuarded={ stillOwned ->runtime.setEnabled(Anime4KPlugin.PLUGIN_ID,true,stillOwned) }).use { oldRenderer ->
                oldRenderer.bindVideoIdentity("BV-FSR-native-final", finalOwner)
                waitForFsr(player, oldRenderer, finalOwner, "original remembered-enabled current video")
                val foreignOwner = player.loadVersioned(PlaybackSource(video.absolutePath, referer = "", startPositionSeconds = 2.0, startPaused = true))
                waitFor(player, "replacement renderer's native source") { !it.loading && it.firstVideoFrameReady && it.nativePaused == true &&
                    player.videoOutput.value.sourceVersion == foreignOwner && player.videoOutput.value.inputWidth > 0 && player.videoOutput.value.displayWidth > 0 }
                val actual = player.videoOutput.value
                val program = requireNotNull(DesktopFsrHookAdapter.prepare(actual.inputWidth, actual.inputHeight, actual.displayWidth, actual.displayHeight,
                    requireNotNull(actual.maximumTextureDimension)))
                val paths = runBlocking { DesktopFsrVideoShaderResources(File(output, "fsr-cache").toPath()).resolve(program) }
                val foreignConfiguration = requireNotNull(player.setVideoShadersIfSourceVersion(foreignOwner, paths,
                    PlayerVideoShaderOptions(program.requiredIntermediateFormat, program.parameters, program.requiredPassDescriptions)))
                oldRenderer.close()
                waitForShaders(player, "old renderer close preserves the replacement's real FSR passes") {
                    it.configurationVersion == foreignConfiguration && it.active && it.actualIntermediateFormat == "rgba8"
                }
                check(player.currentSourceVersion == foreignOwner && player.state.value.videoCodec != null)
                checks["oldRendererClosePreservesForeignMediaAndGpuConfiguration"] = "passed"
                check(player.clearVideoShadersIfConfigurationVersion(foreignConfiguration))
                waitForShaders(player, "final foreign renderer cleanup") { it.requestedFiles.isEmpty() && it.appliedFiles.isEmpty() }
            }
        }
        runBlocking { runtime.setEnabled(Anime4KPlugin.PLUGIN_ID,false) }
        waitForShaders(player,"account guard starts with plugin off") { it.requestedFiles.isEmpty() && it.appliedFiles.isEmpty() }
        for (changeAccount in listOf(true,false)) {
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val expected=player.currentSourceVersion
            DesktopVideoEnhancementSession(player,configuration,enabled,resources,File(output,"fsr-cache").toPath(),pip,started,
                { error("Guarded Runtime enable required") },{ runtime.enhancementConfiguration.rememberCurrentVideoEnabled(it) },epoch::get,
                enablePluginGuarded={ stillOwned ->entered.complete(Unit);release.await();runtime.setEnabled(Anime4KPlugin.PLUGIN_ID,true,stillOwned) }).use { pending ->
                pending.bindVideoIdentity("BV-runtime-guard",expected)
                val job=requireNotNull(pending.setCurrentVideoEnabled(true));runBlocking { withTimeout(5000) { entered.await() } }
                if(changeAccount) { epoch.incrementAndGet();pending.bindVideoIdentity(null,0) }
                else player.loadVersioned(PlaybackSource(video.absolutePath,referer="",startPositionSeconds=2.0,startPaused=true))
                release.complete(Unit);runBlocking { withTimeout(5000) { job.join() } }
                check(runtime.plugins.value.none { it.plugin.id==Anime4KPlugin.PLUGIN_ID && it.enabled })
                check(!runBlocking { PluginStore.isEnabled(runtime.context,Anime4KPlugin.PLUGIN_ID) })
                waitForShaders(player,"late Runtime enable discarded") { it.requestedFiles.isEmpty() && it.appliedFiles.isEmpty() }
            }
        }
        checks["realRuntimeGuardedEnableRejectsChangedAccountEpochAndNativeSource"]="passed"
        return checks
    }

    private fun waitForFsr(player: MpvPlayer, enhancement: DesktopVideoEnhancementSession, owner: Long, description: String) {
        try { waitForShaders(player, description) { it.active && it.actualIntermediateFormat == "rgba8" &&
            setOf(DesktopFsrHookAdapter.EASU_DESCRIPTION, DesktopFsrHookAdapter.RCAS_DESCRIPTION).all { name -> it.executedPasses.any { pass -> pass.contains(name) } } &&
            enhancement.state.value.active && enhancement.state.value.sourceVersion == owner } }
        catch(failure: Throwable) {
            val native = player.state.value
            val shaders = player.videoShaderState.value
            File(System.getProperty("bilipai.fsr.probe.output"), "failure-before-renderer-close.txt").writeText(
                "Stage: $description\nOwner: $owner owns=${player.ownsSourceVersion(owner)}\n" +
                "Enhancement: ${enhancement.state.value}\n" +
                "Native: ready=${native.ready} loading=${native.loading} codec=${native.videoCodec} ended=${native.ended} audioOnly=${native.audioOnly}\n" +
                "Shaders: version=${shaders.configurationVersion} requested=${shaders.requestedFiles.size} applied=${shaders.appliedFiles.size} " +
                "active=${shaders.active} requestedFormat=${shaders.requestedIntermediateFormat} actualFormat=${shaders.actualIntermediateFormat} error=${shaders.error}\n" +
                "Passes: ${shaders.executedPasses}\nOutput: ${player.videoOutput.value}\n")
            throw failure
        }
    }

    private fun waitFor(player: MpvPlayer, description: String, condition: (PlayerState) -> Boolean) {
        NativeProofWindowGuard.check(player)
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            val state = player.state.value
            check(state.error == null) { "$description: ${state.error}" }
            if (condition(state)) return
            Thread.sleep(40)
        }
        error("Timed out waiting for $description; safe native output: ${player.videoOutput.value}")
    }

    private fun waitForShaders(player: MpvPlayer, description: String, condition: (PlayerVideoShaderState) -> Boolean) {
        waitFor(player, description) {
            val state = player.videoShaderState.value
            check(state.error == null) { "$description: ${state.error}" }
            condition(state)
        }
    }

    private fun capture(player: MpvPlayer): BufferedImage {
        var bounds: Rectangle? = null
        SwingUtilities.invokeAndWait {
            check(player.surface.isShowing)
            val point = player.surface.locationOnScreen
            bounds = Rectangle(point.x, point.y, player.surface.width, player.surface.height)
        }
        NativeProofWindowGuard.check(player, requireNotNull(bounds))
        return Robot().createScreenCapture(requireNotNull(bounds))
    }

    private fun changedCapture(player: MpvPlayer, baseline: BufferedImage, noise: Double, description: String): BufferedImage {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            val sample = capture(player)
            val delta = difference(baseline, sample)
            if (delta.first > max(0.1, noise * 3.0) && delta.second > 100) return sample
            Thread.sleep(50)
        }
        ImageIO.write(capture(player), "png", File(System.getProperty("bilipai.fsr.probe.output", ".local/fsr"), "native-fsr-unchanged-failure.png"))
        error("$description acknowledged options but did not change real displayed video pixels.")
    }

    private fun waitForRestored(player: MpvPlayer, baseline: BufferedImage, noise: Double, destination: File) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            val sample = capture(player)
            if (difference(baseline, sample).first <= max(0.1, noise * 2.0)) {
                ImageIO.write(sample, "png", destination); return
            }
            Thread.sleep(50)
        }
        error("Original output bypass did not restore the direct native frame.")
    }

    private fun difference(first: BufferedImage, second: BufferedImage): Pair<Double, Int> {
        check(first.width == second.width && first.height == second.height)
        var sum = 0L; var changed = 0
        for (y in 0 until first.height) for (x in 0 until first.width) {
            val a = first.getRGB(x, y); val b = second.getRGB(x, y)
            val red = abs((a ushr 16 and 255) - (b ushr 16 and 255))
            val green = abs((a ushr 8 and 255) - (b ushr 8 and 255))
            val blue = abs((a and 255) - (b and 255))
            sum += red + green + blue
            if (max(red, max(green, blue)) > 8) changed++
        }
        return sum.toDouble() / (first.width * first.height * 3.0) to changed
    }
}
