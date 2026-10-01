// File: data/repository/ExternalPlaylistRepository.kt
package com.android.purebilibili.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.android.purebilibili.core.network.NetworkModule
import com.android.purebilibili.core.store.settingsDataStore
import com.android.purebilibili.core.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** 外部歌单（网易云 / QQ 音乐）导入与匹配。 */
object ExternalPlaylistRepository {

    enum class Source(val label: String) {
        NETEASE("网易云歌单"),
        QQ("QQ音乐歌单")
    }

    @Serializable
    data class ExternalTrack(
        val title: String,
        val artists: List<String> = emptyList(),
        val album: String = "",
        val durationMs: Long = 0L,
        val coverUrl: String = "",
        val translatedTitle: String? = null
    )

    @Serializable
    data class ExternalPlaylistMeta(
        val source: Source,
        val playlistId: String,
        val name: String,
        val coverUrl: String = "",
        val author: String = "",
        val tracks: List<ExternalTrack> = emptyList()
    )

    @Serializable
    data class MatchedVideo(
        val bvid: String,
        val title: String,
        val cover: String = "",
        val author: String = "",
        val durationSec: Long = 0L
    )

    data class MatchOutcome(
        val track: ExternalTrack,
        val video: MatchedVideo?
    )

    data class ImportCheckpoint(
        val playlist: ExternalPlaylistMeta,
        val outcomes: List<MatchOutcome>,
        val completedCount: Int,
    )

    @Serializable
    private data class SerializableMatchOutcome(
        val track: ExternalTrack,
        val video: MatchedVideo? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val importCheckpointKey = stringPreferencesKey("external_playlist_import_checkpoint_v1")

    suspend fun loadImportCheckpoint(context: Context): ImportCheckpoint? = runCatching {
        val raw = context.settingsDataStore.data.first()[importCheckpointKey] ?: return null
        val stored = json.decodeFromString<StoredImportCheckpoint>(raw)
        ImportCheckpoint(
            playlist = stored.playlist,
            outcomes = stored.outcomes.map { MatchOutcome(it.track, it.video) },
            completedCount = stored.completedCount.coerceIn(0, stored.playlist.tracks.size),
        )
    }.getOrNull()

    suspend fun saveImportCheckpoint(context: Context, checkpoint: ImportCheckpoint) {
        val stored = StoredImportCheckpoint(
            playlist = checkpoint.playlist,
            outcomes = checkpoint.outcomes.map { SerializableMatchOutcome(it.track, it.video) },
            completedCount = checkpoint.completedCount.coerceIn(0, checkpoint.playlist.tracks.size),
        )
        context.settingsDataStore.edit { it[importCheckpointKey] = json.encodeToString(stored) }
    }

    suspend fun clearImportCheckpoint(context: Context) {
        context.settingsDataStore.edit { it.remove(importCheckpointKey) }
    }

    @Serializable
    private data class StoredImportCheckpoint(
        val playlist: ExternalPlaylistMeta,
        val outcomes: List<SerializableMatchOutcome>,
        val completedCount: Int,
    )

    // 匹配过滤规则：音MAD/现场/翻唱/科普/运动分区一律排除，避免匹配到非音乐内容
    private val BLACKLIST_ZONES = setOf(26, 29, 31, 201, 238)
    private const val DURATION_TOLERANCE_SEC = 20L
    private const val MATCH_DELAY_MS = 1200L

    // ---------------------------------------------------------------- 输入解析

    /** 从分享链接或纯数字 id 解析 (来源, 歌单 id)；来源无法识别时返回 null。 */
    fun parsePlaylistInput(input: String): Pair<Source, String>? {
        val text = input.trim()
        if (text.isEmpty()) return null
        return when {
            text.contains("163.com") || text.contains("music.163") -> {
                extractId(text, listOf("id=", "/playlist/", "#/playlist"))?.let { Source.NETEASE to it }
                    ?: text.filter(Char::isDigit).takeIf { it.isNotEmpty() }?.let { Source.NETEASE to it }
            }
            text.contains("qq.com") || text.contains("y.qq.com") -> {
                extractId(text, listOf("dissid=", "id=", "/playlist/"))?.let { Source.QQ to it }
                    ?: text.filter(Char::isDigit).takeIf { it.isNotEmpty() }?.let { Source.QQ to it }
            }
            text.matches(Regex("\\d{4,}")) -> null // 纯数字无法判断来源，交给调用方指定
            else -> null
        }
    }

    private fun extractId(text: String, markers: List<String>): String? {
        for (marker in markers) {
            val index = text.indexOf(marker)
            if (index < 0) continue
            val rest = text.substring(index + marker.length)
            val id = rest.takeWhile { it.isDigit() }
            if (id.isNotEmpty()) return id
        }
        return null
    }

    // ---------------------------------------------------------------- 歌单拉取

    suspend fun fetchPlaylist(
        source: Source,
        playlistId: String,
        explicitId: String? = null
    ): Result<ExternalPlaylistMeta> = withContext(Dispatchers.IO) {
        val id = explicitId ?: playlistId
        if (id.isBlank()) return@withContext Result.failure(IllegalArgumentException("歌单 id 不能为空"))
        when (source) {
            Source.QQ -> fetchQqPlaylist(id)
            Source.NETEASE -> fetchNeteasePlaylist(id)
        }
    }

    private fun fetchQqPlaylist(id: String): Result<ExternalPlaylistMeta> = runCatching {
        val url = "https://c.y.qq.com/v8/fcg-bin/fcg_v8_playlist_cp.fcg" +
            "?id=$id&format=json&newsong=1&platform=jqspaframe.json"
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("Referer", "https://y.qq.com")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
            )
            .build()
        NetworkModule.okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val root = json.parseToJsonElement(body).jsonObject
            val cd = root["data"]?.jsonObject?.get("cdlist")?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?: throw IllegalStateException("未找到该 QQ 音乐歌单")
            val songlist = cd["songlist"]?.jsonArray.orEmpty()
            val tracks = songlist.mapNotNull { element ->
                val song = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
                val title = song["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (title.isBlank()) return@mapNotNull null
                ExternalTrack(
                    title = title,
                    artists = song["singer"]?.jsonArray.orEmpty()
                        .mapNotNull { runCatching { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.getOrNull() },
                    album = song["album"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    durationMs = (song["interval"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L) * 1000L,
                    coverUrl = song["album"]?.jsonObject?.get("mid")?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?.let { "https://y.gtimg.cn/music/photo_new/T002R300x300M000$it.jpg" }
                        .orEmpty(),
                    translatedTitle = song["subtitle"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                )
            }
            ExternalPlaylistMeta(
                source = Source.QQ,
                playlistId = id,
                name = cd["dissname"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "QQ音乐歌单" },
                coverUrl = cd["logo"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                author = cd["nickname"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                tracks = tracks
            )
        }
    }.onFailure { Logger.w("ExternalPlaylist", "fetch QQ playlist failed: ${it.message}") }

    private fun fetchNeteasePlaylist(id: String): Result<ExternalPlaylistMeta> = runCatching {
        if (!id.matches(Regex("\\d+"))) {
            throw IllegalStateException("网易云歌单 id 格式不正确")
        }
        val path = "/api/v6/playlist/detail"
        val payload = """{"s":"0","id":"$id","n":"1000","t":"0",""""" +
            """header":{"osver":"16.3","deviceId":"265B59C3-C5DE-4876-8A33-FD52CD5C2960","os":"ios","appver":"9.0.90","__csrf":""},"e_r":false}"""
        val params = eapiEncrypt(path, payload)
        val request = okhttp3.Request.Builder()
            .url("https://interface3.music.163.com/eapi$path")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Referer", "https://music.163.com")
            .header("User-Agent", "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)")
            .header(
                "Cookie",
                "osver=16.3; deviceId=265B59C3-C5DE-4876-8A33-FD52CD5C2960; os=ios; appver=9.0.90"
            )
            .post(okhttp3.FormBody.Builder().add("params", params).build())
            .build()
        NetworkModule.okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val root = json.parseToJsonElement(body).jsonObject
            val code = root["code"]?.jsonPrimitive?.intOrNull
            if (code != 200 && code != 0) throw IllegalStateException("网易云接口返回错误码 $code")
            val playlist = root["playlist"]?.jsonObject
                ?: throw IllegalStateException("未找到该网易云歌单")
            val tracks = playlist["tracks"]?.jsonArray.orEmpty().mapNotNull { element ->
                val track = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
                val title = track["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (title.isBlank()) return@mapNotNull null
                ExternalTrack(
                    title = title,
                    artists = track["ar"]?.jsonArray.orEmpty()
                        .mapNotNull { runCatching { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.getOrNull() },
                    album = track["al"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    durationMs = track["dt"]?.jsonPrimitive?.longOrNull ?: 0L,
                    coverUrl = track["al"]?.jsonObject?.get("picUrl")?.jsonPrimitive?.contentOrNull
                        ?.replace("http://", "https://").orEmpty(),
                    translatedTitle = track["tns"]?.jsonArray?.firstOrNull()
                        ?.jsonPrimitive?.contentOrNull
                )
            }
            ExternalPlaylistMeta(
                source = Source.NETEASE,
                playlistId = id,
                name = playlist["name"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "网易云歌单" },
                coverUrl = playlist["coverImgUrl"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                author = playlist["creator"]?.jsonObject?.get("nickname")?.jsonPrimitive?.contentOrNull.orEmpty(),
                tracks = tracks
            )
        }
    }.onFailure { Logger.w("ExternalPlaylist", "fetch netease playlist failed: ${it.message}") }

    /**
     * 网易云 eapi 加密：payload = md5 签名的路径-数据串，AES-128-ECB 后转大写 hex。
     * 与公开的 NeteaseCloudMusicApi 协议一致。
     */
    private fun eapiEncrypt(path: String, payload: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
            .digest("nobody${path}use${payload}md5forencrypt".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val data = "$path-36cd479b6b5-$payload-36cd479b6b5-$digest"
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(), "AES"))
        return cipher.doFinal(data.toByteArray()).joinToString("") { "%02X".format(it) }
    }

    // ---------------------------------------------------------------- B站匹配

    /**
     * 在 B 站搜索一首歌并返回最佳匹配。
     * 策略：黑名单分区过滤 → 时长差 ≤20s → 取首个通过项（搜索排序已含相关性）。
     */
    suspend fun matchTrack(track: ExternalTrack): MatchOutcome = withContext(Dispatchers.IO) {
        val query = buildString {
            append(track.title)
            track.translatedTitle?.takeIf { it.isNotBlank() }?.let { append(' ').append(it) }
            val artists = track.artists.joinToString(" ")
            if (artists.isNotBlank()) append(" - ").append(artists)
        }
        val video = runCatching {
            val (items, _) = SearchRepository.search(keyword = query, page = 1)
                .getOrElse { return@withContext MatchOutcome(track, null) }
            items.firstOrNull { candidate ->
                candidate.tid !in BLACKLIST_ZONES &&
                    candidate.duration in 1..Int.MAX_VALUE &&
                    track.durationMs > 0L &&
                    kotlin.math.abs(candidate.duration.toLong() - track.durationMs / 1000L) <= DURATION_TOLERANCE_SEC
            }?.let {
                MatchedVideo(
                    bvid = it.bvid,
                    title = it.title,
                    cover = it.pic,
                    author = it.owner.name,
                    durationSec = it.duration.toLong()
                )
            }
        }.getOrNull()
        MatchOutcome(track, video)
    }

    suspend fun matchTracks(
        tracks: List<ExternalTrack>,
        startIndex: Int = 0,
        onProgress: suspend (completed: Int, total: Int, outcome: MatchOutcome) -> Unit
    ): List<MatchOutcome> {
        val results = mutableListOf<MatchOutcome>()
        tracks.forEachIndexed { index, track ->
            if (index < startIndex) return@forEachIndexed
            val outcome = matchTrack(track)
            results += outcome
            onProgress(index + 1, tracks.size, outcome)
            if (index != tracks.lastIndex) kotlinx.coroutines.delay(MATCH_DELAY_MS)
        }
        return results
    }

    fun buildSearchQueryForManualMatch(track: ExternalTrack): String {
        return buildString {
            append(track.title)
            val artists = track.artists.joinToString(" ")
            if (artists.isNotBlank()) append(' ').append(artists)
        }
    }
}
