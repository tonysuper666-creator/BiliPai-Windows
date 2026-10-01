from pathlib import Path
import hashlib, importlib.util, json, os

P = Path(__file__).resolve().parent
MAIN = P.parents[2]
CANDIDATE = MAIN.parent / 'BiliPai-v023'
PFX = chr(92) * 2 + '?' + chr(92)

def wide(p):
    s = os.path.abspath(p)
    return Path(s if s.startswith(PFX) else PFX + s)

def text(p):
    return wide(p).read_text(encoding='utf-8').replace('\r\n', '\n')

def digest(s):
    return hashlib.sha256(s.encode()).hexdigest()

def save(p, s):
    wide(p).parent.mkdir(parents=True, exist_ok=True)
    wide(p).write_text(s, encoding='utf-8', newline='\n')

def dump(p, obj):
    save(p, json.dumps(obj, ensure_ascii=False, indent=2) + '\n')

def one(body, before, after):
    assert body.count(before) == 1, before[:80]
    return body.replace(before, after, 1)

rows = []
binding_target = 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoRepositoryBinding.kt'
binding_base = MAIN / 'desktop/.local/stable-video-captured-metadata-binding-parity/prepared' / binding_target
base = text(binding_base)
assert digest(base) == '22d033abfd1b02e65227107ab924a5e8e64f64a55ac7ee003edc63db04374049'
before = '    fun hasPrimarySession(): Boolean = read {\n'
after = '''    /** Exact primary values of this request, admitted by its original receipt.
     * Used by original Notes/heartbeat; no page-wide credential or MID cache. */
    fun primaryMid(): Long? = read { capturedPrimaryMid }
    fun primaryCsrf(): String? = read {
        repository.ownedHomeCookie("bili_jct", receipt.accountEpoch, ::entryCurrent)
    }
    /** Fixed request's short Store -> entry mutation admission. No wait/IO may
     * occur in action. Success is never reported for an expired request. */
    fun admitCurrentMutation(action: () -> Unit): Boolean = try {
        read(action)
        true
    } catch (_: CancellationException) { false }
    fun hasPrimarySession(): Boolean = read {
'''
desired = one(base, before, after)
save(P / 'prepared/existing' / binding_target, desired)
rows.append(dict(target=binding_target, dependency='frozen captured-metadata facet16 first',
    baseSHA256LF=digest(base), desiredSHA256LF=digest(desired), hunks=[dict(before=before, after=after)]))

assets_target = 'desktop/src/main/kotlin/com/bilipai/desktop/player/DesktopSubtitleAssets.kt'
base = text(CANDIDATE / assets_target)
before = '    suspend fun import(track: SubtitleTrackMeta): Path = withContext(Dispatchers.IO) {\n'
after = '''    suspend fun import(track: SubtitleTrackMeta): Path = import(track, client)

    /** Same application-owned files/document/cancellation; caller supplies the
     * already captured Repository Call.Factory. This creates no downloader/client. */
    internal suspend fun import(track: SubtitleTrackMeta, ownedCalls: Call.Factory): Path = withContext(Dispatchers.IO) {
'''
desired = one(base, before, after)
before2 = '        val call = client.newCall(Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).get()\n'
after2 = '        val call = ownedCalls.newCall(Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).get()\n'
desired = one(desired, before2, after2)
save(P / 'prepared/existing' / assets_target, desired)
rows.append(dict(target=assets_target, baseSHA256LF=digest(base), desiredSHA256LF=digest(desired),
    hunks=[dict(before=before, after=after), dict(before=before2, after=after2)]))
dump(P / 'exact-hunks.json', rows)

# A separate, explicit post-replay platform delta, never a rewrite of frozen363.
# Preserve all RECIPES/raw spans and the exact original-to-platform inverse receipt.
producer_target = 'desktop/tools/extract-upstream-video-full-owner.py'
base = text(CANDIDATE / producer_target)
anchor = "def generate(repo,output,standalone=False):\n"
replacement = '''def retained_handoff_platform_delta(path, body):
 if path!='com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt':return body
 before=''' + repr('                                 if (!p.isPlaying) p.play()\n') + '''
 after=''' + repr('                                 if (shouldAutoPlay && !p.isPlaying) p.play()\n') + '''
 assert body.count(before)==1,'retained paused-handoff anchor'
 return body.replace(before,after,1)

def generate(repo,output,standalone=False):
'''
desired = one(base, anchor, replacement)
before = "  body=''.join(parts);assert sha(body)==recipe['outputSha256LF'],recipe['output']\n"
after = before + "  body=retained_handoff_platform_delta(recipe['output'],body)\n"
desired = one(desired, before, after)
save(P / 'prepared/existing' / producer_target, desired)
dump(P / 'retained-pause-producer-hunk.json', dict(target=producer_target,
    baseSHA256LF=digest(base), desiredSHA256LF=digest(desired),
    changes=[dict(before=anchor,after=replacement),dict(before=before,after=after)],
    rawPolicy='Original spans still checked and replayed unchanged; explicit Windows post-replay honors captured pause only in original skip-prepare branch',
    installed=False))

spec=importlib.util.spec_from_file_location('producer',P/'prepared/existing'/producer_target)
mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
out=P/'generated-pause-candidate'
results=mod.generate(CANDIDATE,out,True)
vm=next(r for r in results if r['path'].endswith('/VideoPlaybackViewModel.kt'))
dump(P/'retained-pause-replay-proof.json',dict(outputs=results,sourceOnly=True,
    frozenVM13SHA256LF='15d36e1863173138ebac392705afbaebebc8299d5602c7be9ece66bb02e85123',
    explicitWindowsVMDeltaSHA256LF=vm['sha256LF'],
    fullVMConstructed=False,nativeAdoptionAccepted=False))
print('Prepared 3 existing families; original replay',len(results),'outputs. No live writes.')
