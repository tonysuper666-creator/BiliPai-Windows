"""One retained v046 comment chrome, split by the two existing production owners."""
from pathlib import Path
import hashlib
import json

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-comment-chrome'
MANIFEST_SHA256 = 'cf040529fbb1acd460fdd92045b0943ee6594a02be281a0485822b58eb8cf629'
SORT_PATH = 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/CommentSortFilterBar.kt'
PREDECESSOR_SHA256 = '8a47a92187486ced7686861df44f1ef283f790acd80284656d55751853e144e2'
SPLIT = '/**\n * 评论排序分段控件，放置在详情页顶栏'


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def fixed(repo):
    root = Path(repo)/ARCHIVE
    assert root.is_dir() and not root.is_symlink(), 'Missing original comment chrome'
    assert {p.name for p in root.iterdir()} == {
        'CommentSortFilterBar.kt', 'CommentSortFilterBarPolicyTest.kt', 'manifest.json', 'LICENSE'
    }, 'Comment chrome input leaves changed'
    assert sha((root/'manifest.json').read_bytes()) == MANIFEST_SHA256, 'Comment chrome manifest changed'
    manifest = json.loads((root/'manifest.json').read_bytes())
    assert (manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) == (
        'jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw')
    assert len(manifest['files']) == 2
    sources = {}
    for row in manifest['files']:
        file = root/row['filename']
        assert file.is_file() and not file.is_symlink() and file.name == Path(row['path']).name
        raw = file.read_bytes()
        assert len(raw) == row['bytes'] and sha(raw) == row['sha256'], file.name
        assert hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest() == row['gitBlob'], file.name
        sources[file.name] = raw.decode('utf8').replace('\r\n', '\n')
    assert set(sources) == {'CommentSortFilterBar.kt', 'CommentSortFilterBarPolicyTest.kt'}
    license = root/'LICENSE'
    assert not license.is_symlink() and sha(license.read_bytes()) == manifest['license']['sha256']
    return sources


def advance_sort_source(repo, before, output, consumer):
    assert sha(before.replace('\r\n', '\n')) == PREDECESSOR_SHA256, 'Comment chrome predecessor changed'
    assert consumer in {'header', 'filter'}
    source = fixed(repo)['CommentSortFilterBar.kt']
    assert source.count(SPLIT) == 1
    root = (Path(repo)/ARCHIVE).resolve()
    output = Path(output).resolve()
    assert not (output.is_relative_to(root) or root.is_relative_to(output)), 'Output overlaps retained source'
    output.mkdir(parents=True, exist_ok=True)
    proof = dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
                 originalSource=ARCHIVE+'/CommentSortFilterBar.kt', consumer=consumer,
                 beforeSHA256=sha(before.replace('\r\n', '\n')), selectedSHA256=sha(source),
                 completeOriginalDeclarations=True, splitAtExistingProducerBoundary=True)
    (output/('v046-comment-chrome-'+consumer+'-selection.json')).write_text(
        json.dumps(proof, indent=2)+'\n', encoding='utf8', newline='\n')
    return source


def emit_tests(repo, output):
    source = fixed(repo)['CommentSortFilterBarPolicyTest.kt']
    before = '''        val normalizedPath = path.removePrefix("app/")
        val sourceFile = listOf(
            File(path),
            File(normalizedPath)
        )'''
    after = '''        val normalizedPath = "desktop/upstream-slices/v046-comment-chrome/" + File(path).name
        val sourceFile = listOf(
            File(normalizedPath),
            File(normalizedPath.removePrefix("desktop/"))
        )'''
    assert source.count(before) == 1 and source.count('class CommentSortFilterBarPolicyTest {') == 1
    adapted = source.replace(before, after, 1).replace(
        'class CommentSortFilterBarPolicyTest {', 'class V046CommentSortOriginalPolicyTest {', 1)
    restored = adapted.replace('class V046CommentSortOriginalPolicyTest {',
                               'class CommentSortFilterBarPolicyTest {', 1).replace(after, before, 1)
    assert restored == source, 'Original policy test inverse failed'
    target = Path(output)/'test/com/android/purebilibili/feature/video/ui/components/V046CommentSortOriginalPolicyTest.kt'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(adapted, encoding='utf8', newline='\n')
    return target
