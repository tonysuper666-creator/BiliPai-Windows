package androidx.activity.compose

import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.DesktopUnavailableActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/** Keeps registration local to the window; desktop has no Android activity-result provider. */
@Suppress("UNUSED_PARAMETER") // No result source: launch failures belong to the caller, never onResult.
@Composable
fun <I, O> rememberLauncherForActivityResult(
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit,
): ActivityResultLauncher<I> {
    val context = LocalContext.current
    val currentContract = rememberUpdatedState(contract)
    val launcher = remember(context) {
        DesktopUnavailableActivityResultLauncher(context, object : ActivityResultContract<I, O>() {
            override fun createIntent(context: Context, input: I): Intent =
                currentContract.value.createIntent(context, input)

            override fun parseResult(resultCode: Int, intent: Intent?): O =
                currentContract.value.parseResult(resultCode, intent)
        })
    }
    DisposableEffect(launcher) {
        onDispose { launcher.unregister() }
    }
    return launcher
}
