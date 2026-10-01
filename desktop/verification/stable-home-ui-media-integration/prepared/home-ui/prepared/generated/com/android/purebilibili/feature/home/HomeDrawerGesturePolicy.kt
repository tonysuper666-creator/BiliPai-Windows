// Original source app/src/main/java/com/android/purebilibili/feature/home/HomeDrawerGesturePolicy.kt
// LF SHA256 4a5533da5e7aece565dea5326a1216759cb9aee616d5b1b06462aee1fe892357
package com.android.purebilibili.feature.home

import androidx.compose.material3.DrawerValue

internal fun shouldEnableHomeDrawerGestures(
    currentValue: DrawerValue,
    targetValue: DrawerValue,
): Boolean {
    return currentValue == DrawerValue.Open || targetValue == DrawerValue.Open
}
