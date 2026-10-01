Source-only Windows window/global and ErrorState animation supplement. Target is original v0.2.3 `3d5d19a2f994daccd0e2f8b5f522b6d82f43d589`. Actual33 immutable 97-entry graph is used only for compiling two new manual files. Final `compile-02` passes 19 new classes with zero production class intersection. No HTTP, animation, Window or Root consumer has executed in this lane. The earlier native19 actual33 cohort is independent; it does not prove this supplement.

Install only the two files under `prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/`: `DesktopHomeWindowGlobals.kt` and `DesktopHomeErrorAnimation.kt`. Do not install the proof JAR, original-source copies, compiler inputs, or a second raw ErrorState/LottieUrls/renderer producer. No registry identity or dependency is added by these manual platform consumers.

The existing shared background and transition source reads `LocalDesktopHomePlatform`; the current Shell provider lacks it. Current generated calls to the general background modifiers are under HomeScreen, so the scan does not prove current Dynamic/Favorites/Tab/Dock crashes. Those other renderers' `recoverableBlurEnabled` calls alone do not read the platform local. Supplying the same window binding above all route branches is the correct place for shared modifiers and later navigation consumers. There is no exact `BackgroundSurfaceGate` declaration in the pinned source; the audited contracts are `rememberRecoverableHazeState`, the platform background listener, and the two general background modifiers.

Actual source anchors at this review:

- `Main.kt:150–193`: actual Compose Window, minimum size, real frame clock and real `window` passed into DesktopApp.
- `DesktopShell.kt:214`: actual Window-composition `rememberCoroutineScope`; the adapter's frame-clock effect is created in this same composition, not VM/IO context.
- `DesktopShell.kt:584–600`: actual window displayability/iconified visibility listeners.
- `DesktopShell.kt:1125–1150`: existing recorded graphics layer, actual `boundsInWindow`, Compose window size, foreground ownership and original global liquid configuration.
- `DesktopShell.kt:1151`: shared root CompositionLocalProvider; `1202` performs real guarded background record and normal draw.
- `DesktopOriginalHomeRoot.kt:75–77`: branch-local platform/metric/animation providers. Pass the exact same globally constructed values here; create no second bundle inside Home.
- `DesktopRepository.kt:139`: existing sole `httpClient`; `DesktopLottieAsset.kt:39` actual bounded Skottie decode; `UiSkinAssets.kt:51/91` existing local JSON renderer. The latter is not an HTTP URL consumer.
- `DesktopHomeOriginalErrorState.kt:61`: original ERROR URL, 120dp, one iteration. Original full URL/iteration body and source hash are copied in the source audit.

Required factory signatures:

```kotlin
rememberDesktopHomeActualWindowResources(
    window: java.awt.Window,
    isCurrent: () -> Boolean,
    currentThemeColor: () -> java.awt.Color,
    logger: java.util.logging.Logger,
): DesktopHomeActualWindowResources
// fields: background, metrics (including holder), clientPolicy

DesktopHomeWindowGlobals(
    resources: DesktopHomeActualWindowResources,
    homeGraphicsLayerCaptureReady: Boolean,
    errorAnimation: @Composable (String, Dp, Int) -> Unit,
    content: @Composable () -> Unit,
)

DesktopHomeLottieBinding(
    client: OkHttpClient,
    stillOwned: () -> Boolean,
    commitIfCurrent: ((() -> Unit) -> Boolean),
    onFailure: (String) -> Unit,
)
DesktopHomeActualErrorAnimation(binding, url: String, size: Dp, iterations: Int)
```

At Shell's existing theme block, use `requireNotNull(hostWindow)` for the actual original Home consumer; Main already supplies that real Window. Construct the resource bundle once with the existing app/window lifetime callback (`!isClosing()` and the retained actual Window identity). Do not include current section, Home visibility, MID or `hostVisible` in that lifetime: the background port itself observes actual current-process window visibility/focus/modal descendants. Use `java.awt.Color(scheme.background.toArgb(), true)` as the required current-theme callback, and an actual named JVM Logger. Existing helper `desktopHomeActualPlatform` queries real Compose RenderEffect support and uses explicit rectangular decorated Windows client geometry; no physical-monitor corner, Android status bar or DWM value is fabricated.

Wrap the current Shell's whole provider/content in `DesktopHomeWindowGlobals`. Its `homeGraphicsLayerCaptureReady` must come from the real record/capture owner with positive actual layer bounds/size and a completed successful recording, not a constant. The current Shell only records for enabled ADAPTIVE readability. Do not reinterpret that as an always-warm Home layer; if the original Home owns another real backdrop, supply that owner's actual receipt. Capability can initially be unavailable and update after the genuine record. The original direct Haze fallback still follows actual RenderEffect support. Normal record suppression remains the existing `excludeFromLiquidBackground` and `recordBackground` implementation; this supplement changes neither.

Inside that global scope, the Home branch can pass `LocalDesktopHomePlatform.current` and `resources.metrics.holder` into its existing Root wrapper. Pass `LocalLifecycleOwner.current` from the actual Compose Window to the required Home Environment. The metric effect inherits the actual frame clock; no constant elapsed time/frame sample is introduced. The existing `rememberDesktopDynamicReduceMotion` remains the sole Windows SPI binding.

Construct ONE Lottie binding alongside the retained Home data owner, with **that owner's exact capturedEpoch/isCurrent/commitIfCurrent** callbacks and `repository.httpClient`. Do not create another repository/client, skin package, settings/account store or URL cache. Its composable callback is `{ url, size, count -> DesktopHomeActualErrorAnimation(binding, url, size, count) }`; use it in the global provider and the branch Root wrapper. It fetches the unchanged original URL in 64KiB chunks under caller cancellation/owner checks, with the existing decoder's 32MiB resource limit. Actual Skottie decodes and renders the fetched body; real Compose frame timestamps advance its original size/iteration slot. Window-background/lifecycle pauses reuse the same existing background listener. Load failure leaves the original empty animation slot and emits owned feedback; no substitute animation is fabricated. Actual remote JSON was not fetched here. Existing Skottie rejects external-image assets, and broader Lottie engine/text/image compatibility is not proved by compilation.

Retirement order is required. In the existing `beforeStoreFreeze` / explicit app shutdown / backup beforeRestore path, first invalidate/capture the retained owner and its resource references, then release the SessionStore/app admission monitor. **Outside that monitor**, close the owned Home media lifetime, Lottie binding, and window resource bundle (use NonCancellable IO for native joins). Await the retained owner's existing jobs before reinstalling a new epoch. Only then invoke the existing plugin/store freeze and exit/restore sequence. The current `DesktopPluginRuntime.shutdownForRestore` invokes `beforeStoreFreeze` before taking its configuration/player locks and before `store.freezeWrites`. Never call media close from `withCurrentDynamicCacheOwner` or another locked commit callback. Page covering/route changes do not retire the retained Home VM/planner; UI disposal closes only its composition resources, wallpaper pauses via original `isTopLevelActive`, and account/restore/app close retire the captured owner.

Transition binding is a separate required navigation port. The original `VideoCardTransitionClock` is already an actual class. Original `rememberVideoCardTransitionClock` merely remembers that class; its Android-free body can be selected by the existing sole Home producer if Root wants the function. Do not produce a new clock model, another Animatable, constant zero phase or another navigation driver. Current flat Shell `openVideo` is not an original clock driver. Root's retained navigation owner must construct/drive the same original clock/state, source route/key/bounds/snapshot, gesture/return/exposure and motion fields, provide them above route backgrounds, and pass them to HomeRoot. The window/global adapter deliberately has no transition clock field or navigation actor. Full shared transition navigation/visual acceptance stays pending until that real consumer is mounted and verified.

The existing `DesktopOriginalHomePreferences.create(store, scope, defaultTabletUseSidebar, isMobileNetwork)` still requires two separate actual platform inputs. The window `clientPolicy` supplies only edge-to-edge/client background/metrics; this supplement adds neither a network observer nor a device-width classifier. Original `SettingsManager.kt:7602` uses `defaultTabletUseSidebar(isLargeScreenOrFoldableConfiguration(context))`; the original pure classification is `smallestScreenWidthDp >= 600 || hasHingeAngleSensor` (`FoldableDisplayPolicy.kt:234–237`). It is a device/configuration classification, not current window width. Root must document its actual Windows device/display mapping for that initial default; an explicit saved original sidebar preference continues to win. Original `NetworkUtils.isMobileData` reads active network transport `CELLULAR` (`NetworkUtils.kt:38–42`), not metered cost. The same global preference owner's `data_saver` mode 0 and 2 retain their original explicit disabled/enabled semantics, while mode 1 needs a real active cellular transport getter. No desktop network implementation was found by this source scan. Do not fill that required callback with a guessed false or replace cellular with metered. See `prefs-platform-port-review.json` for exact source pins and scan scope. These two ports remain pending Root platform bindings, independent of the two install files.
