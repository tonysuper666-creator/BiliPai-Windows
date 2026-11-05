# Settings search focus structural check

Baseline PR61: `39730a3b595e27c65365526eb2ae7226f44b8078`. This is a test-only correction: the upstream search result now submits the target, focus ID, non-legacy setting ID and query before navigating. The test also protects both the nearby-device and ordinary-result branches. It checks source structure; it does not execute the UI or establish Windows runtime behavior.

From the repository root, with the previously cached Kotlin 2.4.0 and JUnit 5.13.4 dependencies and a JDK:

```sh
python3 desktop/verification/ton-1085-settings-search/verify.py /tmp/ton-1085-search-evidence
```

Use a nonexistent output directory. The script returns 0 when the exact expected failure identities and zero skipped tests are observed. It extracts unchanged source and the baseline test patch from the fixed Git commit; the corrected run uses the checked-out desktop patch. The standalone JUnit runner exits 1 for each suite because seven unrelated failures remain: baseline 25 tests / 8 failures, corrected 25 / 7. The removed failure is `settingsSearchSubmitsResultFocusBeforeOpeningDetailPage()`; the other seven are unchanged and appear by identity in the XML and logs. Five isolated negative fixtures remove the focus, query, legacy filter, ordering, or nearby route; each restores exactly that failure. Raw JUnit XML and command/exit logs are in `evidence/`.

This does not change Gradle registration; Root owns the four-test registration diff at `desktop/verification/ton-1085-settings-resource/registration.diff` and integration. Gradle, Windows, and full-suite execution remain unverified here.
