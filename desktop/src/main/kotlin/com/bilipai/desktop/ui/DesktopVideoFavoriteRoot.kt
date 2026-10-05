package com.bilipai.desktop.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.android.purebilibili.data.repository.DesktopOriginalFavoriteFolderProtocol
import com.android.purebilibili.feature.list.DesktopFavoriteEnvironment
import com.android.purebilibili.feature.video.screen.*
import com.android.purebilibili.feature.video.viewmodel.DesktopOriginalFavoriteFolderSession
import com.bilipai.desktop.appearance.DesktopWindowConfiguration
import com.bilipai.desktop.data.DesktopCommunityRepository
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/** Original membership/session/entry algorithms consume the same Root video projection. */
@Composable internal fun DesktopVideoFavoriteRoot(
    aid: Long, repository: DesktopRepository, community: DesktopCommunityRepository,
    globalStore: DesktopPluginStore, favorited: Boolean?, currentFavoriteCount: Int,
    stillOwned: () -> Boolean, onFavoriteLoaded: (Boolean) -> Unit,
    onFavoriteSaved: (Boolean, Int) -> Unit, onLogin: () -> Unit, feedback: (String) -> Unit,
    sourceOwner: DesktopOriginalVideoAcceptedPublication?,
) {
    val brandEvents = LocalDesktopBrandSuccessEvents.current
    val epoch by repository.sessionEpochFlow.collectAsState()
    val viewport = DesktopWindowConfiguration.current
    val preferences = remember(globalStore) { DesktopFavoriteInteractionPreferences(globalStore) }
    val quick by preferences.getQuickSaveDefaultFolder().collectAsState(DEFAULT_FAVORITE_QUICK_SAVE_DEFAULT_FOLDER)
    // Ownership observer is scoped below by complete accepted identity.
    val latestCount by rememberUpdatedState(currentFavoriteCount)
    val latestLoaded by rememberUpdatedState(onFavoriteLoaded)
    val latestSaved by rememberUpdatedState(onFavoriteSaved)
    val latestFeedback by rememberUpdatedState(feedback)
    key(aid, epoch, sourceOwner) {
        val latestOwned by rememberUpdatedState(stillOwned)
        val capturedEpoch = epoch
        val alive = remember { AtomicBoolean(true) }
        val parent = rememberCoroutineScope()
        val scope = remember { CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job])) }
        val guard = repository.dynamicCacheSessionGuard
        val owner = remember { checkNotNull(guard.dynamicCacheOwner()) }
        fun owned() = alive.get() && scope.isActive && latestOwned() && repository.sessionEpoch == capturedEpoch
        fun commit(action: () -> Unit) {
            if (!owned() || !guard.withCurrentDynamicCacheOwner(owner) {
                    if (!owned()) throw CancellationException("Video favorite owner retired")
                    action()
                }) throw CancellationException("Video favorite owner retired")
        }
        fun notify(message: String) { commit { latestFeedback(message) } }
        val environment = remember {
            DesktopFavoriteEnvironment.forFolderDrawer(scope, community.favoriteApi, ::owned,
                repository::requireCsrf, { repository.account.value?.mid }, ::notify).also { env ->
                    env.mountBrandFeedback(brandEvents) { action ->
                        try { commit(action); true } catch (_: CancellationException) { false }
                    }
                }
        }
        val protocol = remember {
            DesktopOriginalFavoriteFolderProtocol(environment.api, environment::currentMid,
                environment::csrf, environment::assertOwned)
        }
        val folderEnvironment = remember {
            DesktopFavoriteFolderEnvironment(scope, ::owned, { aid }, { latestCount },
                protocol::getFavoriteFolders, protocol::updateFavoriteFolders,
                environment.actions::createFavFolder,
                { value -> commit { latestLoaded(value) } },
                { value, count -> commit { latestSaved(value, count) } }, ::notify).also {
                    it.mountBrandFeedback(brandEvents, environment::captureBrandFeedback)
                }
        }
        val session = remember { DesktopOriginalFavoriteFolderSession(folderEnvironment) }
        val folderSaving by session.isSavingFavoriteFolders.collectAsState()
        var quickSaving by remember { mutableStateOf(false) }
        fun activate(longPress: Boolean) {
            if (!owned() || quickSaving || folderSaving) return
            if (repository.account.value == null) { onLogin(); return }
            when (resolveVideoFavoriteAction(VideoFavoriteEntryPoint.DetailActionRow, longPress, quick)) {
                VideoFavoriteAction.OpenFavoriteFolders -> session.showFavoriteFolderDialog(aid)
                VideoFavoriteAction.ToggleFavorite -> {
                    quickSaving = true
                    scope.launch {
                    try {
                        environment.assertOwned()
                        val result = environment.actions.favoriteVideo(aid, favorited != true)
                        currentCoroutineContext().ensureActive()
                        environment.assertOwned()
                        result.onSuccess { value ->
                            commit { latestSaved(value, (latestCount + if (value) 1 else -1).coerceAtLeast(0)) }
                            session.invalidateFavoriteFolderCache()
                            notify(if (value) "已收藏" else "已取消收藏")
                        }.onFailure { failure -> notify(failure.message ?: "收藏操作失败") }
                    } finally { if (owned()) quickSaving = false }
                    }
                }
            }
        }
        DisposableEffect(session) {
            onDispose { alive.set(false); session.close(); scope.cancel() }
        }
        Surface(shape = MaterialTheme.shapes.small,
            modifier = Modifier.heightIn(min = 48.dp).combinedClickable(
                enabled = !quickSaving && !folderSaving, role = Role.Button, onClick = { activate(false) },
                onLongClick = { activate(true) })) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                Text(if (quickSaving) "保存收藏…" else if (favorited == true) "已收藏 · 管理" else "云端收藏",
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        CompositionLocalProvider(LocalDesktopFavoriteFolderViewport provides
            DesktopFavoriteFolderViewport(viewport.screenHeightDp)) {
            DesktopOriginalFavoriteFolderDrawer(session)
        }
    }
}
