package com.bilipai.desktop.settings

import androidx.compose.runtime.staticCompositionLocalOf
import com.android.purebilibili.core.store.AppIconAppearance
import com.android.purebilibili.feature.settings.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Original keys/defaults; the installed global Store and icon projection remain authoritative. */
internal class DesktopOriginalAboutPreferences(
    val context: DesktopPluginContext,
    private val owns: () -> Boolean,
    private val admit: ((() -> Unit) -> Boolean),
) {
    private val settings = context.store.snapshot("settings")
    private fun boolean(name: String, fallback: Boolean) = settings.map {
        it[DesktopPreferenceKey(name) { raw -> (raw as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull }] ?: fallback
    }.distinctUntilChanged()
    val autoCheck = boolean("auto_check_app_update", true)
    val easterEgg = boolean("easter_egg_enabled", true)
    val initialAutoCheck get() = settings.value[DesktopPreferenceKey("auto_check_app_update") { raw -> (raw as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull }] ?: true
    val initialEasterEgg get() = settings.value[DesktopPreferenceKey("easter_egg_enabled") { raw -> (raw as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull }] ?: true
    val initialChannel get() = DesktopOriginalAboutSettings.AppUpdateChannel.fromValue(settings.value[DesktopPreferenceKey("app_update_channel") { raw -> (raw as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull }] ?: 0)
    val channel = settings.map {
        DesktopOriginalAboutSettings.AppUpdateChannel.fromValue(
            it[DesktopPreferenceKey("app_update_channel") { raw -> (raw as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull }] ?: 0)
    }.distinctUntilChanged()
    val icon = DesktopOriginalAppIconPreferences(context, owns, admit)
    suspend fun setAutoCheck(value: Boolean) = write(mapOf("auto_check_app_update" to JsonPrimitive(value)))
    suspend fun setChannel(value: DesktopOriginalAboutSettings.AppUpdateChannel) = write(mapOf("app_update_channel" to JsonPrimitive(value.value)))
    suspend fun setEasterEgg(value: Boolean) = write(mapOf("easter_egg_enabled" to JsonPrimitive(value)), mapOf("enabled" to JsonPrimitive(value)))
    private suspend fun write(changes: Map<String, JsonElement>, mirror: Map<String, JsonElement>? = null) = withContext(Dispatchers.IO) {
        val caller = currentCoroutineContext()
        fun checkRequest() { caller.ensureActive(); if (!owns()) throw CancellationException("About page retired") }
        fun permit(): DesktopPluginStore.OriginalPreferenceWritePermit {
            checkRequest()
            lateinit var result: DesktopPluginStore.OriginalPreferenceWritePermit
            if (!admit { checkRequest(); result = DesktopPluginStore.OriginalPreferenceWritePermit(context.store) })
                throw CancellationException("About preference admission rejected")
            return result
        }
        context.store.updateOriginalNamespacesFromSnapshot("settings", ::checkRequest, ::permit) {
            Unit to buildMap { put("settings", changes); if (mirror != null) put("easter_egg", mirror) }
        }
    }
}

/** Public release HTTP borrows the installed proxy/TLS/pool; account interceptors/cookies
 * are removed. The original checker still chooses releases and parses all original metadata. */
internal class DesktopOriginalAboutReleaseHttp(client: OkHttpClient, private val owns: () -> Boolean) {
    private val client = client.newBuilder().cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .apply { interceptors().clear(); networkInterceptors().clear() }.build()
    suspend fun fetch(url: String, required: Boolean): String? = coroutineScope {
        ensureActive()
        if (!owns()) throw CancellationException("About metadata owner retired")
        val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "BiliPai-UpdateChecker").build()
        val call = client.newCall(request)
        val cancellation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { response ->
                ensureActive()
                if (!owns()) throw CancellationException("About metadata owner retired")
                if (!response.isSuccessful) {
                    if (required) error("更新接口异常: HTTP ${response.code}")
                    return@use null
                }
                val body = response.body
                require(body.contentLength() <= 2 * 1024 * 1024) { "发布信息超过大小限制" }
                val bytes = body.byteStream().use { it.readNBytes(2 * 1024 * 1024 + 1) }
                require(bytes.size <= 2 * 1024 * 1024) { "发布信息超过大小限制" }
                ensureActive()
                bytes.toString(Charsets.UTF_8)
            }
        } catch (failure: Exception) {
            ensureActive()
            throw failure
        } finally { cancellation.cancel() }
    }
}

internal data class DesktopOriginalAboutReleaseState(
    val checking: Boolean = false,
    val status: String = "点击检查",
    val result: AppUpdateCheckResult? = null,
)

/** One retained Root's metadata projection; caller-owned manual and consent-gated automatic
 * queries share it. It offers neither an APK installer nor a second Windows updater. */
internal class DesktopOriginalAboutReleaseMetadata(private val client: OkHttpClient) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(DesktopOriginalAboutReleaseState())
    val state = mutable.asStateFlow()
    suspend fun check(currentVersion: String, currentVersionCode: Int, includePrerelease: Boolean,
        owns: () -> Boolean, admit: ((() -> Unit) -> Boolean), silent: Boolean): Result<AppUpdateCheckResult>? {
        if (!mutex.tryLock()) return null
        val caller = currentCoroutineContext()
        fun checkRequest() { caller.ensureActive(); if (!owns()) throw CancellationException("About metadata request retired") }
        fun publish(action: () -> Unit) {
            checkRequest(); var applied = false
            val accepted = admit { checkRequest(); action(); applied = true }
            if (!accepted || !applied) throw CancellationException("About metadata publication rejected")
        }
        val previous = mutable.value
        try {
            publish { mutable.value = previous.copy(checking = true, status = if (silent) previous.status else "检查中…") }
            val result = AppUpdateChecker.check(currentVersion, currentVersionCode, includePrerelease,
                DesktopOriginalAboutReleaseHttp(client, owns))
            checkRequest()
            publish {
                mutable.value = result.fold(
                    onSuccess = { DesktopOriginalAboutReleaseState(false, it.message, it) },
                    onFailure = { previous.copy(checking = false, status = "检查失败") })
            }
            return result
        } finally {
            // Cancellation never leaves the shared metadata projection permanently busy.
            // Preserve the last accepted evidence; no feedback can reach a retired caller.
            mutable.value = mutable.value.copy(checking = false)
            mutex.unlock()
        }
    }
}

/** AboutSection's Android context leaf is the same page binding, not a synthetic Context. */
internal class DesktopOriginalAboutBindings(
    val preferences: DesktopOriginalAboutPreferences,
    private val scope: CoroutineScope,
    private val owns: () -> Boolean,
    private val onFailure: (Throwable) -> Unit,
) {
    val iconAppearance: Flow<AppIconAppearance> get() = preferences.icon.appearance
    val initialAppearance get() = preferences.icon.initialAppearance
    fun update(action: suspend () -> Unit) {
        if (!owns()) return
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { ensureActive(); if (owns()) onFailure(failure) }
        }
    }
}
internal val LocalDesktopOriginalAboutBindings = staticCompositionLocalOf<DesktopOriginalAboutBindings> { error("Mounted original About bindings are required") }
internal val LocalDesktopOriginalAboutReleaseMetadata = staticCompositionLocalOf<DesktopOriginalAboutReleaseMetadata> { error("Actual retained Root release metadata owner is required") }
