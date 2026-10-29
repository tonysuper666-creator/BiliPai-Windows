// Replaces upstream app/src/main/java/com/android/purebilibili/core/store/DataSaverState.kt sha256=5c8700a1e536b06d769b785f3b942cb7c80462e23186d047eb0cd2ddb6ae54b4
// Upstream follows the Android default network (ConnectivityManager callbacks on the main Looper) to tell cellular from
// Wi-Fi. A desktop connection is never cellular, so MOBILE_ONLY (the default) never saves data and ALWAYS always does;
// the mode is the same "data_saver" preference, re-read on every get() because the desktop mirror has no change listener.
package com.android.purebilibili.core.store

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

internal class DataSaverState private constructor() {
    private val activeState = mutableStateOf(false)
    val active: State<Boolean> = activeState

    private fun refresh(context: Context) {
        activeState.value = when (SettingsManager.getDataSaverModeSync(context)) {
            SettingsManager.DataSaverMode.OFF -> false
            SettingsManager.DataSaverMode.ALWAYS -> true
            SettingsManager.DataSaverMode.MOBILE_ONLY -> false
        }
    }

    companion object {
        @Volatile private var instance: DataSaverState? = null

        /** The last resolved state, for callers without an Android Context (Coil requests on the desktop); off before the first get(). */
        fun lastKnownActive(): Boolean = instance?.active?.value ?: false

        fun get(context: Context): DataSaverState = (instance ?: synchronized(this) {
            instance ?: DataSaverState().also { instance = it }
        }).also { it.refresh(context) }
    }
}
