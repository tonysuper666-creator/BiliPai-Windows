"""Extract renderer-neutral upstream danmaku code, preserving the original functions verbatim."""
from __future__ import annotations
from v025_source_paths import canonical_source as _desktop_canonical_source

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
    "core-data/src/main/java/com/android/purebilibili/data/repository/DanmakuContentRepository.kt": "extracted",
}


def read_source(repo: Path, path: str) -> str:
    from v025_source_paths import canonical_source
    return canonical_source(repo,path).read_text(encoding="utf-8").replace("\r\n", "\n")


def generated_file(output: Path, relative: str, source_path: str, original: str, body: str) -> Path:
    destination = output / relative
    destination.parent.mkdir(parents=True, exist_ok=True)
    header = "// Generated from " + source_path + "; do not edit.\n"
    header += "// LF-normalized SHA-256: " + hashlib.sha256(original.encode("utf-8")).hexdigest() + "\n"
    destination.write_text(header + body.rstrip() + "\n", encoding="utf-8")
    return destination


def generate(repo: Path, output: Path) -> list[Path]:
    # Retire only these two named outputs from the old destination namespace.
    # The sole producer now emits the same original types in danmaku/parser.
    # Other files in the caller's output directory remain protected.
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    obsolete = []
    for name, original_source in (("AdvancedDanmakuData.kt", "AdvancedDanmakuData.kt"),
                                  ("DesktopAdvancedDanmakuParser.kt", "DanmakuParser.kt")):
        path = output / "com/android/purebilibili/feature/video/danmaku" / name
        if not path.exists():
            continue
        assert path.resolve().is_relative_to(output) and path.is_file() and not path.is_symlink()
        payload = path.read_bytes()
        expected = "// Generated from " + FEATURE_ROOT + original_source + "; do not edit.\n"
        assert payload.replace(b"\r\n", b"\n").startswith(expected.encode()), "Unknown obsolete destination is protected"
        obsolete.append(dict(path=path.relative_to(output).as_posix(), sha256Bytes=hashlib.sha256(payload).hexdigest()))
    for row in obsolete:
        (output / row["path"]).unlink()
    (output / "namespace-relocation-receipt.json").write_text(
        json.dumps(dict(soleProducer=True, obsoleteNamedOutputs=obsolete), indent=2) + "\n", encoding="utf-8")
    model_path = FEATURE_ROOT + "AdvancedDanmakuData.kt"
    model = read_source(repo, model_path)
    marker = "\n/**\n * 弹幕解析结果"
    if model.count(marker) != 1:
        raise ValueError("Upstream advanced model boundary changed; review the desktop renderer")
    generated = [generated_file(output,
        "com/android/purebilibili/danmaku/parser/AdvancedDanmakuData.kt", model_path, model, model.split(marker)[0])]

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
    body = "package com.android.purebilibili.danmaku.parser\n\nobject DesktopAdvancedDanmakuParser {\n" + functions + "\n}\n"
    generated.append(generated_file(output,
        "com/android/purebilibili/danmaku/parser/DesktopAdvancedDanmakuParser.kt", parser_path, parser, body))

    # Complete canonical core-data segment-count business; the legacy entry
    # name is the existing package-visible desktop consumer boundary only.
    import importlib.util
    spec=importlib.util.spec_from_file_location('segment_selector',repo/'desktop/tools/extract-upstream-danmaku-list-menu.py')
    selector=importlib.util.module_from_spec(spec);spec.loader.exec_module(selector)
    repository_path = 'core-data/src/main/java/com/android/purebilibili/data/repository/DanmakuContentRepository.kt'
    repository = read_source(repo, repository_path)
    constants = []
    for name in ['DANMAKU_SEGMENT_DURATION_MS','DANMAKU_SEGMENT_SAFE_FALLBACK_COUNT']:
        match=re.search(r'(?m)^    (?:private )?const val '+name+r'\s*=\s*[0-9_]+L?\s*$',repository)
        assert match,name
        constants.append(match.group().strip().replace('private const val','internal const val',1))
    function=selector.function(repository,'resolveSegmentCount',indent='    ')[0]
    function=function.replace('    fun resolveSegmentCount(', 'internal fun resolveDanmakuSegmentCount(',1)
    body='package com.android.purebilibili.data.repository\n\n'+'\n'.join(constants)+'\n\n'+function+'\n'
    generated.append(generated_file(output,
        'com/android/purebilibili/data/repository/DesktopDanmakuSegmentPolicy.kt',repository_path,repository,body))
    # Canonical parser font-grade policy moved out of Config in v025.
    import importlib.util
    spec=importlib.util.spec_from_file_location('font_policy_selector',repo/'desktop/tools/extract-upstream-danmaku-list-menu.py')
    selector=importlib.util.module_from_spec(spec);spec.loader.exec_module(selector)
    constant=re.search(r'(?m)^const val BILIBILI_STANDARD_DANMAKU_FONT_SIZE[^\n]*',parser)
    assert constant
    scale=selector.function(parser,'resolveBilibiliDanmakuFontScale',indent='')[0]
    generated.append(generated_file(output,
        'com/android/purebilibili/danmaku/parser/DesktopOriginalDanmakuFontScale.kt',parser_path,parser,
        'package com.android.purebilibili.danmaku.parser\n\n'+constant.group(0)+'\n\n'+scale+'\n'))
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
