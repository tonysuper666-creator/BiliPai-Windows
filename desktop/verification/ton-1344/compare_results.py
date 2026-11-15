"""Compare actual Gradle XML failure identities, retaining every finalizer failure.

Usage: python3 compare_results.py BASELINE_REPO HEAD_REPO OUTPUT_DIRECTORY
This only reads existing results; run the documented Gradle command in both trees first.
"""
from pathlib import Path
import hashlib
import json
import shutil
import sys
import xml.etree.ElementTree as ET


def collect(repo, output):
    failures = set()
    suites = []
    root = repo / 'desktop/build/test-results'
    for file in sorted(root.glob('*/TEST-*.xml')):
        suite = ET.parse(file).getroot()
        target = output / file.relative_to(root)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, target)
        tests = suite.findall('testcase')
        suites.append(dict(task=file.parent.name, name=suite.get('name'),
                           tests=len(tests), failures=int(suite.get('failures', 0)),
                           skipped=int(suite.get('skipped', 0))))
        for test in tests:
            if test.find('failure') is not None or test.find('error') is not None:
                failures.add(file.parent.name + '::' + test.get('classname', '') + '::' + test.get('name', ''))
    return dict(failures=sorted(failures), suites=suites)


if __name__ == '__main__':
    base, head, output = map(lambda s: Path(s).resolve(), sys.argv[1:])
    output.mkdir(parents=True, exist_ok=True)
    test = Path('desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopDynamicUpListVisibilityTest.kt')
    if (base / test).read_bytes() != (head / test).read_bytes():
        raise SystemExit('Refusing to compare different behavior tests')
    before = collect(base, output / 'baseline-xml')
    after = collect(head, output / 'head-xml')
    result = dict(testSha256=hashlib.sha256((head / test).read_bytes()).hexdigest(),
                  baseline=before, head=after,
                  cleared=sorted(set(before['failures']) - set(after['failures'])),
                  added=sorted(set(after['failures']) - set(before['failures'])),
                  common=sorted(set(before['failures']) & set(after['failures'])))
    (output / 'failure-identity-diff.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({key: result[key] for key in ('testSha256', 'cleared', 'added', 'common')}, indent=2))
