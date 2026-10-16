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
    runtime_inputs = ('runtime-image', 'candidate-zip', 'fixed-jdk-archive', 'package-run',
        'package-jobs', 'package-log', 'candidate-toolchain', 'source-repo')
    for name in runtime_inputs:
        parser.add_argument('--' + name, type=Path)
    args = parser.parse_args()
    selected = [getattr(args, name.replace('-', '_')) for name in runtime_inputs]
    if any(selected) and not all(selected):
        parser.error('Runtime image binding requires all package evidence, the portable ZIP and fixed JDK archive')
    if args.output.exists() or not args.output.parent.is_dir():
        parser.error('Choose a new immutable binding output file')
    package = None
    if args.runtime_image:
        package = dict(sourceRepo=args.source_repo, zip=args.candidate_zip,
            run=json_bytes(bounded_bytes(args.package_run, 16 * 1024 * 1024)),
            jobs=json_bytes(bounded_bytes(args.package_jobs, 16 * 1024 * 1024)),
            log=bounded_bytes(args.package_log, 32 * 1024 * 1024).decode('utf-8'),
            toolchainRaw=bounded_bytes(args.candidate_toolchain, 65536))
    plan_raw = bounded_bytes(args.plan, 4 * 1024 * 1024)
    proof = bind(args.export, args.candidate, json_bytes(bounded_bytes(args.run, 16 * 1024 * 1024)),
        json_bytes(bounded_bytes(args.jobs, 16 * 1024 * 1024)), bounded_bytes(args.log, 32 * 1024 * 1024).decode('utf-8'),
        plan_raw, args.source_sha, args.qualified_native_jar, package)
    if args.runtime_image:
        from original_root_ui_runtime_image import provision_runtime, java_argument_path
        image = provision_runtime(proof, args.candidate_zip, package['run'], package['jobs'], package['log'],
            package['toolchainRaw'], args.fixed_jdk_archive, args.runtime_image, source_plan_raw=plan_raw)
        proof.update(runtimeImage=image, javaExecutable=image['javaExecutable'],
            classpath=[java_argument_path(path) for path in proof['classpath']],
            systemProperties={key: java_argument_path(value) for key, value in proof['systemProperties'].items()},
            candidateRuntimeBound=True, actualJdkBound=True, imageLaunchReady=True, executionAuthorized=False)
    with args.output.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(json.dumps(proof, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({key: proof[key] for key in ('sourceSha', 'productionClasspathAndResourcesBound',
        'candidateRuntimeBound', 'actualJdkBound', 'imageLaunchReady', 'applicationExecuted')}))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
