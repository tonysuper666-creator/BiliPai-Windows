// Pending Root writer signature; not part of the executed candidate.
// Requires original ReplyCommentImageSpec import and Root writer to accept
// replaceExisting plus atomic withOwnedCommit; do not call old bool-only writer.
suspend fun saveCommentImage(spec: ReplyCommentImageSpec): Boolean = saveMutex.withLock {
    checkpoint()
    val target = selectTarget("BiliPai-comment-${System.currentTimeMillis()}.png", "image/png") ?: return@withLock false
    checkpoint()
    writeDesktopReplyCommentImage(spec, target.path, stillOwned = ::isOwned,
        replaceExisting = target.replaceExisting, withOwnedCommit = ::commitOwned)
}
