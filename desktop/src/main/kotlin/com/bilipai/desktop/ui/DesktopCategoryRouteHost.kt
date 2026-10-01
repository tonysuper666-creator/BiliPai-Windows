package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.android.purebilibili.feature.category.CategoryScreen
import com.android.purebilibili.feature.category.CategoryViewModel

/** Root retains this owner until category exit/replacement, including when video covers it. */
internal class DesktopCategoryRouteOwner(
    val name: String,
    private val environment: DesktopCategoryEnvironment,
) : AutoCloseable {
    val tid: Int get() = environment.tid
    val viewModel = CategoryViewModel(environment)
    fun commit(action: () -> Unit) = viewModel.commitNavigation(action)
    override fun close() = viewModel.close()
}

@Composable
internal fun DesktopCategoryRouteHost(
    owner: DesktopCategoryRouteOwner,
    onBack: () -> Unit,
    onVideoClick: (String, Long, String, Boolean) -> Unit,
    isReturningFromVideoDetail: Boolean,
    isQuickReturningFromVideoDetail: Boolean,
) {
    key(owner) {
        CategoryScreen(owner.tid, owner.name,
            onBack = { owner.commit(onBack) },
            onVideoClick = { bvid, cid, picture, vertical -> owner.commit { onVideoClick(bvid, cid, picture, vertical) } },
            isReturningFromVideoDetail = isReturningFromVideoDetail,
            isQuickReturningFromVideoDetail = isQuickReturningFromVideoDetail,
            viewModel = owner.viewModel)
    }
}
