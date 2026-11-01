package com.bilipai.desktop.data

import com.android.purebilibili.core.network.PassportApi
import com.android.purebilibili.data.repository.buildHdQrLoginParams
import com.android.purebilibili.feature.login.PiliPlusLoginIdentity
import com.bilipai.desktop.compat.UnsupportedDesktopCapability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** One candidate, not the process-wide Android TokenManager or the currently signed-in account. */
internal data class DesktopLoginSubmission(
    val sessData: String, val csrf: String, val buvid3: String, val mid: Long,
    val accessToken: String, val refreshToken: String, val accessTokenPlatform: String,
    val cookies: Map<String, String>,
)

internal interface DesktopLoginPlatform {
    val passportApi: PassportApi
    val identity: PiliPlusLoginIdentity
    suspend fun install(submission: DesktopLoginSubmission): AccountSummary
}

/** Each upstream launch is registered with the existing update hold. Close retires only this VM's jobs. */
internal class DesktopLoginLaunchScope(private val launchOwned: (suspend CoroutineScope.() -> Unit) -> Job) {
    private val lock = Any()
    private val jobs = mutableSetOf<Job>()
    private var closed = false

    fun launch(block: suspend CoroutineScope.() -> Unit): Job = synchronized(lock) {
        if (closed) return@synchronized Job().apply { cancel() }
        launchOwned(block).also { job ->
            jobs += job
            job.invokeOnCompletion { synchronized(lock) { jobs -= job } }
        }
    }

    fun close() = synchronized(lock) {
        closed = true
        jobs.toList().forEach(Job::cancel)
        jobs.clear()
    }
}

/** Original LoginViewModel's network/bitmap work, the existing candidate jar and the SAME M3 installer. */
internal class DesktopLoginEnvironment(
    private val platform: DesktopLoginPlatform,
    val work: DesktopLoginLaunchScope,
) {
    constructor(login: DesktopLoginRepository, work: DesktopLoginLaunchScope) : this(object : DesktopLoginPlatform {
        override val passportApi get() = login.upstreamPassportApi
        override val identity get() = login.upstreamIdentity
        override suspend fun install(submission: DesktopLoginSubmission) = login.installUpstreamLogin(submission)
    }, work)

    val passportApi get() = platform.passportApi
    val identity get() = platform.identity
    var installedAccount: AccountSummary? = null
        private set

    suspend fun generateQr() = passportApi.generateHdQrCode(buildHdQrLoginParams(null))
    suspend fun pollQr(authCode: String) = passportApi.pollHdQrCode(buildHdQrLoginParams(authCode))

    suspend fun install(sessData: String, csrf: String, buvid3: String, mid: Long, accessToken: String,
        refreshToken: String, accessTokenPlatform: String, cookies: Map<String, String>) {
        installedAccount = platform.install(DesktopLoginSubmission(sessData, csrf, buvid3, mid, accessToken,
            refreshToken, accessTokenPlatform, cookies))
    }

    fun requireInstalledAccount(): AccountSummary = installedAccount ?: throw UnsupportedDesktopCapability(
        "Login completion without a desktop installation receipt",
        "desktop transfer must install through the same M3 login actor before completing")
}
