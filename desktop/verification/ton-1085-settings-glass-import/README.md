# TON-1085: liquid-glass structure contract

Baseline: PR #61 `b4ebdcebf65ee141b203e144561fb381e315416c`. The companion test patch checks the same visible slider titles through the settings directory, preserves the import/share actions and labels, and checks the current swipe hint and pager image binding. No product or upstream source was edited. The directory lookup and image renderer changed in desktop-applied source; direct single-line source literals had become stale.

From the repository root, with the pinned upstream source and dependencies already cached:

```sh
python3 desktop/verification/ton-1085-settings-glass-import/verify.py ../settings-glass-import-run7 > ../settings-glass-import-run7.log 2>&1
```

Driver exit: 0. It compiles and runs the actual patched Kotlin JUnit class in isolated fixtures, not the full Gradle suite. For each variant JUnit exits 1 because the separate appearance test remains red. Baseline: 5 tests, 2 failures; corrected: 5 tests, 1 failure; skipped, missing, and new failures: 0. The exact identities and stack traces are in the XML and logs under `evidence/`.

The five negative fixtures reverse import labels, redirect import, enable it during import, change the visible directory title, and remove the page's image model. Each returns the repaired test to failure while retaining the pre-existing failure. The whitespace-only positive fixture stays green for the repaired test. Patches were checked against the source SHA and independently by the build's unique-context matching algorithm and `patch(1)`.

This is isolated JUnit verification only. Gradle registration, the full suite, Windows, and UI behavior remain unverified; Root owns registration and merging.
