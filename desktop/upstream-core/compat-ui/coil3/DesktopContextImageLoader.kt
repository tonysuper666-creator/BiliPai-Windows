package coil3

/**
 * coil3's `PlatformContext.imageLoader` for upstream code that holds an Android Context (`import coil3.imageLoader`):
 * the application singleton loader, the same one [SingletonImageLoader.get] returns for the JVM's single PlatformContext.
 */
val android.content.Context.imageLoader: ImageLoader get() = SingletonImageLoader.get(PlatformContext.INSTANCE)
