package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.share.*
import com.android.purebilibili.data.repository.DesktopOriginalVideoShareMessages
import com.android.purebilibili.core.network.MessageApi
import com.android.purebilibili.data.model.response.*
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.*
import java.lang.reflect.Proxy
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.nio.file.*
import java.util.concurrent.atomic.AtomicBoolean

private var checks=0
private fun expect(value:Boolean,label:String) {check(value) {label};checks++}
private class FakeCall(private val req:Request,private val response:Response):Call {
    var cancelled=false
    override fun request()=req
    override fun execute()=response
    override fun enqueue(callback:Callback) {callback.onResponse(this,response)}
    override fun cancel(){cancelled=true}
    override fun isExecuted()=true
    override fun isCanceled()=cancelled
    override fun clone():Call=FakeCall(req,response)
    override fun timeout()=Timeout.NONE
    override fun <T:Any> tag(type:kotlin.reflect.KClass<T>):T?=null
    override fun <T> tag(type:Class<out T>):T?=null
    override fun <T:Any> tag(type:kotlin.reflect.KClass<T>,computeIfAbsent:()->T):T=computeIfAbsent()
    override fun <T:Any> tag(type:Class<T>,computeIfAbsent:()->T):T=computeIfAbsent()
}
fun main(args:Array<String>)=runBlocking {
    val product=java.nio.file.Path.of(System.getProperty("actualProductJar")).toUri().toURL()
    for(type in listOf(DesktopVideoShareFiles::class.java, DesktopVideoShareImageTransport::class.java,DesktopOriginalVideoShareMessages::class.java)) {
        check(type.protectionDomain.codeSource.location==product)
        println("ORIGIN ${type.name} ${type.protectionDomain.codeSource.location}")
    }

    val root=java.nio.file.Path.of(args[0]);Files.createDirectories(root)
    val payload=buildVideoSharePayload("测试标题","BV123","https://i0.hdslb.com/test.png","UP","123")
    val image=BufferedImage(160,90,BufferedImage.TYPE_INT_RGB)
    val g=image.createGraphics();g.color=Color.GREEN;g.fillRect(0,0,160,90);g.dispose()
    val card=renderVideoShareCardBitmap(payload,image)
    expect(card.width==1080 && card.height==866,"original one-title meta card dimensions")
    expect(card.getRGB(540,500)==Color.GREEN.rgb,"full original cover stretch in rounded clip")
    expect(card.getRGB(56,266)==Color.WHITE.rgb,"rounded cover corner remains white")
    val qr=card.getSubimage(888,56,136,136)
    val rgb=IntArray(qr.width*qr.height);qr.getRGB(0,0,qr.width,qr.height,rgb,0,qr.width)
    val decoded=MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(qr.width,qr.height,rgb))))
    expect(decoded.text==payload.url,"actual QR decodes original URL")
    val lines=buildShareCardTitleLayout("长标题".repeat(200),Font("Dialog",Font.BOLD,52),804)
    expect(lines.size==3 && lines.last().endsWith("…"),"original max three END lines")
    expect(buildShareCardTitleLayout("  ",Font("Dialog",Font.BOLD,52),804).size==1,"original blank title placeholder")
    expect(resolveVideoShareCardMetaLine(payload)=="UP主：UP  ·  播放：123","original metadata line")
    expect(resolveVideoShareCoverMimeType("https://a/x.PNG?q=1")=="image/png","original mime query stripping")
    expect(resolveVideoShareCardFileName(payload).startsWith("BiliPai_share_card_"),"original sanitized card name")
    val encoded=java.io.ByteArrayOutputStream().also {javax.imageio.ImageIO.write(image,"png",it)}.toByteArray()
    val webp=org.jetbrains.skia.Image.makeFromEncoded(encoded).use {native ->native.encodeToData(org.jetbrains.skia.EncodedImageFormat.WEBP)!!.use {it.bytes}}
    val webpFiles=DesktopVideoShareFiles(root.resolve("webp-cache"),{webp},{true}) {action->action();true}
    val webpImage=webpFiles.bitmap("fixture-webp")!!
    expect(webpImage.width==160 && webpImage.height==90,"existing native Skiko decodes WebP cover")
    expect(Color(webpImage.getRGB(80,45)).green>240,"WebP decoded cover content")
    webpImage.flush();image.flush();card.flush()

    val owned=AtomicBoolean(true)
    val files=DesktopVideoShareFiles(root.resolve("cache"),{byteArrayOf(1,2,3)},owned::get) { action ->if(owned.get()){action();true}else false}
    val file=files.publish("BiliPai_share_test.jpg","image/jpeg") {Files.write(it,byteArrayOf(1,2,3));Unit}
    expect(Files.readAllBytes(file.path).contentEquals(byteArrayOf(1,2,3)),"complete published bytes")
    val target=root.resolve("saved.jpg");files.save(file,target)
    expect(Files.readAllBytes(target).contentEquals(byteArrayOf(1,2,3)),"actual Windows local save")
    val existing=runCatching {files.save(file,target)}.exceptionOrNull()
    expect(existing is FileAlreadyExistsException && Files.readAllBytes(target).contentEquals(byteArrayOf(1,2,3)),"no clobber existing target")
    files.mayExpose(file);files.retire(file,false)
    expect(Files.exists(file.path),"unknown recipient supply retained after native retirement")
    owned.set(false)
    expect(runCatching {files.bytes("fixture")}.exceptionOrNull() is CancellationException,"retired owner rejected")
    owned.set(true);files.clearExplicit()
    expect(!Files.exists(file.path),"explicit clear after native retirement removes retained copy")
    val cancelled=launch {
        runCatching {files.publish("BiliPai_share_cancel.jpg","image/jpeg") {Files.write(it,byteArrayOf(1));currentCoroutineContextForProof!!.cancel();Unit}}
    }
    // Cancellation test uses an explicit fixture Job, never a production default.
    currentCoroutineContextForProof=cancelled;cancelled.join();currentCoroutineContextForProof=null
    expect(Files.list(root.resolve("cache/shared_images")).use {it.count()}==0L,"cancelled write leaves no temp/final")

    var code=0
    var sentArgs:Array<out Any?>?=null
    val api=Proxy.newProxyInstance(MessageApi::class.java.classLoader,arrayOf(MessageApi::class.java)) {_,method,values ->
        check(method.name=="sendMsg");sentArgs=values;SendMessageResponse(code=code,data=if(code==0)SendMessageData(msg_key=99)else null)
    } as MessageApi
    val messages=DesktopOriginalVideoShareMessages(api,{"fixture-csrf"},{100L},{"fixture-device"})
    expect(messages.sendTextMessage(200,"fixture-text").getOrThrow().msg_key==99L,"original text protocol success")
    expect(sentArgs!!.contains(100L) && sentArgs!!.contains(200L) && sentArgs!!.count {it=="fixture-csrf"}==2,"sender receiver and both CSRF fields")
    code=21047
    expect(messages.sendTextMessage(200,"fixture-text").exceptionOrNull()?.message=="对方未关注你，最多发送1条消息","original failure code mapping")

    var closed=false
    var observed:Request?=null
    val body=object:ResponseBody() {
        private val src=object:ForwardingSource(Buffer().writeUtf8("test-bytes")) {override fun close(){closed=true;super.close()}}.buffer()
        override fun contentType():MediaType?=null
        override fun contentLength()=10L
        override fun source()=src
    }
    val factory=Call.Factory {req ->observed=req;FakeCall(req,Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build())}
    val raw=DesktopVideoShareImageTransport.download(factory,"https://i0.hdslb.com/test.png") {true}
    expect(raw.toString(Charsets.UTF_8)=="test-bytes" && closed,"same factory download closes body")
    expect(observed!!.header("Cookie")==null && observed!!.header("Referer")=="https://www.bilibili.com/","anonymous cover request and original Referer")
    expect(runCatching {DesktopVideoShareImageTransport.download(factory,"https://example.org/a.png") {true}}.isFailure,"untrusted image host rejected")
    println("PASS 4 groups / $checks assertions; actual40/97 + new-only prepared jar; no HWND/network/account")
}
private var currentCoroutineContextForProof:Job?=null
