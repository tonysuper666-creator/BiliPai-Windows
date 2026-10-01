from pathlib import Path
import hashlib
import importlib.util
import json
import sys

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
REPO = next(p for p in HERE.parents if (p / '.git').exists())
EDITOR = REPO / 'desktop/.local/dynamic-editor-detail-parity/final-production-safe'
SNAPSHOT = REPO / 'desktop/.local/dynamic-editor-main-product-snapshot-01'
OPERATIONS = 'desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt'

def safe(path):
    value = str(Path(path).absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value)

def write(path, value):
    safe(path.parent).mkdir(parents=True, exist_ok=True)
    safe(path).write_text(value, encoding='utf-8', newline='\n')

def sha(path):
    return hashlib.sha256(safe(path).read_bytes()).hexdigest()

assert sha(SNAPSHOT / 'manifest.json') == 'bc6cf9ef064ba68f28f36c3bbf2b59721629405567c7ced7e4799a844ff7b062'
assert sha(SNAPSHOT / 'ordered-runtime-cp.json') == '515bf598f56b27de45aa04d63493e1f8ad38b7e4710ef358fea97b9fc9d620c1'
assert sha(EDITOR / 'frozen-handoff.json') == '15465a9bf04fe3ac59839bfcf3717a3c35ac2ae45e4073d8452a29e2064c65a2'
generator = HERE / 'prepared/desktop/tools/extract-upstream-dynamic-reply.py'
spec = importlib.util.spec_from_file_location('detail_reply_ui', generator)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
module.generate(REPO, safe(HERE / 'generated/ui'))
generator = HERE / 'prepared/desktop/tools/extract-upstream-dynamic-detail.py'
spec = importlib.util.spec_from_file_location('detail_reply_session', generator)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
module.generate(REPO, safe(HERE / 'generated/detail'))

# Only current Main's pinned Operations is overridden. Original editor UI/policy
# classes now come from its actual product snapshot, never old prepared overlays.
baseline = HERE / 'source-baseline/DesktopDynamicCardOperations.kt'
if not safe(baseline).exists():
    assert sha(REPO / OPERATIONS) == '8130c63b4554ed9970250588144955e6accf04e25223b7218dab7784ec585e53'
    write(baseline, safe(REPO / OPERATIONS).read_text(encoding='utf-8'))
base = safe(baseline).read_text(encoding='utf-8')
assert hashlib.sha256(base.encode()).hexdigest() == '8130c63b4554ed9970250588144955e6accf04e25223b7218dab7784ec585e53'
fragment = safe(HERE / 'protocol/DesktopDynamicCommentOperations.fragment.kt').read_text(encoding='utf-8')
detail_fragment = safe(HERE / 'protocol/detail-prepared/DesktopDynamicDetailOperations.fragment.kt').read_text(encoding='utf-8')
assert base.rstrip().endswith('}')
desired = base.rstrip()[:-1] + '\n' + fragment + '\n' + detail_fragment + '\n}\n'
write(HERE / 'prepared' / OPERATIONS, desired)
write(HERE / 'baseline.json', json.dumps(dict(
    snapshot=str(SNAPSHOT.relative_to(REPO)), snapshotSha256Bytes=sha(SNAPSHOT/'manifest.json'),
    orderedRuntimeCpSha256Bytes=sha(SNAPSHOT/'ordered-runtime-cp.json'),
    preparedEditorBaseSha256Bytes=sha(EDITOR/'frozen-handoff.json'),
    operationsBaseIsActualEditorMain=True, operationsBaseSha256Bytes=sha(baseline),
    preparedOperationsSha256Bytes=sha(HERE/'prepared'/OPERATIONS),
    MainIntegration=False, sharedGradle=False, HWND=False, realAccount=False), indent=2)+'\n')
