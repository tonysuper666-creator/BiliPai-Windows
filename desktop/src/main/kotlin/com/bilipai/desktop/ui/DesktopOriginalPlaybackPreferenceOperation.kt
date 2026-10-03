package com.bilipai.desktop.ui

import com.bilipai.desktop.plugins.DesktopPreferenceSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.CoroutineContext

/** Opt-in journal for one original settings action. It has no independent file/cache.
 * Original pure edits and mirror projectors are replayed on each actual Store CAS snapshot.
 * The captured caller and current Root/page are checked before the existing permit is minted.
 */
internal class DesktopOriginalPlaybackPreferenceOperation private constructor(
    private val context: DesktopOriginalPlayerSettingsContext,
    private val caller: CoroutineContext,
    private val owns: () -> Boolean,
) {
    private enum class Phase { STAGING, REPLAY, COMPLETE }
    private sealed interface Recipe {
        class Canonical(val edit: (DesktopOriginalPlayerPreferenceValues) -> Unit) : Recipe
        class Mirror(val name: String, val edits: Map<String, JsonElement?>) : Recipe
        class Projection(val project: (DesktopOriginalPlayerPreferenceValues) -> Unit) : Recipe
    }
    private var phase = Phase.STAGING
    private val recipes = mutableListOf<Recipe>()
    private val stagingValues = DesktopOriginalPlayerPreferenceValues(
        DesktopPreferenceSnapshot(context.pluginContext.store.preferences("settings")))
    private val stagingMirrors = linkedMapOf<String, MutableMap<String, JsonElement?>>()
    private var replayMirrors: MutableMap<String, MutableMap<String, JsonElement?>>? = null

    private fun checkRequest() {
        caller.ensureActive()
        if (!owns()) throw CancellationException("Original playback settings request retired")
        context.requireCurrent()
        com.bilipai.desktop.plugins.DesktopSubscriptionWriteAdmission.checkCurrentRequestOrOriginal()
    }
    private fun checkContext(actual: DesktopOriginalPlayerSettingsContext) {
        check(actual === context) { "Original playback settings operation belongs to a different context" }
        check(phase != Phase.COMPLETE) { "Original playback settings operation has completed" }
        checkRequest()
    }
    fun values(actual: DesktopOriginalPlayerSettingsContext): DesktopOriginalPlayerPreferenceValues {
        checkContext(actual)
        check(phase == Phase.STAGING) { "Original mirror projector must not launch a canonical read" }
        return stagingValues
    }
    fun edit(actual: DesktopOriginalPlayerSettingsContext,
             block: (DesktopOriginalPlayerPreferenceValues) -> Unit): DesktopOriginalPlayerPreferenceValues {
        checkContext(actual)
        check(phase == Phase.STAGING) { "Original mirror projector must not edit canonical preferences" }
        block(stagingValues)
        recipes += Recipe.Canonical(block)
        return stagingValues
    }
    fun mirrorValues(actual: DesktopOriginalPlayerSettingsContext, name: String): JsonObject {
        checkContext(actual)
        val values = context.pluginContext.store.preferences(name).toMutableMap()
        val changes = if (phase == Phase.REPLAY) checkNotNull(replayMirrors)[name] else stagingMirrors[name]
        changes?.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        return JsonObject(values)
    }
    fun mirror(actual: DesktopOriginalPlayerSettingsContext, name: String, edits: Map<String, JsonElement?>) {
        checkContext(actual)
        val target = if (phase == Phase.REPLAY) checkNotNull(replayMirrors) else stagingMirrors
        target.getOrPut(name) { linkedMapOf() }.putAll(edits)
        if (phase == Phase.STAGING) recipes += Recipe.Mirror(name, edits.toMap())
    }
    private fun deferProjection(actual: DesktopOriginalPlayerSettingsContext,
                                project: (DesktopOriginalPlayerPreferenceValues) -> Unit): Boolean {
        checkContext(actual)
        if (phase == Phase.REPLAY) return false
        recipes += Recipe.Projection(project)
        // Execute only against the private journal for read-your-writes. Nothing is published.
        val before = recipes.size
        phase = Phase.REPLAY
        replayMirrors = stagingMirrors
        try { project(stagingValues) }
        finally { replayMirrors = null; phase = Phase.STAGING }
        check(recipes.size == before) { "Original mirror projector appended a canonical recipe" }
        return true
    }
    private fun publish() {
        checkRequest()
        if (recipes.isEmpty()) return
        context.pluginContext.store.updateOriginalNamespacesFromSnapshot("settings", ::checkRequest,
            { context.preferenceWritePermit(::checkRequest) }) { fresh ->
            checkRequest()
            val canonical = DesktopOriginalPlayerPreferenceValues(fresh)
            val mirrors = linkedMapOf<String, MutableMap<String, JsonElement?>>()
            phase = Phase.REPLAY
            replayMirrors = mirrors
            try {
                recipes.forEach { recipe -> when (recipe) {
                    is Recipe.Canonical -> recipe.edit(canonical)
                    is Recipe.Mirror -> mirrors.getOrPut(recipe.name) { linkedMapOf() }.putAll(recipe.edits)
                    is Recipe.Projection -> recipe.project(canonical)
                } }
            } finally { replayMirrors = null; phase = Phase.STAGING }
            checkRequest()
            Unit to buildMap<String, Map<String, JsonElement?>> {
                if (canonical.changes.isNotEmpty()) put("settings", canonical.changes.toMap())
                mirrors.forEach { (name, changes) ->
                    require(name != "settings") { "Canonical preferences require the original DataStore edit path" }
                    put(name, changes.toMap())
                }
            }
        }
    }
    companion object {
        private val local = ThreadLocal<DesktopOriginalPlaybackPreferenceOperation?>()
        fun currentOrNull(): DesktopOriginalPlaybackPreferenceOperation? = local.get()
        fun deferMirrorProjection(context: DesktopOriginalPlayerSettingsContext,
                                  project: (DesktopOriginalPlayerPreferenceValues) -> Unit): Boolean =
            local.get()?.deferProjection(context, project) ?: false
        suspend fun <T> runMirrorHealing(context: DesktopOriginalPlayerSettingsContext,
                                        block: suspend () -> T): T {
            local.get()?.let { operation ->
                // A getter collected inside an original setter joins that same journal.
                // It cannot create a nested writer or smuggle a foreign context into it.
                operation.checkContext(context)
                return block()
            }
            return run(context, context::isCurrentForOriginalWrite, block)
        }
        /** Whole original setters enter once when called by an existing player VM, or
         * join the same captured page action journal. They never start a nested writer. */
        suspend fun <T> runOriginalSetter(context: DesktopOriginalPlayerSettingsContext,
                                         block: suspend () -> T): T {
            local.get()?.let { operation ->
                operation.checkContext(context)
                return block()
            }
            return run(context, context::isCurrentForOriginalWrite, block)
        }
        suspend fun <T> run(context: DesktopOriginalPlayerSettingsContext, owns: () -> Boolean,
                            block: suspend () -> T): T {
            check(local.get() == null) { "Original playback settings operations must not be nested" }
            val caller = currentCoroutineContext()
            caller.ensureActive(); context.requireCurrent()
            if (!owns()) throw CancellationException("Original playback settings request retired")
            val operation = DesktopOriginalPlaybackPreferenceOperation(context, caller, owns)
            return withContext(Dispatchers.IO + local.asContextElement(operation)) {
                try {
                    operation.checkRequest()
                    val result = block()
                    operation.publish()
                    result
                } finally { operation.phase = Phase.COMPLETE }
            }
        }
    }
}
