package com.bilipai.desktop.diagnostics

import com.android.purebilibili.core.util.resolveCrashSnapshotFile
import com.bilipai.desktop.update.UpdateStorage
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.util.UUID

internal class DesktopCrashShareCapacityException(val safeMessage:String):IllegalStateException(safeMessage)

internal class DesktopCrashShareLease internal constructor(val id:String,val owner:String,val path:Path)

/** Only the SAME diagnostic writer calls this owner; no independent store/thread. */
internal class DesktopCrashShareCache(private val root:Path) {
    private val owner=UUID.randomUUID().toString()
    private val directory=root.toAbsolutePath().normalize().resolve("cache/diagnostic-share")
    private val filename=resolveCrashSnapshotFile(root.toFile()).name
    private val allowed=setOf(filename,".lease",".lease.tmp")
    private fun guard(path:Path) {
        var existing=path
        while(!Files.exists(existing,LinkOption.NOFOLLOW_LINKS))existing=requireNotNull(existing.parent)
        UpdateStorage.existingPathWithoutLinks(existing)
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))UpdateStorage.existingPathWithoutLinks(path)
    }
    private fun folders():List<Path> {
        guard(directory)
        if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))return emptyList()
        require(Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))
        return Files.list(directory).use {stream->
            val values=stream.limit(4097).toList();require(values.size<=4096){"分享副本数量已达到安全上限，请显式清理本地日志。"}
            values.onEach {dir->
                require(UUID.fromString(dir.fileName.toString()).toString()==dir.fileName.toString())
                guard(dir);require(Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS))
            }
        }
    }
    private fun files(dir:Path):List<Path> {
        guard(dir)
        return Files.list(dir).use {stream->
            val values=stream.limit(4).toList();require(values.size<=3)
            values.onEach {file->
                require(file.fileName.toString() in allowed);guard(file)
                require(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))
                require(Files.size(file)<=if(file.fileName.toString()==filename)256*1024 else 128)
            }
        }
    }
    private fun state(dir:Path):String {
        files(dir)
        val file=dir.resolve(".lease")
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return "UNCONFIRMED" // earlier platform copies
        require(Files.size(file)<=128)
        val rows=Files.readString(file).split('\n');require(rows.size==3 && rows.last().isEmpty())
        require(UUID.fromString(rows[1]).toString()==rows[1])
        require(rows[0] in setOf("PREPARED","MAY_EXPOSE","RETIRED","TERMINAL"))
        return rows[0]
    }
    private fun writeState(dir:Path,value:String) {
        files(dir)
        val temporary=dir.resolve(".lease.tmp");guard(temporary)
        FileChannel.open(temporary,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING).use {
            val data=ByteBuffer.wrap("$value\n$owner\n".toByteArray(Charsets.UTF_8))
            while(data.hasRemaining())it.write(data)
            it.force(true)
        }
        Files.move(temporary,dir.resolve(".lease"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
    }
    private fun checkLease(lease:DesktopCrashShareLease):Path {
        require(lease.owner==owner && UUID.fromString(lease.id).toString()==lease.id)
        val dir=directory.resolve(lease.id)
        require(lease.path==dir.resolve(filename));files(dir)
        return dir
    }
    private fun deleteFolder(dir:Path) {
        // Validate the whole small directory before deleting any byte. No recursive deletion.
        val values=files(dir);values.filter{it.fileName.toString() !in setOf(".lease",".lease.tmp")}.forEach(Files::delete)
        values.filter{it.fileName.toString() in setOf(".lease",".lease.tmp")}.forEach(Files::delete)
        Files.delete(dir)
    }
    fun create(snapshot:String):DesktopCrashShareLease {
        // Durable safe states may be collected without inventing receiver completion.
        var retainedBytes=0L;var retainedCount=0
        for(dir in folders())when(state(dir)) {
            "PREPARED","TERMINAL"->deleteFolder(dir)
            else->{
                retainedCount++
                val copy=dir.resolve(filename)
                if(Files.exists(copy,LinkOption.NOFOLLOW_LINKS))retainedBytes+=Files.size(copy)
            } // Never modify a possibly still-readable receiver copy.
        }
        val content=sanitizeDesktopDiagnosticText(snapshot).toByteArray(Charsets.UTF_8)
        require(content.isNotEmpty() && content.size<=256*1024)
        if(retainedCount>=4096)throw DesktopCrashShareCapacityException("分享副本数量已达到安全上限，请显式清理本地日志后重试。")
        if(retainedBytes+content.size>16L*1024*1024)throw DesktopCrashShareCapacityException("未确认的分享副本已达到16MiB保护上限，请显式清理本地日志后重试。")
        guard(directory);Files.createDirectories(directory);guard(directory)
        val id=UUID.randomUUID().toString();val dir=directory.resolve(id);Files.createDirectory(dir)
        val lease=DesktopCrashShareLease(id,owner,dir.resolve(filename))
        try {
            FileChannel.open(lease.path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE).use {
                val data=ByteBuffer.wrap(content);while(data.hasRemaining())it.write(data);it.force(true)
            }
            writeState(dir,"PREPARED")
        }catch(failure:Exception){deleteFolder(dir);throw failure}
        return lease
    }
    /** Persist before calling Show: a crash before the first poll cannot misclassify exposure. */
    fun mayExpose(lease:DesktopCrashShareLease){writeState(checkLease(lease),"MAY_EXPOSE")}
    fun retire(lease:DesktopCrashShareLease,safeToDelete:Boolean) {
        val dir=checkLease(lease)
        writeState(dir,if(safeToDelete)"TERMINAL" else "RETIRED")
        if(safeToDelete)deleteFolder(dir)
    }
    /** Explicit clear-all is user revocation, not an inferred successful receiver read. */
    fun clearExplicit() {
        val dirs=folders();dirs.forEach(::files) // validate all before the first deletion
        dirs.forEach(::deleteFolder)
    }
}
