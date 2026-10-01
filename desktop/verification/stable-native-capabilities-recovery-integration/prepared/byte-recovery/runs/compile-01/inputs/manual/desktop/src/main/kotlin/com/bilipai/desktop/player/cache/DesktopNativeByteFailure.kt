package com.bilipai.desktop.player.cache

import com.bilipai.desktop.data.DesktopPlaybackAuthorizationReceipt
import com.bilipai.desktop.player.DesktopNativePlaybackPublication

internal enum class DesktopNativeByteFailureStage { METADATA, BODY_READ }

/** Opaque, captured native read frame. A prefetch/probe cannot create a native
 * failure merely because its best-effort request failed. No address or headers. */
internal class DesktopNativeByteReadStamp internal constructor(
    internal val lease: DesktopMediaByteLease,
    internal val frameIdentity: Any,
    val sourceVersion: Long,
    internal val publication: DesktopNativePlaybackPublication,
    internal val receipt: DesktopPlaybackAuthorizationReceipt,
) {
    override fun toString() = "DesktopNativeByteReadStamp(sourceVersion=$sourceVersion)"
}

/** Capacity ONE per actual lease frame; a real failed native read publishes it
 * under the same Store -> entry admission. Throwable text/URL/Cookie are absent.
 * Constructors do not grant recovery: the lease checks exact event/frame identity. */
internal class DesktopNativeByteFailure internal constructor(
    val sequence: Long,
    internal val stamp: DesktopNativeByteReadStamp,
    val stage: DesktopNativeByteFailureStage,
) {
    val sourceVersion: Long get() = stamp.sourceVersion
    override fun toString() = "DesktopNativeByteFailure(sequence=$sequence, sourceVersion=$sourceVersion, stage=$stage)"
}
