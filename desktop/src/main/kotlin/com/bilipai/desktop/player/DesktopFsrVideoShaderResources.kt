package com.bilipai.desktop.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** The original compiled FSR strings are the only algorithm source; no downloads or alternate filter chains. */
internal class DesktopFsrVideoShaderResources(private val root: Path) {
    suspend fun resolve(program: DesktopFsrHookProgram): List<Path> = withContext(Dispatchers.IO) {
        Files.createDirectories(root)
        val canonical = root.toRealPath()
        val key = digest((program.easu + "\n" + program.rcas).toByteArray(Charsets.UTF_8))
        val directory = canonical.resolve(key)
        Files.createDirectories(directory)
        check(!Files.isSymbolicLink(directory) && directory.toRealPath().startsWith(canonical)) { "FSR shader cache folder is invalid." }
        listOf("bilipai-fsr-easu.glsl" to program.easu, "bilipai-fsr-rcas.glsl" to program.rcas).map { (name, text) ->
            val target = directory.resolve(name)
            check(!Files.isSymbolicLink(target)) { "FSR shader cache file is invalid." }
            val bytes = text.replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
            require(bytes.size in 1..65_536)
            val expected = digest(bytes)
            if (!Files.isRegularFile(target) || Files.size(target) > 65_536 || digest(Files.readAllBytes(target)) != expected) {
                val temporary = Files.createTempFile(directory, ".fsr-", ".tmp")
                try {
                    Files.write(temporary, bytes)
                    try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                    catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
                } finally { Files.deleteIfExists(temporary) }
            }
            target
        }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
