from pathlib import Path
import hashlib, json, runpy, sys
sys.stdout.reconfigure(encoding='utf-8')
sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
sha = lambda value: hashlib.sha256(value).hexdigest()

# Both programs assert the clean current Candidate base and every frozen input.
# The fresh prerequisite graph must equal the exact source plan used by Root313.
prereq_id = sys.argv[1]
root_id = sys.argv[2]
original_args = sys.argv[:]
try:
    sys.argv = [str(HERE / 'preflight_current.py'), prereq_id]
    prerequisites = runpy.run_path(sys.argv[0])
    sys.argv = [str(HERE / 'preflight_full_root.py'), root_id, 'preflight' + prereq_id.zfill(2)]
    joint = runpy.run_path(sys.argv[0])
finally:
    sys.argv = original_args
assert not prerequisites['conflicts']
assert len(joint['texts']) == 94
print(json.dumps(dict(freshSourceComposition=True, sourceFamilies=len(joint['texts']),
                      noCandidateMutation=True, rootResultSHA256Bytes=sha((joint['out'] / 'result.json').read_bytes()))))
