// Additional members inside the existing DesktopDynamicCardOperations; no replacement Ops file.
// Seed is the original DynamicItem captured by the owner's caller. No new seed cache.
// Null history callback explicitly leaves the original best-effort article history side effect unbound.
suspend fun getDynamicDetail(
    dynamicId:String,
    seedItem:DynamicItem?,
    onArticleViewed:(suspend (Long)->Unit)?=null,
):Result<DynamicItem> = result { read {
    val articleProtocol=com.android.purebilibili.data.repository.DesktopDynamicDetailArticleProtocol(
        web.create(ArticleApi::class.java), dynamic, ::signOriginalDetailParams, ::assertOwned, onArticleViewed)
    val protocol=com.android.purebilibili.data.repository.DesktopDynamicDetailProtocol(
        dynamic, ::signOriginalDetailParams,
        { id -> articleProtocol.getArticleDetail(id).getOrNull() }, ::assertOwned)
    protocol.getDynamicDetail(dynamicId,seedItem).getOrThrow()
} }
private suspend fun signOriginalDetailParams(params:Map<String,String>):Map<String,String> {
    coroutineContext.ensureActive();assertOwned()
    return try { repository.signWebParams(params).also { coroutineContext.ensureActive();assertOwned() }
    } catch(cancelled:CancellationException) { throw cancelled
    } catch(failure:Exception) { coroutineContext.ensureActive();assertOwned();params }
}
