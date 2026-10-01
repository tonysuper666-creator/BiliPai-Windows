// Original source app/src/main/java/com/android/purebilibili/feature/home/HomeFeedSkeletonVisualSpec.kt
// LF SHA256 80b1615d19dae58b74485132977d421a9e42629ec8ab24beb702575feeb6787e
package com.android.purebilibili.feature.home

import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import com.android.purebilibili.core.ui.AppShapes

internal fun resolveHomeSkeletonCoverShape(cornerRadius: Dp): Shape {
    return AppShapes.topRounded(cornerRadius)
}

internal fun resolveHomeSkeletonInfoShape(cornerRadius: Dp): Shape {
    return AppShapes.bottomRounded(cornerRadius)
}
