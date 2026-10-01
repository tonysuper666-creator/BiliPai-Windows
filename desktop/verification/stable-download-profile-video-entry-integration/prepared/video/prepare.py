from pathlib import Path
import hashlib,json,subprocess
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];CANDIDATE=MAIN.parent/'BiliPai-v023';SNAP=MAIN/'desktop/.local/stable-product-snapshot-45'
def wide(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
sha=lambda b:hashlib.sha256(b).hexdigest()
def write(p,b):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b)
def save(name,v):write(LANE/name,(json.dumps(v,ensure_ascii=False,indent=2)+'\n').encode())
assert sha(read(SNAP/'manifest.json'))=='61b8233a766542366bf6a72868434e9effbf2f0c6458ae0f77326168a50f3fec'
inputs={r['path']:r for r in json.loads(read(SNAP/'manifest.json'))['inputs']}
controller='desktop/src/main/kotlin/com/bilipai/desktop/DesktopPlaybackController.kt';host='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopVideoCommentRoot.kt'
oldProposal=json.loads(read(MAIN/'desktop/.local/stable-home-root-leaf-route-review/controller-ms-port.proposal.json'))
controllerHunks=oldProposal['hunks']+[{ 'before':'    fun open(bvid: String) = open(VideoCard(bvid, "", "", "", 0, 0))', 'after':'''    fun open(bvid: String) = open(VideoCard(bvid, "", "", "", 0, 0))

    /** Root decides whether route effects are compatible. True only means this existing
     * owned BVID/CID/source was retained; its queue, paused state and source are unchanged.
     * Source/openId remain the Root navigation entry's identity, never a native-source alias. */
    fun openVideoDetail(card: VideoCard, resumePositionMs: Long, keepMatchingSource: Boolean): Boolean {
        val context = current?.takeIf(::owns)
        val seekAvailable = resumePositionMs <= 0L || player?.state?.value?.let { it.videoCodec != null || it.audioCodec != null } == true
        val ready = !state.value.opening && !state.value.recovering && seekAvailable
        val retainedSource = player?.currentSourceSnapshot()?.takeIf { it.sourceVersion == context?.sourceVersion }?.source
        val same = context != null && card.preferredCid > 0L &&
            context.details.bvid == card.bvid && context.details.pages[context.index].cid == card.preferredCid
        val retained = if (keepMatchingSource && ready && same && retainedSource != null) {
            try { publication.admit(retainedSource, { context != null && owns(context) }) { true } }
            catch (_: CancellationException) { false }
        } else false
        if (retained) {
            if (resumePositionMs > 0L) seekTo(resumePositionMs / 1000.0)
            return true
        }
        open(card, resumePositionMs)
        return false
    }''' }]
hostHunks=[
dict(before='    auxiliaryContent: (@Composable (DesktopOriginalCommentRootOwner) -> Unit)? = null,',after='''    auxiliaryContent: (@Composable (DesktopOriginalCommentRootOwner) -> Unit)? = null,
    initialCommentRootRpid: Long = 0L,
    initialCommentTargetRpid: Long = 0L,
    commentRouteOpenId: Long = 0L,'''),
dict(before='    DesktopOriginalCommentRootBindings(repository, community, fraud, info.aid, stillOwned, modifier) { owner ->',after='''    // Same sole owner: a distinct routed comment/openId disposes the old composition scope,
    // preview, detail route and every owned request rather than transferring a stale thread.
    val routeIdentity = Triple(info.aid, commentRouteOpenId, initialCommentRootRpid to initialCommentTargetRpid)
    DesktopOriginalCommentRootBindings(repository, community, fraud, routeIdentity, stillOwned, modifier) { owner ->'''),
dict(before='        var detailRoute by remember(owner) { mutableStateOf<DesktopVideoCommentDetailRoute?>(null) }',after='''        var detailRoute by remember(owner) { mutableStateOf<DesktopVideoCommentDetailRoute?>(null) }
        var hasHandledCommentRootFromRoute by remember(owner) { mutableStateOf(false) }'''),
dict(before='''        LaunchedEffect(owner, info.aid, info.owner.mid, info.stat.reply, preferredSort) {
            if (owned()) viewModel.init(info.aid, info.owner.mid,
                CommentSortMode.fromApiMode(preferredSort), info.stat.reply, commentType = 1)
        }''',after='''        LaunchedEffect(owner, info.aid, info.owner.mid, info.stat.reply, preferredSort) {
            if (owned()) {
                viewModel.init(info.aid, info.owner.mid,
                    CommentSortMode.fromApiMode(preferredSort), info.stat.reply, commentType = 1)
                if (initialCommentRootRpid > 0L && !hasHandledCommentRootFromRoute) {
                    val openStarted = viewModel.openSubReplyFromRoute(
                        rootReplyId = initialCommentRootRpid,
                        targetReplyId = initialCommentTargetRpid
                    )
                    if (openStarted) hasHandledCommentRootFromRoute = true
                }
            }
        }
        LaunchedEffect(owner, initialCommentRootRpid, initialCommentTargetRpid,
            commentState.replies, commentState.isRepliesLoading, subReplyState.visible) {
            if (owned() && initialCommentRootRpid > 0L && !hasHandledCommentRootFromRoute && !subReplyState.visible) {
                val rootReply = commentState.replies.firstOrNull { it.rpid == initialCommentRootRpid }
                if (rootReply != null) {
                    viewModel.openSubReply(rootReply, initialCommentTargetRpid)
                    hasHandledCommentRootFromRoute = true
                } else if (!commentState.isRepliesLoading) {
                    val openStarted = viewModel.openSubReplyFromRoute(
                        rootReplyId = initialCommentRootRpid,
                        targetReplyId = initialCommentTargetRpid
                    )
                    if (openStarted) hasHandledCommentRootFromRoute = true
                }
            }
        }'''),
]
changes=[]
for rel,hunks in [(controller,controllerHunks),(host,hostHunks)]:
    raw=read(CANDIDATE/rel);assert sha(raw)==inputs[rel]['sha256Bytes'],(rel,sha(raw),inputs[rel]['sha256Bytes'])
    write(LANE/'source-baselines'/rel,raw);text=raw.decode('utf-8').replace('\r\n','\n')
    for h in hunks:assert text.count(h['before'])==1,(rel,h['before']);text=text.replace(h['before'],h['after'],1)
    inverse=text
    for h in reversed(hunks):assert inverse.count(h['after'])==1;inverse=inverse.replace(h['after'],h['before'],1)
    assert inverse==raw.decode('utf-8').replace('\r\n','\n')
    write(LANE/'proof-only'/rel,text.encode());changes.append(dict(path=rel,baseSha256Bytes=sha(raw),baseSha256Lf=sha(inverse.encode()),candidateSha256Lf=sha(text.encode()),hunks=hunks,reverseNormalizedByteEqual=True))
save('local-hunks.json',dict(baseActualSnapshot=45,existingFamilies=2,changes=changes,wholeFileInstallationForbidden=True))
original='app/src/main/java/com/android/purebilibili/feature/video/screen/VideoDetailScreenStateHolder.kt'
blob=subprocess.run(['git','show','3d5d19a2f994daccd0e2f8b5f522b6d82f43d589:'+original],cwd=CANDIDATE,capture_output=True,check=True).stdout
write(LANE/'original-stable'/original,blob)
save('source-contract.json',dict(originalPath=original,originalSha256Bytes=sha(blob),originalCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',originalCommentAnchors=[[754,758],[1136,1159],[3085,3106]],originalRouteOpenIdType='Long',signatures=['open(card:VideoCard,resumePositionMs:Long)','openVideoDetail(card:VideoCard,resumePositionMs:Long,keepMatchingSource:Boolean):Boolean','DesktopVideoCommentRootHost(...,initialCommentRootRpid:Long=0L,initialCommentTargetRpid:Long=0L,commentRouteOpenId:Long=0L)'],requestOwnership='Same Controller generation/account/source+existing final publication; same sole CommentRootBindings owner contains aid/openId/root/target and retains existing epoch/Job guards',requiredRootInputs=['Card.preferredCid is the real original CID; zero is an unspecified CID and never falsely matches an already playing part','keepMatchingSource must come from actual compatible route effects; the Controller does not guess fullscreen/audio/portrait flags','sourceRoute/openId remain in the existing typed navigation entry and are not native ownership tokens','Root chooses the comments tab for a positive comment root using original route behavior; Host only opens the actual thread','Caller same-epoch nav-entry owns/cancel guards must wrap deferred Root calls; no stale route may call this fresh Controller'],boundaries=['No new player/store/client/DTO/cache','No original full player action-row, portrait-fullscreen renderer, or Story/navigation window proof','No actual Root UI or native DLL playback accepted here']))
print(json.dumps(dict(prepared=True,existingFamilies=2,hunks=sum(len(r['hunks'])for r in changes))))
