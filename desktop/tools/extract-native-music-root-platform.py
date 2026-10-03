"""Extract only the original pure BGM display selection; no Android UI declarations."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json

SOURCE = "app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoInfoSection.kt"


def declaration(root):
    spec = importlib.util.spec_from_file_location("structure", root / "desktop/tools/sync-upstream.py")
    parser = importlib.util.module_from_spec(spec); spec.loader.exec_module(parser)
    raw = (_desktop_canonical_source(root, SOURCE)).read_text(encoding="utf-8")
    tokens = parser.kotlin_tokens(raw)
    matches = [i for i, token in enumerate(tokens[:-1]) if token[0] == "fun" and tokens[i + 1][0] == "resolveDisplayBgmList"]
    if len(matches) != 1: raise ValueError("Original BGM pure function must be unique")
    start = matches[0] - 1
    if tokens[start][0] != "internal": raise ValueError("Original BGM function visibility changed")
    end = start
    while tokens[end][0] != "{": end += 1
    depth = 1
    while depth:
        end += 1
        depth += (tokens[end][0] == "{") - (tokens[end][0] == "}")
    return raw[tokens[start][1]:tokens[end][2]]


def inventory(root):
    return [{"path": SOURCE, "mode": "policy-extract", "features": ["resolveDisplayBgmList"],
             "sha256": hashlib.sha256((_desktop_canonical_source(root, SOURCE)).read_text(encoding="utf-8").encode()).hexdigest(),
             "declarationSha256": hashlib.sha256(declaration(root).encode()).hexdigest()}]


def generate(root, output):
    file = output / "com/android/purebilibili/feature/video/ui/section/DesktopOriginalBgmDisplay.kt"
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text("package com.android.purebilibili.feature.video.ui.section\n\nimport com.android.purebilibili.data.model.response.BgmInfo\n\n" + declaration(root) + "\n", encoding="utf-8", newline="\n")
    return file


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--inventory", action="store_true")
    args = parser.parse_args()
    if args.inventory: print(json.dumps(inventory(args.source_root), ensure_ascii=False, indent=2)); return
    if args.output_dir is None: parser.error("--output-dir is required for generation")
    generate(args.source_root, args.output_dir)


if __name__ == "__main__": main()
