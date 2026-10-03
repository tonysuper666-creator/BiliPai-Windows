"""Audit actual upstream source adoption and generated API coverage without making network requests.

Source/API coverage describes provenance, not successful end-to-end feature parity.
The feature acceptance matrix remains desktop/PARITY.md.
"""
from __future__ import annotations
from v025_source_paths import canonical_source as _desktop_canonical_source

import argparse
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess


def audit(repo: Path) -> dict:
    manifest = json.loads((repo / "desktop/upstream-sources.json").read_text(encoding="utf-8-sig"))
    entries = manifest["sources"]
    resources = manifest.get("resources", [])
    if len({item["path"] for item in entries}) != len(entries):
        raise ValueError("Duplicate upstream provenance entry")
    if len({item["path"] for item in resources}) != len(resources):
        raise ValueError("Duplicate upstream resource provenance entry")
    for item in entries + resources:
        relative = Path(item["path"])
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError("Source path escapes repository")
        normalization = item.get("hashNormalization", "lf")
        if normalization not in {"lf", "raw"}:
            raise ValueError("Unknown upstream hash normalization: " + item["path"])
        contents = (_desktop_canonical_source(repo, relative)).read_bytes()
        if normalization == "lf":
            contents = contents.replace(b"\r\n", b"\n")
        if hashlib.sha256(contents).hexdigest() != item["sha256"]:
            raise ValueError("Unreviewed source change: " + item["path"])

    spec = importlib.util.spec_from_file_location("bilipai_parity_structure", repo / "desktop/tools/sync-upstream.py")
    parser = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(parser)

    def interfaces(source: str) -> dict[str, dict]:
        tokens = parser.kotlin_tokens(source)
        result = {}
        for marker in re.finditer(r"(?m)^interface\s+(\w+)\s*\{", source):
            start, end = parser.kotlin_structure(tokens, "interface", marker.group(1))
            body = source[tokens[start][1]:tokens[end][2]]
            result[marker.group(1)] = {
                "sha256": hashlib.sha256(body.encode()).hexdigest(),
                "methods": re.findall(r"\bsuspend\s+fun\s+(\w+)\s*\(", body),
            }
        return result

    source = (_desktop_canonical_source(repo, "app/src/main/java/com/android/purebilibili/core/network/ApiClient.kt")).read_text(encoding="utf-8")
    generated_path = repo / "desktop/build/generated/api/com/android/purebilibili/core/network/DesktopUpstreamApi.kt"
    if not generated_path.is_file():
        raise ValueError("Build-generated API is missing; run compileKotlin first")
    original = interfaces(source)
    generated = interfaces(generated_path.read_text(encoding="utf-8"))
    if original != generated:
        raise ValueError("Generated Windows API interfaces differ from the original declarations")
    response_root = repo / "app/src/main/java/com/android/purebilibili/data/model/response"
    adopted = {item["path"] for item in entries}
    response_files = sorted(path.relative_to(repo).as_posix() for path in response_root.glob("*.kt"))
    feature_root = repo / "app/src/main/java/com/android/purebilibili/feature"
    features = {}
    for folder in sorted(path for path in feature_root.iterdir() if path.is_dir()):
        paths = sorted(path.relative_to(repo).as_posix() for path in folder.rglob("*.kt"))
        features[folder.name] = {
            "upstreamKotlinFiles": len(paths),
            "adoptedSourceFiles": [path for path in paths if path in adopted],
            "unadoptedPolicyCandidates": [path for path in paths if path.endswith("Policy.kt") and path not in adopted],
        }
    return {
        "upstreamTag": manifest["upstreamTag"], "upstreamCommit": manifest["upstreamCommit"],
        "windowsSourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip(),
        "workingTreeDirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=repo, text=True).strip()),
        "adoptedSourceCount": len(entries), "adoptionModes": dict(Counter(item["mode"] for item in entries)),
        "adoptedResourceCount": len(resources), "resourceHashesVerified": True,
        "apiInterfacesVerbatim": True, "apiInterfaces": original,
        "apiMethodCount": sum(len(item["methods"]) for item in original.values()),
        "responseFiles": {"upstreamCount": len(response_files), "adoptedCount": len([p for p in response_files if p in adopted]),
            "unadopted": [p for p in response_files if p not in adopted]},
        "featureSources": features,
        "acceptanceMatrix": "desktop/PARITY.md",
        "fullFunctionalParityVerified": False,
    }


if __name__ == "__main__":
    command = argparse.ArgumentParser(description=__doc__)
    command.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    command.add_argument("--output", type=Path)
    arguments = command.parse_args()
    report = audit(arguments.repo.resolve())
    if arguments.output:
        arguments.output.parent.mkdir(parents=True, exist_ok=True)
        arguments.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key not in {"apiInterfaces", "featureSources"}}, ensure_ascii=True, indent=2))
