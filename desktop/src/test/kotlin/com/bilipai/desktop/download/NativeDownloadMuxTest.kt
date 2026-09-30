package com.bilipai.desktop.download

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Explicit native gate: missing binaries fail, and every input is synthesized locally. */
@Tag("native-mux")
class NativeDownloadMuxTest {
    @Test fun `native copy mux produces decodable video audio and audio-only files`(): Unit = runBlocking {
        val ffmpeg = System.getProperty("bilipai.nativeMuxFfmpeg")?.let(Path::of) ?: WindowsFfmpegMuxer.locateFfmpeg()
            ?: error("Native mux gate requires pinned ffmpeg.exe")
        val ffprobe = System.getProperty("bilipai.nativeMuxFfprobe")?.let(Path::of) ?: ffmpeg.resolveSibling("ffprobe.exe")
        require(Files.isRegularFile(ffmpeg) && Files.isRegularFile(ffprobe)) { "Native mux gate binaries are missing" }
        val directory = Files.createTempDirectory("bilipai-native-mux-")
        val video = directory.resolve("video.mp4")
        val audio = directory.resolve("audio.m4a")
        run(ffmpeg, directory, "-hide_banner", "-nostdin", "-y", "-f", "lavfi", "-i", "testsrc=size=160x90:rate=10",
            "-t", "1", "-an", "-c:v", "mpeg4", "-pix_fmt", "yuv420p", video.toString())
        run(ffmpeg, directory, "-hide_banner", "-nostdin", "-y", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
            "-t", "1", "-vn", "-c:a", "aac", audio.toString())
        val merged = directory.resolve("merged.mp4")
        val audioOnly = directory.resolve("audio-only.m4a")
        val progressive = directory.resolve("progressive.mp4")
        val muxer = WindowsFfmpegMuxer(ffmpeg)
        muxer.mux(video, audio, merged)
        muxer.mux(null, audio, audioOnly)
        muxer.mux(merged, null, progressive)
        val joined = directory.resolve("joined.mp4")
        val joinedAudio = directory.resolve("joined-audio.m4a")
        val joinedVideo = directory.resolve("joined-video.mp4")
        muxer.muxSegments(listOf(merged, merged), joined)
        muxer.muxSegments(listOf(merged, merged), joinedAudio, audioOnly = true)
        muxer.muxSegments(listOf(video, video), joinedVideo)
        val probes = listOf(merged, audioOnly, progressive, joined, joinedAudio, joinedVideo).associateWith { output ->
            val text = run(ffprobe, directory, "-v", "error", "-show_entries", "stream=codec_type,codec_name:format=duration", "-of", "json", output.toString())
            val probe = Json.parseToJsonElement(text).jsonObject
            val codecs = probe.getValue("streams").jsonArray.map { it.jsonObject }
            val duration = probe.getValue("format").jsonObject.getValue("duration").jsonPrimitive.double
            assertTrue(duration >= if (output in listOf(joined, joinedAudio, joinedVideo)) 1.95 else 0.95, "Merged file duration was truncated: $duration")
            val types = codecs.map { it.getValue("codec_type").jsonPrimitive.content }.toSet()
            assertEquals(if (output in listOf(audioOnly, joinedAudio)) setOf("audio") else if (output == joinedVideo) setOf("video") else setOf("video", "audio"), types)
            if (output != joinedVideo) assertTrue(codecs.any { it["codec_name"]?.jsonPrimitive?.content == "aac" })
            if (output !in listOf(audioOnly, joinedAudio)) assertTrue(codecs.any { it["codec_name"]?.jsonPrimitive?.content == "mpeg4" })
            run(ffmpeg, directory, "-v", "error", "-nostdin", "-i", output.toString(), "-f", "null", "-")
            probe
        }
        val report = buildJsonObject {
            put("passed", true)
            put("testedAt", java.time.Instant.now().toString())
            put("ffmpegSha256", sha256(ffmpeg)); put("ffprobeSha256", sha256(ffprobe))
            put("ffmpegVersion", run(ffmpeg, directory, "-version").lineSequence().first())
            put("dualTrackCopyMux", true); put("audioOnlyCopyMux", true); put("progressiveCopyMux", true)
            put("multiSegmentCopyMux", true); put("audioOnlyMultiSegmentCopyMux", true); put("videoOnlyMultiSegmentCopyMux", true)
            put("decodedAllOutputs", true)
            putJsonArray("outputs") { probes.forEach { (output, probe) -> add(buildJsonObject {
                put("name", output.fileName.toString()); put("bytes", Files.size(output)); put("sha256", sha256(output)); put("probe", probe)
            }) } }
        }
        System.getProperty("bilipai.nativeMuxReport")?.let { path ->
            val output = Path.of(path).toAbsolutePath()
            Files.createDirectories(output.parent)
            Files.writeString(output, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report))
        }
    }

    private fun run(executable: Path, directory: Path, vararg arguments: String): String {
        val log = Files.createTempFile(directory, "command-", ".log")
        val process = ProcessBuilder(listOf(executable.toAbsolutePath().toString()) + arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Native mux command timed out") }
        val text = Files.readString(log)
        check(process.exitValue() == 0) { "Native mux command failed: ${text.takeLast(4000)}" }
        return text
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
