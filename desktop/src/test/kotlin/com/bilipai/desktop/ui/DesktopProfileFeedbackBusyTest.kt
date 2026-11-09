package com.bilipai.desktop.ui

import com.android.purebilibili.feature.home.UserState
import com.android.purebilibili.data.repository.DesktopOriginalProfileSplashProtocol
import com.android.purebilibili.feature.list.DesktopFavoriteEnvironment
import com.android.purebilibili.feature.profile.ProfileSpaceUiState
import com.android.purebilibili.feature.profile.ProfileUiState
import com.android.purebilibili.feature.profile.ProfileViewModel
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf

class DesktopProfileFeedbackBusyTest {
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java)
    ) { _, method, _ -> error("unexpected ${T::class.java.simpleName}.${method.name}") } as T

    @Test fun busyViewModelDoesNotClaimANewAvatarOperation() {
        // Retired scope keeps the real VM's background loads and all network work inert.
        val scope = CoroutineScope(Job().apply { cancel() })
        val favorite = DesktopFavoriteEnvironment(scope, unused(), null, null, unused(),
            { true }, { null }, { null }, {}, null, null, null, null, null, null)
        val preferences = object : DesktopProfilePreferences by unused<DesktopProfilePreferences>() {
            override fun getTripleJumpEnabled() = flowOf(false)
        }
        val environment = DesktopProfileEnvironment(scope, { true }, { it(); true },
            unused(), unused(), unused(), unused(), DesktopOriginalProfileSplashProtocol(unused(), unused()),
            unused(), favorite.favorite, favorite.pgc, { null }, unused(), preferences, unused(), unused(), unused())
        val vm = ProfileViewModel(environment)
        @Suppress("UNCHECKED_CAST")
        val state = ProfileViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
            .get(vm) as DesktopOwnedProfileState<ProfileUiState>
        val previousOrigin = Any()
        val busy = ProfileUiState.Success(UserState(mid = 42), space = ProfileSpaceUiState(
            isSavingSign = true, signSaveMessage = "已有结果", signSaveMessageOrigin = previousOrigin))
        state.value = busy
        var claimed = false
        vm.updateAccountAvatar("file:///unused") { claimed = true; Any() }
        assertFalse(claimed, "a rejected busy request must not replace the active operation")
        assertEquals(busy, vm.uiState.value)
    }
}
