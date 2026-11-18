package androidx.activity.result.contract

import android.content.Context
import android.content.Intent

/** Request/result conversion only; the launcher owns whether launching is supported. */
abstract class ActivityResultContract<I, O> {
    abstract fun createIntent(context: Context, input: I): Intent
    abstract fun parseResult(resultCode: Int, intent: Intent?): O
}
