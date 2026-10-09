package com.bilipai.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Only in-memory JSON drafts and their original save/import Jobs defer installation. */
internal class DesktopPluginJsonEditorUpdateHold(
    private val mainOwned: () -> Boolean,
    private val startAllowed: () -> Boolean,
) : AutoCloseable {
    internal class Instance internal constructor(private val owner: DesktopPluginJsonEditorUpdateHold) : AutoCloseable {
        private val retired = AtomicBoolean(false)
        internal fun isRetired(): Boolean = retired.get()
        internal fun register(job: Job): Boolean = owner.register(this, job)
        override fun close() {
            if (retired.compareAndSet(false, true)) owner.unmount(this)
        }
    }
    internal data class Activity(val mounted: Boolean, val jobs: Set<Job>)

    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val observed = MutableStateFlow<Map<Instance, Activity>>(emptyMap())
    internal val activity = observed.asStateFlow()

    // These two callbacks are the existing Main's short UI predicates, never Store/IO admission.
    internal fun canBegin(): Boolean = !closed.get() && mainOwned() && startAllowed()
    internal fun blocksUpdateInstallation(): Boolean = observed.value.isNotEmpty()

    // Existing UI dispatcher: acquire the hold before any draft state is published.
    internal fun begin(): Instance? = synchronized(lock) {
        if (!canBegin()) return@synchronized null
        val instance = Instance(this)
        observed.value = observed.value + (instance to Activity(true, emptySet()))
        instance
    }

    private fun register(instance: Instance, job: Job): Boolean {
        synchronized(lock) {
            val current = observed.value[instance] ?: return false
            if (!current.mounted || instance.isRetired() || !canBegin() || job.isActive || job.isCompleted || job.isCancelled) return false
            if (observed.value.values.any { job in it.jobs }) return false
            observed.value = observed.value + (instance to current.copy(jobs = current.jobs + job))
        }
        // This may run immediately or on a cancellation thread. The lock serializes exact removal.
        job.invokeOnCompletion { complete(instance, job) }
        return true
    }

    private fun complete(instance: Instance, job: Job) = synchronized(lock) {
        val current = observed.value[instance] ?: return@synchronized
        if (job !in current.jobs) return@synchronized
        val remaining = current.jobs - job
        observed.value = if (!current.mounted && remaining.isEmpty()) observed.value - instance
        else observed.value + (instance to current.copy(jobs = remaining))
    }

    private fun unmount(instance: Instance) = synchronized(lock) {
        val current = observed.value[instance] ?: return@synchronized
        observed.value = if (current.jobs.isEmpty()) observed.value - instance
        else observed.value + (instance to current.copy(mounted = false))
    }

    override fun close() = synchronized(lock) {
        if (!closed.compareAndSet(false, true)) return@synchronized
        // Disposal retires starts/forms. Actual cancelled Jobs still hold until all children finish.
        observed.value = observed.value.mapValues { (_, value) -> value.copy(mounted = false) }
            .filterValues { it.jobs.isNotEmpty() }
    }
}

internal class DesktopPluginJsonDraft internal constructor(
    val instance: DesktopPluginJsonEditorUpdateHold.Instance, initial: String,
) : AutoCloseable {
    var text by mutableStateOf(initial)
    override fun close() = instance.close()
}

internal fun openDesktopPluginJsonDraft(
    hold: DesktopPluginJsonEditorUpdateHold, initial: () -> String,
): DesktopPluginJsonDraft? {
    val instance = hold.begin() ?: return null
    return try { DesktopPluginJsonDraft(instance, initial()) }
    catch (failure: Throwable) { instance.close(); throw failure }
}

/** Original page scope and returned LAZY Job are retained, with no reparenting. */
internal fun launchTrackedPluginJson(
    scope: CoroutineScope,
    instance: DesktopPluginJsonEditorUpdateHold.Instance,
    onRejected: () -> Unit = {},
    block: suspend CoroutineScope.() -> Unit,
): Job {
    val job = scope.launch(start = CoroutineStart.LAZY, block = block)
    try {
        if (!instance.register(job) || !job.start()) {
            try { onRejected() } finally { job.cancel() }
        }
    } catch (failure: Throwable) {
        job.cancel()
        throw failure
    }
    return job
}
