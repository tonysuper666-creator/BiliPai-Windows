# Actual49 independent fullscreen diagnosis

This cohort uses localhost JDWP suspend=y plus Root's JDK JDI breakpoint watcher. It is diagnostic-only and cannot satisfy normal renderer/native acceptance. Product sources/classes and all 97 immutable runtime entries remain unchanged. The watcher reads arguments/stack frames and immediately resumes the event thread; it invokes no debuggee methods and injects no input.

At the final unwanted clear, Win32GraphicsDevice.setFullScreenWindow receives null via Skiko PlatformOperations.setFullscreen, HardwareLayer.setFullscreen and FullscreenAdapter.componentShown. The event is not an original false request or a Window.dispose call. Complete frames, fixture placement events, geometry and native/classpath hashes are retained. Watcher and fixture timestamps have independent origins and are not compared as a single clock.

Raw geometry after entry is recorded separately for the actual Main, MPV anchor/native Canvas, and owned ComposeDialog. Main native client is 878×564 while the Canvas/dialog native client is 1317×846 at defaultTransform1.5. These are observed raw GetWindowRect/GetClientRect and AWT fields; screenshot-helper scaling is not inferred and no DPI/layout success is claimed.

Root is preparing the minimal platform readiness adaptation: deliver the latest original pending fullscreen request only after the actual foreground Window has processed its shown event, through the existing Root setter. Readiness must invalidate on instance disposal/recreation, and owner/source/epoch admission must reject stale callbacks. That future change is not present or tested here. Original fullscreen algorithm and historical closed01–13 failures remain unchanged.
