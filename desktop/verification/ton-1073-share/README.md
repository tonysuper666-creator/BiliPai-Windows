# Desktop share verification

`DesktopShare.copyLink` preserves its input and reports clipboard failures. `AwtDesktopShare`
uses the system clipboard in production and accepts a `java.awt.datatransfer.Clipboard`
supplier for headless behavior tests. `WindowsTextClipboard` keeps its existing Boolean
and user-facing failure contract while delegating the actual write to the same implementation.

On the fixed integration base and candidate commit, in clean checkouts and the same
environment, run under the shared heavy-build lock with `nice -n 15` and
`--max-workers=2`:

```sh
./gradlew -p desktop --no-daemon --max-workers=2 --console=plain \
  compileKotlin compileTestKotlin test upstreamDesignSourceTests \
  upstreamAppCoreSourceTests :upstream-core:test verifyUniqueClassNames --continue
```

Run the candidate's root test task with `--tests
com.bilipai.desktop.platform.share.AwtDesktopShareTest` for focused behavior evidence.
Retain exit codes and all test XML; the root test task also depends on upstream tests,
so a successful focused XML alone does not imply a successful Gradle exit. Compare the
full suites by task, class, test name and failure type. GUI/system clipboard integration
is outside this headless verification.
