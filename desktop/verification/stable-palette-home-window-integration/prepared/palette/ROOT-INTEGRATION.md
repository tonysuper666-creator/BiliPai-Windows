This is an isolated source package. Install the four payloads in install-contract.json, merge its single original registry row and the source/task snippet. Do not copy generated prepared sources or either proof JAR into the product. There is no new dependency or runtime override.

The pinned original is v0.2.3 / 3d5d19a2f994daccd0e2f8b5f522b6d82f43d589, WallpaperPaletteStore.kt LF SHA 521e632d1b8f3655e862abbeb3624bfaabf144fd8274f5315a2f9d646adb2219. The producer verifies both the original content and that exact Git blob. It emits one full original Store declaration. VideoCardAdaptiveTintPolicy.kt already directly owns WallpaperPalette; the new producer must not emit that schema. The installed Java DesktopPalette/ColorCutQuantizer/ColorUtils/Color remain the only quantizer. The two new Java classes contain the full original AndroidX Palette1.0.0 Target and its six original scoring methods; source receipt and full Apache headers are retained.

Create one WallpaperPaletteStore for the actual application lifetime. This replaces the original global object with a closeable owned instance, not a new persistent store. Keep that instance across Home navigation. Close it when the application owner retires; its clear method still clears the original cache/current flow. Use the existing application scope for lifetime cleanup.

At the retained Home entry's real platform/epoch owner, create a DesktopOwnedWallpaperPaletteContext with all five required constructor inputs:

```kotlin
val wallpaperContext = DesktopOwnedWallpaperPaletteContext(
    platformContext = actualPlatformContext,
    imageLoader = SingletonImageLoader.get(actualPlatformContext),
    system = actualWindowsSystemWallpaperPort,
    owned = entryGate::owns,
    commit = entryGate::commit,
)
// These are the existing mandatory DesktopHomeEnvironment fields.
wallpaperPalette = applicationWallpaperPaletteStore.currentPalette
loadWallpaperPalette = { uri, sourcePageScope ->
    applicationWallpaperPaletteStore.loadWallpaperPalette(wallpaperContext, uri, sourcePageScope)
}
```

The names in the example denote the Root's actual owners, not optional services. actualWindowsSystemWallpaperPort is one DesktopWindowsSystemWallpaperPort, shared as a stateless read effect; it owns no HWND, thread, actor, settings namespace or persisted state. actualPlatformContext must be the real current Compose platform context. SingletonImageLoader must already be Root-configured with the same repository HTTP service. Do not construct another ImageLoader/client for this feature. Each Home/epoch entry captures a fresh owned context; only the application Store/cache is retained. Pass the exact page scope supplied by original Home, not an unrelated global scope. Root admission must follow its existing global Store → entry gate order, reject a retired epoch/page, and execute the publication atomically. The internal requestGate never calls external ownership/admission functions while locked, preventing inverse ordering with Root admission. A new URI cancels/supersedes its own earlier extraction; page cancellation, context retirement and application close prevent late publication and caching.

The Windows static image adapter uses that same Coil request with the original 256x512 decode size. It copies pixels to an owned IntArray and does not close/recycle Coil's borrowed cache Bitmap. The five slices retain the original integer slice boundaries and source fallback order: vibrant, light vibrant, dark vibrant, muted, dominant, center pixel. Palette area112x112 shrinks the full image first, with ceiling dimensions, then maps each region by the resized WIDTH ratio, floor top and ceiling bottom. Nearest-neighbor center sampling replaces Android Bitmap.createScaledBitmap(filter=false). Maximum colors8 and clearFilters are retained; alpha/color quantization is the installed original quantizer. The exact original default five theme colors and access-order maximum8 URI cache remain. The object-to-instance, cancellation, ownership and Android-to-Windows service changes are explicit in reverse-adapters/WallpaperPaletteStore.json.

The actual Windows system effect reads SPI_GETDESKWALLPAPER(0x0073) through existing JNA and decodes the returned local file with the same Coil loader. The returned wallpaper path will not exceed MAX_PATH characters; the adapter supplies 261 chars including termination space. [Microsoft SystemParametersInfoW](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-systemparametersinfow)

COLOR_DESKTOP is documented unsupported on Windows10 and later. The source tests the real Windows major version, also checks the borrowed GetSysColorBrush, and only reads GetSysColor for a supported older generation. A nonzero classic brush does not establish modern support; an unsupported or unidentifiable generation leaves desktopArgb unavailable. It never deletes the Windows-owned brush. Missing/undecodable system image plus unavailable system color uses the exact original default palette. A real supported black color is not confused with API failure. [Microsoft GetSysColor](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-getsyscolor)

There is no constant-null system service or fabricated blank palette. Blank wallpaper URI schedules the actual system read instead of Android's synchronous color API, keeping IO off the UI thread. The resulting palette is published only under the same real ownership admission. Android content:// providers are unavailable on Windows and flow through the original system/default fallback; local file: URIs are parsed with Path.of(URI), preserving Windows drive paths. Wallpaper URI/pixels are neither persisted, logged nor copied to evidence. Unsupported video/image decoding uses that same original fallback; this is not a separate video-frame analysis service.

Validation is against immutable actual41's strict97 ordered CP with no overrides. Four production sources compile to18 classes. Focused proof03 has three groups/34 assertions: actual same SingletonImageLoader/Skia reads a generated local five-band PNG; verifies five slice order, asymmetric full-image resize-before-region, scoring/muted fallback, borrowed Bitmap ownership, maximum8/access-order cache, blank/system-unavailable defaults, cancellation/supersession/retirement/close. The sole local fixture is generated test data. No personal wallpaper, account data, external HTTP or actual Root window was used. Class/top-method collision and JVM validity/classload audits are in abi-audit.json. Root must perform serial whole-build and actual Home binding acceptance after integration; this package does not claim them.
