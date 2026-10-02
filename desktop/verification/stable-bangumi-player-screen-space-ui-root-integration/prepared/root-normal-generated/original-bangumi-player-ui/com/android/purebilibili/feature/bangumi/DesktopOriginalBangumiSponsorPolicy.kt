package com.android.purebilibili.feature.bangumi
import com.android.purebilibili.data.model.response.SponsorSegment
internal fun desktopOriginalBangumiFindSponsorSegment(
        segments: List<SponsorSegment>,
        currentPositionMs: Long
    ): SponsorSegment? {
        val currentSeconds = currentPositionMs / 1000f
        return segments.find { segment ->
            currentSeconds >= segment.startTime && currentSeconds < segment.endTime - 0.5f
        }
    }
