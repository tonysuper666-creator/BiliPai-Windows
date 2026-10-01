package com.bilipai.desktop.ui

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import kotlinx.coroutines.CancellationException
import org.apache.commons.imaging.Imaging
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Values are supplied from the actual Windows machine or declared task
 * fixture. No camera brand/model is invented to encourage gallery recognition. */
internal data class DesktopMotionPhotoExifIdentity(val manufacturer: String, val model: String)

internal fun readDesktopMotionPhotoExifIdentity(): DesktopMotionPhotoExifIdentity {
    val key = "HARDWARE\\DESCRIPTION\\System\\BIOS"
    val manufacturer = Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, key, "SystemManufacturer").trim()
    val model = Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, key, "SystemProductName").trim()
    require(manufacturer.isNotEmpty() && model.isNotEmpty()) { "设备 EXIF 标识不可用" }
    return DesktopMotionPhotoExifIdentity(manufacturer, model)
}

/** Exact four Android ExifInterface tags mapped to Commons Imaging's real
 * lossless JPEG writer. Existing other EXIF fields and scan bytes are retained.
 * The caller runs it in its existing IO operation and supplies owner admission.
 * Failure remains the original saveMotionPhotoToGallery raw-JPEG fallback. */
internal class DesktopDynamicMotionPhotoExif(
    private val stillOwned: () -> Boolean,
    private val identity: () -> DesktopMotionPhotoExifIdentity = ::readDesktopMotionPhotoExifIdentity,
    private val now: () -> Date = { Date() },
) {
    private fun assertOwned() {
        if (!stillOwned()) throw CancellationException("Motion Photo EXIF owner retired")
    }
    fun write(jpeg: ByteArray): ByteArray {
        assertOwned()
        val values = identity()
        val timestamp = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(now())
        assertOwned()
        val metadata = Imaging.getMetadata(jpeg) as? JpegImageMetadata
        val outputSet = metadata?.exif?.outputSet ?: TiffOutputSet()
        val root = outputSet.orCreateRootDirectory
        val exif = outputSet.orCreateExifDirectory
        outputSet.removeField(TiffTagConstants.TIFF_TAG_MAKE)
        outputSet.removeField(TiffTagConstants.TIFF_TAG_MODEL)
        outputSet.removeField(TiffTagConstants.TIFF_TAG_DATE_TIME)
        outputSet.removeField(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL)
        root.add(TiffTagConstants.TIFF_TAG_MAKE, values.manufacturer)
        root.add(TiffTagConstants.TIFF_TAG_MODEL, values.model)
        root.add(TiffTagConstants.TIFF_TAG_DATE_TIME, timestamp)
        exif.add(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, timestamp)
        assertOwned()
        val result = ByteArrayOutputStream().use { output ->
            ExifRewriter().updateExifMetadataLossless(jpeg, output, outputSet)
            output.toByteArray()
        }
        assertOwned()
        return result
    }
}
