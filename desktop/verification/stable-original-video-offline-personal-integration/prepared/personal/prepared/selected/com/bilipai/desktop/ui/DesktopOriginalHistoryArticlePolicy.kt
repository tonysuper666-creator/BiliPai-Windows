package com.bilipai.desktop.ui
internal sealed interface ArticleNavigationTarget {
    data class NativeArticle(val articleId: Long) : ArticleNavigationTarget
    data class NativeDynamic(val dynamicId: String) : ArticleNavigationTarget
}

internal fun buildArticleWebUrl(articleId: Long): String? {
    if (articleId <= 0L) return null
    return "https://www.bilibili.com/read/cv$articleId"
}

internal fun resolveArticleNavigationTargetFromRedirect(
    articleId: Long,
    redirectUrl: String?
): ArticleNavigationTarget? {
    buildArticleWebUrl(articleId) ?: return null
    // /cv/ 链接无论 302 到 /cv/ 还是 /opus/，都是专栏；
    // 统一走专栏渲染器，避免 opus 重定向被误当成图文动态打开。
    return ArticleNavigationTarget.NativeArticle(articleId = articleId)
}
