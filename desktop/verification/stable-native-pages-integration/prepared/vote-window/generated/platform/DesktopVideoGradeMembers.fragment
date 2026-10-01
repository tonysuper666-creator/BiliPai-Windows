
// STABLE_VIDEO_VOTE_GRADE_MEMBERS
// Desktop command vote grade binding. Standard vote continues through submitVote.
suspend fun submitGradeDanmaku(aid: Long, cid: Long, progress: Long, gradeId: String, gradeScore: Int): Result<Unit> = result {
    mutate { csrf ->
        com.android.purebilibili.data.repository.DesktopVideoGradeProtocol(api)
            .submitGradeDanmaku(aid, cid, progress, gradeId, gradeScore, csrf).getOrThrow()
    }
}
