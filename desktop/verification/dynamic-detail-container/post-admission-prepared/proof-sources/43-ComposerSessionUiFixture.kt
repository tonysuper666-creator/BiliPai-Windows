@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.DesktopDynamicCardSettings.DynamicDetailImageLayout
import com.android.purebilibili.core.theme.*
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.DesktopOriginalDynamicReplySession
import com.android.purebilibili.feature.home.components.*
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.*

fun main(args:Array<String>):Unit=runBlocking {
    val output=Path.of(args[0]);Files.createDirectories(output.parent)
    val json=Json{ignoreUnknownKeys=true;coerceInputValues=true}
    val item=json.decodeFromString<DynamicItem>("""{"id_str":"100","type":"DYNAMIC_TYPE_WORD","basic":{"comment_id_str":"100","comment_type":17},"modules":{"module_dynamic":{"desc":{"text":"Actual original detail subject"}}}}""")
    val root=Files.createTempDirectory("bilipai-liquid-owner-")
    val platform=LayoutPlatform(DesktopPluginContext(DesktopPluginStore(root)))
    val scope=CoroutineScope(coroutineContext+SupervisorJob())
    val requests=FixtureReplyRequests()
    requests.main={_,_,_->json.decodeFromString<ReplyData>("""{"cursor":{"all_count":1,"is_end":true},"replies":[{"rpid":701,"oid":100,"mid":88,"like":8,"content":{"message":"Reply 701"},"member":{"mid":"88","uname":"Raw member"}}]}""")}
    val session=DesktopOriginalDynamicReplySession("100",scope,requests,{item})
    val scene=ImageComposeScene(width=500,height=800,coroutineContext=coroutineContext);val ui=ComposerScene(scene)
    try {
        scene.setContent {
            DesktopAppearanceTheme(DesktopThemeSettings(hapticFeedbackEnabled=false)) {
                CompositionLocalProvider(LocalDesktopCommentBindings provides platform,LocalDesktopDetailForeground provides true,
                    LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled=true),
                    LocalLiquidGlassRenderConfig provides LiquidGlassRenderConfig()) {
                    DesktopDetailWindow {
                        DesktopOriginalDynamicDetailLayout("100",DesktopOriginalDynamicDetailUiState.Success(item),session,
                            DynamicDetailImageLayout.EXPANDED,true,88L,onBack={},onRetry={},onUserClick={},card={_,_->
                                AppSurface(Modifier.fillMaxWidth().height(120.dp),color=Color(0xff357abd)){AppText("Existing CardHost synthetic seam")}
                            })
                    }
                }
            }
        }
        ui.await("same Main session renders original full floating composer"){session.comments.value.isNotEmpty()&&ui.nodes().any{it.config.getOrNull(SemanticsActions.SetText)!=null}}
        check(ui.field().boundsInRoot.width<=360.1f&&ui.field().boundsInRoot.width>=300f)
        session.startCommentReply(session.comments.value.first()) // Synthetic target selection, original owner and reducer.
        ui.await("original reply target auto focuses actual composer"){ui.field().config.getOrNull(SemanticsProperties.Focused)==true}
        ui.input("  Targeted actual owner  ");ui.send()
        ui.await("actual Main request receives original owned subject"){requests.calls.any{it.startsWith("post:")}}
        val request=requests.calls.single{it.startsWith("post:")}
        check(request=="post:100:17:701:701:Targeted actual owner"){"Reply target lost across desktop coroutine admission: $request"}
        check(session.commentReplyTarget.value==null&&ui.value().isEmpty())
        ui.screenshot(output.resolveSibling("same-owner-full-liquid-layout.png"))
        Files.writeString(output,buildJsonObject {put("passed",true);put("originalRootAndParent",true);put("sameOriginalReplySessionSchema",true);put("sessionClassCodeSource",session.javaClass.protectionDomain.codeSource.location.toString());put("rawMainReplySession",session.javaClass.protectionDomain.codeSource.location.toString().contains("main-product-snapshot-01/main-kotlin.jar"));put("preparedSessionAdmissionOverride",session.javaClass.protectionDomain.codeSource.location.toString().contains("post-admission-delta"))
            put("fullLayoutCallsFullComposer",true);put("actualFloatingWidth360",true);put("pointerPairs",ui.pointers);put("actualImeActions",ui.imeActions)
            put("sameRequest",request);put("targetSelectionIsSynthetic",true);put("existingCardHostIsSyntheticSeam",true);put("preparedComposerRendererLayoutOnly",true);put("MainConsumerAcceptance",false);put("HTTP",false);put("HWND",false)}.toString())
    } finally {scene.close();session.close();scope.cancel()}
}
