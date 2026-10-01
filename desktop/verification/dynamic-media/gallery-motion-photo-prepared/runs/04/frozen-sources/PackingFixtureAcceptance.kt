package com.bilipai.desktop.ui.gallerymotionphotoproof

import com.android.purebilibili.core.util.*
import com.android.purebilibili.feature.dynamic.components.desktopOriginalMotionPhotoJpeg
import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

fun main(args: Array<String>) = runBlocking {
    val root = Path.of(args[0]); Files.createDirectories(root)
    val image = Path.of(args[1]); val video = Path.of(args[2]); val tagged = Path.of(args[3])
    var assertions = 0
    val cases = mutableListOf<String>()
    fun checkProof(value: Boolean, message: String) { check(value) { message }; assertions++ }
    fun noStaged() = Files.list(root).use { files -> files.noneMatch { it.fileName.toString().startsWith(".bilipai-motion-photo-") } }
    fun binding(owned: () -> Boolean = { true }, commit: ((() -> Unit) -> Boolean) = { it(); true },
        encoder: (ByteArray) -> ByteArray = ::encodeDesktopMotionPhotoJpeg95,
        exif: ((ByteArray) -> ByteArray)? = null, fallback: (Throwable?) -> Unit = {}) =
        DesktopDynamicMotionPhotoFiles(owned, commit, encoder, exif, fallback)

    checkProof(desktopOriginalSingleGalleryResult(true, "primary", listOf("clip")) == "primary", "single data before first clip")
    checkProof(desktopOriginalSingleGalleryResult(true, null, listOf("clip", "other")) == "clip", "single first clip fallback")
    checkProof(desktopOriginalSingleGalleryResult(false, "primary", listOf("clip")) == null, "single cancelled returns null")
    checkProof(desktopOriginalSingleGalleryResult(true, null, null) == null, "single empty returns null")
    checkProof(runCatching { desktopOriginalMultipleGalleryResult(true, "a", null, 1) }.isFailure, "original multiple constructor requires maxItems > 1")
    val result = desktopOriginalMultipleGalleryResult(true, "a", listOf("a", "b", "a", "c", "d"), 3)
    checkProof(result == listOf("a", "b", "c"), "original selection order/dedupe/take")
    checkProof(desktopOriginalMultipleGalleryResult(false, "a", listOf("b"), 9).isEmpty(), "cancelled gallery returns empty")
    checkProof(desktopOriginalMultipleGalleryResult(true, null, null, 9).isEmpty(), "empty selection")
    checkProof(desktopOriginalGalleryMimeType(DesktopGalleryVisualMediaType.ImageOnly) == "image/*", "image MIME")
    checkProof(desktopOriginalGalleryMimeType(DesktopGalleryVisualMediaType.VideoOnly) == "video/*", "video MIME")
    checkProof(desktopOriginalGalleryMimeType(DesktopGalleryVisualMediaType.ImageAndVideo) == null, "mixed MIME fallback")
    checkProof(desktopOriginalGalleryMimeType(DesktopGalleryVisualMediaType.SingleMimeType("image/webp")) == "image/webp", "single MIME")
    cases += "original single fallback, multiple constructor/order/distinct/take, cancellation and MIME"

    val output = root.resolve("motion-photo.jpg")
    var fallbackCount = 0
    binding(fallback = { fallbackCount++ }).use { files ->
        checkProof(files.composeDownloaded(image, video, DesktopDynamicSaveTarget(output, false)), "compose actual JPEG/MP4")
    }
    checkProof(Files.size(output) > Files.size(video), "JPEG+APP1 output precedes full video")
    val bytes = Files.readAllBytes(output); val mp4 = Files.readAllBytes(video)
    checkProof(bytes.takeLast(mp4.size).toByteArray().contentEquals(mp4), "MP4 tail unchanged")
    checkProof(fallbackCount == 1, "explicit unbound EXIF uses original fallback")
    checkProof(noStaged(), "successful save drains staged file")
    cases += "real Skia JPEG95 + original packing + bounded actual MP4 tail"

    // Already-EXIF-tagged fixture JPEG is passed as the input to the original
    // pure packing function. It tests insertion, not an implemented EXIF writer.
    val exifBytes = Files.readAllBytes(tagged)
    val packedExif = desktopOriginalMotionPhotoJpeg(exifBytes, Files.size(video))
    val exifOutput = root.resolve("packing-preserves-input-exif.jpg")
    Files.newOutputStream(exifOutput).use { stream -> stream.write(packedExif); stream.write(mp4) }
    checkProof(packedExif.toString(Charsets.ISO_8859_1).indexOf("Exif") < packedExif.toString(Charsets.ISO_8859_1).indexOf("http://ns.adobe.com/xap/1.0/"), "XMP follows existing EXIF APP1")
    checkProof(packedExif.size > exifBytes.size, "XMP segment inserted")
    cases += "actual tagged JPEG input: original EXIF-before-XMP insertion"

    val sentinel = root.resolve("existing.jpg"); Files.writeString(sentinel, "original-user-file")
    val rejectedExisting = runCatching { binding().use { it.composeDownloaded(image, video, DesktopDynamicSaveTarget(sentinel, false)) } }
    checkProof(rejectedExisting.isFailure, "no-overwrite admission")
    checkProof(Files.readString(sentinel) == "original-user-file", "existing user bytes unchanged")
    checkProof(noStaged(), "rejected destination has no staged file")
    binding().use { checkProof(it.composeDownloaded(image, video, DesktopDynamicSaveTarget(sentinel, true)), "explicit replace") }
    checkProof(Files.size(sentinel) == Files.size(output), "replace commits correct combined length")
    cases += "selected target no-overwrite and explicit replace"

    val denied = root.resolve("denied.jpg")
    val deniedResult = runCatching { binding(commit = { false }).use { it.composeDownloaded(image, video, DesktopDynamicSaveTarget(denied, false)) } }
    checkProof(deniedResult.exceptionOrNull() is CancellationException, "atomic owner admission rejects old epoch")
    checkProof(!Files.exists(denied), "rejected owner produces no destination")
    checkProof(noStaged(), "rejected owner drains staged bytes")
    cases += "same MID owner rotation rejects final commit"

    val checkpointCalls = AtomicInteger()
    val retired = root.resolve("retired-mid-stream.jpg")
    val retiredResult = runCatching { binding(owned = { checkpointCalls.incrementAndGet() < 8 }).use {
        it.composeDownloaded(image, video, DesktopDynamicSaveTarget(retired, false)) } }
    checkProof(retiredResult.exceptionOrNull() is CancellationException, "owner retires during bounded copy")
    checkProof(checkpointCalls.get() >= 8, "mid-copy owner check occurred")
    checkProof(!Files.exists(retired) && noStaged(), "mid-copy retirement leaves no destination/staged file")
    cases += "retired task rejects during 64KiB stream and drains"

    val closed = binding(); closed.close()
    val closedResult = runCatching { closed.composeDownloaded(image, video, DesktopDynamicSaveTarget(root.resolve("closed.jpg"), false)) }
    checkProof(closedResult.exceptionOrNull() is CancellationException, "closed binding rejects before decoding")
    checkProof(!Files.exists(root.resolve("closed.jpg")), "closed operation produced nothing")
    cases += "closed owner rejects before work"

    var fallbackFailure: Throwable? = null
    val failedExif = root.resolve("exif-failure-fallback.jpg")
    binding(exif = { throw IllegalArgumentException("controlled platform EXIF failure") }, fallback = { fallbackFailure = it }).use {
        checkProof(it.composeDownloaded(image, video, DesktopDynamicSaveTarget(failedExif, false)), "original EXIF failure fallback") }
    checkProof(fallbackFailure is IllegalArgumentException, "platform EXIF failure explicitly observed")
    checkProof(Files.readAllBytes(failedExif).contentEquals(bytes), "fallback retains JPEG95 and original package")
    cases += "controlled EXIF writer failure retains original raw-JPEG fallback"

    val emptyVideo = root.resolve("empty.mp4"); Files.write(emptyVideo, byteArrayOf())
    checkProof(runCatching { binding().use { it.composeDownloaded(image, emptyVideo, DesktopDynamicSaveTarget(root.resolve("empty.jpg"), false)) } }.isFailure, "empty video rejected")
    val corruptImage = root.resolve("corrupt-image.bin"); Files.writeString(corruptImage, "not an image")
    checkProof(runCatching { binding().use { it.composeDownloaded(corruptImage, video, DesktopDynamicSaveTarget(root.resolve("corrupt.jpg"), false)) } }.isFailure, "actual codec rejects corrupt source")
    checkProof(!Files.exists(root.resolve("empty.jpg")) && !Files.exists(root.resolve("corrupt.jpg")) && noStaged(), "input failures have no destination/staged file")
    cases += "actual codec corruption and empty original video rejection"

    val oversized = root.resolve("oversized.mp4")
    java.io.RandomAccessFile(oversized.toFile(), "rw").use { it.setLength(200L * 1024 * 1024 + 1) }
    checkProof(runCatching { binding().use { it.composeDownloaded(image, oversized, DesktopDynamicSaveTarget(root.resolve("oversized.jpg"), false)) } }.isFailure, "existing desktop 200MiB bound enforced without allocation")
    checkProof(!Files.exists(root.resolve("oversized.jpg")) && noStaged(), "budget failure drains")
    Files.delete(oversized)
    cases += "desktop video resource budget checked before heap decoding"

    val cancelledCommit = AtomicInteger()
    val cancelledParent = Job().apply { cancel() }
    val cancelledJob = CoroutineScope(coroutineContext + cancelledParent).launch {
        binding(commit = { cancelledCommit.incrementAndGet(); it(); true }).use {
            it.composeDownloaded(image, video, DesktopDynamicSaveTarget(root.resolve("cancelled.jpg"), false)) }
    }
    cancelledJob.join()
    checkProof(cancelledCommit.get() == 0 && !Files.exists(root.resolve("cancelled.jpg")), "pre-cancelled task invokes no commit")
    checkProof(noStaged(), "all cases drained staged output")
    cases += "pre-cancelled parent performs no save"

    Files.writeString(root.resolve("result.json"), """{"passed":true,"assertions":$assertions,"cases":${cases.size},"caseNames":[${cases.joinToString(",") { "\"$it\"" }}],"realSyntheticMedia":true,"realSkiaCodec":true,"originalPackingBodyExceptApprovedThreeXmpAttributeLines":true,"EXIFWriterImplemented":false,"syntheticExifInputOnly":true,"MainIntegration":false,"HTTP":false,"HWND":false,"nativeSHARE":false}""")
    println("PASS $assertions assertions / ${cases.size} cases: original packing/gallery and actual synthetic media; no Main/HTTP/HWND")
}
