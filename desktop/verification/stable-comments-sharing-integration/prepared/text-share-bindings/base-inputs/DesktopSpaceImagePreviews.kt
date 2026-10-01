package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalUriHandler
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.space.DesktopOriginalSpaceAvatarPreview
import com.android.purebilibili.feature.space.DesktopOriginalSpaceTopPhotoPreview
import com.android.purebilibili.feature.dynamic.components.prepareImagePreviewSourceTransition
import com.bilipai.desktop.appearance.DesktopTextClipboard
import com.bilipai.desktop.appearance.LocalDesktopTextClipboard
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.settings.LocalDesktopDynamicTimelinePreferences
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/** A source caller/owner for the existing Root overlay, platform and Assets.
 * It creates no image renderer, account, item cache, save HTTP or preference store.
 */
@Composable
internal fun DesktopSpaceImagePreviews(
    repository: DesktopRepository,
    spaceMid: Long,
    avatarPreviewUrl: String,
    topImages: List<SpaceTopImageItem>,
    fallbackTopPhotoUrl: String,
    onFeedback: (String) -> Unit,
    content: @Composable (onAvatarClick: (Rect?) -> Unit, onTopPhotoClick: (Rect?, String?) -> Unit) -> Unit,
) {
    val preferences = checkNotNull(LocalDesktopDynamicTimelinePreferences.current)
    val session = checkNotNull(LocalDesktopDynamicCardSession.current)
    val locations = checkNotNull(LocalDesktopImageSaveLocations.current)
    val parent = LocalDesktopDynamicSaveParent.current
    val epoch by repository.sessionEpochFlow.collectAsState()
    val capturedEpoch = epoch
    val alive = remember(repository, capturedEpoch, spaceMid, session, locations, parent) { AtomicBoolean(true) }
    val guard = repository.dynamicCacheSessionGuard
    val owner = remember(alive) { guard.dynamicCacheOwner() }
    // Store identity can change before the corresponding Compose flow frame.
    // A captured foreign owner is omitted rather than made current by a check.
    if (owner == null || owner.epoch != capturedEpoch || !session.matches(repository, capturedEpoch)) {
        content({ }, { _, _ -> }); return
    }
    fun owned() = alive.get() && locations.isActive() && repository.sessionEpoch == capturedEpoch &&
        session.matches(repository, capturedEpoch)
    val operations = remember(alive) { DesktopDynamicCardOperations(repository, capturedEpoch, ::owned, session.emotes) }
    val assets = remember(alive, operations) { DesktopDynamicImageAssets(repository.httpClient, ::owned,
        guard, owner, selectTarget = { name, mime -> selectDynamicSaveTarget(name, mime, parent) },
        selectDirectory = { selectDynamicSaveDirectory(parent) }, imageSaveLocations = locations) }
    val clipboard = LocalDesktopTextClipboard.current
    val uriHandler = LocalUriHandler.current
    val latestFeedback by rememberUpdatedState(onFeedback)
    val platform = remember(alive, clipboard, uriHandler) {
        DesktopSpaceAvatarPlatform(preferences.context, session.emotes, operations, assets, ::owned,
            clipboard, feedback = { latestFeedback(it) }, openExternalLink = uriHandler::openUri)
    }
    var showAvatarPreview by remember(alive) { mutableStateOf(false) }
    var avatarSourceRect by remember(alive) { mutableStateOf<Rect?>(null) }
    // URL and geometry belong to the admitted click. A profile refresh cannot
    // attach that old geometry/request to a different face while it is open.
    var openedAvatarUrl by remember(alive) { mutableStateOf("") }
    var showTopPhotoPreview by remember(alive) { mutableStateOf(false) }
    var topPhotoSourceRect by remember(alive) { mutableStateOf<Rect?>(null) }
    var topPhotoBannerUrl by remember(alive) { mutableStateOf<String?>(null) }
    var openedTopImages by remember(alive) { mutableStateOf<List<SpaceTopImageItem>>(emptyList()) }
    var openedFallbackTopPhotoUrl by remember(alive) { mutableStateOf("") }
    DisposableEffect(alive, assets) {
        onDispose { alive.set(false); assets.close() }
    }
    content({ rect ->
        if (owned() && avatarPreviewUrl.isNotBlank()) {
            prepareImagePreviewSourceTransition(rect, avatarPreviewUrl)
            openedAvatarUrl = avatarPreviewUrl
            avatarSourceRect = rect; showAvatarPreview = true
        }
    }, { rect, currentBannerUrl ->
        if (owned()) {
            prepareImagePreviewSourceTransition(rect, currentBannerUrl)
            // Original topImages models and fallback are copied at the admitted
            // click; metadata refresh cannot graft a new list onto this rect.
            openedTopImages = topImages.toList()
            openedFallbackTopPhotoUrl = fallbackTopPhotoUrl
            topPhotoSourceRect = rect; topPhotoBannerUrl = currentBannerUrl
            showTopPhotoPreview = true
        }
    })
    // The original preview token does not contain platform/epoch. Key the
    // caller so an identical URL under a new owner disposes the old request.
    if (owned()) key(alive) {
        CompositionLocalProvider(LocalDesktopDynamicCardBindings provides platform) {
            DesktopOriginalSpaceTopPhotoPreview(showTopPhotoPreview, topPhotoBannerUrl,
                openedTopImages, openedFallbackTopPhotoUrl, topPhotoSourceRect, sourceKey = topPhotoBannerUrl) { showTopPhotoPreview = false }
            DesktopOriginalSpaceAvatarPreview(showAvatarPreview, openedAvatarUrl, avatarSourceRect, sourceKey = openedAvatarUrl) {
                showAvatarPreview = false
            }
        }
    }
}

/** The already existing platform schema. All service/save calls are forwarded
 * to the existing Operations/Assets; the avatar only uses the image actions.
 */
internal class DesktopSpaceAvatarPlatform(
    override val context: DesktopPluginContext,
    override val emotes: DesktopDynamicEmotes,
    private val operations: DesktopDynamicCardOperations,
    private val assets: DesktopDynamicImageAssets,
    private val stillOwned: () -> Boolean,
    private val clipboard: DesktopTextClipboard,
    private val feedback: (String) -> Unit,
    private val openExternalLink: (String) -> Unit,
) : DesktopDynamicCardPlatform {
    override fun isOwned() = stillOwned() && operations.isOwned()
    override fun showFeedback(message: String) { if (isOwned()) feedback(message) }
    override fun copyText(text: String) { if (isOwned() && !clipboard.copyText(text)) showFeedback("复制失败，请重试") }
    override fun shareText(text: String) { showFeedback("Windows 系统文字分享尚未接入，可使用复制") }
    override fun openLink(url: String) {
        if (!isOwned()) return
        val uri = runCatching { URI(url) }.getOrNull() ?: return
        if (uri.scheme in setOf("http", "https")) runCatching { openExternalLink(uri.toString()) }
            .onFailure { showFeedback("无法打开链接") }
    }
    override suspend fun searchUp(name: String) = operations.searchUp(name)
    override suspend fun getVoteInfo(voteId: Long) = operations.getVoteInfo(voteId)
    override suspend fun submitVote(voteId: Long, optionIndexes: List<Int>, dynamicId: String) = operations.submitVote(voteId, optionIndexes, dynamicId)
    override suspend fun saveImage(url: String) = assets.saveImage(url)
    override suspend fun saveImages(urls: List<String>) = assets.saveImages(urls)
    override suspend fun saveMotionPhoto(imageUrl: String, videoUrl: String) = assets.saveMotionPhoto(imageUrl, videoUrl)
    override suspend fun saveLivePhotoVideo(videoUrl: String) = assets.saveLivePhotoVideo(videoUrl)
    override suspend fun shareImage(url: String): Boolean = error("Windows 系统图片分享面板尚未接入")
    override suspend fun getShareTargets(size: Int) = operations.getShareTargets(size)
    override suspend fun getMessageSessions(size: Int) = operations.getMessageSessions(size)
    override suspend fun fetchMessageUserInfo(mid: Long) = operations.fetchMessageUserInfo(mid)
    override suspend fun sendDynamicShare(receiverId: Long, content: String) = operations.sendDynamicShare(receiverId, content)
}
