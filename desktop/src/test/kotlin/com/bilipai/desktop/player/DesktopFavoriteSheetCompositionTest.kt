package com.bilipai.desktop.player

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import com.android.purebilibili.core.ui.AppThemeConfig
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.data.model.response.FavFolder
import com.android.purebilibili.feature.video.ui.components.FavoriteFolderSheet
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DesktopFavoriteSheetCompositionTest {
    private fun nodes(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::nodes)
    private class Presenter(override val ownerView:Any):AppGlassMenuPresenter {
        var active by mutableStateOf<AppGlassMenuRequest?>(null)
        override fun show(owner:Any,request:AppGlassMenuRequest){active=request}
        override fun dismiss(owner:Any){active=null}
    }
    @Test fun realWindowGeometryAndFolderCallbacksSurvivePortraitAndLandscape()=runBlocking {
        for(density in listOf(1f,2f)) for(size in listOf(IntSize(400,800),IntSize(800,400))) {
            val selected=mutableStateOf(emptySet<Long>())
            val saving=mutableStateOf(false)
            val calls=mutableListOf<String>()
            lateinit var presenter:Presenter
            val menuScope=AppGlassMenuScope(pressedItem={null},register={_,_,_,_,_->},unregister={},dispatch={a,_->a()})
            val scene=ImageComposeScene((size.width*density).toInt(),(size.height*density).toInt(),Density(density)) {
                val window = LocalWindowInfo.current
                presenter=remember(window) { Presenter(window) }
                MaterialTheme {
                    CompositionLocalProvider(LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled=true),LocalAppGlassMenuPresenter provides presenter) {
                        FavoriteFolderSheet(
                            folders=listOf(FavFolder(id=101,title="真实收藏夹",media_count=4)),
                            selectedFolderIds=selected.value,isLoading=false,isSaving=saving.value,
                            onFolderToggle={calls+="toggle:${it.id}";selected.value=setOf(it.id)},
                            onSaveClick={calls+="save"},onDismissRequest={calls+="dismiss"},
                        )
                        presenter.active?.let {request->
                            Box(Modifier.width(minOf(request.maxWidth.value,size.width.toFloat()).dp).height(requireNotNull(request.maxHeight))) {
                                request.content(menuScope)
                            }
                        }
                    }
                }
            }
            fun all()=scene.semanticsOwners.flatMap {nodes(it.unmergedRootSemanticsNode)}
            fun frame(){repeat(4){Snapshot.sendApplyNotifications();scene.render().close()}}
            suspend fun ready(){withTimeout(5000){while(presenter.active==null){frame();yield()}};frame()}
            fun click(label:String){
                var node:SemanticsNode?=all().first {it.config.getOrNull(SemanticsProperties.Text)?.any {t->t.text==label}==true}
                while(node!=null&&!node.config.contains(SemanticsActions.OnClick))node=node.parent
                assertNotNull(node).config[SemanticsActions.OnClick].action!!.invoke();frame()
            }
            try {
                frame();ready()
                val request=assertNotNull(presenter.active)
                assertEquals(AppGlassMenuPlacement.CENTER,request.placement)
                assertEquals(if(size.width>size.height)360.dp else 420.dp,request.maxWidth)
                assertEquals(if(size.width>size.height)368.dp else 576.dp,request.maxHeight)
                assertTrue(calls.isEmpty())
                click("真实收藏夹");assertEquals(listOf("toggle:101"),calls)
                click("保存");assertEquals(listOf("toggle:101","save"),calls)
                if(size.width>size.height){click("关闭");assertEquals(listOf("toggle:101","save","dismiss"),calls)}
                else {
                    assertNotNull(presenter.active).onDismissRequest()
                    withTimeout(5000) { while(calls.size<3){frame();yield()} }
                    assertEquals(listOf("toggle:101","save","dismiss"),calls)
                }
            } finally {scene.close()}
        }
    }
}
