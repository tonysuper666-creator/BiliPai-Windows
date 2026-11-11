#!/usr/bin/env python3
"""Compare complete verbose unittest logs without changing the gate's result."""
from pathlib import Path
import argparse
import json
import re


def parse(path):
    task = None
    records = {}
    subtests = {}
    pending = None
    summaries = []
    counts = []
    for line in path.read_text().splitlines():
        heading = re.fullmatch(r'Windows tools: generated stage \((\d+) tests\)', line)
        if heading:
            task = 'generated/live-sync' if task is None else 'generated/legacy'
            counts.append(int(heading[1]))
        match = re.match(r'^(test\S*|setUpClass|tearDownClass) \(([^)]+)\)(?: \.\.\. (.*))?$', line)
        if match:
            name, owner, status = match.groups()
            owner = owner.removesuffix('.' + name)
            pending = '|'.join((task, owner, name))
            status = status or ''
            if status == 'ok' or status.startswith(('FAIL', 'ERROR', 'skipped', 'expected failure', 'unexpected success')):
                records[pending] = status
                pending = None
        elif pending:
            subtest = re.match(r'^  test\S* \([^)]+\) (.+) \.\.\. (FAIL|ERROR|skipped .*)$', line)
            if subtest:
                parameters, status = subtest.groups()
                subtests[pending + ' ' + parameters] = status
                if status.startswith('ERROR') or pending not in records:
                    records[pending] = status
            else:
                status = line.rsplit(' ... ', 1)[-1]
                if status in ('ok', 'FAIL', 'ERROR') or status.startswith('skipped '):
                    records[pending] = status
                    pending = None
        if re.match(r'^Ran \d+ tests in ', line) or re.match(r'^(OK|FAILED)(\b|\()', line):
            summaries.append(line)
    assert len(counts) == 2, ('Missing stage', path, counts)
    assert len([s for s in summaries if s.startswith('Ran ')]) == 2, ('Incomplete run', path)
    assert len([s for s in summaries if s.startswith(('OK', 'FAILED'))]) == 2, ('Missing finalizer', path)
    actual = {key for key in records if not key.endswith(('|setUpClass', '|tearDownClass'))}
    assert len(actual) == sum(int(re.search(r'Ran (\d+)', s)[1]) for s in summaries if s.startswith('Ran ')), ('Unparsed test identities', path)
    return dict(discovered=counts, summaries=summaries, records=records, subtests=subtests)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    root = args.evidence.resolve()
    before, after = [parse(root / phase / 'generated.log') for phase in ('before', 'after')]
    def selected(run, prefix):
        return {key for key, value in run['records'].items() if value.startswith(prefix)}
    before_cases = {key for key in before['records'] if not key.endswith(('|setUpClass', '|tearDownClass'))}
    after_cases = {key for key in after['records'] if not key.endswith(('|setUpClass', '|tearDownClass'))}
    delta = {}
    for name, prefix in (('fail', 'FAIL'), ('error', 'ERROR'), ('skip', 'skipped')):
        old, new = selected(before, prefix), selected(after, prefix)
        delta[name] = dict(before=sorted(old), after=sorted(new), added=sorted(new-old), removed=sorted(old-new))
    delta['missing'] = sorted(before_cases-after_cases)
    delta['subtestsAddedNonpassing'] = sorted(after['subtests'].keys() - before['subtests'].keys())
    delta['subtestsMissing'] = sorted(before['subtests'].keys() - after['subtests'].keys())
    delta['subtestsChanged'] = {key: dict(before=before['subtests'][key], after=after['subtests'][key])
                              for key in sorted(before['subtests'].keys() & after['subtests'].keys())
                              if before['subtests'][key] != after['subtests'][key]}
    delta['newlyExecuted'] = sorted(after_cases-before_cases)
    delta['discoveryIdentical'] = before['discovered'] == after['discovered']
    delta['changedOutcomes'] = {key: dict(before=before['records'][key], after=after['records'][key])
                              for key in sorted(before['records'].keys() & after['records'].keys())
                              if before['records'][key] != after['records'][key]}
    output = dict(before=before, after=after, delta=delta,
                  note='Resolved class-setup errors are reported under removed errors, not missing test methods. Every executed method identity is checked against the native Ran summary. Raw runner exit codes remain in before/command.json and after/command.json.')
    (root/'generated-diff.json').write_text(json.dumps(output, indent=2) + '\n')
    for phase, run in (('before', before), ('after', after)):
        print(phase + ': ' + '; '.join(run['summaries']))
    print('new fail/error/skip/missing: ' + '/'.join(str(len(delta[name]['added'])) for name in ('fail','error','skip')) + '/' + str(len(delta['missing'])))
    print('newly executed: ' + str(len(delta['newlyExecuted'])))
    assert delta['discoveryIdentical'], 'Discovery count changed'
    assert not any(delta[name]['added'] for name in ('fail','error','skip')), 'New nonpassing identities'
    assert not delta['missing'], 'Previously executed identity missing'
    assert not delta['subtestsAddedNonpassing'], 'New nonpassing subtests'
    assert not delta['subtestsChanged'], 'Subtest outcome changed; inspect raw logs'


if __name__ == '__main__':
    main()
