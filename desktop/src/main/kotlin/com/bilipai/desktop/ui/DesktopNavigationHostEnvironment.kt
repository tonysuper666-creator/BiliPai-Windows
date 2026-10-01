package com.bilipai.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.defaultViewModelProviderFactory
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.navigationevent.NavigationEventDispatcherOwner
import com.bilipai.desktop.plugins.DesktopPluginContext

/** The actual Miuix per-entry store/factory/saved-state extras remain the authority. */
internal class DesktopNavigationEntryViewModelPlatform {
    fun patchCreationExtras(
        owner: ViewModelStoreOwner,
        originalExtras: CreationExtras,
    ): CreationExtras {
        // Android's Application factory key has no Windows counterpart. Preserve all real
        // Miuix SavedStateRegistryOwner/ViewModelStoreOwner keys; do not inject an Application shim.
        owner.viewModelStore
        return MutableCreationExtras(originalExtras)
    }

    fun defaultFactory(owner: ViewModelStoreOwner): ViewModelProvider.Factory =
        owner.defaultViewModelProviderFactory
}

/** All values are Root's existing actual window/global store owners, never a route-local store. */
internal class DesktopNavigationHostEnvironment(
    val context: DesktopPluginContext,
    val rootLifecycleOwner: LifecycleOwner,
    val rootViewModelStoreOwner: ViewModelStoreOwner,
    val rootNavigationEventOwner: NavigationEventDispatcherOwner,
    val cornerRadiusQuery: () -> Dp?,
    val isCurrent: () -> Boolean,
) {
    val entryViewModels = DesktopNavigationEntryViewModelPlatform()
}

internal val LocalDesktopNavigationHostEnvironment = staticCompositionLocalOf<DesktopNavigationHostEnvironment> {
    error("The original navigation host requires Root's window lifecycle, ViewModel owner and global store")
}
