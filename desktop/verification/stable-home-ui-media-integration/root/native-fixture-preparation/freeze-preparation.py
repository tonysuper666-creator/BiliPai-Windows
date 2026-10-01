from pathlib import Path
import hashlib, json, sys
sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
def safe(path):
    value = str(path.absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value)
def sha(path): return hashlib.sha256(safe(path).read_bytes()).hexdigest()
def main():
    output = HERE / 'prepared-fixture-handoff.json'
    assert not output.exists()
    expected = ['NativeHomeMediaFixture.kt','run.py','audit-abi.py','abi-source-review.json','audit-history-01.json','CONTRACT.md','freeze-preparation.py']
    files = [HERE / name for name in expected]
    for run in ['compile-01','compile-02']:
        files.extend(path for path in sorted((HERE/'runs'/run).rglob('*')) if path.is_file())
    artifacts = [dict(path=str(path.relative_to(HERE)).replace('\\','/'), bytes=safe(path).stat().st_size, sha256Bytes=sha(path)) for path in files]
    value = dict(status='FROZEN_SOURCE_ONLY_FIXTURE_PREPARATION', artifactCount=len(artifacts), artifacts=artifacts, fixtureOnlyCompilePass=True, actualProductNativeRuntimeProof=False, nativeLoadedOrExecuted=False, ffmpegExecuted=False, productionSourceModified=False, prospectiveCompileDependencies='Actual30 + explicit frozen media74 and Home UI class overlays. NEVER executed.', actualRunRequires='Later Root-authorized immutable installed graph and 0 production class overrides', actualRunCohortMustBeSeparate=True)
    safe(output).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps(dict(path=str(output),sha256Bytes=sha(output),artifacts=len(artifacts)),indent=2))
if __name__ == '__main__': main()
