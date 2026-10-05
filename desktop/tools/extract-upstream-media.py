"""Extract renderer-neutral PGC/offline policies and original download danmaku repository.

All selected algorithm bodies stay verbatim. Only the Android singleton network binding
becomes a Windows transport getter; the actual Retrofit declarations remain upstream.
"""
from __future__ import annotations
from v025_source_paths import canonical_source as _desktop_canonical_source

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import textwrap

BASE = "app/src/main/java/com/android/purebilibili/"
SOURCES = {
    BASE + "data/repository/BangumiRepository.kt": "policy-extract",
    BASE + "data/repository/DanmakuRepository.kt": "extracted",
    "core-data/src/main/java/com/android/purebilibili/data/repository/DanmakuContentRepository.kt": "extracted",
    BASE + "feature/video/danmaku/DanmakuParser.kt": "extracted",
    BASE + "feature/download/DownloadDanmakuAssetService.kt": "direct",
    BASE + "feature/download/OfflineEpisodeQueuePolicy.kt": "direct",
    BASE + "feature/download/OfflineVideoPlaybackPolicy.kt": "policy-extract",
    BASE + "core/network/socket/DanmakuProtocol.kt": "direct",
    BASE + "core/network/socket/LiveDanmakuConnectionHealthPolicy.kt": "direct",
    BASE + "core/network/socket/LiveDanmakuClient.kt": "extracted",
    BASE + "data/repository/LiveInteractionModels.kt": "direct",
    BASE + "data/repository/LiveRepository.kt": "policy-extract",
    BASE + "feature/live/LiveRealtimeMessagePolicy.kt": "direct",
    BASE + "feature/live/LivePlayerViewModel.kt": "policy-extract",
    BASE + "feature/live/LiveSuperChatExpiryPolicy.kt": "direct",
    BASE + "feature/bangumi/policy/BangumiSeasonActionPolicy.kt": "direct",
    BASE + "feature/bangumi/policy/BangumiFollowStatusPolicy.kt": "policy-extract",
    BASE + "feature/bangumi/policy/BangumiPlaybackUrlPolicy.kt": "direct",
    BASE + "feature/bangumi/policy/CourseNavigationPolicy.kt": "direct",
}


def read(repo: Path, path: str) -> str:
    from v025_source_paths import canonical_source
    return canonical_source(repo,path).read_text(encoding="utf-8").replace("\r\n", "\n")


def parser_for(repo: Path):
    spec = importlib.util.spec_from_file_location("bilipai_media_kotlin", repo / "desktop/tools/sync-upstream.py")
    parser = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(parser)
    return parser


def function(source: str, name: str, parser) -> str:
    matches = list(re.finditer(r"(?m)^[ \t]*(?:(?:internal|private|suspend)\s+)*fun\s+(?:[\w.]+\.)?" + re.escape(name) + r"\s*\(", source))
    if len(matches) != 1:
        raise ValueError(f"Required original function {name} is missing or ambiguous")
    match = matches[0]
    tokens = parser.kotlin_tokens(source)
    index = next(i for i, (_, begin, _) in enumerate(tokens) if begin >= match.start())
    while tokens[index][0] != "(":
        index += 1
    parentheses = 1
    while parentheses:
        index += 1
        parentheses += (tokens[index][0] == "(") - (tokens[index][0] == ")")
    # Every reviewed selection has a block or a `when {}` expression body.
    while tokens[index][0] != "{":
        if tokens[index][1] > match.end() and tokens[index][0] in {"fun", "class", "object"}:
            raise ValueError(f"Original {name} no longer has a supported body")
        index += 1
    depth = 1
    while depth:
        index += 1
        depth += (tokens[index][0] == "{") - (tokens[index][0] == "}")
    return textwrap.dedent(source[match.start():tokens[index][2]])


def data_class(source: str, name: str, parser) -> str:
    tokens = parser.kotlin_tokens(source)
    start, end = parser.kotlin_structure(tokens, "class", name, constructor_only=True)
    beginning = source.rfind("\n", 0, tokens[start][1]) + 1
    return source[beginning:tokens[end][2]]


def write(output: Path, relative: str, original_path: str, original: str, body: str) -> Path:
    result = output / relative
    result.parent.mkdir(parents=True, exist_ok=True)
    header = f"// GENERATED from {original_path}; do not edit.\n"
    header += "// LF-normalized SHA-256: " + hashlib.sha256(original.encode()).hexdigest() + "\n"
    result.write_text(header + body.strip() + "\n", encoding="utf-8")
    return result


def generate(repo: Path, output: Path, test_output: Path | None = None) -> list[Path]:
    parser = parser_for(repo)
    generated = []
    path = BASE + "data/repository/BangumiRepository.kt"
    source = read(repo, path)
    pieces = ["package com.android.purebilibili.data.repository",
        "import com.android.purebilibili.data.model.response.*",
        "import kotlinx.serialization.json.*", "import kotlinx.serialization.decodeFromString",
        data_class(source, "BangumiPlayUrlPayload", parser)]
    for name in ["shouldFallbackToLegacyBangumiPlayUrl", "validateBangumiPlayableVideoInfo", "hasPlayableWebStream",
                 "decodeBangumiPlayUrlPayload", "shouldLoadBangumiSections", "mergeBangumiDetailSections",
                 "mergeBangumiEpisodes", "mergeBangumiSections"]:
        pieces.append(function(source, name, parser))
    generated.append(write(output, "com/android/purebilibili/data/repository/DesktopMediaPgcPolicies.kt", path, source, "\n\n".join(pieces)))

    account_path = BASE + "data/repository/DanmakuRepository.kt"
    account_source = read(repo, account_path)
    content_path = "core-data/src/main/java/com/android/purebilibili/data/repository/DanmakuContentRepository.kt"
    content_source = read(repo, content_path)
    # Entire canonical read/cache schema and object body in the existing owner.
    # The account methods use the same already configured transport getter.
    body = content_source
    for original,replacement in {
        "import com.android.purebilibili.core.network.NetworkModule\n": "",
        "object DanmakuContentRepository {": "object DanmakuRepository {",
        "    private val api = NetworkModule.api": "    private val api get() = com.bilipai.desktop.download.DownloadDanmakuTransport.api",
    }.items():
        if body.count(original) != 1:
            raise ValueError("Original shared danmaku read/cache boundary changed: " + original)
        body=body.replace(original,replacement,1)
    closing=body.rfind("}")
    if body[closing:].strip() != "}":
        raise ValueError("Canonical danmaku content object closing changed")
    pieces=[]
    for name in ["getDanmakuView", "getSpecialDanmakuSegments"]:
        pieces.append(textwrap.indent(function(account_source,name,parser),"    "))
    special_spec=importlib.util.spec_from_file_location("desktop_special_download",repo / "desktop/tools/extract-upstream-special-danmaku.py")
    special=importlib.util.module_from_spec(special_spec);special_spec.loader.exec_module(special)
    special_source=special.checked_inputs(repo)["DanmakuRepository.kt"][1]
    original_download=function(special_source,"downloadSpecialDanmaku",parser)
    download=original_download
    download_changes=[
        ("api.getDanmakuSpecialDm(resolvedUrl).use { body ->", "com.bilipai.desktop.danmaku.DesktopSpecialBodyRead.use(api.getDanmakuSpecialDm(resolvedUrl)) { body ->"),
        ('android.util.Log.w("DanmakuRepo", "Special danmaku export failed: ${e.message}")', 'android.util.Log.w("DanmakuRepo", "Special danmaku export failed")'),
    ]
    for before,after in download_changes:
        if download.count(before)!=1:raise ValueError("Special offline streaming port changed")
        download=download.replace(before,after,1)
    inverse=download
    for before,after in reversed(download_changes):inverse=inverse.replace(after,before,1)
    if inverse!=original_download:raise ValueError("Original special export inverse changed")
    pieces.append(textwrap.indent(download,"    "))
    body=body.replace("package com.android.purebilibili.data.repository", "package com.android.purebilibili.data.repository\n\nimport java.io.File\nimport kotlinx.coroutines.currentCoroutineContext\nimport kotlinx.coroutines.ensureActive",1)
    output.mkdir(parents=True,exist_ok=True)
    (output/"special-download-source-proof.json").write_text(json.dumps(dict(
        originalCommit=special.COMMIT, originalRawSha256=special.PINS["DanmakuRepository.kt"],
        originalSelectedBodyInverseExact=True, selectedSha256LF=hashlib.sha256(original_download.encode()).hexdigest(),
        mappings=[dict(before=b,after=a) for b,a in download_changes],
        existingRepositoryAndTransport=True,standardWholeDownloadUnchanged=True),indent=2)+"\n",encoding="utf8")
    # Legacy public clear entry is retained on the same existing object only.
    pieces.append("    fun clearDanmakuCache() = clearCache()")
    closing=body.rfind("}")
    body=body[:closing]+"\n"+"\n\n".join(pieces)+"\n}"+body[closing+1:]
    generated.append(write(output, "com/android/purebilibili/data/repository/DesktopDownloadDanmakuRepository.kt", content_path, content_source, body))

    path = BASE + "feature/video/danmaku/DanmakuParser.kt"
    source = read(repo, path)
    from v025_source_paths import canonical_source
    canonical_path=canonical_source(repo,path).relative_to(repo.resolve()).as_posix()
    body = "package com.android.purebilibili.danmaku.parser\n\nobject DanmakuParser {\n" + textwrap.indent(function(source, "parseWebViewReply", parser), "    ") + "\n}"
    generated.append(write(output, "com/android/purebilibili/danmaku/parser/DesktopDanmakuMetadataParser.kt", canonical_path, source, body))

    path = BASE + "feature/download/OfflineVideoPlaybackPolicy.kt"
    source = read(repo, path)
    body = "package com.android.purebilibili.feature.download\n\n" + function(source, "resolveOfflinePersistedPlaybackPosition", parser)
    generated.append(write(output, "com/android/purebilibili/feature/download/DesktopOfflinePositionPolicy.kt", path, source, body))

    path = BASE + "data/repository/DanmakuRepository.kt"
    source = read(repo, path)
    # This was an inline algorithm in the original startLiveDanmaku method.
    # Preserve it exactly while supplying hosts from the same upstream response.
    start = source.index("            val orderedHosts = hosts.sortedWith(")
    end = source.index("\n\n            com.android.purebilibili.core.util.Logger.d(", start)
    host_body = textwrap.dedent(source[start:end])
    if "val webSocketUrls =" not in host_body or host_body.count(".distinct()") != 1:
        raise ValueError("Original live host ordering algorithm changed")
    body = "package com.android.purebilibili.data.repository\n\ninternal fun resolveDesktopLiveDanmakuHosts(hosts: List<com.android.purebilibili.data.model.response.LiveDanmuHost>): List<String> {\n"
    body += textwrap.indent(host_body, "    ") + "\n    return webSocketUrls\n}"
    generated.append(write(output, "com/android/purebilibili/data/repository/DesktopLiveHosts.kt", path, source, body))

    path = BASE + "core/network/socket/LiveDanmakuClient.kt"
    source = read(repo, path)
    body = source
    bindings = {
        "import android.os.SystemClock\n": "",
        "import com.android.purebilibili.core.network.NetworkModule\n": "",
        "    private val scope: CoroutineScope,\n": "    private val scope: CoroutineScope,\n    private val httpClient: okhttp3.OkHttpClient,\n",
        "SystemClock.elapsedRealtime()": "System.nanoTime() / 1_000_000L",
        "NetworkModule.okHttpClient.newWebSocket": "httpClient.newWebSocket",
    }
    for original, replacement in bindings.items():
        if body.count(original) != 1:
            raise ValueError(f"Live client platform binding changed: {original!r}")
        body = body.replace(original, replacement)
    generated.append(write(output, "com/android/purebilibili/core/network/socket/LiveDanmakuClient.kt", path, source, body))

    path = BASE + "data/repository/LiveRepository.kt"
    source = read(repo, path)
    # The entire original renderer-neutral model/parser prelude is reused. All
    # repository methods following the singleton keep their Retrofit declarations
    # in the shared API extractor and are called by the Windows thin adapter.
    if source.count("object LiveRepository {") != 1:
        raise ValueError("Original live repository singleton declaration changed")
    body = source.split("object LiveRepository {", 1)[0]
    for binding in ["import com.android.purebilibili.core.network.AppSignUtils\n",
                    "import com.android.purebilibili.core.network.NetworkModule\n",
                    "import com.android.purebilibili.core.store.TokenManager\n"]:
        if body.count(binding) != 1:
            raise ValueError(f"Original live policy import changed: {binding!r}")
        body = body.replace(binding, "")
    generated.append(write(output, "com/android/purebilibili/data/repository/DesktopLivePolicies.kt", path, source, body))

    path = BASE + "feature/live/LivePlayerViewModel.kt"
    source = read(repo, path)
    body = "package com.android.purebilibili.feature.live\n\n" + data_class(source, "LiveDanmakuItem", parser)
    generated.append(write(output, "com/android/purebilibili/feature/live/DesktopLiveDanmakuItem.kt", path, source, body))

    path = BASE + "feature/bangumi/policy/BangumiFollowStatusPolicy.kt"
    source = read(repo, path)
    start = source.index("internal const val BANGUMI_FOLLOW_STATUS_UNFOLLOW")
    end = source.index("internal fun isBangumiFollowed", start)
    body = "package com.android.purebilibili.feature.bangumi\n\nimport com.android.purebilibili.data.model.response.UserStatus\n\n" + source[start:end]
    for name in ["isBangumiFollowed", "resolveBangumiFollowStatusLabel", "resolveBangumiMergedFollowStatus"]:
        body += function(source, name, parser) + "\n\n"
    generated.append(write(output, "com/android/purebilibili/feature/bangumi/DesktopFollowPolicies.kt", path, source, body))
    from v030_live_stream import emit_media
    generated.extend(emit_media(repo, output, test_output))
    return generated


if __name__ == "__main__":
    command = argparse.ArgumentParser(description=__doc__)
    command.add_argument("--repo", type=Path, required=True)
    command.add_argument("--output", type=Path)
    command.add_argument("--test-output", type=Path)
    command.add_argument("--inventory", action="store_true")
    arguments = command.parse_args()
    repo = arguments.repo.resolve()
    if arguments.inventory:
        print(json.dumps([{"path": path, "mode": mode, "features": ["media", "downloads"],
            "sha256": hashlib.sha256(read(repo, path).encode()).hexdigest()} for path, mode in SOURCES.items()], indent=2))
    elif arguments.output:
        for path in generate(repo, arguments.output.resolve(), arguments.test_output.resolve() if arguments.test_output else None):
            print(path)
    else:
        command.error("Pass --output or --inventory")
