package com.bilipai.desktop.ui

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
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

/** Existing JNA core only; RegGetValueW reads two local REG_SZ values. No extra
 * JNA platform dependency, registry handle, account or subprocess is created. */
private interface MotionPhotoRegistry : StdCallLibrary {
    fun RegGetValueW(key: Pointer, subkey: WString, value: WString, flags: Int,
        type: IntByReference, data: Pointer?, bytes: IntByReference): Int
}

internal fun readDesktopMotionPhotoExifIdentity(): DesktopMotionPhotoExifIdentity {
    val key = "HARDWARE\\DESCRIPTION\\System\\BIOS"
    val registry = Native.load("Advapi32", MotionPhotoRegistry::class.java)
    val machine = Pointer.createConstant(0x80000002.toInt().toLong())
    fun read(name: String): String {
        val type = IntByReference()
        val size = IntByReference()
        check(registry.RegGetValueW(machine, WString(key), WString(name), 0x2, type, null, size) == 0)
        require(size.value in 2..65536 && type.value == 1) { "设备 EXIF 标识类型无效" }
        return Memory(size.value.toLong()).use { buffer ->
            check(registry.RegGetValueW(machine, WString(key), WString(name), 0x2, type, buffer, size) == 0)
            require(type.value == 1) { "设备 EXIF 标识类型无效" }
            buffer.getWideString(0).trim()
        }
    }
    val manufacturer = read("SystemManufacturer")
    val model = read("SystemProductName")
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
