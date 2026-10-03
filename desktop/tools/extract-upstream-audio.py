"""Keep the upstream JVM lyrics/library sources and extract their platform-neutral queue/loader code verbatim."""
from __future__ import annotations
from v025_source_paths import canonical_source as _desktop_canonical_source

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
    'core-data/src/main/java/com/android/purebilibili/data/repository/FavoriteRepository.kt': "extracted",
}

def read_source(repo: Path, path: str) -> str:
    return (_desktop_canonical_source(repo, path)).read_text(encoding="utf-8").replace("\r\n", "\n")

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
    output = Path(str(output) if str(output).startswith("\\\\?\\") else "\\\\?\\" + str(output.absolute()))
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
    # Select only the original MusicViewModel lyrics domain consumed by BV audio
    # mode. MusicUiState and the unchanged original Repository/providers/cache
    # retain their existing sole owners; no AU player or MiniPlayer is emitted.
    import importlib.util
    def module(name, path):
        spec = importlib.util.spec_from_file_location(name, path)
        value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value)
        return value
    tokens = module("audio_lyrics_tokens", repo / "desktop/tools/sync-upstream.py")
    selector = module("audio_lyrics_selection", repo / "desktop/tools/extract-appearance-platform.py")
    assert hashlib.sha256(original.encode()).hexdigest() == 'e5f8b1a5375cd071ac7114fec95624ca64365cb808a16758292a816d9f88aa25'
    inner = original[original.index("internal class MusicViewModel : ViewModel() {") + len("internal class MusicViewModel : ViewModel() {"):original.rfind("}")]
    names = ["_uiState", "uiState", "lyricsRepository", "lyricsJob", "lyricsOffsetSaveJob",
             "lastLyricsCacheKey", "lastLyricsQuery", "lastBilibiliLyrics", "adjustLyricsOffset",
             "loadLyricsForVideo", "retryLyrics", "searchLyrics", "selectLyricsCandidate", "loadLyrics"]
    retained = selector.declarations(tokens, inner, names)
    body = retained; adaptations = []
    def adapt(before, after, label, all=False):
        nonlocal body
        count = body.count(before)
        assert count > 0 and (all or count == 1), (label, count)
        import re
        offsets = [m.start() for m in re.finditer(re.escape(before), body)]
        for at in reversed(offsets):
            adaptations.append({"label":label,"index":at,"before":before,"after":after})
            body = body[:at] + after + body[at + len(before):]
    adapt("private var lyricsRepository: LyricsRepository? = null", "private var lyricsRepository: LyricsRepository? = suppliedRepository", "same existing Repository instead of Android player initialization")
    for name, params in [("adjustLyricsOffset", "offsetMs: Long"), ("retryLyrics", ""), ("searchLyrics", "title: String"), ("selectLyricsCandidate", "index: Int")]:
        adapt("fun " + name + "(" + params + ")", "fun " + name + "(lease: DesktopOriginalMusicSourceLease" + (", " + params if params else "") + ")", "fixed actual Root subject lease captured before " + name)
    adapt("fun loadLyricsForVideo(\n", "fun loadLyricsForVideo(\n        lease: DesktopOriginalMusicSourceLease,\n", "fixed actual Root subject lease before launch")
    adapt("private suspend fun loadLyrics(\n", "private suspend fun loadLyrics(\n        request: DesktopOriginalMusicLyricsRequest,\n", "lexical launched Job and subject in original private load")
    adapt("viewModelScope.launch {\n", "viewModelScope.launch {\n            val request = DesktopOriginalMusicLyricsRequest(lease, kotlinx.coroutines.currentCoroutineContext(), isOpen)\n            request.check()\n", "capture launched caller Job before original IO", True)
    adapt("_uiState.update {", "request.updateState(_uiState) {", "original UI updates enter only current Job/subject short publication", True)
    adapt("request.updateState(_uiState) { it.copy(lyricsDocument = adjusted) }", "lease.updateState(_uiState, isOpen) { it.copy(lyricsDocument = adjusted) }", "original synchronous offset action checks binding lifetime inside same subject publication")
    adapt("        val document = _uiState.value.lyricsDocument ?: return", "        if (lastLyricsLease !== lease) return\n        val document = _uiState.value.lyricsDocument ?: return", "a queued new-source load cannot adjust the previous source document")
    adapt("        val cacheKey = lastLyricsCacheKey ?: return", "        val cacheKey = lastLyricsCacheKey ?: return\n        if (lastLyricsLease !== lease) return", "private query/cache context belongs to the exact captured lyrics lease", True)
    adapt("loadLyrics(\n                cacheKey =", "loadLyrics(\n                request = request,\n                cacheKey =", "same request carried to original automatic load")
    adapt("loadLyrics(cacheKey, query, lastBilibiliLyrics, forceRefresh = true)", "loadLyrics(request, cacheKey, query, lastBilibiliLyrics, forceRefresh = true)", "same captured request for original retry")
    adapt("        lastLyricsCacheKey = cacheKey\n        lastLyricsQuery = query\n        lastBilibiliLyrics = bilibiliLyrics", "        request.commit {\n            lastLyricsCacheKey = cacheKey\n            lastLyricsQuery = query\n            lastBilibiliLyrics = bilibiliLyrics\n            lastLyricsLease = request.sourceLease\n        }", "original private query context publishes under same current subject")
    adapt("            lastLyricsCacheKey = cacheKey\n            request.updateState(_uiState) { it.copy(isLyricsSearching = false, lyricCandidates = candidates) }", "            request.commit { lastLyricsCacheKey = cacheKey }\n            request.updateState(_uiState) { it.copy(isLyricsSearching = false, lyricCandidates = candidates) }", "manual search cannot publish a retired cache key")
    inverse = body
    for row in reversed(adaptations):
        at = row["index"]; after = row["after"]
        assert inverse[at:at+len(after)] == after, row["label"]
        inverse = inverse[:at] + row["before"] + inverse[at+len(after):]
    assert inverse == retained
    header = "package com.android.purebilibili.feature.audio.viewmodel\n\n"
    header += "import com.android.purebilibili.feature.audio.lyrics.*\nimport com.android.purebilibili.feature.audio.player.MusicPlaybackSource\n"
    header += "import com.bilipai.desktop.ui.DesktopOriginalMusicSourceLease\nimport com.bilipai.desktop.ui.DesktopOriginalMusicLyricsRequest\n"
    header += "import kotlinx.coroutines.Job\nimport kotlinx.coroutines.CoroutineScope\nimport kotlinx.coroutines.launch\nimport kotlinx.coroutines.flow.*\n"
    declaration = "internal class DesktopOriginalMusicLyricsController(\n    suppliedRepository: LyricsRepository,\n    private val viewModelScope: CoroutineScope,\n    private val isOpen: () -> Boolean,\n) {\n    private var lastLyricsLease: DesktopOriginalMusicSourceLease? = null\n"
    generated.append(write(output, "com/android/purebilibili/feature/audio/viewmodel/DesktopOriginalMusicLyricsController.kt", source,
        original, header + declaration + body + "\n}\n"))
    receipt = {"source": source, "sha256LF": hashlib.sha256(original.encode()).hexdigest(), "selected": names,
               "retainedSelectedSHA256LF": hashlib.sha256(retained.encode()).hexdigest(), "adaptations": adaptations,
               "exactSelectedBodyInverse": True, "wholeMusicViewModelOrAuPlayerEmitted": False,
               "existingMusicUiStateAndLyricsRepositoryOwnersPreserved": True}
    (output / "music-lyrics-selection.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
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

    source = 'core-data/src/main/java/com/android/purebilibili/data/repository/FavoriteRepository.kt'
    original = read_source(repo, source)
    error = section(original, "class FavoriteRequestException(", "private fun favoriteApiFailure(")
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
