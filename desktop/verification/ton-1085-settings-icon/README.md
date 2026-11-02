# Settings icon duplicate detection

The upstream test treats an `icon =` expression as ending at the end of the line. Two distinct multiline `rememberThemeAwareSettingsIcon` calls therefore collapse to the same function name. In WebDAV's group, “测试连接” selects `ms_cloud_done_24` / `MiuixIcons.UploadCloud`; “恢复最新备份” selects `ms_settings_backup_restore_24` / `MiuixIcons.Reset`. The product icons are distinct in both themes. This is a desktop test-adaptation defect, not evidence of a product defect.

The patch collects the whole parenthesized expression and tests each theme-specific resource for duplicates. Unrecognized theme-aware expressions fail explicitly; other expressions still use equality checks. It changes no product source or build registration.

## Reproduction

From the repository root, with Java, `patch`, Python 3 and the same local Gradle dependency cache available:

```sh
python3 desktop/verification/ton-1085-settings-icon/verify.py /tmp/settings-icon-evidence-NEW
```

Use a path that does not already exist. The script checks every copied source against fixed pre-change commit `62659bdbcacfddf009c56de5e68fa5caa1cd764b`, applies the corresponding baseline or checked-out patch, independently verifies that all patch hunks have exactly one context match, compiles the actual test with cached Kotlin/JUnit jars, and runs the test using the included XML runner. It deliberately preserves the remaining failures. `verify.py` exits 0 only when the exact expected sets and three counterexamples are observed. Each JUnit invocation exits 1 because the two unrelated tests remain red. This is a standalone JVM test, not Gradle or Windows validation.

| Invocation | Tests | Failed | Skipped | Result |
| --- | ---: | ---: | ---: | --- |
| Baseline | 5 | 3 | 0 | includes `settings groups avoid duplicate setting icons()` |
| Corrected | 5 | 2 | 0 | target passes; no new failures |
| Duplicate Material resource only | 5 | 3 | 0 | target fails |
| Duplicate Miuix resource only | 5 | 3 | 0 | target fails |
| Duplicate ordinary icon expression | 5 | 3 | 0 | target fails |

The remaining identities are `appearance settings expose one ui style selection while keeping miuix scaffold()` and `liquid glass adjustment uses live miuix backdrop and commits on release()`. Raw XML and process logs from the recorded run are under `evidence/`. The runner exit code was 0. Root still owns the four-test registration proposal in `desktop/verification/ton-1085-settings-resource/registration.diff`; no full suite, Gradle registration, Windows result or merge is claimed here.
