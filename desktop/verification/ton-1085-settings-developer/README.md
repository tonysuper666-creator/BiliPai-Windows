# TON-1085: developer profile click contract

Baseline PR61: `9b6d4448b9f0e38e8b7e51fe33e68d03304a850d`. Target: `SettingsRootCategoryContentStructureTest.aboutDevelopers_useGithubProfilesAndClickableAvatars()`.

The About developer tile receives an `AboutDeveloper`, and clicking it uses the local URI handler to open that developer's GitHub profile URL. The upstream structural test referred to a stale local parameter name. The corrected test extracts the actual parameter and local URI handler names and checks the click-to-own-profile relationship with whitespace-tolerant matching. The existing checks for the GitHub URL, developer avatars and layout remain.

Run from the repository root with the existing local Gradle dependency cache and JDK:

```sh
python3 desktop/verification/ton-1085-settings-developer/verify.py /tmp/ton-1085-developer-validation
```

The destination must not exist. Real run: verifier exit 0; both baseline and corrected standalone JUnit exit 1 because unrelated failures remain. All 25 test identities are compared: failures 6 → 5, only the target failure is removed, none added or skipped. Four isolated mutations (empty click, wrong target, wrong handler, wrong GitHub profile URL) each restore the target failure. Two additional positive mutations (whitespace and local variable renaming) retain the same five remaining failures. Raw XML and logs are in `evidence/`.

This is isolated source-structure evidence, not Gradle registration, a complete suite, runtime/UI behavior or Windows verification. Root owns the outstanding four-test registration and PR merges; see `desktop/verification/ton-1085-settings-resource/registration.diff`.
