package com.bilipai.desktop.appearance

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Density
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.DesktopPreferenceKey
import com.bilipai.desktop.plugins.DesktopPreferenceSnapshot
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

internal const val WINDOWS_DISPLAY_NAMESPACE = "windows_display"
internal const val WINDOWS_DISPLAY_DEFAULT_PERCENT = 125
internal const val WINDOWS_DISPLAY_MIN_PERCENT = 50
internal const val WINDOWS_DISPLAY_MAX_PERCENT = 200
internal const val WINDOWS_DISPLAY_STEP_PERCENT = 5
internal data class DesktopWindowsDisplaySettings(val percent: Int, val error: String? = null)
internal sealed interface DesktopWindowsScaleCommand {
    data class Adjust(val steps: Int) : DesktopWindowsScaleCommand
    data class Set(val percent: Int) : DesktopWindowsScaleCommand
    data object Reset : DesktopWindowsScaleCommand
}

private val scaleValue = DesktopPreferenceKey("scale_percent") { it }
internal fun decodeDesktopWindowsDisplay(snapshot: DesktopPreferenceSnapshot): DesktopWindowsDisplaySettings {
    val value = snapshot[scaleValue] ?: return DesktopWindowsDisplaySettings(WINDOWS_DISPLAY_DEFAULT_PERCENT)
    val primitive = value as? JsonPrimitive
    val percent = primitive?.takeUnless { it.isString }?.intOrNull
    return if (percent != null && percent in WINDOWS_DISPLAY_MIN_PERCENT..WINDOWS_DISPLAY_MAX_PERCENT)
        DesktopWindowsDisplaySettings(percent)
    else DesktopWindowsDisplaySettings(WINDOWS_DISPLAY_DEFAULT_PERCENT, "窗口缩放配置无效，暂用 125%。请选择有效比例。")
}

internal fun desktopWindowsScaleAfter(current: Int, command: DesktopWindowsScaleCommand): Int = when (command) {
    is DesktopWindowsScaleCommand.Adjust -> (current.toLong() + command.steps.toLong() * WINDOWS_DISPLAY_STEP_PERCENT)
        .coerceIn(WINDOWS_DISPLAY_MIN_PERCENT.toLong(), WINDOWS_DISPLAY_MAX_PERCENT.toLong()).toInt()
    is DesktopWindowsScaleCommand.Set -> command.percent.also { require(it in WINDOWS_DISPLAY_MIN_PERCENT..WINDOWS_DISPLAY_MAX_PERCENT) }
    DesktopWindowsScaleCommand.Reset -> WINDOWS_DISPLAY_DEFAULT_PERCENT
}

internal fun desktopWindowsScaledDensity(system: Density, percent: Int): Density {
    require(system.density.isFinite() && system.density > 0)
    require(percent in WINDOWS_DISPLAY_MIN_PERCENT..WINDOWS_DISPLAY_MAX_PERCENT)
    return Density(system.density * percent / 100f, system.fontScale)
}

/** Serializes explicit Windows input/settings intent over the SAME atomic original Store.
 * Every command recalculates from fresh CAS data; only the actual Root Context mints a permit.
 * This does not migrate or edit Android display preferences, or create a second preferences file.
 */
internal class DesktopWindowsDisplayScaleController(
    private val store: DesktopPluginStore,
    parentScope: CoroutineScope,
) : AutoCloseable {
    private val alive = AtomicBoolean(true)
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private class WindowRegistration(val context: DesktopOriginalPlayerSettingsContext)
    private val windowContext = AtomicReference<WindowRegistration?>()
    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val namespaceError = runCatching { store.requireObjectNamespace(WINDOWS_DISPLAY_NAMESPACE) }
        .exceptionOrNull()?.message
    private val snapshot = store.snapshot(WINDOWS_DISPLAY_NAMESPACE)
    val settings: StateFlow<DesktopWindowsDisplaySettings> = snapshot.map {
        decodeDesktopWindowsDisplay(it).let { value -> if (namespaceError == null) value else value.copy(error = namespaceError) }
    }.stateIn(scope, SharingStarted.Eagerly,
        decodeDesktopWindowsDisplay(snapshot.value).let { if (namespaceError == null) it else it.copy(error = namespaceError) })
    private val mutableWriteError = MutableStateFlow<String?>(null)
    val writeError: StateFlow<String?> = mutableWriteError.asStateFlow()

    private class Request(val command: DesktopWindowsScaleCommand,
        val context: DesktopOriginalPlayerSettingsContext, val caller: Job,
        val owns: () -> Boolean, val registration: WindowRegistration?,
        val completion: CompletableDeferred<Unit>?)

    init {
        scope.launch(Dispatchers.IO) {
            try {
                for (request in requests) {
                    try {
                        apply(request)
                        mutableWriteError.value = null
                        request.completion?.complete(Unit)
                    } catch (cancelled: CancellationException) {
                        request.completion?.completeExceptionally(cancelled)
                        if (!currentCoroutineContext().isActive) throw cancelled
                    } catch (failure: Exception) {
                        mutableWriteError.value = failure.message ?: "窗口缩放无法保存"
                        request.completion?.completeExceptionally(failure)
                    }
                }
            } finally {
                while (true) {
                    val pending = requests.tryReceive().getOrNull() ?: break
                    pending.completion?.completeExceptionally(CancellationException("Windows display owner retired"))
                }
            }
        }
    }

    /** Actual Shell registers its already existing image/Root-owned global settings Context. */
    fun registerWindowContext(context: DesktopOriginalPlayerSettingsContext): AutoCloseable {
        check(alive.get()); require(context.pluginContext.store === store)
        context.requireCurrent()
        val registration = WindowRegistration(context)
        windowContext.set(registration)
        return AutoCloseable { windowContext.compareAndSet(registration, null) }
    }

    /** EDT input captures this exact registration; a successor never revives the queued request. */
    fun submitWindow(command: DesktopWindowsScaleCommand, owns: () -> Boolean): Boolean {
        if (!alive.get() || !job.isActive || !owns()) return false
        val registration = windowContext.get() ?: return false
        if (!registration.context.isCurrentForOriginalWrite()) return false
        return requests.trySend(Request(command, registration.context, job, owns, registration, null)).isSuccess
    }

    suspend fun setPercent(context: DesktopOriginalPlayerSettingsContext, percent: Int, owns: () -> Boolean) {
        require(context.pluginContext.store === store)
        require(percent in WINDOWS_DISPLAY_MIN_PERCENT..WINDOWS_DISPLAY_MAX_PERCENT)
        val caller = checkNotNull(currentCoroutineContext()[Job])
        context.requireCurrent(); caller.ensureActive()
        if (!alive.get() || !owns()) throw CancellationException("Windows display settings retired")
        val completion = CompletableDeferred<Unit>()
        requests.send(Request(DesktopWindowsScaleCommand.Set(percent), context, caller, owns, null, completion))
        completion.await()
    }

    private suspend fun apply(request: Request) {
        val worker = currentCoroutineContext()
        fun checkRequest() {
            worker.ensureActive(); request.caller.ensureActive()
            if (!alive.get() || !request.owns() ||
                (request.registration != null && windowContext.get() !== request.registration))
                throw CancellationException("Windows display request owner retired")
            request.context.requireCurrent()
        }
        store.updateOriginalFromSnapshot(WINDOWS_DISPLAY_NAMESPACE, ::checkRequest,
            { request.context.preferenceWritePermit(::checkRequest) }) { fresh ->
            val next = desktopWindowsScaleAfter(decodeDesktopWindowsDisplay(fresh).percent, request.command)
            Unit to mapOf("scale_percent" to JsonPrimitive(next))
        }
    }

    override fun close() {
        if (!alive.compareAndSet(true, false)) return
        windowContext.set(null)
        requests.close(); job.cancel()
    }
}

internal val LocalDesktopWindowsDisplayScale = staticCompositionLocalOf<DesktopWindowsDisplayScaleController> {
    error("The actual Main Window display scale controller is required")
}
internal val LocalDesktopWindowsSystemDensity = staticCompositionLocalOf<Density?> { null }
