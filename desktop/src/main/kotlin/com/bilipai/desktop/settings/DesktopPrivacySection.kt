package com.bilipai.desktop.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.feature.settings.PrivacySection
import kotlinx.coroutines.launch

val LocalDesktopPrivacySectionBindings = staticCompositionLocalOf<DesktopPrivacySectionBindings> {
    error("PrivacySection requires the Root shared settings and authoritative search preferences")
}

@Composable
fun DesktopPrivacySection(
    bindings: DesktopPrivacySectionBindings,
    onPermissionClick: () -> Unit,
    onMessageNotificationClick: () -> Unit,
    onBlockedListClick: () -> Unit,
    onCommentFraudHistoryClick: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val privacy by bindings.searchPreferences.privacyMode.collectAsState()
    val suggestions by bindings.searchPreferences.suggestionsEnabled.collectAsState()
    // Read the original persisted value, while clearly disabling the unported auth action.
    val authentication by bindings.authenticationConfigured.collectAsState(initial = false)
    val error by bindings.error.collectAsState()
    CompositionLocalProvider(LocalDesktopPrivacySectionBindings provides bindings) {
        Column {
            PrivacySection(privacy, suggestions, authentication,
                onPrivacyModeChange = { scope.launch { bindings.setPrivacyMode(it) } },
                onSearchSuggestionsChange = { scope.launch { bindings.setSuggestionsEnabled(it) } },
                onPrivacyContentAuthenticationChange = { kotlin.error("Windows identity verification unavailable") },
                onPermissionClick = onPermissionClick,
                onMessageNotificationClick = onMessageNotificationClick,
                onBlockedListClick = onBlockedListClick,
                onCommentFraudHistoryClick = onCommentFraudHistoryClick)
            error?.let { AppText(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
