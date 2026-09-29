"""Generate desktop API declarations from the current upstream source, without hand-copied routes.

Usage: python extract-upstream-api.py --repo <repository> --output <generated-sources-directory>
Missing/changed declarations fail the build so an upstream update cannot silently use stale APIs.
"""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import re


METHODS = {
    "BilibiliApi": ["getNavInfo", "getRecommendParams", "getPopularVideos", "getVideoInfo", "getVideoInfoByAid", "getPlayUrl", "getPlayUrlLegacy", "getRelatedVideos", "getReplyListLegacy"],
    "SearchApi": ["search"],
    "PassportApi": ["validateCookieSession", "generateQrCode", "pollQrCode"],
    "BuvidApi": ["getSpi"],
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


def generate(repo: Path, output: Path) -> Path:
    source_path = repo / "app/src/main/java/com/android/purebilibili/core/network/ApiClient.kt"
    source = source_path.read_text(encoding="utf-8")
    constant = re.search(r'(?m)^internal const val FORCE_COOKIE_HEADER\s*=\s*"[^"\n]+"', source)
    if constant is None:
        raise ValueError("Upstream FORCE_COOKIE_HEADER changed; review session adapter")
    sections = []
    for model in ["BuvidSpiData", "BuvidSpiResponse"]:
        marker = re.search(r"@kotlinx\.serialization\.Serializable\s+data class\s+" + model + r"\s*\(", source)
        if marker is None:
            raise ValueError(f"Upstream visitor model {model} missing")
        opening = source.find("(", marker.start())
        closing = matching_bracket(source, opening, "(", ")")
        sections.append(source[marker.start():closing + 1])
    for interface, methods in METHODS.items():
        marker = re.search(r"\binterface\s+" + re.escape(interface) + r"\s*\{", source)
        if marker is None:
            raise ValueError(f"Upstream interface {interface} missing")
        start = source.find("{", marker.start())
        end = matching_bracket(source, start, "{", "}")
        body = source[start + 1:end]
        declarations = [extract_method(body, name) for name in methods]
        sections.append("interface " + interface + " {\n" + "\n\n".join(declarations) + "\n}")
    generated = "\n".join([
        "// GENERATED from upstream ApiClient.kt; edit the original API or extraction selection, never this file.",
        "// SHA-256: " + hashlib.sha256(source_path.read_bytes()).hexdigest(),
        "package com.android.purebilibili.core.network", "",
        "import com.android.purebilibili.data.model.response.*",
        "import retrofit2.Response", "import retrofit2.http.*", "",
        constant.group(0), "", "\n\n".join(sections), "",
    ])
    destination = output / "com/android/purebilibili/core/network/DesktopUpstreamApi.kt"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(generated, encoding="utf-8")
    return destination


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    print(generate(arguments.repo.resolve(), arguments.output.resolve()))
