package androidx.datastore.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.properties.ReadOnlyProperty

fun Context.preferencesDataStoreFile(name: String): File = File(filesDir, "datastore/$name.preferences_pb")

/**
 * A desktop Context whose upstream preference DataStores are not files: the desktop settings document
 * (DesktopSettingsDocumentDataStore). An owner answers every name it serves and fails for the others.
 */
interface DesktopPreferencesDataStoreOwner {
    fun desktopPreferencesDataStore(name: String): DataStore<Preferences>
}

private val stores = ConcurrentHashMap<String, DataStore<Preferences>>()
fun preferencesDataStore(name: String): ReadOnlyProperty<Context, DataStore<Preferences>> =
    ReadOnlyProperty { ctx, _ ->
        (ctx as? DesktopPreferencesDataStoreOwner)?.desktopPreferencesDataStore(name) ?: run {
            val f = ctx.preferencesDataStoreFile(name)
            stores.getOrPut(f.absolutePath) { PreferenceDataStoreFactory.create { f } }
        }
    }
