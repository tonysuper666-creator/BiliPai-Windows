This is an isolated prospective seven-field slice, not a completed BottomBar/Animation page.

Candidate base: 6fd5bbd804f272650942f5a445385ba5428ffa9b, pinned v0.2.3 / 3d5d19a2f994daccd0e2f8b5f522b6d82f43d589.
Actual immutable product: Main desktop/.local/stable-product-snapshot-83, exactly 101 ordered classpath entries.
No Candidate, Main production source, dependency, Gradle, registry, account, HWND or packaging mutations were made.

The earlier Appearance/Plugins category gap was a read-only audit mistake: DesktopSettingsNavigator.openCategory already invokes resolveDesktopSettingsCategoryDirectTarget, whose generated implementation maps APPEARANCE_THEME to APPEARANCE and PLUGINS_EXTENSIONS to PLUGINS. Their existing admission is unchanged and the focused route method proves it. No duplicate category patch is supplied.

Install contract:

1. Verify frozen-handoff.json and its production-target base/new SHA values. Apply only tree.patch to Candidate DesktopSettingsTree.kt; do not wholesale replace an independently edited file.
2. Install DesktopNavigationInteractionSettings.kt and extract-upstream-navigation-interaction.py. The latter supports --repo ROOT --output build/generated/navigation-interaction --inventory FILE. It emits only three selected original Kotlin files. Generated templates are verification references, not hand-written production sources or an alternate SettingsManager.
3. Register the generated directory/task and merge features into the existing four original source identities from source-inventory.json. Root owns Gradle/registry edits. There are no new dependencies or resources. Existing project license/resource attribution remains applicable to the original selected source.
4. Install optional focused DesktopNavigationInteractionSettingsTest.kt and run its exact class. The isolated runner directly executed six test methods; it does not claim a shared Gradle/JUnit XML run.

Root hooks: none. The settings consumer requires existing LocalDesktopHomeCardPreferences.current.context, already supplied by the actual Root. It writes to that same global PluginStore's settings namespace through existing homeCardVisualDataStore.updateFromSnapshot, preserving write freeze, same-backing merge and publish-after-success semantics. There is no per-account settings file/cache, second global store, new settings model or runtime/provider.

Original correspondence:

* SettingsSections.SettingsRootCategoryContent NAVIGATION_INTERACTION branch: both original SettingsDetailGroup/SettingsDetailEntrySection calls retain their original targets, copy and BOTTOM_BAR_START / ANIMATION_START focus. Only the redundant entrance wrapper is removed because Tree already owns the category scroll column.
* BottomBarSettingsContent original navigation-behavior group: floating dock, icon cross scale, dock search, merge-on-scroll and list-scoped search; the last two preserve the original conditional visibility under dock search.
* AnimationSettingsContent original two card-motion rows: card entrance and transition; original components/icons/copy/callback shape retained, with typed callbacks bound to exact SettingsManager setters.
* SettingsManager exact getters/setters and private Boolean keys are selected unchanged into one uniquely named declaration object, with only Context / settingsDataStore / key platform imports aliased. The individual navigation-icon getter defaults false while aggregate HomeSettings defaults true; this pre-existing upstream difference is explicitly retained, not silently normalized.

Consumers (source tracing, not whole Root rendered-effects acceptance):

* DesktopOriginalHomePreferences.homeSettings reads the exact decodeDesktopOriginalHomeSettings on this same global backing. Original HomeScreen consumes dock floating, icon scale, search/merge and list scoped search. DesktopOriginalRootChrome consumes dock floating; the original favorites/history/watch-later list projection uses search/scoped-search fields.
* Original HomeCategoryPage/HomeScreen consumes cardAnimationEnabled and cardTransitionEnabled. RootMount, OriginalRootStack/Chrome and RootVideoResolver read cardTransitionEnabled; article entry also preserves this source value.
* Isolated disk/observer proof uses the actual83 DesktopOriginalHomePreferences; it is not overridden. It verifies stored keys and actual observer values, not GPU/motion appearance or account/network behavior.

Scroll/focus:

Category remains in Tree's one parent scroll host. Both new Detail targets join nestedPageOwnsScroll, so their finite fillMaxSize LazyColumn receives bounded height rather than an infinite verticalScroll constraint. M3/Miuix light/dark 900x720 scenes actually clicked 48 pointers total, produced 12 PNGs, persisted seven settings and preserved unsupported focus. BOTTOM_BAR_START / BEHAVIOR and ANIMATION_START use their original focus policy. Missing TOP_TABS/DISPLAY/etc and ANIMATION_VISUAL_EFFECTS tokens are not consumed; Tree Back clears them through the existing Navigator. No false focus success is reported.

Verification scope:

* runs/05/result.json: standalone Kotlin/Compose compile against immutable actual83, explicitly declared Tree-family overlays only, plus newly selected source/platform declarations. Product Store, Navigator, SettingsDetailEntry helpers, theme renderer and Home observer are actual jar classes.
* Six directly executed methods: existing direct category routing; exact getter defaults; same-global actual Home observer and disk; failed atomic replace preserves publication; old-generation restore fence; cancelled lazy caller / focus admission. The lazy cancellation case is cancellation-before-start only, not a claim that an accepted synchronous disk operation can be undone.
* runs/05/ui/result.json: four finite offscreen scenes, true mouse events, original conditional rows and focus lifecycle. Appearance/plugins slots are fixture markers only, and their complete UI/Runtime is not claimed here.

Remaining: navigation item order/visibility, top tabs/search type ordering, labels/colors, sidebar/tablet UI, remaining animation/blur/transition settings, Android-only vibration/predictive-back capability adaptation. Full page parity and on-screen Root effect acceptance remain false. Continue these in a later slice; do not count seven switches as the entire category.
