"""Reconcile omitted package notices against immutable upstream trees with identical Rust sources.

Read only: output goes to a separate review directory, never the live native/package inputs.
The archive bytes and published source checksum are all checked before using the raw notice.
"""
from pathlib import Path
import argparse
import hashlib
import io
import json
import tarfile
import zipfile

PINS = {
    "difflib": {"version": "0.4.0", "repository": "DimaKudosh/difflib",
        "commit": "f035fb8e656f27119e23eca9d5b996df14f3885e",
        "sourceZipSha256": "86d4bfb6b0f54256d843fb1d0ce247ce3ee443548e557429f3cd5ecaedc2cc33",
        "licenseSha256": "6725d1437fc6c77301f2ff0e7d52914cf4f9509213e1078dc77d9356dbe6eac5"},
    "simd_helpers": {"version": "0.1.0", "repository": "lu-zero/simd_helpers",
        "commit": "82040194cd05affb060bf94d6f19f82a771d07fb",
        "sourceZipSha256": "93fc50fadbde22bbf7b10ae3d0f626153cfc0ca423c07f36a7ab79ff663d8996",
        "licenseSha256": "d69f24ad84ec2ade64c0b68bdb31b41170e997b158370342056918329cc9af1e"},
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def verify_source(name: str, pin: dict, crate_bytes: bytes, crate_sha: str, source_bytes: bytes, notice: bytes) -> list[dict]:
    if sha256(crate_bytes) != crate_sha:
        raise ValueError("Published crate differs from Cargo.lock source checksum")
    if sha256(source_bytes) != pin["sourceZipSha256"]:
        raise ValueError("Immutable upstream source archive checksum mismatch")
    if sha256(notice) != pin["licenseSha256"]:
        raise ValueError("Raw upstream notice checksum mismatch")
    rows = []
    with zipfile.ZipFile(io.BytesIO(source_bytes)) as source, tarfile.open(fileobj=io.BytesIO(crate_bytes), mode="r:gz") as crate:
        members = source.infolist()
        if len(members) > 256 or sum(item.file_size for item in members) > 2 * 1024 * 1024:
            raise ValueError("Notice source tree exceeds the audited limit")
        prefix = members[0].filename
        if source.read(prefix + "LICENSE") != notice:
            raise ValueError("Notice bytes differ from the matched source tree")
        for member in crate.getmembers():
            if not member.isfile() or not member.name.endswith(".rs"):
                continue
            if member.size > 2 * 1024 * 1024:
                raise ValueError("Published source file exceeds audit bound")
            relative = member.name.split("/", 1)[1]
            published = crate.extractfile(member).read()
            upstream = source.read(prefix + relative)
            if published != upstream:
                raise ValueError("Published Rust source differs from the licensed tree: " + relative)
            rows.append({"path": relative, "sha256": sha256(published)})
    if not rows:
        raise ValueError("No published Rust source files were matched")
    return rows


def prepare(repo: Path, audit: Path, output: Path) -> dict:
    catalog = repo / "desktop/third-party/ffmpeg/SOURCES.json"
    if output.resolve().is_relative_to(catalog.parent.resolve()) or output.resolve().is_relative_to((repo / "desktop/native").resolve()):
        raise ValueError("Review output must not overwrite live native/package inputs")
    document = json.loads(catalog.read_text(encoding="utf-8"))
    inventory = document["dependencyLicenseInventory"]
    packages = inventory["rav1eLockedCrates"]["packages"]
    new_records = []
    (output / "licenses").mkdir(parents=True, exist_ok=True)
    for name, pin in PINS.items():
        entry = next(item for item in packages if item["name"] == name and item["version"] == pin["version"])
        crate_path = audit / f"{name}-{pin['version']}.crate"
        if not crate_path.exists(): crate_path = repo / "desktop/build/downloads/ffmpeg-rust-crates" / crate_path.name
        crate = crate_path.read_bytes()
        source = (audit / f"{name}-{pin['commit']}.zip").read_bytes()
        notice_path = audit / (pin["repository"].replace("/", "-") + "-LICENSE")
        if not notice_path.exists():
            notice_path = catalog.parent / "licenses" / f"rav1e-crate-{name}-{pin['version']}-upstream-LICENSE"
        notice = notice_path.read_bytes()
        matches = verify_source(name, pin, crate, entry["sourceArchiveSha256"], source, notice)
        filename = f"rav1e-crate-{name}-{pin['version']}-upstream-LICENSE"
        (output / "licenses" / filename).write_bytes(notice)
        record = {"path": "licenses/" + filename,
            "sourceUrl": "https://raw.githubusercontent.com/" + pin["repository"] + "/" + pin["commit"] + "/LICENSE",
            "sha256": pin["licenseSha256"], "matchedSourceArchiveUrl": "https://codeload.github.com/" + pin["repository"] + "/zip/" + pin["commit"],
            "matchedSourceArchiveSha256": pin["sourceZipSha256"], "matchedPublishedRustSources": matches}
        new_records.append(record)
        entry.update({"licensePaths": [record["path"]], "status": "collected",
            "noticeSourceCommit": pin["commit"], "noticeResolution": "Published crate omits the notice. All published Rust files exactly match this immutable upstream tree containing the original raw MIT notice."})
    replaced = {item["path"] for item in new_records}
    document["licenseFiles"] = [item for item in document["licenseFiles"] if item["path"] not in replaced] + new_records
    inventory["complete"] = False
    inventory["scope"] = "Direct recipes, known transitive notices, exact embedded Rust runtime and all registry Cargo.lock notices are collected. Missing published MIT notices were matched to immutable upstream trees with byte-identical Rust files. Complete corresponding source-distribution reconciliation remains before public release."
    (output / "SOURCES.json").write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    return {"newNotices": len(new_records), "registryPackages": len(packages),
        "unresolvedPackages": [(item["name"], item["version"]) for item in packages if item["status"] != "collected"],
        "complete": inventory["complete"]}


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--repo", type=Path, required=True)
    cli.add_argument("--audit", type=Path, default=Path(__file__).parent / "tests/fixtures/ffmpeg-notices")
    cli.add_argument("--output", type=Path, required=True)
    args = cli.parse_args()
    print(json.dumps(prepare(args.repo.resolve(), args.audit.resolve(), args.output.resolve()), indent=2))
