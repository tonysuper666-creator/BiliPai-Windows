    private fun applyAuthorUnfollow(authorMid: Long) {
        if (authorMid <= 0L) return
        cachedFollowings = cachedFollowings.filterNot { it.mid == authorMid }
        cachedLiveRooms = cachedLiveRooms.filterNot { it.uid == authorMid }
        _uiState.value = resolveDynamicStateAfterAuthorUnfollow(
            currentState = _uiState.value,
            authorMid = authorMid
        )
        if (_selectedUserId.value == authorMid) {
            selectUser(null)
        }
        _followedUsers.value = resolveFollowedUsersAfterAuthorUnfollow(
            users = _followedUsers.value,
            authorMid = authorMid
        )
        rebuildFollowedUsers()
        saveDynamicCache(_uiState.value.timelinePage("all").items)
    }
