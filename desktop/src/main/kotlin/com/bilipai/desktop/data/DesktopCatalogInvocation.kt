package com.bilipai.desktop.data

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.BuvidApi
import com.android.purebilibili.core.network.WbiUtils
import com.android.purebilibili.data.repository.DesktopOriginalVideoCatalogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

/** An immutable caller's views of the existing sole repository graph. No client,
 * credentials, WBI/creator cache, Job, store or parallel request owner is created. */
internal class DesktopCatalogInvocation(
    val api: BilibiliApi,
    val shared: DesktopOriginalVideoCatalogRepository,
    val assertOwned: () -> Unit,
    val ensureSession: suspend () -> Unit,
    val wbiKeys: suspend () -> Result<Pair<String, String>>,
    val accessToken: () -> String?,
) {
    suspend fun sign(params: Map<String, String>): Map<String, String> {
        assertOwned()
        val keys = wbiKeys().getOrThrow()
        currentCoroutineContext().ensureActive(); assertOwned()
        return WbiUtils.sign(params, keys.first, keys.second).also { assertOwned() }
    }
}

/** Capture before bootstrap, WBI or transport can suspend. Request tags use this
 * exact caller/epoch before newCall, including cached/fast responses. */
internal suspend fun DesktopRepository.catalogInvocation(): DesktopCatalogInvocation {
    val caller = currentCoroutineContext()
    val epoch = sessionEpoch
    fun owns() = caller.isActive && sessionEpoch == epoch
    fun assertOwned() {
        caller.ensureActive()
        if (!owns()) throw CancellationException("Catalog caller/account retired")
    }
    assertOwned()
    val api = ownedHomeService(BilibiliApi::class.java, "https://api.bilibili.com/", epoch, ::owns)
    val buvid = ownedHomeService(BuvidApi::class.java, "https://api.bilibili.com/", epoch, ::owns)
    return DesktopCatalogInvocation(api, DesktopOriginalVideoCatalogRepository(api, ::assertOwned), ::assertOwned,
        { assertOwned(); ensureOwnedHomeSession(epoch, ::owns, buvid); currentCoroutineContext().ensureActive(); assertOwned() },
        { assertOwned(); homeWbiKeys(epoch, ::owns, api).also { currentCoroutineContext().ensureActive(); assertOwned() } },
        { assertOwned(); ownedHomeAccessToken(epoch, ::owns) })
}
