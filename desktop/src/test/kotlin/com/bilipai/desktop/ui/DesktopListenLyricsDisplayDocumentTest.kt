package com.bilipai.desktop.ui

import com.android.purebilibili.feature.audio.lyrics.LyricDocument
import com.android.purebilibili.feature.audio.lyrics.LyricLine
import com.android.purebilibili.feature.audio.lyrics.LyricSource
import com.android.purebilibili.feature.audio.lyrics.LyricSpan
import com.android.purebilibili.feature.audio.lyrics.resolveLyricsDisplayDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The v0.4.6 display projection that the music player hands to every lyric renderer. Pure data: no window,
 * player or network. Hiding translations must never touch the source document. */
class DesktopListenLyricsDisplayDocumentTest {
    private val document = LyricDocument(
        metadata = mapOf("ti" to "Song"),
        lines = listOf(
            LyricLine(0L, 1_000L, "こころ", translations = listOf("heart"), romanization = "kokoro",
                spans = listOf(LyricSpan("こ", 0L, 400L))),
            LyricLine(1_000L, 2_000L, "second", translations = listOf("two", "二")),
        ),
        source = LyricSource.NETEASE, remoteId = "42", offsetMs = 120L, manuallySelected = true, fetchedAtMs = 99L,
    )

    @Test fun anAbsentDocumentStaysAbsentInBothModes() {
        assertNull(resolveLyricsDisplayDocument(null, true))
        assertNull(resolveLyricsDisplayDocument(null, false))
    }

    @Test fun shownTranslationsReturnTheSameDocumentInstance() {
        assertSame(document, resolveLyricsDisplayDocument(document, true))
    }

    @Test fun hiddenTranslationsClearOnlyTranslationsAndRomanization() {
        val projected = checkNotNull(resolveLyricsDisplayDocument(document, false))
        assertNotSame(document, projected)
        assertEquals(listOf(emptyList<String>(), emptyList()), projected.lines.map { it.translations })
        assertEquals(listOf<String?>(null, null), projected.lines.map { it.romanization })
        assertEquals(document.lines.map { it.copy(translations = emptyList(), romanization = null) }, projected.lines)
        assertEquals(document.lines.map { it.spans }, projected.lines.map { it.spans })
        // everything outside the lines keeps its value, including the manual choice and the offset
        assertEquals(document.copy(lines = emptyList()), projected.copy(lines = emptyList()))
    }

    @Test fun theSourceDocumentIsNotChangedSoShowingAgainNeedsNoReload() {
        resolveLyricsDisplayDocument(document, false)
        assertEquals(listOf("heart"), document.lines[0].translations)
        assertEquals("kokoro", document.lines[0].romanization)
        assertSame(document, resolveLyricsDisplayDocument(document, true))
    }
}
