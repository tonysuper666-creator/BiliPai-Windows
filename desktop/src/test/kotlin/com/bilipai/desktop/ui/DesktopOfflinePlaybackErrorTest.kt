package com.bilipai.desktop.ui

import com.android.purebilibili.feature.download.resolveOfflinePlaybackFailure
import com.bilipai.desktop.player.PlayerFailure
import com.bilipai.desktop.player.PlayerFailureKind
import com.bilipai.desktop.player.platform.DesktopOfflineMedia3ErrorCodes
import kotlin.test.*

/** Actual MPV categories are normalized only for the complete original offline policy. */
class DesktopOfflinePlaybackErrorTest {
    private fun failure(kind:PlayerFailureKind)=PlayerFailure(kind,-13,"Safe native failure",sourceVersion=4,attemptId=2)

    @Test fun `decoder failure uses original retry without classifying audio or video output as decoder`() {
        val code=desktopOfflinePlaybackErrorCode(failure(PlayerFailureKind.DECODER))
        assertEquals(DesktopOfflineMedia3ErrorCodes.ERROR_CODE_DECODING_FAILED,code)
        assertTrue(resolveOfflinePlaybackFailure(code).canRetry)
        listOf(PlayerFailureKind.AUDIO_OUTPUT,PlayerFailureKind.VIDEO_OUTPUT).forEach {
            assertEquals(0,desktopOfflinePlaybackErrorCode(failure(it)))
        }
    }

    @Test fun `actual unsupported input follows original download-again policy`() {
        val result=resolveOfflinePlaybackFailure(desktopOfflinePlaybackErrorCode(failure(PlayerFailureKind.UNSUPPORTED)))
        assertFalse(result.canRetry)
        assertTrue(result.message.contains("重新下载"))
    }

    @Test fun `ambiguous native file and untyped failures do not invent missing or denied evidence`() {
        assertEquals(0,desktopOfflinePlaybackErrorCode(failure(PlayerFailureKind.FILE_IO)))
        assertEquals(0,desktopOfflinePlaybackErrorCode(null))
        assertTrue(resolveOfflinePlaybackFailure(0).canRetry)
    }

    @Test fun `actual typed file access failures retain the original no-retry distinction`() {
        val missing=desktopOfflineLoadErrorCode(java.nio.file.NoSuchFileException("private-local.media"))
        val denied=desktopOfflineLoadErrorCode(java.nio.file.AccessDeniedException("private-local.media"))
        assertEquals(DesktopOfflineMedia3ErrorCodes.ERROR_CODE_IO_FILE_NOT_FOUND,missing)
        assertEquals(DesktopOfflineMedia3ErrorCodes.ERROR_CODE_IO_NO_PERMISSION,denied)
        assertFalse(resolveOfflinePlaybackFailure(missing).canRetry)
        assertFalse(resolveOfflinePlaybackFailure(denied).canRetry)
        assertNotEquals(resolveOfflinePlaybackFailure(missing).message,resolveOfflinePlaybackFailure(denied).message)
    }

    @Test fun `generic file error cannot become a permanent missing-file classification`() {
        val code=desktopOfflineLoadErrorCode(java.io.IOException("Bounded generic file failure"))
        assertEquals(0,code)
        assertTrue(resolveOfflinePlaybackFailure(code).canRetry)
    }
}
