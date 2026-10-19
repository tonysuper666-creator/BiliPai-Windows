package com.bilipai.desktop.plugins

import com.bilipai.desktop.update.DesktopUpdater

private const val UNPACKAGED_VERSION = "unpackaged-development"

private val packageVersionName: String by lazy {
    runCatching { DesktopUpdater.packagedVersion() }.getOrDefault(UNPACKAGED_VERSION)
}

/**
 * Windows binding for the Android BuildConfig.VERSION_NAME that the original SponsorBlock client request
 * sends as x-ext-version and in its User-Agent. The packaged build version is read once; an unpackaged
 * development run falls back to the same literal the diagnostics owner uses.
 */
internal fun desktopSponsorBlockVersionName(): String = packageVersionName
