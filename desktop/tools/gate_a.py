#!/usr/bin/env python3
"""Gate A for the tested Windows package (TON-1085; decided 2026-11-03).

The packaged build no longer requires every test to pass. Tests copied from upstream keep upstream's own red
tests, and we change neither upstream production code nor tests to hide them. Instead a candidate passes when its
JUnit results, compared with the committed baseline run on the same upstream version
(desktop/tools/gate-a-baseline.json), show

  * no test identity (class + method) failing that did not fail in the baseline,
  * every baseline identity still present and executed (none missing or newly skipped),
  * every test added since the baseline passing, and
  * the same upstream: the upstream trees recorded in the baseline are unchanged in this checkout.

The baseline may contain failures of upstream-original tests only; desktop-owned tests must pass. Failures are
computed from the JUnit XML of every run; there is no hand-written exception list. Only Root replaces the
baseline, from a complete Windows run of the baseline commit:

  python desktop/tools/gate_a.py baseline --sha <baseline sha> --upstream-commit <merged upstream commit> \
      --upstream-tag <tag> --command "<exact Gradle command>" --results <dir> [--results <dir> ...] \
      --output desktop/tools/gate-a-baseline.json
  python desktop/tools/gate_a.py check --baseline desktop/tools/gate-a-baseline.json \
      --results desktop/build/test-results --results desktop/upstream-core/build/test-results --report <json>

A development card's targeted run (`:test --tests ...` plus the finalizers it triggers; TON-1340) executes only part
of the baseline, so `check --targeted` waives exactly one rule: a baseline identity absent from the results is not
judged. Every other rule stays: any failure outside the baseline's `failing`, a present baseline identity newly
skipped, an added identity failing or skipped, an identity that moved to another scope, worst status over duplicates,
and the same upstream trees. A test renamed or removed is indistinguishable from one not run, so a targeted verdict
is never evidence that gate A passed; its report says `"mode": "targeted"`, `"fullGateEvidence": false`.

Exit status: 0 gate passed, 1 gate failed, 2 invalid input.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

SCHEMA = 1
RANK = {"passed": 0, "skipped": 1, "failed": 2}


class GateInputError(Exception):
    pass


def scope_of(root: Path, path: Path) -> str:
    """The independent upstream-core project compiles unpatched originals; its results are kept apart from the
    desktop project's, where some of the same classes also run (patched). Recognized layouts:
    desktop/upstream-core/build/test-results/<task>/ and an exported <root>/upstream-core-<task>/."""
    task = path.relative_to(root).parts[0]
    owner = root.resolve().parts[-3:-1]
    return "upstream-core" if task.startswith("upstream-core") or owner == ("upstream-core", "build") else "desktop"


def read_results(roots: list[Path]) -> dict[tuple[str, str, str], str]:
    """(scope, class, method) -> passed | skipped | failed, from every TEST-*.xml below the result roots."""
    results: dict[tuple[str, str, str], str] = {}
    files = 0
    for root in roots:
        if not root.is_dir():
            continue
        for path in sorted(root.glob("*/TEST-*.xml")):
            files += 1
            try:
                tree = ET.parse(path)
            except ET.ParseError as error:
                raise GateInputError(f"unreadable JUnit XML {path}: {error}") from error
            top = tree.getroot()
            suites = [top] if top.tag == "testsuite" else list(top.iter("testsuite"))
            for suite in suites:
                for case in suite.iter("testcase"):
                    key = (scope_of(root, path), case.get("classname") or suite.get("name") or "", case.get("name") or "")
                    if case.find("failure") is not None or case.find("error") is not None:
                        status = "failed"
                    elif case.find("skipped") is not None:
                        status = "skipped"
                    else:
                        status = "passed"
                    # One identity reported twice counts with its worst result.
                    if RANK[status] >= RANK.get(results.get(key, "passed"), 0):
                        results[key] = status
    if files == 0 or not results:
        raise GateInputError("no JUnit results found; refusing an empty gate")
    return results


def grouped(keys) -> dict[str, list[str]]:
    """{"<scope>:<class>": [methods]} keeps the committed baseline readable."""
    out: dict[str, list[str]] = {}
    for scope, cls, name in sorted(keys):
        out.setdefault(f"{scope}:{cls}", []).append(name)
    return out


def ungrouped(value) -> set[tuple[str, str, str]]:
    if not isinstance(value, dict):
        raise GateInputError("baseline identities must be a scope:class -> methods map")
    out = set()
    for key, names in value.items():
        scope, sep, cls = key.partition(":")
        if not sep or scope not in ("desktop", "upstream-core") or not isinstance(names, list):
            raise GateInputError(f"invalid baseline identity group {key!r}")
        out.update((scope, cls, name) for name in names)
    return out


def git(repo: Path, *args: str) -> str:
    result = subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True,
                            encoding="utf-8", errors="replace")
    if result.returncode != 0:
        raise GateInputError(f"git {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def top_level_entries(repo: Path, revision: str) -> dict[str, str]:
    entries = {}
    for line in git(repo, "ls-tree", revision).splitlines():
        meta, name = line.split("\t", 1)
        entries[name] = meta.split()[2]
    return entries


def upstream_test_sources(repo: Path, revision: str) -> dict[str, str]:
    """Top-level test class -> path, for test sources outside desktop/ (the upstream-original tests)."""
    index: dict[str, str] = {}
    paths = [p for p in git(repo, "ls-tree", "-r", "--name-only", revision).splitlines()
             if "/src/test/" in p and p.endswith((".kt", ".java")) and not p.startswith(("desktop/", ".github/"))]
    for path in paths:
        text = git(repo, "show", f"{revision}:{path}")
        package = re.search(r"^package\s+([\w.]+)", text, re.M)
        prefix = package.group(1) + "." if package else ""
        stem = Path(path).stem
        index.setdefault(prefix + stem, path)
        for name in re.findall(r"^\s*(?:(?:internal|private|public|open|abstract|final)\s+)*class\s+(\w+)", text, re.M):
            index.setdefault(prefix + name, path)
    return index


def make_baseline(args) -> int:
    repo = Path(args.repo).resolve()
    results = read_results([Path(r) for r in args.results])
    failing = {key for key, status in results.items() if status == "failed"}
    skipped = {key for key, status in results.items() if status == "skipped"}
    sources = upstream_test_sources(repo, args.sha)
    origin = {}
    desktop_owned = []
    for _, cls, _ in sorted(failing):
        top = cls.split("$", 1)[0]
        if top in sources:
            origin[cls] = sources[top]
        else:
            desktop_owned.append(cls)
    if desktop_owned:
        print("Refusing a baseline with failing desktop-owned tests (they must pass):", file=sys.stderr)
        for cls in sorted(set(desktop_owned)):
            print("  " + cls, file=sys.stderr)
        return 1
    upstream = top_level_entries(repo, args.upstream_commit)
    baseline = top_level_entries(repo, args.sha)
    same = {name: oid for name, oid in upstream.items() if baseline.get(name) == oid}
    document = {
        "schema": SCHEMA,
        "gate": "A",
        "rule": "No new failing identity, no missing or newly skipped baseline identity, new tests pass, same upstream trees.",
        "baselineSha": args.sha,
        "upstream": {"tag": args.upstream_tag, "commit": args.upstream_commit, "trees": dict(sorted(same.items()))},
        "source": args.source,
        "command": args.command,
        "generator": "python desktop/tools/gate_a.py baseline (Root only)",
        "counts": {"tests": len(results), "failed": len(failing), "skipped": len(skipped)},
        "failingSources": dict(sorted(origin.items())),
        "failing": grouped(failing),
        "skipped": grouped(skipped),
        "identities": grouped(results),
    }
    Path(args.output).write_text(json.dumps(document, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"Gate A baseline {args.sha}: {len(results)} tests, {len(failing)} failing (all upstream-original), "
          f"{len(skipped)} skipped, {len(same)} unchanged upstream entries -> {args.output}")
    return 0


def check(args) -> int:
    repo = Path(args.repo).resolve()
    document = json.loads(Path(args.baseline).read_text(encoding="utf-8"))
    if document.get("schema") != SCHEMA or document.get("gate") != "A":
        raise GateInputError("not a gate A baseline")
    identities = ungrouped(document["identities"])
    base_failing = ungrouped(document["failing"])
    base_skipped = ungrouped(document.get("skipped", {}))
    if not identities or not base_failing <= identities or not base_skipped <= identities:
        raise GateInputError("inconsistent baseline identities")
    trees = document["upstream"]["trees"]
    if not trees:
        raise GateInputError("baseline records no upstream trees")
    head = top_level_entries(repo, "HEAD")
    changed = sorted(name for name, oid in trees.items() if head.get(name) != oid)

    results = read_results([Path(r) for r in args.results])
    targeted = args.targeted
    new_failures = sorted(key for key, status in results.items() if status == "failed" and key not in base_failing)
    absent = sorted(key for key in identities - base_skipped if key not in results)
    skipped_now = sorted(key for key in identities - base_skipped if results.get(key) == "skipped")
    # The only rule a targeted run waives: a baseline identity that was not run.
    missing = skipped_now if targeted else sorted(absent + skipped_now)
    fixed = sorted(key for key in base_failing if results.get(key) == "passed")
    still = sorted(key for key in base_failing if results.get(key) == "failed")
    added = sorted(key for key in results if key not in identities)
    added_skipped = [key for key in added if results[key] == "skipped"]
    # A baseline identity reported only under another scope did not run where the baseline ran it. A full run also
    # counts it missing; a targeted run, which does not judge absence, needs this to keep the scopes apart.
    absent_names = {(cls, name) for _, cls, name in absent}
    scope_changed = [key for key in added if key[1:] in absent_names]
    passed = not (new_failures or missing or changed or added_skipped or scope_changed)
    report = {
        "gate": "A",
        "mode": "targeted" if targeted else "full",
        "passed": passed,
        "fullGateEvidence": passed and not targeted,
        "candidateSha": git(repo, "rev-parse", "HEAD").strip(),
        "baselineSha": document["baselineSha"],
        "upstream": {"tag": document["upstream"].get("tag"), "commit": document["upstream"].get("commit"),
                     "changedTrees": changed},
        "counts": {"tests": len(results),
                   "failed": sum(1 for s in results.values() if s == "failed"),
                   "skipped": sum(1 for s in results.values() if s == "skipped"),
                   "baselineTests": len(identities), "baselineFailed": len(base_failing),
                   "newFailures": len(new_failures), "missingOrSkipped": len(missing),
                   "fixedSinceBaseline": len(fixed), "stillFailingFromBaseline": len(still),
                   "addedSinceBaseline": len(added), "addedSkipped": len(added_skipped),
                   "scopeChanged": len(scope_changed),
                   "absentBaselineNotJudged": len(absent) if targeted else 0},
        "scopeChanged": [{"scope": s, "class": c, "name": n} for s, c, n in scope_changed],
        "addedSkipped": [{"scope": s, "class": c, "name": n} for s, c, n in added_skipped],
        "newFailures": [{"scope": s, "class": c, "name": n} for s, c, n in new_failures],
        "missingOrSkipped": [{"scope": s, "class": c, "name": n} for s, c, n in missing],
        "fixedSinceBaseline": [{"scope": s, "class": c, "name": n} for s, c, n in fixed],
        "stillFailingFromBaseline": [{"scope": s, "class": c, "name": n} for s, c, n in still],
    }
    if args.report:
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    c = report["counts"]
    print(f"Gate A{' (targeted)' if targeted else ''} against baseline {document['baselineSha'][:12]}: "
          f"{c['tests']} tests, {c['failed']} failed; "
          f"new failures {c['newFailures']}, {'skipped' if targeted else 'missing/skipped'} baseline tests "
          f"{c['missingOrSkipped']}, fixed since baseline {c['fixedSinceBaseline']}, added {c['addedSinceBaseline']} "
          f"(skipped {c['addedSkipped']}), scope changed {c['scopeChanged']}, "
          f"changed upstream trees {len(changed)}")
    for title, rows in (("NEW FAILURE", new_failures), ("SKIPPED" if targeted else "MISSING OR SKIPPED", missing),
                        ("ADDED SKIPPED", added_skipped), ("SCOPE CHANGED", scope_changed)):
        for scope, cls, name in rows[:50]:
            print(f"  {title}: [{scope}] {cls} > {name}")
    for name in changed:
        print(f"  UPSTREAM CHANGED: {name} differs from the baseline's upstream; Root must replace the baseline")
    if targeted:
        print(f"  TARGETED: {c['absentBaselineNotJudged']} baseline tests were not run and not judged; "
              "this verdict is not evidence that gate A passed")
        print("Gate A targeted " + ("PASSED (not a full gate A pass)" if passed else "FAILED"))
    else:
        print("Gate A " + ("PASSED" if passed else "FAILED"))
    return 0 if passed else 1


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo", default=str(Path(__file__).resolve().parents[2]))
    sub = parser.add_subparsers(dest="mode", required=True)
    b = sub.add_parser("baseline")
    b.add_argument("--sha", required=True)
    b.add_argument("--upstream-commit", required=True)
    b.add_argument("--upstream-tag", required=True)
    b.add_argument("--command", required=True)
    b.add_argument("--source", required=True, help="where the run happened")
    b.add_argument("--results", action="append", required=True)
    b.add_argument("--output", required=True)
    c = sub.add_parser("check")
    c.add_argument("--baseline", required=True)
    c.add_argument("--results", action="append", required=True)
    c.add_argument("--report")
    c.add_argument("--targeted", action="store_true",
                   help="judge a partial run: do not require absent baseline identities (not a full gate A pass)")
    args = parser.parse_args(argv)
    try:
        return make_baseline(args) if args.mode == "baseline" else check(args)
    except (GateInputError, KeyError, ValueError, OSError) as error:
        print(f"Gate A input error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
