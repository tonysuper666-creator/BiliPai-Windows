"""Emit the complete pinned v027 hot-danmaku UI/policy with reversible JVM ports.

This explicit source slice does not advance or weaken the v025 canonical catalog.
It reads only checked-in raw original blobs; no Git/network/build dependency exists.
"""
from pathlib import Path
import argparse
import hashlib
import json

COMMIT = "e5a6b59a69ed2de9ea4e69dc7675ea05de495ebf"
ARCHIVE = Path("desktop/upstream-slices/v027-hot-danmaku")
BASE = "app/src/main/java/com/android/purebilibili/"
POLICY = BASE + "feature/video/danmaku/HotDanmakuPolicy.kt"
BAR = BASE + "feature/video/ui/overlay/HotDanmakuBar.kt"
CONFIRMATION = BASE + "feature/video/ui/components/DanmakuSameSendConfirmation.kt"
COUNT = BASE + "core/ui/components/AnimatedCountText.kt"
TEST = "app/src/test/java/com/android/purebilibili/feature/video/danmaku/HotDanmakuPolicyTest.kt"
PINS = {
    POLICY: "2be4cad461dfd9ad04eb3b37d762055deaa10ad6cb3c3c0c6702db11d78ed8ee",
    BAR: "cec2fd00ba5e15a8a5830df30302c59144a75f2c8edd5fde8a890daaf9b8fb5b",
    CONFIRMATION: "23b033faa6b20e884e8fd319204b221d28aa3ca680874d108ee5ca9406f5a665",
    COUNT: "c00947788875769a3410ca345c9b9334cf796ad0328a1e48cfa0038d060d92a9",
    TEST: "bf7e4cb2058777508d6724a02cff72ddc54057dbe938355504863428fb472110",
}


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def wide(path):
    import os
    path = Path(path).resolve()
    return Path("\\\\?\\" + str(path)) if os.name == "nt" else path


def checked_inputs(repo):
    root = Path(repo) / ARCHIVE
    manifest = json.loads(wide(root / "manifest.json").read_bytes())
    if manifest.get("schemaVersion") != 1 or manifest.get("fixedUpstreamCommit") != COMMIT:
        raise ValueError("Unknown hot-danmaku original-source manifest")
    rows = manifest.get("files", [])
    if len(rows) != len(PINS) or {row["originalPath"] for row in rows} != set(PINS):
        raise ValueError("Hot-danmaku manifest does not describe the exact fixed source set")
    originals = {}
    for row in rows:
        path = row["originalPath"]
        if row["archiveFile"] != Path(path).name or row["sha256Bytes"] != PINS[path]:
            raise ValueError("Original archive identity changed: " + path)
        raw = wide(root / row["archiveFile"]).read_bytes()
        if digest(raw) != PINS[path] or len(raw) != row["bytes"]:
            raise ValueError("Pinned original bytes changed: " + path)
        normalized = raw.replace(b"\r\n", b"\n")
        if digest(normalized) != row["sha256LF"]:
            raise ValueError("Original normalized identity changed: " + path)
        originals[path] = normalized.decode("utf-8")
    return originals


def read(repo, path):
    return checked_inputs(repo)[path]


def adapt(text, before, after, changes):
    if text.count(before) != 1:
        raise ValueError("Expected exactly one original platform clause: " + repr(before))
    offset = text.index(before)
    changes.append({"offset": offset, "before": before, "after": after, "occurrenceCount": 1})
    return text[:offset] + after + text[offset + len(before):]


def restore(adapted, changes):
    for change in reversed(changes):
        offset = change["offset"]
        if adapted[offset:offset + len(change["after"])] != change["after"]:
            raise ValueError("Inverse adapter mismatch")
        adapted = adapted[:offset] + change["before"] + adapted[offset + len(change["after"]):]
    return adapted


def port(path, original):
    changes = []
    text = original
    if path == BAR:
        replacements = [
            ("import androidx.compose.ui.platform.LocalContext", "import com.bilipai.desktop.ui.LocalDesktopHotDanmakuBindings as LocalContext"),
            ("import androidx.lifecycle.compose.collectAsStateWithLifecycle", "// Flow collection belongs to the exact mounted Windows source composition."),
            ("import com.android.purebilibili.core.store.SettingsManager", "// The required Root bindings carry the existing Store's expanded-mode flow."),
            ("import androidx.media3.common.Player", "import com.bilipai.desktop.ui.DesktopHotDanmakuPlayer as Player"),
            ("SettingsManager.getHotDanmakuExpandedMode(context)", "context.expandedMode"),
            (".collectAsStateWithLifecycle(initialValue = false)", ".collectAsState(initial = false)"),
            ("import kotlinx.coroutines.isActive", "import kotlinx.coroutines.isActive\nimport com.bilipai.desktop.ui.desktopCommandHitRegion"),
            (".then(if (expandedMode) Modifier.horizontalScroll(rowScrollState) else Modifier),", ".then(if (expandedMode) Modifier.horizontalScroll(rowScrollState) else Modifier)\n                    .then(if (visibleItems.isNotEmpty()) Modifier.desktopCommandHitRegion(\"hot-danmaku-visible-row\") else Modifier),"),
        ]
    elif path == CONFIRMATION:
        replacements = [
            ("import com.android.purebilibili.core.ui.AppAlertDialog", "import com.bilipai.desktop.ui.DesktopHotDanmakuAlertDialog as AppAlertDialog"),
        ]
    elif path == COUNT:
        replacements = [
            ("import androidx.compose.ui.graphics.asComposeRenderEffect", "import androidx.compose.ui.graphics.BlurEffect\nimport androidx.compose.ui.graphics.TileMode"),
            ("import android.os.Build", "import com.bilipai.desktop.ui.desktopDetailRenderEffectsSupported"),
            ("import android.graphics.RenderEffect", "// The public Compose/Skia blur effect replaces the Android RenderEffect factory."),
            ("import android.graphics.Shader", "// TileMode.Decal preserves the original transparent out-of-bounds sampling."),
            ("Build.VERSION.SDK_INT >= Build.VERSION_CODES.S", "desktopDetailRenderEffectsSupported()"),
            ("RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL).asComposeRenderEffect()", "BlurEffect(radiusX = radius, radiusY = radius, edgeTreatment = TileMode.Decal)"),
        ]
    else:
        replacements = []
    for before, after in replacements:
        text = adapt(text, before, after, changes)
    if restore(text, changes) != original:
        raise ValueError("Whole original-file inverse failed: " + path)
    return text, changes


def generated_relative(path):
    prefix = "app/src/test/java/" if path == TEST else "app/src/main/java/"
    return Path(path.removeprefix(prefix))


def inventory(repo):
    originals = checked_inputs(repo)
    return [{"path": path, "sha256": digest(text.encode("utf-8")),
             "sha256Bytes": PINS[path], "upstreamCommit": COMMIT,
             "mode": "fixed-v027-complete-original-test" if path == TEST else "fixed-v027-complete-original-ui-policy",
             "features": ["hot-danmaku-top-center-count"]}
            for path, text in originals.items()]


def generate(repo, output, tests_output=None):
    originals = checked_inputs(repo)
    # Validate every full-file transform before publishing any output.
    transformed = {path: port(path, text) for path, text in originals.items()}
    records = []
    paths = []
    for path, (adapted, changes) in transformed.items():
        if path == TEST and tests_output is None:
            continue
        root = Path(tests_output) if path == TEST else Path(output)
        relative = generated_relative(path)
        target = wide(root / relative)
        target.parent.mkdir(parents=True, exist_ok=True)
        prefix = "// Fixed original " + COMMIT + ":" + path + "\n// Complete original file; only explicit reversible Windows platform ports follow.\n"
        generated = prefix + adapted
        target.write_text(generated, encoding="utf-8", newline="\n")
        paths.append(Path(root) / relative)
        records.append({"source": path, "upstreamCommit": COMMIT,
                        "originalRawSha256": PINS[path],
                        "originalFullFileSha256LF": digest(originals[path].encode("utf-8")),
                        "adaptedFullFileSha256LF": digest(adapted.encode("utf-8")),
                        "generatedSha256Bytes": digest(generated.encode("utf-8")),
                        "generatedPath": relative.as_posix(), "testSource": path == TEST,
                        "generatedPrefix": prefix, "changes": changes,
                        "completeFileInverseExact": restore(adapted, changes) == originals[path]})
    proof = {"schemaVersion": 1, "fixedUpstreamCommit": COMMIT,
             "overallCanonicalBaselineAdvanced": False,
             "originalRawArchiveVerified": True, "completeFilesInverseExact": True,
             "compiled": False, "actualMainRendered": False, "liveBilibiliActionsTested": False,
             "files": records}
    proof_path = wide(Path(output) / "source-proof.json")
    proof_path.parent.mkdir(parents=True, exist_ok=True)
    proof_path.write_text(json.dumps(proof, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    return paths


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--repo", type=Path, required=True)
    cli.add_argument("--output", type=Path)
    cli.add_argument("--tests-output", type=Path)
    cli.add_argument("--inventory", action="store_true")
    args = cli.parse_args()
    if args.inventory:
        print(json.dumps(inventory(args.repo.resolve()), ensure_ascii=False, indent=2))
    if args.output:
        print("Generated", len(generate(args.repo.resolve(), args.output.resolve(), args.tests_output)))
