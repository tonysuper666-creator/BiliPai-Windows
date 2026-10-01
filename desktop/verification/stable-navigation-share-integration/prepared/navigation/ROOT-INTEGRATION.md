This is the complete original stable navigation3 **host/entry/transition/back-stack source closure**, not a claim that all AppNavigation destinations or Root UI have been mounted. Fixed source target remains `3d5d19a2f994daccd0e2f8b5f522b6d82f43d589`. Final candidate `compile-06` uses immutable actual40 and its exact strict97 CP; no VM/raw/Home/Dock source override or extra dependency is involved. The original 776-line BiliPaiNavDisplayHost, 778-line MiuixVideoCardNavTransition, five animation modes, full typed entry mapping, back stack/controller, route motion/content transform, original drawable retained depth layer and predictive background declarations remain present. Exact platform replacements are in source-receipt.json and inverse-checked. AppNavigationAppearancePolicy is the already installed full Home sole producer and is reference-only; the initial duplicate audit failure is retained as history.

Install only the whitelist: one sole producer `extract-upstream-navigation3-host.py` plus two manual files. Registry delta has **24 new identities** (22 direct pure/full source files plus two platform-extracted renderer files) and six existing-identity feature merges. Preserve existing modes/features and append the new feature; never replace the whole source registry. The Android physical display radius source is only a reference and is not copied or advertised as a Windows OS capability. Default production generator emits seven selected outputs; its 22 DIRECT declarations are copied once by existing prepareUpstreamSources. Standalone generation emits all 29 outputs only for isolated compile/audit.

Use the normal existing Gradle task pattern, with no new artifact or runtime identity:

```kotlin
val extractNavigation3Host by tasks.registering(Exec::class) {
    inputs.file("tools/extract-upstream-navigation3-host.py")
    // Add the 30 actual original source rows as inputs; include existing sync-upstream.py.
    outputs.dir(layout.buildDirectory.dir("generated/original-navigation3-host"))
    commandLine(pythonExecutable, "tools/extract-upstream-navigation3-host.py",
        "--repo", rootProject.projectDir.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-navigation3-host").get().asFile.absolutePath)
}
sourceSets.main { kotlin.srcDir(layout.buildDirectory.dir("generated/original-navigation3-host")) }
// Attach extractNavigation3Host to the same compileKotlin/prepare chain as existing sole producers.
```

The main project currently sees the Miuix dependency through an implementation boundary. Expose **the already present exact 2.11.0** `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel`, `lifecycle-viewmodel-compose`, and `lifecycle-runtime-compose` modules to the app compile graph. These resolve the existing actual40/97 KMP metadata/actual desktop jars; this packet must not silently upgrade versions, add another lifecycle runtime or discard existing dependency verification. Actual API classes are `androidx.lifecycle.ViewModelStoreOwner`, `ViewModelProvider.Factory`, `CreationExtras`, `LocalViewModelStoreOwner` and `LocalLifecycleOwner`. The genuine original Miuix per-entry store and saved-state extras remain the authority; the Windows bridge makes a MutableCreationExtras copy of those real keys and uses that entry's real factory, falling back to its actual KMP default factory. It does not construct a fake Application, blank ViewModel base, parallel entry registry or home data VM.

Required platform environment:

```kotlin
DesktopNavigationHostEnvironment(
    context = sameGlobalDesktopPluginContext,
    rootLifecycleOwner = actualRootWindowLifecycleOwner,
    rootViewModelStoreOwner = actualRootNavigationViewModelStoreOwner,
    rootNavigationEventOwner = actualRootNavigationEventDispatcherOwner,
    cornerRadiusQuery = actualWindowCornerQuery, // Dp?; unavailable is explicitly null
    isCurrent = actualRootWindowLife::owns,
)
```

These are actual application/window owners, retained outside drawing branches. They are **not** account-MID, Home-tab visibility or modal-local owners. Root must clear the real parent ViewModelStore and retire/dispose its NavigationEventDispatcher after closing entry jobs on app exit/restore. Its existing real keyboard/back actor feeds DirectNavigationEventInput completed events, honoring text/modal/fullscreen priority; this is not fabricated OS predictive gesture progress. On Skiko the original library's WindowNavigationEventBridge is a pass-through, so Root must supply this dispatcher rather than depending on an Android ViewTree owner. Gesture/Alt-left/Escape handling and real windows are not proven here. The lifecycle owner must track the actual existing root window STARTED/STOPPED/DESTROYED events, not a constant RESUMED state.

Windows currently has no original Android physical-display RoundedCorner measurement. Required nullable query can return null for that unavailable capability; the original host's zero-radius and effective **32.dp** fallback remain unchanged. The retained depth renderer then uses zero for its unknown native client corner, rather than inventing an OS integer. Android RenderEffect changes only to actual Compose/Skia BlurEffect with Clamp; original blur quantum, frame cache, curve, scale, scrim and stale-content checks remain. The capability gate uses the existing real `desktopDetailRenderEffectsSupported()` check. No physical screen measurement or native blur/gesture proof is claimed.

The directly callable wrapper is:

```kotlin
DesktopOriginalNavigationHost(
    environment, actualSnapshotBackStack, actualHomeSettings, actualAppNavigationSettings,
    isLightBackground, actualSystemReduceMotion, actualVideoSharedDuration,
    sameVideoCardClock, returnOwner.sourceMetadataInCapturedHost(), sameProgrammaticBackDispatcher,
    preferWholeCardReturn, actualPerformSystemBack,
    actualPrepareReturn, actualRelatedDetailReturned,
    actualPreviousReturnSessionExists, actualHostModifier,
) { actualTypedKey -> /* required actual Root destination renderer */ }
```

Every action argument is required, with no fake/default empty callback. Root derives previous-detail/related flags from the original return session and actual stack. Use the one original BiliPaiNavBackStackController/push/pop policies to update the physical SnapshotStateList, not a parallel flat route history. `VideoDetail.openId`/CID/root-target/source route and all original typed key fields remain intact. `onPrepareVideoCardSharedReturn` must mark the actual previous target through DesktopHomeReturnNavigationOwner and use the existing cover prefetch; `onRelatedVideoDetailReturned` restores the original prior session/source geometry after the real navigation settle. Never hardcode no ancestor or fixed false session state. The wrapper reads four exact original settings keys on the same global backing: click_to_play (true, and original synchronous auto_play_cache), full_screen_swipe_back_enabled (false), video_transition_realtime_blur_enabled (false), related_video_transition_enabled (true). Dynamic image-preview text reuses the existing sole DesktopDynamicCardSettings getter. Original AppNavigation appearance/predictiveEnabled/source policies are used for effective mode; no second settings authority is created.

Keep Root's retained Home entry, all four embedded VMs, Profile/Favorites/listen owners and current video/player tokens outside visibility conditionals. NavDisplay's original entry registry retains per-entry ViewModels when depth-culling covers them and clears only when an entry permanently leaves/host terminates. This packet does not retire TodayWatch, alter Runtime, bind gallery/native media, implement missing destinations or replace Shell: mount and original planner retirement must be atomic with the separately prepared actual Root factory. Root must provide a real renderer for every mounted typed key; this host closure cannot make missing pages complete.

Evidence: final compile-06 has 31 inputs/173 classes, all97 class overlap zero; proof-05 actually loads all 173 classes, exercises the original host private owner-builder with real KMP ViewModelProvider/Store/factory/extras and clear isolation, original stack/depth/gesture pure policy, and four setting defaults/writes/cache on a real temporary same-global PluginStore. No NavDisplay composition, Root owner construction, HWND, system gesture, native frame, HTTP/account request, final package or EXE is exercised. Historical compile/generation errors and the short-lived incomplete-longpath test Jar are retained as failure evidence, not current acceptance. Correct test Jar creation uses extended-path is_file to include all classes; final Jar/class bytes are excluded from install/Git.
