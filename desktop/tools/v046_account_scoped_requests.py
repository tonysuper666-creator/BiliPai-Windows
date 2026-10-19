"""Whole v0.4.6 AccountScopedRequests and its original test, emitted by the Home view model producer.

AccountScopedRequests is the upstream owner that cancels, joins and resets the requests of a page when the
app account changes. The pinned v0.2.5 tree has no such file, and the Windows tree has no TokenManager, so the
one hash-pinned adaptation drops the Android default `TokenManager.accountIdentity`: a caller must pass Root's
identity flow. Windows pages are not rebuilt on it: every personal page owner is already retired and rebuilt per
Root session epoch (see test_v046_account_scoped_requests_source.py, which audits each owner), so this only
supplies the definer that the carried favorite and home archives import.
"""
from pathlib import Path
import hashlib
import json
import os
import stat

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-account-scoped-requests'
MANIFEST_SHA256 = 'a66da43dc59750d25af177a495e18f4959b961f5cd6ec1e8523bf3766b3d2460'
MAIN = 'AccountScopedRequests.kt'
TEST = 'AccountScopedRequestsTest.kt'
EXPECTED = {MAIN, TEST}
PACKAGE = 'com.android.purebilibili.core.coroutines'
RELATIVE = {name: PACKAGE.replace('.', '/') + '/' + name for name in (MAIN, TEST)}
UPSTREAM_PATHS = {
    MAIN: 'app/src/main/java/com/android/purebilibili/core/coroutines/AccountScopedRequests.kt',
    TEST: 'app/src/test/java/com/android/purebilibili/core/coroutines/AccountScopedRequestsTest.kt',
}
TEST_CASES = 3
PROOF = 'v046-account-scoped-requests-proof.json'
IDENTITY_IMPORT = 'import com.android.purebilibili.core.store.AccountSessionIdentity\n'
# The only two hunks. Both exist because the Windows tree has no com.android.purebilibili.core.store.TokenManager.
IMPORT_BEFORE = IDENTITY_IMPORT + 'import com.android.purebilibili.core.store.TokenManager\n'
IMPORT_AFTER = IDENTITY_IMPORT
PARAMETER_BEFORE = '    private val identities: StateFlow<AccountSessionIdentity> = TokenManager.accountIdentity,\n'
PARAMETER_AFTER = '    private val identities: StateFlow<AccountSessionIdentity>,\n'
HUNKS = ((IMPORT_BEFORE, IMPORT_AFTER), (PARAMETER_BEFORE, PARAMETER_AFTER))
DECLARATION = 'internal class AccountScopedRequests('


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def fixed(repo):
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing original account scoped request inputs')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'},
         'Account scoped request input leaves changed')
    manifest_raw = (root / 'manifest.json').read_bytes()
    need(b'\r' not in manifest_raw and sha(manifest_raw) == MANIFEST_SHA256, 'Account scoped request manifest changed')
    manifest = json.loads(manifest_raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'Account scoped request source identity changed')
    need(len(manifest['files']) == len(EXPECTED) and {row['filename'] for row in manifest['files']} == EXPECTED,
         'Account scoped request source set changed')
    sources, raws = {}, {}
    for row in manifest['files']:
        file = root / row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous account scoped request source')
        need(row['path'] == UPSTREAM_PATHS[file.name] and row['path'].endswith(RELATIVE[file.name]),
             'Account scoped request upstream path changed: ' + file.name)
        leaf = file.read_bytes()
        need(b'\r' not in leaf and len(leaf) == row['bytes'] and sha(leaf) == row['sha256'],
             'Account scoped request bytes changed: ' + file.name)
        need(hashlib.sha1(b'blob ' + str(len(leaf)).encode() + b'\0' + leaf).hexdigest() == row['gitBlob'],
             'Account scoped request Git blob changed: ' + file.name)
        text = leaf.decode('utf8')
        need(text.encode('utf8') == leaf and text.endswith('\n') and ('package ' + PACKAGE + '\n') in text[:400],
             'Account scoped request text changed: ' + file.name)
        sources[file.name] = text
        raws[file.name] = leaf
    need(sources[MAIN].count(DECLARATION) == 1, 'Account scoped request declaration changed')
    need(raws[TEST].count(b'    @Test\n') == TEST_CASES, 'Original account scoped request test count changed')
    license_file = root / 'LICENSE'
    need(not license_file.is_symlink() and sha(license_file.read_bytes()) == manifest['license']['sha256'],
         'Account scoped request license changed')
    return sources, raws, manifest


def validate_outputs(repo, *outputs):
    fixed(repo)
    root = (Path(repo) / ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)),
             'Output overlaps original account scoped request inputs')


def adapt(repo):
    """The whole original with the two TokenManager hunks applied; returns (text, proof)."""
    sources, _raws, _manifest = fixed(repo)
    original = sources[MAIN]
    adapted = original
    for before, after in HUNKS:
        need(adapted.count(before) == 1, 'Account scoped request anchor is not unique: ' + before.strip())
        adapted = adapted.replace(before, after, 1)
    need('TokenManager' not in adapted and 'accountIdentity' not in adapted,
         'Android TokenManager reference remains in the account scoped request owner')
    need(restore(adapted) == original, 'Whole original account scoped request inverse failed')
    return adapted, dict(
        upstreamCommit=COMMIT, source=UPSTREAM_PATHS[MAIN], archive=ARCHIVE, manifestSHA256=MANIFEST_SHA256,
        originalSHA256=sha(original), adaptedSHA256=sha(adapted), wholeOriginal=True, completeOriginalInverse=True,
        hunks=[dict(before=before, after=after) for before, after in HUNKS],
        semanticAdaptation='The identity flow is a required constructor parameter; Windows has no TokenManager.accountIdentity',
        originalJvmCases=TEST_CASES, jvmTestsExecuted=False, applicationExecuted=False)


def restore(adapted):
    """Peel the two hunks off an adapted body, asserting each anchor is unique."""
    text = adapted
    for before, after in reversed(HUNKS):
        need(text.count(after) == 1, 'Adapted account scoped request anchor is not unique: ' + after.strip())
        text = text.replace(after, before, 1)
    return text


def original_test(repo):
    return fixed(repo)[1][TEST]


def _target(root, name):
    base = Path(root).resolve()
    relative = RELATIVE[name]
    target = base / relative
    # A leaf symlink must stay unresolved; resolve() would follow it and write through it.
    if not target.is_symlink():
        target = target.resolve()
        need(target.is_relative_to(base), 'Account scoped request target escapes the output root: ' + relative)
    return target, relative


def _require_regular_or_absent(target, relative):
    try:
        info = os.lstat(target)
    except FileNotFoundError:
        return
    need(stat.S_ISREG(info.st_mode), 'Account scoped request target is not a regular file: ' + relative)


def _write(target, relative, data):
    _require_regular_or_absent(target, relative)
    target.parent.mkdir(parents=True, exist_ok=True)
    _require_regular_or_absent(target, relative)
    target.write_bytes(data)
    written = target.read_bytes()
    need(written == data, 'Account scoped request readback differs: ' + relative)
    return {'path': relative, 'sha256': sha(written)}


def emit(repo, output, test_output=None):
    """Write the adapted owner (and, with a test root, the original test) plus its proof; returns the receipt."""
    validate_outputs(repo, output, test_output)
    adapted, proof = adapt(repo)
    original_test_bytes = original_test(repo)
    main_target, main_relative = _target(output, MAIN)
    proof_target = Path(output).resolve() / PROOF
    test_target = test_relative = None
    if test_output is not None:
        test_target, test_relative = _target(test_output, TEST)
    _require_regular_or_absent(main_target, main_relative)
    _require_regular_or_absent(proof_target, PROOF)
    if test_target is not None:
        _require_regular_or_absent(test_target, test_relative)
    receipt = [_write(main_target, main_relative, adapted.encode('utf8'))]
    proof_bytes = (json.dumps(proof, ensure_ascii=False, indent=2) + '\n').encode('utf8')
    receipt.append(_write(proof_target, PROOF, proof_bytes))
    if test_target is not None:
        receipt.append(_write(test_target, test_relative, original_test_bytes))
    return {'files': receipt}
