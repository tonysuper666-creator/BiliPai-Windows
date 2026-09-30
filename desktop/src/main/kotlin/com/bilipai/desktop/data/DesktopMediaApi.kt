package com.bilipai.desktop.data

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.BangumiApi
import com.android.purebilibili.core.network.SearchApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Transport only: every protocol declaration is generated directly from upstream ApiClient.kt. */
internal class DesktopMediaApi(repository: DesktopRepository, json: Json) {
    private val retrofit = Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(repository.httpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
    val live: BilibiliApi = retrofit.create(BilibiliApi::class.java)
    val bangumi: BangumiApi = retrofit.create(BangumiApi::class.java)
    val search: SearchApi = retrofit.create(SearchApi::class.java)
}
