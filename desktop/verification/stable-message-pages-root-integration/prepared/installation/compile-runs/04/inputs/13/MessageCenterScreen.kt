// 消息中心双栏壳：宽窗口用官方 SupportingPaneScaffold 同时承载收件箱与会话，
// 窄窗口退化为单列 InboxScreen，会话点击走全屏路由。
package com.android.purebilibili.feature.message
import androidx.compose.runtime.collectAsState
import com.bilipai.desktop.ui.LocalDesktopMessagePageOwner
import com.bilipai.desktop.ui.rememberDesktopMessageImagePicker
import com.android.purebilibili.core.ui.LocalNavigationBackHandler as BackHandler

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.SupportingPaneScaffold
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.runtime.key
import com.android.purebilibili.core.util.LocalWindowSizeClass
import com.android.purebilibili.core.util.WindowHeightSizeClass

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun MessageCenterScreen(
    onBack: () -> Unit,
    onTopItemClick: (MessageCenterDestination) -> Unit,
    onOpenSessionFullScreen: (talkerId: Long, sessionType: Int, userName: String) -> Unit,
    onNavigateToVideo: (String) -> Unit,
    onOpenBilibiliLink: (String) -> Unit,
) {
    val useTwoPane =
        LocalWindowSizeClass.current.let {
            it.widthSizeClass != com.android.purebilibili.core.util.WindowWidthSizeClass.Compact &&
                it.heightSizeClass != WindowHeightSizeClass.Compact
        }
    if (!useTwoPane) {
        InboxScreen(
            onBack = onBack,
            onTopItemClick = onTopItemClick,
            onSessionClick = onOpenSessionFullScreen,
        )
        return
    }

    var activeTalkerId by rememberSaveable { mutableLongStateOf(0L) }
    var activeSessionType by rememberSaveable { mutableIntStateOf(0) }
    var activeUserName by rememberSaveable { mutableStateOf("") }

    val pageOwner = LocalDesktopMessagePageOwner.current
    androidx.compose.runtime.SideEffect { pageOwner.keepPaneChat(activeTalkerId, activeSessionType) }
    val windowAdaptiveInfo = currentWindowAdaptiveInfoV2()
    val directive = calculatePaneScaffoldDirective(windowAdaptiveInfo)
    val scaffoldValue = ThreePaneScaffoldValue(
        primary = PaneAdaptedValue.Expanded,
        secondary = if (activeTalkerId != 0L) PaneAdaptedValue.Expanded else PaneAdaptedValue.Hidden,
        tertiary = PaneAdaptedValue.Hidden,
    )

    // 会话面板先于整个消息路由退出，避免系统返回直接把消息中心关掉。
    BackHandler(enabled = activeTalkerId != 0L) {
        activeTalkerId = 0L
    }

    SupportingPaneScaffold(
        directive = directive,
        value = scaffoldValue,
        mainPane = {
            InboxScreen(
                onBack = onBack,
                onTopItemClick = onTopItemClick,
                onSessionClick = { talkerId, sessionType, userName ->
                    activeTalkerId = talkerId
                    activeSessionType = sessionType
                    activeUserName = userName
                },
            )
        },
        supportingPane = {
            if (activeTalkerId != 0L) {
                key(activeTalkerId, activeSessionType) {
                    ChatScreen(
                        talkerId = activeTalkerId,
                        sessionType = activeSessionType,
                        userName = activeUserName.ifBlank { "用户$activeTalkerId" },
                        onBack = { activeTalkerId = 0L },
                        onNavigateToVideo = onNavigateToVideo,
                        onOpenBilibiliLink = onOpenBilibiliLink,
                    )
                }
            }
        },
    )
}
