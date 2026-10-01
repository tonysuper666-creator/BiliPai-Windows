// Original source app/src/main/java/com/android/purebilibili/feature/home/components/DrawerMotionBudgetPolicy.kt
// LF SHA256 799ecb2199e98131e551a1746ef47f3d2f2699fdab97c74c799abc42b346805b
package com.android.purebilibili.feature.home.components



internal enum class DrawerMotionBudget {
    FULL,
    REDUCED
}

internal fun resolveDrawerMotionBudget(
    isDrawerTransitionRunning: Boolean
): DrawerMotionBudget {
    return if (isDrawerTransitionRunning) DrawerMotionBudget.REDUCED else DrawerMotionBudget.FULL
}

internal fun shouldEnableDrawerBlur(
    blurActive: Boolean,
    budget: DrawerMotionBudget
): Boolean = blurActive

internal fun shouldForceLowDrawerBlurBudget(
    budget: DrawerMotionBudget
): Boolean = budget == DrawerMotionBudget.REDUCED
