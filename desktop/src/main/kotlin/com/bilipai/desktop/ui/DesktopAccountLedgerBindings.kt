package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.store.AccountSessionIdentity
import com.android.purebilibili.feature.login.QrAuthorizationSession
import com.android.purebilibili.feature.profile.AccountLedgerScreen
import com.android.purebilibili.feature.profile.AccountLedgerViewModel
import com.android.purebilibili.navigation3.BiliPaiNavKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

/** A ledger visit borrows Profile's gated services and account port. Its child scope ends on back;
 * Profile's admission also ends it on account changes, including re-login with the same MID. */
internal class DesktopAccountLedgerEnvironment(
    val scope: CoroutineScope,
    val api: BilibiliApi,
    private val owns: () -> Boolean,
    private val session: () -> QrAuthorizationSession,
    private val commit: (() -> Unit) -> Boolean,
) {
    val identity = run {
        ensureOwned()
        AccountSessionIdentity(mid = session().mid?.takeIf { it > 0L })
    }

    fun isCurrent(): Boolean = scope.coroutineContext[Job]?.isActive == true && owns()

    fun ensureOwned() {
        if (!isCurrent()) throw CancellationException("Account ledger page retired")
    }

    fun credentials(requested: AccountSessionIdentity): QrAuthorizationSession {
        ensureOwned()
        check(requested == identity) { "账号已切换，请重新加载" }
        val current = session()
        check(current.mid?.takeIf { it > 0L } == identity.mid) { "账号已切换，请重新加载" }
        ensureOwned()
        return current
    }

    fun publish(block: () -> Unit) {
        ensureOwned()
        if (!commit { ensureOwned(); block() }) throw CancellationException("Account ledger page retired")
    }
}

@Composable
internal fun DesktopOriginalAccountLedgerHost(
    profile: DesktopOriginalProfileBinding,
    routes: DesktopOriginalRootRouteAssembly,
    key: BiliPaiNavKey.AccountLedger,
) {
    val scope = rememberCoroutineScope()
    val parent = profile.environment
    val environment = remember(parent, scope, routes, key) {
        // Navigation removes the entry before Compose disposes it. Read the actual stack at
        // admission so a queued callback cannot send or publish in that interval.
        DesktopAccountLedgerEnvironment(scope, parent.api,
            { parent.owns() && routes.owns() && routes.currentKey == key },
            { parent.ensureOwned(); parent.accounts.qrAuthorizationSession() }, parent.commit)
    }
    val model = remember(environment) { AccountLedgerViewModel(environment) }
    AccountLedgerScreen(initialKind = key.kind, model = model, onBack = {
        routes.callbackFor(key) { scope.cancel(); routes.back() }
    })
}
