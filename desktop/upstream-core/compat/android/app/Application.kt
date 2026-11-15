package android.app

import android.content.Context
import androidx.datastore.preferences.DesktopPreferencesDataStoreOwner
import com.bilipai.desktop.compat.UnsupportedDesktopCapability

/** The actual desktop context, including its admitted preference storage, owns this application. */
open class Application(val baseContext: Context) : Context(baseContext.dataDir), DesktopPreferencesDataStoreOwner {
    override val applicationContext: Context get() = this
    override val filesDir get() = baseContext.filesDir
    override val cacheDir get() = baseContext.cacheDir
    override val applicationInfo get() = baseContext.applicationInfo
    override val packageManager get() = baseContext.packageManager
    override val packageName get() = baseContext.packageName
    override val contentResolver get() = baseContext.contentResolver
    override val assets get() = baseContext.assets
    override val resources get() = baseContext.resources
    override fun getExternalFilesDir(type: String?) = baseContext.getExternalFilesDir(type)
    override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences(name, mode)
    override fun desktopPreferencesDataStore(name: String) =
        (baseContext as? DesktopPreferencesDataStoreOwner)?.desktopPreferencesDataStore(name)
            ?: throw UnsupportedDesktopCapability("Application DataStore $name", "the desktop context must own the preference store")
}
