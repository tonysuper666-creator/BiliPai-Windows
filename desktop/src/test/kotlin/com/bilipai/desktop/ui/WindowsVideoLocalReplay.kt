package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.bilipai.desktop.data.DesktopRepository
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import kotlin.math.sin

/** Test-only official-shaped responses on one actual private Main Repository. Never an alternate Root or controller. */
internal class WindowsVideoLocalReplay private constructor(private val report: Path, val bvid: String) : AutoCloseable {
    private val requests = CopyOnWriteArrayList<JsonObject>()
    private val mediaRequests = CopyOnWriteArrayList<JsonObject>()
    private val media = report.resolve("local-replay-media")
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    private val executor = Executors.newFixedThreadPool(2) { task -> Thread(task, "Owned test loopback media").apply { isDaemon = true } }
    private val json = Json { ignoreUnknownKeys = true }
    private val cid = 7007L
    private val aid = 170001L
    private val collectionInput = System.getProperty("bilipai.validation.collectionInput") == "true"
    private val metadataInput = System.getProperty("bilipai.validation.metadataInput") == "true"
    private val bgmInput = System.getProperty("bilipai.validation.bgmInput") == "true"
    private val mediaSeconds = if (bgmInput) 180 else SECONDS
    private val secondCid = 7008L
    private val base: String get() = "http://127.0.0.1:${server.address.port}"
    private var installed = false
    init {
        require(!bgmInput || collectionInput) { "BGM replay needs the original two-part collection flow" }
        require(!Files.exists(media, NOFOLLOW_LINKS)); Files.createDirectory(media)
        createVideo(media.resolve("video.avi").toFile(), mediaSeconds); createAudio(media.resolve("audio.wav").toFile(), mediaSeconds)
        server.executor = executor
        mapOf("/video.avi" to "video/x-msvideo", "/audio.wav" to "audio/wav").forEach { (path, type) ->
            server.createContext(path) { exchange ->
                try {
                    require(exchange.remoteAddress.address.isLoopbackAddress && exchange.requestURI.path == path)
                    require(exchange.requestMethod in setOf("GET", "HEAD"))
                    val bytes = Files.readAllBytes(media.resolve(path.removePrefix("/")))
                    val range = exchange.requestHeaders.getFirst("Range")
                    var start = 0L; var end = bytes.size.toLong() - 1
                    if (range != null) {
                        val match = Regex("bytes=(\\d+)-(\\d*)").matchEntire(range)
                        if (match == null) { exchange.sendResponseHeaders(416, -1); return@createContext }
                        start = match.groupValues[1].toLongOrNull() ?: -1
                        end = match.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull() ?: end
                        if (start !in 0 until bytes.size.toLong() || end < start) {
                            exchange.responseHeaders.set("Content-Range", "bytes */${bytes.size}")
                            exchange.sendResponseHeaders(416, -1); return@createContext
                        }
                        end = minOf(end, bytes.size.toLong() - 1)
                        exchange.responseHeaders.set("Content-Range", "bytes $start-$end/${bytes.size}")
                    }
                    val count = end - start + 1
                    exchange.responseHeaders.set("Accept-Ranges", "bytes")
                    exchange.responseHeaders.set("Content-Type", type)
                    exchange.responseHeaders.set("Content-Length", count.toString())
                    mediaRequests.add(buildJsonObject { put("file", path.removePrefix("/")); put("method", exchange.requestMethod)
                        put("rangeRequested", range != null); put("firstByte", start); put("lastByte", end); put("bytes", count) })
                    exchange.sendResponseHeaders(if (range == null) 200 else 206, if (exchange.requestMethod == "HEAD") -1 else count)
                    if (exchange.requestMethod == "GET") exchange.responseBody.write(bytes, start.toInt(), count.toInt())
                } finally { exchange.close() }
            }
        }
        server.start()
    }
    fun install(repository: DesktopRepository, owns: () -> Boolean) {
        require(!installed && owns() && repository.account.value == null)
        val epoch = repository.sessionEpoch
        val client = repository.httpClient.newBuilder().followRedirects(false).followSslRedirects(false).addInterceptor { chain ->
            fun requireOwner() = require(owns() && repository.sessionEpoch == epoch && repository.account.value == null) {
                "LOCAL replay original guest Root/session owner retired"
            }
            requireOwner()
            val request = chain.request(); val url = request.url; val path = url.encodedPath
            requests.add(buildJsonObject { put("stage", "requestObserved"); put("scheme", url.scheme)
                put("host", url.host); put("port", url.port); put("path", path); put("method", request.method)
                put("hasQuery", url.encodedQuery != null); put("hasFragment", url.encodedFragment != null) })
            val ownedMedia = url.scheme == "http" && url.host == "127.0.0.1" && url.port == server.address.port &&
                path in setOf("/video.avi", "/audio.wav") && request.method in setOf("GET", "HEAD") &&
                url.username.isEmpty() && url.password.isEmpty() && url.encodedQuery == null && url.encodedFragment == null
            if (ownedMedia) {
                // The actual Root native-media proxy uses this SAME repository client.
                // Only the exact live owned server may reach a socket; redirects are disabled.
                requireOwner()
                val response = chain.proceed(request)
                try {
                    requireOwner()
                    require(response.request.url == url && response.priorResponse == null) {
                        "LOCAL replay owned-media response origin/redirect identity changed"
                    }
                    require(response.code in setOf(200, 206)) { "LOCAL replay owned-media server returned non-success status" }
                    requests.add(buildJsonObject { put("path", path); put("method", request.method)
                        put("exactOwnedLoopbackMedia", true); put("originalRepositoryClientUsed", true)
                        put("ownerEpochRecheckedBeforeAfter", true); put("redirectsAllowed", false); put("status", response.code) })
                    return@addInterceptor response
                } catch (failure: Throwable) { response.close(); throw failure }
            }
            // API responses remain memory-only. Every other origin is forbidden, never proceeded.
            require(url.host in setOf("api.bilibili.com", "api.vc.bilibili.com", "app.bilibili.com")) {
                "LOCAL replay forbids requests outside mapped API or exact owned loopback media"
            }
            if (collectionInput) require(path !in setOf("/x/v3/fav/season/fav", "/x/v3/fav/season/unfav")) {
                "Collection layout replay must not submit subscription mutations"
            }
            if (metadataInput) require(path != "/x/relation/modify") {
                "Creator metadata layout replay must not submit follow mutations"
            }
            if (bgmInput) require(request.method == "GET") {
                "BGM guest replay must not submit any account mutation"
            }
            val body = when (path) {
                "/x/web-interface/view" -> {
                    val requested = request.url.queryParameter("bvid")
                    require(requested == null || requested == bvid)
                    val original = """{"code":0,"data":{"bvid":"$bvid","aid":$aid,"cid":$cid,"title":"Windows local layout replay","desc":"LOCAL REPLAY — media/layout only; not live Bilibili acceptance","pic":"","owner":{"mid":1,"name":"Local fixture","face":""},"stat":{"view":0,"reply":0,"like":0},"dimension":{"width":320,"height":180,"rotate":0},"pages":[{"cid":$cid,"page":1,"part":"Local replay","duration":$mediaSeconds,"dimension":{"width":320,"height":180,"rotate":0}}]}}"""
                    val collected = if (collectionInput) collectionMetadata(original) else original
                    if (metadataInput) videoMetadata(collected) else collected
                }
                "/x/player/wbi/playurl", "/x/player/playurl" -> {
                    val allowedCids = if (collectionInput) setOf(cid.toString(), secondCid.toString()) else setOf(cid.toString())
                    require(request.url.queryParameter("bvid") == bvid && request.url.queryParameter("cid") in allowedCids)
                    if (collectionInput) requests.add(buildJsonObject {
                        put("collectionPlayurlCid", requireNotNull(request.url.queryParameter("cid")).toLong())
                    })
                    """{"code":0,"data":{"quality":32,"format":"dash","timelength":${mediaSeconds * 1000},"accept_quality":[32],"accept_description":["Local replay"],"video_codecid":7,"dash":{"duration":$mediaSeconds,"minBufferTime":1.5,"video":[{"id":32,"baseUrl":"$base/video.avi","bandwidth":1000000,"mime_type":"video/x-msvideo","codecs":"avc1.640028","width":320,"height":180,"frameRate":"20","codecid":7}],"audio":[{"id":30280,"baseUrl":"$base/audio.wav","bandwidth":768000,"mime_type":"audio/wav","codecs":"pcm_s16le"}]}}}"""
                }
                "/x/web-interface/nav" -> """{"code":0,"data":{"isLogin":false,"mid":0,"wbi_img":{"img_url":"https://fixture.invalid/${"a".repeat(32)}.png","sub_url":"https://fixture.invalid/${"b".repeat(32)}.png"}}}"""
                "/x/player/v2", "/x/player/wbi/v2" -> {
                    val requestedCid = if (collectionInput) request.url.queryParameter("cid")?.toLongOrNull()
                        ?.also { require(it == cid || it == secondCid) } ?: cid else cid
                    val chapters = if (System.getProperty("bilipai.validation.featureInput") == "true")
                        """[{"content":"开场","from":0,"to":20},{"content":"中段","from":20,"to":40},{"content":"收尾","from":40,"to":60}]""" else "[]"
                    val bgm = if (bgmInput) ",\"bgm_info\":${bgmSong(if (requestedCid == cid) "fixture-p1" else "fixture-p2-a")}" else ""
                    """{"code":0,"data":{"aid":$aid,"cid":$requestedCid,"bvid":"$bvid","subtitle":{"subtitles":[]},"view_points":$chapters$bgm}}"""
                }
                "/x/web-interface/archive/relation" -> if (collectionInput)
                    """{"code":0,"data":{"like":false,"favorite":false,"season_fav":false,"coin":0,"dislike":false}}"""
                    else """{"code":-404,"message":"Unmapped LOCAL replay endpoint"}"""
                "/x/copyright-music-publicity/bgm/multiple/music" -> if (bgmInput) {
                    val part = requireNotNull(url.queryParameter("cid")?.toLongOrNull())
                    require(url.queryParameter("aid")?.toLongOrNull() == aid && part in setOf(cid, secondCid))
                    // Empty P1 list exercises the original single-song fallback; P2
                    // uses the real DTO list and its original selection strip.
                    val songs = if (part == cid) emptyList() else listOf("fixture-p2-a", "fixture-p2-b")
                    requests.add(buildJsonObject { put("bgmStage", "multiple"); put("bgmCid", part); put("songCount", songs.size) })
                    buildJsonObject { put("code", 0); put("data", buildJsonObject {
                        put("list", JsonArray(songs.map(::bgmSong)))
                    }) }.toString()
                } else """{"code":-404,"message":"Unmapped LOCAL replay endpoint"}"""
                "/x/copyright-music-publicity/bgm/detail" -> if (bgmInput) {
                    val id = requireNotNull(url.queryParameter("music_id"))
                    val part = bgmCid(id)
                    require(url.queryParameter("relation_from") == "bgm_page" && url.queryParameter("cid")?.toLongOrNull() == part)
                    val requestedAid = url.queryParameter("aid")?.toLongOrNull() ?: 0L
                    require(requestedAid == 0L || requestedAid == aid)
                    requests.add(buildJsonObject { put("bgmStage", "detail"); put("musicId", id)
                        put("bgmCid", part); put("bgmAid", requestedAid) })
                    buildJsonObject { put("code", 0); put("data", buildJsonObject {
                        put("music_title", bgmTitle(id)); put("origin_artist", "本地艺人"); put("origin_artist_list", "本地艺人")
                        put("music_source", "LOCAL 原格式回放"); put("album", "私有验收"); put("mv_cover", "")
                        put("wish_listen", false); put("wish_count", 3); put("listen_pv", 42); put("music_hot", 18)
                        put("artists_list", buildJsonArray { add(buildJsonObject { put("mid", 0); put("name", "本地艺人"); put("face", "") }) })
                        put("music_comment", buildJsonObject { put("state", 0); put("nums", 0); put("oid", 0); put("page_type", 0) })
                        put("flow_attr", buildJsonObject { put("no_share", true); put("no_comment", true) })
                        put("hot_song_heat", buildJsonObject { put("last_heat", 18); put("song_heat", buildJsonArray {
                            add(buildJsonObject { put("date", 1728000000); put("heat", 18) })
                        }) })
                    }) }.toString()
                } else """{"code":-404,"message":"Unmapped LOCAL replay endpoint"}"""
                "/x/copyright-music-publicity/bgm/recommend_list" -> if (bgmInput) {
                    val id = requireNotNull(url.queryParameter("music_id")); val part = bgmCid(id)
                    val requestedCid = url.queryParameter("cid")?.toLongOrNull() ?: 0L
                    val requestedAid = url.queryParameter("aid")?.toLongOrNull() ?: 0L
                    require((requestedCid == 0L && requestedAid == 0L) || (requestedCid == part && requestedAid == aid))
                    val page = url.queryParameter("pn")?.toIntOrNull() ?: 1
                    val size = url.queryParameter("ps")?.toIntOrNull() ?: 5
                    require(page >= 1 && size == 5)
                    requests.add(buildJsonObject { put("bgmStage", "recommend"); put("musicId", id)
                        put("bgmCid", requestedCid); put("bgmAid", requestedAid); put("page", page); put("pageSize", size) })
                    buildJsonObject { put("code", 0); put("data", buildJsonObject { put("list", buildJsonArray {
                        if (page == 1) add(buildJsonObject { put("aid", aid); put("bvid", bvid); put("cid", part); put("cover", "")
                            put("title", "${bgmTitle(id)}关联视频"); put("mid", 0); put("up_nick_name", "本地验收")
                            put("play", 42); put("danmu", 0); put("duration", mediaSeconds); put("label", ""); put("label_list", JsonArray(emptyList())) })
                    }) }) }.toString()
                } else """{"code":-404,"message":"Unmapped LOCAL replay endpoint"}"""
                "/x/player/videoshot" -> """{"code":0,"data":{"index":[],"image":[]}}"""
                "/x/web-interface/archive/related", "/x/tag/archive/tags" -> """{"code":0,"data":[]}"""
                "/x/v2/reply/wbi/main", "/x/v2/reply/main" -> """{"code":0,"data":{"replies":[],"top_replies":[],"cursor":{"is_begin":true,"is_end":true,"all_count":0,"next":0,"prev":0},"page":{"count":0,"num":1,"size":20}}}"""
                "/x/web-interface/search/default" -> """{"code":0,"data":{"show_name":"","name":""}}"""
                "/x/web-interface/wbi/search/square", "/x/web-interface/search/square" -> """{"code":0,"data":{"trending":{"list":[]}}}"""
                "/x/web-interface/search/suggest" -> """{"code":0,"result":{"tag":[]}}"""
                else -> """{"code":-404,"message":"Unmapped LOCAL replay endpoint"}"""
            }
            requests.add(buildJsonObject { put("path", path); put("method", request.method); put("localReplay", true)
                put("mapped", !body.contains("Unmapped LOCAL")); put("bodyBytes", body.toByteArray(Charsets.UTF_8).size) })
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("LOCAL API replay")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(BilibiliApi::class.java)
        // Exact existing test transport/cache field whitelist; never sessions, credentials, VM, native state or physical stack.
        fun set(name: String, value: Any) {
            require(owns() && repository.sessionEpoch == epoch && repository.account.value == null)
            DesktopRepository::class.java.getDeclaredField(name).apply { check(trySetAccessible()) }.set(repository, value)
        }
        set("api", api); set("client", client); set("visitorInitialized", true); set("visitorGeneration", epoch)
        set("wbiKeys", "a".repeat(32) to "b".repeat(32)); set("wbiExpiresAt", System.currentTimeMillis() + 3_600_000)
        set("wbiGeneration", epoch); installed = true
    }
    fun writeReceipt() {
        require(installed)
        require(requests.any { it["path"]?.jsonPrimitive?.content == "/x/web-interface/view" })
        require(requests.any { it["path"]?.jsonPrimitive?.content in setOf("/x/player/wbi/playurl", "/x/player/playurl") })
        require(mediaRequests.any { it["file"]?.jsonPrimitive?.content == "video.avi" && it["method"]?.jsonPrimitive?.content == "GET" })
        require(mediaRequests.any { it["file"]?.jsonPrimitive?.content == "audio.wav" && it["method"]?.jsonPrimitive?.content == "GET" })
        if (collectionInput) {
            require(requests.any { it["collectionPlayurlCid"]?.jsonPrimitive?.longOrNull == secondCid })
            require(requests.none { it["path"]?.jsonPrimitive?.content in setOf("/x/v3/fav/season/fav", "/x/v3/fav/season/unfav") })
        }
        if (bgmInput) {
            requireBgmRequests("fixture-p1", cid, discovery = false)
            requireBgmRequests("fixture-p2-a", secondCid, discovery = true)
            requireBgmRequests("fixture-p2-b", secondCid, discovery = true)
            require(requests.count { it["bgmStage"]?.jsonPrimitive?.content == "multiple" &&
                it["bgmCid"]?.jsonPrimitive?.longOrNull == cid } >= 2) { "BGM P1 must be reloaded after P2 through the original collection" }
            require(requests.none { it["method"]?.jsonPrimitive?.content == "POST" })
        }
        Files.writeString(report.resolve("local-replay-receipt.json"), buildJsonObject {
            put("schema", 1); put("mode", "LOCAL_API_SHAPE_REAL_LOOPBACK_MEDIA_ACTUAL_MAIN_LAYOUT_ONLY")
            put("sameActualRepository", true); put("realBilibiliDataAccepted", false); put("realAccountUsed", false)
            put("newRootCreated", false); put("newPlayerCreated", false); put("newControllerCreated", false)
            put("originalVmStateWritten", false); put("actualNativeStateWritten", false); put("physicalStackWritten", false)
            put("metadataBvid", bvid); put("metadataCid", cid); put("mediaSeconds", mediaSeconds)
            put("chapterMetadataIsSynthetic", System.getProperty("bilipai.validation.featureInput") == "true")
            put("collectionMetadataIsSynthetic", collectionInput)
            put("collectionMetadataCid2", if (collectionInput) JsonPrimitive(secondCid) else JsonNull)
            put("collectionSubscriptionMutationSubmitted", false)
            put("videoMetadataIsSynthetic", metadataInput)
            put("bgmMetadataIsSynthetic", bgmInput); put("bgmDetailAndRecommendResponsesAreSynthetic", bgmInput)
            put("singleBgmDetailOnlyScope", bgmInput)
            put("singleBgmRecommendationRequested", requests.any { it["bgmStage"]?.jsonPrimitive?.content == "recommend" &&
                it["musicId"]?.jsonPrimitive?.content == "fixture-p1" })
            put("bgmAccountMutationSubmitted", false); put("commentsSent", false)
            put("creatorFollowMutationSubmitted", false)
            put("container", "MJPEG_AVI_PLUS_PCM_WAV"); put("qualityMetadataIsSynthetic", true)
            put("codecMetadataIsSynthetic", true); put("realDASHCodecAccepted", false)
            put("apiRequests", JsonArray(requests.toList())); put("loopbackRequests", JsonArray(mediaRequests.toList()))
        }.toString(), CREATE_NEW, WRITE)
    }
    private fun bgmCid(id: String): Long = when (id) {
        "fixture-p1" -> cid
        "fixture-p2-a", "fixture-p2-b" -> secondCid
        else -> error("Unknown synthetic BGM identity")
    }
    private fun bgmTitle(id: String): String = when (id) {
        "fixture-p1" -> "P1原音乐"
        "fixture-p2-a" -> "P2第一首"
        "fixture-p2-b" -> "P2第二首"
        else -> error("Unknown synthetic BGM identity")
    }
    private fun bgmSong(id: String): JsonObject = buildJsonObject {
        put("music_id", id); put("music_title", bgmTitle(id)); put("actor", "本地艺人"); put("cover_url", "")
        put("jump_url", "https://www.bilibili.com/music/detail?music_id=$id&aid=$aid&cid=${bgmCid(id)}")
    }
    fun requireBgmRequests(id: String, expectedCid: Long, discovery: Boolean) {
        require(bgmInput && bgmCid(id) == expectedCid)
        require(requests.any { it["bgmStage"]?.jsonPrimitive?.content == "detail" &&
            it["musicId"]?.jsonPrimitive?.content == id && it["bgmCid"]?.jsonPrimitive?.longOrNull == expectedCid })
        val recommendations = requests.filter { it["bgmStage"]?.jsonPrimitive?.content == "recommend" &&
            it["musicId"]?.jsonPrimitive?.content == id }
        if (discovery) {
            require(recommendations.any { it["bgmCid"]?.jsonPrimitive?.longOrNull == expectedCid })
        } else {
            // The original typed detail with showVideos=false loads only its
            // detail; recommendation requests belong to the multi-song selector.
            require(recommendations.isEmpty()) { "Original detail-only BGM route unexpectedly requested recommendation videos" }
        }
        require(requests.none { it["method"]?.jsonPrimitive?.content == "POST" })
    }
    private fun videoMetadata(original: String): String {
        val root = json.parseToJsonElement(original).jsonObject.toMutableMap()
        val data = root.getValue("data").jsonObject.toMutableMap()
        data["honor_reply"] = buildJsonObject { put("honor", buildJsonArray { add(buildJsonObject {
            put("aid", aid); put("type", 2); put("weekly_recommend_num", 390)
            put("honor_name", "每周必看验收"); put("honor_url", "bilibili://popular/weekly?number=390")
        }) }) }
        data["argue_info"] = buildJsonObject { put("argue_msg", "演绎内容，仅作原版声明布局验收") }
        data["rights"] = buildJsonObject { put("no_reprint", 1); put("is_cooperation", 1) }
        data["staff"] = buildJsonArray { add(buildJsonObject {
            put("mid", 2); put("name", "原版团队验收"); put("title", "联合创作"); put("face", "")
        }) }
        root["data"] = JsonObject(data)
        return JsonObject(root).toString()
    }
    private fun collectionMetadata(original: String): String {
        val root = json.parseToJsonElement(original).jsonObject.toMutableMap()
        val data = root.getValue("data").jsonObject.toMutableMap()
        val pages = buildJsonArray {
            add(buildJsonObject { put("cid", cid); put("page", 1); put("part", "Local replay P1"); put("duration", mediaSeconds) })
            add(buildJsonObject { put("cid", secondCid); put("page", 2); put("part", "Local replay P2"); put("duration", mediaSeconds) })
        }
        data["pages"] = pages
        data["ugc_season"] = buildJsonObject {
            put("id", 5007); put("title", "Local fixture collection"); put("mid", 1); put("ep_count", 1)
            put("intro", "LOCAL collection intro — original full UI and queue only"); put("cover", "")
            put("sections", buildJsonArray { add(buildJsonObject {
                put("id", 1); put("season_id", 5007); put("title", "本地合集分区")
                put("episodes", buildJsonArray { add(buildJsonObject {
                    put("id", aid); put("aid", aid); put("bvid", bvid); put("cid", cid)
                    put("title", "Windows local layout replay"); put("pages", pages)
                    put("arc", buildJsonObject { put("aid", aid); put("title", "Windows local layout replay")
                        put("pic", ""); put("duration", mediaSeconds); put("stat", buildJsonObject { put("view", 123) }) })
                }) })
            }) })
        }
        root["data"] = JsonObject(data)
        return JsonObject(root).toString()
    }
    fun writeFailureReceipt() {
        Files.writeString(report.resolve("failure-local-replay.json"), buildJsonObject {
            put("installed", installed); put("ownedMediaPort", server.address.port)
            put("requests", JsonArray(requests.toList())); put("loopbackRequests", JsonArray(mediaRequests.toList()))
            put("realAccountUsed", false); put("headersOrQueryValuesRecorded", false)
        }.toString(), CREATE_NEW, WRITE)
    }
    override fun close() { server.stop(0); executor.shutdownNow() }
    companion object {
        fun create(report: Path, bvid: String) = WindowsVideoLocalReplay(report, bvid)
    private const val WIDTH = 320
    private const val HEIGHT = 180
    private const val FPS = 20
    private const val SECONDS = 60

    private fun createVideo(file: File, seconds: Int = SECONDS) {
        val frames = List(FPS * seconds) { index ->
            val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().apply {
                color = Color(24, 27, 38); fillRect(0, 0, WIDTH, HEIGHT)
                color = Color(250, 106, 151); fillRect(0, HEIGHT - 24, WIDTH * index / (FPS * seconds), 24)
                color = Color(82, 191, 248); fillOval(10 + index % 260, 45, 44, 44)
                color = Color.WHITE; font = Font("SansSerif", Font.BOLD, 16)
                drawString("BiliPai · Native Windows", 18, 30)
                font = Font("Monospaced", Font.PLAIN, 15)
                drawString("DASH test: ${index / FPS}.${index % FPS * 5}s", 18, 135)
                dispose()
            }
            ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
        }
        val avih = leInts(1_000_000 / FPS, 0, 0, 0x10, frames.size, 0, 1, 64 * 1024, WIDTH, HEIGHT, 0, 0, 0, 0)
        val strh = ByteArrayOutputStream().apply {
            write("vidsMJPG".toByteArray(Charsets.US_ASCII))
            write(leInts(0, 0, 0, 1, FPS, 0, frames.size, 64 * 1024, -1, 0))
            write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort(0).putShort(0)
                .putShort(WIDTH.toShort()).putShort(HEIGHT.toShort()).array())
        }.toByteArray()
        val strf = ByteArrayOutputStream().apply {
            write(leInts(40, WIDTH, HEIGHT))
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(1).putShort(24).array())
            write("MJPG".toByteArray(Charsets.US_ASCII))
            write(leInts(WIDTH * HEIGHT * 3, 0, 0, 0, 0))
        }.toByteArray()
        val hdrl = listChunk("hdrl", chunk("avih", avih) + listChunk("strl", chunk("strh", strh) + chunk("strf", strf)))
        val movie = ByteArrayOutputStream()
        val index = ByteArrayOutputStream()
        frames.forEach { frame ->
            index.write("00dc".toByteArray(Charsets.US_ASCII))
            index.write(leInts(0x10, movie.size() + 4, frame.size))
            movie.write(chunk("00dc", frame))
        }
        val body = "AVI ".toByteArray(Charsets.US_ASCII) + hdrl + listChunk("movi", movie.toByteArray()) + chunk("idx1", index.toByteArray())
        file.writeBytes("RIFF".toByteArray(Charsets.US_ASCII) + leInts(body.size) + body)
    }

    private fun createAudio(file: File, seconds: Int = SECONDS) {
        val sampleRate = 48_000
        val samples = ByteBuffer.allocate(sampleRate * seconds * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(sampleRate * seconds) { index ->
            val amplitude = (sin(index * 2.0 * Math.PI * 440.0 / sampleRate) * 1_500).toInt().toShort()
            samples.putShort(amplitude)
        }
        val format = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16).array()
        val body = "WAVE".toByteArray(Charsets.US_ASCII) + chunk("fmt ", format) + chunk("data", samples.array())
        file.writeBytes("RIFF".toByteArray(Charsets.US_ASCII) + leInts(body.size) + body)
    }

    private fun chunk(tag: String, body: ByteArray): ByteArray =
        tag.toByteArray(Charsets.US_ASCII) + leInts(body.size) + body + if (body.size % 2 == 0) byteArrayOf() else byteArrayOf(0)
    private fun listChunk(type: String, body: ByteArray): ByteArray = chunk("LIST", type.toByteArray(Charsets.US_ASCII) + body)
    private fun leInts(vararg values: Int): ByteArray = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        .apply { values.forEach(::putInt) }.array()

    }
}
