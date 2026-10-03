package com.bilipai.desktop.player.cache

import com.bilipai.desktop.data.DesktopPlaybackAuthorization
import com.bilipai.desktop.data.DesktopRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import okhttp3.Call

/** Actual same-Repository transport/admission, not another client or credentials store.
 * Namespace is required from the same SessionStore's effective-playback digest.
 * Entry admission is required from the real retained page/source gate. */
internal class DesktopMediaByteRepositoryAdmission(
    private val repository: DesktopRepository,
    private val authorization: DesktopPlaybackAuthorization,
    override val persistentNamespace: String,
    override val ownerJob: Job,
    private val isOwned: () -> Boolean,
    private val withEntryAdmission: ((() -> Unit) -> Boolean),
    private val observeCdnNetwork: () -> DesktopCdnNetworkObservation,
) : DesktopMediaByteCdnAdmission {
    override val receipt = authorization.receipt
    override fun assertCurrent() { commit { Unit } }
    override fun <T> commit(block: () -> T): T = repository.withPlaybackReceiptAdmission(receipt,isOwned) {
        var result: Result<T>? = null
        if (!withEntryAdmission {
            if (!isOwned()) throw CancellationException("Media source entry retired")
            result = runCatching(block)
        }) throw CancellationException("Media source entry retired")
        checkNotNull(result).getOrThrow()
    }
    override fun calls(stillCurrent: () -> Boolean, callerJob: Job): Call.Factory =
        // Dynamic lease supplies the current SAME-receipt entry after adoption.
        // Capturing this admission's old publication here would break an ongoing read.
        repository.ownedPlaybackCallFactory(authorization) { callerJob.isActive && stillCurrent() }
    override fun cdnNetwork(): DesktopCdnNetworkObservation {
        ownerJob.ensureActive(); assertCurrent()
        // WinRT IO is never under the Store->entry commit above.
        val observation = observeCdnNetwork()
        ownerJob.ensureActive(); assertCurrent()
        return observation
    }
    override fun toString() = "DesktopMediaByteRepositoryAdmission"
}
