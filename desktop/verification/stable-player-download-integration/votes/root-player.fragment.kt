// Root-owned merge snippet, not a full Shell replacement. Hoist this state with
// the Root ordinary retained source, outside popup/playerContent/PiP branches.
val commandVersion = player?.currentSourceVersion ?: 0L
val commandDetails = playing.details
val commandCid = commandDetails?.pages?.getOrNull(playing.currentPart)?.cid ?: 0L
val commandState = rememberDesktopVideoCommandVoteState(sessionEpoch, commandVersion,
    commandDetails?.bvid.orEmpty(), commandCid)
// At actual PlayerPanel(...), tail after viewPoints:
commandOverlay = if (initialized === player && danmaku != null &&
    (showVideo || section == DesktopSection.STORY) && commandDetails != null &&
    commandCid > 0 && initialized.ownsSourceVersion(commandVersion) &&
    playback.currentCastSource(commandVersion) != null && preferences.danmaku.enabled) ({
    val capturedEpoch = sessionEpoch
    val capturedInfo = commandDetails
    val capturedCid = commandCid
    val capturedVersion = commandVersion
    DesktopVideoCommandVoteContent(repository, initialized, capturedVersion,
        capturedInfo.bvid, capturedInfo.aid, capturedCid, danmaku, commandState,
        fontScale = preferences.danmaku.fontScale,
        hideInteractiveCommands = false, // Original default; original setting consumer still pending.
        stillOwned = {
            repository.sessionEpoch == capturedEpoch && initialized.ownsSourceVersion(capturedVersion) &&
                playback.state.value.details?.bvid == capturedInfo.bvid &&
                playback.state.value.details?.pages?.getOrNull(playback.state.value.currentPart)?.cid == capturedCid &&
                playback.currentCastSource(capturedVersion) != null
        },
        submitGrade = { operations, aid, cid, progress, gradeId, score ->
            operations.submitGradeDanmaku(aid,cid,progress,gradeId,score)
        }, onFeedback = { error = it })
}) else null
