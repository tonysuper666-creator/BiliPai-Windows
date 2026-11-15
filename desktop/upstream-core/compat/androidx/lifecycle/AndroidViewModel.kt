package androidx.lifecycle

import android.app.Application

/** A desktop lifecycle ViewModel retaining the actual desktop Application, with the Android API. */
open class AndroidViewModel(private val application: Application) : ViewModel() {
    @Suppress("UNCHECKED_CAST")
    fun <T : Application> getApplication(): T = application as T
}
