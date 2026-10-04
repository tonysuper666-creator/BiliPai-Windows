#!/usr/bin/env python3
"""Run source-independent policies before generation, or all contracts after it."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import sys
import unittest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", choices=("policies", "generated"), required=True)
    args = parser.parse_args()
    tools = Path(__file__).resolve().parent
    tests = tools / "tests"
    # Dynamic extractor imports and their child processes use the same helper
    # modules, regardless of the caller's working directory or PYTHONPATH.
    sys.path.insert(0, str(tools))
    os.environ["PYTHONPATH"] = os.pathsep.join(filter(None, (
        str(tools), os.environ.get("PYTHONPATH", ""))))
    patterns = ("test_sync_upstream.py", "test_publish_release.py", "test_provision_native_diagnostic_sdk.py") if args.stage == "policies" else ("test_*.py",)
    suite = unittest.TestSuite()
    for pattern in patterns:
        discovered = unittest.defaultTestLoader.discover(str(tests), pattern=pattern)
        if discovered.countTestCases() == 0:
            parser.error(f"No tests found for {pattern}; refusing an empty gate.")
        suite.addTests(discovered)
    print(f"Windows tools: {args.stage} stage ({suite.countTestCases()} tests)", flush=True)
    return 0 if unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
