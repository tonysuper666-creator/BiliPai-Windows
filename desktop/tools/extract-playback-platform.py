"""Preserve upstream recovery policy with five verified Media3 error constants."""
from __future__ import annotations
from v025_source_paths import canonical_source as _desktop_canonical_source
import argparse
import hashlib
import json
from pathlib import Path

SOURCE = "app/src/main/java/com/android/purebilibili/feature/video/state/PlayerErrorRecoveryPolicy.kt"
SOURCES = {SOURCE: "extracted"}

def read(repo: Path, path: str) -> str:
    return (_desktop_canonical_source(repo, path)).read_text(encoding="utf-8").replace("\r\n", "\n")

def generate(repo: Path, output: Path) -> None:
    source = read(repo, SOURCE)
    old = "import androidx.media3.common.PlaybackException"
    assert source.count(old) == 1, "Upstream Media3 recovery import drifted"
    target = output / "com/android/purebilibili/feature/video/state/PlayerErrorRecoveryPolicy.kt"
    target.parent.mkdir(parents=True, exist_ok=True)
    digest = hashlib.sha256(source.encode()).hexdigest()
    target.write_text(f"// Source: {SOURCE}\n// LF SHA-256: {digest}\n" + source.replace(old,
        "import com.bilipai.desktop.player.platform.DesktopMedia3ErrorCodes as PlaybackException"), encoding="utf-8")
    verified = json.loads((repo / "desktop/third-party/media3-error-codes.json").read_text(encoding="utf-8"))
    assert verified["version"] == "1.10.1"
    assert verified["sourceArchiveSha256"] == "472586b0da9837abba8cfd1e3113b81c801fe265f9fadc5ceca4011dba4255ba"
    assert verified["constants"] == {"ERROR_CODE_IO_UNSPECIFIED": 2000,
        "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED": 2001, "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT": 2002,
        "ERROR_CODE_IO_BAD_HTTP_STATUS": 2004, "ERROR_CODE_IO_FILE_NOT_FOUND": 2005}
    shim = output / "com/bilipai/desktop/player/platform/DesktopMedia3ErrorCodes.kt"
    shim.parent.mkdir(parents=True, exist_ok=True)
    body = "\n".join(f"    const val {name}: Int = {value}" for name, value in verified["constants"].items())
    shim.write_text("// Verified from " + verified["sourceUrl"] + "\n// Source archive SHA-256: " + verified["sourceArchiveSha256"] +
        "\npackage com.bilipai.desktop.player.platform\n\ninternal object DesktopMedia3ErrorCodes {\n" + body + "\n}\n", encoding="utf-8")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--inventory", action="store_true")
    args = parser.parse_args()
    if args.inventory:
        print(json.dumps([{"path": SOURCE, "mode": "extracted", "features": ["playback", "recovery"],
            "sha256": hashlib.sha256(read(args.repo, SOURCE).encode()).hexdigest()}], indent=2))
    elif args.output:
        generate(args.repo, args.output)
    else:
        parser.error("Pass --output or --inventory")
