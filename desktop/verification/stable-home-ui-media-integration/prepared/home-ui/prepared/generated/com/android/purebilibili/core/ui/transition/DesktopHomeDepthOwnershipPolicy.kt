// Original source app/src/main/java/com/android/purebilibili/core/ui/transition/VideoCardTransitionHostDepthLayer.kt
// LF SHA256 2e5da7bef26c7aa839775a2a123b5b14f528db1b1e999748dccc924a6db6d02a
package com.android.purebilibili.core.ui.transition
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

internal fun shouldInvalidateSnapshotOnSourceDispose(
    isHostOwnedSnapshot: Boolean,
): Boolean = !isHostOwnedSnapshot

/**
 * Host 会话层：源 dispose 时是否标 displayListStale。
 *
 * **false**：完整进详情后仍保留 OPENING 冻结帧，SettledHidden Host 可预热满糊；
 * 否则（旧 true）dispose→stale→Host 不画 → 只有开场中断返回有糊、看完再返回无糊。
 * 源页刷新改走 [VideoCardTransitionSnapshotLayerState.needsSourceRefresh]。
 * 若冻结帧在部分机型 dispose 后变空，BackPreview 源页重录会换上真实首页。
 */
