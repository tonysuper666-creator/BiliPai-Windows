package com.bilipai.desktop.hardware

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppDialogAction
import com.android.purebilibili.core.ui.components.AppText

/** Playback and glass consumers use policy; this page only edits the shared pure-policy inputs. */
@Composable internal fun DesktopHardwareSettings(
    optimizer:DesktopHardwareOptimizer, onExport:()->Unit,
) {
    val state by optimizer.state.collectAsState()
    var advanced by remember {mutableStateOf(false)}
    val policy=state.policy
    val overrides=state.learning?.overrides ?: HardwareOverrides()
    val adapter=state.snapshot.adapters.firstOrNull {it.luid==policy.adapterLuid}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        AppText(if(state.busy) "正在为你的电脑自动优化…" else if(policy.optimized) "已为你的电脑自动优化" else "使用安全播放设置")
        AppText((adapter?.name ?: "显卡信息待确认")+when {
            policy.hdr -> "：已验证超分与 HDR"
            policy.enhancement!=VideoEnhancement.NONE -> "：已验证超分"
            state.learning?.selfTestReason=="deferred" -> "：空闲后继续优化"
            else -> "：优先流畅播放，增强将在播放时验证"
        })
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            AppDialogAction(onClick=onExport){AppText("导出诊断")}
            AppDialogAction(onClick={advanced=!advanced}){AppText(if(advanced) "收起高级设置" else "高级设置")}
        }
        if(advanced) {
            AppText(if(overrides.automatic) "自动设置" else "手动设置（不支持的选项会自动回退）")
            AppDialogAction(onClick=optimizer::restoreAutomatic){AppText("恢复自动")}
            AppDialogAction(onClick={
                val values=listOf<VideoCodec?>(null,VideoCodec.AV1,VideoCodec.HEVC,VideoCodec.AVC)
                optimizer.setOverrides(overrides.copy(automatic=false,preferredCodec=values[(values.indexOf(overrides.preferredCodec)+1)%values.size]))
            }){AppText("编码偏好：${overrides.preferredCodec?.name ?: "自动"}")}
            AppDialogAction(onClick={
                val values=listOf<Int?>(null,720,1080,2160)
                optimizer.setOverrides(overrides.copy(automatic=false,maxHeight=values[(values.indexOf(overrides.maxHeight)+1)%values.size]))
            }){AppText("清晰度上限：${overrides.maxHeight?.toString() ?: "自动"}")}
            AppDialogAction(onClick={optimizer.setOverrides(overrides.copy(automatic=false,
                enhancement=if(overrides.enhancement==VideoEnhancement.NONE)null else VideoEnhancement.NONE))}){
                AppText("超分：${if(overrides.enhancement==VideoEnhancement.NONE) "关闭" else "自动验证"}")
            }
            AppDialogAction(onClick={optimizer.setOverrides(overrides.copy(automatic=false,hdr=overrides.hdr==false))}){
                AppText("HDR 增强：${if(overrides.hdr==false) "关闭" else "自动验证"}")
            }
            AppDialogAction(onClick={optimizer.setOverrides(overrides.copy(automatic=false,fullGlass=overrides.fullGlass==false))}){
                AppText("界面模糊：${if(overrides.fullGlass==false) "减少" else "自动"}")
            }
            AppDialogAction(onClick={optimizer.setOverrides(overrides.copy(automatic=false,exclusiveAudio=!overrides.exclusiveAudio))}){
                AppText("独占音频：${if(!policy.exclusiveAllowed) "当前设备未确认支持" else if(overrides.exclusiveAudio) "请求启用" else "共享模式"}")
            }
        }
    }
}
