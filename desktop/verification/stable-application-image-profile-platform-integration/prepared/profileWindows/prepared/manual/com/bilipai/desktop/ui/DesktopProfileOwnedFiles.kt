package com.bilipai.desktop.ui

import com.bilipai.desktop.update.UpdateStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.net.URI
import java.nio.file.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data

/** Only the physical effects of the original import algorithm. No store/client/player. */
internal interface DesktopProfileWallpaperImportContext {
    fun ensureCurrent()
    fun prepareDirectory(directory:File):Boolean
    fun mimeType(source:String):String?
    fun lastPathSegment(source:String):String?
    fun openSource(source:String):InputStream?
    fun createImportFile(directory:File,extension:String):File
    fun openImportOutput(file:File):OutputStream
    fun validateImage(file:File):Boolean
    suspend fun videoWidth(file:File):Int
    fun publishImport(file:File):File
    fun deleteImport(file:File)
}

internal fun interface DesktopProfileVideoWidth { suspend fun read(file:Path,checkpoint:()->Unit):Int }

/** Same entry scope/epoch and atomic Store→entry admission, private bounded temp IO only.
 * Windows same-directory Files.move without REPLACE_EXISTING is a no-clobber rename.
 * Imports retain original GIF/image bytes and original six video extensions. */
internal class DesktopProfileOwnedFiles(
    val stateDirectory:Path,
    private val owns:()->Boolean,
    private val commit:((()->Unit)->Boolean),
    private val metadata:DesktopProfileVideoWidth,
) {
    private val root=stateDirectory.toAbsolutePath().normalize()
    private val io=Mutex()
    private val importGeneration=AtomicLong()
    private fun checkpoint(job:Job?,generation:Long?=null) {
        job?.ensureActive()
        if(!owns() || (generation!=null && generation!=importGeneration.get()))throw CancellationException("Profile file request retired")
    }
    private fun directory(path:Path):Path {
        val target=path.toAbsolutePath().normalize()
        require(target.parent==root && target.fileName.toString() in setOf("profile_wallpaper","splash","home_wallpaper","images")) {"壁纸目录不受支持"}
        UpdateStorage.existingPathWithoutLinks(root)
        Files.createDirectories(target);UpdateStorage.existingPathWithoutLinks(target)
        require(Files.isDirectory(target,LinkOption.NOFOLLOW_LINKS));return target
    }
    private fun local(source:String):Path {
        val path=if(source.startsWith("file:",true))Path.of(URI.create(source)) else {
            require(!source.contains("://")){"请选择本地图片或视频"};Path.of(source)
        }
        val absolute=path.toAbsolutePath().normalize();UpdateStorage.existingPathWithoutLinks(absolute)
        require(Files.isRegularFile(absolute,LinkOption.NOFOLLOW_LINKS)){"所选文件不可用"};return absolute
    }
    private fun checkedInput(input:InputStream,job:Job?,limit:Long,generation:Long?=null):InputStream=object:FilterInputStream(input) {
        private var count=0L
        private fun account(n:Int) {checkpoint(job,generation);if(n>0){count+=n;require(count<=limit){"图片或视频文件过大"}}}
        override fun read():Int {checkpoint(job,generation);return `in`.read().also {account(if(it<0)0 else 1)}}
        override fun read(b:ByteArray,off:Int,len:Int):Int {checkpoint(job,generation);return `in`.read(b,off,len).also(::account)}
        override fun skip(n:Long):Long {checkpoint(job,generation);return `in`.skip(n).also {count+=it;checkpoint(job,generation);require(count<=limit)}}
    }
    private fun checkedOutput(output:OutputStream,job:Job?,generation:Long?=null):OutputStream=object:FilterOutputStream(output) {
        override fun write(b:Int) {checkpoint(job,generation);out.write(b);checkpoint(job,generation)}
        override fun write(b:ByteArray,off:Int,len:Int) {checkpoint(job,generation);out.write(b,off,len);checkpoint(job,generation)}
        override fun flush() {checkpoint(job,generation);out.flush()}
    }
    private fun publish(stage:Path,target:Path,job:Job?,generation:Long?=null) {
        checkpoint(job,generation)
        if(!commit {
            checkpoint(job,generation);UpdateStorage.existingPathWithoutLinks(stage)
            UpdateStorage.existingPathWithoutLinks(target.parent)
            require(!Files.exists(target,LinkOption.NOFOLLOW_LINKS)){"目标文件已存在"}
            Files.move(stage,target)
        })throw CancellationException("Profile file commit retired")
    }
    suspend fun import(source:String,directory:File,imageOnly:Boolean):File {
        val job=currentCoroutineContext()[Job];var generation=0L
        if(!commit {checkpoint(job);generation=importGeneration.incrementAndGet()})throw CancellationException("Profile import entry retired")
        return io.withLock {
            checkpoint(job,generation)
            val context=object:DesktopProfileWallpaperImportContext {
                val stages=mutableSetOf<Path>()
                override fun ensureCurrent() {checkpoint(job,generation)}
                override fun prepareDirectory(directory:File):Boolean {checkpoint(job,generation);directory(directory.toPath());return true}
                override fun mimeType(source:String)=Files.probeContentType(local(source))
                override fun lastPathSegment(source:String)=local(source).fileName.toString()
                override fun openSource(source:String):InputStream {
                    checkpoint(job,generation)
                    return checkedInput(Files.newInputStream(local(source),LinkOption.NOFOLLOW_LINKS),job,if(imageOnly)MAX_IMAGE_BYTES else MAX_MEDIA_BYTES,generation)
                }
                override fun createImportFile(directory:File,extension:String):File {
                    checkpoint(job,generation);val parent=directory(directory.toPath())
                    return Files.createTempFile(parent,".profile-import-",extension).also {stages.add(it)}.toFile()
                }
                override fun openImportOutput(file:File):OutputStream {
                    val path=file.toPath();require(path in stages);checkpoint(job,generation)
                    return checkedOutput(Files.newOutputStream(path,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING,LinkOption.NOFOLLOW_LINKS),job,generation)
                }
                override fun validateImage(file:File):Boolean {
                    checkpoint(job,generation)
                    return try {
                        require(Files.size(file.toPath()) in 1..MAX_IMAGE_BYTES)
                        val bytes=checkedInput(Files.newInputStream(file.toPath(),LinkOption.NOFOLLOW_LINKS),job,MAX_IMAGE_BYTES,generation).use {it.readBytes()}
                        Data.makeFromBytes(bytes).use {data->Codec.makeFromData(data).use {codec->
                            checkpoint(job,generation);val info=codec.imageInfo
                            info.width>0 && info.height>0 && info.width.toLong()*info.height*4<=256L*1024*1024
                        }}
                    } catch(cancelled:CancellationException){throw cancelled}
                    catch(_:Exception){checkpoint(job,generation);false}
                }
                override suspend fun videoWidth(file:File):Int {checkpoint(job,generation);return metadata.read(file.toPath()){checkpoint(job,generation)}.also {checkpoint(job,generation)}}
                override fun publishImport(file:File):File {
                    val stage=file.toPath();require(stage in stages)
                    val suffix=file.name.substringAfterLast('.',"img")
                    val target=stage.parent.resolve("wallpaper_${UUID.randomUUID()}.$suffix")
                    publish(stage,target,job,generation);stages.remove(stage);return target.toFile()
                }
                override fun deleteImport(file:File) {val path=file.toPath();require(path.parent==directory(directory.toPath()));Files.deleteIfExists(path);stages.remove(path)}
                fun cleanup() {for(path in stages)Files.deleteIfExists(path);stages.clear()}
            }
            try {
                if(imageOnly)com.android.purebilibili.feature.profile.importWallpaperImage(context,source,directory)
                else com.android.purebilibili.feature.profile.importWallpaperMedia(context,source,directory)
            } finally {context.cleanup()}
        }
    }
    suspend fun read(body:okhttp3.ResponseBody):ByteArray {
        val job=currentCoroutineContext()[Job];checkpoint(job)
        require(body.contentLength()<=MAX_IMAGE_BYTES){"图片文件过大"}
        return withContext(Dispatchers.IO) {checkedInput(body.byteStream(),job,MAX_IMAGE_BYTES).use {it.readBytes()}.also {
            checkpoint(job);require(it.isNotEmpty());require(body.contentLength()<0 || body.contentLength()==it.size.toLong()){"图片下载不完整"}
        }}
    }
    suspend fun write(destination:File,body:okhttp3.ResponseBody)=writeStream(destination,{body.byteStream()},body.contentLength())
    suspend fun write(destination:File,bytes:ByteArray) {require(bytes.size.toLong() in 1..MAX_IMAGE_BYTES);writeStream(destination,{ByteArrayInputStream(bytes)},bytes.size.toLong())}
    private suspend fun writeStream(destination:File,open:()->InputStream,expected:Long) {
        val job=currentCoroutineContext()[Job];checkpoint(job);require(expected<=MAX_IMAGE_BYTES)
        var published:Path?=null
        try {
            withContext(Dispatchers.IO) {io.withLock {
                checkpoint(job);val target=destination.toPath().toAbsolutePath().normalize();val parent=directory(requireNotNull(target.parent))
                val stage=Files.createTempFile(parent,".profile-write-",".tmp")
                try {
                    checkedInput(open(),job,MAX_IMAGE_BYTES).use {input->checkedOutput(Files.newOutputStream(stage),job).use {input.copyTo(it)}}
                    val length=Files.size(stage);require(length>0 && (expected<0 || length==expected)){"图片下载不完整"}
                    publish(stage,target,job);published=target;checkpoint(job)
                } finally {Files.deleteIfExists(stage)}
            }}
        } catch(error:Throwable){published?.let {Files.deleteIfExists(it)};throw error}
    }
    suspend fun delete(file:File)=withContext(Dispatchers.IO) {
        val job=currentCoroutineContext()[Job];checkpoint(job);val target=file.toPath().toAbsolutePath().normalize()
        directory(requireNotNull(target.parent))
        if(!commit {checkpoint(job);UpdateStorage.existingPathWithoutLinks(target.parent);if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){UpdateStorage.existingPathWithoutLinks(target);Files.delete(target)}})
            throw CancellationException("Profile delete owner retired")
    }
    companion object {const val MAX_IMAGE_BYTES=32L*1024*1024;const val MAX_MEDIA_BYTES=200L*1024*1024}
}
