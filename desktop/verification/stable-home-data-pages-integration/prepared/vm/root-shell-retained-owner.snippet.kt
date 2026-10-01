// Integration snippet, not an installation payload or a pretend production fixture.
// Placement: DesktopReadyApp, after the real Runtime and global Settings bridge are built,
// BEFORE the conditional current screen branch. createActualEnvironment is a REQUIRED Root
// factory that supplies every real original API, setting, storage and platform port.
// It is called inside installOwner, not remembered/constructed before retiring the old VM.

var homeOwner by remember(pluginRuntime) {
    mutableStateOf<DesktopOriginalHomeViewModel?>(null)
}
LaunchedEffect(pluginRuntime, sessionEpoch) {
    val capturedEpoch = sessionEpoch // Immutable value; not a delegated live State getter.
    try {
        val selected = pluginRuntime.recommendations.installOwner(capturedEpoch) {
            DesktopOriginalHomeViewModel(createActualEnvironment(capturedEpoch, scope))
        }
        currentCoroutineContext().ensureActive()
        if (repository.sessionEpoch == capturedEpoch && selected.isCurrentOwner())
            homeOwner = selected as DesktopOriginalHomeViewModel
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        // Use Root's actual safe error boundary/retry; do not resurrect the old planner.
        reportHomeInitializationFailure(failure)
    }
}
val currentHome = homeOwner?.takeIf {
    it.capturedEpoch == sessionEpoch && it.isCurrentOwner()
}
// Pass currentHome as DesktopOriginalHomeStateOwner to DesktopOriginalHomeRoot only after
// real port construction succeeded. Pending has real loading/error/retry UI, never fake items.
// Home content covering/disposal does NOT close this owner. Runtime shutdown/epoch owns it.

// Required atomic factory admission, using Root's EXISTING actual SessionStore guard:
val cacheGuard = repository.dynamicCacheSessionGuard
val capturedOwner = cacheGuard.dynamicCacheOwner()
    ?: throw CancellationException("Home session is unavailable")
if (capturedOwner.epoch != capturedEpoch)
    throw CancellationException("Home session changed before construction")
val entryLock = Any()
// entryStillOwned reads Root app/restore lifecycle, not a Home page visibility boolean.
val commitIfCurrent: ((() -> Unit) -> Boolean) = { mutation ->
    var applied = false
    cacheGuard.withCurrentDynamicCacheOwner(capturedOwner) {
        synchronized(entryLock) {
            if (entryStillOwned()) {
                mutation()
                applied = true
            }
        }
    }
    applied
}
// Root should use the SAME callback for Environment state/feedback writes and
// DesktopHomeFollowingCache(pluginStore, commitIfCurrent). No second preference namespace.
