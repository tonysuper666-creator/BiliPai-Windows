"""Sole fixed-v029 brand catalog, resources and complete BlueSnow UI producer.

Every original is checked as raw bytes against the immutable archive manifest.
The enum and complete BlueSnow body have separate reversible adaptations.
Original raw sources stay in the reference archive; ReduceMotion uses the existing Windows binding.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import stat
import struct

COMMIT = "a4b77f894d0a2dd26c0b9fc144b8adb88ac05480"
ARCHIVE = Path("desktop/upstream-slices/v029-brand-motion")
MANIFEST_SHA256 = "a1ab155b5842edbbc92d2680e284500dc8e38083bf2b61f9344906b7993b4e83"
BLUE_SNOW = "brand-motion/src/main/java/com/android/purebilibili/core/ui/BlueSnowMaidAnimation.kt"
REDUCE_MOTION = "brand-motion/src/main/java/com/android/purebilibili/core/ui/motion/ReduceMotion.kt"
CATALOG_PATH = "kotlin/com/bilipai/desktop/brand/DesktopMaidAnimation.kt"
CATALOG_SHA256 = "15291a833f8e9d90731bdb5c1f8707a63c9958f395fb6938f6b5b49b1910a6c9"
PROOF_PATH = "brand-motion-source-proof.json"
UI_PATH = "kotlin/com/android/purebilibili/core/ui/BlueSnowMaidAnimation.kt"
UI_PROOF_PATH = "brand-motion-ui-source-proof.json"
_ui_spec = importlib.util.spec_from_file_location("v029_brand_motion_ui", Path(__file__).with_name("v029_brand_motion_ui.py"))
brand_ui = importlib.util.module_from_spec(_ui_spec)
_ui_spec.loader.exec_module(brand_ui)
ENUM_ROW = re.compile(r"    (\w+)\(R\.raw\.(\w+), (\d+)L, R\.drawable\.(\w+)(, loopsWhileVisible = true)?\)(,?)")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def git_blob(data: bytes) -> str:
    return hashlib.sha1(b"blob " + str(len(data)).encode("ascii") + b"\0" + data).hexdigest()


def wide(path: Path) -> Path:
    value = os.path.abspath(path)
    if os.name == "nt" and not value.startswith("\\\\?\\"):
        value = "\\\\?\\" + value
    return Path(value)


def strict_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def reject_reparse(path: Path) -> None:
    for part in (path, *path.parents):
        if not wide(part).exists():
            continue
        info = wide(part).lstat()
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            raise ValueError(f"Reparse/symlink path is not an owned producer input/output: {part}")


def files_under(root: Path) -> dict[str, Path]:
    reject_reparse(root)
    if not wide(root).exists():
        return {}
    if not wide(root).is_dir():
        raise ValueError("Expected directory: " + str(root))
    result = {}
    for directory, dirs, files in os.walk(wide(root)):
        for name in (*dirs, *files):
            reject_reparse(Path(directory) / name)
        for name in files:
            path = Path(directory) / name
            result[path.relative_to(wide(root)).as_posix()] = path
    return result


def checked_inputs(repo: Path) -> tuple[dict, dict[str, bytes]]:
    root = repo.absolute() / ARCHIVE
    reject_reparse(root)
    manifest_bytes = wide(root / "manifest.json").read_bytes()
    if sha256(manifest_bytes) != MANIFEST_SHA256:
        raise ValueError("Fixed brand-motion manifest SHA256 mismatch")
    manifest = json.loads(manifest_bytes, object_pairs_hook=strict_object)
    if manifest["schemaVersion"] != 1 or manifest["fixedUpstreamCommit"] != COMMIT or manifest["hashNormalization"] != "raw":
        raise ValueError("Unknown brand-motion source contract")
    rows = manifest["files"]
    if len(rows) != 30:
        raise ValueError("Expected complete 2 Kotlin + 14 JSON + 14 PNG original archive")
    paths = [row["path"] for row in rows]
    if len(set(paths)) != 30 or set(files_under(root)) != {"manifest.json", *paths}:
        raise ValueError("Original archive has missing, duplicate or unknown files")
    originals = {}
    for row in rows:
        path = row["path"]
        parts = Path(path).parts
        if Path(path).is_absolute() or ".." in parts or "\\" in path or not path.startswith("brand-motion/src/main/"):
            raise ValueError("Unexpected original path")
        if row["commit"] != COMMIT or row["upstreamUrl"] != f"https://raw.githubusercontent.com/jay3-yy/BiliPai/{COMMIT}/{path}":
            raise ValueError("Original source commit/URL mismatch: " + path)
        data = wide(root / path).read_bytes()
        if len(data) != row["bytes"] or sha256(data) != row["sha256Bytes"] or git_blob(data) != row["gitBlob"]:
            raise ValueError("Original raw byte/blob identity mismatch: " + path)
        originals[path] = data
    if set(p for p in originals if p.endswith(".kt")) != {BLUE_SNOW, REDUCE_MOTION}:
        raise ValueError("Incomplete original Kotlin reference")
    return manifest, originals


def counted_replace(text: str, before: str, after: str, label: str, edits: list[dict]) -> str:
    if text.count(before) != 1:
        raise ValueError("Counted source adaptation mismatch: " + label)
    edits.append(dict(label=label, occurrences=1, before=before, after=after))
    return text.replace(before, after, 1)


def inverse(text: str, edits: list[dict]) -> str:
    for edit in reversed(edits):
        if text.count(edit["after"]) != edit["occurrences"]:
            raise ValueError("Counted inverse mismatch: " + edit["label"])
        text = text.replace(edit["after"], edit["before"], edit["occurrences"])
    return text


def make_outputs(manifest: dict, originals: dict[str, bytes]) -> dict[str, bytes]:
    original = originals[BLUE_SNOW].decode("utf-8")
    start = original.index("enum class MaidAnimation(")
    end = original.index("\n}", start) + 2
    original_enum = original[start:end]
    first_row = original_enum.index("    WELCOME(")
    original_header = original_enum[:first_row]
    rows = original_enum[first_row:-2].splitlines()
    parsed = [ENUM_ROW.fullmatch(row) for row in rows]
    if len(parsed) != 15 or not all(parsed):
        raise ValueError("Complete fixed original enum shape changed")
    by_path = {row["path"]: row for row in manifest["files"]}
    outputs = {}
    catalog = []
    edits = []
    header = '''package com.bilipai.desktop.brand

// Derived solely from the complete fixed MaidAnimation enum; raw body retained separately.
// Upstream a4b77f894d0a2dd26c0b9fc144b8adb88ac05480, BlueSnowMaidAnimation blob 5e807b4c72ce72722b0b24ba95aa0a5f88c45dc7.
data class BrandMotionAssetIdentity(
    val jsonFileName: String, val pngFileName: String,
    val jsonBytes: Int, val jsonSha256: String, val pngBytes: Int, val pngSha256: String,
)

enum class DesktopMaidAnimation(
    val durationMs: Long, val loopsWhileVisible: Boolean,
    val runtimeAllowed: Boolean, val asset: BrandMotionAssetIdentity,
) {
'''
    code = counted_replace(original_enum, original_header, header, "catalog/platform identity header", edits)
    expected_inputs = {BLUE_SNOW, REDUCE_MOTION}
    for index, match in enumerate(parsed):
        name, raw, duration, png, loops, comma = match.groups()
        if bool(comma) != (index < 14):
            raise ValueError("Original enum order/terminator mismatch")
        json_path = f"brand-motion/src/main/res/raw/{raw}.json"
        png_path = f"brand-motion/src/main/res/drawable-nodpi/{png}.png"
        expected_inputs.update((json_path, png_path))
        data, picture = originals[json_path], originals[png_path]
        jrow, prow = by_path[json_path], by_path[png_path]
        scene = json.loads(data, object_pairs_hook=strict_object)
        if scene["ddd"] != 0 or scene["fr"] != 60 or scene["w"] != 512 or scene["h"] != 512 or scene["v"] != "5.12.2" or scene["ip"] != 0 or scene["markers"] != []:
            raise ValueError("Unexpected fixed brand scene metadata: " + name)
        if (scene["op"] - scene["ip"]) * 1000 != int(duration) * scene["fr"]:
            raise ValueError("Original enum/scene duration mismatch: " + name)
        images = [asset for asset in scene["assets"] if "p" in asset]
        if len(images) != 1 or images[0]["p"] != png + ".png" or images[0]["u"] != "" or images[0]["e"] != 0:
            raise ValueError("Original scene/PNG pairing mismatch: " + name)
        if picture[:8] != b"\x89PNG\r\n\x1a\n" or struct.unpack(">II", picture[16:24]) != (512, 512):
            raise ValueError("Original PNG dimensions mismatch: " + name)
        allowed = name != "WELCOME"
        catalog.append(dict(animation=name, durationMs=int(duration), loopsWhileVisible=bool(loops), runtimeAllowed=allowed,
                            jsonOriginalPath=json_path, pngOriginalPath=png_path))
        if allowed:
            outputs["resources/brand-motion/" + raw + ".json"] = data
            outputs["resources/brand-motion/" + png + ".png"] = picture
        entry = (f'    {name}({duration}L, {str(bool(loops)).lower()}, {str(allowed).lower()}, '
                 f'BrandMotionAssetIdentity("{raw}.json", "{png}.png", {jrow["bytes"]}, "{jrow["sha256Bytes"]}", '
                 f'{prow["bytes"]}, "{prow["sha256Bytes"]}"))' + ("," if index < 14 else ";"))
        code = counted_replace(code, match.group(), entry, "original enum entry " + name, edits)
    code = counted_replace(code, "\n}", '''

    companion object {
        val runtimeAnimations: List<DesktopMaidAnimation> = entries.filter { it.runtimeAllowed }
    }
}
''', "runtime catalog view; WELCOME excluded", edits)
    if expected_inputs != set(originals) or len(outputs) != 26 or len(edits) != 17:
        raise ValueError("Incomplete fixed archive/catalog or runtime output")
    restored_enum = inverse(code, edits)
    restored_full_source = (original[:start] + restored_enum + original[end:]).encode("utf-8")
    catalog_bytes = code.encode("utf-8")
    if restored_enum != original_enum or restored_full_source != originals[BLUE_SNOW] or sha256(catalog_bytes) != CATALOG_SHA256:
        raise ValueError("Complete enum/full source inverse or foundation catalog identity mismatch")
    outputs[CATALOG_PATH] = catalog_bytes
    proof = dict(schemaVersion=1, fixedUpstreamCommit=COMMIT, manifestSha256Bytes=MANIFEST_SHA256,
                 originalFiles=manifest["files"], catalog=catalog, countedAdaptations=edits,
                 originalEnumSha256Bytes=sha256(original_enum.encode("utf-8")), catalogSha256Bytes=sha256(catalog_bytes),
                 fullSourceInverseSha256Bytes=sha256(restored_full_source),
                 runtimeUniquePairs=13, runtimeEnumIdentities=14, welcome="raw archive only; no runtime JSON or PNG",
                 runtimeOutputs=[dict(path=path, bytes=len(data), sha256Bytes=sha256(data)) for path, data in sorted(outputs.items())],
                 scope="Catalog identity/schema/resources only. BlueSnow UI has its own full-source proof. No renderer or animation UI acceptance is inferred from this proof.")
    outputs[PROOF_PATH] = (json.dumps(proof, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    ui, recipe = brand_ui.adapt_blue_snow_with_recipe(originals[BLUE_SNOW])
    restored_ui = brand_ui.reverse_blue_snow(ui, recipe).encode("utf-8")
    if restored_ui != originals[BLUE_SNOW]:
        raise ValueError("Complete BlueSnow UI inverse mismatch")
    outputs[UI_PATH] = ui.encode("utf-8")
    ui_proof = dict(fixedUpstreamCommit=COMMIT, originalPath=BLUE_SNOW,
                    originalSha256Bytes=sha256(originals[BLUE_SNOW]),
                    fullSourceInverseSha256Bytes=sha256(restored_ui),
                    generatedPath=UI_PATH, generatedSha256Bytes=sha256(outputs[UI_PATH]),
                    countedAdaptations=recipe,
                    scope="Complete original BlueSnow lifecycle body with Windows resources, Canvas, clock and foreground ports. Execution and visible rendering require separate validation.")
    outputs[UI_PROOF_PATH] = (json.dumps(ui_proof, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    from v029_brand_success import runtime_outputs
    business = runtime_outputs()
    if set(business) & set(outputs):
        raise ValueError("Brand success output collision")
    outputs.update(business)
    return outputs


def generate(repo: Path, output: Path) -> dict:
    repo, output = repo.absolute(), output.absolute()
    source = repo / ARCHIVE
    reject_reparse(output)
    if output == repo or output in source.parents or source in output.parents or output == source:
        raise ValueError("Generated output overlaps repository/raw source input")
    manifest, originals = checked_inputs(repo)
    outputs = make_outputs(manifest, originals)
    existing = files_under(output)
    if set(existing) - set(outputs):
        raise ValueError("Generated directory contains unknown files")
    for path, existing_path in existing.items():
        if existing_path.read_bytes() != outputs[path]:
            raise ValueError("Existing generated output differs: " + path)
    for path, data in sorted(outputs.items()):
        target = wide(output / path)
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists():
            target.write_bytes(data)
    return dict(originalFiles=len(originals), countedAdaptations=17, fullSourceInverse=True,
                catalogSha256Bytes=CATALOG_SHA256, runtimeUniquePairs=13, runtimeEnumIdentities=14,
                generatedFiles=len(outputs), blueSnowCountedAdaptations=20, welcomeRuntimeAllowed=False)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(generate(args.repo, args.output), sort_keys=True))


if __name__ == "__main__":
    main()
