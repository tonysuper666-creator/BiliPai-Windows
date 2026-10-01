package com.bilipai.desktop.votefixture

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.DynamicApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.DesktopVideoGradeProtocol
import com.android.purebilibili.data.repository.DesktopDynamicVoteRepository
import com.android.purebilibili.feature.video.danmaku.*
import com.android.purebilibili.feature.video.ui.overlay.CommandDanmakuOverlayState
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/** Test-only Java API proxies. No sockets, account or request interceptor. */
internal suspend fun protocolProof(): List<String> {
    val proof = mutableListOf<String>()
    fun gate(label: String, value: Boolean) { check(value) { label }; proof += label }
    var calls = 0
    var fields: List<Any?> = emptyList()
    var response = SimpleApiResponse()
    var cancelled = false
    val api = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader, arrayOf(BilibiliApi::class.java)) { _, method, args ->
        check(method.name == "gradeDanmaku") { "Unexpected API call: ${method.name}" }
        calls++; fields = args!!.dropLast(1)
        if (cancelled) throw CancellationException("task-owned fake API cancellation")
        response
    } as BilibiliApi
    val grade = DesktopVideoGradeProtocol(api)
    gate("grade uses original aid/cid/progress/grade_id/grade_score/csrf fields",
        grade.submitGradeDanmaku(201, 301, 4_500, "991", 8, "fixture-csrf").isSuccess &&
            calls == 1 && fields == listOf(201L,301L,4500L,991L,8,"fixture-csrf"))
    val method = BilibiliApi::class.java.methods.single { it.name == "gradeDanmaku" }
    gate("grade endpoint remains original form POST rather than do_vote",
        method.getAnnotation(retrofit2.http.POST::class.java)?.value == "x/v2/dm/command/grade/post" &&
            method.getAnnotation(retrofit2.http.FormUrlEncoded::class.java) != null)
    val before = calls
    gate("missing CSRF and invalid grade ID stop before API invocation",
        grade.submitGradeDanmaku(201,301,4500,"991",8,"").exceptionOrNull()?.message == "请先登录" &&
            grade.submitGradeDanmaku(201,301,4500,"not-an-id",8,"fixture-csrf").exceptionOrNull()?.message == "缺少打分 ID" && calls == before)
    response = SimpleApiResponse(code=36705, message="untrusted server explanation")
    gate("original grade error mapper is retained",
        grade.submitGradeDanmaku(201,301,4500,"991",8,"fixture-csrf").exceptionOrNull()?.message == "当前账号等级不足，无法发送该弹幕")
    cancelled = true
    var cancellationEscaped = false
    try { grade.submitGradeDanmaku(201,301,4500,"991",8,"fixture-csrf") }
    catch (expected: CancellationException) { cancellationEscaped = true }
    gate("cancelled grade remains coroutine cancellation rather than UI failure", cancellationEscaped)

    // Standard voting must remain the existing original DynamicVoteRepository.
    var ordinaryFields: List<Any?> = emptyList()
    val dynamic = Proxy.newProxyInstance(DynamicApi::class.java.classLoader,arrayOf(DynamicApi::class.java)) { _, method, args ->
        check(method.name == "doVote") { "Unexpected standard vote API: ${method.name}" }
        ordinaryFields = args!!.dropLast(1)
        DynamicVoteInfoResponse(data=DynamicVoteInfoPayload(
            vote_info=DynamicVoteInfo(vote_id=70, options=listOf(DynamicVoteOption(opt_idx=11))), my_votes=listOf(11)))
    } as DynamicApi
    val vote = DesktopDynamicVoteRepository(dynamic,{"fixture-csrf"},{501L})
    val info = vote.submitVote(70,listOf(11)," 123456 ",true).getOrThrow()
    val body = ordinaryFields[1] as DynamicDoVoteRequest
    gate("standard vote uses existing do_vote request and exact opt_idx / actor / anonymous / dynamicId / CSRF",
        ordinaryFields[0] == "fixture-csrf" && body.vote_id == 70L && body.votes == listOf(11) &&
            body.voter_uid == 501L && body.status == 1 && body.op_bit == 0 && body.dynamic_id == 123456L &&
            body.csrf == "fixture-csrf" && body.csrf_token == "fixture-csrf" && info.my_votes == listOf(11))
    val options = listOf(VoteOption("ten-first","ten",10),VoteOption("two","two",2),
        VoteOption("ten-duplicate","duplicate",10),VoteOption("invalid","invalid",7))
    gate("original grade star resolver keeps legal score positions / first duplicate without inventing absent scores",
        resolveGradeStarOptions(options).map { it?.id } == listOf("two",null,null,null,"ten-first"))
    val state = CommandDanmakuOverlayState()
    gate("original command selection is single per id and dismissed commands cannot select",
        state.select("command-a",options.first()) && !state.select("command-a",options[1]) &&
            state.selection("command-a") == options.first() && run { state.dismiss("command-b"); !state.select("command-b",options[1]) })
    return proof
}
