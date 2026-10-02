from pathlib import Path
import hashlib,os,sys
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent
def wide(p):
 s=os.path.abspath(str(p));prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix)else prefix+s)
old=P.parent/'stable-original-bangumi-player-native-owner-root-parity/BangumiNativeOwnerProof.kt'
body=wide(old).read_text(encoding='utf8')
body=body.replace('import kotlinx.coroutines.*','import kotlinx.coroutines.*\nimport com.bilipai.desktop.data.reportDesktopPlaybackHeartbeat\nimport com.android.purebilibili.navigation3.BiliPaiNavKey')
anchor='    println("PASS $checks assertions; actual original Store/native bookkeeping only; Root/HTTP/native ACK/frame/account acceptance=false")'
extra='''    val typed = BiliPaiNavKey.BangumiPlayer(seasonId = 77, epId = 88, resumePositionMs = 9_999,
        isCourse = true, preferredAid = 123)
    checkThat("all original typed PUGV entry fields stay factual", typed.seasonId == 77L && typed.epId == 88L &&
        typed.resumePositionMs == 9_999L && typed.isCourse && typed.preferredAid == 123L)
    checkThat("PGC heartbeat without BVID is allowed only with actual episode+season ids", shouldSendBangumiPlaybackHeartbeat(true,"",9,12_345,88,77))
    checkThat("incomplete PGC heartbeat metadata is rejected", !shouldSendBangumiPlaybackHeartbeat(true,"",9,12_345,88,0))
    checkThat("missing CID remains a failure", !shouldSendBangumiPlaybackHeartbeat(true,"",0,12_345,88,77))
    checkThat("paused ordinary periodic heartbeat stays gated", !shouldSendBangumiPlaybackHeartbeat(false,"BVfactual",9,12_345,88,77))
    val sponsor = SponsorSegment(segment = listOf(1f,5f), UUID = "synthetic-local", category = "sponsor")
    checkThat("original SponsorBlock lower boundary is inclusive", desktopOriginalBangumiFindSponsorSegment(listOf(sponsor),1_000) === sponsor)
    checkThat("original SponsorBlock final half-second is excluded", desktopOriginalBangumiFindSponsorSegment(listOf(sponsor),4_500) == null)
    checkThat("original SponsorBlock preceding milliseconds are retained", desktopOriginalBangumiFindSponsorSegment(listOf(sponsor),4_499) === sponsor)
    checkThat("invalid Windows portrait inset remains zero", resolveBangumiPortraitPlayerContainerTopPaddingDp(Float.NaN) == 0f)
    checkThat("Windows fullscreen never requests physical orientation", resolveBangumiToggleOrientationTarget(false,false,true) == null)
    runBlocking {
        var epoch = 11L
        var mid = 123L
        var sends = 0
        var notifications = 0
        var sent: Map<String,String>? = null
        suspend fun report(expected: Long = 11, privacy: Boolean = false, csrf: String = "synthetic-local-csrf") =
            reportDesktopPlaybackHeartbeat({ privacy },expected,{ epoch },{ mid },{ csrf },
                "",9,12,12,1_000,0,88,77,4,10,onReported = { notifications++ }) { fields ->
                sends++;sent=fields;-101 // No successful network response is fabricated.
            }
        checkThat("real reporter keeps server denial as failure", !report())
        checkThat("PUGV final fields preserve type4/course/old episode position without invented BVID", sent?.let {
            it["type"] == "4" && it["sub_type"] == "10" && it["epid"] == "88" && it["sid"] == "77" &&
                it["cid"] == "9" && it["played_time"] == "12" && "bvid" !in it && "aid" !in it
        } == true)
        checkThat("denied final heartbeat never refreshes history", notifications == 0)
        epoch = 12
        checkThat("account switch before final dispatch cancels old report", !report() && sends == 1)
        epoch = 11;mid = 0
        checkThat("logged out final report is rejected without transport", !report() && sends == 1)
        mid = 123
        checkThat("missing primary CSRF rejects final report", !report(csrf="") && sends == 1)
        checkThat("original privacy policy suppresses transport and refresh", report(privacy=true) && sends == 1 && notifications == 0)
        var cancellationPropagated = false
        try {
            reportDesktopPlaybackHeartbeat({false},11,{epoch},{mid},{"synthetic-local-csrf"},
                "BVfactual",9,12,12,1_000,epid=88,sid=77,videoType=4) { throw CancellationException("actual caller cancelled") }
        } catch (_: CancellationException) { cancellationPropagated = true }
        checkThat("final report propagates real caller cancellation", cancellationPropagated)
    }
'''+anchor
assert body.count(anchor)==1;body=body.replace(anchor,extra)
sourceAnchor='        checkThat("cancelled initial request cannot execute even one queued native command", !initial.admit { error("Cancelled command ran") })'
sourceAfter=sourceAnchor+'''
        val refreshedToken = store.beginLoadRequest(request).requestToken
        val refreshed = nativeOwner.publish(request, physical, player.currentSourceVersion, requestJob) {
            store.state.value.currentLoadRequestToken == refreshedToken
        }
        checkThat("same episode actual requested publication replacement retains exact request/receipt",
            refreshed !== accepted && refreshed.request == accepted.request &&
                refreshed.nativeSource.source.authorizationReceipt == accepted.nativeSource.source.authorizationReceipt &&
                nativeOwner.current() === refreshed && player.currentSourceVersion == 2L)
        var actualPosition: Long? = null
        checkThat("retired accepted object cannot supply progress; current same-episode actual source can",
            !nativeOwner.admitPlaybackDispatch(accepted) { error("Old source progress dispatched") } &&
                nativeOwner.admitPlaybackDispatch(refreshed) { actualPosition = player.state.value.positionSeconds.times(1000).toLong() } &&
                actualPosition != null && !player.state.value.ready && !player.state.value.firstVideoFrameReady)
'''
assert body.count(sourceAnchor)==1;body=body.replace(sourceAnchor,sourceAfter)
wide(P/'BangumiPlayerClosureProof.kt').write_text(body,encoding='utf8',newline='\n')
print('Prepared original native-bookkeeping + PUGV metadata/retirement failure/epoch/privacy closure fixture')
