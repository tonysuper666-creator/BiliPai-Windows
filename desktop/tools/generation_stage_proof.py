"""Receipts for later generator stages, before checking earlier pinned contracts."""
from pathlib import Path
import hashlib
import json

from v029_comment_search import whole_proof

OWNER_SPEED = 'playback-speed-source-continuity'
CENTER_BUFFERING = 'buffering-center-playback'
PGC_BUFFERING = 'pgc-buffering-playback-toggle'


def receipt_path(output, stage, path):
    return Path(output) / 'generation-stage-proofs' / stage / (Path(path).name + '.json')


def record_stage(output, stage, path, before, after):
    if before == after:
        return
    proof = dict(stage=stage, path=path, **whole_proof(before, after))
    target = receipt_path(output, stage, path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(proof, ensure_ascii=False, indent=2) + '\n', encoding='utf8', newline='\n')


def capture_ui_stage(output, stage, transform):
    """Wrap an existing UI delta in tests without changing its source or result."""
    def captured(body, path):
        result, edits = transform(body, path)
        record_stage(output, stage, path, body, result)
        return result, edits
    return captured


def restore_stage(output, stage, path, body):
    proof = json.loads(receipt_path(output, stage, path).read_text(encoding='utf8'))
    if proof['stage'] != stage or proof['path'] != path or proof['exactCompleteInverse'] is not True:
        raise ValueError('Generation stage identity mismatch')
    if hashlib.sha256(body.encode()).hexdigest() != proof['afterSha256LF']:
        raise ValueError('Generation stage output hash mismatch: ' + stage)
    for edit in reversed(proof['indexedEdits']):
        at = edit['afterOffset']
        if body[at:at + len(edit['after'])] != edit['after']:
            raise ValueError('Generation stage inverse span mismatch: ' + stage)
        body = body[:at] + edit['before'] + body[at + len(edit['after']):]
    if hashlib.sha256(body.encode()).hexdigest() != proof['beforeSha256LF']:
        raise ValueError('Generation stage input hash mismatch: ' + stage)
    return body
