package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.bangumi.*
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.playback.session.PlaybackSessionStore
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.player.*
import kotlinx.coroutines.*
import java.nio.file.Path

private var checks = 0
private fun checkThat(name: String, value: Boolean) { check(value) { name }; checks++; println("PASS $name") }
private fun rejects(name: String, action: () -> Unit) {
    checkThat(name, runCatching(action).exceptionOrNull() is IllegalArgumentException)
}
private fun cancelled(name: String, action: () -> Unit) {
    checkThat(name, runCatching(action).exceptionOrNull() is CancellationException)
}
private fun state(detail: BangumiDetail, episode: BangumiEpisode, data: BangumiVideoInfo,
    video: String, audio: String? = null) = BangumiPlayerState.Success(detail, episode, 0, video, audio,
    data.quality, data.acceptQuality.orEmpty(), data.acceptDescription.orEmpty(), cachedDash = data.dash,
    cachedPlayData = data)

fun main(args: Array<String>) {
    val episode = BangumiEpisode(id = 701, aid = 0, bvid = "", cid = 0,
        title = "第一集", longTitle = "真实分集标题", duration = 44_000, cover = "https://cover.invalid/episode.png")
    val detail = BangumiDetail(seasonId = 33, title = "真实番剧", evaluate = "原始简介", seasonType = 1,
        stat = BangumiStat(views = 9_999, likes = 5_555), episodes = listOf(episode))
    val first = "https://v.example.invalid/1.mp4?signature=original"
    val second = "https://v.example.invalid/2.mp4?signature=original"
    val backup = "https://backup.example.invalid/2.mp4?signature=original"
    val data = BangumiVideoInfo(quality = 64, durl = listOf(
        Durl(order = 1, length = 12_345, url = first),
        Durl(order = 2, length = 31_655, url = "", backupUrl = listOf("", second, backup))), timelength = 44_000)
    val referer = "https://www.bilibili.com/bangumi/play/ep701"
    val plan = desktopOriginalBangumiNativePlan(detail, episode, data, first, null,
        listOf(first, second), referer, null, 8_000, false)
    checkThat("all original DURL entries and primary-or-backup order retained", plan.segments.map { it.url } == listOf(first, second))
    checkThat("original milliseconds converted to exact continuous segment seconds", plan.segments.map { it.durationSeconds } == listOf(12.345, 31.655))
    val native = plan.nativeSource()
    checkThat("same native EDL contains both physical segments", native.nativeLoadUrl.startsWith("edl://") && native.nativeLoadUrl.contains(first) && native.nativeLoadUrl.contains(second))
    checkThat("original seek and pause intent retained", native.startPositionSeconds == 8.0 && native.startPaused)
    checkThat("original episode Referer retained", native.referer == referer)
    val tracks = plan.byteTracks(native)
    checkThat("same-cache progressive plan has one track per segment", tracks.map { it.url } == listOf(first, second))
    checkThat("signed progressive backup URL retained as exact alias", backup in tracks[1].urls)
    checkThat("same native headers used by all progressive cache tracks", tracks.all { it.headers["Referer"] == referer })
    val retainedProgressive = plan.retainedSource(native.copy(cookieHeader = "fixture-local-value"), first, null)
    checkThat("retained preparation keeps complete multi-DURL and exact private headers", retainedProgressive.progressiveSegments ==
        plan.segments && retainedProgressive.cookieHeader == "fixture-local-value" && retainedProgressive.referer == referer)
    checkThat("retained multi-DURL creates all original same-cache tracks", plan.byteTracks(retainedProgressive).map { it.url } == listOf(first, second))
    rejects("retained preparation cannot replace episode Referer") { plan.retainedSource(native.copy(referer = "https://www.bilibili.com"), first, null) }
    rejects("retained preparation rejects foreign signed selection") { plan.retainedSource(native, "https://other.invalid/unrelated.mp4", null) }
    val metadata = plan.payload(state(detail, episode, data, first))
    checkThat("legitimate missing BVID and aid/cid stay absent", metadata.info.bvid.isEmpty() && metadata.info.aid == 0L && metadata.info.cid == 0L)
    checkThat("missing uploader and dimensions stay absent", metadata.info.owner.mid == 0L && metadata.info.owner.name.isEmpty() && metadata.info.dimension == null)
    checkThat("season aggregate statistics never fabricated as episode statistics", metadata.info.stat == Stat())
    checkThat("actual episode title cover and raw response duration projected", metadata.info.title == plan.title && metadata.info.pic == episode.cover && metadata.duration == 44_000L)
    rejects("wrong episode Referer rejected") { desktopOriginalBangumiNativePlan(detail, episode, data, first, null,
        listOf(first, second), "https://www.bilibili.com", null, 0, true) }
    rejects("incomplete progressive source rejected") { desktopOriginalBangumiNativePlan(detail, episode, data, first, null,
        null, referer, null, 0, true) }
    rejects("reordered progressive source rejected") { desktopOriginalBangumiNativePlan(detail, episode, data, second, null,
        listOf(second, first), referer, null, 0, true) }
    rejects("foreign signed URL rejected") { desktopOriginalBangumiNativePlan(detail, episode, data,
        "https://other.invalid/unrelated.mp4", null, listOf(first, second), referer, null, 0, true) }
    rejects("metadata from another raw response rejected") { plan.payload(state(detail, episode, data.copy(quality = 32), first)) }
    val course = detail.copy(seasonType = 10, upInfo = PugvUpInfo(mid = 42, uname = "真实老师", avatar = "https://cover.invalid/up.png"))
    val coursePlan = desktopOriginalBangumiNativePlan(course, episode, data, first, null, listOf(first, second),
        "https://www.bilibili.com/cheese/play/ep701", null, 0, true)
    checkThat("course dedicated Referer and true uploader projected", coursePlan.referer.contains("/cheese/") &&
        coursePlan.payload(state(course, episode, data, first)).info.owner == Owner(mid = 42, name = "真实老师", face = "https://cover.invalid/up.png"))

    val video = DashVideo(id = 80, baseUrl = "https://v.example.invalid/80.m4s", backupUrl = listOf("https://backup.example.invalid/80.m4s"),
        codecs = "avc1.640028", bandwidth = 300_000, width = 1920, height = 1080,
        segmentBase = SegmentBase(initialization = "0-1000", indexRange = "1001-2000"))
    val audio = DashAudio(id = 30280, baseUrl = "https://a.example.invalid/audio.m4s", backupUrl = listOf("https://backup.example.invalid/audio.m4s"),
        codecs = "mp4a.40.2", bandwidth = 100_000, segmentBase = SegmentBase(initialization = "0-500", indexRange = "501-1000"))
    val dash = Dash(duration = 44, video = listOf(video), audio = listOf(audio))
    val dashData = BangumiVideoInfo(quality = 80, dash = dash, timelength = 44_000, acceptQuality = listOf(80,64), acceptDescription = listOf("蓝光","高清"))
    val selectedVideo = video.backupUrl!!.single(); val selectedAudio = audio.backupUrl!!.single()
    val manifest = checkNotNull(buildBangumiDashManifest(dash, video, selectedVideo, audio, selectedAudio, 44_000))
    val dashPlan = desktopOriginalBangumiNativePlan(detail, episode, dashData, selectedVideo, selectedAudio, null, referer, manifest, 0, true)
    checkThat("complete original generated MPD retained byte-for-byte", dashPlan.manifest == manifest)
    checkThat("actual selected signed mirrors used by accepted adaptive preparation", dashPlan.adaptiveSource()?.let {
        it.videoTracks.single().getValidUrl() == selectedVideo && it.audioTracks.single().getValidUrl() == selectedAudio
    } == true)
    checkThat("raw signed primary addresses retained as adaptive aliases", video.getValidUrl() in dashPlan.adaptiveSource()!!.videoTracks.single().backupUrl.orEmpty())
    checkThat("original cached raw Dash DTO remains unchanged", dashData.dash!!.video.single().getValidUrl() == video.getValidUrl())
    val dashTracks = dashPlan.byteTracks(dashPlan.nativeSource())
    checkThat("selected DASH mirrors and raw signed primary URLs reach original cache-key grouping", listOf(selectedVideo,
        video.getValidUrl(), selectedAudio, audio.getValidUrl()).all { url -> dashTracks.any { url in it.urls } })
    rejects("foreign DASH selection cannot adopt current receipt") { desktopOriginalBangumiNativePlan(detail, episode, dashData,
        "https://other.invalid/other.m4s", selectedAudio, null, referer, null, 0, true) }

    // The actual single native owner and MPV object run WITHOUT a surface/session.
    // Only real requested-source/version bookkeeping occurs. No loadfile ACK,
    // decoder, native frame, cache success or network response is manufactured.
    val sessions = DesktopSessionStore(Path.of(args.single()).resolve("isolated-session.json"), persistent = false)
    val repository = DesktopRepository(sessions)
    val authorization = repository.capturePlaybackAuthorization(repository.sessionEpoch) { true }
    val physical = native.copy(authorizationReceipt = authorization.receipt)
    val player = MpvPlayer(useNullAudioOutput = true)
    var presenterCurrent = true
    val store = PlaybackSessionStore()
    val request = PlaybackRequest.create(episode.bvid, episode.aid, episode.cid)
    val token = store.beginLoadRequest(request).requestToken
    val nativeOwner = DesktopOriginalVideoNativeOwner(player, DesktopRepositoryPlaybackPublication(repository),
        { repository.sessionEpoch }, { false }, { true }, { action -> action(); true }, {},
        { _, _ -> throw CancellationException("Fixture performs no byte-cache admission") })
    val requestJob = Job()
    try {
        val accepted = nativeOwner.publish(request, physical, player.currentSourceVersion, requestJob) {
            store.state.value.currentLoadRequestToken == token
        }
        checkThat("actual native owner allocated one real requested source version", player.currentSourceVersion == 1L && nativeOwner.current() === accepted)
        checkThat("unmounted actual MPV has no fabricated loadfile ACK or frame", !player.state.value.ready && !player.state.value.firstVideoFrameReady && player.state.value.videoCodec == null)
        var preparations = 0
        fun preparation(value: DesktopOriginalVideoAcceptedPublication): DesktopOriginalVideoMediaPort {
            preparations++
            val raw = value.nativeSource.source
            return DesktopOriginalVideoMediaIntentView(
                legacy = { v,a,_ -> raw.copy(videoUrl = v, audioUrl = a, nativePublication = null, nativeTransport = null) },
                adaptive = { _,_ -> error("Fixture does not prepare adaptive transport") },
                progressive = { raw.copy(videoUrl = it, audioUrl = null, nativePublication = null, nativeTransport = null) },
                publish = { _,_ -> error("Only actual NativeOwner may publish") })
        }
        presenterCurrent = false
        cancelled("retired PGC rejected before accepted-media delegate preparation") { nativeOwner.acceptedMedia(::preparation) { presenterCurrent } }
        checkThat("rejected presenter created no delegate or native replacement", preparations == 0 && nativeOwner.current() === accepted)
        presenterCurrent = true
        val retained = nativeOwner.acceptedMedia(::preparation) { presenterCurrent }
        val before = player.currentSourceSnapshot()
        cancelled("PGC takeover between preparation and final publication rejected") {
            retained.withPlaybackIntent(5_000, false) {
                val source = retained.prepareLegacyDash(first, null, emptyMap())
                presenterCurrent = false
                retained.accept(source)
            }
        }
        checkThat("final rejection left actual MPV requested snapshot unchanged", player.currentSourceSnapshot()?.let {
            it.sourceVersion == before!!.sourceVersion && it.source == before.source
        } == true && nativeOwner.current() === accepted)
        store.beginLoadRequest(PlaybackRequest.create("BVrealNext", 21, 22))
        cancelled("superseded actual original Store token cannot publish") {
            nativeOwner.publish(request, physical, player.currentSourceVersion, requestJob) { store.state.value.currentLoadRequestToken == token }
        }
        checkThat("superseded request did not allocate another native source", player.currentSourceVersion == 1L)
        val cancelledJob = Job().apply { cancel() }
        cancelled("cancelled actual caller job cannot publish") { nativeOwner.publish(request, physical, player.currentSourceVersion, cancelledJob) { true } }
        checkThat("cancelled caller leaves native source and source version unchanged", nativeOwner.current() === accepted && player.currentSourceVersion == 1L)
        val initial = DesktopOriginalVideoInitialPublication(DesktopRepositoryPlaybackPublication(repository), physical,
            cancelledJob, { true }, { true }, { action -> action(); true })
        checkThat("cancelled initial request cannot execute even one queued native command", !initial.admit { error("Cancelled command ran") })
    } finally { requestJob.cancel(); nativeOwner.close(); player.close() }
    println("PASS $checks assertions; actual original Store/native bookkeeping only; Root/HTTP/native ACK/frame/account acceptance=false")
}
