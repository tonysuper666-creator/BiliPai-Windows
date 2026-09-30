    private fun observeFollowStateChanges() {
        viewModelScope.launch {
            ActionRepository.followStateChanges.collect { change ->
                if (change.isFollowing) {
                    followingsFullyLoaded = false
                    lastFollowingsLoadMs = 0L
                    requestFollowingsRefreshIfStale()
                } else {
                    applyAuthorUnfollow(change.mid)
                }
            }
        }
    }
