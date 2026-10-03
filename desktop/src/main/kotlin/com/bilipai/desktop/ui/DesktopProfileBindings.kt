package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.android.purebilibili.core.network.*
import com.android.purebilibili.core.store.StoredAccountSession
import com.android.purebilibili.core.ui.wallpaper.ProfileWallpaperTransform
import com.android.purebilibili.data.model.response.NavData
import com.android.purebilibili.data.repository.DesktopOriginalFavoriteRepository
import com.android.purebilibili.data.repository.DesktopOriginalFavoritePgc
import com.android.purebilibili.data.repository.DesktopOriginalProfileSplashProtocol
import com.android.purebilibili.feature.profile.ProfileScreen
import com.android.purebilibili.feature.profile.ProfileViewModel
import com.android.purebilibili.feature.settings.AppThemeMode
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.File
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Root's SAME account store, never an account DTO/cookie copy or a second authority.
 * Mutation methods must use the immutable entry epoch + actual Store admission.
 * Playback MID is a required real original account choice; absence is not guest/default-null.
 * Root's account-change actor must refresh/retire entries and use this MID for playback requests. */
internal interface DesktopProfileAccountPort {
    suspend fun getAccounts(): List<StoredAccountSession>
    suspend fun getActiveAccountMid(): Long?
    suspend fun getPlaybackAccountMid(): Long?
    suspend fun setPlaybackAccountMid(mid: Long?): Boolean
    fun currentMid(): Long?
    fun hasSession(): Boolean
    fun accessTokenCredentials(): Pair<String?, String>
    fun qrAuthorizationSession(): com.android.purebilibili.feature.login.QrAuthorizationSession
    suspend fun saveMid(mid: Long)
    suspend fun saveVipStatus(vip: Boolean)
    suspend fun upsertCurrentAccount(nav: NavData?)
    suspend fun clearCurrentSession()
    suspend fun clearActiveAccount()
    suspend fun activateAccount(mid: Long): Boolean
    fun removeAccount(mid: Long): Boolean
}

/** All methods operate on the Root's actual global settings store and original keys.
 * Theme/splash/history/privacy sync remains the same original Root settings actor.
 * No setting or account state is serialized by this view. */
internal interface DesktopProfilePreferences {
    fun getPrivacyModeEnabled(): Flow<Boolean>
    fun isPrivacyModeEnabledSync(): Boolean
    suspend fun setPrivacyModeEnabled(enabled: Boolean)
    suspend fun setThemeMode(mode: AppThemeMode)
    fun getShowProfileEditButton(): Flow<Boolean>
    fun getProfileBgUri(): Flow<String?>
    suspend fun setProfileBgUri(uri: String?)
    fun getProfileBgAlignment(isTablet: Boolean): Flow<Float>
    fun getProfileBgTransform(isTablet: Boolean): Flow<ProfileWallpaperTransform>
    suspend fun setProfileBgTransform(isTablet: Boolean, transform: ProfileWallpaperTransform)
    suspend fun resetProfileBgTransform()
    fun getSplashAlignment(isTablet: Boolean): Flow<Float>
    suspend fun setSplashAlignment(isTablet: Boolean, bias: Float)
    suspend fun setSplashWallpaperUri(uri: String)
    suspend fun setSplashEnabled(enabled: Boolean)
    suspend fun setSplashRandomEnabled(enabled: Boolean)
    suspend fun setHomeWallpaperUri(uri: String)
    fun getTripleJumpEnabled(): Flow<Boolean>
    suspend fun setTripleJumpEnabled(enabled: Boolean)
}

internal interface DesktopProfileAnalytics {
    fun logScreenView(name: String)
    fun syncUserContext(mid: Long?, isVip: Boolean, privacyModeEnabled: Boolean)
    fun logLogout()
}

/** Actual Root window content size in DP, updated with physical DPI/window size changes. */
internal data class DesktopProfileWindowConfiguration(val screenWidthDp: Int, val screenHeightDp: Int)

/** Physical file import/download/publication goes through existing Root file/gallery actors.
 * openOwnedDownload uses SAME owned Call.Factory; cancellation cancels its Call.
 * Each read/import/write checks suspend caller Job + entry/epoch. writeOwnedFile publishes
 * through bounded temp-file + atomic rename admission; no raw partially written final file.
 * Wallpaper files stay in the original directory/name convention under actual stateDirectory.
 * The caller closes Response with use. Implementations close every file/source/metadata handle.
 * Picker uses the actual Root Window and returns a real file URI, never an Android grant facade. */
internal interface DesktopProfilePlatform {
    val stateDirectory: Path
    val configuration: StateFlow<DesktopProfileWindowConfiguration>
    val supportsRenderEffectBackedHaze: Boolean
    val applicationIconModel: Any
    fun reportWallpaperPreviewFailure(failure: Throwable)
    fun acquireSystemBars(control: Boolean, lightStatusBars: Boolean): AutoCloseable
    fun pickMedia(onSelected: (String?) -> Unit)
    fun pickQrImage(onSelected: (String?) -> Unit)
    suspend fun readQrImage(uri: String): java.awt.image.BufferedImage
    fun feedback(message: String)
    fun copyText(label: String, text: String)
    suspend fun importWallpaperMedia(uri: String, directory: File): File
    suspend fun importWallpaperImage(uri: String, directory: File): File
    suspend fun openOwnedDownload(request: Request): Response
    suspend fun readOwnedBytes(body: ResponseBody): ByteArray
    suspend fun writeOwnedFile(destination: File, body: ResponseBody)
    suspend fun writeOwnedFile(destination: File, bytes: ByteArray)
    suspend fun deleteOwnedFile(file: File)
    suspend fun saveImageToGallery(bytes: ByteArray, fileName: String)
}

/** Same existing Home media lifetime/software lease, never a second playback actor.
 * Wallpaper is muted, repeat ONE, crop+alignment, GIF playback follows lifecycle/playing.
 * skinVideo uses the sole original resolveProfileSkinVideoRepeatMode(playMode), including NONE.
 * Root must extend its existing lease/texture slot for repeat/alignment before binding this port;
 * an image-only fallback, ignored alignment or unconditional loop is not a conforming binding. */
internal interface DesktopProfileMedia {
    suspend fun isVideoUri(uri: String): Boolean
    @Composable fun wallpaper(uri: String, imageModel: Any, alignment: Alignment,
        playing: Boolean, video: Boolean, modifier: Modifier)
    @Composable fun skinVideo(path: String, playMode: String?, playbackEnabled: Boolean, modifier: Modifier)
}

/** Immutable retained original Profile page ownership. Parent supplies all real services,
 * the SAME global preferences/account store and SAME navigation entry child scope/admission.
 * Raw Retrofit API instances must be owner-tagged wrappers from ownedHomeService/raw694.
 * Favorite/Pgc are the already sole original protocols, created with the same owner environment. */
internal class DesktopProfileEnvironment(
    val scope: CoroutineScope,
    val owns: () -> Boolean,
    val commit: ((() -> Unit) -> Boolean),
    val api: BilibiliApi,
    val spaceApi: SpaceApi,
    val dynamicApi: DynamicApi,
    val searchApi: SearchApi,
    val splash: DesktopOriginalProfileSplashProtocol,
    val authorizationApi: PassportApi,
    val favorite: DesktopOriginalFavoriteRepository,
    val bangumi: DesktopOriginalFavoritePgc,
    val csrf: () -> String?,
    val accounts: DesktopProfileAccountPort,
    val preferences: DesktopProfilePreferences,
    val platform: DesktopProfilePlatform,
    val media: DesktopProfileMedia,
    val analytics: DesktopProfileAnalytics,
) {
    fun ensureOwned() { if (scope.coroutineContext[Job]?.isActive != true || !owns()) throw CancellationException("Profile entry retired") }
    fun launchOwned(context: CoroutineContext = EmptyCoroutineContext, block: suspend CoroutineScope.() -> Unit): Job =
        scope.launch(context) { ensureOwned(); block(); currentCoroutineContext().ensureActive(); ensureOwned() }
    fun publishCallback(block: () -> Unit) {
        if (!commit(block)) throw CancellationException("Profile callback owner retired")
    }
    fun feedback(message: String) { commit { platform.feedback(message) } }
    fun copyText(label: String, text: String) { commit { platform.copyText(label, text) } }
    val supportsRenderEffectBackedHaze get() = platform.supportsRenderEffectBackedHaze
    fun acquireSystemBars(control: Boolean, light: Boolean): AutoCloseable {
        ensureOwned(); return platform.acquireSystemBars(control, light)
    }
}

/** Original transient StateFlows only. No copied persistent preferences/account state. */
internal class DesktopOwnedProfileState<T>(initial: T, private val environment: DesktopProfileEnvironment) {
    private val flow = MutableStateFlow(initial)
    var value: T
        get() = flow.value
        set(next) { environment.publishCallback { flow.value = next } }
    fun asStateFlow(): StateFlow<T> = flow.asStateFlow()
}

internal val LocalDesktopProfileEnvironment = staticCompositionLocalOf<DesktopProfileEnvironment> {
    error("The actual retained Profile owner is required")
}

@Composable internal fun desktopProfileWindowConfiguration(): DesktopProfileWindowConfiguration =
    LocalDesktopProfileEnvironment.current.platform.configuration.collectAsState().value

internal class DesktopProfileMediaPicker(private val launchActual: () -> Unit) { fun launch() = launchActual() }
@Composable internal fun rememberDesktopProfileMediaPicker(onSelected: (String?) -> Unit): DesktopProfileMediaPicker {
    val environment = LocalDesktopProfileEnvironment.current
    val callback by rememberUpdatedState(onSelected)
    return remember(environment) { DesktopProfileMediaPicker {
        environment.ensureOwned()
        environment.platform.pickMedia { uri -> environment.commit { callback(uri) } }
    } }
}

/** All original primary-navigation actions are required; callers cannot inherit source no-ops. */
internal class DesktopProfileNavigation(
    val onBack: () -> Unit,
    val onGoToLogin: () -> Unit,
    val onLogoutSuccess: () -> Unit,
    val onAccountSwitchSuccess: () -> Unit,
    val onSettingsClick: () -> Unit,
    val onSearchClick: () -> Unit,
    val onHistoryClick: () -> Unit,
    val onFavoriteClick: () -> Unit,
    val onSubscriptionClick: () -> Unit,
    val onFavoriteFolderClick: (Long, Long, String) -> Unit,
    val onFollowingClick: (Long) -> Unit,
    val onDownloadClick: () -> Unit,
    val onWatchLaterClick: () -> Unit,
    val onInboxClick: () -> Unit,
    val onVideoClick: (String) -> Unit,
    val onBangumiClick: (Long, Long) -> Unit,
    val onBangumiMoreClick: () -> Unit,
)

/** Original album scanner input over this same retained Profile window/file actor.
 * No scanned URL is opened, no account or file state is stored, and disposal cancels its child. */
@Composable internal fun DesktopProfileQrScanner(
    onCode: (String) -> Unit, onError: (String) -> Unit,
    singleShot: Boolean, acceptAnyQr: Boolean, modifier: Modifier = Modifier,
) {
    val environment = LocalDesktopProfileEnvironment.current
    val latestCode by rememberUpdatedState(onCode)
    val latestError by rememberUpdatedState(onError)
    val active = remember(environment) { java.util.concurrent.atomic.AtomicBoolean(true) }
    var child by remember(environment) { mutableStateOf<Job?>(null) }
    var busy by remember(environment) { mutableStateOf(false) }
    var accepted by remember(environment) { mutableStateOf(false) }
    DisposableEffect(environment) {
        onDispose { active.set(false); child?.cancel(); child = null }
    }
    com.android.purebilibili.core.ui.components.AppButton(
        enabled = !busy && !(singleShot && accepted), modifier = modifier,
        onClick = {
            environment.ensureOwned()
            environment.platform.pickQrImage { uri ->
                if (uri != null && active.get() && environment.owns()) {
                    child?.cancel()
                    child = environment.launchOwned(Dispatchers.IO) {
                        val caller = currentCoroutineContext()[Job] ?: error("Actual scanner caller required")
                        try {
                            environment.publishCallback { caller.ensureActive(); if (active.get()) busy = true }
                            val image = environment.platform.readQrImage(uri)
                            val code = try { com.android.purebilibili.feature.login.BiliPaiQrDecoder.decodeBitmap(image, acceptAnyQr) }
                                finally { image.flush() }
                            environment.publishCallback {
                                caller.ensureActive()
                                if (active.get()) {
                                    if (code == null) latestError("未能识别二维码，请选择完整清晰的二维码图片")
                                    else { accepted = singleShot; latestCode(code) }
                                }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            environment.publishCallback { caller.ensureActive(); if (active.get()) latestError("二维码图片无法读取或识别，请重新选择") }
                        } finally {
                            if (environment.owns()) environment.commit { if (active.get()) busy = false }
                        }
                    }
                }
            }
        },
    ) { com.android.purebilibili.core.ui.components.AppText(if (busy) "正在识别…" else "选择二维码图片") }
}

internal class DesktopOriginalProfileBinding(val environment: DesktopProfileEnvironment) {
    val viewModel = ProfileViewModel(environment)
}

@Composable internal fun DesktopOriginalProfileHost(
    binding: DesktopOriginalProfileBinding,
    navigation: DesktopProfileNavigation,
    isCurrentPage: Boolean,
    accountSessionRefreshGeneration: Int,
    showHistoryService: Boolean,
    skinBackgroundImagePath: String?,
    skinSquaredBackgroundImagePath: String?,
    skinVideoBackgroundPath: String?,
    skinVideoPlayMode: String?,
    deferImmersiveRenderBudget: Boolean,
    scrollToTopChannel: Channel<Unit>?,
) {
    CompositionLocalProvider(LocalDesktopProfileEnvironment provides binding.environment) {
        ProfileScreen(binding.viewModel, isCurrentPage, accountSessionRefreshGeneration,
            navigation.onBack, navigation.onGoToLogin, navigation.onLogoutSuccess,
            navigation.onAccountSwitchSuccess, navigation.onSettingsClick, navigation.onSearchClick,
            navigation.onHistoryClick, showHistoryService, navigation.onFavoriteClick,
            navigation.onSubscriptionClick, navigation.onFavoriteFolderClick, navigation.onFollowingClick,
            navigation.onDownloadClick, navigation.onWatchLaterClick, navigation.onInboxClick,
            navigation.onVideoClick, navigation.onBangumiClick, navigation.onBangumiMoreClick,
            skinBackgroundImagePath, skinSquaredBackgroundImagePath, skinVideoBackgroundPath,
            skinVideoPlayMode, deferImmersiveRenderBudget, scrollToTopChannel)
    }
}
