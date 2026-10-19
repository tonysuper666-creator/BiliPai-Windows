#!/usr/bin/env python3
"""Emit three pinned original rich-text/lyrics sources over the retained v025 baseline."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
from v025_source_paths import canonical_source

COMMIT = "a69d4181a073aa3207def3fca43318dcf7ab2c70"
BASELINE_COMMIT = "79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40"
MANIFEST_SHA256 = "6936fe243c306eef434c3cbd0be8e18d8a18113c6be5ccd0a8e04a811cccad66"
SOURCES = {'core-data/src/main/java/com/android/purebilibili/data/model/response/DynamicModels.kt': 'com/android/purebilibili/data/model/response/DynamicModels.kt', 'app/src/main/java/com/android/purebilibili/feature/audio/lyrics/LyricsParser.kt': 'com/android/purebilibili/feature/audio/lyrics/LyricsParser.kt', 'app/src/main/java/com/android/purebilibili/feature/audio/lyrics/LyricsDisplayPolicy.kt': 'com/android/purebilibili/feature/audio/lyrics/LyricsDisplayPolicy.kt'}

def wide(path: Path) -> Path:
    path = path.resolve()
    return Path("\\\\?\\" + str(path)) if os.name == "nt" and not str(path).startswith("\\\\?\\") else path

def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()

def blob(raw: bytes) -> str:
    return hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()

def require(value: bool, message: str) -> None:
    if not value:
        raise ValueError(message)

def generate(repo: Path, output: Path) -> list[Path]:
    root = Path(__file__).resolve().parents[1] / "upstream-slices" / "v034-text-lyrics"
    raw_manifest = wide(root / "manifest.json").read_bytes()
    from v046_audio_listen_closure import validate_outputs
    validate_outputs(repo, output)
    require(sha(raw_manifest) == MANIFEST_SHA256, "Text/lyrics manifest changed")
    manifest = json.loads(raw_manifest)
    require(manifest.get("schemaVersion") == 1 and
            manifest.get("upstreamRepository") == "jay3-yy/BiliPai" and
            manifest.get("upstreamCommit") == COMMIT and
            manifest.get("baselineCommit") == BASELINE_COMMIT, "Unknown text/lyrics provenance")
    rows = manifest.get("sources", [])
    require(isinstance(rows, list) and len(rows) == len(SOURCES), "Incomplete text/lyrics source set")
    by_path = {row["originalPath"]: row for row in rows}
    require(set(by_path) == set(SOURCES), "Unknown text/lyrics original source")
    require({path.name for path in root.iterdir()} ==
            {"manifest.json", *[row["file"] for row in rows]}, "Text/lyrics archive leaves changed")
    prepared = []
    for original, emitted in SOURCES.items():
        row = by_path[original]
        require(row["file"] == Path(original).name and row["outputPath"] == emitted,
                "Text/lyrics source mapping changed")
        original_file = wide(root / row["file"])
        require(not original_file.is_symlink(), "Text/lyrics source must be a regular owned file")
        raw = original_file.read_bytes()
        require(len(raw) == row["bytes"] and sha(raw) == row["sha256"] and
                blob(raw) == row["gitBlob"], "Text/lyrics original bytes changed: " + original)
        baseline = canonical_source(repo, original).read_bytes().replace(b"\r\n", b"\n")
        require(sha(baseline) == row["baselineSha256LF"] and
                blob(baseline) == row["baselineGitBlobLF"], "Text/lyrics baseline changed: " + original)
        transformed = baseline
        for edit in row["originalEdits"]:
            offset = edit["offsetBytes"]
            before, after = edit["before"].encode("utf-8"), edit["after"].encode("utf-8")
            require(isinstance(offset, int) and offset >= 0 and
                    transformed[offset:offset + len(before)] == before,
                    "Text/lyrics original delta changed: " + original)
            transformed = transformed[:offset] + after + transformed[offset + len(before):]
        require(transformed == raw.replace(b"\r\n", b"\n"), "Text/lyrics whole original delta changed: " + original)
        inverse = transformed
        for edit in reversed(row["originalEdits"]):
            offset = edit["offsetBytes"]
            before, after = edit["before"].encode("utf-8"), edit["after"].encode("utf-8")
            require(inverse[offset:offset + len(after)] == after, "Text/lyrics inverse changed: " + original)
            inverse = inverse[:offset] + before + inverse[offset + len(after):]
        require(inverse == baseline, "Text/lyrics whole inverse changed: " + original)
        effective_row = dict(row, upstreamCommit=COMMIT)
        if original == 'core-data/src/main/java/com/android/purebilibili/data/model/response/DynamicModels.kt':
            from v035_dynamic_link import upgraded_dynamic_model
            raw, effective_row = upgraded_dynamic_model(raw, effective_row)
        if original == 'app/src/main/java/com/android/purebilibili/feature/audio/lyrics/LyricsDisplayPolicy.kt':
            # v0.4.6 resolveLyricsDisplayDocument (roadmap audio-listen-video-closure): the whole v0.4.6 file.
            from v046_audio_listen_closure import upgraded_lyrics_display_policy
            raw, effective_row = upgraded_lyrics_display_policy(repo, raw, effective_row)
        prepared.append((emitted, raw, effective_row))
    # Validate every input before writing any source. Each output remains the raw Git source.
    generated = []
    for emitted, raw, row in prepared:
        destination = wide(output / emitted)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(raw)
        generated.append(destination)
    proof = {"schemaVersion": 1, "validatedPreviousOriginalCommit": COMMIT,
             "baselineCommit": BASELINE_COMMIT, "manifestSha256": MANIFEST_SHA256,
             "upstreamCommits": sorted({row["upstreamCommit"] for _, _, row in prepared}),
             "sources": [row for _, _, row in prepared]}
    receipt = wide(output / "original-text-lyrics-provenance.json")
    receipt.parent.mkdir(parents=True, exist_ok=True)
    receipt.write_text(json.dumps(proof, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    return generated

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    for generated in generate(args.repo.resolve(), args.output.resolve()):
        print(generated)
