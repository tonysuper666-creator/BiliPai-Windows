// Original source app/src/main/java/com/android/purebilibili/feature/home/HomeAvatarActionPolicy.kt
// LF SHA256 2b8f4534e0d5a1fdb50e47c1ef238af34ac2a9e5fbb923afdc3c22f1263de1d6
package com.android.purebilibili.feature.home



enum class HomeAvatarAction {
    OPEN_DRAWER,
    OPEN_PROFILE,
    OPEN_LOGIN
}

fun resolveHomeAvatarAction(
    isLoggedIn: Boolean,
    isHomeDrawerEnabled: Boolean
): HomeAvatarAction {
    return when {
        isLoggedIn && isHomeDrawerEnabled -> HomeAvatarAction.OPEN_DRAWER
        isLoggedIn -> HomeAvatarAction.OPEN_PROFILE
        else -> HomeAvatarAction.OPEN_LOGIN
    }
}
