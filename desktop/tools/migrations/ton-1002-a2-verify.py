#!/usr/bin/env python3
"""Exercise the real A2 recipe in a disposable checkout, without Gradle or GUI."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile

repo = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("migration", repo / "desktop/tools/migrate-video-batch.py")
migration = importlib.util.module_from_spec(spec)
spec.loader.exec_module(migration)
recipe = json.loads(Path(__file__).with_name("ton-1002-a2-resume-feedback.json").read_text())


def run(root, *args, **kwargs):
    return subprocess.run(args, cwd=root, capture_output=True, check=True, **kwargs)


with tempfile.TemporaryDirectory(prefix="a2-verify-") as directory:
    root = Path(directory).resolve()
    checkout = root / "checkout"
    run(repo, "git", "clone", "--shared", "--no-checkout", str(repo), str(checkout))
    paths = ["desktop/build.gradle.kts", "desktop/tools", "desktop/upstream-slices",
             "desktop/upstream-core/legacy-root.commit", "desktop/upstream-core/transition/patches",
             "desktop/upstream-core/patches"] + [item["source"] for item in recipe["files"]]
    run(checkout, "git", "checkout", recipe["desktopBaseCommit"], "--", *paths)
    changes, _ = migration.plan(checkout, recipe)
    bundle = migration.export_patch(changes)
    run(checkout, "git", "apply", "--check", "-", input=bundle)
    run(checkout, "git", "apply", "-", input=bundle)
    again, _ = migration.plan(checkout, recipe)
    assert all(old == new for old, new in again.values())
    assert migration.export_patch(again) == b""
    migration.apply(checkout, again)

    # Re-render independently; consume every emitted patch against real Kotlin.
    legacy = root / ".legacy-root"
    legacy.mkdir()
    pin = (checkout / "desktop/upstream-core/legacy-root.commit").read_text().splitlines()
    pin = [line.strip() for line in pin if line.strip() and not line.startswith("#")][0]
    archive = root / "legacy.tar"
    run(checkout, "git", "archive", "--format=tar", "-o", str(archive), pin,
        "app/src/main", "desktop/tools", "desktop/upstream-slices", "desktop/upstream-sources.json")
    with tarfile.open(archive) as snapshot:
        snapshot.extractall(legacy, filter="data")
    scripts = {
        "original-video-detail-holder-full": "extract-upstream-video-detail-holder.py",
        "original-video-player-section-full": "extract-upstream-video-player-section-full.py",
    }
    for producer, script in scripts.items():
        run(checkout, "python3", str(checkout / "desktop/tools" / script), str(legacy), str(root / producer))
    originals = root / "originals"
    originals.mkdir()
    for item in recipe["files"]:
        source = originals / item["source"]
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_bytes((checkout / item["source"]).read_bytes())
        patch = checkout / ("desktop/upstream-core/patches/" + item["source"] + ".patch")
        run(originals, "git", "apply", "--check", str(patch))
        run(originals, "git", "apply", str(patch))
    for item in recipe["transitions"]:
        generated = root / item["producer"]
        assert migration.sha((generated / item["identity"]).read_bytes()) == item["generatedSha256LF"]
        patch = checkout / "desktop/upstream-core/transition/patches" / item["producer"] / (item["identity"] + ".patch")
        run(generated, "git", "apply", "--check", str(patch))
        run(generated, "git", "apply", str(patch))

    relative = "desktop/upstream-core/transition/patches/" + recipe["transitions"][0]["producer"] + "/" + recipe["transitions"][0]["identity"] + ".patch"
    victim = checkout / relative
    victim.write_bytes(victim.read_bytes() + b"concurrent adaptation\n")
    before = {name: (checkout / name).read_bytes() for name in changes}
    try:
        migration.plan(checkout, recipe)
        raise AssertionError("conflict was accepted")
    except migration.Conflict as error:
        assert "Existing transition differs" in str(error)
    assert before == {name: (checkout / name).read_bytes() for name in changes}
    print(json.dumps(dict(
        batch=recipe["id"], desktopBase=recipe["desktopBaseCommit"], patchSha256=migration.sha(bundle),
        plannedFiles=sum(old != new for old, new in changes.values()), isolatedApply="passed",
        repeatedChangedFiles=sum(old != new for old, new in again.values()),
        realKotlinPatchConsumptions=len(recipe["files"]) + len(recipe["transitions"]),
        conflict="rejected; all six targets unchanged", productionApplied=False,
        consumerCompilation="unverified", verifyUniqueClassNames="unverified",
        perIdentityJvmComparison="unverified", animationLifecycleBehavior="unverified"), indent=2))
