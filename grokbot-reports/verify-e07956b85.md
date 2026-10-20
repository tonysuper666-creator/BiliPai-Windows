# VERIFY desktop/integration-263-claude-main@e07956b85e1c46912c9fff49247aaf6a5d49aef8

- Linux, openjdk version "21.0.12.1" 2026-08-18 LTS, Python Python 3.13.5, 34 min for the Gradle run (clean worktree, JS-worker and diagnostic stubs, Linux only)
- Gradle: BUILD FAILED in 34m 37s, exit 1; kotlin errors (e:) 0; 'shadowed by a member' warnings 0
- Evidence: Linux only. Linux pass is not a Windows validation and not a hardware proof.

## JVM
classes: 646  tests: 5280  failed: 15  errors: 0  skipped: 5
known JS-worker classes failing (expected on Linux): 3
OTHER failing classes: 7
  com.android.purebilibili.core.plugin.feed.PublicContentNetworkTest (1)
  com.android.purebilibili.core.ui.wallpaper.WallpaperTransformPolicyTest (1)
  com.bilipai.desktop.data.DesktopDynamicCacheTest (1)
  com.bilipai.desktop.plugins.DesktopPreferenceReplacementRetryWindowsTest (5)
  com.bilipai.desktop.ui.DesktopDynamicAccountStorageTest (2)
  com.bilipai.desktop.ui.DesktopWindowsFollowConfirmationDialogTest (1)
  com.bilipai.desktop.update.DesktopUpdateMonitoringTest (1)

## log tail

[Incubating] Problems report is available at: file:///workspace/v-e07956b85/desktop/build/reports/problems/problems-report.html

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':test'.
> There were failing tests. See the report at: file:///workspace/v-e07956b85/desktop/build/reports/tests/test/index.html

* Try:
> Run with --scan to get full insights from a Build Scan (powered by Develocity).

BUILD FAILED in 34m 37s
171 actionable tasks: 170 executed, 1 from cache
EXIT=1

## Python
- tests/test_sync_upstream.py: ---------------------------------------------------------------------- Ran 74 tests in 78.245s  OK 
- tests/test_validate_source_cohort.py: ---------------------------------------------------------------------- Ran 25 tests in 0.084s  OK (skipped=1) 
- tests/test_package_source_qualification.py: ---------------------------------------------------------------------- Ran 14 tests in 0.008s  OK 
- tests/test_symbol_closure.py: ---------------------------------------------------------------------- Ran 99 tests in 242.513s  OK (skipped=2) 
- tests/test_v046_*_source.py: original settings keys 84 {"wholeScreens": 2, "managerClosureMembers": 351, "managerLines": 1892, "outputs": 9, "sourcePins": 13, "sourceProofSha256": "759c57717b7945c365fd5d56346ebf7223b1b846251e80ef6f8b4bc2640d3dd8", "
- tests/test_v04[0-5]_*_source.py: 58 source outputs original settings keys 84 58 source outputs original settings keys 84 
- tests/test_gradle_generated_outputs_clean.py: ---------------------------------------------------------------------- Ran 4 tests in 0.009s  OK 
- tests/test_third_party_notices_source.py: ---------------------------------------------------------------------- Ran 5 tests in 0.024s  OK 
- nvidia/test_nvidia_*.py: ---------------------------------------------------------------------- Ran 323 tests in 0.986s  OK 
- ux/test_ux_*.py: 58 source outputs original settings keys 84 58 source outputs original settings keys 84 
