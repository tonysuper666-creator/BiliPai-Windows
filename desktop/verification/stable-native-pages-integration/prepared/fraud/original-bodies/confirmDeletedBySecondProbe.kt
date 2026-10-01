private suspend fun confirmDeletedBySecondProbe(aid: Long, rpid: Long): Boolean {
    delay(DELETE_CONFIRM_RETRY_DELAY_MS)
    val guestRootUrl = "https://api.bilibili.com/x/v2/reply/main?oid=$aid&type=1&mode=2&next=0&ps=20"
    val guestRetryJson = rawCurlGuest(guestRootUrl)
    val rpidPattern = Regex(""""rpid":\s*${rpid}""")
    if (guestRetryJson == null || rpidPattern.containsMatchIn(guestRetryJson)) {
        return false
    }
    val authRetryProbe = probeCommentPresenceBySeekRpid(
        apiClient = api,
        aid = aid,
        targetRpid = rpid
    )
    return authRetryProbe.requestSucceeded && !authRetryProbe.found
}
