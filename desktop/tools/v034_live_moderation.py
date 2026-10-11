"""Fixed complete live moderation policies/requests into the existing Windows owner."""
from __future__ import annotations
import hashlib, json, textwrap
from pathlib import Path
from v030_live_stream import safe

COMMIT = 'b2091b1de636e499df80af7e700796b4f52b7f92'
PINS = {'policy': {'path': 'app/src/main/java/com/android/purebilibili/feature/live/LiveDanmakuFilterPolicy.kt', 'rawBytes': 557, 'rawSha256': 'b8209ccf31596fcf89dfe38024e6d3d9bf65e797577cefac05ac14cd440e2d4e', 'gitBlob': '63c1329382c851114aa0e0a12fcd81a1f5a6da48', 'archive': 'desktop/upstream-slices/v034-live-moderation/app/src/main/java/com/android/purebilibili/feature/live/LiveDanmakuFilterPolicy.kt', 'sourceUrl': 'https://raw.githubusercontent.com/jay3-yy/BiliPai/b2091b1de636e499df80af7e700796b4f52b7f92/app/src/main/java/com/android/purebilibili/feature/live/LiveDanmakuFilterPolicy.kt'}, 'models': {'path': 'app/src/main/java/com/android/purebilibili/data/repository/LiveInteractionModels.kt', 'rawBytes': 5600, 'rawSha256': '3a546bd71fd792388ce7d969051a9bff4f0db2c3e301e9499e2f13d806247871', 'gitBlob': '1521fb76d73ed05d0597d4076837b133d2ba40de', 'archive': 'desktop/upstream-slices/v034-live-moderation/app/src/main/java/com/android/purebilibili/data/repository/LiveInteractionModels.kt', 'sourceUrl': 'https://raw.githubusercontent.com/jay3-yy/BiliPai/b2091b1de636e499df80af7e700796b4f52b7f92/app/src/main/java/com/android/purebilibili/data/repository/LiveInteractionModels.kt'}, 'realtime': {'path': 'app/src/main/java/com/android/purebilibili/feature/live/LiveRealtimeMessagePolicy.kt', 'rawBytes': 16689, 'rawSha256': '002016da3e66429de054b92581e375db865b67d07fd6e1c6791b1d939696762d', 'gitBlob': '12d8955935a452379ec86dc9fe47a9376e2d1dbc', 'archive': 'desktop/upstream-slices/v034-live-moderation/app/src/main/java/com/android/purebilibili/feature/live/LiveRealtimeMessagePolicy.kt', 'sourceUrl': 'https://raw.githubusercontent.com/jay3-yy/BiliPai/b2091b1de636e499df80af7e700796b4f52b7f92/app/src/main/java/com/android/purebilibili/feature/live/LiveRealtimeMessagePolicy.kt'}, 'api': {'path': 'core-data/src/main/java/com/android/purebilibili/core/network/ApiClient.kt', 'rawBytes': 151172, 'rawSha256': '1ed0da47deb801a5a2f6f2a63e6bf2e9e8674bb861e573b45956f1bd5ed2febb', 'gitBlob': '76c1f1dcc3e99579b0684bc6f5d8d2e248697570', 'archive': 'desktop/upstream-slices/v034-live-moderation/core-data/src/main/java/com/android/purebilibili/core/network/ApiClient.kt', 'sourceUrl': 'https://raw.githubusercontent.com/jay3-yy/BiliPai/b2091b1de636e499df80af7e700796b4f52b7f92/core-data/src/main/java/com/android/purebilibili/core/network/ApiClient.kt'}, 'repo': {'path': 'app/src/main/java/com/android/purebilibili/data/repository/LiveRepository.kt', 'rawBytes': 56832, 'rawSha256': '554ca3c32522c88148144c4221879701657aab296519ea0da4162661039d4857', 'gitBlob': '0c69a011fa66f0c9829b066aa4fa149a865c996c', 'archive': 'desktop/upstream-slices/v034-live-vote/app/src/main/java/com/android/purebilibili/data/repository/LiveRepository.kt'}, 'vm': {'path': 'app/src/main/java/com/android/purebilibili/feature/live/LivePlayerViewModel.kt', 'rawBytes': 63055, 'rawSha256': 'e86a019aa43d9d5aca595ff6073bb0cf50d00436142bb5a0bda50655b2507e74', 'gitBlob': '605d846a97f2faaf5af468d5757d180caf1ba098', 'archive': 'desktop/upstream-slices/v034-live-vote/app/src/main/java/com/android/purebilibili/feature/live/LivePlayerViewModel.kt'}}
RECIPES = [{'name': 'getLiveShieldInfo', 'mappings': [('suspend fun getLiveShieldInfo(roomId: Long): Result<LiveShieldInfo>', 'internal suspend fun requestOriginalDesktopLiveShieldInfo(canonicalRoomId: Long, api: BilibiliApi, signer: suspend (Map<String, String>) -> Map<String, String>): Result<LiveShieldInfo>'), ('val realRoomId = resolveRealRoomId(roomId)', 'val realRoomId = canonicalRoomId'), ('signWithWbi(', 'signer(')], 'originalSha256LF': '24430c505eaf88841b70746074dbb312f6385117c1e26cfe5d734d9dfa23cabe', 'adaptedSha256LF': 'a0b66542e2e56bcc99c527a961f6bcaf3c0674fee94cb6d89ada61612905f6a6'}, {'name': 'setLiveSilentRule', 'mappings': [('suspend fun setLiveSilentRule(\n    type: String,\n    level: Int\n): Result<Boolean>', 'internal suspend fun requestOriginalDesktopLiveSilentRule(type: String, level: Int, api: BilibiliApi, currentCsrf: String): Result<Boolean>'), ('val csrf = com.android.purebilibili.core.store.TokenManager.csrfCache ?: ""', 'val csrf = currentCsrf')], 'originalSha256LF': 'b44f5e2312e0eecee90313a31c16830b8605b0f2b6edd54ebf0046478431cf5f', 'adaptedSha256LF': '413ed57865531f49ce302a2f7e7eea6b1abd20cd33c5e44fd9cebc7325dfdfd9'}, {'name': 'addLiveShieldKeyword', 'mappings': [('suspend fun addLiveShieldKeyword(keyword: String): Result<Boolean>', 'internal suspend fun requestOriginalDesktopAddLiveShieldKeyword(keyword: String, api: BilibiliApi, currentCsrf: String): Result<Boolean>'), ('val csrf = com.android.purebilibili.core.store.TokenManager.csrfCache ?: ""', 'val csrf = currentCsrf')], 'originalSha256LF': '77dbf61404fa635f110d8d0dcba94caa0f7aaa57e7bd615f306bf3c2cd3f313a', 'adaptedSha256LF': '8e67622dfe62dee6ed04c7929f7cfc1a656aca2ee83b4d0019d5d1a4415cc389'}, {'name': 'deleteLiveShieldKeyword', 'mappings': [('suspend fun deleteLiveShieldKeyword(keyword: String): Result<Boolean>', 'internal suspend fun requestOriginalDesktopDeleteLiveShieldKeyword(keyword: String, api: BilibiliApi, currentCsrf: String): Result<Boolean>'), ('val csrf = com.android.purebilibili.core.store.TokenManager.csrfCache ?: ""', 'val csrf = currentCsrf')], 'originalSha256LF': '919f7cccadd4da0d27b81471d528c973acef6ebec7de9b74c9e7d1783b413fce', 'adaptedSha256LF': '12f2a47dfb578d833477e58ba99f1123e31d516f281475d958193857bfca4b2e'}, {'name': 'setLiveShieldUser', 'mappings': [('suspend fun setLiveShieldUser(\n    roomId: Long,\n    uid: Long,\n    type: Int = 1\n): Result<LiveShieldUser>', 'internal suspend fun requestOriginalDesktopLiveShieldUser(canonicalRoomId: Long, uid: Long, type: Int, api: BilibiliApi, currentCsrf: String): Result<LiveShieldUser>'), ('val realRoomId = resolveRealRoomId(roomId)', 'val realRoomId = canonicalRoomId'), ('val csrf = com.android.purebilibili.core.store.TokenManager.csrfCache ?: ""', 'val csrf = currentCsrf')], 'originalSha256LF': '52ec9c61ded5e99526cbfb59a92eb6bf6174cff0249ad8fe82fc637d36d771bd', 'adaptedSha256LF': '0674fd74a22768a5956a5233f1c8cc222138e8b3761dae7c7b568c2c123f96b2'}, {'name': 'reportLiveDanmaku', 'mappings': [('suspend fun reportLiveDanmaku(request: LiveDanmakuReportRequest): Result<Boolean>', 'internal suspend fun requestOriginalDesktopReportLiveDanmaku(request: LiveDanmakuReportRequest, api: BilibiliApi, currentCsrf: String): Result<Boolean>'), ('val realRoomId = resolveRealRoomId(request.roomId)', 'val realRoomId = request.roomId'), ('val csrf = com.android.purebilibili.core.store.TokenManager.csrfCache ?: ""', 'val csrf = currentCsrf')], 'originalSha256LF': 'e69203bbf5dd27bae1c20ecf3038603898f6232f93f41b967bf744f81ef8cc95', 'adaptedSha256LF': '14fe0e37f964c8abe0767c8bcaf3fef23434d83c46ead21ad26aafe44a6c6e80'}, {'name': 'reportSuperChat', 'mappings': [('suspend fun reportSuperChat(request: LiveSuperChatReportRequest): Result<Boolean>', 'internal suspend fun requestOriginalDesktopReportLiveSuperChat(request: LiveSuperChatReportRequest, api: BilibiliApi, currentCsrf: String): Result<Boolean>'), ('val realRoomId = resolveRealRoomId(request.roomId)', 'val realRoomId = request.roomId'), ('val csrf = com.android.purebilibili.core.store.TokenManager.csrfCache ?: ""', 'val csrf = currentCsrf')], 'originalSha256LF': 'b58ac0bf8f079fc75bb76f79e088994dc1285921bc6ba75cf58390d39235e4d1', 'adaptedSha256LF': '0d8b28e45095e02a19b4b85d7583aa5207bfe4475bfebce03e44b00cb34e5a25'}]
MODEL_NAMES = ['LiveDanmakuReportRequest', 'LiveSuperChatReportRequest', 'LiveReportReason', 'LiveShieldInfo', 'LiveShieldKeyword', 'LiveShieldUser']
API_CONTRACT = '    @retrofit2.http.FormUrlEncoded\n    @retrofit2.http.POST("https://api.live.bilibili.com/liveact/shield_user")\n    suspend fun shieldLiveUser(\n        @retrofit2.http.Field("uid") uid: Long,\n        @retrofit2.http.Field("roomid") roomId: Long,\n        @retrofit2.http.Field("type") type: Int,\n        @retrofit2.http.Field("csrf") csrf: String,\n        @retrofit2.http.Field("csrf_token") csrfToken: String\n    ): SimpleApiResponse\n\n    @GET("https://api.live.bilibili.com/xlive/web-room/v1/index/getInfoByUser")\n    suspend fun getLiveInfoByUser(\n        @QueryMap params: Map<String, String>\n    ): ResponseBody\n\n    @retrofit2.http.FormUrlEncoded\n    @retrofit2.http.POST("https://api.live.bilibili.com/liveact/user_silent")\n    suspend fun setLiveSilentRule(\n        @retrofit2.http.Field("type") type: String,\n        @retrofit2.http.Field("level") level: Int,\n        @retrofit2.http.Field("csrf") csrf: String,\n        @retrofit2.http.Field("csrf_token") csrfToken: String\n    ): SimpleApiResponse\n\n    @retrofit2.http.FormUrlEncoded\n    @retrofit2.http.POST("https://api.live.bilibili.com/xlive/web-ucenter/v1/banned/AddShieldKeyword")\n    suspend fun addLiveShieldKeyword(\n        @retrofit2.http.Field("keyword") keyword: String,\n        @retrofit2.http.Field("csrf") csrf: String,\n        @retrofit2.http.Field("csrf_token") csrfToken: String\n    ): SimpleApiResponse\n\n    @retrofit2.http.FormUrlEncoded\n    @retrofit2.http.POST("https://api.live.bilibili.com/xlive/web-ucenter/v1/banned/DelShieldKeyword")\n    suspend fun deleteLiveShieldKeyword(\n        @retrofit2.http.Field("keyword") keyword: String,\n        @retrofit2.http.Field("csrf") csrf: String,\n        @retrofit2.http.Field("csrf_token") csrfToken: String\n    ): SimpleApiResponse\n\n    @retrofit2.http.FormUrlEncoded\n    @retrofit2.http.POST("https://api.live.bilibili.com/xlive/web-ucenter/v1/dMReport/Report")\n    suspend fun reportLiveDanmaku(\n        @retrofit2.http.Field("id") id: Long = 0,\n        @retrofit2.http.Field("roomid") roomId: Long,\n        @retrofit2.http.Field("tuid") targetUid: Long,\n        @retrofit2.http.Field("msg") message: String,\n        @retrofit2.http.Field("reason") reason: String,\n        @retrofit2.http.Field("ts") ts: Long,\n        @retrofit2.http.Field("sign") sign: String,\n        @retrofit2.http.Field("reason_id") reasonId: Int,\n        @retrofit2.http.Field("token") token: String = "",\n        @retrofit2.http.Field("dm_type") dmType: Int = 0,\n        @retrofit2.http.Field("id_str") idStr: String,\n        @retrofit2.http.Field("csrf") csrf: String,\n        @retrofit2.http.Field("csrf_token") csrfToken: String,\n        @retrofit2.http.Field("visit_id") visitId: String = ""\n    ): SimpleApiResponse\n\n    @retrofit2.http.FormUrlEncoded\n    @retrofit2.http.POST("https://api.live.bilibili.com/av/v1/SuperChat/report")\n    suspend fun reportLiveSuperChat(\n        @retrofit2.http.Field("id") id: Long,\n        @retrofit2.http.Field("roomid") roomId: Long,\n        @retrofit2.http.Field("uid") uid: Long,\n        @retrofit2.http.Field("msg") message: String,\n        @retrofit2.http.Field("reason") reason: String,\n        @retrofit2.http.Field("ts") ts: Long,\n        @retrofit2.http.Field("sign") sign: String = "",\n        @retrofit2.http.Field("reason_id") reasonId: String,\n        @retrofit2.http.Field("token") token: String = "",\n        @retrofit2.http.Field("id_str") idStr: String,\n        @retrofit2.http.Field("csrf") csrf: String,\n        @retrofit2.http.Field("csrf_token") csrfToken: String,\n        @retrofit2.http.Field("visit_id") visitId: String = ""\n    ): SimpleApiResponse\n\n'

def fixed_inputs(repo: Path) -> dict[str, str]:
    result = {}
    for key, pin in PINS.items():
        raw = safe(repo / pin["archive"]).read_bytes()
        if len(raw) != pin["rawBytes"] or hashlib.sha256(raw).hexdigest() != pin["rawSha256"] or \
                hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest() != pin["gitBlob"]:
            raise ValueError("Fixed full original live-moderation source changed: " + pin["path"])
        result[key] = raw.decode("utf8").replace("\r\n", "\n")
    return result

def emit(repo: Path, output: Path, parser, read, function, data_class, write) -> list[Path]:
    original = fixed_inputs(repo)
    if read(repo, PINS["api"]["path"]).count(API_CONTRACT) != 1:
        raise ValueError("Existing complete live-moderation API declarations changed")
    generated = []
    proofs = []
    models = read(repo, PINS["models"]["path"])
    before_models = models
    model_changes = []
    for name in MODEL_NAMES:
        old = data_class(models, name, parser)
        new = data_class(original["models"], name, parser)
        if models.count(old) != 1:
            raise ValueError("Complete original live model selection changed: " + name)
        if old != new:
            models = models.replace(old, new, 1)
            model_changes.append((old, new))
    old = models[models.index("val DefaultLiveReportReasons ="):].strip()
    new = original["models"][original["models"].index("val DefaultLiveReportReasons ="):].strip()
    if models.count(old) != 1:
        raise ValueError("Complete original report reasons changed")
    models = models.replace(old, new, 1)
    model_changes.append((old, new))
    inverse = models
    for old, new in reversed(model_changes):
        if inverse.count(new) != 1:
            raise ValueError("Complete live schema inverse is ambiguous")
        inverse = inverse.replace(new, old, 1)
    if inverse != before_models:
        raise ValueError("Whole live model adaptation inverse failed")
    provenance = "// Fixed b209 live-moderation schema; unrelated canonical emoji/vote bodies retained.\n"
    generated.append(write(output, "com/android/purebilibili/data/repository/LiveInteractionModels.kt",
        PINS["models"]["path"], before_models, provenance + models))
    before_realtime = read(repo, PINS["realtime"]["path"])
    old = function(before_realtime, "systemMessage", parser)
    new = function(original["realtime"], "systemMessage", parser)
    if before_realtime.count(old) != 1:
        raise ValueError("Complete system-message selection changed")
    realtime = before_realtime.replace(old, new, 1)
    if realtime.count(new) != 1 or realtime.replace(new, old, 1) != before_realtime:
        raise ValueError("Whole realtime system marker inverse failed")
    generated.append(write(output, "com/android/purebilibili/feature/live/LiveRealtimeMessagePolicy.kt",
        PINS["realtime"]["path"], before_realtime, "// Fixed b209 complete systemMessage; other canonical realtime bodies retained.\n" + realtime))
    generated.append(write(output, "com/android/purebilibili/feature/live/LiveDanmakuFilterPolicy.kt",
        PINS["policy"]["path"], original["policy"], original["policy"]))
    generated.append(write(output, "com/android/purebilibili/feature/live/DesktopLiveDanmakuItem.kt",
        PINS["vm"]["archive"], original["vm"], "package com.android.purebilibili.feature.live\n\n" + data_class(original["vm"], "LiveDanmakuItem", parser)))
    projection = original["repo"]
    methods = []
    for recipe in RECIPES:
        before = function(original["repo"], recipe["name"], parser)
        if hashlib.sha256(before.encode()).hexdigest() != recipe["originalSha256LF"]:
            raise ValueError("Complete original moderation method changed: " + recipe["name"])
        after = before
        for old, new in recipe["mappings"]:
            if after.count(old) != 1:
                raise ValueError("Original moderation platform seam changed")
            after = after.replace(old, new, 1)
        inverse = after
        for old, new in reversed(recipe["mappings"]):
            if inverse.count(new) != 1:
                raise ValueError("Original moderation inverse is ambiguous")
            inverse = inverse.replace(new, old, 1)
        if inverse != before or hashlib.sha256(after.encode()).hexdigest() != recipe["adaptedSha256LF"]:
            raise ValueError("Complete original moderation inverse failed")
        original_range, adapted_range = textwrap.indent(before, "    "), textwrap.indent(after, "    ")
        if projection.count(original_range) != 1:
            raise ValueError("Whole original moderation repository range changed")
        projection = projection.replace(original_range, adapted_range, 1)
        proofs.append((original_range, adapted_range))
        methods.append(after)
    inverse = projection
    for old, new in reversed(proofs):
        if inverse.count(new) != 1:
            raise ValueError("Whole moderation repository inverse is ambiguous")
        inverse = inverse.replace(new, old, 1)
    if inverse != original["repo"]:
        raise ValueError("Whole original moderation repository inverse failed")
    body = "package com.android.purebilibili.data.repository\n\nimport com.android.purebilibili.core.network.BilibiliApi\nimport kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.withContext\n\n" + "\n\n".join(methods)
    generated.append(write(output, "com/android/purebilibili/data/repository/DesktopLiveModerationRequests.kt",
        PINS["repo"]["archive"], original["repo"], body))
    safe(output / "v034-live-moderation-source-proof.json").write_text(json.dumps(dict(
        fixedCommit=COMMIT, fullOriginalSources=PINS, fullRequestMethods=RECIPES,
        schemaMappings=model_changes, completeWholeModelsInverse=True,
        wholeRepositoryInverse=True, wholeRealtimeInverse=True,
        exactApiContract=API_CONTRACT, unrelatedCanonicalEmoticonsRetained=True,
        serverSilentLevelsNotLocallyInvented=True), ensure_ascii=True, indent=2) + "\n", encoding="utf8", newline="\n")
    return generated
