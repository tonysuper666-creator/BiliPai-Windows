"""Generate desktop API declarations from the current upstream source, without hand-copied routes.

Usage: python extract-upstream-api.py --repo <repository> --output <generated-sources-directory>
Missing/changed declarations fail the build so an upstream update cannot silently use stale APIs.
"""
from __future__ import annotations
from v025_source_paths import canonical_source as _desktop_canonical_source

import argparse
import hashlib
import importlib.util
from pathlib import Path
import re
import textwrap


METHODS = {
    "BilibiliApi": ["getNavInfo", "getRecommendParams", "getPopularVideos", "getVideoInfo", "getVideoInfoByAid", "getPlayUrl", "getPlayUrlLegacy", "getRelatedVideos", "getReplyListLegacy", "getFavFolders", "getFavoriteList", "getHistoryList", "getWatchLaterPage"],
    "SearchApi": ["search"],
    "PassportApi": ["validateCookieSession", "generateQrCode", "pollQrCode"],
    "BuvidApi": ["getSpi"],
    "DynamicApi": ["getDynamicFeed"],
}


def matching_bracket(text: str, start: int, opening: str, closing: str) -> int:
    depth = 0
    quoted = False
    escaped = False
    for i in range(start, len(text)):
        character = text[i]
        if quoted:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == '"':
                quoted = False
        elif character == '"':
            quoted = True
        elif character == opening:
            depth += 1
        elif character == closing:
            depth -= 1
            if depth == 0:
                return i
    raise ValueError(f"Unbalanced {opening}{closing} in upstream API source")


def extract_method(body: str, name: str) -> str:
    matches = list(re.finditer(r"\bsuspend\s+fun\s+" + re.escape(name) + r"\s*\(", body))
    if len(matches) != 1:
        raise ValueError(f"Expected one upstream {name} declaration; found {len(matches)}")
    function = matches[0]
    opening = body.find("(", function.start())
    end_params = matching_bracket(body, opening, "(", ")")
    return_match = re.match(r"\s*:\s*([\w.]+(?:<[^\n]+>)?\??)", body[end_params + 1:])
    if return_match is None:
        raise ValueError(f"Unsupported upstream return signature for {name}; review before updating")
    end = end_params + 1 + return_match.end()
    # Every selected method is a bodyless Retrofit GET declaration. Keep its original annotations.
    get_annotations = list(re.finditer(r"(?m)^[ \t]*@GET\(", body[:function.start()]))
    if not get_annotations:
        raise ValueError(f"Upstream {name} lost its Retrofit GET annotation")
    start = get_annotations[-1].start()
    preceding_method = body.rfind("suspend fun ", 0, start)
    headers = list(re.finditer(r"(?m)^[ \t]*@Headers\(", body[preceding_method + 1:start]))
    if headers:
        start = preceding_method + 1 + headers[-1].start()
    declaration = body[start:end]
    if "suspend fun " in body[start:function.start()]:
        raise ValueError(f"Unexpected declarations between annotations and {name}")
    return declaration.strip()


CORE_DIAGNOSTICS_SHA = '489dfcff60a50dc4382a8c2d18fd6db71fe93b396d51035a53c16b996ecbcce7'

def generate_core_data_log(repo, output, parser):
    import json
    relative = 'core-data/src/main/java/com/android/purebilibili/core/network/CoreNetworkRuntime.kt'
    original = _desktop_canonical_source(repo, relative).read_text(encoding='utf8').replace('\r\n', '\n')
    assert hashlib.sha256(original.encode()).hexdigest() == CORE_DIAGNOSTICS_SHA
    tokens = parser.kotlin_tokens(original)
    first, last = parser.kotlin_structure(tokens, 'object', 'CoreDataLog')
    beginning = original.rfind('\n', 0, tokens[first][1]) + 1
    selected = original[beginning:tokens[last][2]]
    adapted = selected
    changes = [
        ('CoreNetworkRuntime.config.log', 'DesktopCoreNetworkDiagnostics.log', 3),
        ('CoreNetworkRuntime.config.reportApiError', 'DesktopCoreNetworkDiagnostics.reportApiError', 1),
    ]
    for before, after, count in changes:
        assert adapted.count(before) == count
        adapted = adapted.replace(before, after)
    inverse = adapted
    for before, after, count in reversed(changes):
        assert inverse.count(after) == count
        inverse = inverse.replace(after, before)
    assert inverse == selected
    leaf = '\nprivate object DesktopCoreNetworkDiagnostics {\n' + \
        '    fun log(level:String, tag:String, message:String, error:Throwable?) {\n' + \
        '        com.bilipai.desktop.diagnostics.DesktopDiagnosticsBridge.record(level, tag, message, error)\n    }\n' + \
        '    fun reportApiError(endpoint:String, httpCode:Int, errorMessage:String) {\n' + \
        '        com.bilipai.desktop.diagnostics.DesktopDiagnosticsBridge.reportApiError(endpoint, httpCode, errorMessage)\n    }\n}\n'
    target = output / 'com/android/purebilibili/core/network/DesktopCoreDataLog.kt'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text('package com.android.purebilibili.core.network\n\n' + adapted + leaf, encoding='utf8', newline='\n')
    (output / 'core-data-log-source-proof.json').write_text(json.dumps(dict(
        originalPath=relative, originalSha256LF=CORE_DIAGNOSTICS_SHA,
        selectedSha256LF=hashlib.sha256(selected.encode()).hexdigest(),
        adaptedSha256LF=hashlib.sha256(adapted.encode()).hexdigest(),
        originalSelectedBodyInverseExact=True, callbackChanges=changes,
        existingDiagnosticsConsumer=True, newConfigOrStoreOrClient=False), indent=2)+'\n', encoding='utf8')

def generate(repo: Path, output: Path) -> Path:
    source_path = _desktop_canonical_source(repo, 'core-data/src/main/java/com/android/purebilibili/core/network/ApiClient.kt')
    source = source_path.read_text(encoding="utf-8")
    spec = importlib.util.spec_from_file_location("bilipai_kotlin_structure", repo / "desktop/tools/sync-upstream.py")
    parser = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(parser)
    tokens = parser.kotlin_tokens(source)
    sections = [match.group(0) for match in re.finditer(
        r'(?m)^(?:internal )?const val \w+\s*=\s*"[^"\n]+"', source)]
    # Keep every actual Retrofit interface and request DTO. Android transport/session
    # implementations remain in the platform adapter; no endpoint is retyped here.
    interfaces = list(re.finditer(r"(?m)^interface\s+(\w+)\s*\{", source))
    if not interfaces or not set(METHODS).issubset({match.group(1) for match in interfaces}):
        raise ValueError("An upstream interface disappeared; review before building.")
    for marker in interfaces:
        _, end = parser.kotlin_structure(tokens, "interface", marker.group(1))
        sections.append(source[marker.start():tokens[end][2]])
    for marker in re.finditer(r"@kotlinx\.serialization\.Serializable\s+data class\s+(\w+)\s*\(", source):
        _, end = parser.kotlin_structure(tokens, "class", marker.group(1), constructor_only=True)
        sections.append(source[marker.start():tokens[end][2]])
    for name in ("buildDynamicRepostRequest", "buildFavoriteFolderDynamicRequest"):
        start, end = parser.kotlin_structure(tokens, "fun", name)
        beginning = source.rfind("\n", 0, tokens[start][1]) + 1
        sections.append(source[beginning:tokens[end][2]])
    # One fixed v029 streaming declaration pair, on the existing BilibiliApi.
    special_spec = importlib.util.spec_from_file_location("desktop_special_api", repo / "desktop/tools/extract-upstream-special-danmaku.py")
    special = importlib.util.module_from_spec(special_spec)
    special_spec.loader.exec_module(special)
    before = "    @GET\n    suspend fun getDanmakuSpecialDm(@retrofit2.http.Url url: String): ResponseBody"
    after = special.special_api_declarations(repo)
    joined = "\n\n".join(sections)
    if joined.count(before) != 1 or "suspend fun getDanmakuSpecialRange(" in joined:
        raise ValueError("Canonical special API streaming seam changed")
    sections = [part.replace(before, after, 1) for part in sections]
    adapted = "\n\n".join(sections)
    if adapted.count(after) != 1 or adapted.replace(after, before, 1) != joined:
        raise ValueError("Whole canonical API streaming inverse changed")
    output.mkdir(parents=True, exist_ok=True)
    import json
    (output / "special-api-source-proof.json").write_text(json.dumps(dict(
        originalCommit=special.COMMIT, originalApiRawSha256=special.PINS["ApiClient.kt"],
        selectedSha256LF=hashlib.sha256(after.encode()).hexdigest(),
        canonicalSelectedInverse=True, mappings=[dict(before=before,after=after)],
        wholeCanonicalInterfacesPreserved=True), indent=2)+"\n", encoding="utf8")
    from v030_live_stream import api_delta, emit_models as emit_live_models
    live_api_body = api_delta("\n\n".join(sections), repo, output)
    generated = "\n".join([
        "// GENERATED from upstream ApiClient.kt; edit the original API or extraction selection, never this file.",
        "// SHA-256: " + hashlib.sha256(source_path.read_bytes()).hexdigest(),
        "package com.android.purebilibili.core.network", "",
        "import com.android.purebilibili.data.model.response.*",
        "import retrofit2.Response", "import retrofit2.http.*", "import okhttp3.ResponseBody", "",
        live_api_body, "",
    ])
    destination = output / "com/android/purebilibili/core/network/DesktopUpstreamApi.kt"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(generated, encoding="utf-8")
    policies = [
        ("app/src/main/java/com/android/purebilibili/data/repository/BangumiRepository.kt",
         "com.android.purebilibili.data.repository", ["buildBangumiPlayUrlParams"], [],
         "import com.android.purebilibili.core.util.IdUtils"),
        ("app/src/main/java/com/android/purebilibili/feature/download/DownloadManager.kt",
         "com.android.purebilibili.feature.download", ["isValidPartialContentResponse", "shouldRefreshDownloadUrlAfterFailure"],
         ["PARTIAL_CONTENT_RANGE_REGEX"], ""),
    ]
    for relative, package, functions, constants, imports in policies:
        policy_source = (_desktop_canonical_source(repo, relative)).read_text(encoding="utf-8")
        policy_tokens = parser.kotlin_tokens(policy_source)
        pieces = ["// GENERATED verbatim from " + relative, "package " + package, imports]
        for name in constants:
            marker = re.search(r"(?m)^private val " + re.escape(name) + r"\s*=\s*[^\n]+", policy_source)
            if marker is None:
                raise ValueError("Required upstream policy constant missing: " + name)
            pieces.append(marker.group(0))
        for name in functions:
            if name == "shouldRefreshDownloadUrlAfterFailure":
                positions = [index for index, token in enumerate(policy_tokens[:-1])
                             if token[0] == "fun" and policy_tokens[index + 1][0] == name]
                if len(positions) != 1:
                    raise ValueError("Missing or duplicate upstream URL refresh policy")
                start = positions[0]
                end = start + 2
                while policy_tokens[end][0] != "{":
                    end += 1
                depth = 1
                while depth:
                    end += 1
                    depth += (policy_tokens[end][0] == "{") - (policy_tokens[end][0] == "}")
            else:
                start, end = parser.kotlin_structure(policy_tokens, "fun", name)
            beginning = policy_source.rfind("\n", 0, policy_tokens[start][1]) + 1
            declaration = textwrap.dedent(policy_source[beginning:policy_tokens[end][2]])
            # Only visibility changes: the original Android manager used a private
            # helper; the Windows adapter needs access from its own package.
            declaration = re.sub(r"^private fun ", "internal fun ", declaration)
            pieces.append(declaration)
        target = output / (package.replace(".", "/") + "/DesktopUpstreamPolicies.kt")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("\n\n".join(pieces) + "\n", encoding="utf-8")
    history_path = 'core-data/src/main/java/com/android/purebilibili/data/repository/HistoryRepository.kt'
    history = (_desktop_canonical_source(repo, history_path)).read_text(encoding="utf-8")
    history_tokens = parser.kotlin_tokens(history)
    pieces = ["// GENERATED verbatim from " + history_path, "package com.android.purebilibili.data.repository"]
    for kind, name, constructor_only in [("class", "HistoryCursorQuery", True), ("fun", "resolveHistoryCursorQuery", False)]:
        start, end = parser.kotlin_structure(history_tokens, kind, name, constructor_only=constructor_only)
        begin = history.rfind("\n", 0, history_tokens[start][1]) + 1
        pieces.append(history[begin:history_tokens[end][2]])
    target = output / "com/android/purebilibili/data/repository/DesktopHistoryCursorPolicy.kt"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("\n\n".join(pieces) + "\n", encoding="utf-8")

    token_path = 'core-data/src/main/java/com/android/purebilibili/core/store/TokenManager.kt'
    token_source = (_desktop_canonical_source(repo, token_path)).read_text(encoding="utf-8")
    platform_constants = []
    for name in ["ACCESS_TOKEN_PLATFORM_TV", "ACCESS_TOKEN_PLATFORM_ANDROID"]:
        matches = list(re.finditer(r'(?m)^\s*const val ' + name + r'\s*=\s*"[^"\n]+"', token_source))
        if len(matches) != 1:
            raise ValueError("Upstream token platform constant changed: " + name)
        platform_constants.append(matches[0].group(0).strip())
    # This alias changes only the Android store binding. The original signed parameter
    # function below remains verbatim; actual Windows credentials are passed explicitly.
    target = output / "com/android/purebilibili/core/network/DesktopSpaceRequestPolicy.kt"
    start, end = parser.kotlin_structure(tokens, "fun", "buildSpaceLikedArchiveParams")
    begin = source.rfind("\n", 0, tokens[start][1]) + 1
    pieces = ["// GENERATED from ApiClient.kt and TokenManager.kt platform constants; algorithm is unchanged.",
        "package com.android.purebilibili.core.network", "import com.android.purebilibili.core.network.DesktopTokenPlatform as TokenManager",
        "internal object DesktopTokenPlatform {\n" + "\n".join("    " + line for line in platform_constants) + "\n}",
        source[begin:tokens[end][2]]]
    target.write_text("\n\n".join(pieces) + "\n", encoding="utf-8")
    generate_core_data_log(repo, output, parser)
    from v029_comment_search import emit_models
    emit_models(repo, output)
    emit_live_models(repo, output)
    return destination


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    print(generate(arguments.repo.resolve(), arguments.output.resolve()))
