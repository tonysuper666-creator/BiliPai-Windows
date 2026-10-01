private suspend fun rawCurlGuest(url: String): String? = withContext(Dispatchers.IO) {
    val buvid = com.android.purebilibili.core.store.TokenManager.buvid3Cache
    val request = okhttp3.Request.Builder()
        .url(url)
        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
        .header("Origin", "https://www.bilibili.com")
        .header("Referer", "https://www.bilibili.com")
        .header("Accept", "application/json, text/plain, */*")
        .apply {
            if (!buvid.isNullOrBlank()) {
                header("Cookie", "buvid3=$buvid;")
            }
        }
        .build()

    try {
        NetworkModule.okHttpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    } catch (e: Exception) {
        Logger.e("CommentFraud", "rawCurlGuest 异常: ${e.message}")
        null
    }
}
