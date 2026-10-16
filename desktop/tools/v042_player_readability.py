"""Selected original v0.4.2 title and subtitle fixes, through the sole producers."""
from pathlib import Path
import hashlib
import importlib.util
import json
import re

COMMIT = '5c34a82418b33bf88acea01784bb3c6b179d6491'
ARCHIVE = 'desktop/upstream-slices/v042-player-readability'
MANIFEST_SHA256 = '391c4e73d3bf6d019a03ddcc4771a13f5785568beb6a15d0be46b9955408f0c8'
APP = 'app/src/main/java/com/android/purebilibili/'
TITLE = APP + 'feature/video/ui/overlay/TopControlBar.kt'
SUBTITLE = APP + 'feature/video/ui/overlay/PortraitSubtitleOverlay.kt'


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def fixed_sources(repo):
    root = Path(repo) / ARCHIVE
    raw = (root / 'manifest.json').read_bytes()
    if sha(raw) != MANIFEST_SHA256:
        raise ValueError('Unknown v042 player readability manifest')
    manifest = json.loads(raw)
    if manifest['fixedUpstreamCommit'] != COMMIT or {r['path'] for r in manifest['files']} != {TITLE, SUBTITLE}:
        raise ValueError('Unknown v042 player readability source set')
    result = {}
    for row in manifest['files']:
        if row['filename'] != Path(row['path']).name:
            raise ValueError('Unexpected original player archive filename')
        raw = (root / row['filename']).read_bytes()
        oid = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
        if len(raw) != row['bytes'] or sha(raw) != row['sha256Bytes'] or oid != row['gitBlob']:
            raise ValueError('Changed original v042 player source')
        result[row['path']] = raw.decode('utf-8')
    return result


def function_span(repo, source, name):
    spec = importlib.util.spec_from_file_location('v042_player_original_tokens', Path(repo) / 'desktop/tools/sync-upstream.py')
    parser = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(parser)
    tokens = parser.kotlin_tokens(source)
    starts = [i for i, (word, _, _) in enumerate(tokens[:-1]) if word == 'fun' and tokens[i + 1][0] == name]
    if len(starts) != 1:
        raise ValueError('Unknown original player function: ' + name)
    i = starts[0]
    start = source.rfind('\n', 0, tokens[i][1]) + 1
    while start:
        previous = source.rfind('\n', 0, start - 1) + 1
        if not source[previous:start].strip().startswith('@'):
            break
        start = previous
    depth = parens = brackets = 0
    opened = False
    for word, _, end in tokens[i + 2:]:
        if not opened:
            parens += (word == '(') - (word == ')')
            brackets += (word == '[') - (word == ']')
            if word != '{' or parens or brackets:
                continue
        if word == '{':
            depth += 1
            opened = True
        elif word == '}':
            depth -= 1
            if opened and depth == 0:
                return start, end
    raise ValueError('Unclosed original player function: ' + name)


def apply_and_record(repo, relative, source, output):
    path = APP + relative.removeprefix('com/android/purebilibili/')
    if path not in {TITLE, SUBTITLE}:
        return source
    original = source
    fixed = fixed_sources(repo)[path]
    edits = []

    def swap(before, after, label):
        nonlocal source
        if source.count(before) != 1:
            raise ValueError('Unknown actual player boundary: ' + label)
        at = source.index(before)
        edits.append(dict(offset=at, before=before, after=after, label=label))
        source = source[:at] + after + source[at + len(before):]

    def import_original(line):
        if fixed.count(line + '\n') != 1:
            raise ValueError('Original player import is not pinned')
        if line + '\n' not in source:
            package = re.search(r'(?m)^package [^\n]+\n', source)
            if package is None:
                raise ValueError('Missing actual player package')
            before = package.group(0)
            swap(before, before + '\n' + line + '\n', 'Original v042 player import')

    if path == TITLE:
        a, b = function_span(repo, source, 'PlayerTitleInfoDock')
        c, d = function_span(repo, fixed, 'PlayerTitleInfoDock')
        selected = fixed[c:d]
        if 'val motionEnabled = LocalComponentMotionEnabled.current' not in selected or 'Modifier.basicMarquee()' not in selected:
            raise ValueError('Unknown original v042 title motion policy')
        swap(source[a:b], selected, 'Whole original bounded title dock and reduced-motion fallback')
        import_original('import com.android.purebilibili.core.ui.LocalComponentMotionEnabled')
    else:
        a, b = function_span(repo, source, 'PortraitSubtitlePanel')
        before = 'modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),'
        after = 'modifier = Modifier\n                    .verticalScroll(rememberScrollState())\n                    .padding(horizontal = 10.dp, vertical = 8.dp),'
        if source[a:b].count(before) != 1 or fixed.count(after) != 1:
            raise ValueError('Unknown original bounded subtitle panel column')
        swap(source[a:b], source[a:b].replace(before, after, 1), 'Original scrolling in the existing available-height subtitle panel')
        import_original('import androidx.compose.foundation.rememberScrollState')
        import_original('import androidx.compose.foundation.verticalScroll')

    reverse = source
    for edit in reversed(edits):
        a = edit['offset']
        if reverse[a:a + len(edit['after'])] != edit['after']:
            raise ValueError('Player readability inverse boundary changed')
        reverse = reverse[:a] + edit['before'] + reverse[a + len(edit['after']):]
    if reverse != original:
        raise ValueError('Original player boundary was not preserved')
    proof = dict(upstreamCommit=COMMIT, sourcePath=path, sourceManifestSHA256=MANIFEST_SHA256,
        sourceSHA256=sha(fixed.encode()), inputSHA256=sha(original.encode()), outputSHA256=sha(source.encode()),
        fullInputInverseRecovered=True, edits=edits, partialReuse=True, mainCompiled=False,
        pixelsValidated=False, applicationExecuted=False)
    target = Path(output) / 'v042-player-readability-proofs' / (Path(path).name + '.json')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(proof, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    return source
