package com.android.purebilibili.data.repository

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.util.Logger
import com.android.purebilibili.data.model.response.BgmDetailData
import com.android.purebilibili.data.model.response.BgmInfo
import com.android.purebilibili.data.model.response.BgmRecommendVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class DesktopOriginalBgmRepository(
    private val api: BilibiliApi,
    private val sign: suspend (Map<String, String>) -> Map<String, String>,
) {

    suspend fun getBgmList(aid: Long, bvid: String, cid: Long): Result<List<BgmInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = api.getBgmMultipleMusic(aid = aid, cid = cid)
                val list = response.data?.list.orEmpty().map { bgm ->
                    if (bgm.jumpUrl.isBlank() && bgm.musicId.isNotBlank()) {
                        bgm.copy(
                            jumpUrl = "https://music.bilibili.com/h5/music-detail" +
                                "?music_id=${bgm.musicId}&cid=$cid&aid=$aid&na_close_hide=1&isMulti=1"
                        )
                    } else bgm
                }

                list
            }.onFailure { e ->
                if (e is CancellationException) throw e

            }
        }

    suspend fun getBgmDetail(musicId: String, aid: Long = 0, cid: Long = 0): Result<BgmDetailData?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val params = buildMap {
                    put("music_id", musicId)
                    put("relation_from", "bgm_page")
                    if (aid > 0) put("aid", aid.toString())
                    if (cid > 0) put("cid", cid.toString())
                }
                val response = api.getBgmDetail(sign(params))
                check(response.code == 0) { response.message.ifBlank { "音乐详情加载失败 (${response.code})" } }
                requireNotNull(response.data) { "暂无音乐详情" }
            }.onFailure { e ->
                if (e is CancellationException) throw e

            }
        }

    suspend fun getBgmRecommendVideos(
        musicId: String,
        aid: Long,
        cid: Long,
        page: Int = 1,
        pageSize: Int = 5
    ): Result<List<BgmRecommendVideo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = api.getBgmRecommendList(
                    musicId = musicId,
                    aid = aid,
                    cid = cid,
                    pn = page,
                    ps = pageSize
                )
                check(response.code == 0) { response.message.ifBlank { "推荐视频加载失败 (${response.code})" } }
                response.data?.list.orEmpty()
            }.onFailure { e ->
                if (e is CancellationException) throw e

            }
        }

    suspend fun getAllBgmRecommendVideos(musicId: String): Result<List<BgmRecommendVideo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = api.getAllBgmRecommendList(musicId)
                check(response.code == 0) { response.message.ifBlank { "推荐视频加载失败 (${response.code})" } }
                response.data?.list.orEmpty().filter { it.bvid.isNotBlank() }.distinctBy { it.bvid to it.cid }
            }.onFailure { if (it is CancellationException) throw it }
        }

}
