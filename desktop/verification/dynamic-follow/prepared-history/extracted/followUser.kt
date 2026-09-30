    suspend fun followUser(mid: Long, follow: Boolean): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val csrf = TokenManager.csrfCache ?: ""
                com.android.purebilibili.core.util.Logger.d("ActionRepository", " followUser: mid=$mid, follow=$follow, csrf.length=${csrf.length}")
                if (csrf.isEmpty()) {
                    android.util.Log.e("ActionRepository", " CSRF token is empty!")
                    return@withContext Result.failure(Exception("请先登录"))
                }
                
                val act = if (follow) 1 else 2
                com.android.purebilibili.core.util.Logger.d("ActionRepository", " Calling modifyRelation...")
                val response = api.modifyRelation(fid = mid, act = act, csrf = csrf)
                com.android.purebilibili.core.util.Logger.d("ActionRepository", " Response: code=${response.code}, message=${response.message}")
                
                if (response.code == 0) {
                    _followStateChanges.tryEmit(FollowStateChange(mid = mid, isFollowing = follow))
                    Result.success(follow)
                } else {
                    Result.failure(Exception(response.message.ifEmpty { "操作失败: ${response.code}" }))
                }
            } catch (e: Exception) {
                android.util.Log.e("ActionRepository", "followUser failed", e)
                Result.failure(e)
            }
        }
    }
