package androidx.activity.result

import android.content.Intent

/** Result data only. Constructing this value never launches an Android activity. */
data class ActivityResult(val resultCode: Int, val data: Intent?)
