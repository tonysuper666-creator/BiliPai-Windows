package com.android.purebilibili.feature.audio.bgm

import com.android.purebilibili.data.model.response.*

/** Requests are implemented only by the current DesktopDynamicCardOperations owner. */
internal interface DesktopBgmDetailRequests {
    fun isOwned(): Boolean
    fun hasLogin(): Boolean
    suspend fun getBgmDetail(musicId: String, aid: Long = 0, cid: Long = 0): Result<BgmDetailData?>
    suspend fun getAllBgmRecommendVideos(musicId: String): Result<List<BgmRecommendVideo>>
    suspend fun updateBgmWish(musicId: String, state: Int): Result<SimpleApiResponse>
    suspend fun getEmotePackages(): Result<List<EmotePackage>>
}
