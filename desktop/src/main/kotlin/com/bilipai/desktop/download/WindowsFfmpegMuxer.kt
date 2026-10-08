package com.bilipai.desktop.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

fun interface DownloadMuxer {
    suspend fun mux(video: Path?, audio: Path?, output: Path)
    suspend fun muxSegments(segments: List<Path>, output: Path, audioOnly: Boolean = false) {
        require(segments.size == 1) { "此合并器不支持多段媒体合并" }
        mux(segments.single().takeUnless { audioOnly }, segments.single().takeIf { audioOnly }, output)
    }
}

/** Desktop replacement for Android MediaMuxer; local tracks are copied without re-encoding. */
class WindowsFfmpegMuxer(private val executable: Path? = locateFfmpeg()) : DownloadMuxer {
    override suspend fun mux(video: Path?, audio: Path?, output: Path): Unit = withContext(Dispatchers.IO) {
        val ffmpeg = executable?.takeIf { Files.isRegularFile(it) }
            ?: throw IOException("未找到 FFmpeg，已保留下载轨道；请配置 BILIPAI_FFMPEG 或使用含 FFmpeg 的 Windows 包后重试合并")
        require(video != null || audio != null) { "下载媒体轨道不存在" }
        require(video == null || Files.isRegularFile(video) && Files.size(video) > 0) { "下载视频轨道不存在" }
        require(audio == null || Files.isRegularFile(audio) && Files.size(audio) > 0) { "下载音频轨道不存在" }
        Files.createDirectories(output.parent)
        execute(output) { staged -> command(ffmpeg, video, audio, staged) }
    }

    override suspend fun muxSegments(segments: List<Path>, output: Path, audioOnly: Boolean): Unit = withContext(Dispatchers.IO) {
        val ffmpeg = executable?.takeIf { Files.isRegularFile(it) } ?: throw IOException("未找到 FFmpeg，已保留下载分段")
        require(segments.isNotEmpty()) { "没有可合并的下载分段" }
        val directory = output.toAbsolutePath().normalize().parent
        require(segments.all { it.toAbsolutePath().normalize().parent == directory && Files.isRegularFile(it) &&
            !Files.isSymbolicLink(it) && Files.size(it) > 0 && it.fileName.toString().matches(Regex("[A-Za-z0-9_.-]+")) }) { "媒体分段必须位于同一任务目录" }
        val playlist = Files.createTempFile(directory, "concat-", ".ffconcat")
        try {
            Files.writeString(playlist, "ffconcat version 1.0\n" + segments.joinToString("\n") { "file '${it.fileName}'" } + "\n")
            execute(output) { staged -> concatCommand(ffmpeg, playlist, staged, audioOnly) }
        } finally { Files.deleteIfExists(playlist) }
    }

    private suspend fun execute(output: Path, makeCommand: (Path) -> List<String>) {
        currentCoroutineContext().ensureActive()
        val staged = output.resolveSibling(output.fileName.toString() + ".merging." + output.fileName.toString().substringAfterLast('.'))
        val log = output.resolveSibling("merge.log")
        val process = ProcessBuilder(makeCommand(staged)).redirectErrorStream(true)
            .redirectOutput(log.toFile()).start()
        try {
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                currentCoroutineContext().ensureActive()
                delay(25)
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(staged) || Files.size(staged) < 12) {
                val detail = runCatching { Files.readString(log).takeLast(1500) }.getOrDefault("")
                throw IOException("音视频合并失败，下载轨道仍保留。${detail.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()}")
            }
            currentCoroutineContext().ensureActive()
            Files.newInputStream(staged).use { input ->
                val header = input.readNBytes(12)
                if (header.copyOfRange(4, 8).toString(Charsets.US_ASCII) != "ftyp") throw IOException("FFmpeg 未生成有效 MP4 文件")
            }
            try { Files.move(staged, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(staged, output, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            withContext(NonCancellable) {
                var interrupted = false
                try {
                    if (process.isAlive) runCatching { process.destroy() }
                    // destroyForcibly is a request. Retain the worker/file owner until actual exit.
                    while (process.isAlive) {
                        try {
                            if (!process.waitFor(1, TimeUnit.SECONDS)) runCatching { process.destroyForcibly() }
                        } catch (_: InterruptedException) {
                            interrupted = true
                            runCatching { process.destroyForcibly() }
                        }
                    }
                    Files.deleteIfExists(staged)
                } finally {
                    if (interrupted) Thread.currentThread().interrupt()
                }
            }
        }
    }

    companion object {
        internal fun concatCommand(executable: Path, playlist: Path, output: Path, audioOnly: Boolean): List<String> = buildList {
            addAll(listOf(executable.toAbsolutePath().toString(), "-hide_banner", "-nostdin", "-y", "-f", "concat", "-safe", "1", "-i", playlist.toAbsolutePath().toString()))
            addAll(if (audioOnly) listOf("-map", "0:a:0") else listOf("-map", "0:v:0", "-map", "0:a:0?"))
            addAll(listOf("-c", "copy", "-movflags", "+faststart", output.toAbsolutePath().toString()))
        }
        internal fun command(executable: Path, video: Path?, audio: Path?, output: Path): List<String> = buildList {
            require(video != null || audio != null)
            addAll(listOf(executable.toAbsolutePath().toString(), "-hide_banner", "-nostdin", "-y", "-i", (video ?: requireNotNull(audio)).toAbsolutePath().toString()))
            if (video == null) addAll(listOf("-map", "0:a:0"))
            else if (audio != null) addAll(listOf("-i", audio.toAbsolutePath().toString(), "-map", "0:v:0", "-map", "1:a:0"))
            else addAll(listOf("-map", "0:v:0", "-map", "0:a:0?"))
            addAll(listOf("-c", "copy", "-movflags", "+faststart", output.toAbsolutePath().toString()))
        }

        fun locateFfmpeg(): Path? {
            val explicit = System.getenv("BILIPAI_FFMPEG")?.takeIf { it.isNotBlank() }?.let { runCatching { Path.of(it.trim('"')) }.getOrNull() }
            if (explicit != null && Files.isRegularFile(explicit)) return explicit
            val resources = System.getProperty("compose.application.resources.dir")?.let(Path::of)
            val candidates = listOfNotNull(resources?.resolve("native/windows-x64/ffmpeg.exe"),
                resources?.resolve("ffmpeg.exe"), Path.of("desktop/native/windows-x64/ffmpeg.exe"),
                Path.of("desktop/resources/common/native/windows-x64/ffmpeg.exe")) +
                System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator).filter { it.isNotBlank() }
                    .mapNotNull { runCatching { Path.of(it.trim('"'), "ffmpeg.exe") }.getOrNull() }
            return candidates.firstOrNull { Files.isRegularFile(it) }?.toAbsolutePath()
        }
    }
}
