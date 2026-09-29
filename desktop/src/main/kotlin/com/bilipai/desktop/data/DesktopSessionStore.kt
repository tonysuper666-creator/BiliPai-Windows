package com.bilipai.desktop.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Server cookies retain their scope; explicitly authorized account credentials cover Bilibili hosts. */
internal class DesktopSessionStore(private val path: Path = defaultPath()) : CookieJar {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var saved = readSaved()
    private val serverCookies = saved.serverCookies.mapNotNull { it.toCookie() }
        .filter { it.expiresAt > System.currentTimeMillis() }
        .associateBy { it.identity() }.toMutableMap()
    private val mutableAccount = MutableStateFlow(saved.account)
    val account: StateFlow<AccountSummary?> = mutableAccount.asStateFlow()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (url.scheme != "https" || !isBilibiliHost(url.host)) return
        synchronized(lock) {
            val now = System.currentTimeMillis()
            var accountCookies = saved.cookies
            cookies.filter { belongsToResponseHost(it, url.host) }.forEach { cookie ->
                if (cookie.expiresAt <= now) {
                    serverCookies.remove(cookie.identity())
                    if (cookie.name in ACCOUNT_COOKIE_NAMES && cookie.path == "/") accountCookies = accountCookies - cookie.name
                } else {
                    // Set-Cookie may intentionally target a future path, so do not call matches(url) here.
                    serverCookies[cookie.identity()] = cookie
                }
            }
            removeExpired(now)
            val next = saved.copy(cookies = accountCookies, serverCookies = serverCookies.values.map { ServerCookie.fromCookie(it) })
            persist(next)
            saved = next
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (url.scheme != "https" || !isBilibiliHost(url.host)) return emptyList()
        return synchronized(lock) {
            removeExpired(System.currentTimeMillis())
            val cookies = serverCookies.values.filter { it.matches(url) }.toMutableList()
            // SPI's b_3/b_4 are explicitly returned for the Bilibili visitor session. They fill missing
            // identifiers only; an actual server cookie matching this URL retains its own scope/value.
            (saved.spiCookies + saved.cookies.filterKeys { it in VISITOR_COOKIE_NAMES }).forEach { (name, value) ->
                if (cookies.none { it.name == name }) globalCookie(name, value)?.let(cookies::add)
            }
            saved.cookies.filterKeys { it in ACCOUNT_COOKIE_NAMES }.forEach { (name, value) ->
                cookies.removeAll { it.name == name }
                globalCookie(name, value)?.let(cookies::add)
            }
            cookies
        }
    }

    /** Cookie header material for explicit account validation, never the automatic request jar. */
    fun currentCookies(): Map<String, String> = synchronized(lock) {
        removeExpired(System.currentTimeMillis())
        saved.spiCookies.toMutableMap().apply {
            serverCookies.values.forEach { put(it.name, it.value) }
            putAll(saved.cookies)
        }
    }

    fun saveAccount(cookies: Map<String, String>, account: AccountSummary, imported: Boolean = false) = synchronized(lock) {
        val authorizedNames = if (imported) PERSISTED_COOKIE_NAMES else ACCOUNT_COOKIE_NAMES
        val newSaved = saved.copy(
            cookies = saved.cookies.filterKeys { it in VISITOR_COOKIE_NAMES } + cookies.filterKeys { it in authorizedNames },
            account = account,
            serverCookies = serverCookies.values.map { ServerCookie.fromCookie(it) },
        )
        persist(newSaved)
        saved = newSaved
        mutableAccount.value = account
    }

    fun saveSpiCookies(cookies: Map<String, String>) = synchronized(lock) {
        val visitors = cookies.filterKeys { it in SPI_COOKIE_NAMES }.filterValues { it.isNotBlank() }
        val next = saved.copy(spiCookies = saved.spiCookies + visitors,
            serverCookies = serverCookies.values.map { ServerCookie.fromCookie(it) })
        persist(next)
        saved = next
    }

    fun logout() = synchronized(lock) {
        val cleared = SavedSession()
        persist(cleared)
        saved = cleared
        serverCookies.clear()
        mutableAccount.value = null
    }

    private fun removeExpired(now: Long) {
        serverCookies.entries.removeIf { it.value.expiresAt <= now }
    }

    private fun globalCookie(name: String, value: String): Cookie? = runCatching {
        Cookie.Builder().name(name).value(value).domain("bilibili.com").path("/").secure().build()
    }.getOrNull()

    private fun readSaved(): SavedSession = runCatching {
        if (!Files.isRegularFile(path)) return@runCatching SavedSession()
        val restored = json.decodeFromString<SavedSession>(Files.readString(path))
        if (restored.schemaVersion < 2) {
            // Old flattened visitor entries lack provenance and cannot safely be widened on restore.
            restored.copy(schemaVersion = 2, cookies = restored.cookies.filterKeys { it in ACCOUNT_COOKIE_NAMES },
                spiCookies = emptyMap(), serverCookies = emptyList())
        } else restored.copy(cookies = restored.cookies.filterKeys { it in PERSISTED_COOKIE_NAMES },
            spiCookies = restored.spiCookies.filterKeys { it in SPI_COOKIE_NAMES })
    }.getOrElse { SavedSession() }

    private fun persist(session: SavedSession) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, "session-", ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(SavedSession.serializer(), session.copy(schemaVersion = 2)))
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temporary) }
    }

    private data class CookieIdentity(val name: String, val domain: String, val path: String)
    private fun Cookie.identity() = CookieIdentity(name, domain, path)

    @Serializable
    private data class SavedSession(
        val cookies: Map<String, String> = emptyMap(),
        val account: AccountSummary? = null,
        val spiCookies: Map<String, String> = emptyMap(),
        val serverCookies: List<ServerCookie> = emptyList(),
        val schemaVersion: Int = 0,
    )

    @Serializable
    private data class ServerCookie(
        val name: String,
        val value: String,
        val domain: String,
        val path: String,
        val expiresAt: Long,
        val secure: Boolean,
        val httpOnly: Boolean,
        val hostOnly: Boolean,
        val persistent: Boolean,
    ) {
        fun toCookie(): Cookie? = runCatching {
            require(isBilibiliHost(domain))
            val builder = Cookie.Builder().name(name).value(value).path(path)
            if (hostOnly) builder.hostOnlyDomain(domain) else builder.domain(domain)
            if (persistent) builder.expiresAt(expiresAt)
            if (secure) builder.secure()
            if (httpOnly) builder.httpOnly()
            builder.build()
        }.getOrNull()

        companion object {
            fun fromCookie(cookie: Cookie) = ServerCookie(cookie.name, cookie.value, cookie.domain, cookie.path,
                cookie.expiresAt, cookie.secure, cookie.httpOnly, cookie.hostOnly, cookie.persistent)
        }
    }

    companion object {
        private val ACCOUNT_COOKIE_NAMES = setOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5")
        private val VISITOR_COOKIE_NAMES = setOf("buvid3", "buvid4", "buvid_fp", "b_nut", "b_lsid", "sid")
        private val SPI_COOKIE_NAMES = setOf("buvid3", "buvid4")
        internal val PERSISTED_COOKIE_NAMES = ACCOUNT_COOKIE_NAMES + VISITOR_COOKIE_NAMES
        internal fun isBilibiliHost(host: String): Boolean = host == "bilibili.com" || host.endsWith(".bilibili.com")
        private fun belongsToResponseHost(cookie: Cookie, host: String): Boolean = isBilibiliHost(cookie.domain) &&
            (cookie.domain == host || !cookie.hostOnly && host.endsWith(".${cookie.domain}"))
        private fun defaultPath(): Path {
            val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                ?: Path.of(System.getProperty("user.home"), ".local", "share").toString()
            return Path.of(base, "BiliPai", "session.json")
        }
    }
}
