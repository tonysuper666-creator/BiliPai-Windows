// Original source app/src/main/java/com/android/purebilibili/core/util/WindowSizeUtils.kt
// LF SHA256 cda5b8ae2ba18958739f2d51648683ed33150cec4c1db70b5c232005bd140c62
package com.android.purebilibili.core.util
import androidx.compose.ui.*
import androidx.compose.ui.unit.*
import androidx.compose.foundation.layout.*

fun Modifier.responsiveContentWidth(
    maxWidth: Dp = 800.dp,
    centerContent: Boolean = true
): Modifier {
    val alignment = if (centerContent) {
        Alignment.CenterHorizontally
    } else {
        Alignment.Start
    }
    // 四段各自负责一件事，顺序不能调：
    // 1. fillMaxWidth 让本节点占满父容器，居中才有剩余空间可用；
    // 2. wrapContentWidth 在这段空间里按 alignment 摆放被限宽的内容；
    // 3. widthIn 把内容的上限压到 maxWidth；
    // 4. 末尾再 fillMaxWidth 把内容钉死在「min(父宽, maxWidth)」。
    //    少了第 4 段，wrapContentWidth 传给内容的 minWidth 是 0，窄屏上原本铺满
    //    父宽的内容会退化成按内容裁剪——调用方紧跟其后的 background() 也会跟着缩。
    return this
        .fillMaxWidth()
        .wrapContentWidth(alignment)
        .widthIn(max = maxWidth)
        .fillMaxWidth()
}
