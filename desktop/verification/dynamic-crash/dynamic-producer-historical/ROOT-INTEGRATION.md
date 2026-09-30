# Dynamic settings / real consumer handoff

This prepared slice implements exactly two original user settings: incremental timeline refresh and dynamic LIST/WATERFALL layout. It preserves the existing recommendation-source/count authority and current Windows DynamicCard/actions. It does not claim complete Home/Dynamic settings, all original tabs, user rail, image viewer or Home card/layout parity. `FIELD-AUDIT.md` and `field-audit.json` list the remaining source-driven gaps.

All paths below are relative to the repository. No main source, shared Gradle/build/source inventory, HWND, real account or actual HTTP socket was changed or used by this slice.

## Minimal production payload

Copy these new files from `prepared/` after byte verification:

- `desktop/tools/extract-upstream-dynamic-settings.py`
- `desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopDynamicTimelineSettings.kt`
- `desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicTimeline.kt`
- `desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopDynamicTimelineSettingsTest.kt`
- `desktop/tools/tests/test_dynamic_timeline_sources.py`

Two existing consumers have precise prepared replacements / `consumer.patch`. `git apply --check` passed against the captured actual current bytes; independently compare SHA before applying. No Shell/build/manifest file is replaced:

| Target | Base SHA256 bytes | Desired SHA256 bytes |
|---|---|---|
| `desktop/src/main/kotlin/com/bilipai/desktop/ui/CommunityDynamicScreens.kt` | `95be2e43f7d458be9f3f9b1950ac8234be8adeeddd57a04a6d2c3d76dbfec446` | `e6a771658f863550d7c4e386dc0689e02ede9390c14818b94124a693c2e85cac` |
| `desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopHomeRecommendationSettings.kt` | `b9ca1f12fee689230e4f156371b973ccc8fe2263a2a51ecda5c86a2fbeebf25b` | `4d8c287d5aa60f24551515cf103278a403c43af78471f963c6371b0a7a939fa4` |

The existing CommunityDynamicFeed signature and all remaining detail/composer/card/action helpers stay unchanged. It now uses the new platform state and original fetch/page/layout functions; the real callback is `community.dynamicFeed(requestType,offset,baseline).data`. BiliApiException converts only to the original API response code/message; CancellationException always propagates. Account ownership captures a plain immutable MID and epoch, and rechecks both actual live repository fields around each returned page.

## Root store / CompositionLocal seam

Use the existing single Root global pluginStore, created after the current startup storage boundary succeeds. Do not create a guest/account fallback store or migrate the existing discovery feed_api authority:

```kotlin
val dynamicTimelinePreferences = remember(pluginStore) {
    DesktopDynamicTimelinePreferences(DesktopPluginContext(pluginStore))
}
CompositionLocalProvider(
    // Existing Root providers stay here.
    LocalDesktopDynamicTimelinePreferences provides dynamicTimelinePreferences,
) {
    // Existing ReadyShell content, current theme/density and navigation stay here.
}
```

`DesktopDynamicTimelinePreferences(context: DesktopPluginContext)` reads/writes exactly original `settings/incremental_timeline_refresh` (false) and `settings/dynamic_feed_layout_mode` (0 WATERFALL, 1 LIST, unknown WATERFALL) using the same global shared backing. It rejects corrupt settings namespaces and frozen restore generations. No owned scope/lifecycle callback is added. The original SettingsManager schema/getters/setters remain generated source; the adapter maps original DataStore edit to atomic shared namespace updates.

`DesktopHomeRecommendationSettings(discovery,onFailure,modifier)` now appends the two original controls only when this Root ambient exists. Dynamic feed requires the ambient explicitly so a missing real consumer registration cannot masquerade as a working toggle. Existing original settings category/helper/icon dependencies are reused. The current settings Tree/Home route should receive this wrapper exactly where the existing two recommendation fields are already mounted.

The new timeline state signature is internal `DesktopDynamicTimelineState(type: String, fetchPage: suspend (String,String,String)->DynamicFeedResponse, stillOwned:()->Boolean={true})`; it owns no independent network client. `DesktopDynamicTimelineFeed(state,preferences,onLogin,transform,oldContentDividerLabel,row)` receives the real existing blocked-UP transform and existing card renderer. The scope/scroll memory uses the existing browse-memory keys plus actual MID/epoch/type/revision. LIST applies the original `FeedVerticalStaggeredGrid` manual prepend anchor; WATERFALL lets the original staggered grid own lane retention. Geometry uses original max1840dp/min360dp/18dp horizontal/10dp vertical. The small native header/empty text uses original AppText so the Miuix theme is effective.

## Source registration and build fragments

`source-inventory.json` has 14 unique original identities, 6 direct full files + 8 source extractions. Only 12 are new against the current original inventory. `SettingsManager.kt` and `SettingsSections.kt` already exist: append the new feature tag `settings-home-dynamic-parity` to their existing entries; do not duplicate identities or replace the whole inventory. Six direct files are copied by existing prepareUpstreamSources and should not also be emitted into the production generated directory.

No new resources, vectors, Gradle dependencies or licenses are required. The existing semantic refresh/feed icons, sibling tint palette, original component helpers, original immutable models and shared coroutine/Compose dependencies are reused.

Minimal Gradle fragments for Root to insert into its current file (not a frozen whole-file build patch):

```kotlin
val extractUpstreamDynamicSettings by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamSettingsCategories, extractUpstreamSettingsHome)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-dynamic-settings.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/dynamic-settings").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-dynamic-settings.py", "tools/extract-upstream-plugins.py",
        "tools/extract-upstream-media.py", "tools/extract-appearance-platform.py",
        "tools/extract-upstream-settings-home.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "settings-home-dynamic-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/dynamic-settings"))
}
// Inside the existing main source set:
kotlin.srcDir(layout.buildDirectory.dir("generated/dynamic-settings"))
// Existing task registration:
tasks.named("compileKotlin") { dependsOn(extractUpstreamDynamicSettings) }
```

Production invocation omits `--standalone` and emits eight extracted files; the six full direct sources come from existing preparation. Standalone verification emitted all14 to avoid touching main. Python test regenerates14 into a task temporary directory and validates original body/hash/allowed fetch bindings/determinism. Root can include it in existing unittest discovery. The focused regular Kotlin test is `com.bilipai.desktop.ui.DesktopDynamicTimelineSettingsTest` (13 real methods).

## Verified behavior / limits

The active standalone cohort is `classes-attempt5`. It used actual immutable diagnostics main snapshot manifest SHA `5e8fc360372557bf004b55a1ded9fe4be1a3b6bae33090cb037d8fe600006fda`, actual main-kotlin SHA `faff5ddd9bf61b4ee50b500119f7734228925e891342f9cf3008a6e552b5cd7f`, main-java SHA `a7206eb9cbbdcb16285c8ca49a4a6b5865378adfd449f8f733cce21b325a29f1`, resources SHA `843e19310a97a3d9426dc218f2019313d43466cff089e831cdbeebdb191fdca1`, and all231 pinned runtime jars. Only the two declared existing consumer class families are overridden; Root persistence/repository/API/theme/component classes are loaded from the actual product jar.

- 13 focused JUnit methods pass: real shared temp-disk default/read/write/error/frozen namespace cases; original first-page baseline + multi-page update count and old-tail append; no-overlap replacement; disabled replacement; folded payload / blocked filtering; append de-duplicate / advancing empty pages; API-risk error retry; cancellation publication / mutex release; same-MID epoch retirement; source cache-placeholder exclusion and stable sort.
- Both M3/Miuix original switch/popup actual Press/Release reach real shared disk then actual consumer geometry. WATERFALL centers at 74/467/860 (M3) or 76/469/862 (Miuix), LIST 74/74/74 or 76/76/76, with no transport reload from layout changes. Actual refresh click sends baseline3, merges4/3/2/1, renders original divider; disabling then refresh sends empty baseline and replaces with9. Ten PNGs and actual traces are in `proof/`.
- A separate actual product DesktopRepository/CommunityRepository/original Retrofit DynamicApi application-interceptor fixture proves real query mapping and shared task-only CookieJar identity. Requests have offsets `empty,empty,increment-page-2,old-tail,empty` and baselines `empty,3,empty,empty,empty`; no network/DNS/socket is reached, no real credentials/host account are read.
- Four Python source-identity checks pass. `consumer.patch` passes `git apply --check`; main target bytes still match captured baselines.

The original repository updates its pagination registry per returned page. Later multi-page failure/cancellation is not an all-or-nothing cursor transaction; this is retained original behavior, not secretly repaired or claimed by cancellation tests. This slice does not execute Root Shell navigation, actual authenticated Bilibili HTTP, actual native HWND or a shared Gradle build. It does not finish original Home card layout/motion/carousel/background/settings, article/UP tabs, dynamic visibility/order/user rail/image layout/top chrome, cached feed/local not-interested/pinned users, or full original DynamicCard rendering.
