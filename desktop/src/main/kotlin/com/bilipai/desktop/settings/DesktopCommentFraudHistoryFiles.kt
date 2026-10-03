package com.bilipai.desktop.settings

import com.bilipai.desktop.update.UpdateStorage
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.nio.channels.Channels
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/** One-shot capability issued only by the invoking leaf's real native selector. */
internal class DesktopCommentFraudFileSelection internal constructor(
    internal val path: Path,
    internal val parent: Path,
    internal val export: Boolean,
    internal val owner: Any,
    internal val expected: DesktopCommentFraudFileFingerprint?,
) {
    internal val used = AtomicBoolean(false)
}
internal data class DesktopCommentFraudFileFingerprint(
    val fileKey: Any?, val bytes: Long, val modifiedMillis: Long, val sha256: String,
)

/** UTF-8 JSON transport only. Original import/export schema and record policy stay in
 * DesktopOriginalCommentFraudRepository. Payload I/O runs outside Root admission;
 * only the final same-directory atomic rename enters its existing publication gate. */
internal class DesktopCommentFraudHistoryFiles(
    private val checkpoint: () -> Unit,
    private val commit: ((() -> Unit) -> Unit),
) {
    private val owner = Any()
    private fun checkedPath(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        require(absolute.parent != null && absolute.fileName.toString().isNotBlank()) { "Expected a local JSON file" }
        val parent = UpdateStorage.existingPathWithoutLinks(absolute.parent)
        require(Files.isDirectory(parent, NOFOLLOW_LINKS)) { "Comment record parent is not a directory" }
        val result = parent.resolve(absolute.fileName)
        if (Files.exists(result, NOFOLLOW_LINKS)) {
            require(UpdateStorage.existingPathWithoutLinks(result) == result && Files.isRegularFile(result, NOFOLLOW_LINKS)) {
                "Comment record file must be an ordinary local file"
            }
        }
        return result
    }
    private fun fingerprint(path: Path, caller: kotlin.coroutines.CoroutineContext): DesktopCommentFraudFileFingerprint? {
        caller.ensureActive(); checkpoint()
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        require(checkedPath(path) == path)
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newByteChannel(path, setOf<java.nio.file.OpenOption>(READ, NOFOLLOW_LINKS)).use { channel ->
            Channels.newInputStream(channel).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    caller.ensureActive(); checkpoint()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        }
        caller.ensureActive(); checkpoint()
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(before.fileKey() == after.fileKey() && before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime()) {
            "Selected comment record file changed while reading"
        }
        return DesktopCommentFraudFileFingerprint(after.fileKey(), after.size(), after.lastModifiedTime().toMillis(), digest.digest().joinToString("") { "%02x".format(it) })
    }
    suspend fun select(path: Path, export: Boolean): DesktopCommentFraudFileSelection {
        val caller = currentCoroutineContext()
        return withContext(Dispatchers.IO) {
            caller.ensureActive(); checkpoint()
            val selected = checkedPath(path)
            val expected = fingerprint(selected, caller)
            if (!export) require(expected != null) { "Selected comment record file does not exist" }
            caller.ensureActive(); checkpoint()
            DesktopCommentFraudFileSelection(selected, selected.parent, export, owner, expected)
        }
    }
    private fun claim(selection: DesktopCommentFraudFileSelection, export: Boolean) {
        checkpoint()
        require(selection.owner === owner && selection.export == export) { "Comment record file belongs to another operation" }
        check(selection.used.compareAndSet(false, true)) { "Comment record file selection was already consumed" }
    }
    private fun checkSelection(selection: DesktopCommentFraudFileSelection) {
        checkpoint()
        require(UpdateStorage.existingPathWithoutLinks(selection.parent) == selection.parent && checkedPath(selection.path) == selection.path) {
            "Selected comment record directory changed"
        }
    }
    suspend fun readUtf8(selection: DesktopCommentFraudFileSelection): String {
        val caller = currentCoroutineContext()
        return withContext(Dispatchers.IO) {
            caller.ensureActive(); claim(selection, false); checkSelection(selection)
            require(fingerprint(selection.path, caller) == selection.expected) { "Selected comment record file changed" }
            val text = Files.newByteChannel(selection.path, setOf<java.nio.file.OpenOption>(READ, NOFOLLOW_LINKS)).use { channel ->
                BufferedReader(InputStreamReader(Channels.newInputStream(channel), UTF_8)).use { reader ->
                    val buffer = CharArray(32 * 1024)
                    val content = StringBuilder()
                    while (true) {
                        caller.ensureActive(); checkpoint()
                        val count = reader.read(buffer)
                        if (count < 0) break
                        content.append(buffer, 0, count)
                    }
                    content.toString()
                }
            }
            caller.ensureActive(); checkSelection(selection)
            require(fingerprint(selection.path, caller) == selection.expected) { "Selected comment record file changed" }
            text
        }
    }
    suspend fun writeUtf8(selection: DesktopCommentFraudFileSelection, json: String) {
        val caller = currentCoroutineContext()
        withContext(Dispatchers.IO) {
            caller.ensureActive(); claim(selection, true); checkSelection(selection)
            require(fingerprint(selection.path, caller) == selection.expected) { "Selected export destination changed" }
            val temporary = Files.createTempFile(selection.parent, ".bilipai-comment-fraud-", ".json.tmp")
            try {
                Files.newBufferedWriter(temporary, UTF_8).use { writer ->
                    var offset = 0
                    while (offset < json.length) {
                        caller.ensureActive(); checkpoint()
                        val count = minOf(32 * 1024, json.length - offset)
                        writer.write(json, offset, count); offset += count
                    }
                }
                caller.ensureActive(); checkSelection(selection)
                require(fingerprint(selection.path, caller) == selection.expected) { "Selected export destination changed" }
                val expectedAttributes = selection.expected
                commit {
                    caller.ensureActive(); checkSelection(selection)
                    if (expectedAttributes == null) {
                        require(!Files.exists(selection.path, NOFOLLOW_LINKS)) { "Selected export destination appeared" }
                    } else {
                        val actual = Files.readAttributes(selection.path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                        require(actual.fileKey() == expectedAttributes.fileKey && actual.size() == expectedAttributes.bytes && actual.lastModifiedTime().toMillis() == expectedAttributes.modifiedMillis) {
                            "Selected export destination changed before publication"
                        }
                    }
                    // Windows ATOMIC_MOVE may replace an existing target even without
                    // REPLACE_EXISTING. A new target uses the provider's exclusive move.
                    if (expectedAttributes == null) Files.move(temporary, selection.path)
                    else Files.move(temporary, selection.path, ATOMIC_MOVE, REPLACE_EXISTING)
                }
                caller.ensureActive(); checkpoint()
            } finally { Files.deleteIfExists(temporary) }
        }
    }
}
