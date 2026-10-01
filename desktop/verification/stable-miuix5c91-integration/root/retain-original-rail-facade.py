from pathlib import Path
import difflib,hashlib,json
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2].parent/'BiliPai-v023';OUT=HERE/'miuix5c91-install'
p=REPO/'desktop/tools/extract-upstream-preferences.py';b=p.read_bytes();before=b.decode().replace('\r\n','\n')
old='def adapt(path, source, host):\n    if path.endswith("/AdaptivePreferenceComponents.kt"):\n'
new='def adapt(path, source, host):\n    if path.endswith("/AppNavigationComponents.kt"):\n        # Exact5c91 supports the original facade unchanged; this explicit whitelist\n        # branch preserves the producer rejection of all other unsupported paths.\n        return source\n    elif path.endswith("/AdaptivePreferenceComponents.kt"):\n'
assert before.count(old)==1
after=before.replace(old,new,1);out=after.encode();sha=lambda x:hashlib.sha256(x).hexdigest()
(OUT/'rail-step2-before.py').write_bytes(b);p.write_bytes(out);(OUT/'rail-step2-after.py').write_bytes(out)
patch=''.join(difflib.unified_diff(before.splitlines(keepends=True),after.splitlines(keepends=True),fromfile='before/desktop/tools/extract-upstream-preferences.py',tofile='after/desktop/tools/extract-upstream-preferences.py'))
(OUT/'explicit-original-rail-branch.patch').write_bytes(patch.encode())
report=dict(reason='The producer strictly rejects unmatched adapted paths. Keep AppNavigationComponents as an explicitly approved unchanged original facade after retiring old5157 substitution.',beforeSHA256=sha(b),afterSHA256=sha(out),failedGeneratorPhase=29,unsupportedPathRejectionPreserved=True)
(OUT/'rail-generator-correction.json').write_bytes((json.dumps(report,indent=2)+'\n').encode());print(json.dumps(report))
