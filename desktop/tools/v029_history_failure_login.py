"""Counted FIRST-only Login callbacks over the complete selected original UI.
No request, account, job or producer owner is created. Favorites owns CommonList;
Message continues to be the sole producer of shared ListLoadError.
"""
import hashlib

SOURCE_INPUT_SHA256 = {'common': 'b536179831e1cd0ca6ea4f42b7184e8ad9249d9d75df498085a392103a45d3a3', 'loadError': '6fc1d252d9d1d02eff83cf1d9eabd6ff0a645c4876aab81fa225148119a31a32'}
RECIPES = {'common': [('    favoriteCollectionSharedElementRoute: FavoriteCollectionRoute? = null,\n    isCurrentPage: Boolean = true\n) {\n', '    favoriteCollectionSharedElementRoute: FavoriteCollectionRoute? = null,\n    isCurrentPage: Boolean = true,\n    onFirstReadAuthenticationRequired: ((ListUiState) -> (() -> Unit)?)? = null\n) {\n'), ('                                onRetry = { historyViewModel.retryHistory() },\n                                onRetryLoadMore = { historyViewModel.loadMore(retry = true) },\n', '                                onRetry = { historyViewModel.retryHistory() },\n                                onFirstReadAuthenticationRequired = onFirstReadAuthenticationRequired?.invoke(state),\n                                onRetryLoadMore = { historyViewModel.loadMore(retry = true) },\n'), ('    onRetry: (() -> Unit)? = null,\n    loadMoreError: String? = null,\n', '    onRetry: (() -> Unit)? = null,\n    onFirstReadAuthenticationRequired: (() -> Unit)? = null,\n    loadMoreError: String? = null,\n'), ('                AppButton(onClick = onRetry) {\n                    AppText("重试")\n                }\n            }\n        }\n    } else if (items.isEmpty() && loadMoreError != null && onRetryLoadMore != null) {\n', '                AppButton(onClick = onRetry) {\n                    AppText("重试")\n                }\n            }\n            onFirstReadAuthenticationRequired?.let { login ->\n                AppTextButton(onClick = login) { AppText("登录并返回") }\n            }\n        }\n    } else if (items.isEmpty() && loadMoreError != null && onRetryLoadMore != null) {\n'), ('                    error != null && onRetry != null -> ListLoadError(\n                        message = error,\n                        onRetry = onRetry,\n', '                    error != null && onRetry != null -> ListLoadError(\n                        message = error,\n                        onFirstReadAuthenticationRequired = onFirstReadAuthenticationRequired,\n                        onRetry = onRetry,\n'), ('                        ListLoadError(\n                            message = "刷新失败，已保留现有内容：$error",\n                            onRetry = onRetry,\n', '                        ListLoadError(\n                            message = "刷新失败，已保留现有内容：$error",\n                            onFirstReadAuthenticationRequired = onFirstReadAuthenticationRequired,\n                            onRetry = onRetry,\n')], 'loadError': [('    onRetry: () -> Unit,\n) {\n    Column(\n', '    onRetry: () -> Unit,\n) = ListLoadError(message, modifier, null, onRetry)\n\n/** Optional explicit read authentication; the original three-argument/trailing-lambda\n * entry stays intact for every Message and load-more caller. */\n@Composable\ninternal fun ListLoadError(\n    message: String,\n    modifier: Modifier = Modifier,\n    onFirstReadAuthenticationRequired: (() -> Unit)?,\n    onRetry: () -> Unit,\n) {\n    Column(\n'), ('        AppTextButton(onClick = onRetry) { AppText("重试") }\n', '        AppTextButton(onClick = onRetry) { AppText("重试") }\n        onFirstReadAuthenticationRequired?.let { login ->\n            AppTextButton(onClick = login) { AppText("登录并返回") }\n        }\n')]}

def apply_history_first_login_source(body, kind):
    original = body
    if kind not in RECIPES:
        raise ValueError("Unknown fixed History FIRST UI source")
    if hashlib.sha256(body.encode("utf8")).hexdigest() != SOURCE_INPUT_SHA256[kind]:
        raise ValueError("Complete selected History FIRST UI source changed")
    edits = []
    for before, after in RECIPES[kind]:
        if body.count(before) != 1:
            raise ValueError("History FIRST UI literal is not unique")
        offset = len(body[:body.index(before)].encode("utf8"))
        body = body.replace(before, after, 1)
        edits.append(dict(offset=offset, beforeUTF8=before, afterUTF8=after))
    restored = body.encode("utf8")
    for edit in reversed(edits):
        offset = edit["offset"]
        before, after = edit["beforeUTF8"].encode("utf8"), edit["afterUTF8"].encode("utf8")
        if restored[offset:offset + len(after)] != after:
            raise ValueError("History FIRST UI inverse span mismatch")
        restored = restored[:offset] + before + restored[offset + len(after):]
    if restored != original.encode("utf8"):
        raise ValueError("History FIRST UI full inverse mismatch")
    return body, dict(schemaVersion=1, offsetUnit="UTF-8-byte", edits=edits,
        originalSHA256LF=hashlib.sha256(original.encode("utf8")).hexdigest(),
        afterSHA256LF=hashlib.sha256(body.encode("utf8")).hexdigest(),
        fullActualAfterInverseExact=True, scope="FIRST original UI only; no load-more callback")
