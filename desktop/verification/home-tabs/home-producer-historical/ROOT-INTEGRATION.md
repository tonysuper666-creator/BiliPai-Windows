# Home card layout handoff

The prepared production payload has six owned targets. `frozen-handoff.json` pins each raw byte SHA and distinguishes the single existing consumer patch from new files. No main source, shared Gradle/manifest, HWND, user files, account read or remote API was modified or used by this lane.

- `desktop/tools/extract-upstream-home-cards.py`
- `desktop/tools/tests/test_home_card_sources.py`
- `desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopHomeCardPreferences.kt`
- `desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopHomeCardGrid.kt`
- `desktop/src/test/kotlin/com/bilipai/desktop/settings/DesktopHomeCardPreferencesTest.kt`
- Existing `desktop/src/main/kotlin/com/bilipai/desktop/ui/DiscoveryScreens.kt` only through the exact `consumer.patch` / base and desired hashes.

The existing Discovery screen entry signature is unchanged. Its raw data/source keys, request/cancellation code, retained page, filters, feedback and navigation callbacks are preserved. Grid state becomes the original LazyStaggeredGridState on the same retained CommunityFeedState owner; the original FeedVerticalStaggeredGrid is already compiled in Root's new immutable c013 product and is **not** generated/overridden again by this lane. The actual card cover/title policies are bound, and the footer Row becomes FlowRow with explicit48dp minimum targets. Existing previews and original account mutations are not exercised by this fixture.

## Root hooks only

Create the new adapter from the existing global Root PluginStore after the startup storage boundary succeeds, not from a guest/account fallback. It owns no extra store, coroutine actor or lifecycle job:

```kotlin
val homeCards = remember(pluginStore) {
    DesktopHomeCardPreferences(DesktopPluginContext(pluginStore))
}
CompositionLocalProvider(LocalDesktopHomeCardPreferences provides homeCards) {
    // Existing ReadyShell, actual theme, browse memory and content remain here.
}
```

The actual Discovery grid requires this ambient and fails clearly if Root forgets it. The public settings boundary is:

```kotlin
DesktopHomeCardSettingsSection(
    preferences = homeCards,
    onFailure = onExistingSettingsFailure,
    modifier = Modifier.fillMaxWidth(),
)
```

Append that section to Root's existing Home settings route alongside the already integrated recommendation/dynamic controls. Do **not** replace DesktopHomeRecommendationSettings, Shell, Tree, CommunityDynamicScreens or the other lane's shared tab renderer/helpers. `DesktopHomeCardPreferences(context)` exposes `initialSettings()`, `settings:Flow<DesktopHomeCardSettings>` and the four exact original suspend setters. Shared global PluginStore performs atomic persistence/publication and restore-generation fencing; no additional shutdown callback is needed. A failed write reaches the supplied Root failure handler; CancellationException propagates.

## Original source / Gradle registration

`source-inventory.json` contains seven unique identities: two full direct pure policy files + five selected source extractions. `source-registration-delta.json` records five new identities and two existing identities that need a feature appended. Keep the current existing mode for SettingsManager and PlaybackSettingsSelectionPolicy; do not duplicate them or replace the current manifest. No new resource, dependency or license is required.

The generator's production default emits **five selected generated files only**. Existing prepareUpstreamSources supplies the two direct original files exactly once. `--standalone` emits both direct bodies additionally only for isolated compilation/source tests. Example Root task body:

```kotlin
val prepareHomeCardSources by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    inputs.files(
        "tools/extract-upstream-home-cards.py", "tools/extract-upstream-plugins.py",
        "tools/extract-upstream-media.py", "tools/extract-appearance-platform.py",
        "tools/extract-upstream-settings-home.py", "tools/sync-upstream.py",
    )
    // Add all original source-inventory paths relative to parent project as inputs.
    val generated = layout.buildDirectory.dir("generated/home-cards")
    outputs.dir(generated)
    commandLine("python", "tools/extract-upstream-home-cards.py", "--repo", "..", "--output", generated.get().asFile)
}
// Add generated/home-cards to main Kotlin and depend compileKotlin on prepareHomeCardSources.
// Reuse the existing original FeedVerticalStaggeredGrid/FeedPrependScrollPolicy, never emit a second copy.
```

Independent source regression command after integration: `python desktop/tools/tests/test_home_card_sources.py`. The actual new JUnit class is `com.bilipai.desktop.settings.DesktopHomeCardPreferencesTest` with ten discovered Unit methods. Root owns its shared compile/tests.

## Exact evidence and boundaries

Accepted proof is `compile-evidence.json` / the accepted `proof` directory named there. It compiles the complete prepared Discovery source and new adapters/policies/tests against immutable product c013 plus231 existing external dependency entries. It does not replace Store, theme, settings renderer, feed memory, Repository or the original grid. Runtime CodeSource/class bytes pin12 actual product classes; long-path-safe compiled inventory lists every explicit Discovery overlap. All dependency/source hashes are verified before and after.

The actual offscreen finite UI flows exercise M3/Miuix light800, dark960, dark1680 plus Compact500 in both styles. They use synthetic rows seeded into actual retained CommunityFeedMemory so no request is needed. Normal flows physically Press/Release original menus, select fixed2/width WIDE/auto/OFFICIAL, inspect actual cover geometry and locally decoded Crop pixels, preserve page3/scroll owner/offscreen anchor, and invoke actual MID700 and CID501 callbacks. Compact flows read legacy global compact2 versus wide6, prove a wide write cannot change compact geometry, then exercise the exact compact setter and original style menu. That setter call is explicitly a fixture action, not an unimplemented pinch gesture. Cold independent JVM readers reopen all final task-owned files.

Historical proof5 (5065) and proof6/7 (c013 before footer adaptation) and their crowded screenshots remain. Attempt8 proves the original M3 footer OnClick layout measured40dp; attempt9's clipped second-row assertion was a fixture bug, corrected by measuring actual SemanticsNode.size before distinguishing fully visible versus scroll-clipped targets. Neither failure is relabeled green. Final accepted flow checks the true layout size against48dp, then captures all wholly visible action bounds; no threshold was lowered.

Fixture is ImageComposeScene, no native OS window/chooser or full Main/PluginRuntime. This lane has no native packaged proof. See FIELD-AUDIT.md/field-audit.json for exact unsupported original features; source/test counts are not completion percentages.
