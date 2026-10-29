package com.bilipai.desktop.ui

import com.android.purebilibili.core.events.BrandSuccessEvents
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/** The Root's success feedback and the caller's origin, carried by the coroutine that runs an upstream repository.
 * Upstream repositories (ActionRepository) announce successes through a process-wide BrandSuccessEvents object;
 * the desktop has one instance per Root and only celebrates for a still-current caller. */
class DesktopUpstreamBrandSuccessContext(
    val events: BrandSuccessEvents,
    val origin: DesktopBrandSuccessOrigin,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DesktopUpstreamBrandSuccessContext>
}

/** What the upstream repository patches call instead of the process-wide BrandSuccessEvents object. A call outside a
 * [DesktopUpstreamBrandSuccessContext] is a wiring error and fails loudly instead of dropping the feedback. */
object DesktopUpstreamBrandSuccess {
    suspend fun favoriteSaved() {
        current("favoriteSaved").let { it.events.favoriteSaved(it.origin) }
    }

    suspend fun followChanged(following: Boolean, detail: String? = null) {
        current("followChanged").let { it.events.followChanged(it.origin, following, detail) }
    }

    private suspend fun current(event: String): DesktopUpstreamBrandSuccessContext =
        currentCoroutineContext()[DesktopUpstreamBrandSuccessContext] ?: throw IllegalStateException(
            "BrandSuccessEvents.$event from an upstream repository needs the Root's feedback and the caller's origin " +
                "(run the call inside DesktopUpstreamBrandSuccessContext)")
}
