# Bundled Miuix navigation (Android)

Source: compose-miuix-ui/miuix, miuix-nav-android 0.9.4-5c91d5e5-SNAPSHOT.
The exact published sources are bundled under the upstream Apache-2.0 license.
Android expect/actual declarations are resolved to ordinary Android functions.

Local changes: optional per-host predictive-back release policy, frozen for each
session; peak and applied (anchored) progress tracking. BiliPai enables this only for
video card returns. Floating cards can accept completion or cancellation signals on
release; explicit expansion back toward detail cancels. The opt-in adapter drains
queued progress before cancellation decisions, and stale session work cannot drive
or pop a newer gesture. Other routes retain the upstream cancellation/completion
behavior, and discrete back actions still complete immediately.

The app uses this module instead of the published miuix-nav artifact so the patch
is reproducible in CI and local builds. Keep the other Miuix modules at the matching
version when updating these sources.

Upgrade review (2026-10-02): the pinned `5c91d5e5` source is nine commits after
`v0.9.4`, including the navigation focus fix. The three subsequent upstream main
commits do not modify `miuix-nav`; changing to `0.9.4` would roll back newer fixes.
The app uses Miuix's entry lifecycle and ViewModel stores, so it does not need
`lifecycle-viewmodel-navigation3`. The app's `navigation3` package name is a
historical name, not an AndroidX runtime dependency.

See [the navigation upgrade review](UPGRADE_REVIEW.md)
for verified release claims, deep-link integration, and validation limits.
