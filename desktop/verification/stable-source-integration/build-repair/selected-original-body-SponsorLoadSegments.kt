suspend fun loadSegments(
    bvid: String,
    cid: Long = 0L,
    categories: List<String> = SponsorCategory.ALL_CATEGORIES,
    baseUrl: String = DEFAULT_BASE_URL
): List<SponsorSegment> = withContext(Dispatchers.IO) {
    if (categories.isEmpty()) return@withContext emptyList()
    val url = buildSponsorBlockSegmentsUrl(
        baseUrl = baseUrl.trimEnd('/'),
        bvid = bvid,
        cid = cid,
        categories = categories.distinct().sorted()
    )
    val nowMs = android.os.SystemClock.elapsedRealtime()
    val (cached, cacheGeneration) = synchronized(segmentCache) {
        segmentCache[url]?.takeIf { it.expiresAtMs > nowMs } to segmentCacheGeneration
    }
    if (cached != null) return@withContext cached.segments

    val request = Request.Builder()
        .url(url)
        .header("User-Agent", "BiliPai/2.4.1")
        .get()
        .build()
    val segments = client.newCall(request).execute().use { response ->
        when (response.code) {
            200 -> json.decodeFromString<List<SponsorSegment>>(response.body.string())
            404 -> emptyList()
            else -> throw SponsorBlockRequestException(response.code, response.body.string().take(160))
        }
    }
    val ttlMs = if (segments.isEmpty()) EMPTY_SEGMENT_CACHE_TTL_MS else SEGMENT_CACHE_TTL_MS
    synchronized(segmentCache) {
        // A submission can invalidate data while this request is in flight.
        if (cacheGeneration == segmentCacheGeneration) {
            segmentCache.remove(url)
            segmentCache[url] = CachedSegments(
                segments = segments,
                expiresAtMs = android.os.SystemClock.elapsedRealtime() + ttlMs
            )
            while (segmentCache.size > SEGMENT_CACHE_LIMIT) {
                segmentCache.remove(segmentCache.keys.first())
            }
        }
    }
    android.util.Log.d(TAG, "获取到 ${segments.size} 个空降片段 for $bvid/$cid")
    segments
}
