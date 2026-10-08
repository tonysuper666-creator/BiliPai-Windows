package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.StoredAccountSession
import com.android.purebilibili.data.model.response.NavData
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Root's existing Profile entry and encrypted account Store. No retained credential copy.
 * Store -> entry is the only admission order. No network/native wait occurs in this facade. */
internal class DesktopProfileAccountsBinding(
    private val repository: DesktopRepository,
    private val capturedEpoch: Long,
    private val capturedMid: Long?,
    private val entryJob: Job,
    private val isCurrent: () -> Boolean,
    private val commitIfCurrent: ((() -> Unit) -> Boolean),
    private val retireLoginReturn: () -> Unit = {},
) : DesktopProfileAccountPort {
    private data class LogoutTerminal(val oldEpoch: Long, val acceptedEpoch: Long, val caller: Job)
    @Volatile private var logoutTerminal: LogoutTerminal? = null

    private fun <T> admitted(caller: Job, action: DesktopSessionStore.() -> T): T {
        fun current(): Boolean {
            caller.ensureActive(); entryJob.ensureActive()
            return isCurrent()
        }
        return repository.withProfileAccountAdmission(capturedEpoch, capturedMid, ::current, commitIfCurrent, action)
    }
    private suspend fun caller(): Job = currentCoroutineContext()[Job]?.also { it.ensureActive() }
        ?: error("Profile account mutation requires its actual caller Job")

    override suspend fun getAccounts(): List<StoredAccountSession> = admitted(caller()) { storedAccountSessions() }
    override suspend fun getActiveAccountMid(): Long? = admitted(caller()) { activeAccountMid() }
    override suspend fun getPlaybackAccountMid(): Long? = admitted(caller()) { getPlaybackAccountMid() }
    override suspend fun setPlaybackAccountMid(mid: Long?): Boolean = admitted(caller()) {
        setPlaybackAccountMid(mid, capturedEpoch) { entryJob.isActive && isCurrent() }
    }
    override fun currentMid(): Long? = admitted(entryJob) { activeAccountMid() }
    override fun hasSession(): Boolean = admitted(entryJob) { currentCookies()["SESSDATA"].orEmpty().isNotBlank() }
    override fun accessTokenCredentials(): Pair<String?, String> = admitted(entryJob) { accessTokenCredentials() }
    override fun qrAuthorizationSession(): com.android.purebilibili.feature.login.QrAuthorizationSession = admitted(entryJob) {
        val cookies = currentCookies()
        val credentials = accessTokenCredentials()
        com.android.purebilibili.feature.login.QrAuthorizationSession(
            cookies["SESSDATA"].orEmpty(), cookies["bili_jct"].orEmpty(), activeAccountMid(),
            cookies["buvid3"].orEmpty(), credentials.first.orEmpty(), credentials.second,
        )
    }
    override suspend fun saveMid(mid: Long) { admitted(caller()) { saveProfileMid(mid) } }
    override suspend fun saveVipStatus(vip: Boolean) { admitted(caller()) { saveProfileVipStatus(vip) } }
    override suspend fun upsertCurrentAccount(nav: NavData?) { admitted(caller()) { upsertProfileCurrentAccount(nav) } }

    override suspend fun clearCurrentSession() {
        val job = caller()
        admitted(job) {
            logout()
            retireLoginReturn()
            logoutTerminal = LogoutTerminal(capturedEpoch, generation, job)
        }
        repository.profileAuthenticationChanged()
    }
    override suspend fun clearActiveAccount() {
        val job = caller()
        val terminal = logoutTerminal
        if (terminal != null && terminal.oldEpoch == capturedEpoch && terminal.caller === job) {
            job.ensureActive(); entryJob.ensureActive()
            if (!repository.verifyProfileLogoutTerminal(terminal.oldEpoch, terminal.acceptedEpoch))
                throw CancellationException("Profile logout completion superseded")
            return
        }
        // Original Profile always clears credentials first. An independent clear also uses
        // the same canonical Store logout; it cannot leave active cookies with a false guest MID.
        clearCurrentSession()
    }
    override suspend fun activateAccount(mid: Long): Boolean {
        val accepted = admitted(caller()) { activateAccount(mid).also { if (it) retireLoginReturn() } }
        if (accepted) repository.profileAuthenticationChanged()
        return accepted
    }
    override fun removeAccount(mid: Long): Boolean {
        // This original port is synchronous and is called directly by the UI, not a coroutine.
        // The actual entry Job is therefore its explicit immediate-action lifetime.
        val wasPrimary = capturedMid == mid
        val accepted = admitted(entryJob) { removeAccount(mid).also { if (it && wasPrimary) retireLoginReturn() } }
        if (accepted && wasPrimary) repository.profileAuthenticationChanged()
        return accepted
    }
}
