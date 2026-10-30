package android.content.pm

/**
 * Desktop stand-in for android.content.pm.ApplicationInfo. [dataDir] is the app data directory the Context was built on.
 * [sourceDir] is the installed APK path on Android; the desktop has no APK, so it is null and callers must handle that
 * (the upstream APK-digest code already does: no source means no digest).
 */
class ApplicationInfo(@JvmField val dataDir: String, @JvmField val sourceDir: String?)
