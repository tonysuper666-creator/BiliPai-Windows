package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.DesktopOriginalAudioHistoryStore
import com.android.purebilibili.core.store.DesktopOriginalLocalPlaylistStore
import com.android.purebilibili.core.store.LocalPlaylist
import com.android.purebilibili.core.store.PlayHistoryEntry
import com.android.purebilibili.core.store.PlayLastSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Required Root music ports over the unchanged original algorithms and the SAME
 * global settings document. No Library history projection, player, queue or cache.
 */
internal class DesktopOriginalMusicStorageBinding(private val context: DesktopOriginalPlayerSettingsContext) {
    private suspend fun checkRequest() {
        currentCoroutineContext().ensureActive()
        context.requireCurrent()
    }
    val history: DesktopOriginalAudioHistoryPort = object : DesktopOriginalAudioHistoryPort {
        override fun recent(limit: Int): Flow<List<PlayHistoryEntry>> {
            context.requireCurrent()
            return DesktopOriginalAudioHistoryStore.recent(context, limit).map { checkRequest(); it }
        }
        override fun lastSession(): Flow<PlayLastSession?> {
            context.requireCurrent()
            return DesktopOriginalAudioHistoryStore.lastSession(context).map { checkRequest(); it }
        }
        override suspend fun record(entry: PlayHistoryEntry) {
            checkRequest()
            DesktopOriginalAudioHistoryStore.record(context, entry)
        }
        override suspend fun saveLastSession(session: PlayLastSession) {
            checkRequest()
            DesktopOriginalAudioHistoryStore.saveLastSession(context, session)
        }
    }
    suspend fun savePlaylist(playlist: LocalPlaylist) {
        checkRequest()
        DesktopOriginalLocalPlaylistStore.savePlaylist(context, playlist)
    }
}
