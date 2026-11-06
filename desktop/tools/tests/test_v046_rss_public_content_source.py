"""v0.4.6 RSS public content network on the existing plugin producer, plus the Windows image routing.

Source checks only: nothing here compiles Kotlin or runs a JVM test.
"""
from pathlib import Path
from unittest import mock
import hashlib
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
# Direct sources compile from the live merged root; inside a legacy gate view REPO/app is the legacy pin.
LIVE = Path(os.environ.get('BILIPAI_LIVE_ROOT') or REPO)
TOOLS = REPO / 'desktop/tools'
sys.path.insert(0, str(TOOLS))
import check_slice_symbol_closure as gate
import v046_rss_public_content as source

spec = importlib.util.spec_from_file_location('sync_upstream_rss_public_content', TOOLS / 'sync-upstream.py')
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)

producer_spec = importlib.util.spec_from_file_location('plugins_rss_public_content', TOOLS / 'extract-upstream-plugins.py')
producer = importlib.util.module_from_spec(producer_spec)
producer_spec.loader.exec_module(producer)

FEED = 'com/android/purebilibili/core/plugin/feed/'
EXTRACTOR = 'app/src/main/java/com/android/purebilibili/core/plugin/feed/FeedArticleExtractor.kt'
EXTRACTOR_TEST = 'app/src/test/java/com/android/purebilibili/core/plugin/feed/FeedArticleExtractorTest.kt'
# whenApproved 3 (TON-1232 r4, for Root): the direct owner keeps both v0.4.6 files whole, (sha256, git blob) at d6bf8b16.
EXTRACTOR_PAIR = {
    EXTRACTOR: ('81fa6dbf42c61486a3dedacc1af86485e7ac4cb1c95e7eb66e7bb6af082d9d1b', '790f03df61da35eb7d4a2d741ec94133b0c0bc88'),
    EXTRACTOR_TEST: ('6e07e42ea54ea4c23b3aeb101563db2291c5dc8f6c5f085fbbe02035531caf58', 'afa80b7fa07e5c33012cb8cf44f6ed169f577aa5'),
}
EXTRACTOR_CASES = [
    'standalone reader receives metadata and the resolved source URL',
    'local readability retains article while dropping comments and recommendations',
    'short Chinese article retains headings and lazy images with absolute URLs',
    'malformed nesting and angle brackets in attributes do not truncate the article',
    'hidden text and executable content are not retained',
    'link directories and script-only pages leave the feed summary intact',
    'unreasonably large pages are rejected before extraction',
]
# whenApproved 4 (TON-1232 r4, for Root): the compiled Windows article read point. FeedFetcher is a migrated app core
# path, so Windows compiles the live v0.4.6 file through its desktop patch (build/patched-upstream); the generated
# plugins copy of the same identity is an excluded legacy copy.
FETCHER = 'app/src/main/java/com/android/purebilibili/core/plugin/feed/FeedFetcher.kt'
FETCHER_PATCH = 'desktop/upstream-core/patches/' + FETCHER + '.patch'
FETCHER_LIVE = ('31c4419863e8785a49e71defd0fe0b84ef03af57dbb78c57e443c2c1c0024f9c', '6becaa4ed3a266556f5c64eeeb75e6777c5579007bce1024690030c111eeb9ab')
ARTICLE_READ = '                            extractReadableArticle(page, response.request.url.toString())\n'

POLICY = REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopBilibiliRequestHeaderPolicy.kt'
REPOSITORY = REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopRepository.kt'
LOADER = REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopApplicationImageLoader.kt'
ROUTING = REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopPublicContentImageRouting.kt'
ROUTING_TEST = REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopPublicContentImageRoutingTest.kt'
NETWORK_NAMES = ('publicContentHttpClient', 'createPublicContentHttpClient', 'sanitizePublicContentUrl',
                 'sanitizePublicContentRequest', 'publicContentImageHttpClient', 'withPublicImageReferer',
                 'publicImageReferer', 'publicSiteRoot', 'downloadPublicContentToFile')
GATE_HOSTS = ('bilibili.com', 'bilibili.tv', 'biliapi.net', 'biliapi.com', 'hdslb.com', 'bilivideo.com', 'bilivideo.cn')


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def tree(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in sorted(Path(root).rglob('*')) if p.is_file()}


def lf(raw):
    """The plugin producer writes with the platform newline, so its generated files may hold CRLF on Windows."""
    return raw.decode('utf-8').replace('\r\n', '\n')


def body_of(raw):
    """Generated plugin files start with two header lines."""
    return lf(raw).split('\n', 2)[2]


def task_block(build, name):
    start = build.index('val ' + name + ' by tasks.registering(Exec::class) {')
    return build[start:build.index('\n}\n', start)]


def prepare_block(build):
    start = build.index('val prepareUpstreamSources by tasks.registering(Sync::class) {')
    return build[start:build.index('\nval extractUpstreamApi ', start)]


def declarations(text, path):
    info = gate.parse_kotlin(text, path, 'generated', 'main')
    return info.package, [(item.visibility, item.kind, item.name) for item in info.decls]


def apply_exact_patch(text, patch):
    '''Applies a unified diff whose every context and removed line matches exactly; no fuzz, no offset.'''
    lines, out, at = text.splitlines(keepends=True), [], 0
    hunks = re.split(r'(?m)^@@ -(\d+)(?:,\d+)? \+\d+(?:,\d+)? @@.*\n', patch)[1:]
    for start, body in zip(hunks[::2], hunks[1::2]):
        start = int(start) - 1
        if start < at:
            raise ValueError('hunks out of order')
        out += lines[at:start]; at = start
        for line in body.splitlines(keepends=True):
            kind, rest = line[0], line[1:]
            if kind not in ' -+':
                raise ValueError('unexpected patch line %r' % line)
            if kind in ' -':
                if at >= len(lines) or lines[at] != rest:
                    raise ValueError('patch line %d does not match: %r' % (at + 1, rest))
                at += 1
            if kind in ' +':
                out.append(rest)
    return ''.join(out + lines[at:])


class V046RssPublicContentSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.archive = {p.name: p.read_bytes() for p in (REPO / source.ARCHIVE).iterdir() if p.is_file()}
        cls.manifest = json.loads(cls.archive['manifest.json'])
        cls.temporary = tempfile.TemporaryDirectory(prefix='bp-rpc-')
        cls.root = Path(cls.temporary.name)
        cls.main_root, cls.test_root = cls.root / 'main', cls.root / 'tests'
        cls.files = producer.generate(REPO, cls.main_root)
        cls.feed_tests = producer.generate_feed_tests(REPO, cls.test_root)
        cls.public_tests = producer.generate_public_content_tests(REPO, cls.test_root)
        cls.main = tree(cls.main_root)
        cls.tests = tree(cls.test_root)
        cls.build = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf-8')
        cls.catalog = json.loads((TOOLS / 'compatibility-source-inputs.json').read_text(encoding='utf-8'))
        cls.proof = json.loads(cls.main[source.PROOF_NAME])
        with mock.patch.object(source, 'advance_feed_fetcher', lambda repo, before: (before, None)):
            cls.patched_root = cls.root / 'patched'
            producer.generate(REPO, cls.patched_root)
        cls.patched = tree(cls.patched_root)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    # --- archive -------------------------------------------------------------------------------------

    def test_archive_leaf_manifest_license_and_extra_leaf_reject_tampering(self):
        leaves = sorted(self.archive)
        self.assertEqual(sorted(source.EXPECTED | {'LICENSE', 'manifest.json'}), leaves)
        for leaf in leaves:
            with self.subTest(leaf=leaf), tempfile.TemporaryDirectory(prefix='bp-rpc-') as tmp:
                repo = Path(tmp)
                shutil.copytree(REPO / source.ARCHIVE, repo / source.ARCHIVE)
                target = repo / source.ARCHIVE / leaf
                target.write_bytes(target.read_bytes() + b'\n')
                with self.assertRaises(ValueError):
                    source.fixed(repo)
        for kind in ('extra', 'missing'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory(prefix='bp-rpc-') as tmp:
                repo = Path(tmp)
                shutil.copytree(REPO / source.ARCHIVE, repo / source.ARCHIVE)
                if kind == 'extra':
                    (repo / source.ARCHIVE / 'Extra.kt').write_bytes(b'package extra\n')
                else:
                    (repo / source.ARCHIVE / source.NOTICE).unlink()
                with self.assertRaises(ValueError):
                    source.fixed(repo)
        self.assertEqual(self.archive, {p.name: p.read_bytes() for p in (REPO / source.ARCHIVE).iterdir() if p.is_file()})

    def test_archive_leaves_equal_the_upstream_blobs_and_stay_lf(self):
        self.assertEqual(source.MANIFEST_SHA256, sha(self.archive['manifest.json']))
        self.assertEqual(source.COMMIT, self.manifest['commit'])
        self.assertEqual(source.UPSTREAM_PATHS, {row['filename']: row['path'] for row in self.manifest['files']})
        for name, raw in self.archive.items():
            with self.subTest(leaf=name):
                self.assertNotIn(b'\r', raw)
        have_git = subprocess.run(['git', '-C', str(REPO), 'cat-file', '-e', source.COMMIT + '^{commit}'],
                                  capture_output=True).returncode == 0
        for row in self.manifest['files']:
            with self.subTest(leaf=row['filename']):
                raw = self.archive[row['filename']]
                self.assertEqual((len(raw), sha(raw), hashlib.sha1(b'blob %d\0' % len(raw) + raw).hexdigest()),
                                 (row['bytes'], row['sha256'], row['gitBlob']))
                if have_git:
                    blob = subprocess.run(['git', '-c', 'core.autocrlf=false', '-C', str(REPO), 'cat-file', 'blob',
                                           source.COMMIT + ':' + row['path']], capture_output=True, check=True).stdout
                    self.assertEqual(blob, raw)

    def test_dependency_proposal_is_recorded_and_nothing_is_added_or_shipped(self):
        pending = self.manifest['pendingDependencies']
        self.assertEqual('not-added', pending['status'])
        self.assertIn('D7', pending['decision'])
        self.assertEqual(['net.dankito.readability4j:readability4j:1.0.8', 'org.jsoup:jsoup:1.23.2', 'org.slf4j:slf4j-api'],
                         [row['coordinate'] for row in pending['proposed']])
        self.assertEqual('org.jsoup:jsoup:1.21.2 in desktop/build.gradle.kts', pending['proposed'][1]['replaces'])
        self.assertFalse(self.manifest['compiled'])
        self.assertFalse(self.manifest['jvmTestsExecuted'])
        # Architect decision (TON-1232 r3, for Root): the build keeps readability4j 1.0.8 as its only new artifact.
        # Its jsoup 1.11.2 / slf4j-api 1.7.25 / kotlin-stdlib 1.3.72 edges resolve up to the pins below, so the
        # slice's proposed jsoup 1.23.2 replacement is not taken and this archive record stays as proposed.
        self.assertEqual(['    implementation("net.dankito.readability4j:readability4j:1.0.8")'],
                         [line for line in self.build.splitlines() if 'readability4j' in line])
        self.assertEqual(1, self.build.count('implementation("org.jsoup:jsoup:1.21.2")'))
        self.assertNotIn('jsoup:1.23', self.build)
        self.assertEqual(1, self.build.count('implementation("org.slf4j:slf4j-api:2.0.17")'))
        licenses = REPO / 'desktop/src/main/resources/licenses'
        self.assertEqual(['readability4j-1.0.8', 'readability4j-1.0.8/LICENSE.txt', 'readability4j-1.0.8/provenance.json'],
                         sorted(path.relative_to(licenses).as_posix() for path in licenses.rglob('*')
                                if 'readability4j' in path.relative_to(licenses).as_posix().lower()))
        shipped = licenses / 'readability4j-1.0.8'
        provenance = json.loads((shipped / 'provenance.json').read_text(encoding='utf-8'))
        self.assertEqual('net.dankito.readability4j:readability4j:1.0.8', provenance['coordinate'])
        self.assertEqual('https://repo.maven.apache.org/maven2/net/dankito/readability4j/readability4j/1.0.8/'
                         'readability4j-1.0.8.jar', provenance['artifactUrl'])
        self.assertEqual((80492, '515bca66af21b7aca2c451bd75d7fa8c05df25ad74e0243047fa11e0ce2d9223'),
                         (provenance['artifactBytes'], provenance['artifactSha256']))
        self.assertEqual(sha(self.archive[source.NOTICE]), provenance['licenseSha256'])
        self.assertEqual(provenance['licenseSha256'], sha((shipped / 'LICENSE.txt').read_bytes()))
        carried = licenses / 'upstream-carried-assets'
        self.assertNotIn('readability', (carried / 'provenance.json').read_text(encoding='utf-8').lower())
        self.assertNotIn('readability', (carried / 'NOTICE.txt').read_text(encoding='utf-8').lower())
        notice = self.archive[source.NOTICE].decode('utf-8')
        self.assertIn('Apache License', notice)
        self.assertIn('Version 2.0, January 2004', notice)
        self.assertIn('Copyright 2017 dankito', notice)
        self.assertEqual('app/src/main/assets/licenses/readability4j.LICENSE', source.UPSTREAM_PATHS[source.NOTICE])
        self.assertEqual([], [path for path in self.main if path.endswith('.LICENSE')])
        self.assertEqual(sha(self.archive[source.NOTICE]), self.proof['noticeStagedNotShipped']['sha256'])

    def test_the_direct_article_extractor_owner_retains_both_v046_files_byte_for_byte(self):
        # whenApproved 3. The direct owner is the live merged root: a migrated app core path, compiled straight from
        # app/src/main/java with no desktop patch or rewrite (prepareUpstreamSources leaves migrated sources out). Upstream
        # drift of either file fails here and is answered by a new pinned decision, never by deleting this.
        raws = {path: (LIVE / path).read_bytes() for path in EXTRACTOR_PAIR}
        self.assertEqual(EXTRACTOR_PAIR, {path: (sha(raw), hashlib.sha1(b'blob %d\0' % len(raw) + raw).hexdigest())
                                          for path, raw in raws.items()})
        if shutil.which('git'):
            for path, raw in raws.items():
                self.assertEqual(raw, subprocess.run(['git', '-c', 'core.autocrlf=false', '-C', str(REPO), 'cat-file',
                                                      'blob', source.COMMIT + ':' + path], capture_output=True, check=True).stdout)
        extractor, test = (raws[path].decode('utf-8') for path in (EXTRACTOR, EXTRACTOR_TEST))
        self.assertFalse((REPO / ('desktop/upstream-core/patches/' + EXTRACTOR + '.patch')).exists())
        self.assertIsNone(re.search(r'ImageRequest\.Builder\(|SingletonImageLoader\.get\(', extractor))
        self.assertEqual(1, extractor.count('fun extractArticleBody(pageHtml: String, baseUrl: String = ""): String? =\n'))
        self.assertEqual(1, extractor.count('import net.dankito.readability4j.Readability4J\n'))
        self.assertEqual(EXTRACTOR_CASES, re.findall(r'@Test\s+fun `([^`]+)`\(\)', test))
        self.assertEqual(7, test.count('@Test'))
        self.assertIsNone(re.search(r'(?m)^import (org\.junit\.(?!jupiter)|org\.robolectric|io\.mockk|androidx\.test)', test))
        # The upstream test runs from appTestRoot: core is walked whole and nothing in the build names this pair
        # except the dependency comment, so no not-run, desktop-owned, later-step or corrected entry excludes it.
        self.assertEqual(1, self.build.count('appTestRoot.resolve("com/android/purebilibili/core").walkTopDown()'))
        self.assertEqual(['    // Upstream core/plugin/feed/FeedArticleExtractor (TON-1028): article readability, same version as upstream app.'],
                         [line for line in self.build.splitlines() if 'FeedArticleExtractor' in line])
        core_test = EXTRACTOR_TEST.split('com/android/purebilibili/', 1)[1]
        self.assertEqual([], [key for key in set(re.findall(r'"([\w/.\-]+)" to ', self.build))
                              if core_test.startswith(key) or key == core_test.replace('Test.kt', '.kt')])
        self.assertNotIn('FeedArticleExtractor', (REPO / 'desktop/upstream-core/m4d/tests-not-run.tsv').read_text(encoding='utf-8'))
        # producer.inventory(REPO) hashes the legacy pin inside the view; the committed source inventory names the live bytes.
        inventory = json.loads((REPO / 'desktop/upstream-sources.json').read_text(encoding='utf-8'))
        self.assertEqual([('direct', EXTRACTOR_PAIR[EXTRACTOR][0])],
                         [(row['mode'], row['sha256']) for row in inventory['sources'] if row['path'] == EXTRACTOR])
        # Exactly one compiled definer of the article read: the direct owner.
        definers = sorted(path.relative_to(LIVE).as_posix() for path in (LIVE / 'app/src/main/java').rglob('*.kt')
                          if 'fun extractArticleBody(' in path.read_text(encoding='utf-8'))
        definers += sorted(path.relative_to(REPO).as_posix() for path in (REPO / 'desktop/src/main').rglob('*.kt')
                           if 'fun extractArticleBody(' in path.read_text(encoding='utf-8'))
        definers += sorted(path for path, raw in self.main.items() if path.endswith('.kt') and b'fun extractArticleBody(' in raw)
        self.assertEqual([EXTRACTOR], definers)
        self.assertEqual(0, self.build.count('canonicalOriginalIdentity("' + EXTRACTOR + '")'))
        self.assertEqual([], [path for path in self.main if 'ArticleExtractor' in path])
        self.assertEqual([], [path for path in self.tests if 'ArticleExtractor' in path])
        self.assertIn(EXTRACTOR, producer.DIRECT)
        self.assertEqual('direct', {row['path']: row['mode'] for row in producer.inventory(REPO)}[EXTRACTOR])
        self.assertIn('FeedArticleExtractor.kt', self.manifest['notCarried'])
        for path in ('FeedArticleExtractor.kt', 'FeedArticleExtractorTest.kt'):
            self.assertNotIn(path, self.archive)

    # --- PublicContentNetwork.kt ---------------------------------------------------------------------

    def test_public_content_network_is_emitted_verbatim_and_declared_once(self):
        relative = FEED + source.NETWORK
        self.assertEqual(self.archive[source.NETWORK], self.main[relative])
        self.assertEqual(relative, self.proof['network']['relative'])
        self.assertTrue(self.proof['network']['emittedVerbatim'])
        self.assertEqual(sha(self.archive[source.NETWORK]), self.proof['network']['sha256'])
        package, decls = declarations(self.archive[source.NETWORK].decode('utf-8'), source.NETWORK)
        self.assertEqual(source.FEED_PACKAGE, package)
        self.assertEqual(sorted(NETWORK_NAMES), sorted(name for _vis, _kind, name in decls if name in NETWORK_NAMES))
        users = {path for path, raw in self.main.items() if path.endswith('.kt') and b'publicContentHttpClient' in raw}
        self.assertEqual({relative, FEED + 'FeedFetcher.kt'}, users)
        for root in ('app/src/main/java', 'design-system/src/main/java', 'core-data/src/main/java', 'desktop/src/main'):
            for path in (REPO / root).rglob('*.kt'):
                self.assertNotIn('internal val publicContentHttpClient', path.read_text(encoding='utf-8'), str(path))
        for name in NETWORK_NAMES:
            hits = []
            for generated_path, raw in self.main.items():
                if not generated_path.endswith('.kt') or name.encode('utf-8') not in raw:
                    continue
                _package, found = declarations(lf(raw), generated_path)
                if any(item_name == name for _vis, _kind, item_name in found):
                    hits.append(generated_path)
            self.assertEqual([relative], hits, name)

    def test_the_bilibili_referer_host_gate_is_intact_and_public_content_stays_inside_it(self):
        policy = POLICY.read_text(encoding='utf-8')
        listed = re.search(r'listOf\(([^)]*)\)\s*\.any', policy)
        self.assertEqual(list(GATE_HOSTS), re.findall(r'"([^"]+)"', listed.group(1)))
        self.assertEqual(1, policy.count('internal fun usesDefaultBilibiliHeaders(host: String): Boolean {'))
        lf_policy = POLICY.read_bytes().replace(b'\r\n', b'\n')
        self.assertEqual('4dff49286230f042877b7f128db0ce4545b8fdbd',
                         hashlib.sha1(b'blob %d\0' % len(lf_policy) + lf_policy).hexdigest())
        repository = REPOSITORY.read_text(encoding='utf-8')
        self.assertEqual(1, repository.count('if (!usesDefaultBilibiliHeaders(url.host)) return null'))
        self.assertEqual(1, repository.count('if (usesDefaultBilibiliHeaders(original.url.host) && original.header("Origin").isNullOrBlank()'))
        network = self.archive[source.NETWORK].decode('utf-8')
        # The one Bilibili CDN special case of the anonymous chain is a gate host.
        self.assertEqual(['hdslb.com'], re.findall(r'host == "([^"]+)"', network))
        self.assertIn('hdslb.com', GATE_HOSTS)
        self.assertEqual(['https://www.bilibili.com/'], re.findall(r'header\("Referer", "(https://www\.bilibili\.com/)"\)', network))

    # --- FeedFetcher.kt ------------------------------------------------------------------------------

    def test_feed_fetcher_uses_the_public_client_with_an_exact_inverse_and_nothing_else_changes(self):
        relative = FEED + 'FeedFetcher.kt'
        fetcher = body_of(self.main[relative])
        before = body_of(self.patched[relative])
        self.assertEqual(source.FEED_FETCHER_PREDECESSOR_SHA256, source.sha(before))
        self.assertEqual(1, fetcher.count('    publicContentHttpClient.newBuilder()\n'))
        self.assertNotIn('DesktopPluginNetwork', fetcher)
        self.assertNotIn('NetworkModule', fetcher)
        self.assertIn('.connectTimeout(6, TimeUnit.SECONDS)', fetcher)
        self.assertIn('.callTimeout(8, TimeUnit.SECONDS)', fetcher)
        self.assertIn('header("If-None-Match", it)', fetcher)
        self.assertIn('header("If-Modified-Since", it)', fetcher)
        # Article extraction keeps its single-argument call.
        self.assertEqual(1, fetcher.count('extractArticleBody(page) ?: error('))
        proof = self.proof['feedFetcher']
        self.assertEqual(before, source.restore(fetcher, proof))
        self.assertEqual(source.advance_feed_fetcher(REPO, before)[1], proof)
        self.assertEqual(1, len(proof['hunks']))
        self.assertEqual('publicContentHttpClient', proof['publicClient'])
        self.assertFalse(proof['mainCompiled'])

    def test_compiled_windows_fetcher_passes_the_response_url_as_article_base(self):
        # whenApproved 4. Windows reads articles through fetchArticleHtml -> fetchReadableArticle of the live v0.4.6
        # FeedFetcher after its desktop patch; that one read passes the final response URL as the base URL.
        raw = (LIVE / FETCHER).read_bytes()
        self.assertEqual(FETCHER_LIVE[0], sha(raw))
        if shutil.which('git'):
            self.assertEqual(raw, subprocess.run(['git', '-c', 'core.autocrlf=false', '-C', str(REPO), 'cat-file',
                                                  'blob', source.COMMIT + ':' + FETCHER], capture_output=True, check=True).stdout)
        patch = (REPO / FETCHER_PATCH).read_text(encoding='utf-8')
        self.assertEqual(['--- a/' + FETCHER, '+++ b/' + FETCHER], patch.splitlines()[:2])
        compiled = apply_exact_patch(raw.decode('utf-8'), patch)
        self.assertEqual(FETCHER_LIVE[1], sha(compiled.encode('utf-8')))
        # applyDesktopUpstreamPatches then runs the build-time rewrite rules; none of them matches this file.
        self.assertIsNone(re.search(r'ImageRequest\.Builder\(|SingletonImageLoader\.get\(', compiled))
        self.assertEqual((1, 0), (compiled.count(ARTICLE_READ), compiled.count('extractArticleBody(')))
        self.assertEqual(1, compiled.count('extractReadableArticle('))
        self.assertEqual(1, compiled.count('suspend fun fetchArticleHtml(url: String): Result<String> = fetchReadableArticle(url).map { it.html }\n'))
        start = compiled.index('suspend fun fetchReadableArticle(url: String)')
        block = compiled[start:compiled.index('\nsuspend fun ', start)]
        self.assertEqual(1, block.count('executeFeedCall(call) { response ->'))
        self.assertLess(block.index('executeFeedCall(call) { response ->'), block.index(ARTICLE_READ))
        self.assertTrue(block[block.index(ARTICLE_READ) + len(ARTICLE_READ):].lstrip().startswith('?: error("暂时无法提取原文正文")'))
        # Build wiring that makes the patched live file the compiled one: core is migrated whole, nothing keeps
        # FeedFetcher back, the root file is replaced by its patched copy, and generated copies of migrated
        # identities (the plugins FeedFetcher extracted from the legacy pin) are excluded.
        for line in ('val migratedAppCorePaths: Set<String> = (appJavaRoot.resolve("com/android/purebilibili/core").walkTopDown() +',
                     'val migratedAppPaths: Set<String> = migratedAppCorePaths + migratedAppM4dPaths + migratedAppSingleFiles + migratedAppM4cPaths',
                     'val desktopUpstreamPatchDirectory = file("upstream-core/patches")',
                     'val desktopUpstreamAdaptedRootFiles: Set<String> = (desktopUpstreamPatchedFiles + desktopUpstreamRewrittenFiles).toSortedSet()',
                     '    kotlin.exclude { element -> element.file in adaptedRootFiles }',
                     '        path.startsWith(buildDirectoryPath) && !path.startsWith(patchedDirectoryPath) && upstreamIdentity(element.file) in migratedUpstreamRelativePaths',
                     '    kotlin.srcDir(applyDesktopUpstreamPatches)',
                     '    kotlin.exclude { element -> !element.isDirectory && element.file.toPath().startsWith(appJavaRootPath) && upstreamIdentity(element.file) !in migratedAppPaths }',
                     '    kotlin.srcDir(appJavaRoot)'):
            self.assertEqual(1, self.build.splitlines().count(line), line)
        core = FETCHER.split('com/android/purebilibili/', 1)[1]
        self.assertEqual([], [key for key in set(re.findall(r'"([\w/.\-]+)" to ', self.build)) if core.startswith(key)])
        self.assertEqual([], [line for name in ('m4b', 'data', 'm4c') for line in
                              ((REPO / 'desktop/upstream-core' / name / 'later-steps.tsv').read_text(encoding='utf-8').splitlines()
                               if (REPO / 'desktop/upstream-core' / name / 'later-steps.tsv').is_file() else [])
                              if line.split('\t', 1)[0] == core])
        self.assertIn(FEED + 'FeedFetcher.kt', self.main)
        self.assertEqual(FEED + 'FeedFetcher.kt', FETCHER.split('app/src/main/java/', 1)[1])
        # No desktop code shadows the read path.
        self.assertEqual([], sorted(path.relative_to(REPO).as_posix() for path in (REPO / 'desktop/src/main').rglob('*.kt')
                                    if re.search(r'fun (fetchArticleHtml|fetchReadableArticle|extractReadableArticle)\(',
                                                 path.read_text(encoding='utf-8'))))

    def test_patching_the_fetcher_layer_out_changes_only_the_fetcher_and_the_receipt(self):
        self.assertEqual(set(self.main), set(self.patched))
        different = sorted(path for path in self.main if self.main[path] != self.patched[path])
        self.assertEqual(sorted([FEED + 'FeedFetcher.kt', source.PROOF_NAME]), different)
        self.assertIsNone(json.loads(self.patched[source.PROOF_NAME])['feedFetcher'])

    def test_fetcher_layer_fails_closed_on_a_drifted_or_already_advanced_predecessor(self):
        before = body_of(self.patched[FEED + 'FeedFetcher.kt'])
        after, _proof = source.advance_feed_fetcher(REPO, before)
        with self.assertRaisesRegex(ValueError, 'predecessor changed'):
            source.advance_feed_fetcher(REPO, after)
        with self.assertRaisesRegex(ValueError, 'predecessor changed'):
            source.advance_feed_fetcher(REPO, before.replace('2 * 1024 * 1024', '3 * 1024 * 1024'))
        with self.assertRaisesRegex(ValueError, 'predecessor changed'):
            source.advance_feed_fetcher(REPO, '')
        with self.assertRaisesRegex(ValueError, 'anchor changed'):
            source._replace_once('nothing here', source.FETCHER_CLIENT_BEFORE, source.FETCHER_CLIENT_AFTER, 'x')
        with self.assertRaisesRegex(ValueError, 'anchor changed'):
            source._replace_once(before + before, source.FETCHER_CLIENT_BEFORE, source.FETCHER_CLIENT_AFTER, 'x')

    # --- tests, determinism, overlap -----------------------------------------------------------------

    def test_original_jvm_test_is_emitted_verbatim_in_the_test_root_only(self):
        self.assertEqual(self.archive[source.NETWORK_TEST], self.tests[FEED + source.NETWORK_TEST])
        self.assertEqual(8, source.NETWORK_TEST_CASES)
        self.assertEqual(8, self.archive[source.NETWORK_TEST].decode('utf-8').count('@Test'))
        self.assertEqual([], [p for p in self.main if p.endswith('Test.kt')])
        self.assertEqual({FEED + 'FeedDocumentParserTest.kt', FEED + source.NETWORK_TEST},
                         {p for p in self.tests if p.endswith('.kt')})
        self.assertEqual(1, len(self.feed_tests))
        self.assertEqual(1, len(self.public_tests))
        proof = json.loads(self.tests['v046-rss-public-content-tests-proof.json'])
        self.assertEqual({source.NETWORK_TEST: 8}, {name: row['cases'] for name, row in proof['tests'].items()})
        self.assertFalse(proof['jvmTestsExecuted'])
        kotlin_tests = [raw.decode('utf-8') for path, raw in self.tests.items() if path.endswith('.kt')]
        self.assertEqual(1, sum(text.count('class PublicContentNetworkTest {') for text in kotlin_tests))
        # The older original feed suite is emitted unchanged next to it.
        self.assertEqual(19, self.tests[FEED + 'FeedDocumentParserTest.kt'].decode('utf-8').count('@Test'))

    def test_generation_is_deterministic_and_incremental_reruns_match(self):
        again = self.root / 'again'
        producer.generate(REPO, again)
        self.assertEqual(self.main, tree(again))
        producer.generate(REPO, again)
        self.assertEqual(self.main, tree(again))
        tests_again = self.root / 'tests-again'
        producer.generate_feed_tests(REPO, tests_again)
        producer.generate_public_content_tests(REPO, tests_again)
        producer.generate_public_content_tests(REPO, tests_again)
        self.assertEqual(self.tests, tree(tests_again))

    def test_output_overlapping_the_archive_is_rejected_and_the_archive_is_unchanged(self):
        before = {p.name: p.read_bytes() for p in (REPO / source.ARCHIVE).iterdir() if p.is_file()}
        nested = REPO / source.ARCHIVE / 'child'
        try:
            for call in (lambda: producer.generate(REPO, nested),
                         lambda: producer.generate_public_content_tests(REPO, nested),
                         lambda: source.validate_outputs(REPO, REPO)):
                with self.assertRaisesRegex(ValueError, 'overlaps'):
                    call()
            self.assertFalse(nested.exists())
            self.assertEqual(before, {p.name: p.read_bytes() for p in (REPO / source.ARCHIVE).iterdir() if p.is_file()})
        finally:
            if nested.exists():
                shutil.rmtree(nested)

    def test_special_targets_fail_closed_and_stale_regular_files_are_replaced(self):
        relative = FEED + source.NETWORK
        with tempfile.TemporaryDirectory(prefix='bp-rpc-') as tmp:
            root = Path(tmp)
            target = root / relative
            target.parent.mkdir(parents=True)
            target.write_bytes(b'STALE')
            source.emit_main(REPO, root)
            self.assertEqual(self.archive[source.NETWORK], target.read_bytes())
        with tempfile.TemporaryDirectory(prefix='bp-rpc-') as tmp:
            root = Path(tmp)
            (root / relative).mkdir(parents=True)
            with self.assertRaisesRegex(ValueError, 'not a regular file'):
                source.emit_main(REPO, root)
        with tempfile.TemporaryDirectory(prefix='bp-rpc-') as tmp:
            root = Path(tmp)
            (root / FEED / source.NETWORK_TEST).mkdir(parents=True)
            with self.assertRaisesRegex(ValueError, 'not a regular file'):
                source.emit_tests(REPO, root)

    # --- wiring --------------------------------------------------------------------------------------

    def test_gradle_inputs_and_inventory_wire_the_archive(self):
        block = task_block(self.build, 'extractUpstreamPlugins')
        for literal in ('inputs.file("tools/v046_rss_public_content.py")',
                        'inputs.dir("upstream-slices/v046-rss-public-content")'):
            self.assertEqual(1, self.build.count(literal), literal)
            self.assertEqual(1, block.count(literal), literal)
        self.assertEqual([], [row for row in self.build.splitlines() if 'srcDir' in row and 'rss-public-content' in row])
        self.assertEqual(1, self.build.count('kotlin.srcDir(layout.buildDirectory.dir("generated/plugins"))'))
        self.assertIn('kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/v041-feed-original-tests")) }',
                      self.build)
        self.assertIn('"--test-output", layout.buildDirectory.dir("generated/v041-feed-original-tests")', block)
        self.assertNotIn('rss-public-content', prepare_block(self.build))
        self.assertIn(source.ARCHIVE, self.catalog['buildInputRoots'])
        self.assertEqual(1, self.catalog['buildInputRoots'].count(source.ARCHIVE))
        rows = [row for row in self.catalog['inputs'] if row['localPath'].startswith(source.ARCHIVE + '/')]
        self.assertEqual([source.NETWORK, source.NETWORK_TEST], [Path(row['localPath']).name for row in rows])
        for row in rows:
            self.assertFalse(row['referenceOnly'])
            self.assertEqual(['v046-rss-public-content'], row['features'])
            self.assertEqual('raw', row['hashNormalization'])
            self.assertEqual(source.COMMIT, row['upstreamCommit'])
            self.assertTrue(row['usedScope'].isascii() and row['usedScope'])
        archives = [row for row in self.catalog['archives'] if row['directory'] == source.ARCHIVE]
        self.assertEqual(1, len(archives))
        self.assertEqual({source.ARCHIVE + '/' + name for name in source.EXPECTED | {'LICENSE', 'manifest.json'}},
                         {leaf['path'] for leaf in archives[0]['files']})
        self.assertEqual(rows, [row for row in sync.adapted_compatibility_inputs(REPO)
                                if row['localPath'].startswith(source.ARCHIVE + '/')])
        catalog_bytes = (TOOLS / 'compatibility-source-inputs.json').read_bytes()
        self.assertEqual(sha(catalog_bytes.replace(b'\r\n', b'\n')), sync.COMPATIBILITY_CATALOG_SHA256)

    def test_producer_hooks_run_in_order_and_import_the_generator_lazily(self):
        text = (TOOLS / 'extract-upstream-plugins.py').read_text(encoding='utf-8')
        start = text.index('def generate(')
        validate = text.index('validate_public_content_outputs(repo, output)', start)
        direct_loop = text.index('for path in DIRECT:', start)
        advance = text.index('body, public_content_fetcher_proof = advance_feed_fetcher(repo, body)', start)
        written = text.index('generated.append(write(output, path, source, body))', advance)
        emit = text.index('emit_public_content_main(repo, output, public_content_fetcher_proof)', written)
        self.assertLess(start, validate)
        self.assertLess(validate, direct_loop)
        self.assertLess(advance, written)
        self.assertLess(written, emit)
        self.assertEqual(1, text.count('advance_feed_fetcher(repo, body)'))
        self.assertEqual(1, text.count('emit_public_content_main(repo, output, public_content_fetcher_proof)'))
        self.assertEqual(1, text.count('def generate_public_content_tests('))
        self.assertEqual(1, text.count('generate_public_content_tests(args.repo.resolve(), args.test_output.resolve())'))
        self.assertFalse([line for line in text.splitlines()
                          if line.startswith(('import v046_rss_public_content', 'from v046_rss_public_content'))])
        # The receipt is not part of the producer's own generated list, like the other slice emitters.
        self.assertNotIn(self.main_root / FEED / source.NETWORK, self.files)

    # --- Windows-owned Kotlin ------------------------------------------------------------------------

    def test_application_image_loader_routes_non_bilibili_hosts_through_the_public_image_client(self):
        loader = LOADER.read_text(encoding='utf-8')
        routing = ROUTING.read_text(encoding='utf-8')
        self.assertEqual(1, loader.count('networkCalls = routedImageCalls(bilibiliCalls, desktopPublicImageCalls(), owned)'))
        self.assertEqual(1, loader.count('val bilibiliCalls = Call.Factory { request ->'))
        self.assertIn('repository.ownedHomeCallFactory(capturedEpoch, owned, guest = true).newCall(request)', loader)
        self.assertEqual(1, routing.count('if (usesDefaultBilibiliHeaders(request.url.host)) {'))
        self.assertEqual(1, routing.count('if (!owned()) throw IOException("Public image request owner retired")'))
        self.assertEqual(1, routing.count('publicCalls.newCall(withDesktopBrowserAgent(request))'))
        self.assertEqual(1, routing.count('else request.newBuilder().header("User-Agent", DesktopRepository.USER_AGENT).build()'))
        self.assertEqual(1, routing.count('internal fun routedImageCalls('))
        self.assertEqual(1, routing.count('internal fun desktopPublicImageCalls(): OkHttpClient = publicContentImageHttpClient.newBuilder()'))
        self.assertEqual(1, routing.count('.proxySelector(DesktopNetworkProxyPlatform.buildAppProxySelector())'))
        for budget in ('.connectTimeout(15, TimeUnit.SECONDS)', '.readTimeout(25, TimeUnit.SECONDS)',
                       '.callTimeout(0, TimeUnit.SECONDS)'):
            self.assertEqual(1, routing.count(budget), budget)
        # The shared repository client has the same connect and read budget and no call limit.
        shared = REPOSITORY.read_text(encoding='utf-8')
        self.assertEqual(1, shared.count('.connectTimeout(15, TimeUnit.SECONDS)'))
        self.assertEqual(1, shared.count('.readTimeout(25, TimeUnit.SECONDS)'))
        self.assertEqual(0, shared.count('.callTimeout('))
        self.assertEqual(1, routing.count('import java.util.concurrent.TimeUnit\n'))
        self.assertIn('blob 6d53ec0b339e7e8cd70b55949e84a5dabd9280e9', routing)
        self.assertIn('The Bilibili host gate itself is not changed here.', routing)
        for imported in ('com.android.purebilibili.core.plugin.feed.publicContentImageHttpClient',
                         'com.bilipai.desktop.data.DesktopRepository', 'com.bilipai.desktop.data.usesDefaultBilibiliHeaders',
                         'com.bilipai.desktop.network.DesktopNetworkProxyPlatform'):
            self.assertEqual(1, routing.count('import ' + imported + '\n'), imported)
        self.assertIn('internal val publicContentImageHttpClient: OkHttpClient by lazy {',
                      self.main[FEED + source.NETWORK].decode('utf-8'))
        self.assertIn('const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)', REPOSITORY.read_text(encoding='utf-8'))

    def test_windows_kotlin_files_keep_balanced_delimiters_and_the_jvm_test_has_five_cases(self):
        for path in (ROUTING, ROUTING_TEST, LOADER):
            with self.subTest(path=path.name):
                text = path.read_text(encoding='utf-8')
                self.assertNotIn('\r', text)
                self.balanced(text)
        for generated_path in (FEED + 'FeedFetcher.kt', FEED + source.NETWORK):
            with self.subTest(path=generated_path):
                self.balanced(lf(self.main[generated_path]))
        self.balanced(self.tests[FEED + source.NETWORK_TEST].decode('utf-8'))
        routing_test = ROUTING_TEST.read_text(encoding='utf-8')
        self.assertEqual(5, routing_test.count('@Test'))
        self.assertEqual(1, routing_test.count('class DesktopPublicContentImageRoutingTest'))
        self.assertIn('package com.bilipai.desktop.ui', routing_test)
        self.assertNotIn('Thread.sleep', routing_test)
        self.assertNotIn('MockWebServer', routing_test)
        # Backticked JVM names must not contain characters the JVM forbids.
        for name in re.findall(r'fun `([^`]+)`', routing_test):
            self.assertIsNone(re.search(r'[.;\[\]/<>:\\,]', name), name)

    def balanced(self, text):
        stack = []
        for token, first, _end in sync.kotlin_tokens(text):
            if token in ('(', '[', '{'):
                stack.append(token)
            elif token in (')', ']', '}'):
                self.assertTrue(stack, first)
                self.assertEqual({')': '(', ']': '[', '}': '{'}[token], stack.pop(), first)
        self.assertEqual([], stack)

    def test_generated_sources_have_no_android_or_readability_imports(self):
        for generated_path in (FEED + source.NETWORK, FEED + 'FeedFetcher.kt'):
            text = lf(self.main[generated_path])
            self.assertNotRegex(text, r'(?m)^import android\.', generated_path)
            self.assertNotIn('net.dankito', text, generated_path)
            self.assertNotIn('org.jsoup', text, generated_path)
        network_imports = set(re.findall(r'(?m)^import ([\w.]+)$', self.archive[source.NETWORK].decode('utf-8')))
        self.assertEqual({'okhttp3.Authenticator', 'okhttp3.CookieJar', 'okhttp3.HttpUrl', 'okhttp3.OkHttpClient',
                          'okhttp3.Request', 'java.util.concurrent.TimeUnit'}, network_imports)


if __name__ == '__main__':
    unittest.main()
