package com.bilipai.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.android.purebilibili.feature.list.HistoryViewModel
import kotlinx.coroutines.CancellationException

/** One visit to the History tab. Root renews it whenever another pager tab was selected in
 * between; opening playback and returning keeps the selected tab and therefore this identity.
 * This is the Windows counterpart of AppNavigation's `historyBrowsingSessionKey`. */
internal class DesktopHistoryBrowsingVisit

/** Only a pager-hosted History page carries the tab-visit identity. A standalone History
 * destination has none, so the original ViewModel scopes its session to its own lifetime. */
internal fun desktopHistoryBrowsingSessionKey(visit: DesktopHistoryBrowsingVisit?, pagerHosted: Boolean): Any? =
    visit.takeIf { pagerHosted }

/** The browsing-session key of the History page being rendered; null outside a pager-hosted page. */
internal val LocalDesktopHistoryBrowsingSessionKey = staticCompositionLocalOf<Any?> { null }

/** The original CommonListScreen binds History page activity to [HistoryViewModel.setPageActive];
 * the Windows personal-list host owns that binding. Activation refreshes watch progress in place
 * from the local progress cache, so returning from playback never reorders the retained list.
 * A retired entry can only deactivate, and its progress cache refusing a read is not an error. */
internal fun HistoryViewModel.bindDesktopPageActivity(entryOwned: Boolean, active: Boolean) {
    try {
        setPageActive(active && entryOwned)
    } catch (_: CancellationException) {
        // The retired entry's own scope already drops the work; nothing is left to publish.
    }
}

/** One server read per browsing session, not per activation. The original ViewModel ignores a
 * repeated session key, so activation, playback and return keep the loaded order and cursor. */
internal fun HistoryViewModel.enterDesktopBrowsingSession(entryOwned: Boolean, active: Boolean, sessionKey: Any?) {
    if (active && entryOwned) enterBrowsingSession(sessionKey)
}
