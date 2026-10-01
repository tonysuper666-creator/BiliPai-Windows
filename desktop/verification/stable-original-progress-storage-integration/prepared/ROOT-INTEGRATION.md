This is an independently installable source slice, not acceptance of the full Root player.

Copy only `prepared/manual/com/bilipai/desktop/ui/DesktopOriginalPlaybackProgressStorage.kt`
to the same package under desktop/src/main/kotlin. Apply the TWO exact literal edits in
producer-hunks.json to the existing sole extract-upstream-video-full-owner.py; do not replace
that producer. Union the ONE original identity in registry-delta.json. The existing task/sourceDir
already produces its output; no Gradle/dependency delta is necessary. `generated/` and the complete
producer after-file are verification inputs only. Do not install any class/JAR/temp Store.

Root creates ONE DesktopOriginalGlobalPlaybackProgress(globalPluginContext,appScope,feedback)
per app/global Store, before any ordinary owner consumes progress. It is not MID/route scoped.
The appScope Job must remain active until its writer drains. For each actual Assembly, pass
forEntry(durationMs,owned), where durationMs returns the SAME current accepted native subject's
duration in milliseconds only when its BVID/CID match the arguments. This preserves the original
95% completion rule. BVID-only Home/card reads use the same original Manager; do not substitute
DesktopLibrary integer seconds. Do not instantiate another Manager/getInstance for route changes.

At app close and before restore/freeze: first retire playback admission and drain the Assembly,
then call globalProgress.closeAndJoin() OUTSIDE Store/entry/native locks and before appScope is
cancelled or PluginStore.freezeWrites. A false result is a genuine timeout/write failure: block the
next Store generation/second writer and surface the existing storage feedback. Do not silently
claim completion or use the writer after closing. Original PluginPreferences.apply is untouched.

The unchanged Manager owns immediate lock-free getCachedPosition memory reads; its apply editor
queues immutable edits to the SAME Store. Atomic disk replacements execute serially on IO. Only
the consumed SharedPreferences subset is bridged; prefs.all is the startup load, not a new live
preference authority. Namespace video_progress, exact BVID#CID keys and BVID fallback, 5000 ms,
strictly greater than 95%, 4096 eviction and positive numeric cold loading are original body.

Actual74 strict101 proof compiles ONLY these two new production declarations and pure fixture,
with no product overrides. 22 assertions pass: blocked actual Store backing monitor does not
block original hot memory reads; exact CID/units/minimum/completion, full atomic disk drain,
unrelated namespace preservation, rejected after-close apply and original eviction are verified.
Root mounted full player/normal shutdown/native/account/EXE acceptance remains pending.

Historical actual73 proof01 failed because the fixture's positional three-Long call selected the
original (bvid,position,duration) overload. Proof02 uses named cid/positionMs, and passes the same
original body. No product fix was made. These historical logs retain their explicit family-overridden
candidate context; actual74 proof uses no such overrides. Task-local temporary Store directories
are excluded from installation and archive. No user/account/HTTP/window operations occurred.
