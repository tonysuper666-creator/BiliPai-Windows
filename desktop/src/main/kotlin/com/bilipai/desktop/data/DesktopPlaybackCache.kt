package com.bilipai.desktop.data

import com.android.purebilibili.core.cache.resolvePlayUrlCacheExpiresAt
import com.android.purebilibili.data.model.response.PlayUrlData
import java.util.concurrent.TimeUnit

/** Short-lived media URL cache. Account epochs prevent response data crossing login switches. */
internal class DesktopPlaybackCache {
    data class Key(val accountEpoch: Long, val bvid: String, val cid: Long, val quality: Int, val codec: String?,
        val firstCodec: String? = null, val secondCodec: String? = null, val audioQuality: Int = -1,
        val av1Supported: Boolean = true, val authorizationRevision: Long = 0)
    data class Entry(val data: PlayUrlData, val selectionQuality: Int, val storedAt: Long) {
        val expiresAt: Long = resolvePlayUrlCacheExpiresAt(
            timestampMs = storedAt,
            maxAgeMs = TimeUnit.MINUTES.toMillis(10),
            urls = buildList {
                data.dash?.video.orEmpty().forEach { track ->
                    add(track.baseUrl)
                    addAll(track.backupUrl.orEmpty())
                }
                val audios = data.dash?.audio.orEmpty() + data.dash?.dolby?.audio.orEmpty() +
                    listOfNotNull(data.dash?.flac?.audio)
                audios.forEach { track ->
                    add(track.baseUrl)
                    addAll(track.backupUrl.orEmpty())
                }
                data.durl.orEmpty().forEach { stream ->
                    add(stream.url)
                    addAll(stream.backupUrl.orEmpty())
                }
            }
        )

        fun isExpired(now: Long): Boolean = now < storedAt || now >= expiresAt
    }
    private val entries = LinkedHashMap<Key, Entry>(80, .75f, true)

    @Synchronized fun get(key: Key, now: Long = System.currentTimeMillis()): Entry? {
        val entry = entries[key] ?: return null
        if (entry.isExpired(now)) {
            entries.remove(key)
            return null
        }
        return entry
    }

    @Synchronized fun put(key: Key, data: PlayUrlData, selectionQuality: Int, now: Long = System.currentTimeMillis()) {
        entries.keys.removeAll { it.accountEpoch != key.accountEpoch || it.authorizationRevision != key.authorizationRevision }
        val entry = Entry(data, selectionQuality, now)
        if (entry.isExpired(now)) {
            entries.remove(key)
            return
        }
        entries[key] = entry
        while (entries.size > 80) entries.remove(entries.keys.first())
    }

    /** Original no-quality get: newest valid entry for this video. The same
     * existing LRU/expiry remains sole authority; receipt revision excludes other
     * selected-account credentials. No source data is copied or cached here. */
    @Synchronized fun getForVideo(epoch: Long, revision: Long, bvid: String, cid: Long,
        now: Long = System.currentTimeMillis()): Entry? {
        var candidate: Entry? = null
        entries.toMap().forEach { (key, entry) ->
            if (key.accountEpoch != epoch || key.authorizationRevision != revision ||
                key.bvid != bvid || key.cid != cid) return@forEach
            if (entry.isExpired(now)) {
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
