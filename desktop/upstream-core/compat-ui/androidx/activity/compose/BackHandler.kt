package androidx.activity.compose

import androidx.activity.BackEventCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * androidx.activity's BackHandler on the desktop's back dispatch (navigationevent: Escape, the mouse back button and
 * the window's own Back routes), registered in the composition's current dispatcher like any desktop back owner.
 */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val state = rememberNavigationEventState(NavigationEventInfo.None)
    val currentOnBack by rememberUpdatedState(onBack)
    NavigationBackHandler(state = state, isBackEnabled = enabled, onBackCompleted = { currentOnBack() })
}

/**
 * Desktop back events are discrete, never a gesture with progress. Android hands a non-gesture back (a key or button)
 * to PredictiveBackHandler as a progress flow that completes at once; the desktop does the same.
 */
@Composable
fun PredictiveBackHandler(enabled: Boolean = true, onBack: suspend (progress: Flow<BackEventCompat>) -> Unit) {
    val scope = rememberCoroutineScope()
    val currentOnBack by rememberUpdatedState(onBack)
    BackHandler(enabled) { scope.launch { currentOnBack(emptyFlow()) } }
}
