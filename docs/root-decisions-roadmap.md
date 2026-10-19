# Root decisions for the upstream roadmap (2026-10-18)

Source: `roadmap-20261018.md` section 5. Basis: the user's standing preferences (4K desktop, low memory, desktop-native feel, original liquid-glass default, compile only when the feature set is complete) and "你来 全部授权". Each line can be overruled by the user.

| # | Decision | Effect on the roadmap |
|---|---|---|
| D1 card open/return transitions | Out of scope for now. They are phone-style motion; revisit after the desktop UX design. | `nav-card-transition-family` (#52) and the transition parts of #20, #42, #55 are deferred. |
| D2 typography baseline | Keep the current text sizes (4K readability is a stated product concern; Ctrl zoom exists). Take token definitions only where they do not change the active sizes. | `fnd-theme-typography-accent-tokens` (#7) must not swap the baseline typography. |
| D3 image-cache budgets | Reject the larger 0.3.8 budgets (user asked for lower memory). | #9 skips the budget part. |
| D4 comment-closure branch | Accepted into the integration line: branch `desktop/integration-263` now contains `desktop/source261-comment-closure-compile-repair` (merge 3804042af) and the NVIDIA line. | #24 later drops the compat shims. |
| D5 dock vs sidebar | Desktop-native sidebar/icon rail is the direction (final shape comes from the UX design). Do not invest in dock consumers now. | `chrome-dock-consumers` (#42) deferred. |
| D6 Haze 2.0.1 | Defer; keep adapting call sites. | #58 stays T4. |
| D7 readability4j / jsoup | No new Gradle dependency without explicit user approval. | #37 and #57 deferred. |
| D8 audio waveform | Defer; it must never risk WASAPI exclusive output. | #59 stays T4. |
| D9 wallpaper scope | Foundation and adjust sheet only, later (T2). No Root-level backdrop for every page. | #45 later; #56 deferred. |
| D10 dynamic category merge | Accept the migration when #31 is reached, with an up-to-all mapping in Android-backup import and updated tests. | none now |
| D11 PiliPlus GPL font/icon notices | Do #2 early (read-only first). The user should know this is a compliance item; not legal advice. | #2 in the first batch. |
| D12 validation batching | Linux compile and JVM checks through the user's Grok Bot after each batch of slices (cheap, no local build); one Windows cloud validation when the feature set is complete. | each batch ends with a push for the bot. |

Working rules for every slice implemented from this roadmap: source only, no local compile, Python checks required, no AI attribution lines in commits (AGENTS.md), LF endings, protected paths untouched (`.github/**`, `desktop/native/**`, `desktop/third-party/**`, vendored upstream roots), never relax a test or pin to make something pass.
