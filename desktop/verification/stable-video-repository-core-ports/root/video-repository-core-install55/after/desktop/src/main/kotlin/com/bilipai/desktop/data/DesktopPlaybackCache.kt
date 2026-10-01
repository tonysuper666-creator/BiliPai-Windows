package com.bilipai.desktop.data

import com.android.purebilibili.data.model.response.PlayUrlData
import java.util.concurrent.TimeUnit

/** Short-lived media URL cache. Account epochs prevent response data crossing login switches. */
internal class DesktopPlaybackCache {
    data class Key(val accountEpoch: Long, val bvid: String, val cid: Long, val quality: Int, val codec: String?,
        val firstCodec: String? = null, val secondCodec: String? = null, val audioQuality: Int = -1,
        val av1Supported: Boolean = true, val authorizationRevision: Long = 0)
    data class Entry(val data: PlayUrlData, val selectionQuality: Int, val storedAt: Long)
    private val entries = LinkedHashMap<Key, Entry>(80, .75f, true)

    @Synchronized fun get(key: Key, now: Long = System.currentTimeMillis()): Entry? {
        val entry = entries[key] ?: return null
        if (now < entry.storedAt || now - entry.storedAt > TimeUnit.MINUTES.toMillis(10)) {
            entries.remove(key)
            return null
        }
        return entry
    }

    @Synchronized fun put(key: Key, data: PlayUrlData, selectionQuality: Int, now: Long = System.currentTimeMillis()) {
        entries.keys.removeAll { it.accountEpoch != key.accountEpoch || it.authorizationRevision != key.authorizationRevision }
        entries[key] = Entry(data, selectionQuality, now)
        while (entries.size > 80) entries.remove(entries.keys.first())
    }

    @Synchronized fun invalidateVideo(epoch: Long, bvid: String, cid: Long) {
        entries.keys.removeAll { it.accountEpoch == epoch && it.bvid == bvid && it.cid == cid }
    }

    @Synchronized fun clear() = entries.clear()
}
