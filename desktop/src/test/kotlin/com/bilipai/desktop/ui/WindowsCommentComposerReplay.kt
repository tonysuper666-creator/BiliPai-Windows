package com.bilipai.desktop.ui

import com.bilipai.desktop.data.AccountSummary
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import kotlinx.serialization.json.*
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

/** Isolated test transport/session precondition for the ORIGINAL Main editor.
 * No VM, Root, player, account protocol, picker or application state is faked.
 * The one synthetic credential is installed through the same real SessionStore
 * after all repository API transports are made memory-only. No publish occurs. */
internal class WindowsCommentComposerReplay(private val report: Path) : AutoCloseable {
    companion object {
        const val MID = 990000024L
        const val FRIEND_MID = 990000025L
        const val FRIEND_NAME = "合成好友"
        const val DRAFT = "私有编辑器草稿"
        const val EMOTE = "[夹具表情]"
        const val MENTION_QUERY = "合成"
        private val READ_JSON = setOf("/x/emote/user/panel/web", "/x/emote/package",
            "/x/polymer/web-dynamic/v1/mention/search")
        fun requireReadOnly(method: String, host: String, path: String) {
            require(method == "GET" || (method == "POST" && host == "app.bilibili.com" &&
                WindowsCommentSearchReplay.isReadRpc(path))) { "Composer fixture forbids every mutation" }
            require(path !in setOf("/x/relation/modify", "/x/web-interface/archive/like", "/x/v2/reply/add",
                "/x/v2/reply/action", "/x/v2/reply/hate", "/x/v2/reply/del", "/x/v2/reply/report",
                "/x/dynamic/feed/create/dyn", "/x/dynamic/feed/create/dyn/submit", "/x/v3/fav/resource/deal"))
        }
    }

    private val alive = AtomicBoolean(true)
    private val reads = CopyOnWriteArrayList<JsonObject>()
    private val ordinary = WindowsCommentSearchReplay()
    private var guestEpoch: Long? = null
    @Volatile private var syntheticEpoch: Long? = null
    @Volatile private var syntheticSessionSeeded = false
    val image: Path = report.resolve("composer-private-image.png")
    val imageUri: String get() = image.toUri().toString()
    private val imageSha: String
    init {
        require(Files.isDirectory(report, NOFOLLOW_LINKS) && !Files.isSymbolicLink(report))
        require(!Files.exists(image, NOFOLLOW_LINKS))
        val pixels = BufferedImage(48, 32, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width)
            pixels.setRGB(x, y, if ((x / 8 + y / 8) % 2 == 0) 0xff40c8d8.toInt() else 0xffffa8e0.toInt())
        check(ImageIO.write(pixels, "png", image.toFile()))
        imageSha = sha(image)
    }
    fun bindGuest(repository: DesktopRepository) {
        check(alive.get() && guestEpoch == null && repository.account.value == null)
        guestEpoch = repository.sessionEpoch
    }
    fun ownsSession(repository: DesktopRepository): Boolean {
        if (!alive.get()) return false
        val expected = syntheticEpoch
        return if (expected == null) repository.account.value == null && repository.sessionEpoch == guestEpoch
        else repository.sessionEpoch == expected && repository.account.value?.mid == MID
    }
    fun seedSyntheticSession(repository: DesktopRepository, privateLocal: Path) {
        check(alive.get() && !syntheticSessionSeeded && ownsSession(repository))
        val local = Path.of(requireNotNull(System.getenv("LOCALAPPDATA"))).toRealPath()
        check(local == privateLocal.toRealPath() && local.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath()) &&
            local.fileName.toString().startsWith("BiliPai-v025-root-routes-"))
        check(Path.of(System.getProperty("user.home")).toRealPath() == local) {
            "Composer chooser requires the runner's private user.home before Main starts"
        }
        val marker = local.resolve(".bilipai-root-validation")
        check(Files.isRegularFile(marker, NOFOLLOW_LINKS) && !Files.isSymbolicLink(marker) &&
            Files.readString(marker) == System.getProperty("bilipai.rootValidationToken"))
        // Predict the one documented guest -> different MID transition before
        // publishing StateFlows. No request can borrow an arbitrary later epoch.
        val expected = Math.addExact(requireNotNull(guestEpoch), 1L)
        syntheticEpoch = expected
        val store = repository.dynamicCacheSessionGuard as? DesktopSessionStore
            ?: error("Actual Main repository did not expose its sole SessionStore")
        store.saveAccount(mapOf("SESSDATA" to "LOCAL-COMPOSER-NOT-A-REAL-CREDENTIAL",
            "bili_jct" to "LOCAL-COMPOSER-NOT-A-REAL-CSRF"), AccountSummary(MID, "私有合成用户", ""))
        check(ownsSession(repository))
        syntheticSessionSeeded = true
    }
    fun authenticated(repository: DesktopRepository): Boolean = syntheticSessionSeeded && ownsSession(repository)
    fun epoch(): Long = requireNotNull(syntheticEpoch)
    fun nav(): String = buildJsonObject {
        put("code", 0); put("data", buildJsonObject {
            put("isLogin", syntheticSessionSeeded); put("mid", if (syntheticSessionSeeded) MID else 0L)
            put("uname", "私有合成用户"); put("face", "")
            put("wbi_img", buildJsonObject {
                put("img_url", "https://fixture.invalid/${"a".repeat(32)}.png")
                put("sub_url", "https://fixture.invalid/${"b".repeat(32)}.png")
            })
        })
    }.toString()
    fun intercept(chain: Interceptor.Chain, stillOwned: () -> Boolean): Response? {
        val request = chain.request(); val url = request.url; val path = url.encodedPath
        requireReadOnly(request.method, url.host, path)
        check(alive.get() && stillOwned())
        // Cookie-only synthetic identity has no app access token. Reuse the
        // existing real gRPC parser fixture, not a second comments controller.
        ordinary.intercept(chain, stillOwned)?.let { return it }
        if (path !in READ_JSON) return null
        require(url.scheme == "https" && url.port == 443 && url.host == "api.bilibili.com" &&
            url.username.isEmpty() && url.password.isEmpty() && url.encodedFragment == null)
        val body = when (path) {
            "/x/polymer/web-dynamic/v1/mention/search" -> {
                val query = url.queryParameter("keyword").orEmpty()
                require(query.isEmpty() || query == MENTION_QUERY)
                reads += buildJsonObject { put("kind", "mention"); put("query", query) }
                buildJsonObject { put("code", 0); put("data", buildJsonObject { put("groups", buildJsonArray {
                    add(buildJsonObject { put("group_name", "私有合成用户"); put("group_type", 0)
                        put("items", buildJsonArray { if (query == MENTION_QUERY) add(buildJsonObject {
                            put("uid", FRIEND_MID); put("name", FRIEND_NAME); put("face", ""); put("fans", 0)
                        }) }) })
                }) }) }.toString()
            }
            else -> {
                require(url.queryParameter("business") in setOf("reply", "dynamic"))
                reads += buildJsonObject { put("kind", "emote"); put("path", path) }
                buildJsonObject { put("code", 0); put("data", buildJsonObject { put("packages", buildJsonArray {
                    for (id in listOf(1L, 2L, 53L, 4L)) add(buildJsonObject {
                        put("id", id); put("text", if (id == 1L) "小黄脸" else "私有表情$id"); put("url", imageUri)
                        put("emote", buildJsonArray { add(buildJsonObject {
                            put("id", id * 100L); put("text", if (id == 1L) EMOTE else "[夹具$id]"); put("url", imageUri)
                        }) })
                    })
                }) }) }.toString()
            }
        }
        check(alive.get() && stillOwned() && !chain.call().isCanceled())
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("LOCAL composer read")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }
    fun receipt(requireComplete: Boolean = true): JsonObject {
        check(alive.get() && sha(image) == imageSha)
        if (requireComplete) {
            check(syntheticSessionSeeded && reads.any { it["kind"]?.jsonPrimitive?.content == "emote" } &&
                reads.any { it["query"]?.jsonPrimitive?.content == MENTION_QUERY })
        }
        return buildJsonObject {
            put("syntheticSessionSeededThroughActualStore", syntheticSessionSeeded)
            put("syntheticPrimaryMid", MID); put("syntheticAccountEpoch", syntheticEpoch?.let(::JsonPrimitive) ?: JsonNull)
            put("loginUiAccepted", false); put("realAccountUsed", false); put("mutationRequestsPermitted", false)
            put("imageFile", image.fileName.toString()); put("imageSha256", imageSha)
            put("imageCreatedByFixture", true); put("emoteImagesArePrivateFiles", true)
            put("reads", JsonArray(reads.toList()))
        }
    }
    private fun sha(path: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    override fun close() { alive.set(false); ordinary.close() }
}
