"""Extract exact settings/audio policy bodies with verified platform constants."""
from __future__ import annotations
from v025_source_paths import canonical_source as _desktop_canonical_source
import argparse
import hashlib
import json
from pathlib import Path
import re

STORE = 'app/src/main/java/com/android/purebilibili/core/store/player/PlayerSettingsStore.kt'
SETTINGS = 'app/src/main/java/com/android/purebilibili/feature/settings/PlaybackSettingsSelectionPolicy.kt'
FAILURE = 'app/src/main/java/com/android/purebilibili/feature/video/playback/audio/PremiumAudioPlaybackFailurePolicy.kt'
SCREEN = 'app/src/main/java/com/android/purebilibili/feature/settings/screen/PlaybackSettingsScreen.kt'
SOURCES = {STORE: 'extracted', SETTINGS: 'extracted', FAILURE: 'extracted', SCREEN: 'extracted'}

def read(repo: Path, path: str) -> str:
    return (_desktop_canonical_source(repo, path)).read_text(encoding='utf-8').replace('\r\n', '\n')

def function(source: str, name: str) -> str:
    start = source.index('internal fun ' + name)
    begin = source.index('{', start)
    depth, end = 1, begin + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

def generate(repo: Path, output: Path, platform: Path) -> None:
    def write(relative: str, body: str, original: str | None = None):
        target = output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        if original:
            body = '// Source: ' + original + '\n// LF SHA-256: ' + hashlib.sha256(read(repo, original).encode()).hexdigest() + '\n' + body
        target.write_text(body, encoding='utf-8')
    # Exact v025 interaction/comment control; the existing dialog owns scope.
    import importlib.util
    def load(name, path):
        spec=importlib.util.spec_from_file_location(name,path)
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
    parser=load('comment_setting_parser',repo/'desktop/tools/sync-upstream.py')
    original=read(repo,SCREEN)
    point=original.index('title = "详细评论时间显示"')
    start=original.rfind('AppSwitchPreference(',0,point)
    assert start>=0
    tokens=parser.kotlin_tokens(original[start:]);opening=next(i for i,t in enumerate(tokens)if t[0]=='(')
    depth=1;end=opening
    while depth:
        end+=1;depth+=(tokens[end][0]=='(')-(tokens[end][0]==')')
    body=original[start:start+tokens[end][2]]
    seam='scope.launch {\n                    SettingsManager.setDetailedCommentTimeEnabled(context, enabled)\n                }'
    assert body.count(seam)==1
    body=body.replace(seam,'setDetailedCommentTimeEnabled(enabled)',1)
    write('com/android/purebilibili/feature/settings/DesktopOriginalDetailedCommentTimeSetting.kt',
        'package com.android.purebilibili.feature.settings\nimport androidx.compose.runtime.Composable\nimport com.android.purebilibili.core.ui.components.AppSwitchPreference\nimport com.android.purebilibili.core.theme.iOSTeal\n@Composable\ninternal fun DesktopOriginalDetailedCommentTimeSetting(\n    detailedCommentTimeEnabled:Boolean,\n    setDetailedCommentTimeEnabled:(Boolean)->Unit,\n) {\n'+body+'\n}\n',SCREEN)
    constant = re.search(r'^const val DEFAULT_AUDIO_QUALITY_FOLLOW_LAST = -?\d+\s*$', read(repo, STORE), re.MULTILINE)
    assert constant, 'Original default audio setting drifted'
    write('com/android/purebilibili/core/store/player/DefaultAudioQuality.kt',
        'package com.android.purebilibili.core.store.player\n\n' + constant.group().strip() + '\n', STORE)
    # Whole PlaybackSettingsSelectionPolicy now has one producer. Only remove the
    # previously generated known two-function file; never delete an unknown output.
    obsolete = output / 'com/android/purebilibili/feature/settings/DefaultAudioQualitySelection.kt'
    if obsolete.exists():
        assert hashlib.sha256(obsolete.read_bytes()).hexdigest() == 'e24f5f017c00dd4496c711eae48133946792914472cb3430cc80c6b35a9326ff', 'Stale policy output drifted'
        obsolete.unlink()
    original = read(repo, FAILURE)
    old = 'import androidx.media3.common.PlaybackException'
    assert original.count(old) == 1, 'Original Media3 import drifted'
    write('com/android/purebilibili/feature/video/playback/audio/PremiumAudioPlaybackFailurePolicy.kt',
        original.replace(old, 'import com.bilipai.desktop.player.platform.DesktopPremiumAudioMedia3ErrorCodes as PlaybackException'), FAILURE)
    verified = json.loads(platform.read_text(encoding='utf-8'))
    media = verified['media3']
    assert media['sourceArchiveSha256'] == '472586b0da9837abba8cfd1e3113b81c801fe265f9fadc5ceca4011dba4255ba'
    assert media['constants'] == {'ERROR_CODE_AUDIO_TRACK_INIT_FAILED': 5001, 'ERROR_CODE_AUDIO_TRACK_WRITE_FAILED': 5002,
        'ERROR_CODE_DECODER_INIT_FAILED': 4001, 'ERROR_CODE_DECODING_FAILED': 4003, 'ERROR_CODE_FAILED_RUNTIME_CHECK': 1004}
    assert set(re.findall(r'PlaybackException\.(ERROR_CODE_\w+)', original)) <= set(media['constants'])
    write('com/bilipai/desktop/player/platform/DesktopPremiumAudioMedia3ErrorCodes.kt',
        '// Verified official Media3 source archive SHA-256: ' + media['sourceArchiveSha256'] + '\n' +
        'package com.bilipai.desktop.player.platform\n\ninternal object DesktopPremiumAudioMedia3ErrorCodes {\n' +
        ''.join(f'    const val {name}: Int = {value}\n' for name, value in media['constants'].items()) + '}\n')
    native = verified['mpv']
    assert native['pinnedCommit'] == '69e63f425a531f814431fba12750bdb3721357f2'
    assert native['constant'] == 'MPV_ERROR_AO_INIT_FAILED' and native['value'] == -14
    write('com/bilipai/desktop/player/platform/DesktopPremiumAudioMpvErrors.kt',
        '// Verified pinned mpv client.h SHA-256: ' + native['headerSha256Bytes'] + '\n' +
        'package com.bilipai.desktop.player.platform\n\ninternal object DesktopPremiumAudioMpvErrors {\n' +
        f"    const val {native['constant']}: Int = {native['value']}\n" + '}\n')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--platform', type=Path)
    parser.add_argument('--inventory', action='store_true')
    args = parser.parse_args()
    if args.inventory:
        print(json.dumps([{'path': path, 'mode': mode, 'features': ['playback', 'audio', 'settings'],
            'sha256': hashlib.sha256(read(args.repo, path).encode()).hexdigest()} for path, mode in SOURCES.items()], indent=2))
    elif args.output:
        generate(args.repo, args.output, args.platform or args.repo / 'desktop/third-party/premium-audio-platform.json')
    else: parser.error('Pass --output or --inventory')
