package com.bilipai.desktop.platform

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.nio.file.Path

/** Windows boundary only. Resolves the current user's redirected Pictures/BiliPai;
 * never creates directories, reads preferences, selects a folder or saves a file.
 * Failure belongs to the existing chooser/error flow; no USERPROFILE path fallback.
 */
internal object DesktopWindowsImageSaveDirectory {
    fun resolveDefault(): Result<Path> = resolveDefault(
        windows = Platform.isWindows(),
        native = { JnaWindowsKnownFolderNative },
    )

    internal fun resolveDefault(
        windows: Boolean,
        native: () -> DesktopWindowsKnownFolderNative,
    ): Result<Path> {
        if (!windows) return Result.failure(DesktopKnownFolderFailure("platform"))
        return try {
            val api = native()
            val comResult = api.initializeCom(null, COINIT_MULTITHREADED)
            // RPC_E_CHANGED_MODE means this thread already uses another COM apartment.
            // Keep that existing apartment; this synchronous Shell function needs COM,
            // not a particular apartment. Do not uninitialize someone else's apartment.
            if (comResult < 0 && comResult != RPC_E_CHANGED_MODE) {
                throw DesktopKnownFolderFailure("CoInitializeEx", comResult)
            }
            val ownsInitialization = comResult >= 0
            try {
                val output = PointerByReference()
                try {
                    // SDK GUID layout: DWORD, WORD, WORD, BYTE[8], total 16 bytes.
                    // FOLDERID_Pictures, not FOLDERID_PicturesLibrary.
                    Memory(16).use { folderId ->
                        folderId.setInt(0, 0x33e28130)
                        folderId.setShort(4, 0x4e1e.toShort())
                        folderId.setShort(6, 0x4676.toShort())
                        folderId.write(8, byteArrayOf(
                            0x83.toByte(), 0x5a, 0x98.toByte(), 0x39,
                            0x5c, 0x3b, 0xc3.toByte(), 0xbb.toByte(),
                        ), 0, 8)
                        val hr = api.getKnownFolderPath(folderId, KF_FLAG_DEFAULT, null, output)
                        if (hr != S_OK) throw DesktopKnownFolderFailure("SHGetKnownFolderPath", hr)
                        val pointer = output.value ?: throw DesktopKnownFolderFailure("empty-result")
                        val value = api.readWideString(pointer)
                        if (value.isBlank()) throw DesktopKnownFolderFailure("empty-path")
                        val pictures = try { Path.of(value) } catch (_: Exception) {
                            throw DesktopKnownFolderFailure("invalid-path")
                        }
                        if (!pictures.isAbsolute) throw DesktopKnownFolderFailure("relative-path")
                        Result.success(pictures.resolve("BiliPai"))
                    }
                } finally {
                    // The SDK requires release even when SHGetKnownFolderPath fails.
                    output.value?.let(api::freeTaskMemory)
                }
            } finally {
                // Both S_OK and S_FALSE add a COM initialization reference.
                if (ownsInitialization) api.uninitializeCom()
            }
        } catch (failure: DesktopKnownFolderFailure) {
            Result.failure(failure)
        } catch (_: LinkageError) {
            Result.failure(DesktopKnownFolderFailure("native-binding"))
        } catch (_: Exception) {
            Result.failure(DesktopKnownFolderFailure("native-call"))
        }
    }

    internal const val S_OK = 0
    internal const val COINIT_MULTITHREADED = 0
    internal const val KF_FLAG_DEFAULT = 0
    internal val RPC_E_CHANGED_MODE: Int = 0x80010106.toInt()
}

/** A safe operation/HRESULT only; returned native paths never enter error text. */
internal class DesktopKnownFolderFailure(val operation: String, val hresult: Int? = null) :
    Exception(if (hresult == null) "Known folder query failed ($operation)"
    else "Known folder query failed ($operation, 0x${hresult.toUInt().toString(16).padStart(8, '0')})")

internal interface DesktopWindowsKnownFolderNative {
    fun initializeCom(reserved: Pointer?, flags: Int): Int
    fun uninitializeCom()
    fun getKnownFolderPath(folderId: Pointer, flags: Int, token: Pointer?, output: PointerByReference): Int
    fun readWideString(pointer: Pointer): String
    fun freeTaskMemory(pointer: Pointer)
}

/** JNA core already shipped by the product. No JNA-platform dependency. */
internal object JnaWindowsKnownFolderNative : DesktopWindowsKnownFolderNative {
    private interface Shell32 : StdCallLibrary {
        fun SHGetKnownFolderPath(folderId: Pointer, flags: Int, token: Pointer?, output: PointerByReference): Int
    }
    private interface Ole32 : StdCallLibrary {
        fun CoInitializeEx(reserved: Pointer?, flags: Int): Int
        fun CoUninitialize()
        fun CoTaskMemFree(pointer: Pointer)
    }
    private val shell: Shell32 by lazy { Native.load("shell32", Shell32::class.java) }
    private val ole: Ole32 by lazy { Native.load("ole32", Ole32::class.java) }

    override fun initializeCom(reserved: Pointer?, flags: Int) = ole.CoInitializeEx(reserved, flags)
    override fun uninitializeCom() = ole.CoUninitialize()
    override fun getKnownFolderPath(folderId: Pointer, flags: Int, token: Pointer?, output: PointerByReference) =
        shell.SHGetKnownFolderPath(folderId, flags, token, output)
    override fun readWideString(pointer: Pointer): String = pointer.getWideString(0)
    override fun freeTaskMemory(pointer: Pointer) = ole.CoTaskMemFree(pointer)
}
