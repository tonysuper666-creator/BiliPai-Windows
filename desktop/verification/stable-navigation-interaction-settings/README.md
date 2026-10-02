# Original navigation and card animation settings

The Windows settings tree now opens the original navigation category's two entries. Five dock/search controls and two card animation controls use selected upstream UI and exact SettingsManager getters/setters on Root's existing global settings store. Home observes that same store. The detail pages own bounded scrolling; search acknowledges only the controls present in this slice.

The normal desktop project compiled successfully and its exact `DesktopNavigationInteractionSettingsTest` class passed all 6 JUnit tests. The 3 newly generated files matched the prospective originals byte for byte. The earlier isolated UI proof covers Material 3/Miuix, light/dark, 900×720, and 48 actual pointer events; it overrides the declared Tree family and does not prove the complete Root's rendered effects.

Root corrected the handoff's source inventory assumption: BottomBarSettingsScreen and AnimationSettingsScreen were absent, so these two pinned identities were added. The total increases from 1172 to 1174. SettingsSections and SettingsManager retain their existing identity and gain only the new feature annotation. Identity counts do not measure functional completion.

Navigation ordering, labels, top/search tabs, sidebar and remaining animation settings are still incomplete. Appearance and Plugins already had working category admission and were preserved. Android gesture/haptic behavior and whole Root visual effects remain unaccepted. These source changes are not yet in the desktop package built from commit `6fd5bbd804f272650942f5a445385ba5428ffa9b`.

`artifact-index.json` indexes the copied raw evidence. `prepared/freeze-manifest.json` is the original local manifest; its two compiled fixture JARs stay local and are explicitly excluded in `integration-summary.json`. The normal integrated JUnit XML/log are under `root/`. No runtime JAR, native DLL or package is duplicated here.
