package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.share.VideoShareCoverFile
import com.bilipai.desktop.update.UpdateStorage
import kotlinx.coroutines.*
import java.nio.file.*
import java.util.UUID
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import javax.imageio.ImageWriteParam

/** Temporary share files only, with the existing Root cache/session admission.
 * No account/preferences backing. Unknown native supply is retained, bounded to eight
 * files; restarting never infers that an old recipient has stopped reading. */
internal class DesktopVideoShareFiles(
    cacheDirectory:Path,
    private val download:suspend(String)->ByteArray,
    private val owned:()->Boolean,
    private val commit:((()->Unit)->Boolean),
) {
    private val root=cacheDirectory.toAbsolutePath().normalize().resolve("shared_images")
    private val gate=Any()
    private val exposed=mutableSetOf<Path>()
    private val pending=mutableSetOf<Path>()
    private fun checkOwner() { if(!owned())throw CancellationException("分享文件所有者已退役") }
    private suspend fun checkpoint() {currentCoroutineContext().ensureActive();checkOwner()}
    private fun directory() {
        checkOwner()
        var ancestor=root
        while(!Files.exists(ancestor,LinkOption.NOFOLLOW_LINKS))ancestor=requireNotNull(ancestor.parent)
        UpdateStorage.existingPathWithoutLinks(ancestor)
        Files.createDirectories(root);UpdateStorage.existingPathWithoutLinks(root)
        require(Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))
    }
    internal suspend fun assertAdmission() = checkpoint()
    suspend fun bytes(url:String):ByteArray {checkpoint();return download(url).also {checkpoint();require(it.isNotEmpty() && it.size <= 32*1024*1024)}}
    suspend fun bitmap(url:String):BufferedImage? {
        val raw=bytes(url)
        return ByteArrayInputStream(raw).use { input ->
            ImageIO.createImageInputStream(input).use { stream ->
                val readers=ImageIO.getImageReaders(stream)
                if(!readers.hasNext()) {
                    // Existing Skiko decoder supplies WebP/GIF support, without an ImageIO plugin.
                    return@use org.jetbrains.skia.Image.makeFromEncoded(raw).use { native ->
                        require(native.width>0 && native.height>0 && native.width.toLong()*native.height <= 32_000_000L) {"封面尺寸过大"}
                        native.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.use { png ->ByteArrayInputStream(png.bytes).use(ImageIO::read)}
                    }
                }
                val reader=readers.next()
                try {
                    reader.input=stream
                    val width=reader.getWidth(0);val height=reader.getHeight(0)
                    require(width>0 && height>0 && width.toLong()*height <= 32_000_000L) {"封面尺寸过大"}
                    reader.read(0)
                } finally { reader.dispose() }
            }
        }.also {checkpoint()}
    }
    suspend fun publish(name:String,mime:String,write:(Path)->Unit):VideoShareCoverFile = publish(name,mime,null,write)
    suspend fun publish(name:String,mime:String,beforeCommit:(()->Unit)?,write:(Path)->Unit):VideoShareCoverFile = withContext(Dispatchers.IO) {
        checkpoint()
        val callerJob=currentCoroutineContext()[Job]
        synchronized(gate) {
            directory()
            val existing=Files.newDirectoryStream(root,"BiliPai_share_*").use { it.toList() }
            // Retain restart files and unknown native grants; never silently exceed disk bounds.
            require(existing.size+pending.size<8) {"分享缓存尚有未确认的文件，请在设置中主动清理后重试"}
            require(name==Path.of(name).fileName.toString() && name.startsWith("BiliPai_share_"))
            val ext=name.substringAfterLast('.',"jpg")
            val target=root.resolve(name.substringBeforeLast('.')+"_"+UUID.randomUUID()+"."+ext)
            val temporary=Files.createTempFile(root,"preparing-share-",".tmp");pending.add(temporary)
            try {
                write(temporary);callerJob?.ensureActive();checkOwner()
                require(Files.size(temporary) in 1..32L*1024*1024)
                if(!commit {callerJob?.ensureActive();checkOwner();beforeCommit?.invoke();Files.move(temporary,target)})throw CancellationException("分享文件提交已退役")
                VideoShareCoverFile(target,mime)
            } finally {pending.remove(temporary);Files.deleteIfExists(temporary)}
        }.also { checkpoint() }
    }
    suspend fun jpeg(name:String,image:BufferedImage):VideoShareCoverFile = publish(name,"image/jpeg") { target ->
        val rgb=BufferedImage(image.width,image.height,BufferedImage.TYPE_INT_RGB)
        val g=rgb.createGraphics();try {g.drawImage(image,0,0,null)}finally {g.dispose()}
        try {
            val writer=ImageIO.getImageWritersByFormatName("jpeg").next()
            try {
                ImageIO.createImageOutputStream(target.toFile()).use { output ->
                    writer.output=output
                    val params=writer.defaultWriteParam.apply {compressionMode=ImageWriteParam.MODE_EXPLICIT;compressionQuality=.92f}
                    writer.write(null,javax.imageio.IIOImage(rgb,null,null),params)
                }
            } finally {writer.dispose()}
        } finally {rgb.flush()}
    }
    fun mayExpose(file:VideoShareCoverFile)=synchronized(gate) {verify(file);exposed.add(file.path);Unit}
    fun retire(file:VideoShareCoverFile,safe:Boolean)=synchronized(gate) {
        verify(file)
        exposed.remove(file.path)
        if(safe)Files.deleteIfExists(file.path)
    }
    private fun verify(file:VideoShareCoverFile) {require(file.path.toAbsolutePath().normalize().parent==root);UpdateStorage.existingPathWithoutLinks(file.path)}
    suspend fun save(file:VideoShareCoverFile,target:Path,
        finalAdmission: ((() -> Unit) -> Boolean)? = null) = withContext(Dispatchers.IO) {
        checkpoint();verify(file)
        val absolute=target.toAbsolutePath().normalize();val parent=requireNotNull(absolute.parent)
        UpdateStorage.existingPathWithoutLinks(parent)
        require(!absolute.startsWith(root)) {"保存位置不能覆盖分享缓存"}
        val temp=Files.createTempFile(parent,"saving-share-",".tmp")
        try {
            Files.newInputStream(file.path).use {input ->Files.newOutputStream(temp).use {output ->
                val buffer=ByteArray(64*1024)
                while(true) {checkpoint();val count=input.read(buffer);if(count<0)break;output.write(buffer,0,count)}
            }}
            checkpoint()
            val callerJob=currentCoroutineContext()[Job]
            // Copying remains outside publication. Video's optional source gate
            // is OUTSIDE the retained Root commit, preserving source -> Home entry
            // order already used by source-owned feedback. Legacy callers are unchanged.
            val publish = {
                if(!commit {callerJob?.ensureActive();checkOwner();Files.move(temp,absolute)})
                    throw CancellationException("分享保存已退役")
            }
            if (finalAdmission == null) publish()
            else if (!finalAdmission(publish)) throw CancellationException("分享保存来源已退役")
        } finally {Files.deleteIfExists(temp)}
    }
    /** Same pool, ordinary validated files only; never counts unknown files as reclaimable. */
    fun managedBytes(): Long = synchronized(gate) { directory(); managedFiles().sumOf { Files.size(it) } }
    private fun managedFiles(): List<Path> = Files.newDirectoryStream(root,"BiliPai_share_*").use { stream ->
        stream.filter { it.fileName.toString().matches(Regex("BiliPai_share_.+_[0-9a-fA-F-]{36}\\.(jpg|jpeg|png|webp|gif)")) }.toList()
            .onEach { path -> UpdateStorage.existingPathWithoutLinks(path); require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) }
    }
    /** Only explicit user-authorized clear, AFTER same native actor revocation/drain. */
    fun clearExplicit()=synchronized(gate) {
        require(exposed.isEmpty() && pending.isEmpty()) {"原生分享尚未撤销"}
        directory();val files=managedFiles();files.forEach(Files::delete)
    }
}
