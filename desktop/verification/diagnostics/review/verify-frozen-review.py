"""Read-only byte verification, safe to run after the cohort has been frozen."""
from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent
def safe(p):
    value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
manifest=json.loads((HERE/'frozen-handoff.json').read_text(encoding='utf-8'))
for e in manifest['artifacts']:assert sha(HERE/e['path'])==e['sha256Bytes'],e['path']
plan=json.loads((HERE/'install-plan.json').read_text(encoding='utf-8'))
for e in plan['frozenChecks']+plan['dependencies']:assert sha(e['path'])==e['sha256Bytes'],e['path']
print(json.dumps(dict(passed=True,reviewArtifactCount=manifest['artifactCount'],producerArtifactCount=322,
    dependenciesAndFoundationSnapshotVerified=True,reviewManifestSha256Bytes=sha(HERE/'frozen-handoff.json'),mainWritten=False)))
