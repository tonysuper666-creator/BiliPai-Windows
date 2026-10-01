package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.core.store.*
import com.android.purebilibili.feature.video.ui.components.*
import com.bilipai.desktop.plugins.*
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.awt.image.BufferedImage
import java.nio.file.*
import javax.imageio.ImageIO
import kotlin.test.*

private class PngLuminance(private val image:BufferedImage):LuminanceSource(image.width,image.height) {
    override fun getRow(y:Int,row:ByteArray?):ByteArray=(row?:ByteArray(width)).also { output->
        for(x in 0 until width)output[x]=(image.getRGB(x,y) and 0xff).toByte()
    }
    override fun getMatrix():ByteArray=ByteArray(width*height).also{output->for(y in 0 until height)getRow(y,null).copyInto(output,y*width)}
}
fun main(args:Array<String>):Unit=runBlocking {
    val output=Path.of(args[0]);val root=Files.createTempDirectory("bilipai-reply-image-")
    val store=DesktopPluginStore(root.resolve("prefs"));val context=DesktopPluginContext(store)
    val settings=DesktopOriginalReplySettings
    assertEquals(3,settings.getCommentCollapsedReplyPreviewLimitSync(context))
    assertEquals(3,settings.getCommentCollapsedReplyPreviewLimit(context).first())
    assertFalse(settings.getSubReplyLoadedCountEnabled(context).first())
    settings.setCommentCollapsedReplyPreviewLimit(context,99)
    settings.setSubReplyLoadedCountEnabled(context,true)
    assertEquals(10,settings.getCommentCollapsedReplyPreviewLimitSync(context))
    assertEquals(10,settings.getCommentCollapsedReplyPreviewLimit(context).first())
    val restored=DesktopPluginContext(DesktopPluginStore(root.resolve("prefs")))
    assertEquals(10,settings.getCommentCollapsedReplyPreviewLimitSync(restored))
    assertTrue(settings.getSubReplyLoadedCountEnabled(restored).first())
    assertTrue(SkeletonSettingsStore.breathingEnabled(context).first())
    SkeletonSettingsStore.setBreathingEnabled(context,false)
    assertFalse(SkeletonSettingsStore.breathingEnabled(restored).first())
    val raw=Json{ignoreUnknownKeys=true}.decodeFromString<ReplyItem>("""{"rpid":77,"oid":123,"ctime":1700000000,"like":8,"content":{"message":"  原始评论与 emoji 😀\nsecond line  "},"member":{"uname":"真实字段"}}""")
    val spec=buildReplyCommentImageSpec(raw,1700000000000)
    assertEquals("真实字段",spec.authorName)
    assertEquals("原始评论与 emoji 😀\nsecond line",spec.message)
    // This is the actual upstream route even when the reply belongs to a dynamic.
    assertEquals("https://www.bilibili.com/video/av123?comment_on=1&comment_root_id=77",spec.qrUrl)
    val image=renderDesktopReplyCommentImage(spec.copy(message=(1..20).joinToString("\n"){"Original line $it"}))
    assertEquals(1080,image.width);assertEquals(1250,image.height)
    assertEquals(0xfffafafa.toInt(),image.getRGB(0,0))
    val qr=image.getSubimage(1080-56-148,image.height-188+18,148,148)
    assertEquals(spec.qrUrl,QRCodeReader().decode(BinaryBitmap(HybridBinarizer(PngLuminance(qr)))).text)
    val target=root.resolve("actual-comment.png")
    assertTrue(writeDesktopReplyCommentImage(spec,target){true})
    val actual=ImageIO.read(target.toFile());assertEquals(1080,actual.width)
    val before=Files.readAllBytes(target)
    assertFailsWith<CancellationException>{writeDesktopReplyCommentImage(spec.copy(message="Retired"),target){false}}
    assertContentEquals(before,Files.readAllBytes(target))
    assertTrue(Files.list(root).use{paths->paths.noneMatch{it.fileName.toString().startsWith(".bilipai-comment-")}})
    Files.copy(target,output.resolveSibling("original-reply-comment-export.png"),StandardCopyOption.REPLACE_EXISTING)
    Files.writeString(output,buildJsonObject{
        put("passed",true);put("originalQrDecoded",true);put("originalGeometryLineCap",true)
        put("actualPngWritten",true);put("retiredNeverOverwrites",true);put("actualGlobalStore",true)
        put("WindowsFontRasterizationDiffers",true);put("MainIntegration",false);put("HTTP",false);put("HWND",false)
    }.toString())
}
