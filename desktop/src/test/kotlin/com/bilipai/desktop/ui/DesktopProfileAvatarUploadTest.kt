package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.store.AccountSessionIdentity
import com.android.purebilibili.data.model.response.ProfileUpdateResponse
import com.android.purebilibili.feature.login.QrAuthorizationSession
import com.android.purebilibili.feature.profile.ProfileAccountRepository
import kotlinx.coroutines.test.runTest
import okhttp3.MultipartBody
import okio.Buffer
import java.io.RandomAccessFile
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The avatar file the Windows dialog returns and the upstream upload request built from it. No window or network. */
class DesktopProfileAvatarUploadTest {
    // macOS's temporary directory is reached through the /var link; the reader rejects links, so use the real path.
    private val directory: Path = Files.createTempDirectory("profile-avatar").toRealPath()

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    private fun png(width: Int, height: Int): ByteArray =
        org.jetbrains.skia.Surface.makeRasterN32Premul(width, height).use { surface ->
            surface.canvas.clear(0xFF3366CC.toInt())
            surface.makeImageSnapshot().use { image ->
                image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { it.bytes }
            }
        }

    private fun file(name: String, bytes: ByteArray): String =
        directory.resolve(name).also { Files.write(it, bytes) }.toUri().toString()

    private fun read(uri: String): ByteArray = readDesktopProfileAvatarImage(uri) {}

    private fun rejection(uri: String): String = assertFailsWith<IllegalArgumentException> { read(uri) }.message.orEmpty()

    @Test
    fun aChosenPngIsReadAsIs() {
        val bytes = png(40, 30)
        assertContentEquals(bytes, read(file("face.png", bytes)))
    }

    @Test
    fun aLinkOrNonFileAddressIsRejectedWithAMessage() {
        assertEquals("请选择本机上的图片文件", rejection("https://i0.hdslb.com/face.png"))
        assertEquals("请选择本机上的图片文件", rejection("not a uri"))
    }

    @Test
    fun aMissingOrEmptyFileIsRejectedWithAMessage() {
        assertEquals("找不到所选图片，请重新选择", rejection(directory.resolve("gone.png").toUri().toString()))
        assertEquals("所选图片是空文件，请重新选择", rejection(file("empty.png", ByteArray(0))))
    }

    @Test
    fun aFileOverTwentyMegabytesIsRejectedWithAMessage() {
        val big = directory.resolve("big.png")
        RandomAccessFile(big.toFile(), "rw").use { it.setLength(MAX_AVATAR_BYTES + 1) }
        assertEquals("图片文件过大（最大 20 MB），请换一张图片", rejection(big.toUri().toString()))
    }

    @Test
    fun aFileThatIsNotAnImageIsRejectedWithAMessage() {
        assertEquals("无法识别这张图片，请选择 JPG、PNG、WebP 或 BMP 图片",
            rejection(file("notes.png", "not an image".toByteArray())))
    }

    @Test
    fun aRetiredPageStopsTheRead() {
        val uri = file("face.png", png(8, 8))
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            readDesktopProfileAvatarImage(uri) { throw kotlinx.coroutines.CancellationException("retired") }
        }
    }

    private class Upload(val csrf: String, val cookie: String, val face: MultipartBody.Part)

    private fun repository(
        response: ProfileUpdateResponse,
        uploads: MutableList<Upload>,
        session: QrAuthorizationSession = QrAuthorizationSession("sess", "jct", 42L, "buvid"),
        image: ByteArray = png(900, 600),
    ): ProfileAccountRepository {
        val api = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader, arrayOf(BilibiliApi::class.java)) { _, method, args ->
            check(method.name == "updateMemberAvatar") { "unexpected request ${method.name}" }
            uploads += Upload(args[0] as String, args[4] as String, args[3] as MultipartBody.Part)
            response
        } as BilibiliApi
        return ProfileAccountRepository(api, { session }, { image })
    }

    @Test
    fun theUploadIsAJpegSquareOfAtMost512PixelsWithTheAccountCredentials() = runTest {
        val uploads = mutableListOf<Upload>()
        repository(ProfileUpdateResponse(0), uploads).uploadAvatar(AccountSessionIdentity(), "file:///face.png")
        val upload = uploads.single()
        assertEquals("jct", upload.csrf)
        assertEquals("SESSDATA=sess; bili_jct=jct; DedeUserID=42", upload.cookie)
        val sent = Buffer().also { upload.face.body.writeTo(it) }.readByteArray()
        org.jetbrains.skia.Image.makeFromEncoded(sent).use { image ->
            assertEquals(512, image.width)
            assertEquals(512, image.height)
        }
        assertTrue(sent[0] == 0xFF.toByte() && sent[1] == 0xD8.toByte(), "JPEG")
    }

    @Test
    fun aRejectedUploadReportsBilibilisMessage() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            repository(ProfileUpdateResponse(-400, "头像格式不正确"), mutableListOf())
                .uploadAvatar(AccountSessionIdentity(), "file:///face.png")
        }
        assertEquals("头像格式不正确", error.message)
    }

    @Test
    fun aRejectedUploadWithoutAMessageReportsTheCode() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            repository(ProfileUpdateResponse(-101), mutableListOf()).uploadAvatar(AccountSessionIdentity(), "file:///face.png")
        }
        assertEquals("头像上传失败（-101）", error.message)
    }

    @Test
    fun withoutASignedInAccountNothingIsSent() = runTest {
        val uploads = mutableListOf<Upload>()
        val error = assertFailsWith<IllegalArgumentException> {
            repository(ProfileUpdateResponse(0), uploads, QrAuthorizationSession("", "", null, "buvid"))
                .uploadAvatar(AccountSessionIdentity(), "file:///face.png")
        }
        assertEquals("请先登录", error.message)
        assertTrue(uploads.isEmpty())
    }

    @Test
    fun anUndecodableImageIsReportedAndNothingIsSent() = runTest {
        val uploads = mutableListOf<Upload>()
        val error = assertFailsWith<IllegalStateException> {
            repository(ProfileUpdateResponse(0), uploads, image = "nope".toByteArray())
                .uploadAvatar(AccountSessionIdentity(), "file:///face.png")
        }
        assertEquals("无法读取图片，请选择 JPG、PNG、WebP 或 BMP 图片", error.message)
        assertTrue(uploads.isEmpty())
    }
}
