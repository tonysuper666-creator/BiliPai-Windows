"""Extract original CDN/first-frame/stall decisions verbatim with a verified player-state platform bridge."""
from __future__ import annotations
from v025_source_paths import canonical_source as _desktop_canonical_source
import argparse
import hashlib
import json
from pathlib import Path

SOURCE = "app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt"
SOURCES = {SOURCE: "extracted"}


def read(repo: Path, path: str) -> str:
    return (_desktop_canonical_source(repo, path)).read_text(encoding="utf-8").replace("\r\n", "\n")


def section(source: str, start: str, end: str) -> str:
    assert source.count(start) == 1 and source.count(end) == 1, "Upstream playback watchdog boundaries changed"
    return source[source.index(start):source.index(end)].rstrip() + "\n"


def generate(repo: Path, output: Path, metadata: Path | None = None) -> None:
    # Validate official constants before publishing any generated source.
    verified = json.loads((metadata or repo / "desktop/third-party/media3-player-state-codes.json").read_text(encoding="utf-8"))
    assert verified["version"] == "1.10.1"
    assert verified["sourceArchiveSha256"] == "472586b0da9837abba8cfd1e3113b81c801fe265f9fadc5ceca4011dba4255ba"
    assert verified["constants"] == {"STATE_IDLE": 1, "STATE_BUFFERING": 2, "STATE_READY": 3, "STATE_ENDED": 4}
    original = read(repo, SOURCE)
    stall = section(original, "private const val PLAYBACK_CDN_FIRST_FRAME_FALLBACK_TIMEOUT_MS", "data class CommentMentionSearchUiState(")
    fallback = section(original, "internal data class PlaybackCdnFallbackState(", "internal fun buildPlaybackAudioUrlCandidates(")
    predicate = section(original, "internal fun shouldFallbackFromCdnRewrite(\n    state: PlaybackCdnFallbackState,\n    playbackReady: Boolean\n)",
        "internal fun hostForPlaybackLog(")
    # The second overload remains within the same unchanged original slice.
    assert predicate.count("internal fun shouldFallbackFromCdnRewrite(") == 2
    target = output / "com/android/purebilibili/feature/video/viewmodel/DesktopPlaybackWatchdogPolicies.kt"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("// Generated from " + SOURCE + "; do not edit.\n// LF SHA-256: " + hashlib.sha256(original.encode()).hexdigest() +
        "\npackage com.android.purebilibili.feature.video.viewmodel\n\n" +
        "import com.bilipai.desktop.player.platform.DesktopMedia3PlayerStates as Player\n\n" + stall + "\n" + fallback + "\n" + predicate +
        "\n// Platform aliases expose the unchanged private upstream durations.\n" +
        "internal fun desktopCdnFirstFrameTimeoutMs(): Long = PLAYBACK_CDN_FIRST_FRAME_FALLBACK_TIMEOUT_MS\n" +
        "internal fun desktopPlaybackStallTimeoutMs(): Long = PLAYBACK_STALL_RECOVERY_TIMEOUT_MS\n", encoding="utf-8")
    # No Media3 runtime: verified against the same official source archive used for error recovery.
    shim = output / "com/bilipai/desktop/player/platform/DesktopMedia3PlayerStates.kt"
    shim.parent.mkdir(parents=True, exist_ok=True)
    values = "\n".join("    const val " + name + ": Int = " + str(value) for name, value in verified["constants"].items())
    shim.write_text("// Verified from " + verified["sourceUrl"] + "\n// Source archive SHA-256: " + verified["sourceArchiveSha256"] +
        "\npackage com.bilipai.desktop.player.platform\n\ninternal object DesktopMedia3PlayerStates {\n" +
        values + "\n}\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--metadata", type=Path)
    parser.add_argument("--inventory", action="store_true")
    args = parser.parse_args()
    if args.inventory:
        print(json.dumps([{"path": SOURCE, "mode": "extracted", "features": ["playback", "cdn", "watchdogs"],
            "sha256": hashlib.sha256(read(args.repo, SOURCE).encode()).hexdigest()}], indent=2))
    elif args.output:
        generate(args.repo, args.output, args.metadata)
    else:
        parser.error("Pass --inventory or --output")
