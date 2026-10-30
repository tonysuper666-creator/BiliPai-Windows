package com.bilipai.desktop.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.DesktopPreferencesDataStoreOwner
import androidx.datastore.preferences.core.Preferences
import com.bilipai.desktop.compat.UnsupportedDesktopCapability
import com.android.purebilibili.feature.message.notification.MessageNotificationSource
import com.android.purebilibili.feature.message.notification.PendingMessageNotification
import com.bilipai.desktop.data.DesktopHomeNavRequestReceipt
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.DesktopSettingsDocumentDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicBoolean

/** The Android Context upstream's message notification files receive on Windows: one actual retained Root.
 * Its two DataStores ("message_notification_settings", "message_notification_state") are namespaces of the SAME
 * global settings document; reads and writes go through this Root's admission, so a retired Root cannot write.
 * The receipt is captured when this Root is installed, including its successful UI credential-installation stamp.
 * It never adopts a new account/stamp on an old poll or resurrects an old Root. */
internal class DesktopMessageNotificationContext private constructor(
    internal val repository: DesktopRepository,
    internal val store: DesktopPluginStore,
    internal val root: DesktopHomeRetainedRoot,
    internal val routes: DesktopOriginalRootRouteAssembly,
    private val receipt: DesktopHomeNavRequestReceipt,
) : android.content.Context(store.root.toFile()), DesktopPreferencesDataStoreOwner, AutoCloseable {
    private val closed = AtomicBoolean()
    internal val epoch get() = root.capturedEpoch
    internal val mid get() = root.entry.gate.mid
    override val applicationContext: android.content.Context get() = this
    // The transaction lock file of upstream's MessageNotificationTransaction lives beside the settings document.
    override val filesDir: java.io.File get() = store.root.toFile()
    /** The Worker's periodic work and the resident Service of this window; nothing outlives the Root. */
    internal val tasks = DesktopMessageNotificationTasks(root.entry.gate.scope)

    /** This window's Windows notification centre presentation and network sources, attached by the Root consumer. */
    @Volatile internal var presentation: DesktopMessageNotificationPresentation? = null
        private set
    @Volatile internal var sources: DesktopMessageNotificationSources? = null
        private set
    private val shown = MutableStateFlow(false)
    /** The window is shown and not minimised: the resident lane's "screen on". */
    internal val windowShown: StateFlow<Boolean> get() = shown
    internal fun attach(presentation: DesktopMessageNotificationPresentation, sources: DesktopMessageNotificationSources) {
        this.presentation = presentation
        this.sources = sources
    }
    internal fun windowShownChanged(value: Boolean) { shown.value = value }

    private fun retained() = !closed.get() && root.isCurrentOwner() && routes.owns()
    internal fun admit(block: () -> Unit): Boolean {
        var applied = false
        repository.withCurrentHomeNavRequest(receipt, ::retained) {
            root.entry.gate.commit { if (retained()) { block(); applied = true } }
        }
        return applied
    }
    internal fun isCurrent(): Boolean = admit {}
    internal fun hasAuthenticatedSession(): Boolean {
        var authenticated = false
        admit {
            authenticated = mid != null && !repository.ownedHomeCookie("SESSDATA", epoch, ::retained).isNullOrBlank()
        }
        return authenticated
    }
    /** This Root account's SESSDATA, or null when it has none or the Root retired (the Checker's same-session test). */
    internal fun sessData(): String? {
        var value: String? = null
        admit { value = repository.ownedHomeCookie("SESSDATA", epoch, ::retained) }
        return value
    }
    /** The App access token of this Root's account, or null for a web-only session. Same admission as the cookie
     * read. A retired Root is not a web-only session: it throws instead of returning null. */
    internal fun appAccessToken(): String? {
        var token: String? = null
        if (!admit { token = repository.ownedHomeAccessToken(epoch, ::retained) })
            throw CancellationException("Message notification Root retired")
        return token
    }
    internal fun check() {
        if (!isCurrent()) throw CancellationException("Message notification Root retired")
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        tasks.close()
        presentation = null
        sources = null
    }

    private val settingsStore by lazy { gatedStore("message_notification_settings") }
    private val stateStore by lazy { gatedStore("message_notification_state") }
    override fun desktopPreferencesDataStore(name: String): DataStore<Preferences> = when (name) {
        "message_notification_settings" -> settingsStore
        "message_notification_state" -> stateStore
        else -> throw UnsupportedDesktopCapability("DataStore \"$name\"", "a message notification window holds only its settings and state")
    }
    override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
        throw UnsupportedDesktopCapability("SharedPreferences \"$name\"", "message notifications keep their values in DataStores")

    /** The original keys in the settings document. Every read re-checks this Root; a write mints its permit inside
     * the Root admission and is recomputed by the document CAS, as DataStore serializes edits. */
    private fun gatedStore(namespace: String): DataStore<Preferences> = object : DataStore<Preferences> {
        private val backing = DesktopSettingsDocumentDataStore(store, namespace, ::check) { checkRequest ->
            checkRequest()
            var permit: DesktopPluginStore.OriginalPreferenceWritePermit? = null
            if (!admit { permit = DesktopPluginStore.OriginalPreferenceWritePermit(store) })
                throw CancellationException("Message notification preference source retired")
            requireNotNull(permit)
        }
        override val data: Flow<Preferences> = backing.data.map { currentCoroutineContext().ensureActive(); check(); it }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            check()
            return backing.updateData(transform).also { check() }
        }
    }

    companion object {
        internal fun captureOrNull(repository: DesktopRepository, store: DesktopPluginStore,
            root: DesktopHomeRetainedRoot, routes: DesktopOriginalRootRouteAssembly): DesktopMessageNotificationContext? =
            try {
                val receipt = repository.captureHomeNavRequest(root.capturedEpoch, root.entry.gate.mid) {
                    root.isCurrentOwner() && routes.owns()
                }
                DesktopMessageNotificationContext(repository, store, root, routes, receipt)
                    .also { it.check() }
            } catch (_: CancellationException) { null }
    }
}

/** Where upstream's Notifier posts on Windows: the notification centre balloons and tray menu of one window. */
internal interface DesktopMessageNotificationPresentation {
    fun canPost(): Boolean
    suspend fun activeMessageKeys(): Set<String>
    /** [cancelled] are items the server now reports as read; [pendingCounts] are the committed unread counts per group. */
    suspend fun postAll(pending: List<PendingMessageNotification>, cancelled: Set<String> = emptySet(),
        pendingCounts: Map<String, Int>? = null)
    suspend fun clear()
}

/** The read-only network source of one Checker poll (upstream's NetworkMessageNotificationSource), opened over this
 * window's Root for the duration of [block]. */
internal interface DesktopMessageNotificationSources {
    suspend fun <T> poll(context: DesktopMessageNotificationContext, block: suspend (MessageNotificationSource) -> T): T
}

/** The desktop's persistent tasks for one window: upstream's unique periodic work (WorkManager) and the resident
 * Service. They run while this window's Root lives and end with it; nothing runs after the client exits. */
internal class DesktopMessageNotificationTasks(parent: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
) : AutoCloseable {
    private val lifetime = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + lifetime + dispatcher)
    private class Periodic(val periodMs: Long, val job: Job)
    private val periodic = mutableMapOf<String, Periodic>()
    private val lastRunAt = mutableMapOf<String, Long>()
    private var resident: Job? = null

    /** WorkManager's ExistingPeriodicWorkPolicy.UPDATE: an enqueued work keeps its schedule; a new period keeps the
     * last run as its origin. A new work runs at once. [work] answers false for Result.retry(), which waits the
     * linear backoff (backoff × attempt, at most five hours) instead of the period. */
    @Synchronized fun enqueueUniquePeriodicWork(name: String, periodMs: Long, backoffMs: Long, work: suspend () -> Boolean) {
        require(periodMs > 0 && backoffMs > 0)
        val current = periodic[name]
        if (current != null && current.job.isActive && current.periodMs == periodMs) return
        current?.job?.cancel()
        val firstDelay = lastRunAt[name]?.let { (it + periodMs - now()).coerceAtLeast(0) } ?: 0L
        periodic[name] = Periodic(periodMs, scope.launch {
            var wait = firstDelay
            var attempt = 0
            while (isActive) {
                delay(wait)
                synchronized(this@DesktopMessageNotificationTasks) { lastRunAt[name] = now() }
                if (work()) { attempt = 0; wait = periodMs }
                else { attempt++; wait = minOf(backoffMs * attempt, MAX_BACKOFF_MS) }
            }
        })
    }
    @Synchronized fun cancelUniqueWork(name: String) {
        periodic.remove(name)?.job?.cancel()
        lastRunAt.remove(name)
    }
    @Synchronized fun isScheduled(name: String): Boolean = periodic[name]?.job?.isActive == true

    /** A running resident lane keeps running, as a repeated startForegroundService does. */
    @Synchronized fun startResident(run: suspend () -> Unit) {
        if (resident?.isActive == true) return
        resident = scope.launch { run() }
    }
    @Synchronized fun stopResident() { resident?.cancel(); resident = null }
    @Synchronized fun isResidentRunning(): Boolean = resident?.isActive == true

    override fun close() { lifetime.cancel() }

    private companion object { const val MAX_BACKOFF_MS = 5 * 60 * 60_000L }
}

/** The message notification Root behind an Android Context upstream received; any other Context is refused. */
internal fun desktopMessageNotificationContext(context: android.content.Context): DesktopMessageNotificationContext =
    context.applicationContext as? DesktopMessageNotificationContext
        ?: throw UnsupportedDesktopCapability("message notifications for ${context::class.java.name}",
            "only a window's message notification Context holds a Windows notification presentation")

/** The Root account the Checker compares where Android reads TokenManager's caches. */
internal class DesktopMessageNotificationAccount(private val context: DesktopMessageNotificationContext) {
    fun mid(): Long? = context.mid.takeIf { context.isCurrent() }
    fun sessData(): String? = context.sessData()
}
internal fun desktopMessageNotificationAccount(context: android.content.Context) =
    DesktopMessageNotificationAccount(desktopMessageNotificationContext(context))

/** What Android's boot_count stands for in the recent-check suppression: the origin of the elapsed clock. The desktop
 * elapsed clock (SystemClock) is this process's monotonic clock, so any value fixed once per process identifies it.
 * Only java.base: the packaged Windows runtime has no java.management module. */
private object DesktopMessageNotificationClockEpoch { val value: Long = System.currentTimeMillis() }
internal fun desktopMessageNotificationClockEpoch(): Long = DesktopMessageNotificationClockEpoch.value
