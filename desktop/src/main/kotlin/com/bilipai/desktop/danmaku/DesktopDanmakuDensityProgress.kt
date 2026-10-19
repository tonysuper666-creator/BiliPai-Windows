package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.parser.DanmakuProto
import com.android.purebilibili.data.repository.DanmakuRepository
import com.android.purebilibili.data.repository.resolveDanmakuSegmentCount
import com.android.purebilibili.feature.video.progress.PbpProgressData
import com.android.purebilibili.feature.video.progress.buildDanmakuDensityValues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Local danmaku density curve for the progress bar heat ridge (upstream 918b6924, v0.2.6 getDanmakuDensityProgressData).
 *
 * STATUS: a bottom layer only, NOT wired. Playback still has no fallback heat curve. The official pbp endpoint
 * (bvc.bilivideo.com/pbp/data) is reported to answer 404, and when it fails or is empty the existing Windows path publishes
 * no curve at all (buildPbpRidgeSamples returns an empty list for an empty or missing PbpProgressData, so the heat ridge is
 * simply absent, never wrong). Nothing in desktop/src/main calls [load].
 *
 * Why it is not wired here: upstream calls its fallback from VideoPlaybackViewModel.loadDanmakuDensityFallback, in the pbp
 * success-with-empty and failure branches, with the page duration and under a settings gate. The Windows view model is a
 * hash-pinned recipe over the v0.2.5 file, which predates upstream 918b6924, and no generator in desktop/tools names that
 * method or its call sites. The owner seam DesktopOriginalVideoOwnerRequestRepository.getPbpProgressData(bvid, cid, aid)
 * carries neither the duration nor a danmaku source. Wiring is therefore its own follow-up slice: build this object's
 * source from the invocation binding, call [load] where getPbpProgressData fails or returns no values, then flip the
 * zero-callers pin in test_v046_danmaku_protocol_source.py.
 *
 * What [load] does: every segment is fetched through the public danmaku transport, decoded with the v0.4.6 strict parser and
 * bucketed by the pinned upstream buildDanmakuDensityValues, so the render layer needs no other change.
 */
object DesktopDanmakuDensityProgress {
    /** Upstream getDanmakuSegments parallelism. */
    const val MAX_SEGMENT_PARALLELISM = 3
    /**
     * Deliberate deviation from upstream, which downloads every segment of any duration: a video this long (40 six-minute
     * segments) keeps the empty curve instead of downloading every segment, to bound traffic and memory on the desktop.
     */
    const val MAX_SEGMENTS = 40
    private const val MAX_SEGMENT_BYTES = 4 * 1024 * 1024

    suspend fun load(
        source: DesktopDanmakuSource,
        cid: Long,
        durationSeconds: Long,
        invalidateShared: (Long, Int) -> Unit = DanmakuRepository::invalidateDanmakuSegment,
    ): Result<PbpProgressData> {
        if (cid <= 0L || durationSeconds <= 0L) {
            return Result.failure(IllegalArgumentException("弹幕密度曲线参数无效: cid=${cid} duration=${durationSeconds}s"))
        }
        return try {
            val stepSeconds = (durationSeconds / 240).coerceIn(2L, 10L).toInt()
            val count = resolveDanmakuSegmentCount(durationSeconds * 1000L, null)
            if (count > MAX_SEGMENTS) {
                return Result.failure(IllegalStateException("视频过长，不构建本地弹幕密度曲线"))
            }
            val fetched = coroutineScope {
                val permits = Semaphore(MAX_SEGMENT_PARALLELISM)
                (1..count).map { index ->
                    async {
                        permits.withPermit {
                            try {
                                index to source.segment(cid, index).also(::requireSegmentBytes)
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (failure: Exception) {
                                null
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            if (fetched.isEmpty()) return Result.failure(IllegalStateException("弹幕分段为空"))
            val positionsMs = ArrayList<Long>()
            var serverDisabled = false
            fetched.forEach { (index, bytes) ->
                val reply = DanmakuProto.parseReply(bytes)
                if (reply.parseFailed) {
                    // The same strict parse as the playback window: corrupt bytes are never counted and are evicted.
                    try { invalidateShared(cid, index) } catch (_: Exception) { }
                    return@forEach
                }
                if (reply.state == 1) serverDisabled = true
                reply.elems.forEach { item ->
                    if (item.mode in 1..6 && item.progress >= 0 && item.content.isNotBlank()) {
                        positionsMs += item.progress.toLong()
                    }
                }
            }
            if (serverDisabled) return Result.failure(IllegalStateException("UP主已关闭该视频弹幕"))
            val values = buildDanmakuDensityValues(
                positionsMs = positionsMs,
                durationSeconds = durationSeconds,
                stepSeconds = stepSeconds,
            )
            if (values.all { it == 0f }) return Result.failure(IllegalStateException("该视频弹幕密度为零"))
            Result.success(PbpProgressData(stepSeconds = stepSeconds, values = values))
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    private fun requireSegmentBytes(bytes: ByteArray) {
        require(bytes.size <= MAX_SEGMENT_BYTES) { "Danmaku segment is too large." }
        require(bytes.firstOrNull() != '{'.code.toByte() && bytes.firstOrNull() != '['.code.toByte()) {
            "Danmaku endpoint returned an API error instead of protobuf."
        }
    }
}
