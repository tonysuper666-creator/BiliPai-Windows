"""v0.4.6 search state: results survive edits and Back, a search-only silent switch, the larger landing text.

Hash-pinned layer on the bodies that the sole complete-Search producer (extract-upstream-search-pages.py)
already builds from the v0.4.1/v0.4.2 pins:

* SearchViewModel.kt, SearchScreen.kt and SearchLandingUi.kt are replaced by their whole v0.4.6 files. The same
  reviewed platform recipe (the producer's SPEC rows, nothing new) is replayed over the v0.4.6 text, so the
  Windows output is the complete original plus exactly the owner/platform substitutions the earlier pins
  already carried. SearchLandingUi takes the v0.3.5 hierarchy/spacing, the v0.4.0 larger text and the history
  switch in one step.
* SearchVideoFilterSheet.kt is archived verbatim (the manifest pins it) but WITHHELD: the Windows body stays the
  v0.4.1 sheet. The whole v0.4.6 sheet is not part of the roadmap commit cd86c9f13; it differs from the v0.4.1
  sheet only through commit 35e2fac8a ("refine native theme colors and shared paging interactions"), which
  (a) removes the single/multi-column toggle (the VideoListLayoutToggle import, the singleColumn, onLayoutToggle
  and showLayoutToggle parameters and the dock that renders them) and (b) recolours the selected filter chips
  (primaryContainer container, AppContentColors.accent label). The v0.4.6 screen already stops passing the two
  toggle arguments, so retain_layout_toggle puts exactly those two lines back (one counted, hash-pinned hunk,
  in the order the v0.4.2 screen passed them): Windows keeps its toggle and the chip colours stay as they were.
  advance_page for the sheet asserts the predecessor, the toggle signature and the archived file, returns the
  body unchanged and records the withheld delta as an inverse proof. Adopting the v0.4.6 sheet is a separate,
  user-visible decision: a follow-up slice must also drop retain_layout_toggle.
* SearchContentStatePolicy.kt (shouldRecordSearchHistory: a search-only preference never overrides the global
  privacy switch) and SearchMotionBudgetPolicy.kt (Back from results returns to the landing, a draft edit
  never changes the active surface) are emitted whole. The first is a canonical direct file that gains one
  exclude line; the second was already emitted by the v0.4.2 dependency pin and is advanced here.
* The two upstream test classes that exercise those policies are emitted as the v0.4.6 originals. The v0.4.2
  SearchMotionBudgetPolicyTest this layer replaces pins the removed shouldKeepResultsOnQueryChange.

Deliberately NOT carried:

* SettingsManager.kt: Windows never compiles the Android search or privacy declarations from it. The search
  preferences are reached through DesktopOriginalSearchSettings, which is desktop-owned, and the share catalog
  is desktop-owned too (DesktopSettingsShareWindowsCatalog).
* AppSearchEntry (roadmap entry v026-search-entry-centered-transition, centeredContent): the design-system
  leaves-b manifest records that no carried or desktop-owned file calls it (Windows keeps the hash-pinned
  v0.2.5 AdaptivePreferenceComponents adapter and desktop/src has no caller), so no Windows surface would
  render the layout.
* AppLiquidAwareSearchField.kt (the cd86c9f13 hunk: glass-only compact geometry via LiquidSearchFieldHeightDp
  and no in-field clear for TOP_BAR): deferred. It is a canonical direct-mode file that the roadmap shares with
  chrome-tab-rails-and-geometry, so adopting it is a whole direct-file replacement with one
  exclude(canonicalOriginalIdentity(...)) line (R-T2), best done together with that geometry slice.

Every layer asserts the exact predecessor body, applies counted recipe edits only, and records inverse proofs
(original to Windows and predecessor to current) so older tests can peel it off again.
"""
from pathlib import Path
import hashlib
import json

from v041_search_all import apply_recipe
from v044_favorite_detail import inverse_proof
from v044_favorite_subscription import need

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-search-state'
MANIFEST_SHA256 = '07dcdcfb779029d2a2238f995395078373cc4784c6520e321ea35d1df93a18f8'
APP = 'app/src/main/java/com/android/purebilibili/feature/search/'
TEST = 'app/src/test/java/com/android/purebilibili/feature/search/'
VIEW_MODEL = APP + 'SearchViewModel.kt'
SCREEN = APP + 'SearchScreen.kt'
LANDING = APP + 'SearchLandingUi.kt'
FILTER_SHEET = APP + 'SearchVideoFilterSheet.kt'
CONTENT_POLICY = APP + 'SearchContentStatePolicy.kt'
MOTION_POLICY = APP + 'SearchMotionBudgetPolicy.kt'
CONTENT_TEST = TEST + 'SearchContentStatePolicyTest.kt'
MOTION_TEST = TEST + 'SearchMotionBudgetPolicyTest.kt'
PAGES = (VIEW_MODEL, SCREEN, LANDING, FILTER_SHEET)
# PAGES all pass through advance_page; the filter sheet is archived but its v0.4.1 body is retained (see above).
WITHHELD_PAGES = (FILTER_SHEET,)
PATHS = PAGES + (CONTENT_POLICY, MOTION_POLICY, CONTENT_TEST, MOTION_TEST)
EXPECTED = {Path(path).name for path in PATHS}
PROOF_NAME = 'v046-search-state-proof.json'
MAIN_RELATIVE = 'com/android/purebilibili/feature/search/'

# SearchScreen: the v0.4.2 layer dropped this duplicated import from the recipe; v0.4.6 never had it.
OBSOLETE_IMPORT = ('import com.android.purebilibili.core.ui.isMiuixNonGlassEnabled\n'
                   'import com.android.purebilibili.core.ui.isMiuixNonGlassEnabled')
CLOCK = 'android.os.SystemClock.elapsedRealtime()'
CLOCK_REPLACEMENT = '(System.nanoTime() / 1_000_000L)'

# The retained layout toggle: the v0.4.2 screen passed these two arguments to SearchVideoFilterActions (showLayoutToggle
# kept its default), the v0.4.6 screen does not. The v0.4.1 sheet that Windows keeps still declares all three parameters.
FILTER_CALL_INDENT = ' ' * 60
LAYOUT_TOGGLE_ANCHOR = 'SearchVideoFilterActions(\n' + FILTER_CALL_INDENT + 'currentDurations = state.searchDurations,\n'
LAYOUT_TOGGLE_ARGUMENTS = (FILTER_CALL_INDENT + 'singleColumn = listLayout.singleColumn,\n' +
                           FILTER_CALL_INDENT + 'onLayoutToggle = listLayout.toggle,\n')
SHEET_TOGGLE_PARAMETERS = '    singleColumn: Boolean,\n    onLayoutToggle: () -> Unit,\n    showLayoutToggle: Boolean = true,\n'
# The manifest pins the plain whole-v0.4.6 replay of the screen (afterFinalOutputs); this pins the replay plus the hunk above.
SCREEN_WITH_TOGGLE_SHA256 = '75e3031dd1757f1b1b79f8aa4c04a4473f8952b6fddd54e658612b7af89cfb33'


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def fixed(repo):
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing v0.4.6 search state inputs')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'}, 'Search state input leaves changed')
    raw = (root / 'manifest.json').read_bytes()
    need(sha(raw) == MANIFEST_SHA256, 'Search state manifest changed')
    manifest = json.loads(raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'Search state source identity changed')
    need(tuple(row['path'] for row in manifest['files']) == PATHS, 'Search state source set changed')
    sources = {}
    for row in manifest['files']:
        file = root / row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous search state source')
        raw = file.read_bytes()
        need(len(raw) == row['bytes'] and sha(raw) == row['sha256'], 'Search state bytes changed: ' + file.name)
        need(hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest() == row['gitBlob'],
             'Search state Git blob changed: ' + file.name)
        need(b'\r' not in raw, 'Search state source has a carriage return: ' + file.name)
        sources[row['path']] = raw.decode('utf8')
    licence = root / 'LICENSE'
    need(not licence.is_symlink() and sha(licence.read_bytes()) == manifest['license']['sha256'],
         'Search state license changed')
    return sources, manifest


def validate_outputs(repo, *outputs):
    fixed(repo)
    root = (Path(repo) / ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)), 'Output overlaps v0.4.6 search state inputs')


def restore(value, proof):
    """Peel one recorded inverse proof (a predecessor-to-current or original-to-Windows receipt)."""
    need(sha(value) == proof['afterSHA256'], 'Search state final output differs from its receipt')
    lines = value.splitlines(keepends=True)
    for hunk in reversed(proof['hunks']):
        first, last = hunk['afterLines']
        need(''.join(lines[first:last]) == hunk['after'], 'Search state inverse span changed')
        lines[first:last] = hunk['before'].splitlines(keepends=True)
    result = ''.join(lines)
    need(sha(result) == proof['beforeSHA256'], 'Search state predecessor inverse failed')
    return result


def pins(manifest, key, path):
    need(path in manifest[key], 'Search state manifest pin missing: ' + key + ' ' + path)
    return manifest[key][path]['sha256']


def adapt(repo, item, original):
    """The reviewed platform recipe of the producer SPEC row, replayed over the whole v0.4.6 file."""
    path = item['path']
    need(path in PAGES, 'Unknown search state page: ' + path)
    need(path not in WITHHELD_PAGES, 'The v0.4.6 search filter sheet is archived but withheld: ' + path)
    if path == SCREEN:
        need(original.count(OBSOLETE_IMPORT) == 0, 'Original duplicate Miuix import is back in the screen')
        operations = [op for op in item['operations'] if op['before'] != OBSOLETE_IMPORT]
        need(len(operations) == len(item['operations']) - 1, 'Search screen recipe changed')
        generated = apply_recipe(repo, original, dict(item, operations=operations))
        need(generated.count(CLOCK) == 2, 'Current Search focused-clear monotonic clock boundary changed')
        return generated.replace(CLOCK, CLOCK_REPLACEMENT)
    if path == LANDING:
        # The landing recipe is four counted, unique anchors; it keeps the same-window configuration binding.
        generated = original
        need(len(item['operations']) == 4, 'Search landing recipe changed')
        for op in item['operations']:
            need(generated.count(op['before']) == 1, 'Search landing anchor changed: ' + op['before'][:60])
            generated = generated.replace(op['before'], op['after'], 1)
        return generated
    generated = apply_recipe(repo, original, item)
    if path == VIEW_MODEL:
        # The two initial-state reads are split over two lines upstream, so the recipe's one-line
        # SettingsManager anchor misses them. Both bind to the desktop-owned search settings view.
        for name in ('isSearchHistoryRecordingEnabledSync', 'isPrivacyModeEnabledSync'):
            before = ('com.android.purebilibili.core.store.SettingsManager\n            .' + name + '(application)')
            need(generated.count(before) == 1, 'Search initial settings read changed: ' + name)
            generated = generated.replace(before, 'DesktopOriginalSearchSettings.' + name + '(application)', 1)
        need('core.store.SettingsManager' not in generated, 'Search view model still names the Android settings manager')
    # SearchViewModel keeps the producer's explicit owner guards.
    return generated


def retain_layout_toggle(generated):
    """The one counted hunk over the whole v0.4.6 screen: both toggle arguments return to the filter actions call."""
    need(generated.count(LAYOUT_TOGGLE_ANCHOR) == 1, 'Search filter actions call changed')
    need('onLayoutToggle' not in generated and 'listLayout.toggle' not in generated,
         'Search screen already passes the layout toggle')
    need(generated.count('val listLayout = rememberVideoListLayoutControl()') == 1, 'Search list layout control changed')
    return generated.replace(LAYOUT_TOGGLE_ANCHOR, 'SearchVideoFilterActions(\n' + LAYOUT_TOGGLE_ARGUMENTS +
                             FILTER_CALL_INDENT + 'currentDurations = state.searchDurations,\n', 1)


def retain_filter_sheet(before):
    """The sheet stays the v0.4.1 body: the retained screen call needs its three layout toggle declarations."""
    need(before.count(SHEET_TOGGLE_PARAMETERS) == 1 and before.count('if (showLayoutToggle) {') == 1 and
         before.count('onClick = onLayoutToggle,') == 1, 'The retained search filter sheet no longer carries the layout toggle')
    return before


def advance_page(repo, item, before):
    """Advance one complete producer body to the whole v0.4.6 file plus the same platform recipe.

    The screen additionally gets the retained layout toggle; the filter sheet is returned unchanged (withheld)."""
    sources, manifest = fixed(repo)
    path = item['path']
    need(path in PAGES, 'Unknown search state page: ' + path)
    need(sha(before) == pins(manifest, 'beforeFinalOutputs', path), 'Search state predecessor changed: ' + path)
    original = sources[path]
    extra = {}
    if path in WITHHELD_PAGES:
        # The manifest's after pin of the sheet is the archived whole v0.4.6 file itself: the candidate that is not applied.
        need(sha(original) == pins(manifest, 'afterFinalOutputs', path), 'Search state output changed: ' + path)
        body = retain_filter_sheet(before)
        extra = dict(retainedPredecessor=True, withheldOriginalSHA256=sha(original))
    else:
        body = replay = adapt(repo, item, original)
        need(sha(replay) == pins(manifest, 'afterFinalOutputs', path), 'Search state output changed: ' + path)
        if path == SCREEN:
            body = retain_layout_toggle(replay)
            need(sha(body) == SCREEN_WITH_TOGGLE_SHA256, 'Search screen with the retained layout toggle changed')
            extra = dict(recipeReplaySHA256=sha(replay), layoutToggleRetained=True)
    return body, dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, path=path,
        wholeOriginalSelected=path not in WITHHELD_PAGES, completeCurrentOriginalScreen=path == SCREEN,
        originalSHA256=sha(original), predecessorSHA256=sha(before), advancedSHA256=sha(body),
        originalToWindows=inverse_proof(original, body), retainedToCurrent=inverse_proof(before, body),
        jvmTestsExecuted=False, **extra)


def _write(target, text):
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding='utf8', newline='\n')


def emit_policies(repo, output, test_output):
    """Whole policies and their original tests; the motion policy and its test advance the v0.4.2 emission."""
    sources, manifest = fixed(repo)
    output = Path(output)
    rows = []
    # SearchContentStatePolicy.kt: a canonical direct file, excluded in prepareUpstreamSources and emitted whole.
    content = output / (MAIN_RELATIVE + 'SearchContentStatePolicy.kt')
    need(not content.exists(), 'Search content state policy already has an emitter')
    _write(content, sources[CONTENT_POLICY])
    rows.append(dict(path=CONTENT_POLICY, output=str(content), wholeOriginal=True, originalSHA256=sha(sources[CONTENT_POLICY])))
    # SearchMotionBudgetPolicy.kt: the v0.4.2 dependency pin emitted it; the exact body is required first.
    motion = output / (MAIN_RELATIVE + 'SearchMotionBudgetPolicy.kt')
    need(motion.is_file(), 'The v0.4.2 search motion policy was not emitted')
    before = motion.read_text(encoding='utf8')
    need(sha(before) == pins(manifest, 'beforeFinalOutputs', MOTION_POLICY), 'Search motion policy predecessor changed')
    _write(motion, sources[MOTION_POLICY])
    rows.append(dict(path=MOTION_POLICY, output=str(motion), wholeOriginal=True, originalSHA256=sha(sources[MOTION_POLICY]),
        predecessorSHA256=sha(before), retainedToCurrent=inverse_proof(before, sources[MOTION_POLICY])))
    if test_output is not None:
        tests = Path(test_output)
        previous = tests / (MAIN_RELATIVE + 'SearchMotionBudgetPolicyTest.kt')
        need(previous.is_file(), 'The v0.4.2 search motion test was not emitted')
        before_test = previous.read_text(encoding='utf8')
        need(sha(before_test) == pins(manifest, 'beforeFinalOutputs', MOTION_TEST), 'Search motion test predecessor changed')
        _write(previous, sources[MOTION_TEST])
        rows.append(dict(path=MOTION_TEST, output=str(previous), wholeOriginal=True, originalSHA256=sha(sources[MOTION_TEST]),
            predecessorSHA256=sha(before_test), retainedToCurrent=inverse_proof(before_test, sources[MOTION_TEST])))
        added = tests / (MAIN_RELATIVE + 'SearchContentStatePolicyTest.kt')
        need(not added.exists(), 'Search content state test already has an emitter')
        _write(added, sources[CONTENT_TEST])
        rows.append(dict(path=CONTENT_TEST, output=str(added), wholeOriginal=True, originalSHA256=sha(sources[CONTENT_TEST])))
    proof = dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, policies=rows,
        originalTestCount=dict(motion=sources[MOTION_TEST].count('@Test'), content=sources[CONTENT_TEST].count('@Test')),
        jvmTestsExecuted=False, mainCompiled=False, pixelsValidated=False)
    _write(output / PROOF_NAME, json.dumps(proof, ensure_ascii=False, indent=2) + '\n')
    return proof
