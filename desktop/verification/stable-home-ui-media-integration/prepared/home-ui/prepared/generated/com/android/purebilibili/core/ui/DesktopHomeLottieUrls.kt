// Original source app/src/main/java/com/android/purebilibili/core/ui/LottieComponents.kt
// LF SHA256 d6725e7276bc02e559bcf692daa602bf9ff4c9a4ecf5beea3ed3e94c953dc728
package com.android.purebilibili.core.ui
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*

object LottieUrls {
    //  通用状态动画
    const val SUCCESS = "https://assets4.lottiefiles.com/packages/lf20_jbrw3hcz.json"
    const val ERROR = "https://assets1.lottiefiles.com/packages/lf20_cr9slsdh.json"
    const val EMPTY = "https://raw.githubusercontent.com/DrKLO/Telegram/master/TMessagesProj/src/main/res/raw/utyan_empty2.json"

    //  新手引导页面动画
    const val THEME_COLORS = "https://assets5.lottiefiles.com/packages/lf20_jtbfg2nb.json"  // 彩虹渐变
    const val VIDEO_PLAY = "https://assets8.lottiefiles.com/packages/lf20_khzniaya.json"  // 播放按钮
}

/**
 *  通用 Lottie 动画组件
 */
