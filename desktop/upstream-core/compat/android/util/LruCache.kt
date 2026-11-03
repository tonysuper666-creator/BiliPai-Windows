package android.util

/** android.util.LruCache over the androidx.collection implementation the desktop already ships (same eviction and counters). */
open class LruCache<K : Any, V : Any>(maxSize: Int) : androidx.collection.LruCache<K, V>(maxSize)
