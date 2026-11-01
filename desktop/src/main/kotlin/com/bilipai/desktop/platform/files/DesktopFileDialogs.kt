package com.bilipai.desktop.platform.files

import java.awt.Window
import java.nio.file.Path

/** Native single-file selection. Only user cancellation returns null; failures throw.
 * Selecting a save destination does not create or truncate it. The consumer owns IO.
 * [owner] is resolved on the AWT event thread and must be a mounted Frame or Dialog.
 * Close the instance when its presentation retires; it cannot be reused after closing. */
interface DesktopFileDialogs : AutoCloseable {
    suspend fun chooseFile(owner: () -> Window, request: DesktopFileDialogRequest): Path?
}

enum class DesktopFileDialogMode { OPEN, SAVE }

data class DesktopFileDialogRequest(
    val title: String,
    val mode: DesktopFileDialogMode,
    /** A native filter hint, not validation of the selected file's contents. */
    val extensions: Set<String> = emptySet(),
    val suggestedFileName: String? = null,
) {
    init {
        require(title.isNotBlank()) { "A file dialog requires a title" }
        require(extensions.all { it.matches(Regex("[A-Za-z0-9]+")) }) {
            "File extensions must omit dots and path separators"
        }
        suggestedFileName?.let { requireFileLeaf(it) }
    }
}

internal fun requireFileLeaf(name: String) {
    require(name.isNotBlank() && name != "." && name != ".." &&
        name.none { it == '/' || it == '\\' || it == ':' || it == '\u0000' }) {
        "A file name must be a single path component"
    }
}
