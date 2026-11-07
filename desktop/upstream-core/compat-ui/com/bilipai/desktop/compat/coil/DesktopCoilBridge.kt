package com.bilipai.desktop.compat.coil

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest

/**
 * Targets of the build-time rewrite rules (desktop/build.gradle.kts, desktopUpstreamRewriteRules, TON-1072): upstream
 * builds coil3 requests and reads the singleton loader with an Android Context, which on Android is coil's PlatformContext.
 * On the JVM coil has exactly one PlatformContext ([PlatformContext.INSTANCE]); the Android Context stand-in carries no
 * image state of its own, so every Context maps to it and requests reach the application image loader
 * (DesktopApplicationImageLoader) like desktop code's own requests.
 */
val android.content.Context.coilPlatformContext: PlatformContext get() = PlatformContext.INSTANCE

fun desktopImageRequestBuilder(context: android.content.Context): ImageRequest.Builder = ImageRequest.Builder(context.coilPlatformContext)
fun desktopImageRequestBuilder(context: PlatformContext): ImageRequest.Builder = ImageRequest.Builder(context)
fun desktopImageRequestBuilder(request: ImageRequest): ImageRequest.Builder = ImageRequest.Builder(request)
fun desktopImageRequestBuilder(request: ImageRequest, context: android.content.Context): ImageRequest.Builder =
    ImageRequest.Builder(request, context.coilPlatformContext)
fun desktopImageRequestBuilder(request: ImageRequest, context: PlatformContext): ImageRequest.Builder = ImageRequest.Builder(request, context)

fun desktopSingletonImageLoader(context: android.content.Context): ImageLoader = SingletonImageLoader.get(context.coilPlatformContext)
fun desktopSingletonImageLoader(context: PlatformContext): ImageLoader = SingletonImageLoader.get(context)
