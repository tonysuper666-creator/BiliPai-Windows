package android.os

/** Constants only. SDK_INT stays below 37 so upstream AppDns keeps its JVM-capable resolver path. */
object Build {
    object VERSION { @JvmField val SDK_INT: Int = 36; @JvmField val RELEASE: String = System.getProperty("os.version") ?: "" }
    object VERSION_CODES { const val M = 23; const val N = 24; const val O = 26; const val P = 28; const val Q = 29; const val R = 30; const val S = 31; const val TIRAMISU = 33; const val UPSIDE_DOWN_CAKE = 34; const val VANILLA_ICE_CREAM = 35 }
    @JvmField val MANUFACTURER: String = "Microsoft"; @JvmField val MODEL: String = "Windows Desktop"; @JvmField val BRAND: String = "windows"
}
