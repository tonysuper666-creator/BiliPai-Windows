package com.bilipai.desktop.update

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopUpdaterSafetyTest {
    @Test
    fun `missing releases or missing assets never claim current version is latest`() {
        val repository = "owner/BiliPai-Windows"
        assertIs<UpdateState.Failed>(DesktopUpdater.evaluateReleaseMetadata("[]", "0.2.406.1", repository))
        assertIs<UpdateState.Failed>(DesktopUpdater.evaluateReleaseMetadata(releaseMetadata("0.2.406.2", includeChecksum = false), "0.2.406.1", repository))
        assertIs<UpdateState.Available>(DesktopUpdater.evaluateReleaseMetadata(releaseMetadata("0.2.406.2"), "0.2.406.1", repository))
        assertIs<UpdateState.UpToDate>(DesktopUpdater.evaluateReleaseMetadata(releaseMetadata("0.2.406.1"), "0.2.406.1", repository))
    }

    @Test
    fun `incomplete newest release is not hidden by an older healthy release`() {
        val newer = releaseMetadata("0.2.406.3", includeChecksum = false).removeSurrounding("[", "]")
        val older = releaseMetadata("0.2.406.2").removeSurrounding("[", "]")
        assertIs<UpdateState.Failed>(DesktopUpdater.evaluateReleaseMetadata("[$newer,$older]", "0.2.406.1", "owner/BiliPai-Windows"))
    }

    @Test
    fun `cleanup only removes owned obsolete stages and preserves active previous and running stages`() {
        val root = Files.createTempDirectory("bilipai-prune-test")
        val repository = "owner/BiliPai-Windows"
        val current = UpdateStorage.createStage(root, repository, 1)
        val previous = UpdateStorage.createStage(root, repository, 2)
        val running = UpdateStorage.createStage(root, repository, 3)
        val obsolete = UpdateStorage.createStage(root, repository, 4)
        Files.writeString(obsolete.resolve("download.zip"), "stale")
        val unrelated = Files.createDirectory(root.resolve("staged-5-user-data"))
        val foreign = UpdateStorage.createStage(root, "different/repository", 6)
        UpdateStorage.prune(root, repository, setOf(current, previous, running))
        listOf(current, previous, running, unrelated, foreign).forEach { assertTrue(Files.isDirectory(it), it.toString()) }
        assertFalse(Files.exists(obsolete))
        assertFalse(UpdateStorage.deleteOwnedStage(root, root, repository))
        assertFalse(UpdateStorage.deleteOwnedStage(root, unrelated, repository))
    }

    @Test
    fun `zip extraction propagates cancellation before creating package files`() {
        val directory = Files.createTempDirectory("bilipai-update-cancel")
        val zip = createZip(directory, listOf("BiliPai Windows.exe"))
        val target = Files.createDirectory(directory.resolve("new-install"))
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            SafeUpdateZip.extract(zip, target, "BiliPai Windows.exe") { throw kotlinx.coroutines.CancellationException("cancelled") }
        }
        assertFalse(Files.exists(target.resolve("BiliPai Windows.exe")))
    }

    @Test
    fun `desktop rebuild revision and prerelease numbers sort correctly`() {
        fun version(value: String) = requireNotNull(DesktopVersion.parse(value))
        assertTrue(version("windows-v0.2.406.2") > version("0.2.406.1"))
        assertTrue(version("0.2.407.1") > version("0.2.406.9"))
        assertTrue(version("v0.2.3-alpha.10") > version("0.2.3-alpha.9"))
        assertTrue(version("0.2.3") > version("0.2.3-alpha.10"))
        assertTrue(version("0.2.406+desktop.2") > version("0.2.406+desktop.1"))
        assertEquals(0, version("0.2.406.0").compareTo(version("0.2.406")))
    }

    @Test
    fun `only configured GitHub repository Windows zip assets are accepted`() {
        assertTrue(DesktopUpdater.isWindowsZip("BiliPai-Windows-0.2.406.2.zip"))
        assertFalse(DesktopUpdater.isWindowsZip("BiliPai-0.2.3-alpha.10.apk"))
        assertFalse(DesktopUpdater.isWindowsZip("BiliPai-Windows-0.2.406.2.exe"))
        assertFalse(DesktopUpdater.isWindowsZip("../BiliPai-Windows.zip"))
        val repository = "owner/BiliPai-Windows"
        assertTrue(DesktopUpdater.trustedAssetUrl("https://github.com/$repository/releases/download/windows-v0.2.406.2/BiliPai-Windows.zip", repository))
        listOf(
            "https://github.com.attacker.test/$repository/releases/download/v1/package.zip",
            "https://github.com/other/repository/releases/download/v1/package.zip",
            "http://github.com/$repository/releases/download/v1/package.zip",
            "https://attacker.test/$repository/releases/download/v1/package.zip",
        ).forEach { assertFalse(DesktopUpdater.trustedAssetUrl(it, repository), it) }
    }

    @Test
    fun `checksum is bound to exact asset instead of first entry`() {
        val expected = "ab".repeat(32)
        assertEquals(expected, DesktopUpdater.parseChecksum("${"cd".repeat(32)}  other.zip\n$expected  BiliPai-Windows.zip\n", "BiliPai-Windows.zip"))
        assertFailsWith<IllegalArgumentException> { DesktopUpdater.parseChecksum("$expected  other.zip", "BiliPai-Windows.zip") }
        assertFailsWith<IllegalArgumentException> { DesktopUpdater.parseChecksum("$expected  BiliPai-Windows.zip\n$expected  BiliPai-Windows.zip", "BiliPai-Windows.zip") }
        DesktopUpdater.verifyChecksum(expected.uppercase(), expected)
        assertFailsWith<IllegalArgumentException> { DesktopUpdater.verifyChecksum("cd".repeat(32), expected) }
    }

    @Test
    fun `automatic checks debounce and recover from clock reversal`() {
        val previous = 100_000_000L
        assertFalse(DesktopUpdater.shouldCheck(previous, previous + 1))
        assertTrue(DesktopUpdater.shouldCheck(previous, previous + DesktopUpdater.AUTO_CHECK_INTERVAL_MS))
        assertTrue(DesktopUpdater.shouldCheck(previous, previous - 1))
        assertTrue(DesktopUpdater.shouldCheck(0, previous))
    }

    @Test
    fun `safe distribution extracts beside old install`() {
        val directory = Files.createTempDirectory("bilipai-update-valid")
        val zip = createZip(directory, listOf("BiliPai Windows/BiliPai Windows.exe", "BiliPai Windows/app/runtime.dll"))
        val target = Files.createDirectory(directory.resolve("new-install"))
        val executable = SafeUpdateZip.extract(zip, target, "BiliPai Windows.exe")
        assertEquals(target.resolve("BiliPai Windows/BiliPai Windows.exe"), executable)
        assertTrue(Files.isRegularFile(target.resolve("BiliPai Windows/app/runtime.dll")))
    }

    @Test
    fun `zip traversal absolute paths and Windows device paths are rejected`() {
        listOf("../outside.exe", "C:/outside.exe", "\\\\server\\share\\file.exe", "app/../outside.exe", "app/NUL.exe", "app/file.exe:payload", "app/file. ")
            .forEach { path -> assertFailsWith<IllegalArgumentException>(path) { SafeUpdateZip.safeRelativeName(path) } }
    }

    @Test
    fun `zip unix symlink metadata is rejected before extraction`() {
        val directory = Files.createTempDirectory("bilipai-update-symlink")
        val zip = createZip(directory, listOf("BiliPai Windows.exe"))
        val bytes = Files.readAllBytes(zip)
        val central = (0 until bytes.size - 4).first { index ->
            bytes[index] == 0x50.toByte() && bytes[index + 1] == 0x4b.toByte() && bytes[index + 2] == 1.toByte() && bytes[index + 3] == 2.toByte()
        }
        // Unix type S_IFLNK (0xa000) in the high 16 bits of external attributes.
        bytes[central + 41] = 0xa0.toByte()
        Files.write(zip, bytes)
        val target = Files.createDirectory(directory.resolve("new-install"))
        assertFailsWith<IllegalArgumentException> { SafeUpdateZip.extract(zip, target, "BiliPai Windows.exe") }
        assertFalse(Files.exists(target.resolve("BiliPai Windows.exe")))
    }

    @Test
    fun `zip case collisions cannot overwrite another entry on Windows`() {
        val directory = Files.createTempDirectory("bilipai-update-collision")
        val zip = createZip(directory, listOf("BiliPai Windows.exe", "bilipai windows.EXE"))
        assertFailsWith<IllegalArgumentException> { SafeUpdateZip.extract(zip, Files.createDirectory(directory.resolve("new-install")), "BiliPai Windows.exe") }
    }

    private fun createZip(directory: Path, paths: List<String>): Path {
        val zip = directory.resolve("update.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { output ->
            paths.forEach { path ->
                output.putNextEntry(ZipEntry(path))
                output.write("test-package-content".toByteArray())
                output.closeEntry()
            }
        }
        return zip
    }

    private fun releaseMetadata(version: String, includeChecksum: Boolean = true): String {
        val prefix = "https://github.com/owner/BiliPai-Windows/releases/download/windows-v$version"
        val checksum = if (includeChecksum) """,{"id":3,"name":"BiliPai-Windows.zip.sha256","size":100,"browser_download_url":"$prefix/BiliPai-Windows.zip.sha256"}""" else ""
        return """[{"id":1,"tag_name":"windows-v$version","html_url":"https://github.com/owner/BiliPai-Windows/releases/tag/windows-v$version","assets":[{"id":2,"name":"BiliPai-Windows.zip","size":1000,"browser_download_url":"$prefix/BiliPai-Windows.zip"}$checksum]}]"""
    }
}
