"""Test-only verification of actual engagement receipts and pinned v029 inputs."""
import hashlib
import json
from pathlib import Path

from desktop_paths import long_path
import v046_follow_confirmation as follow

VM = 'com/android/purebilibili/feature/video/viewmodel/VideoEngagementViewModel.kt'
HASHES = {
    'final': 'f7f08003b1e54115e33e0953380c4dfaa45c28777a8932f1ba272374f96a3fc0',
    'like': '923f65151ec76950c590b227e9fb3c16627db3dcdf31bf6b5586d93113bd1a26',
    'triple': 'b5434f08fab19fb3fef48e4558b8a2be21a4ab5d69f4391acddfc51bcd811478',
    'owner-follow': 'b4e70587658931c63781f90f0de529a97b61344cd697217d9083a741c33268ea',
    'coin-request': 'ccacc36d69286436bd9b67bb65f2620db05145fc211f26b1816561878446c974',
    'lifetime': '0a943503cbbfba372e4355e7ddac6dddccac3384eae7d334a7a0d77b65c09170',
    'origin': '50d878aa1cfacb9913fdd7f6bd20e10a47052729bba183349a93e98e33415908',
    'state': 'b3214587cdb01e8e8a16c85ed2bdd80b6abdf2cd96c994957a4f40ecd7cd8573',
}
RECEIPTS = [
    ('like', 'v046-like-write-guard-proofs/VideoEngagementViewModel.kt.json'),
    ('triple', 'v046-triple-submission-proofs/VideoEngagementViewModel.kt.json'),
    ('owner-follow', 'v042-owner-follow-projection-proof.json'),
    ('coin-request', 'v037-video-coin-request-boundary-proof.json'),
    ('lifetime', 'v029-video-feedback-lifetime-proof.json'),
    ('origin', 'v029-video-feedback-origin-proof.json'),
    ('state', 'v029-video-feedback-state-proof.json'),
]
FIXTURE_PINS = {
    'pre-feedback-VideoEngagementViewModel.kt': ('c60acf33697141b6eff513f49162820a21ffcd9b7550aba411ae4f199d3837fe', 27237),
    'v029-video-feedback-state-proof.json': ('b4c787351a360d04c915ee8e45f2b92613876101a7bc23f1345d27b3f758438e', 28357),
    'v029-video-feedback-origin-proof.json': ('547edf65f7e62b17ad42cd27a8ac78d7a0da1e56f5f1cba21060f97bb99c2854', 5412),
}


def sha(text):
    return hashlib.sha256(text.encode('utf-8')).hexdigest()


def read(path):
    return long_path(Path(path).absolute()).read_bytes().decode('utf-8').replace('\r\n', '\n')


def historical_inputs():
    root = Path(__file__).parent / 'fixtures/ton1228-v029-video-feedback'
    provenance = json.loads(read(root / 'provenance.json'))
    assert provenance['sourceCommit'] == '6f972611b6c0968b715e73e5c2ffe052e8abe421'
    assert provenance['crossCheckCommit'] == '5ae0e7a6748ec57751a12242b69712ab14161080'
    assert provenance['crossCheckByteIdentical'] is True
    data = {}
    for name, (digest, size) in FIXTURE_PINS.items():
        assert provenance['files'][name] == dict(sha256=digest, bytes=size), name
        raw = long_path((root / name).absolute()).read_bytes()
        assert len(raw) == size and hashlib.sha256(raw).hexdigest() == digest, name
        data[name] = raw.decode('utf-8')
    return (data['pre-feedback-VideoEngagementViewModel.kt'],
            json.loads(data['v029-video-feedback-state-proof.json']),
            json.loads(data['v029-video-feedback-origin-proof.json']))


class EngagementChain:
    def __init__(self, case, repo, output):
        self.case, self.repo, self.output = case, repo, output
        self.proofs = {name: json.loads(read(output / path)) for name, path in RECEIPTS}

    def transform(self, text, proof, reverse):
        c = self.case
        before = proof.get('inputSHA256', proof.get('beforeSha256LF'))
        after = proof.get('outputSHA256', proof.get('afterSha256LF'))
        c.assertEqual(after if reverse else before, sha(text))
        indexed = 'indexedEdits' in proof
        edits = proof['indexedEdits'] if indexed else proof.get('edits', [proof])
        # Whole-source offsets refer to the two complete strings. Sequential
        # receipts instead record offsets in the text at each individual edit.
        ordered = reversed(edits) if reverse or indexed else edits
        for edit in ordered:
            old, new = (edit['after'], edit['before']) if reverse else (edit['before'], edit['after'])
            if indexed:
                at = edit['afterOffset' if reverse else 'beforeOffset']
            elif 'offset' in edit:
                at = edit['offset']
            else:
                c.assertEqual(1, text.count(old), edit.get('label'))
                at = text.index(old)
            c.assertEqual(old, text[at:at + len(old)], edit.get('label'))
            text = text[:at] + new + text[at + len(old):]
        c.assertEqual(before if reverse else after, sha(text))
        return text

    def follow_noop(self, text):
        path = long_path((self.output / follow.PROOF_DIRECTORY / 'VideoEngagementViewModel.kt.json').absolute())
        self.case.assertFalse(path.exists())
        result = follow.apply_and_record(self.repo, VM, text, self.output)
        self.case.assertEqual(text.encode('utf-8'), result.encode('utf-8'))
        self.case.assertFalse(path.exists())
        return result

    def peel_v046(self, text):
        self.case.assertEqual(HASHES['final'], sha(text))
        text = self.transform(text, self.proofs['like'], True)
        self.case.assertEqual(HASHES['like'], sha(text))
        text = self.follow_noop(text)
        text = self.transform(text, self.proofs['triple'], True)
        self.case.assertEqual(HASHES['triple'], sha(text))
        return text

    def replay_v046(self, text):
        text = self.transform(text, self.proofs['triple'], False)
        text = self.follow_noop(text)
        return self.transform(text, self.proofs['like'], False)

    def peel(self, text, through='state'):
        stages = {'final': text}
        text = self.peel_v046(text)
        stages['triple'] = text
        for name, _ in RECEIPTS[2:]:
            text = self.transform(text, self.proofs[name], True)
            self.case.assertEqual(HASHES[name], sha(text), name)
            stages[name] = text
            if name == through:
                break
        return stages

    def replay(self, text):
        for name, _ in reversed(RECEIPTS[2:]):
            text = self.transform(text, self.proofs[name], False)
        text = self.replay_v046(text)
        self.case.assertEqual(HASHES['final'], sha(text))
        return text
