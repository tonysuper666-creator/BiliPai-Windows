package com.bilipai.desktop.ui

import com.android.purebilibili.feature.list.HistoryViewModel
import com.android.purebilibili.feature.list.ListUiState
import com.android.purebilibili.navigation3.BiliPaiNavKey
import java.awt.EventQueue

/** One genuine FIRST error callback, captured by the original CommonList renderer from
 * its own displayed state. No latest-error action is placed outside that original slot. */
internal class DesktopHistoryFailureLoginIntent private constructor(
    override val root: DesktopHomeRetainedRoot,
    private val entry: DesktopPersonalListEntry,
    private val actualRouteKey: BiliPaiNavKey,
    private val model: HistoryViewModel,
    private val failure: DesktopHistoryReadFailure,
    private val stillPresented: () -> Boolean,
    override val destination: BiliPaiNavKey,
) : DesktopReadFailureLoginIntent {
    override val sourceEpoch: Long get() = failure.source.primaryInstallation.epoch
    override val sourceMid: Long? get() = failure.source.primaryInstallation.mid

    private fun sameMount(routes: DesktopOriginalRootRouteAssembly): Boolean {
        if (routes.root !== root || !routes.owns() || !stillPresented() || !entry.owns() ||
            entry.homeGate !== root.entry.gate || entry.viewModel !== model ||
            failure.source.environment !== entry.environment || failure.source.route !== entry.key ||
            actualRouteKey != entry.key || sourceEpoch != root.capturedEpoch ||
            sourceMid != root.entry.gate.mid) return false
        return when (actualRouteKey) {
            BiliPaiNavKey.History -> routes.currentKey == BiliPaiNavKey.MainHost &&
                routes.loginReadDestination() == BiliPaiNavKey.History
            is BiliPaiNavKey.HistorySearch -> routes.currentKey === actualRouteKey
            else -> false
        }
    }
    private fun current(routes: DesktopOriginalRootRouteAssembly): Boolean = sameMount(routes) &&
        failure.code == -101 && failure.slot == DesktopHistoryReadFailureSlot.FIRST &&
        model.uiState.value.error != null && model.desktopHistoryReadFailure() === failure &&
        destinationFor(failure) == destination

    override fun admit(routes: DesktopOriginalRootRouteAssembly, block: () -> Unit): Boolean {
        check(EventQueue.isDispatchThread())
        // Reject an unrelated old Root before entering its gate. The actual source then
        // re-enters the SAME primary Store/Home gate; checkpoint has already run outside it.
        if (!sameMount(routes)) return false
        var applied = false
        failure.source.inspect { if (current(routes)) { block(); applied = true } }
        return applied
    }

    companion object {
        private fun destinationFor(failure: DesktopHistoryReadFailure): BiliPaiNavKey? =
            when (val parameters = failure.source.parameters) {
                is DesktopHistoryReadParameters.Search ->
                    if (parameters.page == 1 && parameters.keyword.isNotBlank() &&
                        parameters.keyword == failure.searchQuery)
                        BiliPaiNavKey.HistorySearch(parameters.keyword) else null
                is DesktopHistoryReadParameters.List ->
                    if (parameters.max == 0L && parameters.viewAt == 0L && failure.searchQuery.isEmpty())
                        if (failure.source.route is BiliPaiNavKey.HistorySearch)
                            BiliPaiNavKey.HistorySearch("") else BiliPaiNavKey.History
                    else null
            }

        fun capture(routes: DesktopOriginalRootRouteAssembly, entry: DesktopPersonalListEntry,
            actualRouteKey: BiliPaiNavKey, model: HistoryViewModel, displayed: ListUiState,
            stillPresented: () -> Boolean): DesktopHistoryFailureLoginIntent? {
            check(EventQueue.isDispatchThread())
            if (!routes.owns() || !entry.owns() || !stillPresented() ||
                entry.homeGate !== routes.root.entry.gate || entry.viewModel !== model ||
                displayed.error == null || model.uiState.value !== displayed) return null
            val failure = model.desktopHistoryReadFailure() ?: return null
            val destination = destinationFor(failure) ?: return null
            val intent = DesktopHistoryFailureLoginIntent(routes.root, entry, actualRouteKey, model,
                failure, stillPresented, destination)
            var result: DesktopHistoryFailureLoginIntent? = null
            intent.admit(routes) {
                // FIRST provider is invoked by CommonList with its real current renderer state.
                // Later item-only copies may preserve the same failure; a new request/error may not.
                if (model.uiState.value === displayed) result = intent
            }
            return result
        }
    }
}
