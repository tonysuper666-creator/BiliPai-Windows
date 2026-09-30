package com.bilipai.desktop.plugins

import com.android.purebilibili.feature.anime4k.Anime4KPreset
import com.android.purebilibili.feature.anime4k.resolveAnime4KRenderProfile
import com.android.purebilibili.feature.anime4k.gl.MpvAnime4KShaderParser
import com.android.purebilibili.feature.anime4k.gl.resolveAnime4KShaderFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.security.MessageDigest

/** A verified, original mpv hook chain. No Android fragment shader conversion is needed. */
class DesktopVideoShaderResources(private val cacheRoot: Path,
    private val resource: (String) -> InputStream? = { DesktopVideoShaderResources::class.java.classLoader.getResourceAsStream(it) }) {
    private val mutex = Mutex()
    suspend fun resolveAnime4KPaths(preset: Anime4KPreset): List<Path> = withContext(Dispatchers.IO) {
        mutex.withLock {
            Files.createDirectories(cacheRoot)
            val canonicalRoot = cacheRoot.toRealPath()
            resolveAnime4KShaderFiles(resolveAnime4KRenderProfile(preset).shaderChain).map { name ->
                val expected = DesktopPluginAssetHashes.hashes["app/src/main/assets/anime4k/$name"] ?: error("画质着色器未登记：$name")
                val bytes = resource("anime4k/$name")?.use { input ->
                    val raw = input.readNBytes(1_048_577)
                    require(raw.size <= 1_048_576) { "画质着色器超过大小限制" }
                    raw.toString(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
                } ?: error("缺少画质着色器：$name")
                check(digest(bytes) == expected) { "画质着色器校验失败：$name" }
                MpvAnime4KShaderParser.parse(name, bytes.toString(Charsets.UTF_8))
                val folder = canonicalRoot.resolve(expected)
                Files.createDirectories(folder)
                check(folder.toRealPath().startsWith(canonicalRoot)) { "画质缓存目录越界" }
                val target = folder.resolve(name)
                check(!Files.isSymbolicLink(target)) { "画质缓存文件不能是符号链接" }
                if (!Files.isRegularFile(target) || digest(Files.readAllBytes(target)) != expected) {
                    val temp = Files.createTempFile(folder, ".shader-", ".tmp")
                    try {
                        Files.write(temp, bytes)
                        try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                        catch (unsupported: AtomicMoveNotSupportedException) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING) }
                    } finally { Files.deleteIfExists(temp) }
                }
                target
            }
        }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
