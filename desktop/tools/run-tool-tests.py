#!/usr/bin/env python3
"""Run source-independent policies before generation, or all contracts after it."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import sys
import subprocess
import unittest

from legacy_gate_workspace import legacy_gate_workspace


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", choices=("policies", "generated", "ux"), required=True)
    parser.add_argument("--in-read-point", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--live-sync-only", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()
    tools = Path(__file__).resolve().parent
    if not args.in_read_point and not args.live_sync_only:
        # The updater inventory pins the merged sources. Retained generators
        # still use the legacy originals; neither tree can stand in for the other.
        live_result = 0
        if args.stage != "ux":
            live_result = subprocess.call([sys.executable, "-B", str(Path(__file__).resolve()),
                                           "--stage", args.stage, "--live-sync-only"], cwd=tools.parents[1])
        with legacy_gate_workspace(tools.parents[1]) as repo:
            # Suites that judge the merged upstream tree (not the legacy originals) find the live checkout here.
            legacy_result = subprocess.call([sys.executable, "-B", str(repo / "desktop/tools/run-tool-tests.py"),
                                             "--stage", args.stage, "--in-read-point"], cwd=repo,
                                            env={**os.environ, "BILIPAI_LIVE_ROOT": str(tools.parents[1])})
        return legacy_result or live_result
    tests = tools / ("ux" if args.stage == "ux" else "tests")
    # Dynamic extractor imports and their child processes use the same helper
    # modules, regardless of the caller's working directory or PYTHONPATH.
    sys.path.insert(0, str(tools))
    os.environ["PYTHONPATH"] = os.pathsep.join(filter(None, (
        str(tools), os.environ.get("PYTHONPATH", ""))))
    patterns = ("test_sync_upstream.py", "test_publish_release.py", "test_provision_native_diagnostic_msvc.py", "test_provision_native_diagnostic_sdk.py", "test_verify_updater_baseline.py", "test_fetch_updater_baseline.py", "test_same_workspace_candidate.py", "test_same_workspace_terminal_admission.py") if args.stage == "policies" else ("test_*.py",)
    if args.live_sync_only:
        patterns = ("test_sync_upstream.py",)
    elif args.stage == "policies":
        patterns = tuple(name for name in patterns if name != "test_sync_upstream.py")
    elif args.stage == "generated":
        patterns = tuple(path.name for path in sorted(tests.glob("test_*.py"))
                         if path.name != "test_sync_upstream.py")
    suite = unittest.TestSuite()
    for pattern in patterns:
        discovered = unittest.defaultTestLoader.discover(str(tests), pattern=pattern)
        if discovered.countTestCases() == 0:
            parser.error(f"No tests found for {pattern}; refusing an empty gate.")
        suite.addTests(discovered)
    if suite.countTestCases() == 0:
        parser.error("No tests found; refusing an empty gate.")
    print(f"Windows tools: {args.stage} stage ({suite.countTestCases()} tests)", flush=True)
    return 0 if unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
