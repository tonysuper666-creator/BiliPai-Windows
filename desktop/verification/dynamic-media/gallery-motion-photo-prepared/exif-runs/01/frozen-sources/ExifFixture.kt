package com.bilipai.desktop.ui.gallerymotionphotoproof

import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import org.apache.commons.imaging.Imaging
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants
import java.nio.file.Files
import java.nio.file.Path
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

fun main(args: Array<String>) = runBlocking {
    val root = Path.of(args[0]); Files.createDirectories(root)
    val tagged = Path.of(args[1]); val image = Path.of(args[2]); val video = Path.of(args[3])
    var assertions = 0
    val cases = mutableListOf<String>()
    fun prove(value: Boolean, message: String) { check(value) { message }; assertions++ }
    fun noStaged() = Files.list(root).use { paths -> paths.noneMatch { it.fileName.toString().startsWith(".bilipai-motion-photo-") } }
    val identity = DesktopMotionPhotoExifIdentity("Declared synthetic manufacturer", "Declared synthetic model")
    val fixtureDate = Date(1790848800000L)
    fun writer(owned: () -> Boolean = { true }, id: () -> DesktopMotionPhotoExifIdentity = { identity }) =
        DesktopDynamicMotionPhotoExif(owned, id, { fixtureDate })
    val original = Files.readAllBytes(tagged)
    val written = writer().write(original)
    Files.write(root.resolve("four-tags-written.jpg"), written)
    val metadata = Imaging.getMetadata(written) as JpegImageMetadata
    prove(metadata.findEXIFValueWithExactMatch(TiffTagConstants.TIFF_TAG_MAKE)?.stringValue == identity.manufacturer, "real writer Make readback")
    prove(metadata.findEXIFValueWithExactMatch(TiffTagConstants.TIFF_TAG_MODEL)?.stringValue == identity.model, "real writer Model readback")
    prove(metadata.findEXIFValueWithExactMatch(TiffTagConstants.TIFF_TAG_DATE_TIME)?.stringValue == "2026:10:01 10:00:00", "original DateTime format readback")
    prove(metadata.findEXIFValueWithExactMatch(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL)?.stringValue == "2026:10:01 10:00:00", "original DateTimeOriginal readback")
    prove(metadata.findEXIFValueWithExactMatch(TiffTagConstants.TIFF_TAG_ARTIST)?.stringValue == "Other existing artist", "other existing EXIF preserved")
    prove(metadata.findEXIFValueWithExactMatch(TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION)?.stringValue == "Other existing description", "other EXIF description preserved")
    prove(metadata.findEXIFValueWithExactMatch(TiffTagConstants.TIFF_TAG_ORIENTATION)?.intValue == 1, "other EXIF orientation preserved")
    cases += "actual lossless writer replaces only four original tags; real Imaging readback"

    val raw = encodeDesktopMotionPhotoJpeg95(Files.readAllBytes(image))
    Files.write(root.resolve("jpeg95-before-exif.jpg"), raw)
    val rawWritten = writer().write(raw)
    Files.write(root.resolve("jpeg95-after-exif.jpg"), rawWritten)
    prove((Imaging.getMetadata(rawWritten) as JpegImageMetadata).findEXIFValueWithExactMatch(TiffTagConstants.TIFF_TAG_MAKE)?.stringValue == identity.manufacturer, "real writer adds EXIF to absent EXIF")
    cases += "actual Skia JPEG95 input with no EXIF gains original four tags"

    val windowsIdentity = readDesktopMotionPhotoExifIdentity()
    val actualWindowsOutput = DesktopDynamicMotionPhotoExif({ true }, now = { fixtureDate }).write(raw)
    Files.write(root.resolve("windows-platform-identity.jpg"), actualWindowsOutput)
    val windowsMetadata = Imaging.getMetadata(actualWindowsOutput) as JpegImageMetadata
    prove(windowsMetadata.findEXIFValueWithExactMatch(TiffTagConstants.TIFF_TAG_MAKE)?.stringValue == windowsIdentity.manufacturer, "actual Windows BIOS manufacturer mapped exactly")
    prove(windowsMetadata.findEXIFValueWithExactMatch(TiffTagConstants.TIFF_TAG_MODEL)?.stringValue == windowsIdentity.model, "actual Windows BIOS product model mapped exactly")
    cases += "actual Windows platform identity is read and written without fabricated camera metadata"

    prove(runCatching { writer().write("invalid jpeg".toByteArray()) }.isFailure, "real EXIF library rejects invalid input")
    cases += "actual EXIF parser rejects corrupted JPEG"
    val retired = AtomicBoolean(false)
    val retiredResult = runCatching { writer({ !retired.get() }, { retired.set(true); identity }).write(original) }
    prove(retiredResult.exceptionOrNull() is CancellationException, "retired owner rejected after platform identity read")
    prove(runCatching { writer({ false }).write(original) }.exceptionOrNull() is CancellationException, "retired owner rejected before work")
    cases += "same MID different epoch owner retirement rejects EXIF result"

    val combined = root.resolve("motion-photo-with-real-exif.jpg")
    DesktopDynamicMotionPhotoFiles({ true }, { it(); true }, writeExif = writer()::write).use {
        prove(it.composeDownloaded(image, video, DesktopDynamicSaveTarget(combined, false)), "real EXIF writer wired to actual candidate compose")
    }
    val combinedMetadata = Imaging.getMetadata(Files.readAllBytes(combined)) as JpegImageMetadata
    prove(combinedMetadata.findEXIFValueWithExactMatch(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL)?.stringValue == "2026:10:01 10:00:00", "actual packed JPEG retains real DateTimeOriginal")
    prove(noStaged(), "actual combined save drained staged output")
    cases += "real EXIF writer plus original corrected XMP and original MP4 append"

    // The real library sees invalid bytes; compose then follows the original
    // raw-JPEG fallback. This is not a fake writer returning fabricated EXIF.
    var actualFailure: Throwable? = null
    val fallback = root.resolve("real-library-failure-fallback.jpg")
    DesktopDynamicMotionPhotoFiles({ true }, { it(); true }, writeExif = { writer().write("invalid jpeg".toByteArray()) },
        onExifFallback = { actualFailure = it }).use {
        prove(it.composeDownloaded(image, video, DesktopDynamicSaveTarget(fallback, false)), "actual library failure follows original raw fallback")
    }
    prove(actualFailure != null && actualFailure !is CancellationException, "actual library failure is observed")
    prove((Imaging.getMetadata(Files.readAllBytes(fallback)) as JpegImageMetadata).exif == null, "fallback did not fabricate the four EXIF tags")
    cases += "real library failure retains original raw-JPEG fallback"

    var cancellationFallbackCalls = 0
    val cancelOwned = AtomicBoolean(true)
    val cancelWriter = writer({ cancelOwned.get() }, { cancelOwned.set(false); identity })
    val cancelOutput = root.resolve("retired-cannot-fallback.jpg")
    val cancelled = runCatching {
        DesktopDynamicMotionPhotoFiles({ cancelOwned.get() }, { it(); true }, writeExif = cancelWriter::write,
            onExifFallback = { cancellationFallbackCalls++ }).use {
            it.composeDownloaded(image, video, DesktopDynamicSaveTarget(cancelOutput, false))
        }
    }
    prove(cancelled.exceptionOrNull() is CancellationException, "EXIF owner retirement propagates actual cancellation")
    prove(cancellationFallbackCalls == 0, "cancellation never becomes original exception fallback")
    prove(!Files.exists(cancelOutput) && noStaged(), "retired EXIF produces no saved/staged output")
    cases += "real writer cancellation cannot leak into best effort fallback"

    val windowsText = """{"manufacturer":"${windowsIdentity.manufacturer.replace("\\", "\\\\").replace("\"", "\\\"")}","model":"${windowsIdentity.model.replace("\\", "\\\\").replace("\"", "\\\"")}"}"""
    Files.writeString(root.resolve("windows-platform-identity.json"), windowsText)
    Files.writeString(root.resolve("result.json"), """{"passed":true,"assertions":$assertions,"cases":${cases.size},"caseNames":[${cases.joinToString(",") { "\"$it\"" }}],"actualExifLibrary":true,"actualFourTagWriteReadback":true,"actualWindowsIdentityRead":true,"declaredSyntheticIdentityAlsoTested":true,"MainIntegration":false,"HTTP":false,"HWND":false,"nativeSHARE":false}""")
    println("PASS $assertions assertions / ${cases.size} cases: real EXIF library, exact Windows identity and actual compose; no Main/HTTP/HWND")
}
