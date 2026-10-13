# Player ambient light

Opt-in SDR enhancement for the existing detail/fullscreen player. The setting is
independent of immersive status-bar/portrait-letterbox settings. No output routing,
HDR preference, codec or Anime4K shader is changed.

- A player-local controller serializes sampling across session cancellation. The
  PixelCopy destination stays alive until its callback finishes. Published images
  are immutable and never manually recycled while a renderer may retain them.
- Long-edge 96px sampling targets 8 fps (4 fps under power/battery/thermal pressure,
  static probing at 4 fps in auto / 2 fps in saving after three seconds unchanged). The legacy status-bar-only path
  remains at 1500ms. Processing uses a small separable Gaussian blur off the UI
  thread. High-frequency rendering does not use full-size Haze/RenderEffect blur.
- Image drawing interpolates over 120ms for small changes, 80ms for medium changes,
  and 48ms for large frame differences or requested refreshes. This is a visual
  change heuristic, not semantic scene-cut detection. Interrupted transitions
  flatten the current blend using that frame's own duration before continuing.
- Initial appearance/recovery fades in over 180ms. Soft hides/seek fade out over
  80ms; shared-card/flip gates still hide immediately to keep glow out of morphs.
  Seek/resume/foreground recovery require a matching fresh sampling generation;
  in-flight results from before a refresh are rejected. Refresh events interrupt
  periodic waits within 50ms, without cancelling PixelCopy buffer ownership.
- Static probing now favors responsiveness over the former 1fps setting: automatic
  mode detects resumed motion on its next 250ms sample, saving on its next 500ms
  sample, plus capture/processing time. Failures fade over 200ms and retry after 2s.
- Crop detection runs once per second, needs three matching observations and never
  updates in dark scenes. It only changes ambient pixels, never the video surface.
- Fullscreen glow is behind video/controls and restricted to the space outside the measured video surface;
  filled/zoomed video has no manufactured margins. Inline light still fades to
  transparent within 48dp. Fullscreen covers each actual bar, with a slower fade
  whose zero-alpha endpoint is at 125% of that bar width, outside the viewport.
  Fullscreen strength is 1.6 times the selected inline opacity (standard 0.48,
  soft 0.288, strong 0.672), capped at 0.72. It samples blurred 10–22% inward strips
  (78–90% on the opposite edge) while retaining spatial colors along each edge.
  Elliptical corner masks connect unequal horizontal/vertical bar widths.
  No extra frame copies or sampling requests are introduced. Viewport-clipped
  bar/corner GPU layers blend frames before masking to avoid crossfade dimming.
  Wider fullscreen drawing increases GPU fill cost; device profiling is pending.
- Phone/tablet/cinema detail hosts own the inline canvas outside the clipped/shared
  player. A separate 48dp bottom gutter is reserved only for supported, enabled
  ambient light; the original video size/aspect is retained. Title/comments start
  after that gutter. The gutter has a black backing below the glow: its first
  24% stays black, fades to the theme page background by 85%, and finishes with
  that background before the tabs. The backing is drawn outside shared-card
  bounds and hidden by the same transition gate as the glow. Tablet/cinema hosts
  also use existing side space for corners.
  Fullscreen has no gutter. Transition visibility gates hide the glow without
  adding it to the shared video-card bounds.
- HDR, Anime4K, audio-only, portrait pager, mini-player, PiP, hidden entries and
  navigation/flip transitions do not show the new glow. Existing legacy chrome
  settings retain their own compatibility behavior.

Tests in `app/src/test/.../video/ambient` cover geometry, crop hysteresis, static
recovery, spatial colors, cancellation serialization and retry backoff. They have
not been executed: the user requires explicit authorization for compilation and
execution. Neither the 8fps performance target nor driver-specific rendering is
verified by static checks.

Device acceptance after authorization: compare identical 1080p30/60 playback with
ambient off/on for 30 minutes. Dropped-frame increase <=0.1 percentage points;
UI jank increase <=1 percentage point; retained images/memory must plateau after
warmup. Also inspect phone/tablet inline margins (including parent clipping), fullscreen aspect modes, SurfaceView/TextureView, rotation, quality/seek changes, background,
shared-element return and HDR/Anime4K exclusions. Inspect subtitles and comments
for unchanged contrast. Keep default off if performance or visual acceptance fails.

- Fullscreen liquid-glass controls now consume Miuix `LayerBackdrop` recordings
  of sibling page layers (video/glow, cover, danmaku, subtitles), composed in paint
  order. Glass consumers never belong to their own capture subtree. Miuix owns
  source coordinates, inverse transforms, invalidation and layer cleanup.
- SDR glass uses TextureView so video participates in the view hierarchy. The
  TextureView choice is retained for the detail entry to avoid recreating the
  output again when leaving fullscreen. HDR still overrides it; HDR/Anime4K,
  audio-only, portrait fullscreen and PiP use the neutral control material.
- Glass no longer requests bitmap copies, native-size frame buffers, 30fps polling,
  stale-frame fading or pause/resume frame retention from the ambient controller.
  Ambient/status-bar capture retains its own 96px budget and refresh lifecycle.
- Recording remains attached through the shared 300ms chrome exit and while
  paused-center controls can appear; it detaches when playing with hidden controls.
  Controls read the latest recorded page rather than a timer-driven frame cache.
  Hidden controls do not change the ambient sampling policy. Actual TextureView replay through
  Compose graphics layers and rendering cost remain unverified at runtime.
- Top actions and the three bottom docks retain 48dp rows; fullscreen titles
  ellipsize instead of scrolling into the action area.
