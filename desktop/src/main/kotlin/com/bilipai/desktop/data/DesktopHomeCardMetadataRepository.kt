package com.bilipai.desktop.data

import com.android.purebilibili.core.network.BilibiliApi
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.OkHttpClient

/** Original card endpoints on the existing platform session; constructor sends nothing. */
internal class DesktopHomeCardMetadataRepository(private val repository:DesktopRepository) {
    private fun api(client:OkHttpClient)=Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client)
        .addConverterFactory(Json{ignoreUnknownKeys=true;coerceInputValues=true}.asConverterFactory("application/json".toMediaType()))
        .build().create(BilibiliApi::class.java)
    suspend fun onlineCount(bvid:String,cid:Long,expectedSessionEpoch:Long):String=withContext(Dispatchers.IO){
        require(bvid.isNotBlank() && cid>0)
        checkEpoch(expectedSessionEpoch)
        repository.ensureSession()
        checkEpoch(expectedSessionEpoch)
        val response=api(ownedClient(expectedSessionEpoch,write=false)).getOnlineCount(bvid,cid)
        ensureActive();checkEpoch(expectedSessionEpoch)
        if(response.code!=0)throw BiliApiException(response.code,response.message)
        response.data?.total.orEmpty()
    }
    /** Explicit user menu action only. Capture the originating generation before dispatching IO. */
    suspend fun addWatchLater(aid:Long,expectedSessionEpoch:Long)=withContext(Dispatchers.IO){
        require(aid>0)
        checkEpoch(expectedSessionEpoch)
        repository.requireAccount()
        val csrf=repository.requireCsrf()
        repository.ensureSession();ensureActive();checkEpoch(expectedSessionEpoch)
        val response=api(ownedClient(expectedSessionEpoch,write=true)).addToWatchLater(aid,csrf)
        ensureActive();checkEpoch(expectedSessionEpoch)
        if(response.code!=0)throw BiliApiException(response.code,response.message)
    }
    private fun ownedClient(expected:Long,write:Boolean)=desktopHomeCardOwnedClient(repository.httpClient,expected,{repository.sessionEpoch},write)
    private fun checkEpoch(expected:Long){if(repository.sessionEpoch!=expected)throw CancellationException("Card session changed")}
}

/** Same pipeline for product and local fixtures, with the real repository client as production input. */
internal fun desktopHomeCardOwnedClient(client:OkHttpClient,expected:Long,currentEpoch:()->Long,write:Boolean):OkHttpClient {
    fun checkEpoch(){if(currentEpoch()!=expected)throw BiliApiException(-101,"账号已切换，请重新操作")}
    return client.newBuilder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .addInterceptor {chain->checkEpoch();chain.proceed(chain.request()).also {response->
            if(currentEpoch()!=expected){response.close();throw BiliApiException(-101,"账号已切换，请重新操作")}
        }}
        .addNetworkInterceptor {chain->
            checkEpoch()
            chain.proceed(chain.request()).also {response->
                // Stop OkHttp follow-up processing from repeating an explicit account mutation.
                if(write && response.code in setOf(408,503)){response.close();throw java.io.IOException("Card operation HTTP ${response.code}")}
            }
        }.build()
}
