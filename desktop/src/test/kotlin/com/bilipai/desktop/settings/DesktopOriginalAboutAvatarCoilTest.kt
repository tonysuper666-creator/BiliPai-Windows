package com.bilipai.desktop.settings

import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.android.purebilibili.app.newImageLoader
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class DesktopOriginalAboutAvatarCoilTest {
    // Fixed upstream 79e8fa3 raw assets, not hashes derived from the tested output.
    private val pins = linkedMapOf(
        "original-about/avatar_chenx_dust.webp" to "6bd10701b79de88a710085adc83c2bfeb6b423fb8dfc1a95f566b60ac1104eb7",
        "original-about/avatar_jay3_yy.webp" to "9f8cf840441ef3731337fcc9c03eb0eb006f499dc1871d9ada9083ab0d712582",
        "original-about/avatar_lekoowo.webp" to "6476dd362822eb59128a7d98720c85d97a6476a6324956c496167b5d40c44ae1",
        "original-about/avatar_mvanhorn.webp" to "b620dc5436dfe3e26a1c2bddc2fbea0fa8131ec02fc0eca1745bb453917f1ea9",
        "original-about/avatar_qyo123oyq.jpg" to "b3e5d2b196bb9aa5d62e37ad71bfe43d4dc9175e245d7c21b359c8584653d37a",
        "original-about/avatar_tanakalun.webp" to "c335060e273f0d837495e81aa39c9fcac76e2b09e445859b115fc2c69d8543fb",
        "original-about/avatar_usontong.webp" to "c8e5fa77d68c018fc50b2e5793138bc686b1bd2020b07d8a25041b817dbe29b2",
    )

    private fun originalRoot(): Path = System.getProperty("compose.application.resources.dir")
        ?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        ?: Path.of("resources/common").toAbsolutePath()

    private fun originalBytes(): Map<String, ByteArray> = pins.mapValues { (relative, expected) ->
        Files.readAllBytes(originalRoot().resolve(relative)).also { raw ->
            val digest = MessageDigest.getInstance("SHA-256").digest(raw)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, digest, "Original avatar changed: $relative")
        }
    }

    private fun name(relative: String) = relative.substringAfterLast('/').substringBeforeLast('.')

    private suspend fun decode(models: Collection<coil3.Uri>, cacheRoot: Path) {
        val calls = AtomicInteger()
        val loader = newImageLoader(PlatformContext.INSTANCE, Call.Factory {
            calls.incrementAndGet()
            error("Original local About avatars must never use the network")
        }, cacheRoot.toFile())
        try {
            assertEquals(7, models.size)
            for (model in models) {
                val result = loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(model).build())
                assertTrue(result is SuccessResult, "Actual original Coil could not decode avatar: $model ($result)")
                val image = (result as SuccessResult).image
                assertTrue(image.width > 0 && image.height > 0, "Empty decoded original avatar: $model")
            }
            assertEquals(0, calls.get())
        } finally {
            loader.shutdown()
        }
    }

    private fun archive(root: Path, originals: Map<String, ByteArray>): Path {
        val file = root.resolve("原版头像 含空格.jar")
        JarOutputStream(Files.newOutputStream(file)).use { output ->
            for ((relative, raw) in originals) {
                output.putNextEntry(JarEntry(relative))
                output.write(raw)
                output.closeEntry()
            }
        }
        return file
    }

    @Test fun allSevenUntouchedCommonAssetsDecodeThroughActualOriginalCoil(): Unit = runBlocking {
        val originals = originalBytes()
        assertEquals(6, originals.keys.count { it.endsWith(".webp") })
        assertEquals(1, originals.keys.count { it.endsWith(".jpg") })
        URLClassLoader(emptyArray(), null).use { noClasspathFallback ->
            val models = originals.keys.map { desktopOriginalAboutAvatarUri(name(it), originalRoot(), noClasspathFallback) }
            decode(models, Files.createTempDirectory("bp-about-common-cache-"))
        }
    }

    @Test fun actualCommonFilePathsWithUnicodeAndSpacesDecodeWithoutClasspathFallback(): Unit = runBlocking {
        val directory = Files.createTempDirectory("bp-about-file-").resolve("原版头像 含空格")
        Files.createDirectories(directory)
        val originals = originalBytes()
        for ((relative, raw) in originals) {
            val asset = directory.resolve(relative)
            Files.createDirectories(asset.parent)
            Files.write(asset, raw)
        }
        URLClassLoader(emptyArray(), null).use { noClasspathFallback ->
            decode(originals.keys.map { desktopOriginalAboutAvatarUri(name(it), directory, noClasspathFallback) },
                directory.resolve("cache"))
        }
    }

    @Test fun localArchiveFallbackWithUnicodeAndSpacesDecodesSameOriginalWebpAndJpg(): Unit = runBlocking {
        val directory = Files.createTempDirectory("bp-about-jar-")
        val originals = originalBytes()
        val file = archive(directory, originals)
        URLClassLoader(arrayOf(file.toUri().toURL()), null).use { classpath ->
            val models = originals.keys.map { desktopOriginalAboutAvatarUri(name(it), null, classpath) }
            decode(models, directory.resolve("cache"))
        }
    }

    @Test fun configuredCommonAssetMissingFailsEvenWhenAValidArchiveCopyExists() {
        val directory = Files.createTempDirectory("bp-about-missing-")
        val file = archive(directory, originalBytes())
        val emptyCommon = Files.createDirectory(directory.resolve("empty-common"))
        URLClassLoader(arrayOf(file.toUri().toURL()), null).use { classpath ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                desktopOriginalAboutAvatarUri("avatar_jay3_yy", emptyCommon, classpath)
            }
            assertTrue(failure.message.orEmpty().contains("avatar_jay3_yy.webp"))
            assertTrue(failure.message.orEmpty().contains("empty-common"))
        }
    }

    @Test fun unknownNamesCannotResolveOutsideTheSevenOriginalAssets() {
        for (unknown in listOf("../../avatar_jay3_yy", "avatar_jay3_yy.webp", "https://example.invalid/avatar")) {
            assertThrows(IllegalStateException::class.java) {
                desktopOriginalAboutAvatarUri(unknown, originalRoot())
            }
        }
    }
}
