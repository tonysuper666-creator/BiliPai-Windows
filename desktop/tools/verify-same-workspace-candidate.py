"""Check a provisional image against the preceding source step's output ledger.

This tool neither authenticates a completed GitHub run nor qualifies a release.
The existing terminal-run guard and full candidate runtime binding remain required.
It executes no Java, client, media or network request.
"""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import re
import sys

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
import original_root_ui_runtime_binding as binding


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, TOOLS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


readback = load('same_workspace_ui_data', 'verify-original-root-ui-harness-export.py')
source = load('same_workspace_source_inputs', 'validate-source-cohort.py')
gate = readback.gate
require = readback.require
jars = binding.jars


def compile_tasks(log):
    clean = re.sub(r'\x1b\[[0-9;]*m', '', log)
    rows = re.findall(r'(?m)^(?:\d{4}-\d\d-\d\dT\S+Z )?> Task :(compileKotlin|compileJava)(?: ([A-Z][A-Z-]*))?\s*$', clean)
    require(len(rows) == len({name for name, _ in rows}), 'Ambiguous main compilation task markers')
    require(all(state not in ('FROM-CACHE', 'FAILED') for _, state in rows),
            'Unexpected cached or failed main compilation task')
    tasks = dict(rows)
    avoided = tasks.get('compileKotlin') == 'UP-TO-DATE' and tasks.get('compileJava') in ('UP-TO-DATE', 'NO-SOURCE')
    return tasks, avoided


def verify(candidate, export, plan_raw, source_log, source_sha, actual_pins, package_log):
    require(re.fullmatch('[0-9a-f]{40}', source_sha) is not None, 'Exact source SHA is required')
    require(len(plan_raw) <= 4 * 1024 * 1024, 'Oversized source plan')
    plan = json.loads(plan_raw, object_pairs_hook=gate.unique_json)
    require(plan.get('repository') == gate.REPOSITORY and plan.get('exportOriginalRootUiHarness') is True,
            'Own-repository original UI ledger is required')
    payload, receipt_digest = gate.receipt_from_log(source_log)
    receipt = payload['receipt']
    require(receipt.get('passed') is True and receipt.get('exitCode') == 0, 'Source step did not report success')
    require(receipt.get('sourceSha') == source_sha and receipt.get('baseHead') == plan['baseHead'] and
            receipt.get('windowsVersion') == plan['windowsVersion'], 'Source step identity differs')
    pins = {row['path']: row['sha256'] for row in plan['sources']}
    require(len(pins) == len(plan['sources']) and receipt.get('sourceUnchanged') is True and
            receipt.get('sourceBefore') == pins == receipt.get('sourceAfter') == actual_pins,
            'Source inputs changed after tests')
    sealed = receipt.get('originalRootUiHarnessExport', {})
    require(sealed.get('sourceSha') == source_sha and sealed.get('productionClassesExported') is False and
            sealed.get('productionRuntimeBindingRequired') is True,
            'Source step did not export its production ledger')
    export = jars.paths.checked_path(export)
    manifest_raw = jars.bounded_bytes(export / 'manifest.json', readback.MAX_JSON)
    require(hashlib.sha256(manifest_raw).hexdigest() == sealed.get('manifestSha256'),
            'Production ledger manifest is not the source step result')
    manifest = json.loads(manifest_raw, object_pairs_hook=gate.unique_json)
    require(manifest.get('schema') == 1 and manifest.get('sourceSha') == source_sha and
            manifest.get('planSha256') == hashlib.sha256(plan_raw).hexdigest() and
            manifest.get('windowsVersion') == plan['windowsVersion'], 'Production ledger identity differs')
    rows = manifest['payload']
    require(isinstance(rows, list) and 4 <= len(rows) <= 36, 'Invalid source export inventory')
    inventory = {readback.relative(row['path']): row for row in rows}
    require(len(inventory) == len(rows) and 'production-ledger.json' in inventory,
            'Missing or duplicate production ledger')
    ledger = readback.json_snapshot(export, 'production-ledger.json', inventory['production-ledger.json'])
    readback.ledger_inventory(ledger)
    candidate = jars.paths.checked_path(candidate)
    packaged = jars.inventory(candidate)
    main = candidate / 'app' / packaged['mainJar']
    main_output = binding.verify_main_output(main, ledger)
    require(jars.read_windows_version(main) == plan['windowsVersion'], 'Packaged Windows version differs')
    tasks, avoided = compile_tasks(package_log)
    return dict(schema=1, repository=gate.REPOSITORY, sourceSha=source_sha,
        windowsVersion=plan['windowsVersion'], sourceReceiptSHA256=receipt_digest,
        sourcePlanSHA256=hashlib.sha256(plan_raw).hexdigest(), mainOutput=main_output,
        packagedMainMatchesSourceStepOutputs=True, mainCompilationTasks=tasks,
        mainRecompileAvoided=avoided, sourceQualified=False, packageQualified=False,
        fullRuntimeBindingRequired=True, successfulTerminalRunStillRequired=True,
        applicationExecuted=False, hardwareExecuted=False, releaseQualified=False,
        executionAuthorized=False, desktopReplaced=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ('repo', 'candidate', 'ui-export', 'source-log', 'package-log', 'source-sha', 'output'):
        parser.add_argument('--' + key, required=True)
    args = parser.parse_args()
    repo = Path(args.repo).resolve()
    require(source.git(repo, 'rev-parse', 'HEAD') == args.source_sha, 'Current source commit differs')
    plan = source.check(repo)
    plan_raw = source.read(repo / source.PLAN)
    measured = {row['path']: source.source_digest(repo, row) for row in plan['sources']}
    proof = verify(Path(args.candidate), Path(args.ui_export), plan_raw,
        jars.bounded_bytes(Path(args.source_log), 64 * 1024 * 1024).decode('utf-8-sig'), args.source_sha,
        measured, jars.bounded_bytes(Path(args.package_log), 64 * 1024 * 1024).decode('utf-8-sig'))
    require(source.git(repo, 'rev-parse', 'HEAD') == args.source_sha and
            source.read(repo / source.PLAN) == plan_raw and
            measured == {row['path']: source.source_digest(repo, row) for row in plan['sources']},
            'Source changed during image verification')
    with Path(args.output).open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(json.dumps(proof, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(proof))


if __name__ == '__main__':
    main()
