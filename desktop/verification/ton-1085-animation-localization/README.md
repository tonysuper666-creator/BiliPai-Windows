## TON-1085 animation settings localization slice

Base: PR #61 `44749655072e8b3897cba9b9e9a7e24ba2deeab7` (based on PR #59 `2789bb2713877a68252387f74ca8e4b9edbcc6bc`). The upstream source moved animation labels into Android resources. Four existing structural tests now check the exact resource text and binding together with the original toggle and import operations. The return-gesture explanatory text changed upstream from “移动” to “倾斜”; the new assertion checks the actual Chinese text and resource reference. The predictive-back test is deliberately unchanged: upstream changed its control and behavior, so this patch does not claim to resolve it.

Run from repository root with Java, javac, `patch`, and the versions of Kotlin/JUnit cached in Gradle as specified in `verify.py`:

```sh
python3 desktop/verification/ton-1085-animation-localization/verify.py /path/to/new-evidence-directory
```

Exit 0 means all expected failure identities matched. The program invokes the JUnit Platform Jupiter engine on the full six-test class, records per-run XML and logs, and compiles each original/corrected test independently. It is a targeted standalone run, not a Gradle or Windows result. Original: 6 tests, 5 failures; corrected: 6 tests, 1 failure (unchanged predictive-back identity); three independent negative fixtures each add back the transition-blur failure by changing, respectively, its resource text, title binding, or checked state. Nothing is skipped.

`registration.diff` is a proposal for Root: both the previous `SettingsCoreCombineOrderTest` correction and this `AnimationSettingsScreenStructureTest` correction must be registered together in `correctedUpstreamTestPaths`. It is intentionally not applied. Until registration, Gradle runs the originals and cannot validate the corrected tests. Full app-core, `:test`, Windows, and overall remaining failures remain unverified.
