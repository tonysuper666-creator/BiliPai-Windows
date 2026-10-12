#!/usr/bin/env python3
"""Root-only real CLI/linked-worktree tests; local shared objects, no constant overrides.
All new refs/files/state/worktrees stay in one new Temp fixture; nothing is deleted.
"""
import argparse, datetime as dt, hashlib, importlib.util, io, json, os, subprocess, sys, tempfile, time, unittest
from pathlib import Path
MIN = "a969341e22d42e965e03861cc275df2bb9fa38f0"
EXPECTED_COORDINATOR_SHA = "919905da0847478af5543992a4afea0ac16d517069c50accee566b7738ed3e22"
ORIGIN = "https://github.com/tonysuper666-creator/BiliPai-Windows.git"
CASE_NAMES = ["test_real_registration_and_duplicate_records", "test_old_and_legacy_baselines_rejected", "test_writer_root_conflicts_and_readonly_overlap", "test_scope_deviation_tracks_rename_and_untracked_file", "test_dirty_delivery_then_clean_delivery_still_holds_scope", "test_expired_scope_requires_explicit_release", "test_malformed_registry_is_never_rewritten", "test_real_os_lock_blocks_registry_registration", "test_wrong_origin_and_other_common_dir_rejected", "test_adopt_clean_scoped_commit_binds_real_head_and_preserves_baseline", "test_adopt_committed_out_of_scope_and_dirty_results_reject_without_rewrite", "test_future_file_and_descendant_scopes_conflict_in_both_directions", "test_index_only_out_of_scope_change_remains_visible_when_worktree_is_restored"]
HOLDER = r'''
import importlib.util, sys, time
from pathlib import Path
source, state, entered, release = map(Path, sys.argv[1:])
spec = importlib.util.spec_from_file_location("actual_coordinator_for_lock", source)
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
assert module.MIN_BASE == "a969341e22d42e965e03861cc275df2bb9fa38f0"
with module.locked(state):
    entered.write_text("actual production lock held", encoding="utf-8")
    deadline = time.monotonic() + 7
    while not release.exists() and time.monotonic() < deadline: time.sleep(.02)
'''
def need(ok, text):
    if not ok: raise AssertionError(text)
def sha(value): return hashlib.sha256(value).hexdigest()

def run(command, cwd=None, stdin=None):
    process = subprocess.Popen(list(map(str, command)), cwd=cwd, stdin=subprocess.PIPE if stdin is not None else None, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    stdout, stderr = process.communicate(stdin)
    return process.returncode, stdout.decode("utf-8"), stderr.decode("utf-8")

def git(repo, *args, stdin=None, expected=0):
    command = ["git", "--no-optional-locks", "-c", "core.fsmonitor=false", "-c", "core.hooksPath=" + str(CTX["emptyTemplate"]), "-c", "commit.gpgsign=false", "-c", "gc.auto=0", "-c", "maintenance.auto=false", "-C", str(repo), *args]
    code, stdout, stderr = run(command, stdin=stdin)
    need(code == expected, "fixture Git failed: " + " ".join(args[:2]) + ": " + stderr)
    return stdout.strip()

def new_worktree(name, commit=None, repo=None, branch=None):
    repo = repo or CTX["repo"]; path = CTX["fixture"] / "worktrees" / name
    path.parent.mkdir(parents=True, exist_ok=True)
    git(repo, "worktree", "add", "-b", branch or "fixture/" + name, str(path), commit or CTX["accepted"])
    need(path.resolve().is_relative_to(CTX["fixture"]), "fixture worktree containment")
    return path

class ActualCoordinatorTests(unittest.TestCase):
    def setUp(self):
        self.state = CTX["fixture"] / "states" / self._testMethodName
        self.state.parent.mkdir(parents=True, exist_ok=True)
        self.cli("init", "--root-repo", CTX["repo"], "--accepted-head", CTX["accepted"], "--reserved", "prefix:core", "--reserved", "file:tools/shared.py")
    def cli(self, *args, success=True, json_output=True):
        command = [sys.executable, CTX["coordinator"], "--state-dir", self.state, *args]
        code, stdout, stderr = run(command)
        CTX["calls"].append({"arguments": list(map(str, args)), "exitCode": code, "stdout": stdout, "stderr": stderr})
        self.assertEqual(code, 0) if success else self.assertNotEqual(code, 0)
        return json.loads(stdout) if json_output and success else (code, stdout, stderr)
    def register(self, name, scope="file:ui/One.kt", readonly=False, worktree=None, base=None, expires=None):
        worktree = worktree or new_worktree(self._testMethodName + "-" + name)
        args = ["register", "--module", name, "--agent", "fixture-agent", "--thread", "fixture-thread", "--worktree", worktree, "--base-sha", base or CTX["accepted"], "--scope", scope]
        if readonly: args.append("--read-only")
        if expires: args.extend(["--expires-at", expires])
        return worktree, self.cli(*args)
    def registry(self): return (self.state / "registry.json").read_bytes()
    def reject_unchanged(self, *args):
        before = self.registry(); result = self.cli(*args, success=False, json_output=False)
        self.assertEqual(before, self.registry()); return result

    def test_real_registration_and_duplicate_records(self):
        worktree, record = self.register("display-ui")
        status = self.cli("status")
        self.assertEqual(status["config"]["minimumBase"], MIN)
        self.assertEqual(status["config"]["origin"], "tonysuper666-creator/BiliPai-Windows")
        self.assertEqual(Path(record["worktree"]), worktree.resolve())
        self.assertEqual(record["branch"], git(worktree, "branch", "--show-current"))
        self.assertEqual(status["processLiveness"], "NOT_INSPECTED")
        self.reject_unchanged("register", "--module", "other-module", "--worktree", worktree, "--scope", "prefix:docs", "--read-only")
        second = new_worktree(self._testMethodName + "-second")
        self.reject_unchanged("register", "--module", "display-ui", "--worktree", second, "--scope", "file:ui/Two.kt")

    def test_old_and_legacy_baselines_rejected(self):
        old = new_worktree(self._testMethodName + "-old", CTX["oldTiny"])
        git(old, "merge-base", "--is-ancestor", MIN, CTX["oldTiny"], expected=1)
        code, _, error = self.reject_unchanged("register", "--module", "old", "--worktree", old, "--base-sha", CTX["oldTiny"], "--scope", "prefix:ui")
        self.assertIn("old or unaccepted baseline", error)
        legacy = new_worktree(self._testMethodName + "-legacy", branch="desktop/windows/alpha9")
        _, _, error = self.reject_unchanged("register", "--module", "legacy", "--worktree", legacy, "--scope", "prefix:ui")
        self.assertIn("legacy alpha9", error)

    def test_writer_root_conflicts_and_readonly_overlap(self):
        self.register("display", scope="prefix:ui")
        conflict = new_worktree(self._testMethodName + "-conflict")
        self.reject_unchanged("register", "--module", "duplicate-ui", "--worktree", conflict, "--scope", "file:ui/One.kt")
        _, audit = self.register("audit", scope="prefix:ui", readonly=True)
        self.assertTrue(audit["readOnly"])
        reserved = new_worktree(self._testMethodName + "-reserved")
        self.reject_unchanged("register", "--module", "root-write", "--worktree", reserved, "--scope", "file:core/Root.kt")
        self.assertEqual(len(self.cli("status")["records"]), 2)

    def test_scope_deviation_tracks_rename_and_untracked_file(self):
        worktree, record = self.register("rename")
        git(worktree, "mv", "ui/One.kt", "ui/Renamed.kt")
        (worktree / "outside").mkdir(); (worktree / "outside/Leak.kt").write_text("fixture untracked", encoding="utf-8")
        code, stdout, _ = self.cli("check", "--record-id", record["id"], success=False, json_output=False)
        check = json.loads(stdout)
        self.assertFalse(check["ok"]); self.assertTrue(check["dirty"])
        self.assertIn("ui/One.kt", check["changedPaths"])
        self.assertEqual(set(check["scopeDeviations"]), {"ui/Renamed.kt", "outside/Leak.kt"})

    def test_dirty_delivery_then_clean_delivery_still_holds_scope(self):
        worktree, record = self.register("deliver")
        (worktree / "ui/One.kt").write_text("fixture scoped change\n", encoding="utf-8")
        self.reject_unchanged("deliver", "--record-id", record["id"])
        git(worktree, "add", "--", "ui/One.kt"); git(worktree, "commit", "-m", "scoped fixture delivery")
        delivered = self.cli("deliver", "--record-id", record["id"])
        self.assertTrue(delivered["ok"]); self.assertFalse(delivered["dirty"])
        self.assertEqual(delivered["headSha"], git(worktree, "rev-parse", "HEAD"))
        ledger = self.cli("status")["records"][0]
        self.assertEqual(ledger["state"], "DELIVERED"); self.assertEqual(ledger["delivery"]["qualification"], "NOT_ASSESSED")
        self.assertEqual(ledger["delivery"]["changedPaths"], ["ui/One.kt"])
        other = new_worktree(self._testMethodName + "-other")
        self.reject_unchanged("register", "--module", "still-held", "--worktree", other, "--scope", "file:ui/One.kt")

    def test_expired_scope_requires_explicit_release(self):
        expiry = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=2)).isoformat()
        _, record = self.register("expired", expires=expiry)
        deadline = time.monotonic() + 5
        while dt.datetime.now(dt.timezone.utc) <= dt.datetime.fromisoformat(expiry):
            self.assertLess(time.monotonic(), deadline); time.sleep(.02)
        before = self.registry()
        _, stdout, _ = self.cli("check", "--record-id", record["id"], success=False, json_output=False)
        self.assertTrue(json.loads(stdout)["expired"]); self.assertEqual(before, self.registry())
        other = new_worktree(self._testMethodName + "-other")
        self.reject_unchanged("register", "--module", "blocked", "--worktree", other, "--scope", "file:ui/One.kt")
        self.cli("release", "--record-id", record["id"], "--reason", "fixture owner explicitly released")
        self.register("after-release", worktree=other)
        rows = self.cli("status")["records"]
        self.assertEqual(len(rows), 2); self.assertEqual(rows[0]["state"], "RELEASED")

    def test_malformed_registry_is_never_rewritten(self):
        _, record = self.register("preserved")
        valid = self.registry(); value = json.loads(valid)
        unknown = dict(value, schema=999)
        malformed = json.loads(valid); malformed["records"]["11111111-1111-1111-1111-111111111111"] = {"id": "11111111-1111-1111-1111-111111111111", "module": "unknown"}
        for bad in (b"{", json.dumps(unknown).encode(), json.dumps(malformed).encode()):
            (self.state / "registry.json").write_bytes(bad)
            for args in (("status",), ("release", "--record-id", record["id"], "--reason", "must not discard unknown state")):
                self.cli(*args, success=False, json_output=False); self.assertEqual(bad, self.registry())
        (self.state / "registry.json").write_bytes(valid)
        self.assertEqual(self.cli("status")["records"][0]["state"], "ACTIVE")

    def test_real_os_lock_blocks_registry_registration(self):
        worktree = new_worktree(self._testMethodName + "-waiter")
        entered = CTX["fixture"] / "lock-entered"; release = CTX["fixture"] / "lock-release"
        holder = subprocess.Popen([sys.executable, str(CTX["holder"]), str(CTX["coordinator"]), str(self.state), str(entered), str(release)], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        waiter = None; before = self.registry()
        try:
            deadline = time.monotonic() + 5
            while not entered.exists(): self.assertLess(time.monotonic(), deadline); time.sleep(.02)
            waiter = subprocess.Popen([sys.executable, str(CTX["coordinator"]), "--state-dir", str(self.state), "register", "--module", "lock-waiter", "--worktree", str(worktree), "--scope", "prefix:ui"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            time.sleep(.3); self.assertIsNone(waiter.poll()); self.assertEqual(before, self.registry())
            release.write_text("release actual OS lock", encoding="utf-8")
            stdout, stderr = waiter.communicate(); self.assertEqual(waiter.returncode, 0, stderr.decode())
            self.assertEqual(json.loads(stdout)["module"], "lock-waiter")
        finally:
            release.write_text("bounded fixture release", encoding="utf-8")
            if waiter is not None: waiter.communicate()
            _, error = holder.communicate(); self.assertEqual(holder.returncode, 0, error.decode())

    def test_wrong_origin_and_other_common_dir_rejected(self):
        worktree = new_worktree(self._testMethodName + "-origin")
        try:
            git(CTX["repo"], "remote", "set-url", "origin", "https://github.com/jay3-yy/BiliPai.git")
            self.reject_unchanged("register", "--module", "upstream-origin", "--worktree", worktree, "--scope", "prefix:ui")
        finally: git(CTX["repo"], "remote", "set-url", "origin", ORIGIN)
        other = CTX["fixture"] / "foreign-common"
        git(CTX["fixture"], "clone", "--shared", "--no-checkout", "--template", str(CTX["emptyTemplate"]), str(CTX["repo"]), str(other))
        git(other, "remote", "set-url", "origin", ORIGIN)
        foreign = new_worktree(self._testMethodName + "-foreign", repo=other)
        _, _, error = self.reject_unchanged("register", "--module", "foreign", "--worktree", foreign, "--scope", "prefix:ui")
        self.assertIn("same own repository", error)

    def test_adopt_clean_scoped_commit_binds_real_head_and_preserves_baseline(self):
        worktree = new_worktree(self._testMethodName + "-scoped")
        (worktree / "ui/One.kt").write_text("fixture committed scoped result\n", encoding="utf-8")
        git(worktree, "add", "--", "ui/One.kt"); git(worktree, "commit", "-m", "existing scoped fixture result")
        head = git(worktree, "rev-parse", "HEAD")
        args = ["register", "--module", "adopted", "--worktree", worktree, "--base-sha", CTX["accepted"], "--scope", "file:ui/One.kt"]
        self.reject_unchanged(*args) # Ordinary registration still requires HEAD == base.
        record = self.cli(*args, "--adopt-committed")
        self.assertEqual(record["baseSha"], CTX["accepted"])
        check = self.cli("check", "--record-id", record["id"])
        self.assertTrue(check["ok"]); self.assertFalse(check["dirty"])
        self.assertEqual(check["headSha"], head); self.assertEqual(check["changedPaths"], ["ui/One.kt"])
        delivery = self.cli("deliver", "--record-id", record["id"])
        self.assertEqual(delivery["headSha"], head)
        self.assertEqual(self.cli("status")["records"][0]["delivery"]["baseSha"], CTX["accepted"])

    def test_adopt_committed_out_of_scope_and_dirty_results_reject_without_rewrite(self):
        outside = new_worktree(self._testMethodName + "-outside")
        (outside / "docs/audit.md").write_text("fixture committed outside declared scope\n", encoding="utf-8")
        git(outside, "add", "--", "docs/audit.md"); git(outside, "commit", "-m", "outside adopted scope")
        _, _, error = self.reject_unchanged("register", "--module", "outside", "--worktree", outside,
            "--base-sha", CTX["accepted"], "--scope", "file:ui/One.kt", "--adopt-committed")
        self.assertIn("existing commits exceed", error)
        dirty = new_worktree(self._testMethodName + "-dirty")
        (dirty / "ui/One.kt").write_text("fixture clean scoped commit\n", encoding="utf-8")
        git(dirty, "add", "--", "ui/One.kt"); git(dirty, "commit", "-m", "scoped but then dirty")
        (dirty / "ui/One.kt").write_text("fixture pending change after commit\n", encoding="utf-8")
        self.reject_unchanged("register", "--module", "dirty", "--worktree", dirty,
            "--base-sha", CTX["accepted"], "--scope", "file:ui/One.kt", "--adopt-committed")

    def test_future_file_and_descendant_scopes_conflict_in_both_directions(self):
        pairs = (("file:future-node", "file:future-node/child.kt"),
                 ("file:future-node/child.kt", "file:future-node"),
                 ("file:future-node", "prefix:future-node/branch"),
                 ("prefix:future-node/branch", "file:future-node"))
        for index, (firstScope, secondScope) in enumerate(pairs):
            first, record = self.register("future-" + str(index), scope=firstScope)
            second = new_worktree(self._testMethodName + "-second-" + str(index))
            self.assertFalse((first / "future-node").exists()); self.assertFalse((second / "future-node").exists())
            _, _, error = self.reject_unchanged("register", "--module", "blocked-" + str(index),
                "--worktree", second, "--scope", secondScope)
            self.assertIn("write scope conflicts", error)
            self.cli("release", "--record-id", record["id"], "--reason", "fixture explicit release before reverse pair")

    def test_index_only_out_of_scope_change_remains_visible_when_worktree_is_restored(self):
        worktree, record = self.register("index-only", scope="file:ui/One.kt")
        outside = worktree / "docs/audit.md"; original = outside.read_bytes()
        outside.write_text("fixture staged outside declared scope\n", encoding="utf-8")
        git(worktree, "add", "--", "docs/audit.md")
        outside.write_bytes(original)
        self.assertEqual(git(worktree, "diff", "--name-only", CTX["accepted"]), "")
        self.assertEqual(git(worktree, "diff", "--cached", "--name-only", CTX["accepted"]), "docs/audit.md")
        before = self.registry()
        _, stdout, _ = self.cli("check", "--record-id", record["id"], success=False, json_output=False)
        check = json.loads(stdout)
        self.assertFalse(check["ok"]); self.assertTrue(check["dirty"])
        self.assertIn("docs/audit.md", check["scopeDeviations"]); self.assertEqual(before, self.registry())
        self.reject_unchanged("deliver", "--record-id", record["id"])


def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--source-repo", type=Path, required=True); parser.add_argument("--coordinator", type=Path, required=True); parser.add_argument("--coordinator-sha", default=EXPECTED_COORDINATOR_SHA); parser.add_argument("--fixture-dir", type=Path)
    args = parser.parse_args(); coordinator = args.coordinator.resolve()
    need(args.coordinator_sha == EXPECTED_COORDINATOR_SHA, "test source is bound to the reviewed production coordinator SHA")
    need(sha(coordinator.read_bytes()) == args.coordinator_sha, "actual coordinator source SHA mismatch")
    need(('MIN_BASE = "' + MIN + '"').encode() in coordinator.read_bytes(), "production minimum constant must remain unchanged")
    temp = Path(tempfile.gettempdir()).resolve()
    fixture = args.fixture_dir.resolve() if args.fixture_dir else Path(tempfile.mkdtemp(prefix="bp-coordinator-tests-"))
    need(fixture.is_relative_to(temp) and fixture != temp, "fixture must be a dedicated Temp child")
    if args.fixture_dir: need(not fixture.exists(), "fixture output already exists"); fixture.mkdir(parents=True)
    global CTX
    CTX = {"fixture": fixture, "coordinator": coordinator, "emptyTemplate": fixture / "empty-template", "calls": []}
    CTX["emptyTemplate"].mkdir(); (fixture / "worktrees").mkdir(); (fixture / "states").mkdir()
    source = args.source_repo.resolve()
    sourceBefore = {"head": git(source, "rev-parse", "HEAD"), "statusSha256": sha(git(source, "status", "--porcelain=v1", "-z").encode())}
    repo = fixture / "root-repo"; CTX["repo"] = repo
    git(fixture, "clone", "--shared", "--no-checkout", "--template", str(CTX["emptyTemplate"]), str(source), str(repo))
    git(repo, "remote", "set-url", "origin", ORIGIN); git(repo, "config", "user.name", "Coordination Fixture"); git(repo, "config", "user.email", "fixture@example.invalid")
    git(repo, "read-tree", "--empty")
    for path in ("ui/One.kt", "core/Root.kt", "docs/audit.md", "tools/shared.py"):
        file = repo / path; file.parent.mkdir(parents=True, exist_ok=True); file.write_text("tiny fixture\n", encoding="utf-8")
    git(repo, "add", "--", "ui/One.kt", "core/Root.kt", "docs/audit.md", "tools/shared.py")
    tree = git(repo, "write-tree"); oldParent = git(repo, "rev-parse", MIN + "^")
    CTX["accepted"] = git(repo, "commit-tree", tree, "-p", MIN, "-m", "tiny accepted coordination fixture")
    CTX["oldTiny"] = git(repo, "commit-tree", tree, "-p", oldParent, "-m", "tiny old baseline fixture")
    git(repo, "update-ref", "refs/heads/fixture/root", CTX["accepted"]); git(repo, "symbolic-ref", "HEAD", "refs/heads/fixture/root")
    need(not git(repo, "status", "--porcelain=v1", "-z"), "tiny fixture Root must be clean")
    CTX["holder"] = fixture / "actual-lock-holder.py"; CTX["holder"].write_text(HOLDER, encoding="utf-8", newline="\n")
    stream = io.StringIO(); suite = unittest.TestSuite(ActualCoordinatorTests(name) for name in CASE_NAMES)
    result = unittest.TextTestRunner(stream=stream, verbosity=2).run(suite)
    sourceAfter = {"head": git(source, "rev-parse", "HEAD"), "statusSha256": sha(git(source, "status", "--porcelain=v1", "-z").encode())}
    report = {"passed": result.wasSuccessful() and sourceBefore == sourceAfter, "cases": result.testsRun, "failures": len(result.failures), "errors": len(result.errors), "methods": CASE_NAMES, "sourceBefore": sourceBefore, "sourceAfter": sourceAfter, "sourceUnchanged": sourceBefore == sourceAfter, "coordinatorSha256": args.coordinator_sha, "minimumRealCommit": MIN, "localTinyAcceptedCommit": CTX["accepted"], "localTinyOldCommit": CTX["oldTiny"], "fixtureDirectory": str(fixture), "CLIInvocations": len(CTX["calls"]), "constantOverridden": False, "appExecuted": False, "networkAccessed": False, "hardwareQualified": False, "applicationParity": False}
    (fixture / "unittest-output.txt").write_text(stream.getvalue(), encoding="utf-8")
    (fixture / "cli-transcript.json").write_text(json.dumps(CTX["calls"], ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (fixture / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2)); return 0 if report["passed"] else 1
if __name__ == "__main__": raise SystemExit(main())
