package com.bilipai.desktop.settings

import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.android.purebilibili.app.newImageLoader
import com.android.purebilibili.core.store.AppIconAppearance
import com.android.purebilibili.feature.settings.getIconGroups
import com.android.purebilibili.feature.settings.resolveIconOptionPreviewRes
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class DesktopOriginalAppIconCoilTest {
    @Test fun everyLocalIconModelExecutesThroughOriginalRootCoilConfigWithoutNetwork(): Unit = runBlocking {
        val networkCalls = AtomicInteger()
        val loader = newImageLoader(PlatformContext.INSTANCE, Call.Factory {
            networkCalls.incrementAndGet()
            error("Original classpath icon must not use the network")
        }, Files.createTempDirectory("bp-icon-coil-").toFile())
        val resources = linkedSetOf<coil3.Uri>()
        for (option in getIconGroups().flatMap { it.icons })
            for (appearance in AppIconAppearance.entries) for (dark in listOf(false, true)) {
                resources += desktopOriginalIconPreviewUri(resolveIconOptionPreviewRes(option.key, appearance), dark)
                val launcher = resolveDesktopOriginalLauncherIconResource(option.key, appearance, dark)
                resources += desktopOriginalCoilClasspathUri(requireNotNull(javaClass.getResource(launcher)))
            }
        try {
            assertEquals(18, resources.size)
            for (model in resources) {
                val result = loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(model).build())
                assertTrue(result is SuccessResult, "Actual Coil could not fetch/decode original icon: $model ($result)")
                val image = (result as SuccessResult).image
                assertTrue(image.width >= 64 && image.height >= 64, model.toString())
            }
            assertEquals(0, networkCalls.get())
        } finally { loader.shutdown() }
    }

    @Test fun classpathArchiveWithSpacesAndUnicodeExecutesThroughTheSameOriginalLoader(): Unit = runBlocking {
        val resource = resolveDesktopOriginalLauncherIconResource("icon_blue_snow_maid", AppIconAppearance.DARK, true)
        val raw = requireNotNull(javaClass.getResourceAsStream(resource)).use { it.readBytes() }
        val directory = Files.createTempDirectory("bp-icon-jar-")
        val archive = directory.resolve("本地图标 测试.jar")
        java.util.jar.JarOutputStream(Files.newOutputStream(archive)).use { output ->
            output.putNextEntry(java.util.jar.JarEntry(resource.removePrefix("/")))
            output.write(raw)
            output.closeEntry()
        }
        val calls = AtomicInteger()
        val loader = newImageLoader(PlatformContext.INSTANCE, Call.Factory {
            calls.incrementAndGet();error("Local classpath archive must not use the network")
        }, directory.resolve("cache").toFile())
        try {
            java.net.URLClassLoader(arrayOf(archive.toUri().toURL()), null).use { classpath ->
                val model = desktopOriginalCoilClasspathUri(requireNotNull(classpath.getResource(resource.removePrefix("/"))))
                val result = loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(model).build())
                assertTrue(result is SuccessResult, "Actual Coil could not decode the local archive icon: $result")
                assertTrue((result as SuccessResult).image.width >= 64)
                assertEquals(0, calls.get())
            }
        } finally { loader.shutdown() }
    }
}
