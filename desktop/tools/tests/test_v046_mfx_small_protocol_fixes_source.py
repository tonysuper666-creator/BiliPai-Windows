"""v0.4.6 small protocol fixes: whole chat history response and article parser, SponsorBlock and log redaction layers."""
from pathlib import Path
import contextlib
import hashlib
import importlib.util
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import textwrap
import unittest

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'desktop/tools'
sys.path.insert(0, str(TOOLS))
import check_slice_symbol_closure as gate
import v046_mfx_small_protocol_fixes as mfx
from v044_favorite_detail import inverse_proof
from v044_favorite_subscription import declaration, parser as kotlin_parser


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, TOOLS / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


sync = load('sync_upstream_mfx_small_protocol_fixes', 'sync-upstream.py')
message_pages = load('message_pages_mfx_small_protocol_fixes', 'extract-upstream-message-pages.py')
article_detail = load('article_detail_mfx_small_protocol_fixes', 'extract-upstream-article-detail.py')
diagnostics = load('diagnostics_mfx_small_protocol_fixes', 'extract-upstream-diagnostics.py')
plugins = load('plugins_mfx_small_protocol_fixes', 'extract-upstream-plugins.py')

ARCHIVE = mfx.ARCHIVE
RESPONSE = mfx.UPSTREAM_PATHS['MessageResponse.kt']
PARSER = mfx.UPSTREAM_PATHS['ArticleContentBlockParser.kt']
JVM_TESTS = {
    'desktop/src/test/kotlin/com/android/purebilibili/data/model/response/MessageHistoryParsingTest.kt': 4,
    'desktop/src/test/kotlin/com/bilipai/desktop/data/DesktopMessageHistoryCursorTest.kt': 4,
    'desktop/src/test/kotlin/com/bilipai/desktop/plugins/DesktopSponsorBlockProtocolTest.kt': 7,
    'desktop/src/test/kotlin/com/bilipai/desktop/diagnostics/DesktopLogRedactionPatternsTest.kt': 2,
    'desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopArticlePublicNoteFallbackTest.kt': 8,
}
WINDOWS_MAIN = (
    'desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopSponsorBlockClientVersion.kt',
    'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopArticlePublicNoteFallback.kt',
    'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalArticleBindings.kt',
)
STATIC_ROOTS = ('app/src/main/java', 'design-system/src/main/java', 'core-data/src/main/java',
                'core-player/src/main/java', 'desktop/src', 'desktop/upstream-slices')


def sha(raw):
    return hashlib.sha256(raw if isinstance(raw, bytes) else raw.encode('utf8')).hexdigest()


def lf(path):
    return Path(path).read_bytes().replace(b'\r\n', b'\n').decode('utf8')


def tree(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in sorted(Path(root).rglob('*')) if p.is_file()}


def archive_files():
    return {p.name: p.read_bytes() for p in (REPO / ARCHIVE).iterdir() if p.is_file()}


def frozen(root):
    rows = []
    for path in sorted(Path(root).rglob('*')):
        rel = path.relative_to(root).as_posix()
        if path.is_symlink():
            rows.append((rel, 'link', os.readlink(path)))
        elif path.is_file():
            rows.append((rel, 'file', path.read_bytes()))
        elif path.is_dir():
            rows.append((rel, 'dir'))
        else:
            rows.append((rel, 'other', path.lstat().st_mode))
    return tuple(rows)


def prepare_block(build):
    start = build.index('val prepareUpstreamSources by tasks.registering(Sync::class) {')
    return build[start:build.index('\nval extractUpstreamApi ', start)]


@contextlib.contextmanager
def patched(**replacements):
    saved = {name: getattr(mfx, name) for name in replacements}
    for name, value in replacements.items():
        setattr(mfx, name, value)
    try:
        yield
    finally:
        for name, value in saved.items():
            setattr(mfx, name, value)


def spy(captured, name):
    real = getattr(mfx, name)

    def wrapper(repo, before):
        captured[name] = before
        return real(repo, before)

    return wrapper


def identity(repo, before):
    return before, {}


def balanced(case, source):
    stack = []
    for token, first, _end in sync.kotlin_tokens(source):
        if token in ('(', '[', '{'):
            stack.append(token)
        elif token in (')', '}', ']'):
            case.assertTrue(stack, first)
            case.assertEqual({')': '(', ']': '[', '}': '{'}[token], stack.pop(), first)
    case.assertEqual([], stack)


def declarations(text, path):
    info = gate.parse_kotlin(text, path, 'generated', 'main')
    return info.package, [(item.visibility, item.kind, item.name) for item in info.decls]


class V046SmallProtocolFixesSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sources, cls.raws, cls.manifest = mfx.fixed(REPO)
        cls.archive = archive_files()
        cls.syntax = kotlin_parser(REPO)
        cls.temporary = tempfile.TemporaryDirectory(prefix='bp-mfx-')
        cls.root = Path(cls.temporary.name)
        cls.captured = {}
        with patched(advance_sponsorblock_repository=spy(cls.captured, 'advance_sponsorblock_repository'),
                     advance_sponsorblock_plugin=spy(cls.captured, 'advance_sponsorblock_plugin'),
                     advance_log_collector=spy(cls.captured, 'advance_log_collector')):
            plugins.generate(REPO, cls.root / 'plugins')
            diagnostics.generate(REPO, cls.root / 'diagnostics')
        message_pages.generate(REPO, cls.root / 'message')
        article_detail.generate(REPO, cls.root / 'article')
        cls.plugins = tree(cls.root / 'plugins')
        cls.diagnostics = tree(cls.root / 'diagnostics')
        cls.message = tree(cls.root / 'message')
        cls.article = tree(cls.root / 'article')
        cls.build = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf-8')
        cls.catalog = json.loads((TOOLS / 'compatibility-source-inputs.json').read_text(encoding='utf-8'))

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def text(self, files, relative):
        return files[relative].decode('utf8').replace('\r\n', '\n')

    # ---- archive and whole emitted files -------------------------------------------------------------------

    def test_archive_leaf_manifest_license_and_extra_leaf_reject_tampering(self):
        original = archive_files()
        self.assertEqual(self.archive, original)
        self.assertEqual(sorted(mfx.EXPECTED | {'LICENSE', 'manifest.json'}), sorted(original))
        for leaf in sorted(original):
            with self.subTest(leaf=leaf), tempfile.TemporaryDirectory(prefix='bp-mfx-') as tmp:
                repo = Path(tmp)
                shutil.copytree(REPO / ARCHIVE, repo / ARCHIVE)
                target = repo / ARCHIVE / leaf
                target.write_bytes(target.read_bytes() + b'\n')
                with self.assertRaises(ValueError):
                    mfx.fixed(repo)
        with tempfile.TemporaryDirectory(prefix='bp-mfx-') as tmp:
            repo = Path(tmp)
            shutil.copytree(REPO / ARCHIVE, repo / ARCHIVE)
            (repo / ARCHIVE / 'Extra.kt').write_bytes(b'package extra\n')
            with self.assertRaises(ValueError):
                mfx.fixed(repo)
        self.assertEqual(original, archive_files())

    def test_every_leaf_is_the_exact_upstream_git_blob_with_lf_endings(self):
        self.assertEqual(mfx.MANIFEST_SHA256, sha(self.archive['manifest.json']))
        for row in self.manifest['files']:
            with self.subTest(name=row['filename']):
                blob = subprocess.run(['git', '-c', 'core.autocrlf=false', 'cat-file', 'blob', mfx.COMMIT + ':' + row['path']],
                                      cwd=str(REPO), capture_output=True, check=True).stdout
                self.assertEqual(blob, self.archive[row['filename']])
                self.assertEqual(row['gitBlob'], hashlib.sha1(b'blob ' + str(len(blob)).encode() + b'\0' + blob).hexdigest())
                self.assertNotIn(b'\r', blob)
        self.assertEqual(sorted(mfx.UPSTREAM_PATHS.values()), sorted(row['path'] for row in self.manifest['files']))

    def test_whole_files_are_emitted_verbatim_by_their_existing_producers(self):
        for name, files in (('MessageResponse.kt', self.message), ('MessageHistoryCursorSerializer.kt', self.message),
                            ('ArticleContentBlockParser.kt', self.article)):
            with self.subTest(name=name):
                self.assertEqual(self.archive[name], files[mfx.RELATIVE[name]])
                matches = [path for path, raw in files.items() if raw == self.archive[name]]
                self.assertEqual([mfx.RELATIVE[name]], matches)
        for name in ('ArticleContentBlockParser.kt',):
            self.assertNotIn(mfx.RELATIVE[name], self.message)
        for name in mfx.MESSAGE_FILES:
            self.assertNotIn(mfx.RELATIVE[name], self.article)
        for leaf in ('SponsorBlockRepository.kt', 'SponsorBlockPlugin.kt', 'Logger.kt', 'ArticleRepository.kt'):
            self.assertNotIn(self.archive[leaf], list(self.plugins.values()) + list(self.diagnostics.values()) +
                             list(self.message.values()) + list(self.article.values()))

    def test_vendored_message_response_differs_only_by_the_cursor_annotation(self):
        vendored = lf(REPO / RESPONSE)
        proof = inverse_proof(vendored, self.archive['MessageResponse.kt'].decode('utf8'))
        self.assertEqual(1, len(proof['hunks']))
        hunk = proof['hunks'][0]
        self.assertEqual('', hunk['before'])
        self.assertEqual('    @Serializable(with = MessageHistoryCursorSerializer::class)\n', hunk['after'])
        self.assertNotIn('MessageHistoryCursorSerializer', vendored)
        serializer = self.archive['MessageHistoryCursorSerializer.kt'].decode('utf8')
        self.assertIn('if (raw == "18446744073709551615") return 0L', serializer)
        self.assertIn('?.coerceAtLeast(0L)', serializer)

    def test_vendored_article_parser_differs_only_by_the_three_upstream_hunks(self):
        proof = inverse_proof(lf(REPO / PARSER), self.archive['ArticleContentBlockParser.kt'].decode('utf8'))
        self.assertEqual(3, len(proof['hunks']))
        added = ''.join(hunk['after'] for hunk in proof['hunks'])
        for token in ('if (contentOps.isNotEmpty()) emptyList() else {',
                      '!rawContent.startsWith("{") && !rawContent.startsWith("[")',
                      'is kotlinx.serialization.json.JsonArray -> root',
                      'is JsonObject -> root["ops"]?.jsonArray'):
            self.assertIn(token, added)

    def test_emit_is_deterministic_a_stale_file_is_replaced_and_special_targets_fail_closed(self):
        emitters = ((mfx.emit_message_history, mfx.MESSAGE_FILES), (mfx.emit_article_parser, mfx.ARTICLE_FILES))
        for emit, names in emitters:
            first = emit(REPO, self.root / ('emit-a-' + names[0]))
            second = emit(REPO, self.root / ('emit-a-' + names[0]))
            self.assertEqual(first, second)
            self.assertEqual([mfx.RELATIVE[name] for name in names], [row['path'] for row in first['files']])
            for name in names:
                self.assertEqual(self.archive[name], (self.root / ('emit-a-' + names[0]) / mfx.RELATIVE[name]).read_bytes())
            for kind in ('stale', 'symlink', 'directory'):
                relative = mfx.RELATIVE[names[0]]
                with self.subTest(kind=kind, relative=relative), tempfile.TemporaryDirectory(prefix='bp-mfx-') as tmp:
                    root = Path(tmp)
                    (root / 'outside.txt').write_bytes(b'OUTSIDE')
                    target = root / relative
                    target.parent.mkdir(parents=True)
                    (target.parent / 'decoy.kt').write_bytes(b'DECOY')
                    if kind == 'stale':
                        target.write_bytes(b'SENTINEL-FOREIGN')
                        emit(REPO, root)
                        self.assertEqual(self.archive[names[0]], target.read_bytes())
                        self.assertTrue(stat.S_ISREG(os.lstat(target).st_mode))
                        self.assertEqual(b'DECOY', (target.parent / 'decoy.kt').read_bytes())
                        continue
                    if kind == 'symlink':
                        neighbor = target.parent / 'neighbor.bin'
                        neighbor.write_bytes(b'NEIGHBOR')
                        try:
                            target.symlink_to(neighbor)
                        except OSError:
                            self.skipTest('symlink creation is not permitted')
                    else:
                        target.mkdir()
                        (target / 'nested.txt').write_bytes(b'NESTED')
                    before = frozen(root)
                    with self.assertRaises(ValueError) as caught:
                        emit(REPO, root)
                    self.assertIn('not a regular file: ' + relative, str(caught.exception))
                    self.assertEqual(before, frozen(root))
                    self.assertEqual(b'OUTSIDE', (root / 'outside.txt').read_bytes())

    def test_output_overlapping_the_archive_is_rejected_by_every_hooked_producer(self):
        before = archive_files()
        nested = REPO / ARCHIVE / 'child'
        try:
            for label, call in (
                ('validate', lambda: mfx.validate_outputs(REPO, REPO)),
                ('emit-message', lambda: mfx.emit_message_history(REPO, nested)),
                ('emit-article', lambda: mfx.emit_article_parser(REPO, nested)),
                ('plugins', lambda: plugins.generate(REPO, nested)),
                ('diagnostics', lambda: diagnostics.generate(REPO, nested)),
                ('message-pages', lambda: message_pages.generate(REPO, nested)),
                ('article-detail', lambda: article_detail.generate(REPO, nested)),
            ):
                with self.subTest(producer=label), self.assertRaisesRegex(ValueError, 'overlaps'):
                    call()
            self.assertFalse(nested.exists())
            self.assertEqual(before, archive_files())
        finally:
            if nested.exists():
                shutil.rmtree(nested)
            for name, raw in before.items():
                target = REPO / ARCHIVE / name
                if not target.is_file() or target.read_bytes() != raw:
                    target.write_bytes(raw)

    # ---- layers -----------------------------------------------------------------------------------------------

    def test_predecessor_pins_match_the_real_producer_bodies_and_every_layer_inverts(self):
        pins = self.manifest['beforeFinalOutputs']
        self.assertEqual({mfx.REPOSITORY_OUTPUT, mfx.PLUGIN_OUTPUT, mfx.COLLECTOR_OUTPUT}, set(pins))
        self.assertEqual({'advance_sponsorblock_repository', 'advance_sponsorblock_plugin', 'advance_log_collector'},
                         set(self.captured))
        for name, relative, output in (
            ('advance_sponsorblock_repository', mfx.REPOSITORY_OUTPUT, self.plugins),
            ('advance_sponsorblock_plugin', mfx.PLUGIN_OUTPUT, self.plugins),
            ('advance_log_collector', mfx.COLLECTOR_OUTPUT, self.diagnostics),
        ):
            with self.subTest(layer=name):
                before = self.captured[name]
                self.assertEqual(pins[relative]['sha256'], sha(before))
                after, proof = getattr(mfx, name)(REPO, before)
                self.assertEqual(before, mfx.restore(after, proof['retainedToCurrent']))
                self.assertTrue(proof['retainedToCurrent']['wholeInverseExact'])
                self.assertEqual(mfx.COMMIT, proof['upstreamCommit'])
                self.assertEqual(mfx.MANIFEST_SHA256, proof['sourceManifestSHA256'])
                self.assertFalse(proof['jvmTestsExecuted'])
                emitted = self.text(output, relative)
                self.assertIn(after.strip(), emitted)
        for name, proof_file in (('plugins', 'v046-mfx-sponsorblock-repository-proof.json'),
                                 ('plugins', 'v046-mfx-sponsorblock-plugin-proof.json'),
                                 ('diagnostics', 'v046-mfx-log-redaction-proof.json')):
            written = json.loads(getattr(self, name)[proof_file].decode('utf8'))
            self.assertTrue(written['retainedToCurrent']['wholeInverseExact'])
            self.assertEqual(mfx.MANIFEST_SHA256, written['sourceManifestSHA256'])

    def test_sponsorblock_repository_layer_carries_the_complete_original_declarations(self):
        original = self.sources['SponsorBlockRepository.kt']
        body = self.text(self.plugins, mfx.REPOSITORY_OUTPUT)
        syntax = self.syntax
        for name in ('buildSponsorBlockVoteBody', 'voteOnSegment'):
            self.assertEqual(declaration(original, 'fun', name, syntax), declaration(body, 'fun', name, syntax), name)
        request = mfx._expression_function(original, 'internal fun buildSponsorBlockClientRequest(', '\n        .build()')
        self.assertEqual(1, body.count(request))
        client = declaration(body, 'fun', 'buildSponsorBlockHttpClient', syntax)
        upstream_client = declaration(original, 'fun', 'buildSponsorBlockHttpClient', syntax)
        self.assertEqual(upstream_client.replace('BuildConfig.VERSION_NAME', mfx.VERSION_BINDING), client)
        self.assertEqual(1, upstream_client.count('BuildConfig.VERSION_NAME'))
        self.assertNotIn('BuildConfig', body)
        self.assertNotIn('"BiliPai/2.4.1"', body)
        self.assertNotIn('addQueryParameter("UUID"', body)
        self.assertIn('import kotlinx.serialization.json.buildJsonObject\nimport kotlinx.serialization.json.put\n', body)
        for name in ('buildSponsorBlockSegmentsUrl', 'buildSponsorBlockClientRequest', 'buildSponsorBlockVoteBody'):
            self.assertEqual(1, body.count('internal fun ' + name + '('), name)
        # the Windows transport bindings that the layer sits on survive
        self.assertIn('com.bilipai.desktop.plugins.DesktopPlayerPluginWriteAdmission.executePublicOrOriginal(client.newCall(request))', body)
        self.assertIn('com.bilipai.desktop.appearance.DesktopMonotonicClock.elapsedRealtime()', body)
        proof = json.loads(self.plugins['v046-mfx-sponsorblock-repository-proof.json'])
        binding = proof['completeOriginalDeclarations']['buildSponsorBlockHttpClient']['platformBinding']
        self.assertEqual(upstream_client, mfx.restore(client, binding))
        self.assertEqual(sha(upstream_client), proof['completeOriginalDeclarations']['buildSponsorBlockHttpClient']['originalSHA256'])

    def test_sponsorblock_plugin_layer_carries_the_original_config_loading(self):
        original = self.sources['SponsorBlockPlugin.kt']
        body = self.text(self.plugins, mfx.PLUGIN_OUTPUT)
        self.assertEqual(declaration(original, 'fun', 'loadConfigSuspend', self.syntax),
                         declaration(body, 'fun', 'loadConfigSuspend', self.syntax))
        self.assertEqual(1, body.count(mfx.CONFIG_AFTER))
        self.assertNotIn(mfx.CONFIG_BEFORE, body)
        self.assertEqual(1, body.count('PluginStore.setConfigJson(context, id, Json.encodeToString(config))'))
        self.assertNotIn('config = SponsorBlockConfig(autoSkip = true)', body)
        for needed in ('import kotlinx.serialization.encodeToString', 'import com.android.purebilibili.core.plugin.PluginStore'):
            self.assertIn(needed, body)
        # unrelated declarations of the extracted plugin are byte-identical to the unlayered producer body
        before = self.captured['advance_sponsorblock_plugin']
        for name in ('onVideoLoad', 'voteOnCommunitySegment'):
            self.assertEqual(declaration(before, 'fun', name, self.syntax), declaration(body, 'fun', name, self.syntax))

    def test_log_collector_layer_shares_immutable_patterns_and_keeps_the_sanitizer_behaviour(self):
        logger = self.sources['Logger.kt']
        body = self.text(self.diagnostics, mfx.COLLECTOR_OUTPUT)
        old_logger = lf(REPO / mfx.UPSTREAM_PATHS['Logger.kt'])
        media = mfx._media(REPO)
        collector = logger[logger.index('object LogCollector {'):]
        old_collector = old_logger[old_logger.index('object LogCollector {'):]
        new_function = media.function(collector, 'sanitizeMessage', self.syntax)
        old_function = media.function(old_collector, 'sanitizeMessage', self.syntax)
        self.assertEqual(new_function, media.function(body[body.index('companion object'):], 'sanitizeMessage', self.syntax))
        patterns = mfx._patterns_declaration(logger, self.syntax)
        self.assertEqual(1, body.count(patterns))
        self.assertEqual(1, body.count('private object LogRedactionPatterns {'))
        self.assertTrue(body.rstrip('\n').endswith(patterns.rstrip('\n')))
        # no pattern is compiled per call any more
        self.assertEqual(0, new_function.count('Regex('))
        self.assertEqual(58, old_function.count('Regex('))
        self.assertEqual(58, new_function.count('LogRedactionPatterns.'))
        properties = dict(re.findall(r'(?m)^    val (\w+) = (Regex\(.*\))$', patterns))
        self.assertEqual(58, len(properties))
        # Behaviour: inlining every shared pattern into the new sanitizer gives back the pre-0.4.6 sanitizer text.
        inlined = new_function
        for name, expression in properties.items():
            inlined, count = re.subn(r'LogRedactionPatterns\.' + name + r'\b', lambda _m, e=expression: e, inlined)
            self.assertGreaterEqual(count, 1, name)
        self.assertNotIn('LogRedactionPatterns.', inlined)
        squash = lambda text: re.sub(r'\s+', '', text)
        self.assertEqual(squash(old_function), squash(inlined))
        # the Windows owner still reaches the sanitizer through the unchanged policy function
        self.assertIn('DesktopDiagnosticCollector.sanitizeMessage(message)',
                      (self.root / 'diagnostics/com/android/purebilibili/core/util/DesktopDiagnosticPolicy.kt').read_text('utf8'))

    def test_layers_fail_closed_on_predecessor_drift_and_double_application(self):
        for name, key in (('advance_sponsorblock_repository', 'advance_sponsorblock_repository'),
                          ('advance_sponsorblock_plugin', 'advance_sponsorblock_plugin'),
                          ('advance_log_collector', 'advance_log_collector')):
            before = self.captured[key]
            advance = getattr(mfx, name)
            after, _proof = advance(REPO, before)
            with self.subTest(layer=name):
                for drifted in (before + '\n// drift\n', before.replace('package ', 'package  ', 1), after, ''):
                    with self.assertRaisesRegex(ValueError, 'predecessor changed'):
                        advance(REPO, drifted)
        with self.assertRaisesRegex(ValueError, 'anchor changed'):
            mfx._once('a', 'b', 'c', 'missing')
        with self.assertRaisesRegex(ValueError, 'anchor changed'):
            mfx._once('bb', 'b', 'c', 'duplicate')

    def test_patching_a_layer_out_changes_only_its_own_outputs(self):
        with patched(advance_sponsorblock_repository=identity, advance_sponsorblock_plugin=identity,
                     advance_log_collector=identity):
            plain_plugins = self.root / 'plain-plugins'
            plain_diagnostics = self.root / 'plain-diagnostics'
            plugins.generate(REPO, plain_plugins)
            diagnostics.generate(REPO, plain_diagnostics)
        for layered, plain, relatives in (
            (self.plugins, tree(plain_plugins), {mfx.REPOSITORY_OUTPUT, mfx.PLUGIN_OUTPUT}),
            (self.diagnostics, tree(plain_diagnostics), {mfx.COLLECTOR_OUTPUT}),
        ):
            differing = {path for path in set(layered) | set(plain) if layered.get(path) != plain.get(path)
                         and not path.endswith('-proof.json')}
            self.assertEqual(relatives, differing)

    def test_generated_kotlin_keeps_balanced_delimiters_and_lf_text(self):
        for label, text in (
            ('repository', self.text(self.plugins, mfx.REPOSITORY_OUTPUT)),
            ('plugin', self.text(self.plugins, mfx.PLUGIN_OUTPUT)),
            ('collector', self.text(self.diagnostics, mfx.COLLECTOR_OUTPUT)),
            ('response', self.archive['MessageResponse.kt'].decode('utf8')),
            ('serializer', self.archive['MessageHistoryCursorSerializer.kt'].decode('utf8')),
            ('parser', self.archive['ArticleContentBlockParser.kt'].decode('utf8')),
        ):
            with self.subTest(file=label):
                balanced(self, text)
        for path in WINDOWS_MAIN + tuple(JVM_TESTS):
            with self.subTest(file=path):
                raw = (REPO / path).read_bytes()
                self.assertNotIn(b'\r', raw)
                balanced(self, raw.decode('utf8'))

    # ---- Gradle, inventory and producer hooks -------------------------------------------------------------

    def test_gradle_excludes_the_two_direct_copies_and_tracks_the_archive_inputs(self):
        build = self.build
        prepared = prepare_block(build)
        model = gate.parse_gradle(build)
        for path in (RESPONSE, PARSER):
            line = 'exclude(canonicalOriginalIdentity("' + path + '"))'
            self.assertEqual(1, build.count(line), line)
            self.assertEqual(1, prepared.count(line), line)
            self.assertEqual(1, model.sync_excludes.count(path))
        sources = json.loads((REPO / 'desktop/upstream-sources.json').read_text(encoding='utf-8'))
        for path in (RESPONSE, PARSER):
            listed = [row for row in sources['sources'] if row['path'] == path]
            self.assertEqual(1, len(listed))
            self.assertEqual('direct', listed[0].get('mode', 'direct'))
        # the retired copies are no longer compiled: the archive is the only definer of the new symbols
        vendored = lf(REPO / RESPONSE)
        self.assertNotIn('MessageHistoryCursorSerializer', vendored)
        block = build[build.index('tasks.matching { it.name in setOf(\n    "extractOriginalMessagePages"'):]
        block = block[:block.index('\n}\n') + 3]
        for task in ('extractOriginalMessagePages', 'extractOriginalArticleDetail', 'extractUpstreamPlugins', 'extractUpstreamDiagnostics'):
            self.assertIn('"' + task + '"', block)
            self.assertIn('val ' + task + ' by tasks.registering(Exec::class)', build)
        for literal in ('inputs.file("tools/v046_mfx_small_protocol_fixes.py")', 'inputs.dir("upstream-slices/v046-mfx-small-protocol-fixes")'):
            self.assertEqual(1, build.count(literal), literal)
            self.assertEqual(1, block.count(literal), literal)
        self.assertEqual([], [row for row in build.splitlines() if 'srcDir' in row and 'mfx-small-protocol' in row])
        self.assertEqual(1, build.count('kotlin.srcDir(layout.buildDirectory.dir("generated/original-message-pages"))'))
        self.assertEqual(1, build.count('kotlin.srcDir(layout.buildDirectory.dir("generated/original-article-detail"))'))

    def test_inventory_rows_catalog_digest_and_counts_cover_every_leaf(self):
        catalog = self.catalog
        self.assertEqual(1, catalog['buildInputRoots'].count(ARCHIVE))
        archives = [row for row in catalog['archives'] if row['directory'] == ARCHIVE]
        self.assertEqual(1, len(archives))
        self.assertEqual({ARCHIVE + '/' + name for name in mfx.EXPECTED | {'LICENSE', 'manifest.json'}},
                         {leaf['path'] for leaf in archives[0]['files']})
        rows = [row for row in catalog['inputs'] if row['localPath'].startswith(ARCHIVE + '/')]
        self.assertEqual(sorted(mfx.UPSTREAM_PATHS.values()), sorted(row['path'] for row in rows))
        for row in rows:
            self.assertEqual(['v046-mfx-small-protocol-fixes'], row['features'])
            self.assertEqual('raw', row['hashNormalization'])
            self.assertEqual(mfx.COMMIT, row['upstreamCommit'])
            self.assertTrue(row['usedScope'].isascii() and row['usedScope'])
            self.assertEqual(row['path'].endswith('ArticleRepository.kt'), row['referenceOnly'])
        whole = {row['localPath'].rsplit('/', 1)[1] for row in rows if not row['partialReuse']}
        self.assertEqual({'MessageResponse.kt', 'MessageHistoryCursorSerializer.kt', 'MessageHistoryParsingTest.kt',
                          'ArticleContentBlockParser.kt'}, whole)
        methods = {row['localPath'].rsplit('/', 1)[1]: row['reusedMethods'] for row in rows if 'reusedMethods' in row}
        self.assertEqual({'SponsorBlockRepository.kt': ['buildSponsorBlockHttpClient', 'buildSponsorBlockClientRequest',
                                                        'buildSponsorBlockVoteBody', 'voteOnSegment'],
                          'SponsorBlockPlugin.kt': ['loadConfigSuspend'], 'Logger.kt': ['sanitizeMessage']}, methods)
        loaded = sync.adapted_compatibility_inputs(REPO)
        self.assertEqual(rows, [row for row in loaded if row['localPath'].startswith(ARCHIVE + '/')])
        self.assertEqual(sync.COMPATIBILITY_CATALOG_SHA256, sha((TOOLS / 'compatibility-source-inputs.json').read_bytes()))

    def test_each_hooked_producer_validates_first_and_layers_after_its_own_adaptations(self):
        def source(name):
            return (TOOLS / name).read_text(encoding='utf-8')

        plugin_source = source('extract-upstream-plugins.py')
        start = plugin_source.index('def generate(repo: Path, output: Path)')
        end = plugin_source.index('def generate_additional(', start)
        generate = plugin_source[start:end]
        self.assertLess(generate.index('validate_mfx_outputs(repo, output)'), generate.index('prune_retired_renderer_outputs(repo, output)'))
        repository = generate.index('body, mfx_repository_proof = advance_sponsorblock_repository(repo, body)')
        self.assertLess(generate.index('executePublicOrOriginal(client.newCall(request)) { response ->", 1)'), repository)
        self.assertLess(repository, generate.index('generated.append(write(output, path, source, body))', repository))
        plugin = generate.index('body, mfx_plugin_proof = advance_sponsorblock_plugin(repo, platform_logger(body))')
        self.assertLess(generate.index('logSponsorBlockSkip", "com.bilipai.desktop.plugins.DesktopPluginAnalytics.logSponsorBlockSkip")'), plugin)
        diagnostics_source = source('extract-upstream-diagnostics.py')
        self.assertLess(diagnostics_source.index('validate_mfx_outputs(repo,out)'), diagnostics_source.index("host=load(repo/'desktop/tools/extract-upstream-plugins.py'"))
        self.assertLess(diagnostics_source.index('source,mfx_proof=advance_log_collector(repo,source)'),
                        diagnostics_source.index("files.append(host.write(out,LOGGER,original,source,'DesktopDiagnosticCollector.kt'))"))
        message_source = source('extract-upstream-message-pages.py')
        self.assertLess(message_source.index('validate_mfx_outputs(repo,out)'), message_source.index("spec=importlib.util.spec_from_file_location('message_decl'"))
        self.assertLess(message_source.index("message_scaffold_source(repo))"), message_source.index('emit_message_history(repo,out)'))
        self.assertLess(message_source.index('emit_message_history(repo,out)'), message_source.index(' return files', message_source.index('emit_message_history(repo,out)')))
        article_source = source('extract-upstream-article-detail.py')
        self.assertLess(article_source.index('validate_mfx_outputs(repo,output)'), article_source.index("spec=importlib.util.spec_from_file_location('article_source_lexer'"))
        self.assertLess(article_source.index("put('platform-edits.json'"), article_source.index('emit_article_parser(repo,output)'))
        for text in (plugin_source, diagnostics_source, message_source, article_source):
            self.assertFalse(any(line.startswith(('import v046_mfx', 'from v046_mfx')) for line in text.splitlines()))

    def test_each_top_level_name_is_declared_once_in_the_whole_producer_universe(self):
        wanted = {}
        for name in mfx.EMITTED_FILES:
            package, decls = declarations(self.archive[name].decode('utf8'), name)
            self.assertEqual(mfx.PACKAGE[name], package)
            for visibility, kind, declared in decls:
                if visibility != 'private':
                    wanted[declared] = (name, visibility, kind, package)
        self.assertIn('MessageHistoryCursorSerializer', wanted)
        self.assertIn('MessageHistoryData', wanted)
        self.assertIn('parseArticleContentBlocks', wanted)
        scratch = tempfile.mkdtemp(prefix='bp-mfx-')
        try:
            root, env = gate.make_scratch_repo(REPO, scratch)
            model = gate.parse_gradle((root / 'desktop/build.gradle.kts').read_text(encoding='utf-8'))
            self.assertEqual(1, model.sync_excludes.count(RESPONSE))
            self.assertEqual(1, model.sync_excludes.count(PARSER))
            tasks, _skipped = gate.select_producers(model)
            results = gate.run_producers(root, tasks, env)
            failed = [(row['task'], row['returncode'], row['stderrTail']) for row in results if row['returncode']]
            self.assertEqual([], failed)
            self.assertTrue({'extractOriginalMessagePages', 'extractOriginalArticleDetail', 'extractUpstreamPlugins',
                             'extractUpstreamDiagnostics'} <= {row['task'] for row in results})
            hits = {}
            for base in (root / 'desktop/src', root / 'desktop/build'):
                for path in base.rglob('*'):
                    if not path.is_file() or path.suffix not in {'.kt', '.kts'}:
                        continue
                    raw = path.read_bytes()
                    if not any(name.encode('utf-8') in raw for name in wanted):
                        continue
                    relative = path.relative_to(root).as_posix()
                    package, decls = declarations(raw.decode('utf-8'), relative)
                    for visibility, _kind, declared in decls:
                        if declared in wanted and visibility != 'private':
                            hits.setdefault(declared, []).append((package, relative))
            for declared, (name, _visibility, _kind, package) in wanted.items():
                found = hits.get(declared, [])
                self.assertEqual(1, len(found), (declared, found))
                self.assertEqual(package, found[0][0], declared)
                self.assertTrue(found[0][1].endswith(mfx.RELATIVE[name]), (declared, found[0][1]))
                self.assertNotIn('generated/upstream/', found[0][1])
            gate_output = {row['task'] for row in results}
            self.assertIn('extractOriginalMessagePages', gate_output)
        finally:
            shutil.rmtree(scratch, ignore_errors=True)

    # ---- Windows Kotlin sources -----------------------------------------------------------------------------

    def test_existing_sponsor_assembly_test_pins_the_v046_json_vote_not_the_old_query(self):
        text = (REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopOriginalSponsorPresentationAssemblyTest.kt').read_text(encoding='utf-8')
        repository = self.archive['SponsorBlockRepository.kt'].decode('utf8')
        self.assertIn('postJson("${baseUrl.trimEnd(\'/\')}/voteOnSponsorTime", body)', repository)
        for token in ('check(request.query.isEmpty())',
                      'check(vote.getValue("UUID").jsonPrimitive.content == SEGMENT_ID)',
                      'check(vote.getValue("userID").jsonPrimitive.content == USER_ID)',
                      'check(vote.getValue("type").jsonPrimitive.int == 0)',
                      'assertEquals(emptyMap<String, String>(), vote.query)',
                      'assertEquals(0, voteBody.getValue("type").jsonPrimitive.int)',
                      'assertEquals(setOf("UUID", "userID", "type"), voteBody.keys)'):
            self.assertEqual(1, text.count(token), token)
        for stale in ('request.query["UUID"] == SEGMENT_ID', 'request.query["type"] == "0"',
                      'assertEquals("0", vote.query["type"])', 'assertEquals("", vote.body)'):
            self.assertNotIn(stale, text, stale)

    def test_windows_jvm_regression_sources_exist_with_their_pinned_case_counts(self):
        for path, count in JVM_TESTS.items():
            with self.subTest(path=path):
                text = (REPO / path).read_text(encoding='utf-8')
                self.assertEqual(count, len(re.findall(r'(?m)^\s*@Test\b', text)))
        self.assertEqual(self.archive['MessageHistoryParsingTest.kt'],
                         (REPO / 'desktop/src/test/kotlin/com/android/purebilibili/data/model/response/MessageHistoryParsingTest.kt').read_bytes())
        for path in JVM_TESTS:
            self.assertFalse(any(token in (REPO / path).read_text(encoding='utf-8')
                                 for token in ('java.net.URL(', 'MockWebServer', 'Thread.sleep', 'GraphicsEnvironment', 'Robot(')), path)

    def test_article_fallback_mirrors_the_original_public_note_algorithm(self):
        original = self.sources['ArticleRepository.kt']
        windows = (REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopArticlePublicNoteFallback.kt').read_text(encoding='utf-8')
        function = declaration(original, 'fun', 'fetchPublicNoteArticle', self.syntax)
        for token in ('if (response.code != 0) return null', 'val note = response.data ?: return null',
                      'parseArticleContentBlocks(emptyList(), note.content)', 'if (blocks.isEmpty()) return null',
                      'articleId = note.cvid.takeIf { it > 0L } ?: articleId,', 'title = note.title.ifBlank { "公开笔记" },',
                      'summary = note.summary,', 'authorName = note.author?.name.orEmpty(),',
                      'authorMid = note.author?.mid ?: 0L,', 'publishTime = "",', 'bannerUrl = null,', 'blocks = blocks,'):
            self.assertIn(token, function, token)
            self.assertIn(token, windows, token)
        self.assertIn('authorFace = note.author?.face.normalizeImageUrl().orEmpty(),', function)
        self.assertIn('authorFace = note.author?.face.normalizedPublicNoteImageUrl().orEmpty(),', windows)
        # the three original trigger points: failed view, bad response, empty merged blocks
        for token in ('return@runCatching fetchPublicNoteArticle(articleId) ?: throw error',
                      'fetchPublicNoteArticle(articleId)?.let { return@runCatching it }',
                      'if (merged.blocks.isEmpty()) {'):
            self.assertIn(token, original)
        # the original getArticleDetail returns before any request for an invalid id, so the note is never read for it
        self.assertIn('if (articleId <= 0L) return primary(articleId)', windows)
        self.assertLess(windows.index('if (articleId <= 0L) return primary(articleId)'), windows.index('val first = primary(articleId)'))
        self.assertIn('if (articleId <= 0L) {', original)
        self.assertLess(original.index('if (articleId <= 0L) {'), original.index('articleApi.getArticleView('))
        self.assertIn('if (first.getOrNull()?.blocks?.isNotEmpty() == true) return first', windows)
        self.assertIn('val note = fetchPublicNoteArticle(articleId) ?: return first', windows)
        self.assertIn('throw cancelled', windows)
        bindings = (REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalArticleBindings.kt').read_text(encoding='utf-8')
        self.assertEqual(1, bindings.count('DesktopArticlePublicNoteFallback('))
        self.assertIn('api.getPublicVideoNoteInfo(articleId)', bindings)
        self.assertIn('publicNoteFallback.load(articleId, protocol::getArticleUiDetail)', bindings)
        self.assertNotIn('DesktopOriginalArticleBindings(protocol::getArticleUiDetail, owned)', bindings)

    def test_windows_version_binding_is_the_only_desktop_input_to_the_client_headers(self):
        version = (REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopSponsorBlockClientVersion.kt').read_text(encoding='utf-8')
        self.assertIn('DesktopUpdater.packagedVersion()', version)
        self.assertIn('"unpackaged-development"', version)
        self.assertEqual(1, version.count('internal fun desktopSponsorBlockVersionName(): String'))
        body = self.text(self.plugins, mfx.REPOSITORY_OUTPUT)
        self.assertEqual(1, body.count(mfx.VERSION_BINDING))
        self.assertIn('.header("Origin", "https://github.com/jay3-yy/BiliPai")', body)
        self.assertIn('.header("x-ext-version", versionName)', body)


if __name__ == '__main__':
    unittest.main()
