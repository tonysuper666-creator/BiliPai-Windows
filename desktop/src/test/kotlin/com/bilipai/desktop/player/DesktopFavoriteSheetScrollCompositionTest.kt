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
class DesktopFavoriteSheetScrollCompositionTest {
    private fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)
    private class Presenter(override val ownerView: Any): AppGlassMenuPresenter {
        var active by mutableStateOf<AppGlassMenuRequest?>(null)
        override fun show(owner: Any, request: AppGlassMenuRequest) { active = request }
        override fun dismiss(owner: Any) { active = null }
    }

    @Test fun shortWindowKeepsFooterStationaryWhileFoldersScrollAndSavingDisablesActions() = runBlocking {
        for (density in listOf(1f, 2f)) {
            val saving = mutableStateOf(false)
            val calls = mutableListOf<String>()
            lateinit var presenter: Presenter
            val scope = AppGlassMenuScope(pressedItem={null}, register={_,_,_,_,_->}, unregister={}, dispatch={a,_->a()})
            val scene = ImageComposeScene((800*density).toInt(), (320*density).toInt(), Density(density)) {
                val window = LocalWindowInfo.current
                presenter = remember(window) { Presenter(window) }
                MaterialTheme {
                    CompositionLocalProvider(LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled=true), LocalAppGlassMenuPresenter provides presenter) {
                        FavoriteFolderSheet(
                            folders=(1L..40L).map { FavFolder(id=it, title="收藏夹 $it", media_count=4) },
                            selectedFolderIds=setOf(1L), isLoading=false, isSaving=saving.value,
                            onFolderToggle={calls += "folder:${it.id}"}, onSaveClick={calls += "save"}, onDismissRequest={calls += "dismiss"},
                        )
                        presenter.active?.let { request ->
                            Box(Modifier.width(request.maxWidth).height(requireNotNull(request.maxHeight))) { request.content(scope) }
                        }
                    }
                }
            }
            fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
            var frameTime = 0L
            fun frame() { frameTime += 16_666_667L; Snapshot.sendApplyNotifications(); scene.render(frameTime).close() }
            suspend fun settle() { repeat(12) { frame(); delay(12) } }
            fun label(text: String) = all().first { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == text } == true }
            fun button(text: String): SemanticsNode {
                var node: SemanticsNode? = label(text)
                while (node != null && !node.config.contains(SemanticsActions.OnClick)) node = node.parent
                return assertNotNull(node)
            }
            try {
                settle()
                assertEquals(288.dp, assertNotNull(presenter.active).maxHeight)
                val footer = label("已选择 1 个收藏夹").boundsInRoot
                val close = button("关闭").boundsInRoot
                val save = button("保存").boundsInRoot
                assertTrue(save.bottom <= 288*density)
                val list = all().first { it.config.contains(SemanticsActions.ScrollBy) }
                val rangeBefore = list.config[SemanticsProperties.VerticalScrollAxisRange].value()
                assertTrue(list.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 200*density))
                settle()
                val scrolled = all().first { it.config.contains(SemanticsActions.ScrollBy) }
                assertTrue(scrolled.config[SemanticsProperties.VerticalScrollAxisRange].value() > rangeBefore)
                assertEquals(footer, label("已选择 1 个收藏夹").boundsInRoot)
                assertEquals(save, button("保存").boundsInRoot)
                assertEquals(close, button("关闭").boundsInRoot)
                assertTrue(calls.isEmpty())
                saving.value = true
                settle()
                assertTrue(button("关闭").config.contains(SemanticsProperties.Disabled))
                val disabledButtons = all().filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Button && it.config.contains(SemanticsProperties.Disabled) }
                assertEquals(2, disabledButtons.size, "Only close and save are disabled during saving")
                val saveWhileBusy = disabledButtons.single { it.id != button("关闭").id }
                assertTrue(saveWhileBusy.boundsInRoot.bottom <= 288*density)
                assertTrue(calls.isEmpty())
                saving.value = false
                settle()
                assertFalse(button("保存").config.contains(SemanticsProperties.Disabled))
                button("保存").config[SemanticsActions.OnClick].action!!.invoke()
                settle()
                assertEquals(listOf("save"), calls)
            } finally { scene.close() }
        }
    }
}
