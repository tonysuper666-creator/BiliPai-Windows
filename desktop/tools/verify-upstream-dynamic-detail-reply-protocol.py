"""Verify both appended request blocks against their sole original-source producers."""
from pathlib import Path
import argparse
import hashlib
import json

COMMENT_MARKER = '// Paste inside existing DesktopDynamicCardOperations; no package/class/API/model producer.'
DETAIL_MARKER = '// Additional members inside the existing DesktopDynamicCardOperations; no replacement Ops file.'
GRADE_MARKER = '// STABLE_VIDEO_VOTE_GRADE_MEMBERS'
FRAUD_MARKER = '// STABLE_ORIGINAL_COMMENT_FRAUD_MEMBERS'
BGM_MARKER = '    // STABLE_ORIGINAL_BGM_MEMBERS'


def lf(path):
    return path.read_text(encoding='utf-8').replace('\r\n', '\n')


def main():
    cli = argparse.ArgumentParser()
    cli.add_argument('--repo', type=Path, required=True)
    cli.add_argument('--comment-output', type=Path, required=True)
    cli.add_argument('--detail-output', type=Path, required=True)
    cli.add_argument('--grade-output', type=Path, required=True)
    cli.add_argument('--fraud-output', type=Path, required=True)
    cli.add_argument('--bgm-output', type=Path, required=True)
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
    assert actual.count(FRAUD_MARKER) == actual.count(BGM_MARKER) == 1
    fraud_start, bgm_start = actual.index(FRAUD_MARKER), actual.index(BGM_MARKER)
    assert end > bgm_start > fraud_start > grade_start > middle and not actual[end + 2:].strip()
    fragments = {
        'comment': (args.comment_output / 'DesktopDynamicCommentOperations.fragment.kt', actual[start:middle]),
        'detail': (args.detail_output / 'DesktopDynamicDetailOperations.fragment.kt', actual[middle:grade_start]),
        'grade': (args.grade_output, actual[grade_start:fraud_start]),
        'bgm': (args.bgm_output, actual[bgm_start:end]),
    }
    hashes = {}
    for name, (path, selected) in fragments.items():
        expected = lf(path).rstrip('\n')
        if name == 'grade':
            raw = path.read_bytes()
            assert hashlib.sha256(raw).hexdigest() == '9e8cc08a84eb42cb1ae59364598d91a9d4920a55dfe20a136889bf2112a37ba8'
            expected = expected.lstrip('\n')
        if name == 'bgm':
            expected = expected.lstrip('\n')
        assert selected.rstrip('\n') == expected, f'{name} request members differ from their original-source producer.'
        hashes[name] = hashlib.sha256(expected.encode('utf-8')).hexdigest()
    fraud_source = json.loads(lf(args.fraud_output))
    expected_fraud = FRAUD_MARKER + '\n' + fraud_source['operationsMemberFragment'].lstrip('\n').rstrip('\n')
    assert actual[fraud_start:bgm_start].rstrip('\n') == expected_fraud, 'fraud request members differ from their sole original-source producer.'
    hashes['fraud'] = hashlib.sha256(expected_fraud.encode()).hexdigest()
    assert actual.count('suspend fun getPublishedDynamicDetail') == 1
    assert 'read { dynamic.getDynamicDetail(id) }' in actual
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({
        'passed': True, 'soleOperationsProducer': True,
        'memberSha256Lf': hashes,
        'operationsSha256Lf': hashlib.sha256(actual.encode('utf-8')).hexdigest(),
        'authPublishVerificationPreserved': True,
    }, indent=2) + '\n', encoding='utf-8', newline='\n')
    print('PASS five exact original-source request families in the existing Operations class')


if __name__ == '__main__':
    main()
