package com.bilipai.desktop.ui.assetsintegrationproof.qr

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.ui.components.*
import com.bilipai.desktop.ui.*
import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.awt.image.BufferedImage
import java.nio.file.*
import javax.imageio.ImageIO

private class FixtureLuminance(private val image:BufferedImage):LuminanceSource(image.width,image.height) {
    override fun getRow(y:Int,row:ByteArray?)=(row?:ByteArray(width)).also { out ->
        for(x in 0 until width)out[x]=(image.getRGB(x,y) and 0xff).toByte()
    }
    override fun getMatrix()=ByteArray(width*height).also { out ->for(y in 0 until height)getRow(y,null).copyInto(out,y*width) }
}
private data class RouteCase(val name:String,val oid:Long,val rpid:Long,val root:Long=0L,val longMessage:Boolean=false,val dated:Boolean=true)

fun main(args:Array<String>):Unit=runBlocking {
    val mode="actual-main";val output=Path.of(args[0]);Files.createDirectories(output)
    val main=Path.of(args[1]).toAbsolutePath().normalize();val required=main
    fun location(type:Class<*>)=Path.of(type.protectionDomain.codeSource.location.toURI()).toAbsolutePath().normalize()
    fun isMain(name:String)=location(Class.forName(name))==main
    var checks=0
    fun prove(value:Boolean,message:String){check(value){message};checks++}
    prove(location(DesktopCommentCanvas::class.java)==required,"actual Main Canvas codeSource")
    prove(location(Class.forName("com.android.purebilibili.feature.video.ui.components.DesktopOriginalReplyImageRendererKt"))==required,"actual Main renderer codeSource")
    prove(isMain("com.android.purebilibili.feature.video.ui.components.DesktopOriginalReplyCommentImageSpecKt")&&
        isMain("com.bilipai.desktop.ui.DesktopCommentImageCanvasKt")&&location(DesktopCommentPaint::class.java)==main,
        "real Main spec/writer/bitmap factory/Paint retain actual product classes")
    val routes=listOf(
        RouteCase("short-root",123,77),RouteCase("three-digit-root",123,621),
        RouteCase("long-video",1234567890,1234567890123),
        RouteCase("long-dynamic-ids",9876543210987,987654321098765432),
        RouteCase("max-long-root",Long.MAX_VALUE,Long.MAX_VALUE),
        RouteCase("long-secondary",987654321098765432,876543210987654321,765432109876543210),
        RouteCase("long-secondary-14-lines",Long.MAX_VALUE,Long.MAX_VALUE-1,Long.MAX_VALUE-2,true),
        RouteCase("undated-long-secondary",4567890123456789,5678901234567890,6789012345678901,dated=false),
    )
    var decodeFailures=0;var pristineFailures=0
    val rows=routes.map { route ->
        val item=ReplyItem(oid=route.oid,rpid=route.rpid,root=route.root,
            ctime=if(route.dated)1700000000 else 0,like=8,
            member=ReplyMember(uname="自有合成作者"),
            content=ReplyContent(message=if(route.longMessage)(1..20).joinToString("\n"){"合成原文行 $it 😀"}else"合成评论 😀"))
        val spec=buildReplyCommentImageSpec(item,1700000000000)
        val target=output.resolve(route.name+".png")
        prove(writeDesktopReplyCommentImage(spec,target,{true},replaceExisting=false),"actual writer emits ${route.name}")
        val image=ImageIO.read(target.toFile())
        prove(image.width==1080&&image.height==(if(route.longMessage)1250 else 520),"original geometry preserved ${route.name}")
        val left=1080-56-148;val top=image.height-188+18
        val qr=image.getSubimage(left,top,148,148)
        val decoded=runCatching{QRCodeReader().decode(BinaryBitmap(HybridBinarizer(FixtureLuminance(qr)))).text}
        val matches=decoded.getOrNull()==spec.qrUrl
        if(!matches)decodeFailures++
        val originalMatrix=QRCodeWriter().encode(spec.qrUrl,BarcodeFormat.QR_CODE,148,148)
        val pristine=(0 until 148).all { x ->(0 until 148).all { y ->qr.getRGB(x,y)==if(originalMatrix[x,y])0xff000000.toInt() else 0xffffffff.toInt() } }
        if(!pristine)pristineFailures++
        if(mode=="actual-main") {
            prove(matches,"actual Main saved QR decodes complete original payload ${route.name}")
            prove(pristine,"actual Main all QR/quiet-zone pixels exactly equal original matrix ${route.name}")
        }
        buildJsonObject {
            put("name",route.name);put("oid",route.oid);put("rpid",route.rpid);put("root",route.root)
            put("width",image.width);put("height",image.height);put("qrLeft",left);put("qrTop",top)
            put("expectedFullOriginalPayload",spec.qrUrl);put("decodedPayload",decoded.getOrNull().orEmpty())
            put("qrDecoded",matches);put("originalQrMatrixPixelsExact",pristine)
            put("decodeFailure",decoded.exceptionOrNull()?.javaClass?.simpleName.orEmpty())
            put("authorName",spec.authorName);put("message",spec.message);put("metadataText",spec.metadataText)
            put("footerText",spec.footerText);put("generatedAtText",spec.generatedAtText)
        }
    }
    var graphicsRestored=false
    if(mode=="actual-main") {
        val bitmap=createDesktopCommentBitmap(300,300,BufferedImage.TYPE_INT_ARGB)
        val canvas=DesktopCommentCanvas(bitmap);canvas.drawColor(DesktopCommentColors.WHITE)
        val paint=DesktopCommentPaint(1).apply{color=DesktopCommentColors.BLACK;textSize=26f}
        DesktopCommentCanvas::class.java.getDeclaredMethod("drawFooterTextBeforeQr",String::class.java,
            java.lang.Float.TYPE,java.lang.Float.TYPE,DesktopCommentPaint::class.java,java.lang.Float.TYPE)
            .invoke(canvas,"clipped footer URL",10f,100f,paint,80f)
        canvas.drawText("AFTER",150f,200f,paint);canvas.close()
        graphicsRestored=(150 until 280).any{x->(170 until 220).any{y->bitmap.getRGB(x,y)!=0xffffffff.toInt()}}
        prove(graphicsRestored,"dedicated clip does not constrain subsequent ordinary drawText")
        ImageIO.write(bitmap,"png",output.resolve("graphics-state-restored.png").toFile())
    }
    prove(Files.list(output).use{paths->paths.noneMatch{it.fileName.toString().startsWith(".bilipai-comment-")}},"actual writer drains PNG scratch")
    Files.writeString(output.resolve("result.json"),buildJsonObject {
        put("passed",true);put("mode",mode);put("assertions",checks);put("routes",JsonArray(rows))
        put("decodeFailures",decodeFailures);put("nonPristineQrCount",pristineFailures)
        put("GraphicsStateRestored",graphicsRestored);put("actualMainWriterSpecPaint",true)
        put("candidateOverridesDeclared",false);put("AndroidActualRender",false)
        put("printedFooterTextCanBeClipped",true);put("fullOriginalQrPayloadPreserved",true)
        put("HTTP",false);put("HWND",false);put("realChooser",false);put("MainCompiledProductIntegration",true);put("MainShellUIExecuted",false);put("productionOverrides",false)
    }.toString())
    println("PASS $mode $checks assertions / ${routes.size} actual original long-ID routes; decodeFailures=$decodeFailures; pristineFailures=$pristineFailures")
}
