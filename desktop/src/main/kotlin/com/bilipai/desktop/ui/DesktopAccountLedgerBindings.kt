package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.store.AccountSessionIdentity
import com.android.purebilibili.data.model.AccountLedgerKind
import com.android.purebilibili.feature.login.QrAuthorizationSession
import com.android.purebilibili.feature.profile.AccountLedgerScreen
import com.android.purebilibili.feature.profile.AccountLedgerViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

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
    kind: AccountLedgerKind,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val parent = profile.environment
    val environment = remember(parent, scope) {
        DesktopAccountLedgerEnvironment(scope, parent.api, parent.owns,
            { parent.ensureOwned(); parent.accounts.qrAuthorizationSession() }, parent.commit)
    }
    val model = remember(environment) { AccountLedgerViewModel(environment) }
    AccountLedgerScreen(initialKind = kind, onBack = onBack, model = model)
}
