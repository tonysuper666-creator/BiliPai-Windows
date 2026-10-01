The fixture and runner are prepared. No native playback or Java fixture has run in this lane.

`compile-01` and `compile-02` compile ONLY `NativeHomeMediaFixture.kt` against historical actual Main30 plus the explicitly declared frozen media74 and Home UI classes. The fixture emits 12 classes, with no intersection against any production dependency class. These runs do not execute the fixture, load libmpv, invoke FFmpeg, or establish an installed-product runtime result. The second compile records the final runner/toolchain pins. The source audit's initial capitalization error is retained in `audit-history-01.json`; it did not indicate a production defect.

The byte-only ABI review verifies the fixed Windows x64 libmpv PE and five render exports against the pinned official `render.h`, commit `69e63f425a531f814431fba12750bdb3721357f2`. Windows x64 C layout implies the 16-byte parameter record with offsets 0/8; this is a source/layout inference, not an executed native sizeof probe. The software `bgr0` fourth byte is uninitialized in that header. Production copies the real native raster, sets alpha bytes to 255, then uses Skia OPAQUE. Render calls live on the existing software-render thread and its wake callback calls no client/render API. Source inspection shows context free/join before core termination. Runtime cleanup evidence will observe managed thread completion and the source ordering, because no native handle counter is exposed.

Actual Main32 preflight pins its immutable 97-entry CP and records six missing media classes. The runner refuses it. Root must supply a later installed, immutable snapshot manifest and ordered CP SHA before any execution. `actual-run` accepts no prepared media/Home class directory or candidate JAR; all nine inspected production classes must originate exclusively from that snapshot's actual main Kotlin JAR. The fixture class intersection with the entire actual runtime CP must be empty. Compiler, CP, fixed binaries, source and provenance bytes are checked before and after.

After Root supplies that graph, the intended narrow run generates two four-second MPEG4 local videos with the existing fixed FFmpeg (the build lacks libx264). It decodes those files through actual MpvPlayer and the same-core software transport. It observes the first successful copied CPU frame, opaque pixels, progressing moving raster, actual pause, tracked seek receipt plus changed pixels, source replacement including a paused blue file, and close. FFmpeg only creates input assets; it does not produce the tested playback frames.

The owned media phase uses the actual product's nonpersistent SessionStore with a fresh guest generation and no saved account path. Root ownership callbacks delegate its actual atomic dynamic owner admission. An isolated RESUMED lifecycle is fixture input. The actual Home texture renders into ImageComposeScene; its real native blue image must pass half-alpha and rounded clipping, and one first-frame callback. Actual logout advances the guest epoch (same MID 0), invalidating old pixels and callbacks. Closing the Home lifetime must leave the independent protected Mpv source live, and final managed native/render thread IDs must return to baseline.

The elapsed time is the observation interval until the first copied CPU render frame reaches StateFlow, not monitor presentation, native-window display, Root/Home end-to-end latency, or a benchmark. The isolated offscreen scene is not a mounted Root window/account or Android TextureView/HDR/physical IME proof. No main player, Listen owner, API, second Store, HTTP client, mask actor, or user media directory is introduced. Java networking/listeners/process execution are fenced inside the fixture; the native demux inputs are two canonical local files. Actual startup/paused-first-frame ordering and native cleanup remain pending until this run.

Commands, run from the workspace:

```powershell
python work/BiliPai/desktop/.local/stable-home-native-media-proof/run.py prepared-compile UNIQUE_COMPILE_NAME
```

Only after Root supplies a new actual graph:

```powershell
python work/BiliPai/desktop/.local/stable-home-native-media-proof/run.py actual-run UNIQUE_ACTUAL_NAME --snapshot ROOT_ACTUAL_SNAPSHOT --manifest-sha ROOT_MANIFEST_SHA --cp-sha ROOT_ORDERED_CP_SHA
```

The runner never overwrites an existing run directory. Preserve source-only preparation and any later actual run as separate cohorts. No shared source, source registry, Gradle, frozen media74, or native binary is changed.
