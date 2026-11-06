#!/usr/bin/env python3
"""Compare same-version JUnit executions and untagged-sheet renders; no failure allowlist."""
import json
import hashlib
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

TASKS = ("test", "upstreamDesignSourceTests", "upstreamAppCoreSourceTests", "applicationImageLoaderOwnerTests", "sponsorPresentationTests")


def collect(root):
    repo = Path(root)
    root = repo / "desktop/build"
    cases, failed, skipped, counts = set(), set(), set(), {}
    for task in TASKS:
        reports = sorted((root / "test-results" / task).glob("TEST-*.xml"))
        if not reports:
            raise SystemExit(f"No XML results for {task} in {root}")
        total = bad = skip = 0
        for report in reports:
            for case in ET.parse(report).getroot().iter("testcase"):
                key = task + "::" + case.attrib["classname"] + "::" + case.attrib["name"]
                cases.add(key)
                total += 1
                if case.find("failure") is not None or case.find("error") is not None:
                    failed.add(key)
                    bad += 1
                if case.find("skipped") is not None:
                    skipped.add(key)
                    skip += 1
        counts[task] = {"tests": total, "failed": bad, "skipped": skip}
    hashes = {p.name: p.read_text().strip() for p in (root / "ton1194-default").glob("*.sha256")}
    if not hashes:
        raise SystemExit(f"No rendered sheet hashes in {root}")
    # All non-desktop committed inputs must match, not just the test failure names.
    entries = subprocess.check_output(["git", "ls-tree", "HEAD"], cwd=repo, text=True).splitlines()
    inputs = {line.split("\t", 1)[1]: line.split()[2] for line in entries
              if line.split("\t", 1)[1] != "desktop"}
    fixture = repo / "desktop/src/test/kotlin/com/bilipai/desktop/ui/modal/DesktopModalDefaultBaselineTest.kt"
    provenance = {
        "commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip(),
        "non_desktop_inputs": inputs,
        "default_fixture_sha256": hashlib.sha256(fixture.read_bytes()).hexdigest(),
    }
    return cases, failed, skipped, counts, hashes, provenance


base, candidate = map(collect, sys.argv[1:3])
result = {
    "baseline": base[3], "candidate": candidate[3],
    "new_failures": sorted(candidate[1] - base[1]),
    "removed_test_cases": sorted(base[0] - candidate[0]),
    "new_skips": sorted(candidate[2] - base[2]),
    "baseline_failures": sorted(base[1]),
    "candidate_failures": sorted(candidate[1]),
    "untagged_sheet_hashes": candidate[4],
    "untagged_sheet_hashes_equal": base[4] == candidate[4],
    "provenance": {"baseline": base[5], "candidate": candidate[5]},
    "same_upstream_inputs": base[5]["non_desktop_inputs"] == candidate[5]["non_desktop_inputs"],
    "same_default_fixture": base[5]["default_fixture_sha256"] == candidate[5]["default_fixture_sha256"],
}
print(json.dumps(result, ensure_ascii=False, indent=2))
sys.exit(bool(result["new_failures"] or result["removed_test_cases"] or result["new_skips"]
              or not all(result[key] for key in ("untagged_sheet_hashes_equal", "same_upstream_inputs", "same_default_fixture"))))
