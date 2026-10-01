// Original source app/src/main/java/com/android/purebilibili/feature/home/HomeDrawerLogoutPolicy.kt
// LF SHA256 198067b7a9e28013a55b279b45bff231aac75e97658034f8201a3607606c53ec
package com.android.purebilibili.feature.home



internal fun resolveHomeDrawerLogoutAction(
    onLogout: (() -> Unit)?,
    onProfileClick: () -> Unit
): () -> Unit {
    return onLogout ?: onProfileClick
}
