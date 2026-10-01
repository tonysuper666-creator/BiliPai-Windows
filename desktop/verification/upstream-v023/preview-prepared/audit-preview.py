"""Independent original-body inventory for the single prepared preview owner."""
from pathlib import Path
import difflib, hashlib, importlib.util, json, re, sys, textwrap
sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = next(p for p in HERE.parents if (p / '.git').exists())
COMP = 'app/src/main/java/com/android/purebilibili/feature/dynamic/components/'

def safe(path):
    value = str(Path(path).absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value)

def read(path):
    return safe(path).read_text(encoding='utf-8').replace('\r\n', '\n')

def write(path, value):
    safe(path.parent).mkdir(parents=True, exist_ok=True)
    safe(path).write_text(value, encoding='utf-8', newline='\n')

def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()

def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

parser_path = ROOT / 'desktop/tools/sync-upstream.py'
media_path = ROOT / 'desktop/tools/extract-upstream-media.py'
parser = load('audit_original_parser', parser_path)
media = load('audit_original_functions', media_path)
original = read(HERE / 'original-v023' / (COMP + 'ImagePreviewDialog.kt'))
renderer_path = HERE / 'generated/com/android/purebilibili/feature/dynamic/components/DesktopOriginalImagePreviewRenderer.kt'
renderer = read(renderer_path)
policy = read(renderer_path.with_name('DesktopOriginalImageUrlPolicy.kt'))
checks = []

def check(name, a, b):
    assert a == b, name
    checks.append(dict(name=name, status='PASS', originalLfSha256=digest(a), selectedLfSha256=digest(b)))

def declaration(source, kind, name):
    tokens = parser.kotlin_tokens(source)
    found = [i for i, token in enumerate(tokens[:-1]) if token[0] == kind and tokens[i + 1][0] == name]
    assert len(found) == 1, (kind, name)
    index = found[0]
    start = source.rfind('\n', 0, tokens[index][1]) + 1
    while tokens[index][0] != '{':
        index += 1
    depth = 1
    while depth:
        index += 1
        depth += (tokens[index][0] == '{') - (tokens[index][0] == '}')
    return textwrap.dedent(source[start:tokens[index][2]])

for name in ['ImagePreviewSourceAnchor', 'ImagePreviewTransitionPolicy', 'ImagePreviewDecodePolicy', 'ZoomableImage']:
    check('Original complete direct source: ' + name,
        read(HERE / 'original-v023' / (COMP + name + '.kt')),
        read(HERE / 'direct-original' / (COMP + name + '.kt')))
for name in ['normalizeImageUrl', 'resolveImagePreviewPlaceholderCacheKey', 'resolveImageShareMimeType']:
    check('Original URL-policy function: ' + name,
        media.function(original, name, parser), media.function(policy, name, parser))
for kind, name in [('class', 'CounterScaledCornerShape'), ('object', 'ImagePreviewOverlayController')]:
    check('Original full declaration: ' + name,
        declaration(original, kind, name), declaration(renderer, kind, name))
for name in ['isImagePreviewSourceHidden', 'prepareImagePreviewSourceTransition',
             'currentTransitionProgress', 'currentFlightRect', 'triggerDismiss',
             'ImagePreviewActionButton', 'ImagePreviewCommentTopBar', 'ImagePreviewCommentPanel',
             'ImagePreviewCommentActionButton', 'LivePhotoIcon', 'LivePhotoOffIcon', 'resolveLivePhotoVideoUrl']:
    check('Original exact function body/signature: ' + name,
        media.function(original, name, parser), media.function(renderer, name, parser))
dialog = media.function(renderer, 'ImagePreviewDialog', parser)
dialog = dialog.replace('    val platform = com.bilipai.desktop.ui.LocalDesktopDynamicCardBindings.current\n', '', 1)
dialog = dialog.replace('                platform = platform,\n', '', 1)
check('Original ImagePreviewDialog after only two declared platform-capture lines removed',
      media.function(original, 'ImagePreviewDialog', parser), dialog)
request = media.data_class(renderer, 'ImagePreviewOverlayRequest', parser)
request = request.replace('    val platform: com.bilipai.desktop.ui.DesktopDynamicCardPlatform,\n', '', 1)
check('Original request schema after declared platform field removed',
      media.data_class(original, 'ImagePreviewOverlayRequest', parser), request)

# Retain the complete source diff, including the existing platform adapter.
# This is separate from the exact original declaration/body checks above.
prefix = original[:original.index('/**\n *  规范化图片 URL')]
baseline = prefix + '\n@Composable\n' + media.function(original, 'LivePhotoIcon', parser)
baseline += '\n@Composable\n' + media.function(original, 'LivePhotoOffIcon', parser)
baseline += '\n' + media.function(original, 'resolveLivePhotoVideoUrl', parser) + '\n'
prepared = '\n'.join(renderer.splitlines()[2:]) + '\n'
write(HERE / 'original-selected-preview-prefix.kt', baseline)
write(HERE / 'stable-preview-platform-adaptations.patch', ''.join(difflib.unified_diff(
    baseline.splitlines(True), prepared.splitlines(True), fromfile='original-v023-selected-prefix',
    tofile='prepared-unique-desktop-preview-renderer')))
diff_rows = []
original_lines = baseline.splitlines(True)
prepared_lines = prepared.splitlines(True)
for opcode, a, b, c, d in difflib.SequenceMatcher(None, original_lines, prepared_lines, autojunk=False).get_opcodes():
    if opcode != 'equal':
        old = ''.join(original_lines[a:b])
        new = ''.join(prepared_lines[c:d])
        diff_rows.append(dict(operation=opcode, originalStartLine=a + 1, originalLineCount=b - a,
            preparedStartLine=c + 1, preparedLineCount=d - c, original=old, prepared=new))
write(HERE / 'platform-diff-rows.json', json.dumps(diff_rows, ensure_ascii=False, indent=2) + '\n')
contract_path = HERE / 'reviewed-platform-adaptation-contract.json'
if safe(contract_path).exists():
    contract = json.loads(read(contract_path))
    assert contract['originalImagePreviewDialogLfSha256'] == digest(original)
    assert contract['preparedRendererLfSha256'] == digest(renderer)
    assert contract['preparedRendererSha256Bytes'] == hashlib.sha256(safe(renderer_path).read_bytes()).hexdigest()
    assert len(contract['reviewedRows']) == len(diff_rows)
    for expected, actual in zip(contract['reviewedRows'], diff_rows):
        assert expected['shape'] == [actual[key] for key in ['operation', 'originalStartLine',
            'originalLineCount', 'preparedStartLine', 'preparedLineCount']]
        assert expected['originalLfSha256'] == digest(actual['original'])
        assert expected['preparedLfSha256'] == digest(actual['prepared'])
    # Reverse only the independently reviewed exact adaptation pairs, then
    # compare the complete selected original prefix including retained extras.
    reversed_lines = prepared_lines[:]
    for actual in reversed(diff_rows):
        start = actual['preparedStartLine'] - 1
        end = start + actual['preparedLineCount']
        reversed_lines[start:end] = actual['original'].splitlines(True)
    check('Complete selected prefix reverse-normalized by 33 exact reviewed platform adaptation pairs',
          baseline, ''.join(reversed_lines))
result = dict(schema='stable-original-preview-source-contract-v1', status='PASS',
    originalTag='v0.2.3', originalCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
    originalImagePreviewDialogLfSha256=digest(original), preparedRendererLfSha256=digest(renderer),
    checks=checks, exactChecks=len(checks), completePlatformDiffRows=len(diff_rows),
    reviewedPlatformContractSha256Bytes=hashlib.sha256(safe(contract_path).read_bytes()).hexdigest()
        if safe(contract_path).exists() else None,
    parserInputs=[dict(path=str(path), sha256Bytes=hashlib.sha256(safe(path).read_bytes()).hexdigest())
        for path in [parser_path, media_path]],
    declaredBoundaries=['Existing desktop platform context/settings/lifecycle alias, haptic/save/share/copy/feedback and live playback adapters.',
        'Existing unique Root OverlayHost receives captured platform owner.',
        'Android Activity/window/navigation-bar/dim/predictive provider and permission/MediaStore backend remain Windows platform boundaries.',
        'No stable caller runtime proof. All owners of new ImagePreviewDialog/sourceKey and ZoomableImage signatures must recompile together.'])
write(HERE / 'source-contract-result.json', json.dumps(result, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(dict(status=result['status'], exactChecks=len(checks), completeDiffRows=len(diff_rows))))
