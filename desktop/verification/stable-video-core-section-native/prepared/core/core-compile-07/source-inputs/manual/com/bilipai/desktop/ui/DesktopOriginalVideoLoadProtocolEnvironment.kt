package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.store.StoredAccountSession
import com.android.purebilibili.data.model.response.PlayUrlData
import kotlinx.coroutines.CancellationException

/** SAME Root playback cache with captured epoch, authorization revision and original
 * quality/language policy. There is intentionally no map, fallback cache or second file. */
internal interface DesktopOriginalVideoRawCache {
    fun get(bvid:String,cid:Long,requestedQuality:Int):PlayUrlData?
    fun put(bvid:String,cid:Long,data:PlayUrlData,quality:Int)
}

/** Mutable protocol diagnostics/cooldown/WBI state must be the existing Root request
 * authority. Each accessor/mutation requires its captured epoch admission. */
internal interface DesktopOriginalVideoProtocolState {
    var appApiCooldownUntilMs:Long
    var wbiKeys:Pair<String,String>?
    var wbiKeysTimestamp:Long
    var last412Time:Long
}

/** Explicit required SAME Repository views. api uses primary account, playbackApi
 * uses the captured authorization receipt, guestApi strips account Cookie/Set-Cookie.
 * Every request is tagged BEFORE CallFactory.newCall and response/cache commits use
 * that exact receipt. Entry assertions here cannot replace those transport guards.
 */
internal class DesktopOriginalVideoLoadProtocolEnvironment(
    val api:BilibiliApi,
    val playbackApi:BilibiliApi,
    val guestApi:BilibiliApi,
    val cache:DesktopOriginalVideoRawCache,
    val state:DesktopOriginalVideoProtocolState,
    val ensureBuvid:suspend ()->Unit,
    val playbackAccount:()->StoredAccountSession?,
    val hasPlaybackSessionCookie:()->Boolean,
    val playbackAccessToken:()->String?,
    val playbackAccessTokenPlatform:()->String,
    val androidAccessTokenPlatform:String,
    val isPlaybackVip:()->Boolean,
    val auto1080pEnabled:()->Boolean,
    val directedTrafficEnabled:()->Boolean,
    val isMobileData:()->Boolean,
    val canRefreshPrimaryToken:()->Boolean,
    val refreshPrimaryToken:suspend ()->Boolean,
    private val stillOwned:()->Boolean,
) {
    fun assertOwned() {
        if (!stillOwned()) throw CancellationException("Original raw playback owner retired")
    }
}
