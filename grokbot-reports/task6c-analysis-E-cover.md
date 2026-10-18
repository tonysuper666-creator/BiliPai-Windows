I'll investigate only `DesktopOriginalVideoCoverFileAdmissionTest`: re-check the prior analysis against current code, then classify it and propose a test-only or product patch.The prior note treats this as a teardown race after `manager.close()`. I'll re-check the manager's close/retirement API and the current fixture against that claim.## 1. `DesktopOriginalVideoCoverFileAdmissionTest` — (i) stale test

The gallery assertions are not what fails. `@TempDir` cleanup races an in-flight queue persist that `manager.close()` no longer finishes before it returns. The await API already exists, so this is a test-only change.

**What fails.** Both methods share `Fixture.close`. The failing method is `actualBindingRejectsRetiredCaptureAtFinalMoveAndCleansGalleryStage`. Its body has already returned; the stack is JUnit deleting the temp root.

- Earlier run: `DirectoryNotEmptyException` on the temp root only (`/workspace/g-logs/t5/ui/TEST-com.bilipai.desktop.ui.DesktopOriginalVideoCoverFileAdmissionTest.xml`).
- Later run, same method: `NoSuchFileException` for `downloads-5683874984724240463.tmp` (`/workspace/g-logs/t6/t6-baseline/TEST-com.bilipai.desktop.ui.DesktopOriginalVideoCoverFileAdmissionTest.xml`). The walk saw that file, then the file was moved or deleted before `Files.delete`.

That name is `Files.createTempFile(stateFile.parent, "downloads-", ".tmp")` in `persistLocked` (`DesktopDownloadManager.kt:872`), removed in the same function’s `finally` (`:881`). `stateFile` is `root.resolve("download-state.json")` (`DesktopOriginalVideoCoverFileAdmissionTest.kt:67-70`), so the temp file is a direct child of the `@TempDir`.

**Why the test is stale.** `6660b7d9f0a7` (2026-10-10, “Cover real gallery file publication and retired video capture”) is still the only commit of this test. `Fixture.close` (`DesktopOriginalVideoCoverFileAdmissionTest.kt:82-85`) calls `manager.close()` and returns. At that date `close()` persisted on the caller. `6da71260b392` (2026-10-12, “persist download queue ownership across update handoff”) changed that. Those lines are unchanged at `56ff5a459666`:

- `close()` (`DesktopDownloadManager.kt:973-976`) only `markClosing()` and `startCloseRetirement()`.
- `startCloseRetirement` (`:963-970`) launches `shutdownForApplicationExit()` on `retirementScope` and does not join it.
- The durable `persistLocked(force = true, durable = true)` and `lease.close()` run only inside that suspend function (`:944-959`).

So `downloads-*.tmp` is created and removed after `Fixture.close` returns, overlapping `@TempDir`. The sibling test uses the same `close` and has the same race. The lock file is not this failure: `tryAcquire` creates `download-state.json.bilipai-lock` synchronously in the constructor (`DesktopDownloadQueueAuthority.kt:170-175`), and `close()` does not create it.

**Await API (same one case B needs).** `internal suspend fun shutdownForApplicationExit()` at `DesktopDownloadManager.kt:944`. It is the body the retirement job runs. It is already called from this module without a visibility change:

- Application exit awaits it: `DesktopShell.kt:706-709` (`registerShutdown`).
- Compose dispose does not: `DesktopShell.kt:1421-1424` calls `downloads.close()` and returns. That is intentional for a non-suspend `onDispose`.
- Tests that delete a directory after a manager already use it, including `DesktopBangumiCourseDownloadOwnershipTest.kt:827-834`.

Calling it performs `markClosing`, joins the worker scope, persists, deletes the `downloads-*.tmp` in `persistLocked`’s `finally`, and releases the lease before it returns. There is no join handle on `close()`; `closeRetirement` is private. Do not also call `manager.close()` afterward: that starts a second retirement coroutine after the fixture returns.

**OkHttp.** Leave `client.dispatcher.executorService.shutdown()` (`DesktopOriginalVideoCoverFileAdmissionTest.kt:84`). The client has no cache, and the interceptor (`:56-62`) never calls `proceed`. It does not create `downloads-*.tmp`. `awaitTermination` would be a timed wait and is not required.

**Test-only edit** (`desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoCoverFileAdmissionTest.kt`). `runBlocking` is already imported. `close` becomes suspend and is invoked from the existing test `runBlocking`, so this does not nest `runBlocking`. Every current assertion stays.

```diff
--- a/desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoCoverFileAdmissionTest.kt
+++ b/desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoCoverFileAdmissionTest.kt
@@ -33,7 +33,7 @@ import org.junit.jupiter.api.io.TempDir
 class DesktopOriginalVideoCoverFileAdmissionTest {
     @TempDir lateinit var directory: Path
 
-    private class Fixture(root: Path) : AutoCloseable {
+    private class Fixture(root: Path) {
         val bytes = Base64.getDecoder().decode(
@@ -79,13 +79,20 @@ class DesktopOriginalVideoCoverFileAdmissionTest {
             action()
         }
         suspend fun configure() { preferences.setImageSaveTreeUri(images.toUri().toString()) }
-        override fun close() {
-            assets.close(); manager.close(); entryScope.cancel(); lifetime.close(); entryActive.set(false)
+        suspend fun close() {
+            assets.close()
+            try {
+                manager.shutdownForApplicationExit()
+            } finally {
+                entryScope.cancel(); lifetime.close(); entryActive.set(false)
+                client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
+            }
         }
     }
 
+    private suspend fun <T> Fixture.closing(block: suspend (Fixture) -> T): T =
+        try { block(this) } finally { close() }
+
     private fun files(directory: Path): List<Path> = Files.list(directory).use { it.toList() }
 
     @Test fun actualBindingSavesOriginalCoverBytesToConfiguredGallery(): Unit = runBlocking {
-        Fixture(directory).use { fixture ->
+        Fixture(directory).closing { fixture ->
             fixture.configure()
@@ -109,7 +116,7 @@ class DesktopOriginalVideoCoverFileAdmissionTest {
     }
 
     @Test fun actualBindingRejectsRetiredCaptureAtFinalMoveAndCleansGalleryStage(): Unit = runBlocking {
-        Fixture(directory).use { fixture ->
+        Fixture(directory).closing { fixture ->
             fixture.configure()
```

`files()` still uses `Stream.use`. No `Thread.sleep`, no poll, no product change. The `finally` keeps the old entry-scope and OkHttp cleanup if the checkpoint throws; that throw then fails the test instead of leaving a temp file behind.
