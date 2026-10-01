"""Keep the existing Operations class's selected editor members tied to upstream."""
from pathlib import Path
import argparse, hashlib, importlib.util, json, sys
sys.dont_write_bytecode = True

def main():
    args = argparse.ArgumentParser()
    args.add_argument('--repo', type=Path, required=True)
    args.add_argument('--output', type=Path, required=True)
    options = args.parse_args()
    repo = options.repo.resolve()
    producer = repo / 'desktop/tools/extract-upstream-dynamic-editor-protocol.py'
    spec = importlib.util.spec_from_file_location('desktop_editor_protocol', producer)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    expected = module.generate_members(repo).rstrip('\n')
    operations = repo / 'desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt'
    actual = operations.read_text(encoding='utf-8').replace('\r\n', '\n')
    marker = '// GENERATED original editor members; do not hand-maintain a second request algorithm.'
    assert actual.count(marker) == 1
    reply_marker = '// Paste inside existing DesktopDynamicCardOperations; no package/class/API/model producer.'
    assert actual.count(reply_marker) in (0, 1)
    start = actual.index(marker)
    if reply_marker in actual:
        stop = actual.index(reply_marker)
        assert stop > start, 'Reply members must follow the original editor block.'
        selected = actual[start:stop].rstrip('\n')
    else:
        selected = actual[start:].rsplit('\n}', 1)[0].rstrip('\n')
    assert selected == expected, 'Editor request members differ from the original-source producer. Regenerate the existing Operations member block.'
    options.output.parent.mkdir(parents=True, exist_ok=True)
    options.output.write_text(json.dumps({'passed': True, 'soleOperationsProducer': True,
        'memberSha256Lf': hashlib.sha256(expected.encode()).hexdigest(),
        'producerSha256Lf': hashlib.sha256(producer.read_bytes().replace(b'\r\n', b'\n')).hexdigest(),
        'operationsSha256Lf': hashlib.sha256(actual.encode()).hexdigest(),
        'businessRequestsSelectedFromUpstream': True}, indent=2)+'\n', encoding='utf-8', newline='\n')
    print('PASS original-source editor request members in the existing Operations class')

if __name__ == '__main__': main()
