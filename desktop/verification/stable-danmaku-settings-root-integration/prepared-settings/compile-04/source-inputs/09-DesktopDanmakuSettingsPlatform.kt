package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.data.repository.DanmakuCloudFilterRule
import com.android.purebilibili.data.repository.DanmakuCloudFilterRules
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

internal data class DesktopDanmakuSettingsViewport(val screenWidthDp:Int,val screenHeightDp:Int)
internal val LocalDesktopDanmakuSettingsViewport=staticCompositionLocalOf<DesktopDanmakuSettingsViewport> {
    error("Danmaku settings require measured Root window constraints")
}
internal interface DesktopDanmakuCloudRuleActions {
    suspend fun getDanmakuCloudFilterRules():Result<DanmakuCloudFilterRules>
    suspend fun addDanmakuCloudFilterRule(type:Int,filter:String):Result<DanmakuCloudFilterRule>
    suspend fun deleteDanmakuCloudFilterRule(id:Long):Result<Unit>
}
/** Required existing Root owner/transport/chooser ports. No HTTP client, store or persistent list. */
internal interface DesktopDanmakuSettingsPlatform {
    val cloud:DesktopDanmakuCloudRuleActions
    fun isOwned():Boolean
    fun showFeedback(message:String)
    fun pickRuleFile(mimeTypes:Array<String>,onSelected:(String?)->Unit)
    /** Root's fileURI stream must check caller cancellation and the same owner during reads. */
    fun openRuleInput(fileUri:String):InputStream?
}
internal val LocalDesktopDanmakuSettingsPlatform=staticCompositionLocalOf<DesktopDanmakuSettingsPlatform> {
    error("Danmaku settings require the existing Root owned platform")
}
internal object DesktopDanmakuOpenRuleDocument
internal class DesktopDanmakuRuleImportLauncher(private val launchOwned:(Array<String>)->Unit) {
    fun launch(mimeTypes:Array<String>)=launchOwned(mimeTypes)
}
@Composable internal fun rememberDesktopDanmakuRuleImportLauncher(
    contract:DesktopDanmakuOpenRuleDocument,
    onResult:(String?)->Unit,
):DesktopDanmakuRuleImportLauncher {
    val platform=LocalDesktopDanmakuSettingsPlatform.current
    val latest by rememberUpdatedState(onResult)
    val alive=remember(platform){AtomicBoolean(true)}
    DisposableEffect(platform){onDispose{alive.set(false)}}
    return remember(platform,contract){DesktopDanmakuRuleImportLauncher { types ->
        if(alive.get()&&platform.isOwned())platform.pickRuleFile(types.copyOf()) { selected ->
            if(alive.get()&&platform.isOwned())latest(selected)
        }
    }}
}
