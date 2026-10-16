#!/usr/bin/env python3
"""Prove sealed production classpath/resource identity; never execute Java."""
from pathlib import Path
import argparse
import json
from original_root_ui_runtime_binding import bind, json_bytes
from original_root_ui_candidate_classpath import bounded_bytes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('export', 'candidate', 'run', 'jobs', 'log', 'plan', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--source-sha', required=True)
    parser.add_argument('--qualified-native-jar', type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Choose a new immutable binding output file')
    proof = bind(args.export, args.candidate, json_bytes(bounded_bytes(args.run, 16 * 1024 * 1024)),
        json_bytes(bounded_bytes(args.jobs, 16 * 1024 * 1024)), bounded_bytes(args.log, 32 * 1024 * 1024).decode('utf-8'),
        bounded_bytes(args.plan, 4 * 1024 * 1024), args.source_sha, args.qualified_native_jar)
    with args.output.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(json.dumps(proof, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({key: proof[key] for key in ('sourceSha', 'productionClasspathAndResourcesBound',
        'candidateRuntimeBound', 'actualJdkBound', 'imageLaunchReady', 'applicationExecuted')}))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
