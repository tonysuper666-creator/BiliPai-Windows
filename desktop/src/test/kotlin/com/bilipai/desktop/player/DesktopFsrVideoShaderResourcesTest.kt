package com.bilipai.desktop.player

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.*

class DesktopFsrVideoShaderResourcesTest {
    @Test fun sharpnessReusesVerifiedOriginalAssetsAndCorruptionIsRepaired() = runBlocking<Unit> {
        val root = Files.createTempDirectory("bilipai-fsr-asset-fixture-").toRealPath()
        var paths = emptyList<java.nio.file.Path>()
        try {
            val resources = DesktopFsrVideoShaderResources(root)
            val weak = assertNotNull(DesktopFsrHookAdapter.prepare(320, 180, 720, 405, 4096, 0f))
            val strong = assertNotNull(DesktopFsrHookAdapter.prepare(320, 180, 720, 405, 4096, 1f))
            paths = resources.resolve(weak)
            assertEquals(listOf("bilipai-fsr-easu.glsl", "bilipai-fsr-rcas.glsl"), paths.map { it.fileName.toString() })
            assertTrue(paths.all { it.toRealPath().startsWith(root) })
            assertEquals(weak.easu, Files.readString(paths[0]))
            assertEquals(weak.rcas, Files.readString(paths[1]))
            val marker = FileTime.fromMillis(1_230_000L)
            paths.forEach { Files.setLastModifiedTime(it, marker) }
            assertEquals(paths, resources.resolve(strong))
            assertTrue(paths.all { Files.getLastModifiedTime(it) == marker }, "Changing a native uniform must not rewrite the algorithm assets.")
            Files.writeString(paths[0], "corrupt fixture")
            assertEquals(paths, resources.resolve(strong))
            assertEquals(strong.easu, Files.readString(paths[0]))
            assertEquals(strong.rcas, Files.readString(paths[1]))
            Files.list(paths[0].parent).use { entries -> assertEquals(2L, entries.count()) }
        } finally {
            // Only explicit fixture paths are removed; no recursive filesystem deletion.
            paths.forEach { check(it.toAbsolutePath().normalize().startsWith(root)); Files.deleteIfExists(it) }
            paths.firstOrNull()?.parent?.let { Files.deleteIfExists(it) }
            Files.deleteIfExists(root)
        }
    }
}
