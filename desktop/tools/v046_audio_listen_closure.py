"""Roadmap slice audio-listen-video-closure: the v0.4.6 listen-to-video closure on the existing producers.

Upstream 7c1d16d3 (0.3.6 glass controls), 3de463f1 (0.3.8 direct entry and queue), 4e792ad and b491ed2 (0.3.9 recent
plays), c42851ae, d17c3408 and 20b05863 (0.4.0 recent plays, immersive colour and pager fixes) and 022f76a5 (0.4.5
lyric and subtitle display controls), at v0.4.6 (d6bf8b1655ae910999860bf13e65c8fcea5b345b). The archive holds five whole
v0.4.6 files; each one advances the output of the producer that already owns it:

* ListenVideoScreen.kt (extract-upstream-listen-video-root.py): the v0.3.8 Windows screen is rebuilt from the v0.4.6
  original by replaying the v038 ports that the predecessor recorded, with one re-anchored port, plus the recent plays
  sheet port: the sheet reads the music UI platform, so the page supplies a history-only one
  (DesktopOriginalListenHistoryPlatform) and its play callback is admitted like every other page callback;
* MusicPlayerContent.kt and Music3DCoverFlow.kt (extract-upstream-music-player-full.py) and AudioModeMusicPlayer.kt
  (extract-upstream-video-audio-full.py): the recorded 79e8 ports are replayed on the v0.4.6 body, plus the counted
  v0.4.6 hunks below;
* LyricsDisplayPolicy.kt (extract-upstream-text-lyrics.py): the whole v0.4.6 file replaces the v0.3.4 one.

Each replay is checked twice: the recorded ports must reproduce the predecessor body from the predecessor original
(so the recorded recipe is complete), and the same ports applied to the v0.4.6 original must find every anchor.

Not carried, so the Windows output path is never touched:

* the audio waveform atmosphere (MusicAtmosphereBackground, AudioWaveformSource, the music atmosphere switch): roadmap
  D8 defers the waveform and it must not risk WASAPI exclusive output. The v0.4.6 host call becomes a plain block and
  the switch and its Toast are dropped, so MusicAtmosphereHost and the audio energy never exist on Windows;
* AudioNowPlayingBar.kt (onCancelPlayback, elastic feedback, window snapshot dissolve, card corner handoff): card
  transitions (D1) and dock consumers (D5) are deferred, the file is pinned whole once by chrome-dock-consumers;
* ListenVideoViewModel.kt: the account audit of acct-account-scoped-requests found that the Root Listen entry is
  already retired and rebuilt per session epoch, so the v0.4.3 account-scoped request rewrite adds nothing;
* PagesSelector coverUrl in AudioModeMusicPlayer: the Windows PagesSelector is the v0.3.7 file; the argument returns
  with lst-episode-selector-collection.

Source only. Nothing here compiles or runs a JVM.
"""
from pathlib import Path
import hashlib
import json
import os

from v044_favorite_detail import inverse_proof

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-audio-listen-closure'
MANIFEST_SHA256 = 'f1f8033deea1882b5f0db26ea8264a9738afc42dc5a6b66aca5bca93e4a34cd1'
EXPECTED = {'ListenVideoScreen.kt', 'MusicPlayerContent.kt', 'Music3DCoverFlow.kt', 'AudioModeMusicPlayer.kt',
            'LyricsDisplayPolicy.kt'}
BASE = 'app/src/main/java/com/android/purebilibili/'
RELATIVE = {
    'listen': 'com/android/purebilibili/feature/audio/screen/ListenVideoScreen.kt',
    'music': 'com/android/purebilibili/feature/audio/screen/MusicPlayerContent.kt',
    'cover': 'com/android/purebilibili/feature/audio/screen/Music3DCoverFlow.kt',
    'audio': 'com/android/purebilibili/feature/video/screen/AudioModeMusicPlayer.kt',
    'lyrics': 'com/android/purebilibili/feature/audio/lyrics/LyricsDisplayPolicy.kt',
}
CHANGE_KEYS = {
    'music': 'feature/audio/screen/MusicPlayerContent.kt',
    'audio': 'feature/video/screen/AudioModeMusicPlayer.kt',
}
PROOFS = {
    'listen': 'v046-audio-listen-screen-proof.json',
    'music': 'v046-audio-listen-music-proof.json',
    'audio': 'v046-audio-listen-audio-mode-proof.json',
}
# LF digests of the whole 79e8 originals that the music and audio producers start from.
BASELINE_SHA256 = {
    'MusicPlayerContent.kt': '2a200f2661989021073992af6fbe041a8a973dd8e9a1e221f8935283aebb538d',
    'Music3DCoverFlow.kt': 'fea16146c8b3ff2ecca919e31f177330d59ff973703972eba6a162aac2b23392',
    'AudioModeMusicPlayer.kt': '4aa801e622630315ff95d9ba5bb4c277ecee524055ef4f5ccc5c71e648f136ea',
}
# The v0.3.4 LyricsDisplayPolicy the text lyrics producer emits today (raw digest of the v034-text-lyrics leaf).
LYRICS_PREDECESSOR_SHA256 = 'cecab90e58b2e625f63a8ab34973a46b2c71b2e628265a9dd6e357535f6434db'

# ListenVideoScreen: the v038 port whose anchor spans a line that v0.4.6 splits (the recent plays state sits between
# the two anchored lines) is re-anchored without changing what it does.
LISTEN_READJUSTED_LABEL = 'same original StateFlows/no mirrored queue'
LISTEN_READJUSTED_BEFORE = ('    val state by viewModel.uiState.collectAsStateWithLifecycle()\n'
                            '    val playlistState by PlaylistManager.uiState.collectAsStateWithLifecycle(')
LISTEN_READJUSTED_AFTER = ('    val page = LocalDesktopOriginalListenVideoPage.current\n'
                           '    val state by viewModel.uiState.collectAsStateWithLifecycle()\n'
                           '    val playlistState by page.playlist.uiState.collectAsStateWithLifecycle(')
LISTEN_READJUSTED = [
    ('    val state by viewModel.uiState.collectAsStateWithLifecycle()\n'
     '    var showPlayHistory by rememberSaveable { mutableStateOf(false) }\n',
     '    val page = LocalDesktopOriginalListenVideoPage.current\n'
     '    val state by viewModel.uiState.collectAsStateWithLifecycle()\n'
     '    var showPlayHistory by rememberSaveable { mutableStateOf(false) }\n',
     'The retained Root page is read before the first original state'),
    ('    val playlistState by PlaylistManager.uiState.collectAsStateWithLifecycle(',
     '    val playlistState by page.playlist.uiState.collectAsStateWithLifecycle(',
     'The original queue state flows come from the Root playlist binding'),
]
# The recent plays sheet. Every other page callback is admitted by page.runUi; the sheet additionally needs the music
# UI platform that PlayHistorySheet reads, which only the page can supply for this composition.
LISTEN_PORTS = [
    ('import com.bilipai.desktop.ui.LocalDesktopOriginalListenVideoPage\n',
     'import com.bilipai.desktop.ui.LocalDesktopOriginalListenVideoPage\n'
     'import com.bilipai.desktop.ui.LocalDesktopOriginalMusicUiPlatform\n'
     'import androidx.compose.runtime.CompositionLocalProvider\n',
     'Imports of the history-only music UI platform local'),
    ('    if (showPlayHistory) {\n'
     '        PlayHistorySheet(\n'
     '            onPlay = { entry ->\n'
     '                showPlayHistory = false\n'
     '                viewModel.pauseBackgroundLoading()\n'
     '                onPlayFromHistory(entry.bvid, entry.cid)\n'
     '            },\n',
     '    if (showPlayHistory) CompositionLocalProvider(\n'
     '        LocalDesktopOriginalMusicUiPlatform provides page.historyPlatform,\n'
     '    ) {\n'
     '        PlayHistorySheet(\n'
     '            onPlay = { entry -> page.runUi {\n'
     '                showPlayHistory = false\n'
     '                viewModel.pauseBackgroundLoading()\n'
     '                onPlayFromHistory(entry.bvid, entry.cid)\n'
     '            } },\n',
     'PlayHistorySheet reads the music UI platform: the page supplies its history-only one, and the play callback is '
     'admitted by the retained Root page like every other callback'),
]

# MusicPlayerContent: the audio waveform atmosphere is not carried (roadmap D8, no audio output risk).
MUSIC_PRODUCER_IMPORT = ('import com.bilipai.desktop.ui.LocalDesktopOriginalMusicUiPlatform\n',
                         'import com.bilipai.desktop.ui.LocalDesktopOriginalMusicUiPlatform\n'
                         'import com.bilipai.desktop.ui.asImageBitmap\n',
                         'The producer adds the Compose-to-raster extension import after its ports')
MUSIC_PORTS = [
    ('    val audioSpectrumEnabled by SettingsManager\n'
     '        .getAudioSpectrumEnabled(context)\n'
     '        .collectAsStateWithLifecycle(initialValue = false)\n',
     '    // Windows has no audio waveform source (roadmap D8): the music atmosphere switch is not carried.\n'
     '    val audioSpectrumEnabled = false\n',
     'The music atmosphere setting does not exist on Windows; the atmosphere stays off'),
    ('                                MusicActionSheetItem(\n'
     '                                    if (audioSpectrumEnabled) "音乐氛围：开启" else "音乐氛围：关闭",\n'
     '                                    contentColor = sheetContentColor\n'
     '                                ) {\n'
     '                                    showActions = false\n'
     '                                    val nextEnabled = !audioSpectrumEnabled\n'
     '                                    settingsScope.launch {\n'
     '                                        SettingsManager.setAudioSpectrumEnabled(context, nextEnabled)\n'
     '                                    }\n'
     '                                    android.widget.Toast.makeText(\n'
     '                                        context,\n'
     '                                        if (nextEnabled) "已开启音乐氛围" else "已关闭音乐氛围",\n'
     '                                        android.widget.Toast.LENGTH_SHORT,\n'
     '                                    ).show()\n'
     '                                }\n',
     '',
     'The music atmosphere menu entry and its Android Toast are not carried'),
    ('    MusicAtmosphereHost(\n'
     '        enabled = atmosphereEnabled,\n'
     '        isPlaying = isPlaying,\n'
     '        reduceMotion = reduceMotion,\n'
     '        lyricsPageProgress = lyricsPageProgress,\n'
     '        modifier = Modifier.fillMaxSize(),\n'
     '    ) { audioEnergy ->\n',
     '    // The atmosphere host is the identity of the original when it is disabled, which is the only state on Windows.\n'
     '    run {\n',
     'The disabled atmosphere host is a plain block, so the audio energy provider never exists'),
    ('                audioEnergyProvider = audioEnergy,\n', '',
     'The cover flow background keeps its v0.3.4 signature without an audio energy provider'),
]

# AudioModeMusicPlayer: the one v0.4.6 argument whose callee is not carried yet.
# The only counted v0.4.6 port of this file used to drop the `coverUrl = info.pic,` argument of the part selector call
# because the Windows selector was the v0.3.7 one. The episode selector slice (lst-episode-selector-collection) now carries
# the whole v0.4.6 PagesSelector, so the argument is real and stays exactly as v0.4.6 writes it.
AUDIO_PORTS = []


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def _wide(path):
    path = Path(path).resolve()
    text = str(path)
    return Path('\\\\?\\' + text) if os.name == 'nt' and not text.startswith('\\\\?\\') else path


def fixed(repo):
    """The archive, byte-exact: leaf set, manifest digest, identity, per-leaf length/digest/blob, licence."""
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing v0.4.6 listen closure inputs')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'},
         'Listen closure input leaves changed')
    raw = (root / 'manifest.json').read_bytes()
    need(sha(raw) == MANIFEST_SHA256, 'Listen closure manifest changed')
    manifest = json.loads(raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'Listen closure source identity changed')
    need(len(manifest['files']) == len(EXPECTED) and {r['filename'] for r in manifest['files']} == EXPECTED,
         'Listen closure source set changed')
    sources = {}
    for row in manifest['files']:
        file = root / row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous listen closure source')
        body = file.read_bytes()
        need(len(body) == row['bytes'] and sha(body) == row['sha256'], 'Listen closure source bytes changed: ' + file.name)
        need(hashlib.sha1(b'blob ' + str(len(body)).encode() + b'\0' + body).hexdigest() == row['gitBlob'],
             'Listen closure source Git blob changed: ' + file.name)
        sources[file.name] = body.decode('utf8').replace('\r\n', '\n')
    licence = root / 'LICENSE'
    need(licence.is_file() and not licence.is_symlink() and sha(licence.read_bytes()) == manifest['license']['sha256'],
         'Listen closure licence changed')
    return sources, manifest


def validate_outputs(repo, *outputs):
    fixed(repo)
    root = (Path(repo) / ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)),
             'Output overlaps the v0.4.6 listen closure inputs')


def restore(value, proof):
    """Apply an inverse_proof backwards: the exact predecessor of an advanced body."""
    need(sha(value) == proof['afterSHA256'], 'Advanced body differs from its receipt')
    lines = value.splitlines(keepends=True)
    for hunk in reversed(proof['hunks']):
        first, last = hunk['afterLines']
        need(''.join(lines[first:last]) == hunk['after'], 'Advanced body inverse span changed')
        lines[first:last] = hunk['before'].splitlines(keepends=True)
    result = ''.join(lines)
    need(sha(result) == proof['beforeSHA256'], 'Advanced body predecessor inverse failed')
    return result


# The predecessor of the audio mode player is the output of the part selector (episode selector) layer; the same output
# WITHOUT that layer (the layer's own base run) is the only other state this layer accepts. The manifest pins the former.
ALTERNATE_PREDECESSORS = {
    'audio/AudioModeMusicPlayer.kt': '640bf139905a0d5dc3ae5cd06330144d514afb22dc44b3bba938342dcb4ca982',
}


def _before(repo, key, body):
    sources, manifest = fixed(repo)
    accepted = {manifest['beforeFinalOutputs'][key]['sha256']}
    if key in ALTERNATE_PREDECESSORS:
        accepted.add(ALTERNATE_PREDECESSORS[key])
    need(sha(body) in accepted, 'Listen closure predecessor changed: ' + key)
    return sources


def _once(text, old, new, label):
    need(text.count(old) == 1, 'Listen closure anchor changed: ' + label)
    return text.replace(old, new, 1)


def _ports(text, ports):
    edits = []
    for old, new, label in ports:
        text = _once(text, old, new, label)
        edits.append(dict(before=old, after=new, label=label))
    return text, edits


def group_ops(changes):
    """The producers record one entry per replaced occurrence: consecutive equal entries are one replace-all."""
    ops = []
    for change in changes:
        if ops and (ops[-1]['before'], ops[-1]['after'], ops[-1]['label']) == (
                change['before'], change['after'], change['label']):
            ops[-1]['count'] += 1
        else:
            ops.append(dict(before=change['before'], after=change['after'], label=change['label'], count=1))
    return ops


def replay(text, ops, what):
    for op in ops:
        need(text.count(op['before']) == op['count'], what + ' port anchor changed: ' + op['label'])
        text = text.replace(op['before'], op['after'])
    return text


def _advance(original, before, ops, ports, baseline, what, previous_ops=None):
    """The recorded ports of the predecessor, replayed on the v0.4.6 original, plus the counted v0.4.6 ports.

    previous_ops are the ports as the predecessor recorded them when a port had to be re-anchored for v0.4.6."""
    need(replay(baseline, ops if previous_ops is None else previous_ops, what + ' predecessor') == before,
         what + ' recorded ports do not rebuild the predecessor')
    adapted, edits = _ports(replay(original, ops, what), ports)
    return adapted, dict(
        upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, wholeOriginal=True,
        originalSHA256=sha(original), adaptedSHA256=sha(adapted), predecessorSHA256=sha(before),
        replayedPorts=[dict(label=op['label'], count=op['count']) for op in ops], windowsEdits=edits,
        originalToWindows=inverse_proof(original, adapted), retainedToCurrent=inverse_proof(before, adapted),
        jvmTestsExecuted=False)


def _baseline(repo, path, leaf):
    from v025_source_paths import canonical_source
    text = canonical_source(repo, BASE + path).read_text(encoding='utf-8').replace('\r\n', '\n')
    need(sha(text) == BASELINE_SHA256[leaf], 'The 79e8 original of ' + leaf + ' changed')
    return text


def advance_listen_screen(repo, before, recorded_edits):
    """The v0.3.8 Windows screen becomes the whole v0.4.6 screen with the same ports and the recent plays sheet."""
    import v038_listen_screen
    sources = _before(repo, 'listen/ListenVideoScreen.kt', before)
    ops = []
    for edit in recorded_edits:
        if edit['label'] == LISTEN_READJUSTED_LABEL:
            need((edit['before'], edit['after']) == (LISTEN_READJUSTED_BEFORE, LISTEN_READJUSTED_AFTER),
                 'The recorded state port changed')
            ops.extend(dict(before=old, after=new, label=label, count=1) for old, new, label in LISTEN_READJUSTED)
        else:
            ops.append(dict(before=edit['before'], after=edit['after'], label=edit['label'], count=1))
    previous = [dict(before=e['before'], after=e['after'], label=e['label'], count=1) for e in recorded_edits]
    return _advance(sources['ListenVideoScreen.kt'], before, ops, LISTEN_PORTS,
                    v038_listen_screen.fixed_sources(repo)['ListenVideoScreen.kt'], 'Listen screen', previous)


def advance_music_player_content(repo, before, changes):
    """The 79e8 Windows MusicPlayerContent becomes the whole v0.4.6 body with the same ports plus the counted hunks."""
    sources = _before(repo, 'music/MusicPlayerContent.kt', before)
    ops = group_ops(changes) + [dict(before=MUSIC_PRODUCER_IMPORT[0], after=MUSIC_PRODUCER_IMPORT[1],
                                     label=MUSIC_PRODUCER_IMPORT[2], count=1)]
    return _advance(sources['MusicPlayerContent.kt'], before, ops, MUSIC_PORTS,
                    _baseline(repo, 'feature/audio/screen/MusicPlayerContent.kt', 'MusicPlayerContent.kt'),
                    'Music player')


def advance_cover_flow(repo, before):
    """The producer emits the 79e8 Music3DCoverFlow unchanged; v0.4.6 carries the unknown like placeholder whole."""
    sources = _before(repo, 'music/Music3DCoverFlow.kt', before)
    need(before == _baseline(repo, 'feature/audio/screen/Music3DCoverFlow.kt', 'Music3DCoverFlow.kt'),
         'Music3DCoverFlow is not the unchanged 79e8 original')
    original = sources['Music3DCoverFlow.kt']
    return original, dict(
        upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, wholeOriginal=True, windowsEdits=[],
        originalSHA256=sha(original), adaptedSHA256=sha(original), predecessorSHA256=sha(before),
        retainedToCurrent=inverse_proof(before, original), jvmTestsExecuted=False)


def advance_audio_mode_player(repo, before, changes):
    """The 79e8 Windows AudioModeMusicPlayer becomes the whole v0.4.6 body with the same ports plus the counted hunk."""
    sources = _before(repo, 'audio/AudioModeMusicPlayer.kt', before)
    # The episode selector layer (v046_episode_selector) ports the part selector cover argument onto the 79e8 body just
    # before this layer. The whole v0.4.6 original already carries that argument itself, so that recorded port is
    # superseded here: drop it, but only after proving the original really contains its result exactly once.
    from v046_episode_selector import CALL_SITE_LABELS
    original = sources['AudioModeMusicPlayer.kt']
    cover_label = CALL_SITE_LABELS['audio']
    recorded = group_ops(changes)
    # The recorded change carries the text the layer really produced (the layer's own base run records a no-op), so the
    # test reads the recorded result instead of a module constant that a baseline run may have patched.
    for change in changes:
        if change['label'] == cover_label and change['before'] != change['after']:
            need(original.count(change['after']) == 1, 'v0.4.6 audio mode player lacks the part selector cover argument')
    changes = [change for change in changes if change['label'] != cover_label]
    return _advance(original, before, group_ops(changes), AUDIO_PORTS,
                    _baseline(repo, 'feature/video/screen/AudioModeMusicPlayer.kt', 'AudioModeMusicPlayer.kt'),
                    'Audio mode player', previous_ops=recorded)


def upgraded_lyrics_display_policy(repo, previous_raw, previous_row):
    """Text lyrics producer layer: the whole v0.4.6 policy (resolveLyricsDisplayDocument) replaces the v0.3.4 file."""
    sources, manifest = fixed(repo)
    need(sha(previous_raw) == LYRICS_PREDECESSOR_SHA256 == manifest['beforeFinalOutputs'][
        'lyrics/LyricsDisplayPolicy.kt']['sha256'], 'Lyrics display policy predecessor changed')
    row = next(r for r in manifest['files'] if r['filename'] == 'LyricsDisplayPolicy.kt')
    original = sources['LyricsDisplayPolicy.kt']
    result = original.encode('utf8')
    previous = previous_raw.decode('utf8').replace('\r\n', '\n')
    need(original.count('internal fun resolveLyricsDisplayDocument(') == 1 and previous.count(
        'resolveLyricsDisplayDocument') == 0, 'Lyrics display policy shape changed')
    return result, dict(
        file=row['filename'], originalPath=row['path'], outputPath=RELATIVE['lyrics'], bytes=row['bytes'],
        sha256=row['sha256'], gitBlob=row['gitBlob'], upstreamCommit=COMMIT, completeOriginalFile=True,
        referenceOnly=False, v046AudioListenManifestSha256=MANIFEST_SHA256, previousOriginalValidation=previous_row,
        v046AudioListenAdvance=dict(retainedToCurrent=inverse_proof(previous, original), windowsEdits=[]))


def _write_proof(output, name, proof):
    target = _wide(Path(output) / name)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(proof, ensure_ascii=False, indent=2) + '\n', encoding='utf8', newline='\n')


def _advance_file(output, key, advance):
    target = _wide(Path(output) / RELATIVE[key])
    before = target.read_text(encoding='utf8')
    body, proof = advance(before)
    target.write_text(body, encoding='utf8', newline='\n')
    return dict(proof, output=RELATIVE[key], outputFileSHA256=sha(body))


def advance_listen_output(repo, output, receipts):
    """Listen producer layer: rewrite the emitted screen; the receipt of the screen carries this layer's proof."""
    receipt = next(r for r in receipts if r.get('output') == RELATIVE['listen'])
    proof = _advance_file(output, 'listen', lambda before: advance_listen_screen(repo, before, receipt['edits']))
    receipt['v046AudioListenAdvance'] = proof
    _write_proof(output, PROOFS['listen'], proof)
    return proof


def advance_music_outputs(repo, output, changes):
    """Music player producer layer: MusicPlayerContent and Music3DCoverFlow, with one receipt for both."""
    key = CHANGE_KEYS['music']
    music = _advance_file(output, 'music', lambda before: advance_music_player_content(repo, before, changes[key]))
    cover = _advance_file(output, 'cover', lambda before: advance_cover_flow(repo, before))
    _write_proof(output, PROOFS['music'], dict(musicPlayerContent=music, music3DCoverFlow=cover))
    return music, cover


def advance_audio_mode_outputs(repo, output, changes):
    """Audio mode producer layer: AudioModeMusicPlayer."""
    key = CHANGE_KEYS['audio']
    proof = _advance_file(output, 'audio', lambda before: advance_audio_mode_player(repo, before, changes[key]))
    _write_proof(output, PROOFS['audio'], proof)
    return proof
