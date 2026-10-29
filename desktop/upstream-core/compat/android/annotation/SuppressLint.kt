package android.annotation

/** Lint suppression marker; no runtime meaning. */
@Retention(AnnotationRetention.SOURCE)
annotation class SuppressLint(vararg val value: String)
