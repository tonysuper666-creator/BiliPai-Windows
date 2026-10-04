package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.parser.bas.BasDanmaku
import com.android.purebilibili.danmaku.parser.bas.BasScriptParser
import com.android.purebilibili.danmaku.parser.bas.BasTarget
import java.awt.image.BufferedImage
import kotlin.test.*

/** Actual Skia CPU pixels and original retained painter; only tiny private rasters, no HWND/GPU/video. */
class DesktopBasRendererTest {
    private fun item(source:String)=BasDanmaku(1,0,source,BasScriptParser.parse(source))
    private fun paint(renderer:DesktopBasRenderer,width:Int=180,height:Int=100):BufferedImage {
        val image=BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB_PRE)
        val graphics=image.createGraphics()
        try {assertTrue(renderer.paint(graphics),renderer.status.reason)}finally{graphics.dispose()}
        return image
    }
    private val button="""def button b {text="GO" x=10 y=40 fillColor=0x0000ff target=seek {time=2s}}"""

    @Test fun originalTextButtonAndSvgActuallyPaintDistinctPixels() {
        DesktopBasRenderer().use { renderer ->
            renderer.configure(listOf(item("""
                def path p {d="M0 0 L40 0 L40 20 L0 20 Z" x=10 y=10 fillColor=0xff0000}
                def text t {content="BAS 中文" x=60 y=10 fontSize=18 color=0x00ff00 textShadow=0}
                $button
            """.trimIndent())),180,100)
            renderer.frame(1000)
            val image=paint(renderer)
            assertEquals(0xffff0000.toInt(),image.getRGB(20,20))
            assertEquals(0xff0000ff.toInt(),image.getRGB(15,45))
            assertTrue((60 until 175).any {x->(10 until 40).any {y-> val pixel=image.getRGB(x,y);(pixel ushr 24)>0 && ((pixel ushr 8) and 255)>100 && (pixel and 255)<30 }})
            assertEquals(BasTarget.Seek(2000),renderer.hit(15f,45f))
            image.flush()
        }
    }
    @Test fun opacityIsAppliedExactlyOnceAndSnapshotDoesNotAttestTargetPaint() {
        DesktopBasRenderer().use { renderer ->
            renderer.configure(listOf(item("""def path p {d="M0 0 L40 0 L40 20 L0 20 Z" fillColor=0xff0000}""")),180,100,opacity=0.5f)
            renderer.frame(0)
            val snapshot=assertNotNull(renderer.rasterSnapshot())
            assertEquals(128,snapshot.getRGB(10,10) ushr 24)
            assertEquals(-1L,renderer.paintedRevision);snapshot.flush()
        }
    }
    @Test fun originalParentTransformAndInheritedAlphaReachActualTextPixels() {
        DesktopBasRenderer().use {renderer->
            renderer.configure(listOf(item("""
                def text parent {content="" x=40 y=10 width=1 height=1 alpha=.5 rotateZ=90 textShadow=0}
                def text child {content="BAS" parent="parent" x=10 y=0 fontSize=18 color=0xff0000 alpha=.5 textShadow=0}
            """.trimIndent())),180,100,opacity=.5f)
            renderer.frame(0);val image=paint(renderer)
            val painted=ArrayList<Pair<Int,Int>>()
            for(y in 0 until image.height)for(x in 0 until image.width) {
                val pixel=image.getRGB(x,y);if((pixel ushr 24)>0){
                    assertTrue((pixel ushr 24)<=32,"Parent, child and display alpha must each apply once")
                    painted.add(x to y)
                }
            }
            assertTrue(painted.isNotEmpty())
            assertTrue(painted.all {(x,y)->x in 0..40 && y in 20..90},"Parent rotation must transform the real child glyphs")
            image.flush()
        }
    }
    @Test fun originalRotateXYChangesRealRasterWithoutAnAffineOnlySubstitute() {
        fun raster(rotation:String):BufferedImage {
            val renderer=DesktopBasRenderer()
            return try {
                renderer.configure(listOf(item("""def text t {content="BAS 中文" x=50 y=20 fontSize=24 color=0xff0000 textShadow=0 $rotation}""")),180,100)
                renderer.frame(0);paint(renderer)
            }finally{renderer.close()}
        }
        val plain=raster("");val perspective=raster("rotateX=25 rotateY=35")
        try {
            var differing=0;var painted=0
            for(y in 0 until plain.height)for(x in 0 until plain.width) {
                if(plain.getRGB(x,y)!=perspective.getRGB(x,y))differing++
                if((perspective.getRGB(x,y) ushr 24)>0)painted++
            }
            assertTrue(painted>0);assertTrue(differing>0,"Original rotateX/rotateY projection must change actual pixels")
        }finally{plain.flush();perspective.flush()}
    }
    @Test fun originalSvgViewBoxMeetAndClipArePreserved() {
        DesktopBasRenderer().use {renderer->
            renderer.configure(listOf(item("""def path p {d="M0 0 L100 0 L100 100 L0 100 Z" x=10 y=10 width=80 height=40 viewBox="0 0 100 100" fillColor=0xff0000}""")),180,100)
            renderer.frame(0);val image=paint(renderer)
            assertEquals(0,image.getRGB(20,20) ushr 24)
            assertEquals(0xffff0000.toInt(),image.getRGB(40,20))
            assertEquals(0,image.getRGB(80,20) ushr 24);image.flush()
        }
    }
    @Test fun authoredPathWithoutViewBoxIsNotResizedOrClippedByWidthHeight() {
        DesktopBasRenderer().use {renderer->
            renderer.configure(listOf(item("""def path p {d="M0 0 L40 0 L40 20 L0 20 Z" width=5 height=5 fillColor=0xff0000}""")),180,100)
            renderer.frame(0);val image=paint(renderer)
            assertEquals(0xffff0000.toInt(),image.getRGB(30,10));image.flush()
        }
    }
    @Test fun reverseDrawOrderChoosesTopmostButtonAndSeekDoesNotReconstructTheScene() {
        DesktopBasRenderer().use {renderer->
            renderer.configure(listOf(item("""
                def button low {text="LOW" x=10 y=40 target=seek {time=1s}}
                def button high {text="HIGH" x=10 y=40 zIndex=2 target=seek {time=2s}}
            """.trimIndent())),180,100)
            renderer.frame(1000);paint(renderer).flush()
            val first=assertNotNull(renderer.hitReceipt(15f,45f));assertEquals(BasTarget.Seek(2000),first.target)
            renderer.frame(3000);paint(renderer).flush()
            assertSame(first.targetIdentity,assertNotNull(renderer.hitReceipt(15f,45f)).targetIdentity)
            renderer.frame(5000);paint(renderer).flush();assertNull(renderer.hit(15f,45f))
            renderer.frame(1000);paint(renderer).flush();assertEquals(BasTarget.Seek(2000),renderer.hit(15f,45f))
        }
    }
    @Test fun playingButtonAcceptsNewPaintedFrameButDragOrGeometryChangeCancels() {
        DesktopBasRenderer(4f).use {renderer->
            val items=listOf(item(button));renderer.configure(items,180,100);renderer.frame(0)
            assertNull(renderer.pressReceipt(15f,45f)) // nothing has been painted to target Graphics yet.
            paint(renderer).flush();val down=assertNotNull(renderer.pressReceipt(15f,45f))
            renderer.frame(1000);paint(renderer).flush()
            val up=assertNotNull(renderer.releaseReceipt(15f,45f))
            assertTrue(up.frameRevision>down.frameRevision);assertSame(down.targetIdentity,up.targetIdentity)
            assertNotNull(renderer.pressReceipt(15f,45f));renderer.move(30f,45f);assertNull(renderer.release(15f,45f))
            assertNotNull(renderer.pressReceipt(15f,45f));renderer.configure(items,200,100);renderer.frame(1000);paint(renderer,200,100).flush()
            assertNull(renderer.release(15f,45f))
        }
    }
    @Test fun percentResizeForwardAndBackwardSeekUseOriginalAbsoluteTimeline() {
        DesktopBasRenderer().use {renderer->
            val items=listOf(item("""def button b {text="GO" x=50% y=40 target=seek {time=2s}}"""))
            renderer.configure(items,180,100);renderer.frame(1000);paint(renderer).flush()
            assertEquals(BasTarget.Seek(2000),renderer.hit(95f,45f))
            renderer.configure(items,220,100);renderer.frame(1000);paint(renderer,220,100).flush()
            assertNull(renderer.hit(95f,45f));assertEquals(BasTarget.Seek(2000),renderer.hit(115f,45f))
            renderer.frame(5000);paint(renderer,220,100).flush();assertNull(renderer.hit(115f,45f))
            renderer.frame(1000);paint(renderer,220,100).flush();assertEquals(BasTarget.Seek(2000),renderer.hit(115f,45f))
        }
    }
    @Test fun invalidRasterAdmissionClearsOnlyThisLeafAndExplicitReconfigureCanRecover() {
        DesktopBasRenderer().use {renderer->
            val items=listOf(item(button));renderer.configure(items,180,100);renderer.frame(0);paint(renderer).flush()
            renderer.configure(items,Int.MAX_VALUE,100);assertFalse(renderer.status.accepted)
            assertNull(renderer.hit(15f,45f));assertNull(renderer.rasterSnapshot())
            renderer.configure(items,180,100);renderer.frame(0);paint(renderer).flush()
            assertEquals(BasTarget.Seek(2000),renderer.hit(15f,45f))
            renderer.clear();assertNull(renderer.hit(15f,45f));assertNull(renderer.rasterSnapshot())
        }
    }
}
