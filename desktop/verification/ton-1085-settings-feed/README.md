# TON-1085: feed setting title and switch contract

Baseline PR61: `58b8072d5f734a02396ddd8bb61f91a3a72eb05b`. The target test is `SettingsRootCategoryContentStructureTest.feedApiSection_usesNativeSwitchAndSliderItems()`.

The product obtains the unchanged Chinese title from `SettingsAdditionalDirectory.kt` via `settingItemTitle` and the combined directory in `PlaybackSettingDirectory.kt`; the switch, checked state and callback remain in `ui/SettingsSections.kt`. The upstream structural test still looked for the literal inside the section. This test-only patch checks the actual title lookup, directory membership, exact ID/title, switch, checked state and callback. Existing switch, slider, divider and other assertions remain.

Run from repository root with the existing local Gradle dependency cache and JDK:

```sh
python3 desktop/verification/ton-1085-settings-feed/verify.py /tmp/ton-1085-feed-validation
```

The destination must not exist. The script compiles the baseline and working-tree patched test, runs standalone JUnit on fixed baseline sources, checks the total of 25 tests and exact failure identities, and exits 0 only on the expected results. Real run: script exit 0; baseline JUnit exit 1, 7 failures; corrected JUnit exit 1, 6 failures. Both have zero skipped tests, and the only removed failure is the target test. Five isolated mutations (title, lookup ID, checked value, callback, directory membership) each restore exactly that failure and leave the six existing failures unchanged. Raw JUnit XML and logs are in `evidence/`.

This is isolated structural evidence, not Gradle registration, a complete test suite, runtime/UI behavior, or Windows validation. Root owns the outstanding four-test registration and PR merges; see `desktop/verification/ton-1085-settings-resource/registration.diff`.
