"""Extract renderer-neutral upstream danmaku code, preserving the original functions verbatim."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re

FEATURE_ROOT = "app/src/main/java/com/android/purebilibili/feature/video/danmaku/"
SOURCES = {
    FEATURE_ROOT + "DanmakuProto.kt": "direct",
    FEATURE_ROOT + "DanmakuSegmentWindowPolicy.kt": "direct",
    FEATURE_ROOT + "DanmakuKeywordFilterPolicy.kt": "direct",
    FEATURE_ROOT + "DanmakuTypeFilterPolicy.kt": "direct",
    FEATURE_ROOT + "CommandDanmakuPolicy.kt": "direct",
    FEATURE_ROOT + "AdvancedDanmakuData.kt": "extracted",
    FEATURE_ROOT + "DanmakuParser.kt": "extracted",
    "app/src/main/java/com/android/purebilibili/data/repository/DanmakuRepository.kt": "extracted",
}


def read_source(repo: Path, path: str) -> str:
    return (repo / path).read_text(encoding="utf-8").replace("\r\n", "\n")


def generated_file(output: Path, relative: str, source_path: str, original: str, body: str) -> Path:
    destination = output / relative
    destination.parent.mkdir(parents=True, exist_ok=True)
    header = "// Generated from " + source_path + "; do not edit.\n"
    header += "// LF-normalized SHA-256: " + hashlib.sha256(original.encode("utf-8")).hexdigest() + "\n"
    destination.write_text(header + body.rstrip() + "\n", encoding="utf-8")
    return destination


def generate(repo: Path, output: Path) -> list[Path]:
    model_path = FEATURE_ROOT + "AdvancedDanmakuData.kt"
    model = read_source(repo, model_path)
    marker = "\n/**\n * 弹幕解析结果"
    if model.count(marker) != 1:
        raise ValueError("Upstream advanced model boundary changed; review the desktop renderer")
    generated = [generated_file(output,
        "com/android/purebilibili/feature/video/danmaku/AdvancedDanmakuData.kt", model_path, model, model.split(marker)[0])]

    parser_path = FEATURE_ROOT + "DanmakuParser.kt"
    parser = read_source(repo, parser_path)
    begin = "    internal fun parseAdvancedDanmaku("
    end = "    private fun formatDanmakuTextWithCount("
    if parser.count(begin) != 1 or parser.count(end) != 1:
        raise ValueError("Upstream advanced parser boundary changed; review before updating")
    functions = parser[parser.index(begin):parser.index(end)].rstrip()
    required = ["parseAdvancedDanmaku", "normalizeBasCoordinate", "optBasDouble", "parseBasAlphaRange", "parseBasPath"]
    found = re.findall(r"\bfun\s+(\w+)\s*\(", functions)
    if found != required:
        raise ValueError(f"Upstream advanced parser dependencies changed: {found}")
    body = "package com.android.purebilibili.feature.video.danmaku\n\nobject DesktopAdvancedDanmakuParser {\n" + functions + "\n}\n"
    generated.append(generated_file(output,
        "com/android/purebilibili/feature/video/danmaku/DesktopAdvancedDanmakuParser.kt", parser_path, parser, body))

    repository_path = "app/src/main/java/com/android/purebilibili/data/repository/DanmakuRepository.kt"
    repository = read_source(repo, repository_path)
    constants = []
    for name in ["DANMAKU_SEGMENT_DURATION_MS", "DANMAKU_SEGMENT_SAFE_FALLBACK_COUNT"]:
        match = re.search(r"(?m)^internal const val " + name + r"\s*=\s*\d+L?\s*$", repository)
        if match is None:
            raise ValueError("Upstream segment-count constant changed: " + name)
        constants.append(match.group(0).strip())
    begin = "internal fun resolveDanmakuSegmentCount("
    end = "\n/**\n * 弹幕相关数据仓库"
    if repository.count(begin) != 1 or repository.count(end) != 1:
        raise ValueError("Upstream segment-count boundary changed")
    function = repository[repository.index(begin):repository.index(end)].rstrip()
    body = "package com.android.purebilibili.data.repository\n\n" + "\n".join(constants) + "\n\n" + function + "\n"
    generated.append(generated_file(output,
        "com/android/purebilibili/data/repository/DesktopDanmakuSegmentPolicy.kt", repository_path, repository, body))
    return generated


if __name__ == "__main__":
    command = argparse.ArgumentParser(description=__doc__)
    command.add_argument("--repo", type=Path, required=True)
    command.add_argument("--output", type=Path)
    command.add_argument("--inventory", action="store_true")
    arguments = command.parse_args()
    repo = arguments.repo.resolve()
    if arguments.inventory:
        print(json.dumps([{"path": path, "mode": mode, "features": ["danmaku"],
            "sha256": hashlib.sha256(read_source(repo, path).encode("utf-8")).hexdigest()}
            for path, mode in SOURCES.items()], ensure_ascii=False, indent=2))
    elif arguments.output:
        for path in generate(repo, arguments.output.resolve()):
            print(path)
    else:
        command.error("Pass --output or --inventory")
