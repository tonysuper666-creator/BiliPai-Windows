"""Keep the upstream JVM lyrics/library sources and extract their platform-neutral queue/loader code verbatim."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

AUDIO = "app/src/main/java/com/android/purebilibili/feature/audio/"
SOURCES = {
    **{AUDIO + "lyrics/" + name + ".kt": "direct" for name in (
        "LyricsModels", "LyricsParser", "LyricsRepository", "LyricsNetworkProviders", "FileLyricsCache",
        "LyricsMatchingPolicy", "LyricsTimelinePolicy", "LyricsWordAlignmentPolicy", "LyricsHighlightPolicy",
        "LyricsDisplayPolicy", "BiliSubtitleLyricsPolicy")},
    AUDIO + "library/ListenVideoLibraryModels.kt": "direct",
    AUDIO + "lyrics/halcyon/HalcyonLyricModels.kt": "direct",
    AUDIO + "lyrics/halcyon/HalcyonLyricTextHelpers.kt": "direct",
    AUDIO + "library/ListenVideoLibraryPolicy.kt": "direct",
    AUDIO + "player/MusicPlaybackContract.kt": "direct",
    AUDIO + "viewmodel/MusicViewModel.kt": "extracted",
    "app/src/main/java/com/android/purebilibili/feature/video/subtitle/BiliSubtitlePolicy.kt": "direct",
    **{"app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/" + name + ".kt": "direct" for name in (
        "MiniPlayerOverlayLayoutPolicy", "MiniPlayerOverlayChromePolicy", "MiniPlayerOverlayPositionPolicy", "MiniPlayerOverlayPollingPolicy")},
    AUDIO + "library/ListenVideoLibraryDataSource.kt": "extracted",
    "app/src/main/java/com/android/purebilibili/feature/video/player/PlaylistManager.kt": "extracted",
    "app/src/main/java/com/android/purebilibili/feature/list/FavoriteCollectionPolicy.kt": "extracted",
    "app/src/main/java/com/android/purebilibili/data/repository/FavoriteRepository.kt": "extracted",
}

def read_source(repo: Path, path: str) -> str:
    return (repo / path).read_text(encoding="utf-8").replace("\r\n", "\n")

def section(text: str, start: str, end: str) -> str:
    if text.count(start) != 1 or text.count(end) != 1:
        raise ValueError("Upstream audio extraction boundary changed: " + start)
    return text[text.index(start):text.index(end)].rstrip() + "\n"

def write(output: Path, relative: str, source: str, original: str, body: str) -> Path:
    file = output / relative
    file.parent.mkdir(parents=True, exist_ok=True)
    header = "// Generated from " + source + "; do not edit.\n"
    header += "// LF-normalized SHA-256: " + hashlib.sha256(original.encode()).hexdigest() + "\n"
    file.write_text(header + body.rstrip() + "\n", encoding="utf-8")
    return file

def generate(repo: Path, output: Path) -> list[Path]:
    generated = []
    source = AUDIO + "viewmodel/MusicViewModel.kt"
    original = read_source(repo, source)
    music = section(original, "internal data class MusicUiState(", "internal class MusicViewModel : ViewModel() {")
    imports = "\n".join(
        line for line in original.splitlines()
        if line in (
            "import com.android.purebilibili.data.model.response.SongInfoData",
            "import com.android.purebilibili.feature.audio.lyrics.LyricDocument",
            "import com.android.purebilibili.feature.audio.lyrics.LyricCandidate",
            "import com.android.purebilibili.feature.audio.player.MusicPlaybackSource",
        )
    )
    if len(imports.splitlines()) != 4:
        raise ValueError("Upstream MusicUiState imports changed")
    generated.append(write(output, "com/android/purebilibili/feature/audio/viewmodel/MusicUiState.kt", source,
        original, "package com.android.purebilibili.feature.audio.viewmodel\n\n" + imports + "\n\n" + music))
    source = "app/src/main/java/com/android/purebilibili/feature/video/player/PlaylistManager.kt"
    original = read_source(repo, source)
    queue = section(original, "/**\n * 播放列表项", "/**\n *  播放列表管理器")
    for required in ("data class PlaylistItem(", "enum class PlayMode", "internal fun advanceShuffleProgress(",
                     "internal fun reconcileShuffleProgressForPlaylistUpdate(", "internal fun resolvePlaylistUiState("):
        if required not in queue:
            raise ValueError("Upstream queue policy changed: " + required)
    generated.append(write(output, "com/android/purebilibili/feature/video/player/DesktopPlaylistPolicies.kt", source,
        original, "package com.android.purebilibili.feature.video.player\n\nimport kotlinx.serialization.Serializable\n\n" + queue))

    source = AUDIO + "library/ListenVideoLibraryDataSource.kt"
    original = read_source(repo, source)
    platform = section(original, "internal class BilibiliListenVideoLibraryDataSource", "internal class ListenVideoLibraryLoader")
    body = original.replace(platform, "").replace("import com.android.purebilibili.data.repository.FavoriteRepository\n", "")
    if "FavoriteRepository." in body or "android." in body.replace("com.android.purebilibili", "upstream"):
        raise ValueError("Upstream library loader gained a platform dependency")
    generated.append(write(output, "com/android/purebilibili/feature/audio/library/DesktopListenVideoLibraryLoader.kt", source, original, body))

    source = "app/src/main/java/com/android/purebilibili/data/repository/FavoriteRepository.kt"
    original = read_source(repo, source)
    error = section(original, "internal class FavoriteRequestException(", "private fun favoriteApiFailure(")
    generated.append(write(output, "com/android/purebilibili/data/repository/DesktopFavoriteRequestException.kt", source,
        original, "package com.android.purebilibili.data.repository\n\n" + error))

    source = "app/src/main/java/com/android/purebilibili/feature/list/FavoriteCollectionPolicy.kt"
    original = read_source(repo, source)
    risk = section(original, "internal fun isFavoriteRiskControlError(", "internal fun shouldApplyFavoriteFolderResult(")
    generated.append(write(output, "com/android/purebilibili/feature/list/DesktopFavoriteRiskPolicy.kt", source, original,
        "package com.android.purebilibili.feature.list\n\nimport com.android.purebilibili.data.repository.FavoriteRequestException\n\n" + risk))
    return generated

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--inventory", action="store_true")
    args = parser.parse_args()
    if args.inventory:
        print(json.dumps([{"path": path, "mode": mode, "features": ["listen-video", "lyrics"],
            "sha256": hashlib.sha256(read_source(args.repo, path).encode()).hexdigest()}
            for path, mode in SOURCES.items()], ensure_ascii=False, indent=2))
    elif args.output:
        for file in generate(args.repo.resolve(), args.output.resolve()): print(file)
    else: parser.error("Pass --inventory or --output")
