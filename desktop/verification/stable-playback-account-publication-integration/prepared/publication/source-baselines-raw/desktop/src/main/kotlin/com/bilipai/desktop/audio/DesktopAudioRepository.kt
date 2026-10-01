package com.bilipai.desktop.audio

import com.android.purebilibili.core.network.AudioApi
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.FavoriteRequestException
import com.android.purebilibili.feature.audio.library.*
import com.android.purebilibili.feature.audio.lyrics.*
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.android.purebilibili.feature.video.subtitle.SubtitleCue
import com.android.purebilibili.feature.video.subtitle.SubtitleTrackMeta
import com.android.purebilibili.feature.video.subtitle.mapPlayerInfoSubtitleTracks
import com.android.purebilibili.feature.video.subtitle.parseBiliSubtitleBody
import com.bilipai.desktop.danmaku.ApiDesktopDanmakuSource
import com.bilipai.desktop.data.BiliApiException
import com.bilipai.desktop.data.DesktopCommunityRepository
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.player.PlaybackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal data class PreparedListenAudio(val item: PlaylistItem, val source: PlaybackSource, val aid: Long = 0, val songLyrics: String? = null, val songInfo: SongInfoData? = null)

/** Only transport and native stream adaptation live here; library pagination and lyrics remain upstream source. */
internal class DesktopAudioRepository(val repository: DesktopRepository, private val community: DesktopCommunityRepository) : ListenPlaybackDataSource {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val retrofit = Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(repository.httpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
    private val api = retrofit.create(BilibiliApi::class.java)
    private val audioApi = retrofit.create(AudioApi::class.java)
    private val lyricsClient = boundedAudioClient(OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build())
    private val subtitleClient = boundedAudioClient(repository.httpClient)
    private val fileLyricsCache = FileLyricsCache(Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "BiliPaiWindows", "lyrics").toFile())
    private val lyricsCacheMutex = Mutex()
    override val lyrics = LyricsRepository(listOf(NeteaseLyricsProvider(lyricsClient), QqMusicLyricsProvider(lyricsClient), KugouLyricsProvider(lyricsClient)),
        object : LyricsCache {
            override suspend fun read(key: String) = lyricsCacheMutex.withLock { fileLyricsCache.read(key) }
            override suspend fun write(key: String, document: LyricDocument) = lyricsCacheMutex.withLock { fileLyricsCache.write(key, document) }
        })
    val librarySource = object : ListenVideoLibraryDataSource {
        override suspend fun ownedFolders(mid: Long): Result<List<FavFolder>> = libraryRead {
            val response = api.getFavFolders(mid)
            favoriteCode(response.code, response.message)
            response.data?.list.orEmpty().map { it.copy(source = FavFolderSource.OWNED) }
        }
        override suspend fun collectedFolders(mid: Long, page: Int): Result<ListenVideoCollectedFoldersPage> = libraryRead {
            val response = api.getCollectedFavFolders(mid, page, 20, "web")
            favoriteCode(response.code, response.message)
            val folders = response.data?.list.orEmpty().map { it.copy(source = FavFolderSource.SUBSCRIBED) }
            val total = response.data?.count ?: 0
            ListenVideoCollectedFoldersPage(folders, if (total > 0) page * 20 < total else folders.size >= 20)
        }
        override suspend fun folderPage(mediaId: Long, page: Int): Result<FavoriteResourceData> = libraryRead {
            val response = api.getFavoriteList(mediaId = mediaId, pn = page, ps = 20)
            favoriteCode(response.code, response.message)
            response.data ?: throw IOException("收藏夹内容为空。")
        }
        override suspend fun albumPage(seasonId: Long, page: Int): Result<FavoriteResourceData> = libraryRead {
            val response = api.getFavoriteSeasonList(seasonId = seasonId, pn = page)
            favoriteCode(response.code, response.message)
            response.data ?: throw IOException("合集内容为空。")
        }
    }

    override suspend fun prepare(item: PlaylistItem): PreparedListenAudio = withContext(Dispatchers.IO) {
        val epoch = repository.sessionEpoch
        suspend fun ensureAccount() {
            currentCoroutineContext().ensureActive()
            check(repository.sessionEpoch == epoch) { "账号已切换，请重新加载音频。" }
        }
        val sid = Regex("(?i)^au([1-9][0-9]*)$").matchEntire(item.bvid)?.groupValues?.get(1)?.toLongOrNull()
        if (sid != null) {
            repository.ensureSession()
            ensureAccount()
            val infoReply = audioApi.getSongInfo(sid)
            ensureAccount()
            songCode(infoReply.code, infoReply.msg)
            val info = infoReply.data ?: throw IOException("音频信息为空。")
            val streamReply = audioApi.getSongStream(sid)
            ensureAccount()
            songCode(streamReply.code, streamReply.msg)
            val address = streamReply.data?.cdns.orEmpty().firstOrNull { it.toHttpUrlOrNullAudio() != null }
                ?: throw IOException("没有可播放的音频地址。")
            val songLyrics = try { audioApi.getSongLyric(sid).takeIf { it.code == 0 }?.data }
                catch (failure: Exception) { if (failure is CancellationException) throw failure; null }
            ensureAccount()
            val cookies = repository.httpClient.cookieJar.loadForRequest(requireNotNull(address.toHttpUrlOrNullAudio()))
                .joinToString("; ") { "${it.name}=${it.value}" }
            val actual = item.copy(title = info.title.ifBlank { item.bvid }, cover = info.cover, owner = info.author.ifBlank { info.uname }, duration = info.duration.toLong())
            ensureAccount()
            PreparedListenAudio(actual, PlaybackSource(address, cookieHeader = cookies, title = actual.title),
                songLyrics = songLyrics, songInfo = info)
        } else {
            val details = repository.videoDetails(item.bvid)
            val part = details.pages.indexOfFirst { it.cid == item.cid }.takeIf { it >= 0 } ?: 0
            ensureAccount()
            val media = repository.playback(details, part)
            ensureAccount()
            val page = details.pages.getOrNull(part) ?: throw IOException("视频没有可播放分 P。")
            val actual = item.copy(bvid = details.bvid, cid = page.cid, title = item.title.takeUnless { it.isBlank() || it == item.bvid } ?: details.title,
                cover = details.cover, owner = details.author, duration = page.duration)
            // DASH audio is loaded as the sole stream. Combined streams retain the same native vid=no policy.
            PreparedListenAudio(actual, PlaybackSource(media.audioUrl ?: media.videoUrl, referer = media.referer,
                cookieHeader = media.cookieHeader, title = actual.title,
                progressiveSegments = if (media.audioUrl == null) media.progressiveSegments else emptyList()), details.aid)
        }
    }

    override suspend fun subtitleTracks(item: PlaylistItem): List<SubtitleTrackMeta> {
        if (item.cid <= 0 || item.bvid.startsWith("au", true)) return emptyList()
        return mapPlayerInfoSubtitleTracks(community.playerMetadata(item.bvid, item.cid).subtitle?.subtitles.orEmpty()).take(50)
    }

    override suspend fun subtitleCues(track: SubtitleTrackMeta): List<SubtitleCue> {
        val url = ApiDesktopDanmakuSource.trustedSpecialUrl(track.subtitleUrl)
        val text = subtitleClient.readAudioText(url)
        return withContext(Dispatchers.Default) { parseBiliSubtitleBody(text).take(50_000) }
    }

    private suspend fun <T> libraryRead(action: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try { repository.requireAccount(); repository.ensureSession(); Result.success(action()) }
        catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            Result.failure(when (failure) {
                is BiliApiException -> FavoriteRequestException(apiCode = failure.apiCode, message = failure.message.orEmpty(), cause = failure)
                is HttpException -> FavoriteRequestException(httpCode = failure.code(), message = "HTTP ${failure.code()}", cause = failure)
                else -> failure
            })
        }
    }

    private fun favoriteCode(code: Int, message: String) { if (code != 0) throw FavoriteRequestException(apiCode = code, message = message) }
    private fun songCode(code: Int, message: String) { if (code != 0) throw BiliApiException(code, message) }
}

private fun String.toHttpUrlOrNullAudio(): HttpUrl? = toHttpUrlOrNull()?.takeIf { it.scheme == "https" || it.scheme == "http" }

/** The upstream providers use ResponseBody.string(); bound their source before that allocation. */
private fun boundedAudioClient(client: OkHttpClient): OkHttpClient = client.newBuilder().addNetworkInterceptor { chain ->
    val response = chain.proceed(chain.request())
    if (response.body.contentLength() > MAX_AUDIO_TEXT_BYTES) { response.close(); throw IOException("歌词或字幕文档过大。") }
    response.newBuilder().body(AudioTextBody(response.body)).build()
}.build()

private class AudioTextBody(private val delegate: ResponseBody) : ResponseBody() {
    private val bounded = object : ForwardingSource(delegate.source()) {
        private var consumed = 0L
        override fun read(sink: Buffer, byteCount: Long): Long {
            val count = super.read(sink, byteCount.coerceAtMost(MAX_AUDIO_TEXT_BYTES - consumed + 1))
            if (count > 0) consumed += count
            if (consumed > MAX_AUDIO_TEXT_BYTES) throw IOException("歌词或字幕文档过大。")
            return count
        }
    }.buffer()
    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()
    override fun source(): BufferedSource = bounded
}

private suspend fun OkHttpClient.readAudioText(url: String): String = suspendCancellableCoroutine { continuation ->
    val call = newCall(Request.Builder().url(url).build())
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWith(Result.failure(e)) }
        override fun onResponse(call: Call, response: Response) {
            val result = runCatching { response.use { check(it.isSuccessful) { "字幕请求失败：HTTP ${it.code}" }; it.body.string() } }
            if (continuation.isActive) continuation.resumeWith(result)
        }
    })
}

private const val MAX_AUDIO_TEXT_BYTES = 8L * 1024 * 1024
