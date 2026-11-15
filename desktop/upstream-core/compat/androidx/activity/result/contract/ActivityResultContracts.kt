package androidx.activity.result.contract

import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResult

class ActivityResultContracts {
    class StartActivityForResult : ActivityResultContract<Intent, ActivityResult>() {
        override fun createIntent(context: Context, input: Intent): Intent = input
        override fun parseResult(resultCode: Int, intent: Intent?): ActivityResult =
            ActivityResult(resultCode, intent)
    }
}
