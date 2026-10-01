Profile Windows physical effects and original theme bridge
========================================================

This is a source-only packet. It keeps stable commit `3d5d19a2f994daccd0e2f8b5f522b6d82f43d589`. Original Profile137, URI7, Wallpaper43 and native media packets remain unchanged. Narrow validation uses immutable actual42, ordered 97 entries, with **three explicitly prospective shared source files** (PluginStore, ThemePrefs, DynamicImageAssets). It is not an actual Root/window acceptance or a zero-override run. Install the six payloads and five local hunks from install-contract.json; never overwrite the retained whole shared candidate files. Root then regenerates/compiles the whole product using its actual existing module.

Concrete retained binding
-------------------------

`createDesktopOriginalWindowsProfileBinding` in `DesktopProfileWindowsBindingFactory.kt` returns the installed `DesktopOriginalProfileBinding` and its original VM/Environment. Every argument is mandatory, without fallback/default/no-op ports:

```
context: DesktopPluginContext                       // same Runtime.context and backing
stateDirectory: Path                                // actual existing application state root
scope: CoroutineScope                               // retained Profile nav-entry child scope
owns: () -> Boolean                                 // captured immutable account epoch + entry ownership
commit: (() -> Unit) -> Boolean                      // actual SessionStore → entry atomic admission
api: BilibiliApi; spaceApi: SpaceApi
dynamicApi: DynamicApi; searchApi: SearchApi          // actual owner-tagged existing raw APIs
splash: DesktopOriginalProfileSplashProtocol
favorite: DesktopOriginalFavoriteRepository
bangumi: DesktopOriginalFavoritePgc                  // existing sole original protocol classes
csrf: () -> String?                                 // late existing owner-checked getter
accounts: DesktopProfileAccountPort                 // actual single SessionStore / playback MID
appearance: DesktopThemePrefs                       // ReadyApp's same remembered instance
configuration: StateFlow<DesktopProfileWindowConfiguration>
supportsRenderEffectBackedHaze: Boolean
applicationIconModel: Any
actualWindow: java.awt.Window
ownedCallFactory: okhttp3.Call.Factory
metadata: DesktopProfileVideoWidth
assets: DesktopDynamicImageAssets
clipboard: DesktopTextClipboard
chrome: DesktopWindowsProfileChrome
media: DesktopProfileMedia
analytics: DesktopProfileAnalytics
feedback: (String) -> Unit
diagnostic: (Throwable) -> Unit
```

There is one factory call per retained Profile entry. Hold the same entry across child video/detail covers. On real retirement/account replacement/restore, close its scope outside Store locks. Keep global appearance, image-save Assets/Locations/lifetime, clipboard and the Window chrome authority alive. Do not instantiate another account/store/client/media lifetime. `DesktopOriginalProfileHost` still receives its original 17 navigation callbacks, actual isCurrentPage/account-refresh generation/visible-history policy/skin paths and play mode/pager budget/scroll channel from the full Root navigation owner. Profile is the main navigation Profile route; Space remains a separate user route.

Physical Root bindings
----------------------

* `configuration` must reflect the actual Root client content width/height converted with the current physical Density, including resize/DPI changes. Supply the actual platform render-effect probe (same existing Home capability) and real application icon resource model. Constant fixture dimensions or an invented icon are not production bindings.
* `ownedCallFactory` is the same repository's owner-tagged shared Call.Factory for the captured entry epoch. The adapter cancels its Call when the original suspend caller Job/page/epoch retires and checks the same captured Job on each body read. Original VM closes Response with `use`. There is no second HTTP client or credentials serialization.
* `metadata` is `DesktopProfileFfprobeWidth(actualTrustedFfprobePath)`, using the existing bundled/sourced ffprobe adjacent to existing FFmpeg. Resolve through the same trusted existing binary installation, not a download, PATH guess or an extra MPV core. Source catalog and exercised hashes are in proof-09/media-inputs.json. Only local file/pipe protocols are allowed, output is at most 4 KiB, deadline is ten seconds, and all process pipes are explicitly closed on every exit.
* `assets` is the actual sole existing Root/account-owned image-save service with actual global Locations and application/window lifetime. Its new `saveProfileGalleryBytes(bytes,fileName,profileOwns,profileCommit)` reuses its save mutex, same SessionStore admission and global location collision policy, then gates the final move with the Profile entry. Root must use its non-null Locations binding; the historical explicit chooser path is retained for intentional other callers. It saves the original supplied bytes under the original JPEG display name, without creating an encoder/gallery actor.
* `clipboard` uses the existing `WindowsTextClipboard`/current LocalDesktopTextClipboard instance. Copy and feedback post to EDT and re-enter real admission there; they never wait for EDT under Store locks.
* `feedback` is Root's actual owned feedback actor. `diagnostic` is the existing diagnostic recorder, for example the same installed DesktopDiagnosticsBridge `record` call with its real privacy policy. Do not substitute a dummy logger or claim an Android upload occurred.
* `media` must reference the installed full Profile media port on the same Home media lifetime. Actual41/42 already accepted raw GIF/alignment/STARTED/once/repeat behavior. This packet does not create or reprove that playback service.

The picker opens a real single-file JFileChooser on the actual Root Window and returns a real file URI. It accepts images/GIF and the original six video extensions. Its own captured dialog is disposed on caller/page retirement; the Root Window is never disposed by picker cleanup. The proof does not open a real chooser or clipboard.

Original file flow and bounded publication
-----------------------------------------

`WallpaperImageImport.kt` is generated once from the full original 127-line file. All three functions and their copy/validate/dispatch-back cancellation control flow are retained. An exact reverse receipt reconstructs the original Git blob. Image/GIF bytes remain unchanged under `.img`; video bytes retain the original MIME/source extension and positive-width validation. Android BitmapFactory/MediaMetadataRetriever/content grants map to actual owned local Skia/fileURI/ffprobe effects.

The three import directories remain `profile_wallpaper`, `splash`, `home_wallpaper`; official downloaded images remain under `images`. Temp input/output checks the original caller Job, captured entry epoch and import generation before/after every read/write/skip. Boundaries are 32 MiB encoded image, 200 MiB encoded media and 256 MiB decoded image dimensions. A later import reservation invalidates the older one before metadata/publication. Files are written into private same-directory stages, then a same-directory Windows no-clobber move is performed under Store→entry admission. Invalid/retired/cancelled stages and the private published result when cancelled during UI dispatch-back are cleaned outside admission locks. No shared or user's selected source is deleted.

There is one Root-approved independent producer hunk changing **only** the original official download physical destination `profile_bg.jpg` to `profile_bg_<UUID>.jpg`, so repeated downloads can meet mandatory no-clobber without replacing an existing file. The original saved URI/preferences and legacy `images/profile_bg.jpg` delete remain unchanged. Previously imported/downloaded final files retain the original application's cleanup behavior; this packet does not invent a persistent media garbage collector. Its import-generation proof covers physical IO/publication, not a new cross-request ProfileVM preference arbitration policy.

Same original global theme authority
------------------------------------

Do not create a second DesktopThemePrefs or a Profile-local dark Boolean. Factory receives the existing ReadyApp `appearance`; original `DesktopOriginalProfilePreferences` delegates `setThemeMode` to `appearance.setThemeModeOwned(mode, owns, commit)`. Its same backing transaction resolves missing `dark_theme_style_v1` using the **old** `theme_mode_v2` (including legacy AMOLED=3) before changing mode, writes canonical keys and `theme_cache` together, and publishes the same existing settings Flow. The ordinary global mode/style setters receive the same original cache behavior. Startup migration and other theme/settings methods remain in their sole existing producer/actor. No source SettingsManager object is emitted twice.

ReadyApp already collects `appearance.settings` and applies DesktopAppearanceTheme globally; retain that collector and the original FOLLOW_SYSTEM/LIGHT/DARK/AMOLED model. The Windows Compose/global flow is the actual runtime counterpart of Android AppCompatDelegate. The callback captures cancellation and enters the same Store→entry admission; both canonical and cache remain unchanged if retired/cancelled/frozen. The caller Job/entry checkpoint is checked again inside the existing backing immediately before the atomic document move, after temp writing/force, and the failed private document stage is cleaned.

Window chrome lease
-------------------

Construct **one** `DesktopWindowsProfileChrome(actualWindow, existingHomeClientPolicy, currentRootThemeLight, diagnostic)` per Root Window. `currentRootThemeLight` must be a fresh getter over Root's original resolved theme and actual system theme (e.g. rememberUpdatedState), rather than a captured Boolean. Root's theme effect invokes `chrome.refreshCurrentRootTheme()`. The existing Home client policy also reads the current Root background on EDT; use that same instance. This prevents two independent writers and stale-color restoration.

The original request leases the actual decorated Window's DWM title mode; closing it restores the next active request or **current** Root theme. Control=false is the original explicit no-control branch. Acquire/restore runs on EDT and owns JNA Memory. Call/dispose outside Store locks. Actual HRESULT failures are surfaced/recorded, not false success. Windows client/theme effects are explicit platform mappings; no Android status/navigation bars are claimed.

Microsoft documents attribute 1 (`DWMWA_NCRENDERING_ENABLED`) for Get, and attribute 20 (`DWMWA_USE_IMMERSIVE_DARK_MODE`) for Set. The latter is documented on Windows 11 build 22000+, and TRUE honors OS dark mode rather than promising an independently forced dark title on every Windows generation. This packet has not accepted actual Root DWM/title/chooser behavior. See [DWM attributes](https://learn.microsoft.com/en-us/windows/win32/api/dwmapi/ne-dwmapi-dwmwindowattribute) and [DwmSetWindowAttribute](https://learn.microsoft.com/en-us/windows/win32/api/dwmapi/nf-dwmapi-dwmsetwindowattribute).

Installation and evidence
-------------------------

Append exactly one WallpaperImageImport registry row and merge the SettingsManager feature on its existing row. Install the new sole producer and register its generated Kotlin folder with Root's existing Gradle task pattern (recipe only, no new dependencies). Apply the five verified hunk snippets sequentially to the current four shared targets; install the five manual effect/factory sources. Root preserves any concurrent changes outside these exact snippets. Regenerate Profile once so the destination hunk takes effect.

Final compile-09 uses actual product module name, actual42 ordered97 and the explicit three prospective shared source overrides. ABI audit records all overlapping prospective classes; new FQN/public top-method overlap and invalid method names are zero. All produced classes load without initialization. Focused proof-09 tests the actual compiled candidate methods against synthetic local PNG/GIF/video files and private fixture Store/gallery paths: three groups, 55 assertions, no HTTP/account/user-wallpaper/HWND/real chooser. Synthetic video generation and validation use the already trusted binaries. Earlier failed fixture/module attempts are retained as evidence; the final pipe reader eliminates the metadata output-temp mechanism exposed by failure02.

Root's complete product compile, actual retained navigation mount, real Root window configuration/picker/clipboard/chrome, owned HTTP download and user experience still require serial integration/acceptance. No shared Gradle, shared source installation, native rebuild or desktop deployment was performed here.
