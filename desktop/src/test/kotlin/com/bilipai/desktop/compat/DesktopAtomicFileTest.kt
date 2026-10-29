package com.bilipai.desktop.compat

import android.util.AtomicFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The AtomicFile stand-in keeps Android's contract: a reader only ever sees a finished write. */
class DesktopAtomicFileTest {
    @Test fun `a finished write replaces the file, a failed or unfinished one leaves the previous version`() {
        val file = AtomicFile(Files.createTempDirectory("atomic-file-").resolve("feeds.json").toFile())
        file.startWrite().also { it.write("first".toByteArray()); file.finishWrite(it) }
        assertEquals("first", file.readFully().decodeToString())
        file.startWrite().also { it.write("broken".toByteArray()); file.failWrite(it) }
        assertEquals("first", file.readFully().decodeToString())
        assertFalse(java.io.File(file.baseFile.path + ".new").exists())
        val unfinished = file.startWrite(); unfinished.write("partial".toByteArray()); unfinished.close()
        assertEquals("first", file.readFully().decodeToString())
        file.startWrite().also { it.write("second".toByteArray()); file.finishWrite(it) }
        assertEquals("second", file.openRead().use { it.readBytes().decodeToString() })
    }
}
