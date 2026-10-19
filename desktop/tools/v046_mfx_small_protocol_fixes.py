"""v0.4.6 small protocol fixes (roadmap mfx-small-protocol-fixes).

Whole files (the direct-mode copies are excluded in prepareUpstreamSources):
  MessageResponse.kt and MessageHistoryCursorSerializer.kt  (empty private-message history returns UINT64_MAX as min_seqno)
  ArticleContentBlockParser.kt                              (public notes are top-level Quill arrays)
Hash-pinned layers on existing producer outputs, each with an inverse proof:
  SponsorBlockRepository.kt  vote JSON body, client request headers, stale User-Agent removed
  SponsorBlockPlugin.kt      config always normalised (stable user ID) and persisted when it had none
  DesktopDiagnosticCollector.kt  shared immutable LogRedactionPatterns behind the unchanged sanitizer
ArticleRepository.kt is carried as the reference of the public-note fallback (see DesktopOriginalArticleBindings).
"""
from pathlib import Path
import hashlib
import importlib.util
import json
import os
import stat
import textwrap

from v044_favorite_detail import inverse_proof
from v044_favorite_subscription import parser, span, declaration, need

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-mfx-small-protocol-fixes'
MANIFEST_SHA256 = '8122e2e97d2cb1bb9d889dcc3a4e0309b49450015b19d8659075e8b5fb0d6674'
MESSAGE_FILES = ('MessageResponse.kt', 'MessageHistoryCursorSerializer.kt')
ARTICLE_FILES = ('ArticleContentBlockParser.kt',)
LAYER_FILES = ('SponsorBlockRepository.kt', 'SponsorBlockPlugin.kt', 'Logger.kt')
REFERENCE_FILES = ('ArticleRepository.kt',)
TEST_FILES = ('MessageHistoryParsingTest.kt',)
EMITTED_FILES = MESSAGE_FILES + ARTICLE_FILES
EXPECTED = set(EMITTED_FILES + LAYER_FILES + REFERENCE_FILES + TEST_FILES)
PACKAGE = {
    'MessageResponse.kt': 'com.android.purebilibili.data.model.response',
    'MessageHistoryCursorSerializer.kt': 'com.android.purebilibili.data.model.response',
    'ArticleContentBlockParser.kt': 'com.android.purebilibili.feature.article',
    'ArticleRepository.kt': 'com.android.purebilibili.data.repository',
    'SponsorBlockRepository.kt': 'com.android.purebilibili.data.repository',
    'SponsorBlockPlugin.kt': 'com.android.purebilibili.feature.plugin',
    'Logger.kt': 'com.android.purebilibili.core.util',
    'MessageHistoryParsingTest.kt': 'com.android.purebilibili.data.model.response',
}
RELATIVE = {name: PACKAGE[name].replace('.', '/') + '/' + name for name in EMITTED_FILES}
UPSTREAM_PATHS = {
    'MessageResponse.kt': 'core-data/src/main/java/com/android/purebilibili/data/model/response/MessageResponse.kt',
    'MessageHistoryCursorSerializer.kt':
        'core-data/src/main/java/com/android/purebilibili/data/model/response/MessageHistoryCursorSerializer.kt',
    'MessageHistoryParsingTest.kt':
        'app/src/test/java/com/android/purebilibili/data/model/response/MessageHistoryParsingTest.kt',
    'ArticleContentBlockParser.kt': 'app/src/main/java/com/android/purebilibili/feature/article/ArticleContentBlockParser.kt',
    'ArticleRepository.kt': 'app/src/main/java/com/android/purebilibili/data/repository/ArticleRepository.kt',
    'SponsorBlockRepository.kt': 'app/src/main/java/com/android/purebilibili/data/repository/SponsorBlockRepository.kt',
    'SponsorBlockPlugin.kt': 'app/src/main/java/com/android/purebilibili/feature/plugin/SponsorBlockPlugin.kt',
    'Logger.kt': 'app/src/main/java/com/android/purebilibili/core/util/Logger.kt',
}
# Every leaf carries these exact tokens once; a changed archive or a wrong pairing fails closed.
DECLARATIONS = {
    'MessageResponse.kt': (
        'data class MessageHistoryData(',
        '    @Serializable(with = MessageHistoryCursorSerializer::class)\n    val min_seqno: Long = 0,\n',
    ),
    'MessageHistoryCursorSerializer.kt': (
        'object MessageHistoryCursorSerializer : KSerializer<Long> {',
        'if (raw == "18446744073709551615") return 0L',
    ),
    'MessageHistoryParsingTest.kt': ('class MessageHistoryParsingTest {',),
    'ArticleContentBlockParser.kt': (
        'internal fun parseArticleContentBlocks(',
        '!rawContent.startsWith("{") && !rawContent.startsWith("[")',
    ),
    'ArticleRepository.kt': (
        'private suspend fun fetchPublicNoteArticle(articleId: Long): ArticleDetailUiModel? {',
        'navApi.getPublicVideoNoteInfo(articleId)',
    ),
    'SponsorBlockRepository.kt': (
        'internal fun buildSponsorBlockClientRequest(',
        'internal fun buildSponsorBlockVoteBody(',
    ),
    'SponsorBlockPlugin.kt': (
        'private var config: SponsorBlockConfig = SponsorBlockConfig().normalized()',
        'private suspend fun loadConfigSuspend() {',
    ),
    'Logger.kt': (
        'private object LogRedactionPatterns {',
        'internal fun sanitizeMessage(message: String): String {',
    ),
}
REPOSITORY_OUTPUT = 'com/android/purebilibili/data/repository/SponsorBlockRepository.kt'
PLUGIN_OUTPUT = 'com/android/purebilibili/feature/plugin/SponsorBlockPlugin.kt'
COLLECTOR_OUTPUT = 'com/android/purebilibili/core/util/DesktopDiagnosticCollector.kt'
VERSION_BINDING = 'com.bilipai.desktop.plugins.desktopSponsorBlockVersionName()'
SPONSOR_STALE_AGENT = '            .header("User-Agent", "BiliPai/2.4.1")\n'
JSON_IMPORT_ANCHOR = 'import kotlinx.serialization.json.intOrNull\n'
JSON_IMPORTS_ADDED = ('import kotlinx.serialization.json.buildJsonObject\n'
                      'import kotlinx.serialization.json.put\n')
CONFIG_BEFORE = '    private var config: SponsorBlockConfig = SponsorBlockConfig()\n'
CONFIG_AFTER = '    private var config: SponsorBlockConfig = SponsorBlockConfig().normalized()\n'
PATTERNS_DOC = '/** Immutable patterns are shared across logging threads; replacement order stays in sanitizeMessage. */\n'


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def fixed(repo):
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing small protocol fix inputs')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'},
         'Small protocol fix input leaves changed')
    manifest_raw = (root / 'manifest.json').read_bytes()
    need(b'\r' not in manifest_raw and sha(manifest_raw) == MANIFEST_SHA256, 'Small protocol fix manifest changed')
    manifest = json.loads(manifest_raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'Small protocol fix source identity changed')
    need(len(manifest['files']) == len(EXPECTED) and {row['filename'] for row in manifest['files']} == EXPECTED,
         'Small protocol fix source set changed')
    sources, raws = {}, {}
    for row in manifest['files']:
        file = root / row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous small protocol fix source')
        need(row['path'] == UPSTREAM_PATHS[file.name], 'Small protocol fix upstream path changed: ' + file.name)
        leaf = file.read_bytes()
        need(b'\r' not in leaf and len(leaf) == row['bytes'] and sha(leaf) == row['sha256'],
             'Small protocol fix bytes changed: ' + file.name)
        need(hashlib.sha1(b'blob ' + str(len(leaf)).encode() + b'\0' + leaf).hexdigest() == row['gitBlob'],
             'Small protocol fix Git blob changed: ' + file.name)
        text = leaf.decode('utf8')
        need(text.encode('utf8') == leaf and text.endswith('\n') and
             ('package ' + PACKAGE[file.name] + '\n') in text[:400], 'Small protocol fix text changed: ' + file.name)
        for token in DECLARATIONS[file.name]:
            need(text.count(token) == 1, 'Small protocol fix declaration changed: ' + token)
        sources[file.name] = text
        raws[file.name] = leaf
    license_file = root / 'LICENSE'
    need(not license_file.is_symlink() and sha(license_file.read_bytes()) == manifest['license']['sha256'],
         'Small protocol fix license changed')
    return sources, raws, manifest


def validate_outputs(repo, *outputs):
    fixed(repo)
    root = (Path(repo) / ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)),
             'Output overlaps original small protocol fix inputs')


def _target(root, name):
    base = Path(root).resolve()
    relative = RELATIVE[name]
    target = base / relative
    # A leaf symlink must stay unresolved; resolve() would follow it and write through it.
    if not target.is_symlink():
        target = target.resolve()
        need(target.is_relative_to(base), 'Small protocol fix target escapes the output root: ' + relative)
    return target, relative


def _require_regular_or_absent(target, relative):
    try:
        info = os.lstat(target)
    except FileNotFoundError:
        return
    need(stat.S_ISREG(info.st_mode), 'Small protocol fix target is not a regular file: ' + relative)


def _emit(repo, output, names):
    validate_outputs(repo, output)
    _sources, raws, _manifest = fixed(repo)
    targets = []
    for name in names:
        target, relative = _target(output, name)
        _require_regular_or_absent(target, relative)
        targets.append((target, relative, raws[name]))
    receipt = []
    for target, relative, leaf in targets:
        target.parent.mkdir(parents=True, exist_ok=True)
        _require_regular_or_absent(target, relative)
        target.write_bytes(leaf)
        written = target.read_bytes()
        need(written == leaf, 'Small protocol fix readback differs: ' + relative)
        receipt.append({'path': relative, 'sha256': sha(written)})
    return {'files': receipt}


def emit_message_history(repo, output):
    """Whole v0.4.6 MessageResponse.kt and the cursor serializer, verbatim."""
    return _emit(repo, output, MESSAGE_FILES)


def emit_article_parser(repo, output):
    """Whole v0.4.6 ArticleContentBlockParser.kt, verbatim."""
    return _emit(repo, output, ARTICLE_FILES)


def restore(value, proof):
    need(sha(value) == proof['afterSHA256'], 'Small protocol fix final output differs from its receipt')
    lines = value.splitlines(keepends=True)
    for hunk in reversed(proof['hunks']):
        first, last = hunk['afterLines']
        need(''.join(lines[first:last]) == hunk['after'], 'Small protocol fix inverse span changed')
        lines[first:last] = hunk['before'].splitlines(keepends=True)
    result = ''.join(lines)
    need(sha(result) == proof['beforeSHA256'], 'Small protocol fix predecessor inverse failed')
    return result


def _before(repo, relative, body):
    sources, _raws, manifest = fixed(repo)
    need(sha(body) == manifest['beforeFinalOutputs'][relative]['sha256'],
         'Small protocol fix predecessor changed: ' + relative)
    return sources, manifest


def _once(body, old, new, label):
    need(body.count(old) == 1, 'Small protocol fix anchor changed: ' + label)
    return body.replace(old, new, 1)


def _expression_function(source, start, end):
    """A complete expression-bodied function; span() fails closed on those by design."""
    need(source.count(start) == 1, 'Expression function changed: ' + start)
    first = source.index(start)
    need(source.count(end, first) >= 1, 'Expression function end changed: ' + start)
    last = source.index(end, first) + len(end)
    return source[first:last]


def _media(repo):
    path = Path(repo) / 'desktop/tools/extract-upstream-media.py'
    spec = importlib.util.spec_from_file_location('mfx_small_protocol_block_selector', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _receipt(**fields):
    return dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, jvmTestsExecuted=False, **fields)


def advance_sponsorblock_repository(repo, before):
    sources, _ = _before(repo, REPOSITORY_OUTPUT, before)
    syntax = parser(repo)
    original = sources['SponsorBlockRepository.kt']
    need('BuildConfig' not in before and 'buildSponsorBlockVoteBody' not in before and
         'buildSponsorBlockClientRequest' not in before, 'SponsorBlock repository already advanced')
    body = _once(before, JSON_IMPORT_ANCHOR, JSON_IMPORT_ANCHOR + JSON_IMPORTS_ADDED, 'json imports')
    # Client: the whole original function, with the Android BuildConfig version bound to the Windows package version.
    old_client = declaration(body, 'fun', 'buildSponsorBlockHttpClient', syntax)
    raw_client = declaration(original, 'fun', 'buildSponsorBlockHttpClient', syntax)
    client = _once(raw_client, 'BuildConfig.VERSION_NAME', VERSION_BINDING, 'client version binding')
    request = _expression_function(original, 'internal fun buildSponsorBlockClientRequest(', '\n        .build()')
    body = _once(body, old_client, client + '\n\n' + request, 'client function')
    # Vote body: a complete new original function after the unchanged segments URL builder.
    vote_body = declaration(original, 'fun', 'buildSponsorBlockVoteBody', syntax)
    first, last = span(body, 'fun', 'buildSponsorBlockSegmentsUrl', syntax)
    body = body[:last] + '\n\n' + vote_body + body[last:]
    # The stale hard-coded User-Agent is replaced by the client request header.
    body = _once(body, SPONSOR_STALE_AGENT, '', 'stale User-Agent')
    old_vote = declaration(body, 'fun', 'voteOnSegment', syntax)
    new_vote = declaration(original, 'fun', 'voteOnSegment', syntax)
    body = _once(body, old_vote, new_vote, 'voteOnSegment')
    need('BuildConfig' not in body and body.count('fun buildSponsorBlockVoteBody(') == 1 and
         body.count('fun buildSponsorBlockClientRequest(') == 1, 'SponsorBlock repository adaptation is incomplete')
    return body, _receipt(
        completeOriginalDeclarations=dict(
            buildSponsorBlockHttpClient=dict(originalSHA256=sha(raw_client), platformBinding=inverse_proof(raw_client, client)),
            buildSponsorBlockClientRequest=sha(request),
            buildSponsorBlockVoteBody=sha(vote_body),
            voteOnSegment=sha(new_vote)),
        retainedToCurrent=inverse_proof(before, body))


def advance_sponsorblock_plugin(repo, before):
    sources, _ = _before(repo, PLUGIN_OUTPUT, before)
    syntax = parser(repo)
    original = sources['SponsorBlockPlugin.kt']
    need(CONFIG_AFTER not in before, 'SponsorBlock plugin already advanced')
    body = _once(before, CONFIG_BEFORE, CONFIG_AFTER, 'config initializer')
    old_load = declaration(body, 'fun', 'loadConfigSuspend', syntax)
    new_load = declaration(original, 'fun', 'loadConfigSuspend', syntax)
    body = _once(body, old_load, new_load, 'loadConfigSuspend')
    need(body.count('PluginStore.setConfigJson(context, id, Json.encodeToString(config))') == 1,
         'SponsorBlock plugin adaptation is incomplete')
    return body, _receipt(
        completeOriginalDeclarations=dict(loadConfigSuspend=sha(new_load)),
        retainedToCurrent=inverse_proof(before, body))


def _patterns_declaration(logger, syntax):
    first, last = span(logger, 'object', 'LogRedactionPatterns', syntax)
    need(logger[:first].endswith(PATTERNS_DOC), 'Log redaction pattern documentation changed')
    return PATTERNS_DOC + logger[first:last]


def advance_log_collector(repo, before):
    sources, _ = _before(repo, COLLECTOR_OUTPUT, before)
    syntax = parser(repo)
    logger = sources['Logger.kt']
    need('LogRedactionPatterns' not in before, 'Log collector already advanced')
    collector = logger[logger.index('object LogCollector {'):]
    sanitizer = textwrap.indent(_media(repo).function(collector, 'sanitizeMessage', syntax), '        ')
    first, last = span(before, 'fun', 'sanitizeMessage', syntax)
    patterns = _patterns_declaration(logger, syntax)
    body = before[:first] + sanitizer + before[last:]
    body = body.rstrip('\n') + '\n\n' + patterns + '\n'
    # No pattern is compiled per call any more: the 58 shared patterns are referenced by name.
    need(body.count('private object LogRedactionPatterns {') == 1 and sanitizer.count('Regex(') == 0 and
         sanitizer.count('LogRedactionPatterns.') == patterns.count('Regex(') == before[first:last].count('Regex('),
         'Log collector adaptation is incomplete')
    return body, _receipt(
        completeOriginalDeclarations=dict(sanitizeMessage=sha(sanitizer), LogRedactionPatterns=sha(patterns)),
        retainedToCurrent=inverse_proof(before, body))


def write_proof(output, name, proof):
    target = Path(output) / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(proof, ensure_ascii=False, indent=2) + '\n', encoding='utf8', newline='\n')
    return target
