from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys
sys.stdout.reconfigure(encoding='utf-8');sys.dont_write_bytecode=True
H=Path(__file__).resolve().parent;MAIN=H.parents[2];REPO=MAIN.parent/'BiliPai-v023'
def wide(p):
    s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b.encode() if isinstance(b,str) else b).hexdigest()
def write(p,b):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b.encode() if isinstance(b,str) else b)
target='desktop/tools/extract-upstream-audio.py';base=read(REPO/target).replace(b'\r\n',b'\n')
before='    source = "app/src/main/java/com/android/purebilibili/feature/video/player/PlaylistManager.kt"\n'
addition='''    # Select only the original MusicViewModel lyrics domain consumed by BV audio
    # mode. MusicUiState and the unchanged original Repository/providers/cache
    # retain their existing sole owners; no AU player or MiniPlayer is emitted.
    import importlib.util
    def module(name, path):
        spec = importlib.util.spec_from_file_location(name, path)
        value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value)
        return value
    tokens = module("audio_lyrics_tokens", repo / "desktop/tools/sync-upstream.py")
    selector = module("audio_lyrics_selection", repo / "desktop/tools/extract-appearance-platform.py")
    assert hashlib.sha256(original.encode()).hexdigest() == "d9705a700bbe14722b79d9bb484d70da0afb97ae1c9df393e7a271e4a0173d0c"
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
    adapt("fun loadLyricsForVideo(\\n", "fun loadLyricsForVideo(\\n        lease: DesktopOriginalMusicSourceLease,\\n", "fixed actual Root subject lease before launch")
    adapt("private suspend fun loadLyrics(\\n", "private suspend fun loadLyrics(\\n        request: DesktopOriginalMusicLyricsRequest,\\n", "lexical launched Job and subject in original private load")
    adapt("viewModelScope.launch {\\n", "viewModelScope.launch {\\n            val request = DesktopOriginalMusicLyricsRequest(lease, kotlinx.coroutines.currentCoroutineContext(), isOpen)\\n            request.check()\\n", "capture launched caller Job before original IO", True)
    adapt("_uiState.update {", "request.updateState(_uiState) {", "original UI updates enter only current Job/subject short publication", True)
    adapt("request.updateState(_uiState) { it.copy(lyricsDocument = adjusted) }", "lease.updateState(_uiState, isOpen) { it.copy(lyricsDocument = adjusted) }", "original synchronous offset action checks binding lifetime inside same subject publication")
    adapt("        val document = _uiState.value.lyricsDocument ?: return", "        if (lastLyricsLease !== lease) return\\n        val document = _uiState.value.lyricsDocument ?: return", "a queued new-source load cannot adjust the previous source document")
    adapt("        val cacheKey = lastLyricsCacheKey ?: return", "        val cacheKey = lastLyricsCacheKey ?: return\\n        if (lastLyricsLease !== lease) return", "private query/cache context belongs to the exact captured lyrics lease", True)
    adapt("loadLyrics(\\n                cacheKey =", "loadLyrics(\\n                request = request,\\n                cacheKey =", "same request carried to original automatic load")
    adapt("loadLyrics(cacheKey, query, lastBilibiliLyrics, forceRefresh = true)", "loadLyrics(request, cacheKey, query, lastBilibiliLyrics, forceRefresh = true)", "same captured request for original retry")
    adapt("        lastLyricsCacheKey = cacheKey\\n        lastLyricsQuery = query\\n        lastBilibiliLyrics = bilibiliLyrics", "        request.commit {\\n            lastLyricsCacheKey = cacheKey\\n            lastLyricsQuery = query\\n            lastBilibiliLyrics = bilibiliLyrics\\n            lastLyricsLease = request.sourceLease\\n        }", "original private query context publishes under same current subject")
    adapt("            lastLyricsCacheKey = cacheKey\\n            request.updateState(_uiState) { it.copy(isLyricsSearching = false, lyricCandidates = candidates) }", "            request.commit { lastLyricsCacheKey = cacheKey }\\n            request.updateState(_uiState) { it.copy(isLyricsSearching = false, lyricCandidates = candidates) }", "manual search cannot publish a retired cache key")
    inverse = body
    for row in reversed(adaptations):
        at = row["index"]; after = row["after"]
        assert inverse[at:at+len(after)] == after, row["label"]
        inverse = inverse[:at] + row["before"] + inverse[at+len(after):]
    assert inverse == retained
    header = "package com.android.purebilibili.feature.audio.viewmodel\\n\\n"
    header += "import com.android.purebilibili.feature.audio.lyrics.*\\nimport com.android.purebilibili.feature.audio.player.MusicPlaybackSource\\n"
    header += "import com.bilipai.desktop.ui.DesktopOriginalMusicSourceLease\\nimport com.bilipai.desktop.ui.DesktopOriginalMusicLyricsRequest\\n"
    header += "import kotlinx.coroutines.Job\\nimport kotlinx.coroutines.CoroutineScope\\nimport kotlinx.coroutines.launch\\nimport kotlinx.coroutines.flow.*\\n"
    declaration = "internal class DesktopOriginalMusicLyricsController(\\n    suppliedRepository: LyricsRepository,\\n    private val viewModelScope: CoroutineScope,\\n    private val isOpen: () -> Boolean,\\n) {\\n    private var lastLyricsLease: DesktopOriginalMusicSourceLease? = null\\n"
    generated.append(write(output, "com/android/purebilibili/feature/audio/viewmodel/DesktopOriginalMusicLyricsController.kt", source,
        original, header + declaration + body + "\\n}\\n"))
    receipt = {"source": source, "sha256LF": hashlib.sha256(original.encode()).hexdigest(), "selected": names,
               "retainedSelectedSHA256LF": hashlib.sha256(retained.encode()).hexdigest(), "adaptations": adaptations,
               "exactSelectedBodyInverse": True, "wholeMusicViewModelOrAuPlayerEmitted": False,
               "existingMusicUiStateAndLyricsRepositoryOwnersPreserved": True}
    (output / "music-lyrics-selection.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2)+"\\n", encoding="utf-8")
'''
assert base.count(before.encode())==1
after=base.replace(before.encode(),addition.encode()+before.encode(),1)
assert after.replace((addition+before).encode(),before.encode(),1)==base
write(H/'baseline'/target,read(REPO/target));write(H/'prepared'/target,after)
write(H/'producer-hunk.json',json.dumps(dict(target=target,baseSHA256LF=sha(base),desiredSHA256LF=sha(after),
    edits=[dict(before=before,after=addition+before)],indexedInverse=True),indent=2)+'\n')
output=H/(sys.argv[1] if len(sys.argv)>1 else 'generated');assert not output.exists()
command=[sys.executable,str(H/'prepared'/target),'--repo',str(REPO),'--output',str(output)]
result=subprocess.run(command,capture_output=True,timeout=60);write(H/(output.name+'-generation.log'),result.stdout+result.stderr)
if result.returncode:print((result.stdout+result.stderr).decode());raise SystemExit(result.returncode)
print(json.dumps(dict(producerHunks=1,selectedOriginalSha='d9705a700bbe14722b79d9bb484d70da0afb97ae1c9df393e7a271e4a0173d0c',generationPassed=True)))
