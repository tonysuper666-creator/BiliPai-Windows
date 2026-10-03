package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.*
import com.android.purebilibili.data.repository.DesktopOriginalFavoriteRepository
import com.android.purebilibili.data.repository.DesktopOriginalFavoritePgc
import com.android.purebilibili.data.repository.DesktopOriginalProfileSplashProtocol
import com.bilipai.desktop.appearance.DesktopThemePrefs
import com.bilipai.desktop.appearance.DesktopTextClipboard
import com.bilipai.desktop.plugins.DesktopPluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Call
import java.awt.Window
import java.nio.file.Path

/** Construct once per actual retained Profile entry. All services below are mandatory
 * references to the Root's existing services, never replacement stores/network/media actors.
 * Dispose the retained entry scope outside Store locks; do not close global assets/chrome/theme. */
internal fun createDesktopOriginalWindowsProfileBinding(
    context:DesktopPluginContext,
    stateDirectory:Path,
    scope:CoroutineScope,
    owns:()->Boolean,
    commit:((()->Unit)->Boolean),
    api:BilibiliApi,
    spaceApi:SpaceApi,
    dynamicApi:DynamicApi,
    searchApi:SearchApi,
    splash:DesktopOriginalProfileSplashProtocol,
    authorizationApi:PassportApi,
    favorite:DesktopOriginalFavoriteRepository,
    bangumi:DesktopOriginalFavoritePgc,
    csrf:()->String?,
    accounts:DesktopProfileAccountPort,
    appearance:DesktopThemePrefs,
    configuration:StateFlow<DesktopProfileWindowConfiguration>,
    supportsRenderEffectBackedHaze:Boolean,
    applicationIconModel:Any,
    actualWindow:Window,
    ownedCallFactory:Call.Factory,
    metadata:DesktopProfileVideoWidth,
    assets:DesktopDynamicImageAssets,
    clipboard:DesktopTextClipboard,
    chrome:DesktopWindowsProfileChrome,
    media:DesktopProfileMedia,
    analytics:DesktopProfileAnalytics,
    feedback:(String)->Unit,
    diagnostic:(Throwable)->Unit,
):DesktopOriginalProfileBinding = DesktopOriginalProfileBinding(
    createDesktopOriginalWindowsProfileEnvironment(
        context, stateDirectory, scope, owns, commit, api, spaceApi, dynamicApi, searchApi, splash, authorizationApi, favorite, bangumi, csrf, accounts, appearance, configuration, supportsRenderEffectBackedHaze, applicationIconModel, actualWindow, ownedCallFactory, metadata, assets, clipboard, chrome, media, analytics, feedback, diagnostic
    )
)

internal fun createDesktopOriginalWindowsProfileEnvironment(
    context:DesktopPluginContext,
    stateDirectory:Path,
    scope:CoroutineScope,
    owns:()->Boolean,
    commit:((()->Unit)->Boolean),
    api:BilibiliApi,
    spaceApi:SpaceApi,
    dynamicApi:DynamicApi,
    searchApi:SearchApi,
    splash:DesktopOriginalProfileSplashProtocol,
    authorizationApi:PassportApi,
    favorite:DesktopOriginalFavoriteRepository,
    bangumi:DesktopOriginalFavoritePgc,
    csrf:()->String?,
    accounts:DesktopProfileAccountPort,
    appearance:DesktopThemePrefs,
    configuration:StateFlow<DesktopProfileWindowConfiguration>,
    supportsRenderEffectBackedHaze:Boolean,
    applicationIconModel:Any,
    actualWindow:Window,
    ownedCallFactory:Call.Factory,
    metadata:DesktopProfileVideoWidth,
    assets:DesktopDynamicImageAssets,
    clipboard:DesktopTextClipboard,
    chrome:DesktopWindowsProfileChrome,
    media:DesktopProfileMedia,
    analytics:DesktopProfileAnalytics,
    feedback:(String)->Unit,
    diagnostic:(Throwable)->Unit,
):DesktopProfileEnvironment {
    require(scope.coroutineContext[Job]!=null){"Actual retained Profile scope Job is required"}
    val files=DesktopProfileOwnedFiles(stateDirectory,owns,commit,metadata)
    val preferences=DesktopOriginalProfilePreferences(context.store,owns,commit) {mode->
        appearance.setThemeModeOwned(mode,owns,commit)
    }
    val platform=DesktopWindowsProfilePlatform(stateDirectory,configuration,supportsRenderEffectBackedHaze,
        applicationIconModel,actualWindow,scope,owns,commit,ownedCallFactory,files,assets,clipboard,chrome,feedback,diagnostic)
    return DesktopProfileEnvironment(scope,owns,commit,
        api,spaceApi,dynamicApi,searchApi,splash,authorizationApi,favorite,bangumi,csrf,accounts,preferences,platform,media,analytics)
}
