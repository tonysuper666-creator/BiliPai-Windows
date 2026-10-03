from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse, hashlib, json, textwrap

TARGET = '3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
APP = 'app/src/main/java/com/android/purebilibili/navigation/AppNavigation.kt'
TOP = 'app/src/main/java/com/android/purebilibili/navigation/AppTopLevelNavigationPolicy.kt'
PREFIX = chr(92) * 2 + '?' + chr(92)

def safe(p):
    s = str(Path(p).absolute())
    return Path(s if s.startswith(PREFIX) else PREFIX + s)

def function(raw, name, indent):
    # Selected callers contain no nested strings with unmatched braces. Reject any source drift
    # through the source/selection receipt rather than approximating an Android callback body.
    start = raw.index(' ' * indent + 'fun ' + name + '(')
    line = raw[start:raw.index('\n', start)]
    equals = ' = ' in line
    if equals:
        end = raw.index('\n' + ' ' * indent + 'fun ', start + len(line))
    else:
        brace = raw.index('{', start)
        depth = 0
        for i in range(brace, len(raw)):
            if raw[i] == '{': depth += 1
            if raw[i] == '}':
                depth -= 1
                if depth == 0:
                    end = i + 1
                    break
        else: raise ValueError(name)
    return textwrap.dedent(raw[start:end]).rstrip()

def sha(s): return hashlib.sha256(s.encode('utf-8')).hexdigest()

def generate(repo, output):
    app = safe(_desktop_canonical_source(repo, APP)).read_text(encoding='utf-8').replace('\r\n', '\n')
    top = safe(_desktop_canonical_source(repo, TOP)).read_text(encoding='utf-8').replace('\r\n', '\n')
    selections = {}
    selected = []
    names = ('currentNavigation3SourceMetadata', 'captureCardSourceDirectionForSession',
             'captureVideoCardTransitionSession', 'prearmVideoCardOpening')
    for index, name in enumerate(names):
        start = app.index('        fun ' + name + '(')
        end_marker = ('        fun ' + names[index + 1] + '(' if index + 1 < len(names)
                      else '        var lastVideoDetailOpenId')
        end = app.index(end_marker, start)
        body = textwrap.dedent(app[start:end]).rstrip()
        selections[name] = {'originalSha256LF': sha(body), 'originalBody': body}
        if name == 'currentNavigation3SourceMetadata':
            body = body.replace('fun currentNavigation3SourceMetadata()',
                'internal fun desktopOriginalHomeSourceMetadata(navigation3ReturnSession: BiliPaiReturnSessionState)')
        elif name == 'captureCardSourceDirectionForSession':
            body = body.replace('fun captureCardSourceDirectionForSession(',
                'internal fun desktopOriginalHomeCardSourceDirection(')
        elif name == 'captureVideoCardTransitionSession':
            body = body.replace('fun captureVideoCardTransitionSession(',
                'internal fun desktopOriginalHomeTransitionSession(')
            body = body.replace('    coverIdentity: String?,\n',
                '    coverIdentity: String?,\n    navigationHostOriginInRoot: Offset,\n')
            body = body.replace('captureCardSourceDirectionForSession()',
                'desktopOriginalHomeCardSourceDirection()')
        elif name == 'prearmVideoCardOpening':
            body = body.replace('fun prearmVideoCardOpening(session: VideoCardTransitionSession)',
                'internal fun desktopOriginalHomePrearmOpening(\n    session: VideoCardTransitionSession,\n'
                '    sharedVideoCardTransitionEnabled: Boolean,\n    relatedVideoTransitionEnabled: Boolean,\n'
                '    systemReduceMotion: Boolean,\n    videoCardTransitionClock: VideoCardTransitionClock,\n)')
        selections[name]['generatedSha256LF'] = sha(body)
        selected.append(body)
    top_selected = []
    for name, lead in [('resolveVideoCardSourceRouteForNavigation', 'internal '),
                       ('normalizeVideoCardNavigationSourceRoute', 'private '),
                       ('resolveClickedVideoSourceRoute', 'private ')]:
        # The exact original visibility is preserved here; no second route implementation.
        start = top.index(lead + 'fun ' + name + '(')
        raw_body = function(top[start + len(lead):], name, 0)
        body = lead + raw_body
        top_selected.append(body)
        selections[name] = {'originalSha256LF': sha(body), 'originalBody': body,
                            'generatedSha256LF': sha(body)}
    header = ('// Source: ' + APP + '\n// Stable target: ' + TARGET + '\n'
              'package com.bilipai.desktop.ui\n\n'
              'import androidx.compose.ui.geometry.Offset\n'
              'import com.android.purebilibili.core.util.CardPositionManager\n'
              'import com.android.purebilibili.core.ui.transition.VideoCardTransitionClock\n'
              'import com.android.purebilibili.navigation3.*\n\n')
    a = output / 'com/bilipai/desktop/ui/DesktopOriginalHomeReturnCallers.kt'
    safe(a.parent).mkdir(parents=True, exist_ok=True)
    safe(a).write_text(header + '\n\n'.join(selected) + '\n', encoding='utf-8', newline='\n')
    b = output / 'com/android/purebilibili/navigation/DesktopOriginalHomeVideoSourceRoute.kt'
    safe(b.parent).mkdir(parents=True, exist_ok=True)
    safe(b).write_text('// Source: ' + TOP + '\n// Stable target: ' + TARGET + '\n'
                      'package com.android.purebilibili.navigation\n\n' +
                      '\n\n'.join(top_selected) + '\n', encoding='utf-8', newline='\n')
    return {'target': TARGET, 'sources': [
        {'path': APP, 'sha256LF': sha(app), 'selected': list(selections)[:4]},
        {'path': TOP, 'sha256LF': sha(top), 'selected': list(selections)[4:]}],
        'selections': selections,
        'outputs': [{'path': str(p), 'sha256Bytes': hashlib.sha256(safe(p).read_bytes()).hexdigest()}
                    for p in (a, b)]}

if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--repo', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--receipt', type=Path)
    args = p.parse_args()
    receipt = generate(args.repo, args.output)
    if args.receipt: safe(args.receipt).write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
