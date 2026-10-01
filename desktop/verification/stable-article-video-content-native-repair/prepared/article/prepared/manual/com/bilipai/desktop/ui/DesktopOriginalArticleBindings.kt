package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.*
import com.android.purebilibili.data.repository.ArticleDetailUiModel
import com.android.purebilibili.data.repository.DesktopDynamicDetailArticleProtocol
import com.android.purebilibili.feature.list.DesktopFavoriteEnvironment
import com.bilipai.desktop.data.DesktopRepository
import kotlinx.coroutines.*

/** Required view of the sole original Article protocol and existing account request graph. */
class DesktopOriginalArticleBindings internal constructor(
    private val request: suspend (Long) -> Result<ArticleDetailUiModel>,
    private val owned: () -> Boolean,
) {
    suspend fun load(articleId: Long): Result<ArticleDetailUiModel> {
        currentCoroutineContext().ensureActive()
        if (!owned()) throw CancellationException("Article entry retired")
        val result = request(articleId)
        currentCoroutineContext().ensureActive()
        if (!owned()) throw CancellationException("Article entry retired")
        return result
    }
}

internal fun desktopOriginalArticleBindings(
    repository: DesktopRepository,
    gate: DesktopHomeRetainedGate,
    scope: CoroutineScope,
    privacyModeEnabled: () -> Boolean,
): DesktopOriginalArticleBindings {
    val owned = { scope.isActive && gate.owns() }
    fun assertOwned() { if (!owned()) throw CancellationException("Article entry retired") }
    val api = repository.ownedHomeService(BilibiliApi::class.java, "https://api.bilibili.com/", gate.epoch, owned)
    val article = repository.ownedHomeService(ArticleApi::class.java, "https://api.bilibili.com/", gate.epoch, owned)
    val dynamic = repository.ownedHomeService(DynamicApi::class.java, "https://api.bilibili.com/", gate.epoch, owned)
    // Only the installed original Article-history method is consumed here. Other list APIs
    // and list data are unmounted, rather than creating an unused History ViewModel.
    val history = DesktopFavoriteEnvironment(scope, api, null, null, null, owned,
        { repository.ownedHomeCookie("bili_jct", gate.epoch, owned) }, { assertOwned(); gate.mid }, {},
        null, null, { assertOwned(); privacyModeEnabled() }, null, null, null).history
    val protocol = DesktopDynamicDetailArticleProtocol(article, dynamic, { params ->
        assertOwned()
        val keys = repository.homeWbiKeys(gate.epoch, owned, api).getOrNull()
        assertOwned()
        if (keys == null) params else WbiUtils.sign(params, keys.first, keys.second)
    }, ::assertOwned, { articleId -> history.reportArticleView(articleId).getOrThrow() })
    return DesktopOriginalArticleBindings(protocol::getArticleUiDetail, owned)
}
