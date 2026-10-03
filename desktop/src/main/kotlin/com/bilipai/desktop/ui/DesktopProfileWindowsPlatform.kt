package com.bilipai.desktop.ui

import com.bilipai.desktop.appearance.DesktopTextClipboard
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okio.*
import java.awt.Window
import java.awt.EventQueue
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter

/** Every capability/actor below is the real retained Root; no optional/default effect ports. */
internal class DesktopWindowsProfilePlatform(
    override val stateDirectory:Path,
    override val configuration:StateFlow<DesktopProfileWindowConfiguration>,
    override val supportsRenderEffectBackedHaze:Boolean,
    override val applicationIconModel:Any,
    private val window:Window,
    private val scope:CoroutineScope,
    private val owns:()->Boolean,
    private val commit:((()->Unit)->Boolean),
    private val callFactory:Call.Factory,
    private val files:DesktopProfileOwnedFiles,
    private val assets:DesktopDynamicImageAssets,
    private val clipboard:DesktopTextClipboard,
    private val chrome:DesktopWindowsProfileChrome,
    private val actualFeedback:(String)->Unit,
    private val diagnostic:(Throwable)->Unit,
):DesktopProfilePlatform {
    private fun checkpoint(job:Job?=null) {job?.ensureActive();if(scope.coroutineContext[Job]?.isActive!=true || !owns())throw CancellationException("Profile platform retired")}
    override fun reportWallpaperPreviewFailure(failure:Throwable) {if(owns())diagnostic(failure)}
    override fun acquireSystemBars(control:Boolean,lightStatusBars:Boolean):AutoCloseable {checkpoint();return chrome.acquire(control,lightStatusBars){checkpoint()}}
    override fun feedback(message:String) {EventQueue.invokeLater {if(owns() && scope.coroutineContext[Job]?.isActive==true)commit {actualFeedback(message)}}}
    override fun copyText(label:String,text:String) {
        EventQueue.invokeLater {if(owns() && scope.coroutineContext[Job]?.isActive==true)commit {if(!clipboard.copyText(text))actualFeedback("无法写入系统剪贴板，请稍后重试")}}
    }
    override fun pickMedia(onSelected:(String?)->Unit) = pickVisualFile(false, onSelected)
    override fun pickQrImage(onSelected:(String?)->Unit) = pickVisualFile(true, onSelected)
    private fun pickVisualFile(qr:Boolean,onSelected:(String?)->Unit) {
        checkpoint()
        scope.launch(Dispatchers.Swing) {
            checkpoint(currentCoroutineContext()[Job]);val dialog=AtomicReference<JDialog?>()
            val chooser=object:JFileChooser() {
                override fun createDialog(parent:java.awt.Component?):JDialog=super.createDialog(parent).also(dialog::set)
            }.apply {dialogTitle=if(qr) "选择登录二维码图片" else "选择壁纸图片 / 视频";isMultiSelectionEnabled=false;fileSelectionMode=JFileChooser.FILES_ONLY
                fileFilter=if(qr) FileNameExtensionFilter("二维码图片","jpg","jpeg","png","gif","webp","bmp") else FileNameExtensionFilter("图片 / 视频","jpg","jpeg","png","gif","webp","bmp","mp4","m4v","webm","mkv","mov","3gp")}
            val pickerJob=currentCoroutineContext()[Job]
            val watcher=scope.launch(Dispatchers.Default) {
                try {while(true){if(pickerJob?.isActive!=true || !owns())break;delay(50)}}
                finally {EventQueue.invokeLater {dialog.getAndSet(null)?.dispose()}}
            }
            try {
                val approved=chooser.showOpenDialog(window)==JFileChooser.APPROVE_OPTION
                checkpoint(pickerJob);val uri=if(approved)chooser.selectedFile.toPath().toAbsolutePath().normalize().toUri().toString() else null
                if(!commit {checkpoint(pickerJob);onSelected(uri)})throw CancellationException("Profile picker result retired")
            } finally {watcher.cancel();dialog.getAndSet(null)?.dispose()}
        }
    }
    override suspend fun readQrImage(uri:String):java.awt.image.BufferedImage = withContext(Dispatchers.IO) {
        val job=currentCoroutineContext()[Job];checkpoint(job)
        val selected=java.net.URI(uri)
        require(selected.scheme=="file") {"需要选择本机二维码图片"}
        val path=java.nio.file.Path.of(selected).toAbsolutePath().normalize()
        com.bilipai.desktop.update.UpdateStorage.existingPathWithoutLinks(path)
        require(java.nio.file.Files.isRegularFile(path,java.nio.file.LinkOption.NOFOLLOW_LINKS))
        require(java.nio.file.Files.size(path) in 1..16L*1024*1024) {"二维码图片过大"}
        val raw=java.nio.file.Files.newInputStream(path,java.nio.file.StandardOpenOption.READ,java.nio.file.LinkOption.NOFOLLOW_LINKS).use { input ->
            val result=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true) {checkpoint(job);val count=input.read(buffer);if(count<0)break;require(result.size()+count<=16*1024*1024);result.write(buffer,0,count)}
            result.toByteArray()
        }
        checkpoint(job)
        org.jetbrains.skia.Data.makeFromBytes(raw).use { data ->
            org.jetbrains.skia.Codec.makeFromData(data).use { codec ->
                require(codec.width.toLong()*codec.height in 1L..16_777_216L) {"二维码图片尺寸过大"}
            }
        }
        val image=java.io.ByteArrayInputStream(raw).use(javax.imageio.ImageIO::read)
            ?: org.jetbrains.skia.Image.makeFromEncoded(raw).use { native ->
                native.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.use { png ->
                    java.io.ByteArrayInputStream(png.bytes).use(javax.imageio.ImageIO::read)
                } ?: error("二维码图片无法解码")
            }
        try {checkpoint(job);image} catch(error:Throwable) {image.flush();throw error}
    }

    override suspend fun importWallpaperMedia(uri:String,directory:File)=files.import(uri,directory,false)
    override suspend fun importWallpaperImage(uri:String,directory:File)=files.import(uri,directory,true)
    override suspend fun readOwnedBytes(body:ResponseBody)=files.read(body)
    override suspend fun writeOwnedFile(destination:File,body:ResponseBody)=files.write(destination,body)
    override suspend fun writeOwnedFile(destination:File,bytes:ByteArray)=files.write(destination,bytes)
    override suspend fun deleteOwnedFile(file:File)=files.delete(file)
    override suspend fun saveImageToGallery(bytes:ByteArray,fileName:String) {checkpoint(currentCoroutineContext()[Job]);check(assets.saveProfileGalleryBytes(bytes,fileName,owns,commit)){"图片保存未完成"}}
    override suspend fun openOwnedDownload(request:Request):Response {
        val job=currentCoroutineContext()[Job];checkpoint(job)
        val call=callFactory.newCall(request);var response:Response?=null;var watcher:Job?=null
        try {
            watcher=scope.launch(Dispatchers.Default) {try {while(true){if(job?.isActive!=true || !owns())break;delay(50)}}finally {call.cancel()}}
            val ready=withContext(Dispatchers.IO) {checkpoint(job);call.execute().also {response=it;checkpoint(job)}}
            val body=ready.body
            val monitor=watcher
            val wrapped=object:ResponseBody() {
                override fun contentType()=body.contentType()
                override fun contentLength()=body.contentLength()
                private val guarded=object:ForwardingSource(body.source()) {
                    override fun read(sink:Buffer,byteCount:Long):Long {checkpoint(job);return super.read(sink,byteCount).also {checkpoint(job)}}
                    override fun close() {try {super.close()}finally {monitor?.cancel();call.cancel()}}
                }.buffer()
                override fun source()=guarded
            }
            return ready.newBuilder().body(wrapped).build().also {checkpoint(job)}
        } catch(error:Throwable) {call.cancel();response?.close();watcher?.cancel();throw error}
    }
}
