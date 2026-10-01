"""Verify both appended request blocks against their sole original-source producers."""
from pathlib import Path
import argparse
import hashlib
import json

COMMENT_MARKER = '// Paste inside existing DesktopDynamicCardOperations; no package/class/API/model producer.'
DETAIL_MARKER = '// Additional members inside the existing DesktopDynamicCardOperations; no replacement Ops file.'
GRADE_MARKER = '// STABLE_VIDEO_VOTE_GRADE_MEMBERS'


def lf(path):
    return path.read_text(encoding='utf-8').replace('\r\n', '\n')


def main():
    cli = argparse.ArgumentParser()
    cli.add_argument('--repo', type=Path, required=True)
    cli.add_argument('--comment-output', type=Path, required=True)
    cli.add_argument('--detail-output', type=Path, required=True)
    cli.add_argument('--grade-output', type=Path, required=True)
    cli.add_argument('--output', type=Path, required=True)
    args = cli.parse_args()
    operations = args.repo / 'desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt'
    actual = lf(operations)
    assert actual.count(COMMENT_MARKER) == actual.count(DETAIL_MARKER) == 1
    start, middle = actual.index(COMMENT_MARKER), actual.index(DETAIL_MARKER)
    assert start < middle
    end = actual.rfind('\n}')
    assert actual.count(GRADE_MARKER) == 1
    grade_start = actual.index(GRADE_MARKER)
    assert end > grade_start > middle and not actual[end + 2:].strip()
    fragments = {
        'comment': (args.comment_output / 'DesktopDynamicCommentOperations.fragment.kt', actual[start:middle]),
        'detail': (args.detail_output / 'DesktopDynamicDetailOperations.fragment.kt', actual[middle:grade_start]),
        'grade': (args.grade_output, actual[grade_start:end]),
    }
    hashes = {}
    for name, (path, selected) in fragments.items():
        expected = lf(path).rstrip('\n')
        if name == 'grade':
            raw = path.read_bytes()
            assert hashlib.sha256(raw).hexdigest() == '46c54c11f7e8f1461dcf92989058eccd1497fd1da69e0fd7f6ca979f6814b3a3'
            expected = expected.lstrip('\n')
        assert selected.rstrip('\n') == expected, f'{name} request members differ from their original-source producer.'
        hashes[name] = hashlib.sha256(expected.encode('utf-8')).hexdigest()
    assert actual.count('suspend fun getPublishedDynamicDetail') == 1
    assert 'read { dynamic.getDynamicDetail(id) }' in actual
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({
        'passed': True, 'soleOperationsProducer': True,
        'memberSha256Lf': hashes,
        'operationsSha256Lf': hashlib.sha256(actual.encode('utf-8')).hexdigest(),
        'authPublishVerificationPreserved': True,
    }, indent=2) + '\n', encoding='utf-8', newline='\n')
    print('PASS original-source detail, reply and grade request blocks in the existing Operations class')


if __name__ == '__main__':
    main()
