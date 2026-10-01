// Integration fragment only: Root keeps the existing model/session/Window owners.
// Insert in the existing remember(operations) block in DesktopOriginalDynamicCardHost.
// This fragment is not a second Repository, Store, chooser or persistent state owner.
val assets = remember(operations) {
    val guard = repository.dynamicCacheSessionGuard
    // Actual DesktopSessionStore returns a local MID=0 owner when logged out.
    // Do not call FollowStateEvents.requireOwner() or add a mid > 0 filter here.
    val capturedOwner = requireNotNull(guard.dynamicCacheOwner())
    DesktopDynamicImageAssets(
        repository.httpClient,
        stillOwned = { operations.isOwned() && capturedOwner.epoch == operations.expectedEpoch },
        sessionGuard = guard,
        expectedOwner = capturedOwner,
    )
}

// If the account changes between operations creation and owner capture, the
// capturedOwner.epoch check makes this instance unavailable; it cannot submit
// an old operation under a newly captured owner. The final movement still runs
// under this exact owner in the actual Store monitor via Assets.commitOwned.
// Dispose by closing this existing Assets owner, then cancel/join owned save jobs
// when the Root lifecycle has a drain boundary. close() itself is nonblocking.

// Root comment PNG writer can use the same captured owner. Its final file move
// must occur inside this block; the writer must treat false as cancellation.
// The existing Assets.saveCommentImage fragment instead passes ::commitOwned,
// which also checks the Assets own close lock inside this actual Store lock.
val commentWithOwnedCommit: ((() -> Unit) -> Boolean) = { block ->
    guard.withCurrentDynamicCacheOwner(capturedOwner) {
        if (!operations.isOwned() || capturedOwner.epoch != operations.expectedEpoch) {
            throw kotlinx.coroutines.CancellationException("图片会话已切换，保存未提交")
        }
        block()
    }
}

// The last example assumes guard/capturedOwner are in the enclosing existing
// owner scope, not the remember-local scope above. It is an insertion template,
// not a standalone source file. External page bool retirement does not acquire
// the Store monitor, so it is checked ownership, not a strong page-close gate.
