"""Read-only source identity and same-base XML comparison for TON-1004.

Run after the documented candidate command. Never changes production or test inputs.
The baseline snapshot was captured before changing DesktopBgmDetailRoot.
"""
from collections import Counter
from pathlib import Path
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent


def save(name, value):
    (OUT / name).write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n")


def digest(path):
    return hashlib.sha256((ROOT / path).read_bytes()).hexdigest()


def snapshot():
    rows = []
    for path in sorted((ROOT / "desktop/build/test-results").glob("*/*.xml")):
        for case in ET.parse(path).getroot().findall("testcase"):
            status = ("failed" if case.find("failure") is not None or case.find("error") is not None
                      else "skipped" if case.find("skipped") is not None else "passed")
            rows.append({"task": path.parent.name, "class": case.get("classname"),
                         "name": case.get("name"), "status": status})
    return rows


baseline = json.loads((OUT / "baseline-tests.json").read_text())
candidate = snapshot()
save("candidate-tests.json", candidate)


def identities(rows, status=None):
    return {(r["task"], r["class"], r["name"]) for r in rows
            if status is None or r["status"] == status}


delta = {"baseline": dict(Counter((r["task"] + ":" + r["status"]) for r in baseline)),
         "candidate": dict(Counter((r["task"] + ":" + r["status"]) for r in candidate)),
         "missing": sorted(identities(baseline) - identities(candidate)),
         "added": sorted(identities(candidate) - identities(baseline))}
for status in ("failed", "skipped"):
    delta[status + "_added"] = sorted(identities(candidate, status) - identities(baseline, status))
    delta[status + "_removed"] = sorted(identities(baseline, status) - identities(candidate, status))
save("test-delta.json", delta)
assert not delta["missing"]
assert not delta["failed_added"] and not delta["failed_removed"]
assert not delta["skipped_added"] and not delta["skipped_removed"]
assert len(delta["added"]) == 3
assert all(row[1] == "com.bilipai.desktop.ui.DesktopBgmDetailMigrationTest" for row in delta["added"])
assert delta["candidate"]["test:passed"] == 8

screen = "com/android/purebilibili/feature/audio/bgm/BgmDetailScreen.kt"
vm = "com/android/purebilibili/feature/video/viewmodel/VideoCommentViewModel.kt"
generated = "desktop/build/generated/bgm-detail/"
build = (ROOT / "desktop/build.gradle.kts").read_text()
producer = (ROOT / "desktop/tools/extract-upstream-bgm-detail.py").read_text()
actual_screen = (ROOT / (generated + screen)).read_text()
actual_vm = (ROOT / (generated + vm)).read_text()
canonical = (ROOT / ("app/src/main/java/" + screen)).read_text()
old_patch = (OUT / "old-screen.patch").read_text()
assert len(re.findall(r"^@@ ", old_patch, re.M)) == 13
assert re.search(r'^\s*"' + re.escape(screen) + '" to ', build, re.M)
assert 'kotlin.srcDir(layout.buildDirectory.dir("generated/bgm-detail/com"))' in build
assert not (ROOT / ("desktop/upstream-core/patches/app/src/main/java/" + screen + ".patch")).exists()
assert screen not in (ROOT / "desktop/upstream-core/m4c/retired-generated.tsv").read_text()
assert "video_cache.select_vm(original[path])" in producer
assert "v046_comments.advance_bgm_comment_content" in producer or "advance_bgm_comment_content" in producer
apis = ["composerDrafts", "beginCommentDraft", "updateCommentDraft"]
assert all(api in canonical and api not in actual_vm for api in apis)
assert "commentViewModel.clearForVideoChange(); viewModel.refresh()" in actual_screen
paths = ["desktop/build.gradle.kts", "desktop/tools/extract-upstream-bgm-detail.py",
         "desktop/tools/v036_video_comment_cache.py", "desktop/tools/v046_comment_list.py",
         "app/src/main/java/" + screen, generated + screen, generated + vm,
         "desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopBgmDetailRoot.kt",
         "desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopBgmDetailMigrationTest.kt"]
save("source-identity.json", {
    "base": "0cb58c3fe890f7a88860acb03fafc3e1b0fdacd8",
    "base_tree": "81c03ae092d61a4e7b20edc6072699495593df33",
    "old_pr56_head": "6cac3abc0011e087a59e8629ec7c2a1d3fe48506",
    "screen_source_set": "desktop/build.gradle.kts:3693 generated/bgm-detail/com",
    "screen_producer": "desktop/tools/extract-upstream-bgm-detail.py:358-378; emit:184",
    "content_only_advancement": "desktop/tools/v046_comment_list.py:446 BgmDetailContent only",
    "generic_vm_selection": "desktop/tools/extract-upstream-bgm-detail.py:281 v036_video_comment_cache.select_vm",
    "missing_actual_generic_vm_apis": apis,
    "whole_screen_migrated": False,
    "sha256": {p: digest(p) for p in paths},
})
print(json.dumps(delta, ensure_ascii=False, indent=2))
