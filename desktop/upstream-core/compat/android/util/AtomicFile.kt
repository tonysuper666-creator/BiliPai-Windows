package android.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Desktop implementation of android.util.AtomicFile with the Android contract: writes go to `<name>.new` and replace
 * the base file only in [finishWrite] (after fsync); [failWrite] discards them; a reader never sees a partial write.
 * A pre-API-29 `<name>.bak` left by an interrupted Android-style write is restored on the next access.
 */
class AtomicFile(val baseFile: File) {
    private val newFile = File(baseFile.path + ".new")
    private val legacyBackupFile = File(baseFile.path + ".bak")

    private fun restoreLegacyBackup() {
        if (legacyBackupFile.exists()) {
            if (baseFile.exists()) legacyBackupFile.delete() else if (!legacyBackupFile.renameTo(baseFile))
                throw IOException("Failed to restore $legacyBackupFile to $baseFile")
        }
    }

    fun delete() { baseFile.delete(); newFile.delete(); legacyBackupFile.delete() }

    fun startWrite(): FileOutputStream {
        restoreLegacyBackup()
        return try { FileOutputStream(newFile) } catch (_: IOException) {
            val parent = newFile.parentFile
            if (parent != null && !parent.mkdirs() && !parent.isDirectory) throw IOException("Failed to create directory for $newFile")
            FileOutputStream(newFile)
        }
    }

    fun finishWrite(stream: FileOutputStream?) {
        if (stream == null) return
        stream.fd.sync()
        stream.close()
        java.nio.file.Files.move(newFile.toPath(), baseFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    fun failWrite(stream: FileOutputStream?) {
        if (stream == null) return
        runCatching { stream.close() }
        if (!newFile.delete()) Log.e("AtomicFile", "Failed to delete new file $newFile")
    }

    fun openRead(): FileInputStream {
        restoreLegacyBackup()
        // An interrupted write leaves only the .new file; Android ignores it and reads the last finished version.
        if (newFile.exists() && baseFile.exists()) newFile.delete()
        return FileInputStream(baseFile)
    }

    fun readFully(): ByteArray = openRead().use { it.readBytes() }
    fun exists(): Boolean = baseFile.exists() || legacyBackupFile.exists()
}
