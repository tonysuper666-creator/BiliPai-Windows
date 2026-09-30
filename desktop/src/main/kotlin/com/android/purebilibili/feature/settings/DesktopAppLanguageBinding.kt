package com.android.purebilibili.feature.settings

import com.bilipai.desktop.appearance.DesktopLanguageRuntime

/** Desktop counterpart of Android applyAppLanguage; resources are supplied by DesktopAppearanceTheme. */
internal fun applyAppLanguage(appLanguage: AppLanguage) = DesktopLanguageRuntime.apply(appLanguage)
