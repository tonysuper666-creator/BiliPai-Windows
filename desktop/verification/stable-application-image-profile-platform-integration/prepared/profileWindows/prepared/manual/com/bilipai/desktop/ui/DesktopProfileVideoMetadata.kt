package com.bilipai.desktop.ui

import kotlinx.coroutines.*
import java.nio.file.*
import java.util.concurrent.TimeUnit

/** Required path to the ALREADY trusted/bundled ffprobe beside existing FFmpeg.
 * A bounded local metadata subprocess, never another MPV core/player or transport. */
internal class DesktopProfileFfprobeWidth(private val executable:Path):DesktopProfileVideoWidth {
    override suspend fun read(file:Path,checkpoint:()->Unit):Int=withContext(Dispatchers.IO) {
        checkpoint();require(Files.isRegularFile(executable) && Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))
        coroutineScope {
            val process=ProcessBuilder(executable.toAbsolutePath().toString(),"-v","error","-protocol_whitelist","file,pipe",
                "-select_streams","v:0","-show_entries","stream=width","-of","default=noprint_wrappers=1:nokey=1",file.toAbsolutePath().toString())
                .redirectErrorStream(true).start()
            val reader=async(Dispatchers.IO) {
                process.inputStream.use {input->
                    val bytes=java.io.ByteArrayOutputStream();val buffer=ByteArray(512)
                    while(true) {
                        currentCoroutineContext().ensureActive();checkpoint()
                        val count=input.read(buffer);checkpoint()
                        if(count<0)break
                        require(bytes.size()+count<=4096){"视频元数据读取过大"}
                        bytes.write(buffer,0,count)
                    }
                    bytes.toString(Charsets.UTF_8)
                }
            }
            try {
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
                while(!process.waitFor(50,TimeUnit.MILLISECONDS)) {
                    currentCoroutineContext().ensureActive();checkpoint()
                    if(reader.isCompleted)reader.await()
                    require(System.nanoTime()<deadline){"视频元数据读取超时"}
                    delay(10)
                }
                currentCoroutineContext().ensureActive();checkpoint()
                val output=reader.await()
                require(process.exitValue()==0){"视频损坏或格式不受支持"}
                output.trim().toIntOrNull()?.takeIf {it>0} ?: 0
            } finally {
                if(process.isAlive){process.destroy();if(!process.waitFor(500,TimeUnit.MILLISECONDS))process.destroyForcibly();process.waitFor(500,TimeUnit.MILLISECONDS)}
                process.inputStream.close();process.errorStream.close();process.outputStream.close();reader.cancel()
            }
        }
    }
}
