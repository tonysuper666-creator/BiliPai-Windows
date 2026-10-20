"""v0.4.6 Bangumi hub pages: single UiState, shorter follow preload, index filter motion, channel bar, card transitions.

Roadmap slice bgm-hub-pages (#28), SOURCE only; nothing here was compiled or run on a JVM.

Covers the two entries v036-bangumi-follow-preload-single-state (3b37b7a2) and v040-bangumi-index-filter-and-channel-bar
(cf1fc70c, c42851ae, d17c3408, 557de49c). The follow-session policy half of 3b37b7a2 and the player view model are carried
by slice bgm-player-viewmodel-and-features (v046_bangumi_player.py); the account-scoped hub view model (ba4384bad) is the
account slice's and is not carried here.

What is carried, all from the v046-bangumi-hub-pages archive (verbatim v0.4.6 bytes, d6bf8b16):
- BangumiViewModel.kt (the combined detail/catalog view model, now one BangumiUiState with the old properties derived
  from it), BangumiScreen.kt, BangumiHubContent.kt, HomeBangumiTabPage.kt, BangumiHubPolicy.kt (year range label) and
  BangumiCardTransition.kt are carried WHOLE. BangumiScreen.kt and BangumiCardTransition.kt are the files that roadmap
  slice srch-container-transitions shares: they are pinned whole once here.
- The two independent producers that already own these outputs (extract-upstream-home-bangumi-page.py and
  extract-upstream-bangumi-pages.py) still run their v0.2.5 recipes unchanged; the layers below replace each output after
  asserting the exact predecessor hash (manifest beforeFinalOutputs) and record BOTH inverse proofs: original to Windows
  and predecessor to current.
- BangumiRepository.kt is carried as the whole v0.4.6 blob only to prove the page-size delta: the repository bodies the two
  producers select are the v0.2.5 text plus exactly two counted hunks (getBangumiIndex 20 to 30, getBangumiIndexPage 21 to
  30); repository_delta asserts that the pinned canonical file plus those hunks IS the v0.4.6 blob.
- Smaller counted hunks on retained bodies: BangumiDetailScreen reads detailState from the single uiState (3b37b7a2) and
  BangumiHubUiState gains accountGeneration (its Windows value is the constant 0 because every Windows personal page owner
  is rebuilt per Root session epoch, which the account-scoped-requests audit established).
- BangumiHubPolicyTest.kt (the original suite, JUnit4 imports rewritten to kotlin.test) is emitted next to the existing
  independent-pages tests.

Re-runs: both producers overwrite every output they own, so a second run into the same output directory regenerates the
predecessor stage first and this layer then verifies its hash again (fail closed, nothing is written when any predecessor or
anchor drifted). The one file that is new in this layer, DesktopOriginalBangumiCardTransition.kt, is written
unconditionally like the other slices' new emissions, so the rebuild of an unchanged or persistent output directory is
byte-identical.

Stale receipts: the v0.2.5 recipes of the two producers write a v037-explicit-tab-role-proofs receipt for each of
BangumiHubContent.kt, HomeBangumiTabPage.kt and DesktopOriginalBangumiScreen.kt. The v0.4.6 texts declare every tab role
themselves, so the explicit role projection is a no-op on them and each of those receipts describes an intermediate text that
no longer exists: this layer deletes the three receipts (and the then empty receipt directory) after it writes the whole
files, the same as the episode selector slice did for its sheet. The remaining proofs of these files are this layer's own
v046-bangumi-hub-pages-proofs (original to Windows, predecessor to current).

Not carried from the covered commits (follow-up slices, none of it is needed for the pages to work on Windows):
- The two Bangumi structure tests added by 3b37b7a2, BangumiViewModelSingleStateStructureTest and
  BangumiScreenHubViewModelStructureTest, read Android source paths and cannot run here; the Python source tests mirror their
  assertions instead (one backing state, derived legacy properties, the hub view model passed in).
- The CategoryScreen.kt single-state change of the same commit and its structure test
  CategoryUiStateSingleSourceStructureTest: the Windows category page (DesktopCategoryRouteHost, feature category-page)
  stays at its v0.2.5 text until a category single-state slice carries it.
- The hub view model change of ba4384bad beyond accountGeneration: it loads follow and timeline data when the hub becomes
  visible and stale. That belongs to an account-scoped hub view model slice (the Windows owner is rebuilt per Root epoch, so
  only the constant accountGeneration field is carried).
- The BangumiDetailScreen v0.4.6 changes of the container transition commits c42851ae and 4e792adaf: no Windows host passes
  onPlayEpisode, so the detail side of the container transition stays off; it belongs to the container transition slice
  under roadmap D1 / #44.

Windows reality: the card container transition (BangumiCardTransitionHost with an onPlayEpisode callback) is phone-style
motion that the roadmap decisions defer (D1); no Windows host passes onPlayEpisode, so the host calls its content directly
and the carried transition state stays off. The destination lambda still has to compile, so its detail call is bound to the
desktop-owned DesktopOriginalBangumiCardDetailScreen, which builds the pages view model from the page environment.
"""
from pathlib import Path
import hashlib
import json
import os

from v044_favorite_detail import inverse_proof
from v025_source_paths import canonical_source

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-bangumi-hub-pages'
MANIFEST_SHA256 = 'd7c5cbcdc3e3e0d58055958535b5670945ad53304dbf0ff500f2b9682afee369'
APP = 'app/src/main/java/com/android/purebilibili/'
LEAVES = {
    'BangumiViewModel.kt': APP + 'feature/bangumi/BangumiViewModel.kt',
    'BangumiRepository.kt': APP + 'data/repository/BangumiRepository.kt',
    'BangumiHubPolicy.kt': APP + 'feature/bangumi/policy/BangumiHubPolicy.kt',
    'BangumiHubContent.kt': APP + 'feature/bangumi/BangumiHubContent.kt',
    'BangumiScreen.kt': APP + 'feature/bangumi/BangumiScreen.kt',
    'HomeBangumiTabPage.kt': APP + 'feature/bangumi/HomeBangumiTabPage.kt',
    'BangumiCardTransition.kt': APP + 'feature/bangumi/BangumiCardTransition.kt',
    'BangumiHubPolicyTest.kt': 'app/src/test/java/com/android/purebilibili/feature/bangumi/BangumiHubPolicyTest.kt',
}
EXPECTED = set(LEAVES)
PROOF_DIRECTORY = 'v046-bangumi-hub-pages-proofs'
TAB_ROLE_PROOF_DIRECTORY = 'v037-explicit-tab-role-proofs'

BANGUMI = 'com/android/purebilibili/feature/bangumi/'
REPOSITORY = 'com/android/purebilibili/data/repository/'
OUT_HUB_CONTENT = BANGUMI + 'BangumiHubContent.kt'
OUT_HUB_POLICY = BANGUMI + 'BangumiHubPolicy.kt'
OUT_TAB_PAGE = BANGUMI + 'HomeBangumiTabPage.kt'
OUT_HUB_VM = BANGUMI + 'BangumiHubViewModel.kt'
OUT_HUB_REPO = REPOSITORY + 'DesktopOriginalBangumiHubRepository.kt'
OUT_TRANSITION = BANGUMI + 'DesktopOriginalBangumiCardTransition.kt'
OUT_SCREEN = BANGUMI + 'DesktopOriginalBangumiScreen.kt'
OUT_VM = BANGUMI + 'DesktopOriginalBangumiViewModel.kt'
OUT_DETAIL = BANGUMI + 'DesktopOriginalBangumiDetailScreen.kt'
OUT_PAGES_REPO = REPOSITORY + 'DesktopOriginalBangumiPagesRequests.kt'
TEST_OUTPUT = BANGUMI + 'BangumiHubPolicyTest.kt'
TEST_COUNT = 12
CANONICAL_REPOSITORY_SHA256 = '7a7f195be5ba108aaa3e9b05d7069e9c981183fa7a79fae751b8f0da25714c73'
REPOSITORY_PATH = LEAVES['BangumiRepository.kt']
DETAIL_COMMIT = '3b37b7a20187c03398a71ebcdfd7c2e1fea284e5'

# (label, before, after, count): each pair is applied to the text it names and must match exactly `count` times.
TAB_PAGE_ADAPTATIONS = [
    ('the Home environment is the Windows LocalContext',
     'import androidx.compose.ui.platform.LocalContext\n',
     'import com.bilipai.desktop.ui.LocalDesktopHomeEnvironment as LocalContext\n', 1),
    ('the retained local supplies the hub view model',
     'import androidx.lifecycle.viewmodel.compose.viewModel\n',
     '// Windows: required retained local supplies the original Hub VM\n', 1),
    ('the persisted Home settings projection replaces SettingsManager',
     'import com.android.purebilibili.core.store.SettingsManager\n',
     '// Windows: use the same original persisted Home settings projection\n', 1),
    ('the Root owned image save replaces DownloadManager',
     'import com.android.purebilibili.feature.download.DownloadManager\n',
     '// Windows: existing Root owned image-save consumer\n', 1),
    ('the page is internal like its view model', 'fun HomeBangumiTabPage(', 'internal fun HomeBangumiTabPage(', 1),
    ('the hub view model defaults to the retained local',
     'viewModel: BangumiHubViewModel = viewModel(),',
     'viewModel: BangumiHubViewModel = com.bilipai.desktop.ui.LocalDesktopBangumiHubViewModel.current,', 1),
    ('the timeline switch comes from the Home settings projection',
     'SettingsManager.getShowPgcTimeline(context)', 'context.settings.homeSettings.map { it.showPgcTimeline }', 1),
    ('the cover save goes through the Root consumer',
     'DownloadManager.saveImageToGallery(context, url, title)', 'context.saveCover(url, title)', 1),
    ('the projection needs the flow map import',
     'import kotlinx.coroutines.launch\n', 'import kotlinx.coroutines.launch\nimport kotlinx.coroutines.flow.map\n', 1),
]
SCREEN_ADAPTATIONS = [
    ('Build is replaced by the page environment',
     'import android.os.Build\n',
     'import com.bilipai.desktop.ui.LocalDesktopOriginalBangumiPagesEnvironment\n', 1),
    ('the haze capability comes from the page environment',
     'import com.android.purebilibili.core.ui.blur.shouldAllowRenderEffectBackedHazeEffect\n',
     '// Capability comes from the actual Desktop page environment.\n', 1),
    ('the Home environment is the Windows LocalContext',
     'import androidx.compose.ui.platform.LocalContext\n',
     'import com.bilipai.desktop.ui.LocalDesktopHomeEnvironment as LocalContext\n', 1),
    ('the settings projection replaces SettingsManager',
     'import com.android.purebilibili.core.store.SettingsManager\n', 'import kotlinx.coroutines.flow.map\n', 1),
    ('the cover save boundary is Root owned',
     'import com.android.purebilibili.feature.download.DownloadManager\n',
     '// Root owns the existing cover download/save boundary.\n', 1),
    ('the hub view model is required', 'viewModel: BangumiHubViewModel = viewModel()', 'viewModel: BangumiHubViewModel', 1),
    ('both entry points are internal like their view models', 'fun BangumiScreen(', 'internal fun BangumiScreen(', 2),
    ('the timeline switch comes from the Home settings projection',
     'SettingsManager.getShowPgcTimeline(context)', 'context.settings.homeSettings.map { it.showPgcTimeline }', 1),
    ('the haze capability is read from the page environment',
     'shouldAllowRenderEffectBackedHazeEffect(Build.VERSION.SDK_INT)',
     'LocalDesktopOriginalBangumiPagesEnvironment.current.hazeEffectSupported', 1),
    ('the cover save goes through the Root consumer',
     'DownloadManager.saveImageToGallery(context, url, title)', 'context.saveCover(url, title)', 1),
    ('the source compatible bridge hands the page environment hub to the entry point',
     '        initialType = initialType,\n    )',
     '        initialType = initialType,\n        viewModel = LocalDesktopOriginalBangumiPagesEnvironment.current.hub,\n    )', 1),
]
VM_ADAPTATIONS = [
    ('the page environment owns the coroutine scope',
     'import androidx.lifecycle.viewModelScope\n',
     'import com.bilipai.desktop.ui.DesktopOriginalBangumiPagesEnvironment\n'
     'import com.bilipai.desktop.ui.DesktopHomeOwnedMutableStateFlow\n', 1),
    ('no repository singleton',
     'import com.android.purebilibili.data.repository.BangumiRepository\n',
     '// Existing Root services are required, never a new network singleton.\n', 1),
    ('the view model is built from the page environment',
     'class BangumiViewModel : ViewModel() {\n',
     'internal class BangumiViewModel(private val desktopEnvironment: DesktopOriginalBangumiPagesEnvironment) : ViewModel() {\n'
     '    private val viewModelScope get() = desktopEnvironment.scope\n', 1),
    ('every repository call goes through the owner request view',
     'BangumiRepository.', 'desktopEnvironment.requests.', 15),
    ('the follow cache session is the Root account identity',
     'currentBangumiFollowSession()', 'desktopEnvironment.followSession()', 3),
    ('the background verification passes its session and request view',
     '                targetSeasonId = seasonId\n            )\n',
     '                targetSeasonId = seasonId,\n'
     '                sessionProvider = desktopEnvironment.followSession,\n'
     '                fetchPage = { requestedType, page, pageSize ->\n'
     '                    desktopEnvironment.requests.getMyFollowBangumi(type = requestedType, page = page, pageSize = pageSize)\n'
     '                }\n            )\n', 1),
    ('state is committed only while the entry is current',
     'MutableStateFlow(BangumiUiState())',
     'DesktopHomeOwnedMutableStateFlow(BangumiUiState(), desktopEnvironment.commitIfCurrent)', 1),
]
TRANSITION_ADAPTATIONS = [
    ('the destination binds the pages environment view model',
     '            BangumiDetailScreen(\n',
     '            com.bilipai.desktop.ui.DesktopOriginalBangumiCardDetailScreen(\n', 1),
]
JUNIT_IMPORTS = [
    ('import org.junit.Assert.assertEquals\n', 'import kotlin.test.assertEquals\n'),
    ('import org.junit.Assert.assertTrue\n', 'import kotlin.test.assertTrue\n'),
    ('import org.junit.Test\n', 'import kotlin.test.Test\n'),
]
# Counted hunks on retained bodies: (label, before, after).
DETAIL_HUNKS = [
    ('the detail screen collects the single uiState and reads its detail state (3b37b7a2)',
     '    val detailState by viewModel.detailState.collectAsStateWithLifecycle()\n',
     '    val uiState by viewModel.uiState.collectAsStateWithLifecycle()\n    val detailState = uiState.detailState\n'),
]
DETAIL_UPSTREAM_LINES = ['val uiState by viewModel.uiState.collectAsStateWithLifecycle()', 'val detailState = uiState.detailState']
HUB_VM_HUNKS = [
    ('the hub state carries the account generation; Windows rebuilds the owner per Root session epoch, so it stays 0',
     '    val isLoggedIn: Boolean = false,\n    val channel: BangumiChannel = BangumiChannel.BANGUMI,\n',
     '    val isLoggedIn: Boolean = false,\n'
     '    // Windows rebuilds every personal page owner per Root session epoch: the generation never advances in place.\n'
     '    val accountGeneration: Long = 0L,\n'
     '    val channel: BangumiChannel = BangumiChannel.BANGUMI,\n'),
]
# The v0.4.6 repository delta, on the upstream text (pinned canonical file plus these pairs is the archived blob) and on
# the two generated selections of the same methods.
REPOSITORY_DELTA = [
    ('getBangumiIndex default page size',
     '        page: Int = 1,\n        pageSize: Int = 20\n    ): Result<BangumiIndexData> = withContext(Dispatchers.IO) {\n'
     '        try {\n            val requestFilter = buildBangumiIndexRequestFilter(',
     '        page: Int = 1,\n        pageSize: Int = 30\n    ): Result<BangumiIndexData> = withContext(Dispatchers.IO) {\n'
     '        try {\n            val requestFilter = buildBangumiIndexRequestFilter('),
    ('getBangumiIndexPage default page size',
     '        pageSize: Int = 21,\n        params: Map<String, String> = emptyMap(),\n    ): Result<BangumiIndexData> = withContext(Dispatchers.IO) {',
     '        pageSize: Int = 30,\n        params: Map<String, String> = emptyMap(),\n    ): Result<BangumiIndexData> = withContext(Dispatchers.IO) {'),
]
PAGES_REPO_HUNKS = [
    ('the selected getBangumiIndex default page size is 30',
     '    pageSize: Int = 20\n): Result<BangumiIndexData> = ownedBangumiRequest(Dispatchers.IO, owned) {\n'
     '    try {\n        val requestFilter = buildBangumiIndexRequestFilter(',
     '    pageSize: Int = 30\n): Result<BangumiIndexData> = ownedBangumiRequest(Dispatchers.IO, owned) {\n'
     '    try {\n        val requestFilter = buildBangumiIndexRequestFilter('),
]
HUB_REPO_HUNKS = [
    ('the selected getBangumiIndexPage default page size is 30',
     '        pageSize: Int = 21,\n        params: Map<String, String> = emptyMap(),\n',
     '        pageSize: Int = 30,\n        params: Map<String, String> = emptyMap(),\n'),
]

# (output, leaf, adaptations, header). A header is written by the pages producer family only.
HOME_WHOLE = [
    (OUT_HUB_CONTENT, 'BangumiHubContent.kt', [], False),
    (OUT_HUB_POLICY, 'BangumiHubPolicy.kt', [], False),
    (OUT_TAB_PAGE, 'HomeBangumiTabPage.kt', TAB_PAGE_ADAPTATIONS, False),
]
HOME_HUNKS = [(OUT_HUB_VM, HUB_VM_HUNKS), (OUT_HUB_REPO, HUB_REPO_HUNKS)]
PAGES_WHOLE = [
    (OUT_SCREEN, 'BangumiScreen.kt', SCREEN_ADAPTATIONS, True),
    (OUT_VM, 'BangumiViewModel.kt', VM_ADAPTATIONS, True),
]
PAGES_HUNKS = [(OUT_DETAIL, DETAIL_HUNKS), (OUT_PAGES_REPO, PAGES_REPO_HUNKS)]
TRANSITION = (OUT_TRANSITION, 'BangumiCardTransition.kt', TRANSITION_ADAPTATIONS, True)

_WIDE_PREFIX = chr(92) * 2 + '?' + chr(92)


def wide(path):
    """Extended-length form on Windows: the generated tree sits deep below the checkout (MAX_PATH)."""
    path = Path(path).resolve()
    return Path(_WIDE_PREFIX + str(path)) if os.name == 'nt' and not str(path).startswith(_WIDE_PREFIX) else path


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def counted(text, old, new, count, label):
    need(text.count(old) == count, 'Bangumi hub anchor changed: ' + label)
    return text.replace(old, new)


def adapt(text, adaptations):
    for label, old, new, count in adaptations:
        text = counted(text, old, new, count, label)
    return text


def hunks_applied(text, hunks):
    for label, old, new in hunks:
        text = counted(text, old, new, 1, label)
    return text


def fixed(repo):
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing Bangumi hub page originals')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'}, 'Bangumi hub page input leaves changed')
    raw = (root / 'manifest.json').read_bytes()
    need(sha(raw) == MANIFEST_SHA256, 'Bangumi hub page manifest changed')
    manifest = json.loads(raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'Bangumi hub page source identity changed')
    need(len(manifest['files']) == len(EXPECTED) and {r['filename'] for r in manifest['files']} == EXPECTED and
         {r['filename']: r['path'] for r in manifest['files']} == LEAVES, 'Bangumi hub page source set changed')
    sources = {}
    for row in manifest['files']:
        file = root / row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous Bangumi hub page source')
        raw = file.read_bytes()
        need(len(raw) == row['bytes'] and sha(raw) == row['sha256'], 'Bangumi hub page bytes changed: ' + file.name)
        need(hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest() == row['gitBlob'],
             'Bangumi hub page Git blob changed: ' + file.name)
        need(b'\r' not in raw, 'Bangumi hub page source must be LF only: ' + file.name)
        sources[file.name] = raw.decode('utf8')
    licence = root / 'LICENSE'
    need(not licence.is_symlink() and sha(licence.read_bytes()) == manifest['license']['sha256'],
         'Bangumi hub page license changed')
    return sources, manifest


def validate_outputs(repo, *outputs):
    fixed(repo)
    root = (Path(repo) / ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)), 'Output overlaps Bangumi hub page originals')


def restore(value, proof):
    need(sha(value) == proof['afterSHA256'], 'Bangumi hub page final output differs from its receipt')
    lines = value.splitlines(keepends=True)
    for hunk in reversed(proof['hunks']):
        first, last = hunk['afterLines']
        need(''.join(lines[first:last]) == hunk['after'], 'Bangumi hub page inverse span changed')
        lines[first:last] = hunk['before'].splitlines(keepends=True)
    result = ''.join(lines)
    need(sha(result) == proof['beforeSHA256'], 'Bangumi hub page predecessor inverse failed')
    return result


def repository_delta(repo):
    """The pinned canonical v0.2.5 BangumiRepository plus the two counted hunks IS the archived v0.4.6 blob."""
    sources, _ = fixed(repo)
    canonical = canonical_source(repo, REPOSITORY_PATH).read_bytes().decode('utf8').replace('\r\n', '\n')
    need(sha(canonical) == CANONICAL_REPOSITORY_SHA256, 'The pinned canonical BangumiRepository changed')
    text = hunks_applied(canonical, REPOSITORY_DELTA)
    need(text == sources['BangumiRepository.kt'], 'The v0.4.6 BangumiRepository is more than the two page size hunks')
    return canonical, text


def header_for(manifest, leaf):
    row = next(r for r in manifest['files'] if r['filename'] == leaf)
    return '// GENERATED from ' + row['path'] + '; v0.4.6 ' + COMMIT[:8] + '; raw SHA-256 ' + row['sha256'] + '\n'


def whole_text(manifest, sources, leaf, adaptations, header):
    """The carried whole file as its output: the upstream text plus its counted Windows adaptations."""
    adapted = adapt(sources[leaf], adaptations)
    return adapted, (header_for(manifest, leaf) if header else '') + adapted


def read_text(path):
    return Path(path).read_bytes().decode('utf8').replace('\r\n', '\n')


def _save(out, output, data):
    target = wide(Path(out) / PROOF_DIRECTORY / (Path(output).stem + '.json'))
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf8', newline='\n')


def _write(out, output, text):
    target = wide(Path(out) / output)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding='utf8', newline='\n')


def _predecessor(out, manifest, output):
    target = wide(Path(out) / output)
    need(target.is_file(), 'Bangumi hub page predecessor output is missing: ' + output)
    before = read_text(target)
    need(sha(before) == manifest['beforeFinalOutputs'][output]['sha256'], 'Bangumi hub page predecessor changed: ' + output)
    return before


def _plan_whole(out, manifest, sources, spec):
    output, leaf, adaptations, header = spec
    before = _predecessor(out, manifest, output)
    adapted, after = whole_text(manifest, sources, leaf, adaptations, header)
    need(sha(after) == manifest['afterFinalOutputs'][output]['sha256'], 'Bangumi hub page output changed: ' + output)
    proof = dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, output=output, leaf=leaf,
        adaptationCount=len(adaptations), originalToWindows=inverse_proof(sources[leaf], adapted),
        retainedToCurrent=inverse_proof(before, after))
    return dict(output=output, text=after, proof=proof, drop_tab_role_receipt=True,
        row=dict(output=output, leaf=leaf, adaptations=len(adaptations), sha256=sha(after)))


def _plan_hunks(out, manifest, output, hunks):
    before = _predecessor(out, manifest, output)
    after = hunks_applied(before, hunks)
    need(sha(after) == manifest['afterFinalOutputs'][output]['sha256'], 'Bangumi hub page output changed: ' + output)
    proof = dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, output=output,
        hunkLabels=[label for label, _, _ in hunks], retainedToCurrent=inverse_proof(before, after))
    return dict(output=output, text=after, proof=proof, row=dict(output=output, hunks=len(hunks), sha256=sha(after)))


def _plan_new(out, manifest, sources, spec):
    output, leaf, adaptations, header = spec
    # Written unconditionally: a re-run regenerates it like every other output (see "Re-runs" in the module docstring).
    adapted, text = whole_text(manifest, sources, leaf, adaptations, header)
    need(sha(text) == manifest['afterFinalOutputs'][output]['sha256'], 'Bangumi hub page output changed: ' + output)
    proof = dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, output=output, leaf=leaf,
        adaptationCount=len(adaptations), originalToWindows=inverse_proof(sources[leaf], adapted))
    return dict(output=output, text=text, proof=proof, row=dict(output=output, leaf=leaf, adaptations=len(adaptations),
        sha256=sha(text)))


def _drop_tab_role_receipt(out, output):
    """Delete the v0.2.5 tab role receipt of an output this layer replaced whole (it describes an intermediate text)."""
    target = wide(Path(out) / TAB_ROLE_PROOF_DIRECTORY / (Path(output).name + '.json'))
    existed = target.is_file()
    if existed:
        target.unlink()
    try:
        target.parent.rmdir()
    except OSError:
        pass  # missing, or still holding the receipt of another producer
    return existed


def _commit(out, plans):
    """Every predecessor and anchor is verified while planning: nothing is written when any of them drifted."""
    for plan in plans:
        _write(out, plan['output'], plan['text'])
        _save(out, plan['output'], plan['proof'])
        if plan.get('drop_tab_role_receipt'):
            plan['row']['staleTabRoleReceiptRemoved'] = _drop_tab_role_receipt(out, plan['output'])
    return [plan['row'] for plan in plans]


def advance_home(repo, out):
    """The last layer of the Home Bangumi hub producer (its v0.2.5 recipe is unchanged)."""
    validate_outputs(repo, out)
    sources, manifest = fixed(repo)
    repository_delta(repo)
    plans = [_plan_whole(out, manifest, sources, spec) for spec in HOME_WHOLE]
    plans += [_plan_hunks(out, manifest, output, hunks) for output, hunks in HOME_HUNKS]
    plans.append(_plan_new(out, manifest, sources, TRANSITION))
    return _commit(out, plans)


def advance_pages(repo, out):
    """The last layer of the independent Bangumi pages producer (its v0.2.5 recipe is unchanged)."""
    validate_outputs(repo, out)
    sources, manifest = fixed(repo)
    repository_delta(repo)
    plans = [_plan_whole(out, manifest, sources, spec) for spec in PAGES_WHOLE]
    plans += [_plan_hunks(out, manifest, output, hunks) for output, hunks in PAGES_HUNKS]
    return _commit(out, plans)


def emit_hub_policy_test(repo, test_root):
    sources, _ = fixed(repo)
    validate_outputs(repo, test_root)
    original = sources['BangumiHubPolicyTest.kt']
    need(original.count('@Test') == TEST_COUNT, 'Original Bangumi hub policy test count changed')
    body = original
    for old, new in JUNIT_IMPORTS:
        body = counted(body, old, new, 1, old.strip())
    need('org.junit' not in body, 'A JUnit4 reference survived the kotlin.test rewrite')
    _write(test_root, TEST_OUTPUT, body)
    _save(test_root, TEST_OUTPUT, dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, output=TEST_OUTPUT,
        originalToWindows=inverse_proof(original, body)))
    return dict(output=TEST_OUTPUT, sha256=sha(body))
