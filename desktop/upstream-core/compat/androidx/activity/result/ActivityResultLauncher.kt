package androidx.activity.result

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import com.bilipai.desktop.compat.UnsupportedDesktopCapability
import java.util.concurrent.atomic.AtomicBoolean

abstract class ActivityResultLauncher<I> {
    abstract fun launch(input: I)
    abstract fun unregister()
    abstract val contract: ActivityResultContract<I, *>
}

/**
 * Android activity results have no desktop provider. Fail at launch so upstream's failure handler
 * can resolve its pending permission request; never open a window or invent a successful result.
 * A Compose registration must unregister this instance when its owning composition is disposed.
 */
class DesktopUnavailableActivityResultLauncher<I>(
    private val context: Context,
    override val contract: ActivityResultContract<I, *>,
) : ActivityResultLauncher<I>() {
    private val registered = AtomicBoolean(true)

    override fun launch(input: I) {
        check(registered.get()) { "ActivityResultLauncher has been unregistered" }
        val intent = contract.createIntent(context, input)
        throw UnsupportedDesktopCapability(
            "ActivityResultLauncher.launch(${intent.action})",
            "Android activity results are unavailable on the desktop",
        )
    }

    override fun unregister() {
        registered.set(false)
    }
}
