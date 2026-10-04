package com.bilipai.desktop.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.android.purebilibili.feature.settings.DesktopHomeRecommendationFields
import com.bilipai.desktop.data.DesktopDiscoveryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The same discovery instance read by the real recommendation screen owns both fields. */
@Composable
internal fun DesktopHomeRecommendationSettings(
    discovery: DesktopDiscoveryRepository,
    onFailure: (Throwable) -> Unit,
    modifier: Modifier = Modifier,
) {
    val feedMode by discovery.feedMode.collectAsState()
    val refreshCount by discovery.refreshCount.collectAsState()
    val scope=rememberCoroutineScope()
    val writer=remember(discovery){Mutex()}
    val latestFailure by rememberUpdatedState(onFailure)
    fun update(action:suspend ()->Unit) {
        scope.launch {
            try {writer.withLock{action()}}
            catch(cancelled:CancellationException){throw cancelled}
            catch(failure:Exception){latestFailure(failure)}
        }
    }
    Column(modifier) {
        DesktopHomeRecommendationFields(
            feedApiType=feedMode,
            onFeedApiTypeChange={value->update{discovery.setFeedMode(value)}},
            homeRefreshCount=refreshCount,
            onHomeRefreshCountChange={value->update{discovery.setRefreshCount(value)}},
        )
        LocalDesktopDynamicTimelinePreferences.current?.let {preferences->
            DesktopDynamicTimelineSettings(preferences,onFailure)
        }
    }
}
