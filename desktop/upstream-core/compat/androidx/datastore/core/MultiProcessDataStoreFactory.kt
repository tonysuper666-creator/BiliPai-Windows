package androidx.datastore.core

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.bilipai.desktop.compat.UnsupportedDesktopCapability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Compile surface only. The JVM DataStore artifacts have no multi-process store, and a process-local
 * store would silently drop the cross-process guarantees upstream TokenManager relies on. Desktop
 * session state lives in DesktopSessionStore.
 */
object MultiProcessDataStoreFactory {
    @Suppress("UNUSED_PARAMETER")
    fun <T> create(
        serializer: Serializer<T>,
        corruptionHandler: ReplaceFileCorruptionHandler<T>? = null,
        migrations: List<DataMigration<T>> = listOf(),
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        produceFile: () -> File
    ): DataStore<T> = throw UnsupportedDesktopCapability("Multi-process DataStore", "desktop session state is stored by DesktopSessionStore")
}
