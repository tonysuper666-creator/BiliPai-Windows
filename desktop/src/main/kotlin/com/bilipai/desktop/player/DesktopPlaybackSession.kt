package com.bilipai.desktop.player

/** Request intent only. The installed owner still resolves media, versions, recovery and heartbeat. */
data class DesktopPlaybackSessionRequest(
    val bvid: String,
    val aid: Long = 0,
    val cid: Long = 0,
    val resumePositionMs: Long? = null,
    val autoPlay: Boolean? = null,
    val force: Boolean = true,
) {
    init {
        require(bvid.isNotBlank())
        require(cid >= 0 && aid >= 0)
        require(resumePositionMs == null || resumePositionMs >= 0)
    }
}

data class DesktopPlaybackCapabilities(
    val hevc: Boolean,
    val av1: Boolean,
    val hdr: Boolean,
    val dolbyVision: Boolean,
    val dolbyAtmosAudio: Boolean,
    val dolbySoftwareAudioDecoderRequired: Boolean,
)

/** Owner-bound desktop session, never an independently constructed decoder.
 * Snapshot and commands retain the installed source admission. Request/close run
 * outside that admission, since they may cancel the owner's existing jobs.
 */
interface DesktopPlaybackSession : DesktopPlaybackRequestPort, AutoCloseable {
    override fun requestPlayback(card: com.bilipai.desktop.data.VideoCard, resumePositionMs: Long?): DesktopPlaybackRequestLease =
        requestPlayback(DesktopPlaybackSessionRequest(card.bvid, cid = card.preferredCid,
            resumePositionMs = resumePositionMs))
    fun requestPlayback(request: DesktopPlaybackSessionRequest): DesktopPlaybackRequestLease
    fun play()
    fun pause()
    fun setPlaybackSpeed(value: Float)
    fun seekToTracked(positionMs: Long): com.bilipai.desktop.ui.DesktopOriginalNativeSeekSubmission?
    fun addListener(listener: com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl.Listener)
    fun removeListener(listener: com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl.Listener)
    fun playbackSnapshot(): PlayerState
    fun playbackCapabilities(): DesktopPlaybackCapabilities
}
