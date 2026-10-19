"""v0.4.6 RSS public content network on the existing plugin producer.

PublicContentNetwork.kt is emitted verbatim and its original JVM test with it. The Windows FeedFetcher advances
by one pinned hunk: feeds and article pages leave through the anonymous public content client. Article
extraction is not changed: the v0.4.6 extractor imports Readability4J, which Root decision D7 keeps out of the
build, and the Readability4J notice is only staged in the archive. Nothing is compiled or executed here.
"""
from pathlib import Path
import hashlib
import json
import os
import stat

from v044_favorite_detail import inverse_proof

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-rss-public-content'
MANIFEST_SHA256 = '3b2818d306d31d2c22118a3bd796f9181ac8102406676708fb5396f51bc8a2d8'
FEED_PACKAGE = 'com.android.purebilibili.core.plugin.feed'
FEED_DIRECTORY = 'com/android/purebilibili/core/plugin/feed/'
NETWORK = 'PublicContentNetwork.kt'
NETWORK_TEST = 'PublicContentNetworkTest.kt'
NOTICE = 'readability4j.LICENSE'
EXPECTED = {NETWORK, NETWORK_TEST, NOTICE}
UPSTREAM_PATHS = {
    NETWORK: 'app/src/main/java/com/android/purebilibili/core/plugin/feed/PublicContentNetwork.kt',
    NETWORK_TEST: 'app/src/test/java/com/android/purebilibili/core/plugin/feed/PublicContentNetworkTest.kt',
    NOTICE: 'app/src/main/assets/licenses/readability4j.LICENSE',
}
NETWORK_TEST_CASES = 8
# Top-level declarations of the carried file; each must appear exactly once.
DECLARATIONS = (
    'internal val publicContentHttpClient: OkHttpClient by lazy',
    'internal fun createPublicContentHttpClient(): OkHttpClient',
    'internal fun sanitizePublicContentUrl(url: HttpUrl): HttpUrl',
    'internal fun sanitizePublicContentRequest(request: Request): Request',
    'internal val publicContentImageHttpClient: OkHttpClient by lazy',
    'internal fun withPublicImageReferer(request: Request): Request',
    'internal fun publicImageReferer(url: HttpUrl): String',
    'internal fun publicSiteRoot(host: String): String',
    'internal fun downloadPublicContentToFile(url: String, file: java.io.File)',
)
PROOF_NAME = 'v046-rss-public-content-proof.json'
FEED_FETCHER_PATH = 'app/src/main/java/com/android/purebilibili/core/plugin/feed/FeedFetcher.kt'
# LF SHA-256 of the Windows FeedFetcher body that the plugin producer holds after its own substitutions.
FEED_FETCHER_PREDECESSOR_SHA256 = 'eeef342d9eb115bc168bb9442f83f8013d531badd6a41b6e2f05bd0f132902e9'

FETCHER_CLIENT_BEFORE = '    com.bilipai.desktop.plugins.DesktopPluginNetwork.publicClient.newBuilder()\n'
FETCHER_CLIENT_AFTER = (
    '    // RSS feeds and article pages use the anonymous public content client (PublicContentNetwork.kt):\n'
    '    // no cookie jar or authenticator, and credentials are stripped again at every redirect hop.\n'
    '    publicContentHttpClient.newBuilder()\n')


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def fixed(repo):
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing original RSS public content inputs')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'},
         'RSS public content input leaves changed')
    manifest_raw = (root / 'manifest.json').read_bytes()
    need(b'\r' not in manifest_raw and sha(manifest_raw) == MANIFEST_SHA256, 'RSS public content manifest changed')
    manifest = json.loads(manifest_raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'RSS public content source identity changed')
    need(len(manifest['files']) == len(EXPECTED) and {row['filename'] for row in manifest['files']} == EXPECTED,
         'RSS public content source set changed')
    sources, raws = {}, {}
    for row in manifest['files']:
        file = root / row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous RSS public content source')
        need(row['path'] == UPSTREAM_PATHS[file.name], 'RSS public content upstream path changed: ' + file.name)
        leaf = file.read_bytes()
        need(b'\r' not in leaf and len(leaf) == row['bytes'] and sha(leaf) == row['sha256'],
             'RSS public content bytes changed: ' + file.name)
        need(hashlib.sha1(b'blob ' + str(len(leaf)).encode() + b'\0' + leaf).hexdigest() == row['gitBlob'],
             'RSS public content Git blob changed: ' + file.name)
        text = leaf.decode('utf8')
        need(text.encode('utf8') == leaf and text.endswith('\n'), 'RSS public content text changed: ' + file.name)
        if file.suffix == '.kt':
            need(('package ' + FEED_PACKAGE + '\n') in text[:400], 'RSS public content package changed: ' + file.name)
        sources[file.name] = text
        raws[file.name] = leaf
    for token in DECLARATIONS:
        need(sources[NETWORK].count(token) == 1, 'RSS public content declaration changed: ' + token)
    need(sources[NETWORK_TEST].count('@Test') == NETWORK_TEST_CASES, 'RSS public content test count changed')
    need('Apache License' in sources[NOTICE] and 'Version 2.0' in sources[NOTICE],
         'Readability4J notice is not the Apache License')
    license_file = root / 'LICENSE'
    need(not license_file.is_symlink() and sha(license_file.read_bytes()) == manifest['license']['sha256'],
         'RSS public content license changed')
    return sources, raws, manifest


def validate_outputs(repo, *outputs):
    fixed(repo)
    root = (Path(repo) / ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)),
             'Output overlaps original RSS public content inputs')


def restore(value, proof):
    """Apply an inverse_proof backwards; the proof and every hunk are checked on the way."""
    need(sha(value) == proof['afterSHA256'], 'RSS public content output differs from its receipt')
    lines = value.splitlines(keepends=True)
    for hunk in reversed(proof['hunks']):
        first, last = hunk['afterLines']
        need(''.join(lines[first:last]) == hunk['after'], 'RSS public content inverse span changed')
        lines[first:last] = hunk['before'].splitlines(keepends=True)
    result = ''.join(lines)
    need(sha(result) == proof['beforeSHA256'], 'RSS public content predecessor inverse failed')
    return result


def _replace_once(body, old, new, label):
    need(body.count(old) == 1, 'RSS public content anchor changed: ' + label)
    return body.replace(old, new, 1)


def advance_feed_fetcher(repo, before):
    """Windows FeedFetcher: feeds and article pages use the anonymous public content client."""
    fixed(repo)
    need(sha(before.replace('\r\n', '\n')) == FEED_FETCHER_PREDECESSOR_SHA256,
         'RSS public content FeedFetcher predecessor changed')
    after = _replace_once(before, FETCHER_CLIENT_BEFORE, FETCHER_CLIENT_AFTER, 'public client binding')
    need('DesktopPluginNetwork' not in after, 'RSS public content FeedFetcher still names the plain client')
    proof = inverse_proof(before, after)
    proof.update(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, source=FEED_FETCHER_PATH,
                 predecessorSHA256=FEED_FETCHER_PREDECESSOR_SHA256, publicClient='publicContentHttpClient',
                 jvmTestsExecuted=False, mainCompiled=False, runtimeExecuted=False)
    return after, proof


def _target(root, relative):
    base = Path(root).resolve()
    target = base / relative
    # A leaf symlink must stay unresolved; resolve() would follow it and write through it.
    if not target.is_symlink():
        target = target.resolve()
        need(target.is_relative_to(base), 'RSS public content target escapes the output root: ' + relative)
    return target


def _require_regular_or_absent(target, relative):
    try:
        info = os.lstat(target)
    except FileNotFoundError:
        return
    need(stat.S_ISREG(info.st_mode), 'RSS public content target is not a regular file: ' + relative)


def _write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf8', newline='\n')


def _write_verbatim(output, name, raw):
    relative = FEED_DIRECTORY + name
    target = _target(output, relative)
    _require_regular_or_absent(target, relative)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(raw)
    need(target.read_bytes() == raw, 'RSS public content readback differs: ' + name)
    return target


def emit_main(repo, output, feed_fetcher_proof=None):
    """Write PublicContentNetwork.kt verbatim and the receipt; return the generated Kotlin paths."""
    validate_outputs(repo, output)
    _, raws, _ = fixed(repo)
    target = _write_verbatim(output, NETWORK, raws[NETWORK])
    _write_json(Path(output) / PROOF_NAME, dict(
        upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        network=dict(source=UPSTREAM_PATHS[NETWORK], emittedVerbatim=True, relative=FEED_DIRECTORY + NETWORK,
                     sha256=sha(raws[NETWORK])),
        feedFetcher=feed_fetcher_proof,
        noticeStagedNotShipped=dict(source=UPSTREAM_PATHS[NOTICE], sha256=sha(raws[NOTICE])),
        jvmTestsExecuted=False, mainCompiled=False, runtimeExecuted=False))
    return [target]


def emit_tests(repo, output):
    """Write the original JVM test unchanged (it already uses kotlin.test)."""
    validate_outputs(repo, output)
    _, raws, _ = fixed(repo)
    target = _write_verbatim(output, NETWORK_TEST, raws[NETWORK_TEST])
    _write_json(Path(output) / 'v046-rss-public-content-tests-proof.json', dict(
        upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        tests={NETWORK_TEST: dict(source=UPSTREAM_PATHS[NETWORK_TEST], cases=NETWORK_TEST_CASES,
                                  sha256=sha(raws[NETWORK_TEST]), emittedVerbatim=True)},
        jvmTestsExecuted=False))
    return [target]
