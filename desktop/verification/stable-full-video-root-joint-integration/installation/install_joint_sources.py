from pathlib import Path
import hashlib, json, runpy, sys
sys.stdout.reconfigure(encoding='utf-8')
sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
sha = lambda value: hashlib.sha256(value).hexdigest()
original_args = sys.argv[:]
assert not (HERE / 'joint-source-installation-01.json').exists()
try:
    sys.argv = [str(HERE / 'compose_fresh_joint.py'), '08', '03']
    composed = runpy.run_path(sys.argv[0])
finally:
    sys.argv = original_args
joint = composed['joint']
texts = joint['texts']
assert len(texts) == 94 and joint['result']['lifecycleDeltaApplied']
expected = json.loads((HERE / 'full-root-preflight-01/result.json').read_bytes())
assert joint['result']['sourceOutputs'] == expected['sourceOutputs']
wide = joint['inspection'].wide
pending = []
for path, text in texts.items():
    destination = REPO / path
    assert destination.resolve().is_relative_to(REPO.resolve()), path
    previous = wide(destination).read_bytes() if wide(destination).exists() else None
    pending.append((destination, previous, text.encode('utf-8')))

# Every byte written below is an in-memory composition of verified local hunks
# on the current clean Candidate, or a specifically whitelisted new payload.
# No complete prepared existing family, generated tree or class/JAR is copied.
try:
    for destination, _, data in pending:
        wide(destination).parent.mkdir(parents=True, exist_ok=True)
        wide(destination).write_bytes(data)
    for destination, _, data in pending: assert wide(destination).read_bytes() == data
except BaseException:
    for destination, previous, _ in pending:
        if previous is None:
            if wide(destination).exists(): wide(destination).unlink()
        else: wide(destination).write_bytes(previous)
    raise
receipt = dict(schema=1, candidateBaseHead=joint['BASE_HEAD'],
               sourceCompositionResultSHA256Bytes=sha((joint['out'] / 'result.json').read_bytes()),
               sourceComposerSHA256Bytes=sha((HERE / 'compose_fresh_joint.py').read_bytes()),
               prerequisiteComposerSHA256Bytes=sha((HERE / 'preflight_current.py').read_bytes()),
               rootComposerSHA256Bytes=sha((HERE / 'preflight_full_root.py').read_bytes()),
               installerSHA256Bytes=sha(Path(__file__).read_bytes()),
               productionSourceFilesWritten=len(pending), registryAndGradleApplied=False,
               generatedFilesInstalled=False, productBinariesInstalled=False,
               wholeBuildPassed=False, actualMainAccepted=False, newExeDeployed=False,
               files=[dict(path=str(path.relative_to(REPO)).replace('\\', '/'),
                           new=before is None, beforeSHA256Bytes=sha(before) if before is not None else None,
                           afterSHA256Bytes=sha(after)) for path, before, after in pending])
(HERE / 'joint-source-installation-01.json').write_bytes((json.dumps(receipt, indent=2) + '\n').encode())
print(json.dumps(dict(sourceFilesWritten=len(pending), newFiles=sum(before is None for _, before, _ in pending),
                      generatedOrBinaryCopies=0, wholeBuildPassed=False, registryAndGradleApplied=False)))
