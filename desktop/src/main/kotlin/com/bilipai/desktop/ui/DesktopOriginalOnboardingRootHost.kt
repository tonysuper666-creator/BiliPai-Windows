package com.bilipai.desktop.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import com.android.purebilibili.core.ui.AppAlertDialog
import com.android.purebilibili.feature.onboarding.APP_WELCOME_PREFS_NAME
import com.android.purebilibili.feature.onboarding.OnboardingScreen
import com.android.purebilibili.feature.onboarding.USER_AGREEMENT_ACK_KEY
import com.android.purebilibili.feature.onboarding.isUserAgreementRequired
import com.android.purebilibili.feature.settings.RELEASE_DISCLAIMER_ACK_KEY
import com.android.purebilibili.navigation.ScreenRoutes
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.android.purebilibili.navigation3.resolveInitialBiliPaiBackStack
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicReference

/** Windows starts directly in Home. Legal/help documents remain available in settings.
 * This startup policy neither accepts an agreement nor writes diagnostic consent. */
internal class DesktopOriginalOnboardingPreferences(context: DesktopPluginContext) {
    private val store = context.store
    private val welcome = context.getSharedPreferences(APP_WELCOME_PREFS_NAME, DesktopPluginContext.MODE_PRIVATE)
    val openPortraitFeedOnStartup = false

    fun isRequired(): Boolean = false
    fun initialStack(includeStartupPortraitFeed: Boolean = true): List<BiliPaiNavKey> = resolveInitialBiliPaiBackStack(
        firstRoute = ScreenRoutes.Home.route,
        onboardingRequired = isRequired(),
        openPortraitFeedOnStartup = includeStartupPortraitFeed && openPortraitFeedOnStartup,
    )
    /** Stage/fsync outside Root/entry locks, mint the existing Store permit only inside the
     * genuine caller's current Root gate, then atomically publish all original ACK fields. */
    suspend fun acknowledge(owns: () -> Boolean, admit: ((() -> Unit) -> Boolean)) = withContext(Dispatchers.IO) {
        val caller = currentCoroutineContext()
        fun checkRequest() {
            caller.ensureActive()
            if (!owns()) throw CancellationException("Onboarding window owner retired")
        }
        fun acquirePermit(): DesktopPluginStore.OriginalPreferenceWritePermit {
            lateinit var permit: DesktopPluginStore.OriginalPreferenceWritePermit
            checkRequest()
            if (!admit { checkRequest(); permit = DesktopPluginStore.OriginalPreferenceWritePermit(store) })
                throw CancellationException("Onboarding preference admission rejected")
            return permit
        }
        store.updateOriginalFromSnapshot(APP_WELCOME_PREFS_NAME, ::checkRequest, ::acquirePermit) {
            Unit to mapOf(
                USER_AGREEMENT_ACK_KEY to JsonPrimitive(true),
                "first_launch_shown" to JsonPrimitive(true),
                RELEASE_DISCLAIMER_ACK_KEY to JsonPrimitive(true),
            )
        }
    }
}

internal val LocalDesktopOriginalOnboardingPreferences = staticCompositionLocalOf<DesktopOriginalOnboardingPreferences> {
    error("Onboarding requires the actual ReadyApp window preference binding")
}

/** Complete original UI. Android preferences/finishAffinity alone become existing Windows
 * Store admission and graceful Root exit. No automatic acceptance or permission simulation. */
@Composable internal fun DesktopOriginalOnboardingRootHost(
    routes: DesktopOriginalRootRouteAssembly,
    active: Boolean,
    onDisagree: () -> Unit,
) {
    val preferences = LocalDesktopOriginalOnboardingPreferences.current
    val scope = rememberCoroutineScope()
    val activeNow by rememberUpdatedState(active)
    val disagreeNow by rememberUpdatedState(onDisagree)
    val saveJob = remember(routes) { AtomicReference<Job?>(null) }
    var saving by remember(routes) { mutableStateOf(false) }
    var failure by remember(routes) { mutableStateOf<String?>(null) }
    val isCurrent = { activeNow && routes.owns() && routes.currentKey == BiliPaiNavKey.Onboarding }
    DisposableEffect(routes) { onDispose { saveJob.getAndSet(null)?.cancel() } }
    OnboardingScreen(
        onFinish = {
            if (!saving && isCurrent()) {
                saving = true
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        preferences.acknowledge(isCurrent) { action ->
                            var applied = false
                            val accepted = routes.root.entry.gate.commit {
                                if (isCurrent()) { action(); applied = true }
                            }
                            accepted && applied
                        }
                        ensureActive()
                        if (isCurrent() && !routes.completeOnboarding(preferences.openPortraitFeedOnStartup))
                            failure = "使用须知已保存，当前窗口未能进入首页，请重试。"
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (isCurrent()) failure = "使用须知确认未能保存，请重试。"
                    } finally {
                        saving = false
                    }
                }
                saveJob.set(job)
                job.invokeOnCompletion { saveJob.compareAndSet(job, null) }
                job.start()
            }
        },
        onDisagree = {
            if (isCurrent()) {
                saveJob.getAndSet(null)?.cancel()
                disagreeNow()
            }
        },
    )
    failure?.let { message ->
        AppAlertDialog(
            onDismissRequest = { failure = null },
            title = { Text("保存失败") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { failure = null }) { Text("返回重试") } },
        )
    }
}
