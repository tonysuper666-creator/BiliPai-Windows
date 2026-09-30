# Original Home recommendation settings: two real fields

This isolated draft selects the original FeedApiSection recommendation source and refresh-count controls, preserving the exact original calls, palette/icon declarations, single-choice component, slider summary/range/steps, and option resolver. It binds the two controls to the same existing DesktopDiscoveryRepository instance used by the real recommendation screen. It does not bind or claim the original dynamic-layout/tab/order/image controls.

No main file, shared Gradle, account, network request, or HWND was changed. The current Root category list/header and popup integration remain separately owned. This draft uses the frozen prior product snapshot `f864fc80a93a58c54235988c3cc4cdfd2140e417f44ec26f3c7187c77b981883`, not a claim that the current Root integration has been packaged or visually verified.

Copy `extract-upstream-settings-home.py` to `desktop/tools/` and the thin `DesktopHomeRecommendationSettings.kt` to `desktop/src/main/kotlin/com/bilipai/desktop/settings/` only when Root integrates. Run the extractor with `--repo <repo> --output <generated-directory>`; product output contains exactly two files, DesktopHomeRecommendationFields.kt and DesktopFeedApiSegmentOptions.kt. Standalone output additionally contains exact original SettingsSelectionComponents.kt, which should be registered as a direct source in product. The inventory has three reviewed LF-hash entries. SettingsSections.kt and PlaybackSettingsSelectionPolicy.kt are existing source identities: merge the feature into those existing entries, do not add duplicate paths or duplicate direct outputs. SettingsSelectionComponents.kt is the one new direct original source. No new dependency or resource asset is needed.

The original `SettingsManager.FeedApiType` reference in the option resolver is bound solely to existing `DesktopFeedSettings.FeedApiType`, already generated from the original SettingsManager by the discovery-platform extractor. No parallel enum, state schema, JSON protocol, or settings store is introduced. All options are the exact values/labels consumed by current DesktopDiscoveryRepository requests.

This slice shares the single original SettingsCardGroup and SettingsAdaptiveDivider owned by the category extractor. Pipeline's PrivacySection slice will give Root the minimal `private -> internal` visibility adapter for SettingsAdaptiveDivider; apply that once to the category extractor, not a second helper/FQN. The isolated fixture applies only this expected visibility adapter to its own copy. Do not register `fixture-category-helpers` or `reference-only` sources into product.

Root call contract (same main module):

```kotlin
internal fun DesktopHomeRecommendationSettings(
    discovery: DesktopDiscoveryRepository,
    onFailure: (Throwable) -> Unit,
    modifier: Modifier = Modifier,
)
```

Pass Root's existing shared discovery instance, not a newly constructed instance or a local copied flow. The binding observes the real feedMode and refreshCount StateFlows and serializes writes through existing repository setters. Root must surface onFailure through its actual error state/snackbar; failed persistence is not optimistically represented as a changed value. The original settings renderer locals should remain as for the root/category integration (FILLED icon treatment; original CARD versus FLAT group presentation). The two controls can be placed in the existing Home settings content while the remaining original controls are accurately tracked as absent. This is a partial source selection, not a completed original HOME category.

Evidence: isolated Kotlin/Compose 2.4.0 compilation passed for 18 sources: the exact selected original three-file standalone Home graph, the category four-file original helper graph, nine already-owned settings-search helpers required by the fixed snapshot, thin platform binding, and fixture. All compiled bytes and fixed dependencies are frozen here. Exact token-selected source checks prove that both original control calls remain unchanged and no dynamic/inert switch was emitted.

Four actual temporary Windows repository/store cases passed without account HTTP: original option/enum identity and initial values; writing through the shared actual repository publishes its existing flow and survives a fresh disk reader; original slider normalization/summary/range/steps preserve persisted recommendation request counts; actual atomic-write failure leaves the existing field unchanged. The latter creates a directory at the settings-file target and exercises the actual production persistence failure, not a fake success callback. `proof/result.json` and logs record the result. These are executable store/flow proofs, not four new product JUnit cases or four GUI/native-window gates.

No click/slider interaction was performed in this cohort. Original single-choice and slider UI compiled, but they open genuine Desktop dialogs; native interaction needs a separately coordinated exclusive HWND lane, or a meaningful supported offscreen interaction fixture. The original 26 component native gates and six width checks are preserved separately and do not prove these Home field actions. Root should run focused product compilation after registration, then the actual original Home dialog selection/slider save and full restart persistence proof when a native UI lane is available.
