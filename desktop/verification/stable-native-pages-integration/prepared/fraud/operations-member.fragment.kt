
    // Desktop original fraud protocol binding; BGM owns records/status/policy.
    private val originalCommentFraud = com.android.purebilibili.data.repository.DesktopOriginalCommentFraudProtocol(
        api, guestWeb.callFactory(),
        { assertOwned(); repository.authCookies()["buvid3"] },
        { params -> assertOwned(); repository.signWebParams(params).also { coroutineContext.ensureActive(); assertOwned() } },
        { assertOwned(); repository.ensureSession(); assertOwned() },
        ::assertOwned,
    )
    suspend fun checkCommentStatus(aid: Long, rpid: Long, rootId: Long = 0, hasPictures: Boolean = false,
        sentAtSeconds: Long = 0, waitMs: Long = -1): Result<com.android.purebilibili.data.model.CommentFraudStatus> =
        result { read { originalCommentFraud.checkCommentStatus(aid, rpid, rootId, hasPictures, sentAtSeconds, waitMs).getOrThrow() } }
