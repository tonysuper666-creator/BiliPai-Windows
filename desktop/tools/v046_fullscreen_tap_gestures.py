"""v0.4.6 fullscreen overlay: main onTap/onDoubleTap block uses detectPlayerTapGestures.

The androidx detectTapGestures import stays for the ratio-menu dismiss call.
"""
from pathlib import Path
import hashlib
import json

UPSTREAM_COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
UPSTREAM_PATH = 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/FullscreenPlayerOverlay.kt'
UPSTREAM_BLOB = '80fc2e9a492463fc4c7dc3ec9f04e0da3f0c9b39'
UPSTREAM_IMPORT_LINE = 'import com.android.purebilibili.feature.video.ui.gesture.detectPlayerTapGestures\n'
UPSTREAM_IMPORT_LINE_SHA256 = '0cfb4ab73d32ba5726522f81e6e38e148f8d816b993a76440ffa2703dfccb108'
UPSTREAM_CALL_CONTEXT = '                detectPlayerTapGestures(\n                    onTap = {'
UPSTREAM_CALL_CONTEXT_SHA256 = '0132c8a086e58201ed77168b7dfbf715e7f47bdc6c420808997e917bbf1d2598'
OUTPUT_RELATIVE = 'com/android/purebilibili/feature/video/ui/overlay/FullscreenPlayerOverlay.kt'
IMPORT_BEFORE = 'import androidx.compose.foundation.gestures.detectTapGestures\n'
CALL_BEFORE = '                detectTapGestures(\n                    onTap = {'
CALL_AFTER = UPSTREAM_CALL_CONTEXT
RATIO_CALL = 'detectTapGestures { showRatioMenu = false }'
ALLOWED_PARAMETERS = ('onTap', 'onDoubleTap')


def sha(text):
    if isinstance(text, str):
        text = text.encode()
    return hashlib.sha256(text).hexdigest()


def assert_pins():
    if sha(UPSTREAM_IMPORT_LINE) != UPSTREAM_IMPORT_LINE_SHA256:
        raise ValueError('Fullscreen tap upstream import pin is inconsistent')
    if sha(UPSTREAM_CALL_CONTEXT) != UPSTREAM_CALL_CONTEXT_SHA256:
        raise ValueError('Fullscreen tap upstream call pin is inconsistent')
    if UPSTREAM_CALL_CONTEXT != CALL_BEFORE.replace('detectTapGestures(', 'detectPlayerTapGestures(', 1):
        raise ValueError('Fullscreen tap call pin does not match the counted edit')
    if not (len(UPSTREAM_COMMIT) == len(UPSTREAM_BLOB) == 40 and UPSTREAM_PATH.endswith('FullscreenPlayerOverlay.kt')):
        raise ValueError('Fullscreen tap upstream identity pin is inconsistent')
    if UPSTREAM_IMPORT_LINE.count('\n') != 1 or not UPSTREAM_IMPORT_LINE.endswith('\n'):
        raise ValueError('Fullscreen tap import pin is not one line')


def call_parameter_names(source, call_at):
    """Top-level named arguments of the main tap call. Stops if the call is not parenthesized."""
    open_paren = source.find('(', call_at, call_at + len(CALL_BEFORE))
    if open_paren < 0:
        raise ValueError('Fullscreen tap call is not parenthesized')
    i = open_paren + 1
    depth_paren, depth_brace, depth_bracket = 1, 0, 0
    buf = []
    names = []
    in_line = False
    in_block = False
    quote = None
    escape = False

    def flush(fragment):
        text = ''.join(fragment).strip()
        if not text:
            return
        head = text.split('=', 1)[0].strip().split('//', 1)[0].strip()
        if not head.isidentifier():
            raise ValueError('Fullscreen tap parameter is not a name')
        names.append(head)

    while i < len(source):
        char = source[i]
        nxt = source[i + 1] if i + 1 < len(source) else ''
        if in_line:
            if char == '\n':
                in_line = False
            buf.append(char)
            i += 1
            continue
        if in_block:
            if char == '*' and nxt == '/':
                in_block = False
                buf.append(char)
                buf.append(nxt)
                i += 2
                continue
            buf.append(char)
            i += 1
            continue
        if quote:
            buf.append(char)
            if escape:
                escape = False
            elif char == '\\':
                escape = True
            elif char == quote:
                quote = None
            i += 1
            continue
        if char == '/' and nxt == '/':
            in_line = True
            buf.append(char)
            i += 1
            continue
        if char == '/' and nxt == '*':
            in_block = True
            buf.append(char)
            i += 1
            continue
        if char in ('"', "'"):
            quote = char
            buf.append(char)
            i += 1
            continue
        if char == '(':
            depth_paren += 1
        elif char == ')':
            depth_paren -= 1
            if depth_paren == 0 and depth_brace == 0 and depth_bracket == 0:
                flush(buf)
                return names
        elif char == '{':
            depth_brace += 1
        elif char == '}':
            depth_brace -= 1
        elif char == '[':
            depth_bracket += 1
        elif char == ']':
            depth_bracket -= 1
        elif char == ',' and depth_paren == 1 and depth_brace == 0 and depth_bracket == 0:
            flush(buf)
            buf = []
            i += 1
            continue
        buf.append(char)
        i += 1
    raise ValueError('Unclosed fullscreen tap call')


def _swap(source, edits, before, after, label):
    if source.count(before) != 1:
        raise ValueError('Fullscreen tap anchor changed: ' + label)
    at = source.index(before)
    edits.append(dict(offset=at, before=before, after=after, count=1, label=label))
    return source[:at] + after + source[at + len(before):]


def apply_and_record(_repo, relative, source, output):
    assert_pins()
    if relative != OUTPUT_RELATIVE:
        return source
    original = source
    if source.count(IMPORT_BEFORE) != 1 or source.count(UPSTREAM_IMPORT_LINE) != 0:
        raise ValueError('Fullscreen tap import anchor changed')
    if source.count(CALL_BEFORE) != 1 or source.count(CALL_AFTER) != 0:
        raise ValueError('Fullscreen tap call anchor changed')
    if source.count(RATIO_CALL) != 1:
        raise ValueError('Fullscreen ratio-menu tap anchor changed')
    names = call_parameter_names(source, source.index(CALL_BEFORE))
    if tuple(names) != ALLOWED_PARAMETERS:
        raise ValueError('Fullscreen tap block parameters are not only onTap and onDoubleTap')
    edits = []
    source = _swap(source, edits, IMPORT_BEFORE, IMPORT_BEFORE + UPSTREAM_IMPORT_LINE,
                   'Import detectPlayerTapGestures after the retained androidx detectTapGestures import')
    source = _swap(source, edits, CALL_BEFORE, CALL_AFTER,
                   'Main fullscreen onTap/onDoubleTap block uses detectPlayerTapGestures')
    if any(edit['count'] != 1 for edit in edits) or len(edits) != 2:
        raise ValueError('Fullscreen tap edits were not two counted replacements')
    if source.count(RATIO_CALL) != 1 or source.count('detectPlayerTapGestures(') != 1:
        raise ValueError('Fullscreen tap replacement disturbed another call')
    if source.count(UPSTREAM_IMPORT_LINE) != 1 or source.count(IMPORT_BEFORE) != 1:
        raise ValueError('Fullscreen tap import was not inserted once')
    reverse = source
    for edit in reversed(edits):
        at = edit['offset']
        if reverse[at:at + len(edit['after'])] != edit['after']:
            raise ValueError('Fullscreen tap inverse boundary changed')
        reverse = reverse[:at] + edit['before'] + reverse[at + len(edit['after']):]
    if reverse != original:
        raise ValueError('Fullscreen tap input was not preserved')
    proof = dict(
        upstreamCommit=UPSTREAM_COMMIT,
        sourcePath=UPSTREAM_PATH,
        upstreamBlob=UPSTREAM_BLOB,
        upstreamImportLineSHA256=UPSTREAM_IMPORT_LINE_SHA256,
        upstreamCallContextSHA256=UPSTREAM_CALL_CONTEXT_SHA256,
        inputSHA256=sha(original),
        outputSHA256=sha(source),
        fullInputInverseRecovered=True,
        edits=edits,
        mainCompiled=False,
        applicationExecuted=False,
    )
    target = Path(output) / 'v046-fullscreen-tap-gestures-proofs' / (Path(OUTPUT_RELATIVE).name + '.json')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(proof, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    return source
