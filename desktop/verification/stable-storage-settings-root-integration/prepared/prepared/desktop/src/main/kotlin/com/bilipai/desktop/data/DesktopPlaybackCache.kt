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

    /** Original no-quality get: newest valid entry for this video. The same
     * existing LRU/TTL remains sole authority; receipt revision excludes other
     * selected-account credentials. No source data is copied or cached here. */
    @Synchronized fun getForVideo(epoch: Long, revision: Long, bvid: String, cid: Long,
        now: Long = System.currentTimeMillis()): Entry? {
        var candidate: Entry? = null
        entries.toMap().forEach { (key, entry) ->
            if (key.accountEpoch != epoch || key.authorizationRevision != revision ||
                key.bvid != bvid || key.cid != cid) return@forEach
            if (now < entry.storedAt || now - entry.storedAt > TimeUnit.MINUTES.toMillis(10)) {
                entries.remove(key); return@forEach
            }
            val current = candidate
            if (current == null || entry.storedAt > current.storedAt) candidate = entry
        }
        return candidate
    }

    @Synchronized fun invalidateVideo(epoch: Long, bvid: String, cid: Long) {
        entries.keys.removeAll { it.accountEpoch == epoch && it.bvid == bvid && it.cid == cid }
    }

    /** Same estimate as original PlayUrl cache UI; runtime memory estimate, never disk bytes. */
    @Synchronized fun estimatedBytes(): Long = entries.size * 2048L

    @Synchronized fun clear() = entries.clear()
}
