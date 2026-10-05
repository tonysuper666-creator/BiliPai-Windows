@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.viewmodel.VideoSubjectSnapshot
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.PlayerSelfTest
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import kotlinx.serialization.json.*
import java.awt.Canvas
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.ceil

/** Opt-in carrier acceptance on PlayerSelfTest's EXISTING local player/Canvas.
 * Static diagnostic rectangles are not a confirmed Like/coin/triple or a VM
 * receipt. No account, protocol, second player, default profile or timer owner
 * is created. Production popup, peer style and event delivery are exercised.
 */
internal object DesktopFeedbackCarrierNativeSmoke {
    private interface User32 : StdCallLibrary {
        fun GetWindowLongW(window: Pointer, index: Int): Int
        fun GetForegroundWindow(): Pointer?
    }
    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        val result = AtomicReference<Result<T>?>()
        SwingUtilities.invokeAndWait { result.set(runCatching(block)) }
        return checkNotNull(result.get()).getOrThrow()
    }

    fun run(player: MpvPlayer, outputDirectory: File) {
        check(!SwingUtilities.isEventDispatchThread())
        val expected = checkNotNull(player.currentSourceSnapshot())
        val incoming = player.state.value
        check(incoming.ready && !incoming.loading && incoming.error == null && incoming.firstVideoFrameReady &&
            incoming.paused && incoming.nativePaused == true) { "Carrier smoke requires the original settled paused video." }
        val owner = edt { checkNotNull(SwingUtilities.getWindowAncestor(player.surface)) as Frame }
        val canvas = edt { player.surface.components.filterIsInstance<Canvas>().single() }
        val initialPlacement = edt { owner.extendedState }
        val initialChildren = edt { owner.ownedWindows.toSet() }
        val user32 = Native.load("user32", User32::class.java)
        val robot = Robot()
        val deadline = System.nanoTime() + 35_000_000_000L
        val stages = mutableListOf<JsonObject>()
        var scene: ImageComposeScene? = null
        var popup: ComposeDialog? = null
        var windowIdentity: Any? = null
        var available = false
        var rejected = false
        var retired = false
        var presses = 0
        var releases = 0
        var closed = false
        var windowDisposed = false
        var playbackPreserved = false
        var cleanupFailed = false
        var failure: Throwable? = null
        val createdChildren = linkedSetOf<java.awt.Window>()
        val mouse = object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) { if (event.component === canvas && event.button == MouseEvent.BUTTON1) presses++ }
            override fun mouseReleased(event: MouseEvent) { if (event.component === canvas && event.button == MouseEvent.BUTTON1) releases++ }
        }
        fun ownsSource(): Boolean = !retired && owner.isDisplayable && player.ownsSourceSnapshot(expected) &&
            SwingUtilities.getWindowAncestor(player.surface) === owner && canvas.parent === player.surface
        fun assertSource() {
            check(edt { ownsSource() }) { "Carrier fixture lost its exact original native source/owner." }
            val state = player.state.value
            check(state.error == null && !state.loading && state.paused && state.nativePaused == true &&
                state.muted == incoming.muted && abs(state.volume-incoming.volume)<0.1 && abs(state.speed-incoming.speed)<0.01 &&
                abs(state.positionSeconds-incoming.positionSeconds)<0.2) { "Carrier fixture changed playback intent or its paused clock." }
        }
        fun pump() { edt {
            scene?.render(System.nanoTime())?.close()
            val added = owner.ownedWindows.filter { it !in initialChildren }
            createdChildren.addAll(added)
            check(added.size <= 1) { "Foreign owned window appeared during carrier creation." }
        } }
        fun await(label: String, condition: () -> Boolean) {
            val end = minOf(deadline, System.nanoTime()+6_000_000_000L)
            do {
                assertSource(); pump()
                check(!edt { rejected }) { "Production decorative carrier rejected its transparent native peer: $label" }
                if (condition()) return
                Thread.sleep(30)
            } while (System.nanoTime()<end)
            error("Timed out waiting for carrier $label")
        }
        fun bounds(): Rectangle = edt {
            check(ownsSource() && owner.isShowing && owner.extendedState and Frame.ICONIFIED == 0 && canvas.isShowing)
            val rectangle = Rectangle(canvas.locationOnScreen, canvas.size)
            check(rectangle.width>120 && rectangle.height>120 && owner.bounds.contains(rectangle))
            check(canvas.graphicsConfiguration.bounds.contains(rectangle)) { "Owned Canvas is outside its physical screen." }
            rectangle
        }
        fun capture(): BufferedImage {
            assertSource()
            val rectangle = bounds()
            val image = robot.createScreenCapture(rectangle)
            check(bounds() == rectangle) { "Owned Canvas moved during passive screen capture." }
            return image
        }
        fun save(name: String, image: BufferedImage) {
            val target = outputDirectory.toPath().resolve(name)
            Files.newOutputStream(target, CREATE_NEW, WRITE).use { check(ImageIO.write(image, "png", it)) }
        }
        fun facts(stage: String): JsonObject = edt {
            val child = popup
            buildJsonObject {
                put("stage",stage); put("sourceVersion",expected.sourceVersion); put("sameFullSource",player.ownsSourceSnapshot(expected))
                put("nativePaused",player.state.value.nativePaused==true); put("positionSeconds",player.state.value.positionSeconds)
                put("ownerIconified",owner.extendedState and Frame.ICONIFIED != 0)
                put("available",available); put("rejected",rejected)
                put("popupShowing",child?.isShowing==true); put("popupDisplayable",child?.isDisplayable==true)
                put("popupTransparent",child?.isTransparent==true)
                fun geometry(window: java.awt.Component): String = "${window.x},${window.y},${window.width},${window.height}"
                put("ownerBounds",geometry(owner)); put("canvasBoundsInParent",geometry(canvas))
                child?.let { put("popupBounds",geometry(it)) }
                put("popupIsForeground",child?.takeIf { it.isDisplayable }?.let { user32.GetForegroundWindow()==Native.getWindowPointer(it) }==true)
                child?.takeIf { it.isDisplayable }?.let {
                    val style=user32.GetWindowLongW(Native.getWindowPointer(it),-20)
                    put("extendedStyle", "0x"+style.toUInt().toString(16))
                    put("layeredTransparentNoActivate",DesktopDecorativeWindowStylePolicy.acknowledged(style))
                }
            }
        }
        fun recordStage(stage: String) {
            val value=facts(stage)
            stages+=value
            fun flag(key:String)=value[key]?.jsonPrimitive?.booleanOrNull
            check(flag("sameFullSource")==true && flag("nativePaused")==true && flag("rejected")==false &&
                flag("popupDisplayable")==true && flag("popupTransparent")==true &&
                flag("popupIsForeground")==false && flag("layeredTransparentNoActivate")==true) {
                "Carrier $stage lost its transparent/non-activating owned native peer: $value"
            }
            val hidden=stage=="hidden"
            check(flag("ownerIconified")==hidden && flag("available")==!hidden && flag("popupShowing")==!hidden) {
                "Carrier $stage did not preserve its expected native visibility: $value"
            }
        }
        try {
            val baseline = capture()
            // Exactly the established physical two-color oracle, not MPV's
            // decoded screenshot, a CPU ImageComposeScene raster or metadata.
            PlayerSelfTest.checkRenderedVideo(baseline)
            save("native-feedback-carrier-before.png",baseline)
            val focusBefore = edt {
                val focus=KeyboardFocusManager.getCurrentKeyboardFocusManager()
                check(focus.activeWindow===owner && focus.focusedWindow===owner) { "Carrier smoke lacks an owned active baseline." }
                focus.focusOwner
            }
            edt {
                canvas.addMouseListener(mouse)
                val transform=canvas.graphicsConfiguration.defaultTransform
                val size=IntSize(canvas.width,canvas.height)
                val rasterWidth=ceil(size.width*transform.scaleX).toInt()
                val rasterHeight=ceil(size.height*transform.scaleY).toInt()
                check(rasterWidth in 1..4096 && rasterHeight in 1..4096 &&
                    rasterWidth.toLong()*rasterHeight <= 16_777_216L) { "Carrier composition exceeds its CPU raster budget." }
                // These ids label carrier-only geometry. They never enter an
                // engagement VM, authorize IO, or claim a protocol completion.
                val subject=VideoSubjectSnapshot("BVnative-carrier-only",1,1,1,"","",60_000,1)
                val source=DesktopOriginalVideoAcceptedPublication(PlaybackRequest.create(subject.bvid,aid=1,cid=1),expected)
                scene=ImageComposeScene(rasterWidth,rasterHeight,Density(transform.scaleX.toFloat())) {
                    CompositionLocalProvider(LocalAwtWindow provides owner) {
                        DesktopDecorativeVideoFeedbackPopup(size,player.surface,source,subject,::ownsSource,
                            onWindowAvailability={ identity,ready ->
                                if(windowIdentity==null) windowIdentity=identity else check(windowIdentity===identity)
                                available=ready
                            },onWindowRejected={ rejected=true }) {
                            // No clickable/indication/pointerInput: all input
                            // must pass through the actual Skiko child window.
                            ComposeCanvas(Modifier.fillMaxSize()) {
                                // DrawScope dimensions are actual Skiko pixels,
                                // not the anchor's AWT logical dimensions.
                                val rasterSize = this.size
                                drawRect(Color.Magenta,Offset(rasterSize.width*0.25f,rasterSize.height*0.20f),Size(rasterSize.width*0.15f,rasterSize.height*0.20f))
                                drawRect(Color.Cyan.copy(alpha=0.5f),Offset(rasterSize.width*0.55f,rasterSize.height*0.20f),Size(rasterSize.width*0.15f,rasterSize.height*0.20f))
                            }
                        }
                    }
                }
            }
            await("actual production peer availability") {
                edt {
                    val added=owner.ownedWindows.filter { it !in initialChildren }
                    check(added.size<=1) { "Foreign owned window appeared during carrier creation." }
                    popup=added.singleOrNull() as? ComposeDialog
                    available && popup?.isShowing==true
                }
            }
            edt {
                val child=checkNotNull(popup)
                check(child.owner===owner && child.isTransparent && !child.focusableWindowState && !child.isAutoRequestFocus)
                check(DesktopDecorativeWindowStylePolicy.acknowledged(user32.GetWindowLongW(Native.getWindowPointer(child),-20)))
                val focus=KeyboardFocusManager.getCurrentKeyboardFocusManager()
                check(focus.activeWindow===owner && focus.focusedWindow===owner && focus.focusOwner===focusBefore)
                check(user32.GetForegroundWindow()!=Native.getWindowPointer(child)) { "Decorative popup activated itself." }
            }
            var visible: BufferedImage?=null
            await("visible transparent color and half-alpha over the same video") {
                capture().let { image -> if(pixelsMatch(baseline,image)) { visible=image; true } else false }
            }
            save("native-feedback-carrier-visible.png",checkNotNull(visible)); recordStage("visible")
            val physical=bounds()
            val point=java.awt.Point(physical.x+(physical.width*0.32).toInt(),physical.y+(physical.height*0.29).toInt())
            edt { check(ownsSource() && canvas.isShowing && physical.contains(point) && owner.isActive) }
            robot.mouseMove(point.x,point.y); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            try { robot.delay(40) } finally { robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }
            await("real OS press and release reach the exact Canvas through Skiko") { edt { presses==1 && releases==1 } }
            check(edt { KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow===owner &&
                user32.GetForegroundWindow()!=Native.getWindowPointer(checkNotNull(popup)) })
            recordStage("mouse-through")
            val originalPeer=popup
            edt { owner.extendedState=initialPlacement or Frame.ICONIFIED }
            await("same native popup hides without disposal") { edt { !available && popup===originalPeer && popup?.isDisplayable==true && popup?.isShowing==false } }
            recordStage("hidden")
            Thread.sleep(250); assertSource()
            edt { owner.extendedState=initialPlacement }
            await("same native popup and source restore") { edt { available && popup===originalPeer && popup?.isShowing==true } }
            var restored: BufferedImage?=null
            await("restored physical decoration over the unchanged frame") {
                capture().let { image -> if(pixelsMatch(baseline,image)) { restored=image; true } else false }
            }
            save("native-feedback-carrier-restored.png",checkNotNull(restored)); recordStage("restored")
        } catch(error: Throwable) {
            failure=error
            runCatching { stages+=facts("failed") }
            runCatching { save("native-feedback-carrier-failed.png",capture()) }
        } finally {
            fun cleanupStep(action: () -> Unit) {
                try { action() } catch(cleanup: Throwable) {
                    cleanupFailed=true
                    val primary=failure
                    if(primary==null) failure=cleanup else if(primary!==cleanup) primary.addSuppressed(cleanup)
                }
            }
            cleanupStep {
                edt {
                    retired=true
                    val closingScene=scene; scene=null
                    closingScene?.close()
                }
            }
            cleanupStep { edt { canvas.removeMouseListener(mouse) } }
            cleanupStep {
                edt {
                    if(owner.isDisplayable && owner.extendedState!=initialPlacement) owner.extendedState=initialPlacement
                }
            }
            cleanupStep { edt { } }
            // A production disposal failure must remain a failure, but still
            // release the exact already captured peer held by this fixture.
            // Never dispose an unknown/foreign child from the ownership diff.
            var capturedPeerNeedsDisposal=false
            cleanupStep { capturedPeerNeedsDisposal=edt { popup?.isDisplayable==true } }
            if(capturedPeerNeedsDisposal) {
                cleanupStep { error("Production carrier did not dispose its captured native peer.") }
                cleanupStep { edt { popup?.takeIf { it.owner===owner }?.dispose() } }
            }
            cleanupStep {
                // Inspect this exact owner's newly created children even if
                // style rejection happened before the availability callback.
                // Do not close any foreign window or enumerate global peers.
                check(edt {
                    createdChildren.addAll(owner.ownedWindows.filter { it !in initialChildren })
                    popup?.isDisplayable!=true && createdChildren.none { it.isDisplayable } && mouse !in canvas.mouseListeners
                }) { "Carrier smoke leaked its owned peer/listener." }
                windowDisposed=true
            }
            cleanupStep {
                // This smoke never sends playback commands. Preserve the exact
                // incoming source, paused state, mute, volume, speed and clock.
                check(player.ownsSourceSnapshot(expected))
                val state=player.state.value
                check(state.error==null && !state.loading && state.paused==incoming.paused && state.nativePaused==incoming.nativePaused && state.muted==incoming.muted &&
                    abs(state.volume-incoming.volume)<0.1 && abs(state.speed-incoming.speed)<0.01 &&
                    abs(state.positionSeconds-incoming.positionSeconds)<0.2)
                playbackPreserved=true
            }
            closed=!cleanupFailed && windowDisposed && playbackPreserved
        }
        val receipt=buildJsonObject {
            put("passed",failure==null && closed); put("diagnosticOnly",true); put("carrierOnly",true)
            put("remoteActionConfirmed",false); put("engagementVmStateWritten",false); put("playerConstructed",false)
            put("syntheticCarrierLayoutMetadata",true); put("cleanupGraceful",closed); put("carrierWindowDisposed",windowDisposed)
            put("borrowedNativePlayerClosed",false)
            put("sameIncomingSourceAndPlaybackIntent",playbackPreserved); put("actualCanvasPresses",presses); put("actualCanvasReleases",releases)
            put("stages",JsonArray(stages)); failure?.let { put("failureType",it.javaClass.simpleName); put("failure",it.message.orEmpty().take(500)) }
        }
        runCatching { Files.writeString(outputDirectory.toPath().resolve("native-feedback-carrier.json"),receipt.toString()+"\n",CREATE_NEW,WRITE) }
            .onFailure { error -> val primary=failure; if(primary==null) failure=error else if(primary!==error) primary.addSuppressed(error) }
        failure?.let { throw it }
    }

    /** Derivable physical alpha/color checks, independent of rendering code. */
    internal fun pixelsMatch(before: BufferedImage, after: BufferedImage): Boolean {
        if(before.width!=after.width || before.height!=after.height) return false
        fun sample(image: BufferedImage,x:Double,y:Double): java.awt.Color =
            java.awt.Color(image.getRGB((image.width*x).toInt(),(image.height*y).toInt()))
        val opaque=sample(after,0.32,0.29)
        if(opaque.red<190 || opaque.blue<190 || opaque.green>65) return false
        for((x,y) in listOf(0.10 to 0.15,0.10 to 0.50,0.82 to 0.50,0.50 to 0.75)) {
            val a=sample(before,x,y); val b=sample(after,x,y)
            if(abs(a.red-b.red)>8 || abs(a.green-b.green)>8 || abs(a.blue-b.blue)>8) return false
        }
        val a=sample(before,0.62,0.29); val b=sample(after,0.62,0.29)
        val original=listOf(a.red,a.green,a.blue); val actual=listOf(b.red,b.green,b.blue); val cyan=listOf(0,255,255)
        val fractions=original.indices.filter { abs(cyan[it]-original[it])>=80 }.map {
            (actual[it]-original[it]).toDouble()/(cyan[it]-original[it])
        }
        return fractions.isNotEmpty() && fractions.all { it in 0.30..0.70 }
    }
}
