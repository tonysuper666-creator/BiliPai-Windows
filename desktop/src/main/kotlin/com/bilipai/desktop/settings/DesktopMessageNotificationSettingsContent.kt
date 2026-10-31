package com.bilipai.desktop.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.feature.message.notification.*
import com.bilipai.desktop.ui.LocalDesktopMessageNotificationContext
import kotlinx.coroutines.*

/** Small Windows settings adapter over upstream's MessageNotificationSettingsStore; all defaults, stored keys and
 * migration remain original, and the window's Root consumer runs upstream's Sync after every change.
 * Android's "resident" service keeps polling after the app is closed; Windows cannot, so the page separates what
 * happens while the client is open from after it exits, and offers no switch for the latter. */
@Composable
internal fun DesktopMessageNotificationSettingsContent(onFailure: (Throwable) -> Unit) {
    val context = LocalDesktopMessageNotificationContext.current
    if (context == null) {
        AppText("消息通知需要当前 Windows 窗口的账号环境。", Modifier.padding(20.dp))
        return
    }
    val flow = remember(context) { MessageNotificationSettingsStore.getSettings(context) }
    val settings by flow.collectAsState(initial = MessageNotificationSettings())
    val scope = rememberCoroutineScope()
    val latestFailure by rememberUpdatedState(onFailure)
    fun write(change: suspend () -> Unit) {
        scope.launch {
            try { context.check(); change() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (context.isCurrent()) latestFailure(failure) }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppPreferenceSectionTitle(DESKTOP_MESSAGE_NOTIFICATION_WHILE_OPEN_TITLE)
        AppSwitchPreference(title = "消息通知", subtitle = "BiliPai 开着时检查新消息，最小化也会检查；首次检查只记录基线。",
            checked = settings.enabled, onCheckedChange = { write { MessageNotificationSettingsStore.setEnabled(context, it) } })
        AppPreference(title = "检查频率", value = if (settings.mode == MessageNotificationMode.POWER_SAVING) "节能" else "更及时",
            subtitle = "节能每 30 分钟，更及时每 15 分钟。", onClick = {
                write { MessageNotificationSettingsStore.setMode(context,
                    if (settings.mode == MessageNotificationMode.POWER_SAVING) MessageNotificationMode.MORE_TIMELY else MessageNotificationMode.POWER_SAVING) }
            })
        AppSwitchPreference(title = "窗口显示时更及时检查", subtitle = "显示窗口时每 1–2 分钟；最小化后每 15 分钟。",
            checked = settings.residentEnabled, onCheckedChange = { write { MessageNotificationSettingsStore.setResidentEnabled(context, it) } },
            enabled = settings.enabled)
        AppSwitchPreference(title = "私信", checked = settings.notifyPrivateMessages, enabled = settings.enabled,
            onCheckedChange = { write { MessageNotificationSettingsStore.setPrivateMessagesEnabled(context, it) } })
        AppSwitchPreference(title = "回复我的", checked = settings.notifyReplies, enabled = settings.enabled,
            onCheckedChange = { write { MessageNotificationSettingsStore.setRepliesEnabled(context, it) } })
        AppSwitchPreference(title = "@我", checked = settings.notifyAtMe, enabled = settings.enabled,
            onCheckedChange = { write { MessageNotificationSettingsStore.setAtMeEnabled(context, it) } })
        AppSwitchPreference(title = "收到的赞", checked = settings.notifyLikes, enabled = settings.enabled,
            onCheckedChange = { write { MessageNotificationSettingsStore.setLikesEnabled(context, it) } })
        AppSwitchPreference(title = "系统通知", checked = settings.notifySystemNotices, enabled = settings.enabled,
            onCheckedChange = { write { MessageNotificationSettingsStore.setSystemNoticesEnabled(context, it) } })
        AppSwitchPreference(title = "关注动态", subtitle = "需要 TV 授权登录；只有网页登录时无法预加载关注动态，不会发送此类通知。",
            checked = settings.notifyDynamicUpdates, enabled = settings.enabled,
            onCheckedChange = { write { MessageNotificationSettingsStore.setDynamicUpdatesEnabled(context, it) } })
        AppSwitchPreference(title = "开播提醒", checked = settings.notifyLiveAlerts, enabled = settings.enabled,
            onCheckedChange = { write { MessageNotificationSettingsStore.setLiveAlertsEnabled(context, it) } })
        AppText("提醒显示在 Windows 通知中心，系统可能合并或隐藏通知。通知菜单保留对应页面入口。" +
            "已弹出的托盘气泡无法撤回；内容在服务器端标为已读后，只会从通知菜单中移除对应条目。")
        AppPreferenceSectionTitle(DESKTOP_MESSAGE_NOTIFICATION_AFTER_EXIT_TITLE)
        AppText(DESKTOP_MESSAGE_NOTIFICATION_AFTER_EXIT_TEXT)
    }
}

internal const val DESKTOP_MESSAGE_NOTIFICATION_WHILE_OPEN_TITLE = "客户端开着时提醒"
internal const val DESKTOP_MESSAGE_NOTIFICATION_AFTER_EXIT_TITLE = "退出后也提醒"
/** Windows has no counterpart of Android's resident service: said plainly, with no switch that would do nothing. */
internal const val DESKTOP_MESSAGE_NOTIFICATION_AFTER_EXIT_TEXT =
    "Windows 版做不到。关闭 BiliPai 后不再检查新消息，开机后也不会自动开始检查。需要提醒时，请让 BiliPai 开着（可以最小化）。"
