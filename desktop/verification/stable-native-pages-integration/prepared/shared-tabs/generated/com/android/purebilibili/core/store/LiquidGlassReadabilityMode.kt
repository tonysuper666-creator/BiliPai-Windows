// OriginalSource: app/src/main/java/com/android/purebilibili/core/store/LiquidGlassReadabilityMode.kt
// OriginalSHA256: 414b70073eea199ee79184d7cc2bafd694c74090d55416f85b136ca3dc4860c1
package com.android.purebilibili.core.store

enum class LiquidGlassReadabilityMode(val value: Int, val label: String) {
    STABLE(0, "稳定内容色"),
    ADAPTIVE(1, "自动适配");

    companion object {
        fun fromValue(value: Int): LiquidGlassReadabilityMode =
            entries.find { it.value == value } ?: STABLE
    }
}
