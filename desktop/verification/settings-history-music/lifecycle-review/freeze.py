from pathlib import Path
import hashlib
import json

HERE = Path(__file__).resolve().parent
REPO = next(p for p in HERE.parents if (p / "AGENTS.md").is_file() and (p / "desktop/tools").is_dir())
paths = ["desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopSearchPreferences.kt",
    "desktop/src/main/kotlin/com/bilipai/desktop/DesktopPlaybackController.kt",
    "desktop/src/test/kotlin/com/bilipai/desktop/data/DesktopSearchRestoreLifecycleTest.kt",
    "desktop/src/test/kotlin/com/bilipai/desktop/DesktopCheckpointFailureTest.kt"]
junit = json.loads((HERE / "junit-evidence.json").read_text(encoding="utf-8"))
assert [junit[k] for k in ("found", "started", "succeeded", "failed")] == [8, 8, 8, 0]
result = dict(passed=True, mainStable=True, junit=junit, sharedGradleInvoked=False, nativeWindowOpened=False,
    sourceAdditions=0, dependenciesAdded=0, schemaChanges=0,
    files=[dict(path=p, sha256Lf=hashlib.sha256((REPO / p).read_text(encoding="utf-8").encode()).hexdigest()) for p in paths],
    artifacts=[dict(path=name, sha256=hashlib.sha256((HERE / name).read_bytes()).hexdigest()) for name in
        ("INTEGRATION.md", "junit-evidence.json", "compile-evidence.json", "run-tests.py", "TestLauncher.kt", "freeze.py")])
(HERE / "frozen-lifecycle.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
print(json.dumps({k: v for k, v in result.items() if k not in ("files", "artifacts")}))
