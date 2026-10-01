package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.engine.DanmakuMaskFrame
import com.bilipai.desktop.player.PlayerVideoOutputState
import com.bilipai.desktop.player.PlayerVideoViewport
import java.awt.Color
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path

private var assertions=0
private fun verify(ok:Boolean,label:String){check(ok){label};assertions++}
private fun near(actual:Double,expected:Double)=kotlin.math.abs(actual-expected)<0.00001
private fun paint(frame:DanmakuMaskFrame,viewport:PlayerVideoViewport?,width:Int,height:Int):BufferedImage {
    val image=BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB)
    image.createGraphics().usePaint { root ->
        root.color=Color.RED;root.fillRect(0,0,width,height)
        (root.create() as java.awt.Graphics2D).usePaint { standard ->
            applyDesktopWebMaskClip(standard,width,height,frame,viewport)
            standard.color=Color.BLUE;standard.fillRect(0,0,width,height)
        }
    }
    return image
}
private inline fun <T> java.awt.Graphics2D.usePaint(block:(java.awt.Graphics2D)->T):T=try{block(this)}finally{dispose()}
fun main(args:Array<String>){
    val path=DesktopWebMaskPath.createPathFromPathData("M25 25 H75 V75 H25 Z")!!
    val frame=DanmakuMaskFrame(0,1000,path,100,100)
    // Recorded-OSD-style fixture inputs. These are explicit observations, never a 16:9/window-fit inference.
    val observed=PlayerVideoViewport(1000,800,100,200,800,400)
    val transform=observed.sourceToPhysicalTransform(2000,1600,100,100)
    val center=transform.transform(Point2D.Double(50.0,50.0),null)
    verify(near(center.x,1000.0)&&near(center.y,800.0),"actual margins + 2x physical scaling")
    val face=path.transformedArea(transform).bounds2D
    verify(near(face.x,600.0)&&near(face.y,600.0)&&near(face.width,800.0)&&near(face.height,400.0),"source viewBox maps inside actual OSD video rectangle")
    val image=paint(frame,observed,2000,1600)
    verify(image.getRGB(1000,800)==Color.RED.rgb,"face preserves video pixels")
    verify(image.getRGB(500,800)==Color.BLUE.rgb,"ordinary pixels outside face continue")
    verify(image.getRGB(1000,200)==Color.BLUE.rgb,"observed top margin is never guessed as face")
    verify(image.getRGB(599,800)==Color.BLUE.rgb&&image.getRGB(600,800)==Color.RED.rgb,"left physical boundary")
    verify(image.getRGB(1399,800)==Color.RED.rgb&&image.getRGB(1400,800)==Color.BLUE.rgb,"right physical boundary")
    image.createGraphics().usePaint { it.color=Color.GREEN;it.fillRect(999,799,3,3) }
    verify(image.getRGB(1000,800)==Color.GREEN.rgb,"later UI/advanced layer is not clipped")

    val fractional=observed.sourceToPhysicalTransform(1250,1200,100,100)
    val fractionalFace=path.transformedArea(fractional).bounds2D
    verify(near(fractionalFace.x,375.0)&&near(fractionalFace.y,450.0),"asymmetric/fractional physical scale retains actual margins")
    verify(near(fractionalFace.width,500.0)&&near(fractionalFace.height,300.0),"independent actual physical axes")
    val fractionalImage=paint(frame,observed,1250,1200)
    verify(fractionalImage.getRGB(625,600)==Color.RED.rgb&&fractionalImage.getRGB(374,600)==Color.BLUE.rgb,"fractional physical compositor pixels")

    val cropped=PlayerVideoViewport(300,200,-50,-20,400,240)
    val croppedFace=path.transformedArea(cropped.sourceToPhysicalTransform(600,400,100,100)).bounds2D
    verify(near(croppedFace.x,100.0)&&near(croppedFace.y,80.0)&&near(croppedFace.width,400.0)&&near(croppedFace.height,240.0),"legitimate negative crop/pan margins remain observed")
    val cropImage=paint(frame,cropped,600,400)
    verify(cropImage.getRGB(300,200)==Color.RED.rgb&&cropImage.getRGB(99,200)==Color.BLUE.rgb,"cropped physical face boundary")
    val full=DesktopWebMaskPath.createPathFromPathData("M0 0 H100 V100 H0 Z")!!
    val fullFrame=DanmakuMaskFrame(0,1000,full,100,100)
    val entireCrop=paint(fullFrame,cropped,600,400)
    verify(entireCrop.getRGB(0,0)==Color.RED.rgb&&entireCrop.getRGB(599,399)==Color.RED.rgb,"negative bounds are clipped by existing physical canvas")

    val missing=paint(frame,null,2000,1600)
    verify(missing.getRGB(1000,800)==Color.BLUE.rgb,"unobserved native rectangle skips optional mask, no guessed fit")
    val output=PlayerVideoOutputState(sourceVersion=42,displayWidth=800,displayHeight=400,viewport=observed)
    fun accepted(version:Long?,owned:Boolean)=output.takeIf {it.sourceVersion==version&&version?.let {owned}==true}?.viewport
    verify(accepted(42,true)===observed,"same native source output admitted")
    verify(accepted(41,true)==null&&accepted(42,false)==null&&accepted(null,true)==null,"source version/retirement/missing pool reject observed bounds")
    verify(output.copy(sourceVersion=43,displayWidth=0,displayHeight=0,viewport=null).viewport==null,"existing load/stop reset clears bounds")
    verify(runCatching{PlayerVideoViewport(0,200,0,0,100,100)}.isFailure,"invalid observations cannot form a rectangle")
    verify(runCatching{observed.sourceToPhysicalTransform(0,1600,100,100)}.isFailure,"invalid physical dimensions rejected")
    verify(runCatching{observed.sourceToPhysicalTransform(2000,1600,0,100)}.isFailure,"invalid source viewBox rejected")
    val rows=listOf("status=PASS","groups=4","assertions=$assertions","scope=actual Skia/Java2D + exact production ABI; explicit observed OSD fixture inputs; no HWND/native MPV/Root window acceptance")
    Files.writeString(Path.of(args[0],"proof-result.txt"),rows.joinToString("\n",postfix="\n"))
    println(rows.joinToString("\n"))
}
