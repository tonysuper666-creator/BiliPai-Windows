I'll investigate the two cold-restore download tests and every production site that constructs `DesktopDownloadManager`, starting from the prior analysis and verifying each claim against the current code.The prior write-up treats this as a product defect. I’ll re-check every production construction site and the close/retirement path against the current tree.Production construction looks concentrated in `DesktopShell`. I’ll read the factory, close path, and every caller that can rebuild a manager in-process.The shell `remember` keys can rebuild the manager. I’ll trace when those keys change and whether the old manager is retired first.Both cold-restore failures are stale tests. Production never builds a second `DesktopDownloadManager` in-process, and the successor is only safe after `shutdownForApplicationExit()`.

HEAD is `56ff5a459`. The only production construction site in main sources, generated sources, and app wiring is `DesktopShell.kt:461`. `git grep DesktopDownloadManager(` outside tests, `desktop/verification/**`, and `desktop/upstream-slices/**` returns that one line. There is no DI binding. Constructors are the four overloads on `DesktopDownloadManager` (`DesktopDownloadManager.kt:37-53`); the shell calls the handoff overload at lines 49-53.

`6da71260b` (2026-10-12) made `close()` async. `close()` (`DesktopDownloadManager.kt:973-975`) only `markClosing()` and `startCloseRetirement()`. Durable persist and `FileLock` release run later on `retirementScope` inside `shutdownForApplicationExit()` (`DesktopDownloadManager.kt:944-957`). Same-JVM `FileChannel.tryLock` throws `OverlappingFileLockException`, which `tryAcquire` treats as busy (`DesktopDownloadQueueAuthority.kt:177-178`). The new manager then stores `emptyList()` (`DesktopDownloadManager.kt:222`, also `6da71260b`).

The shell `remember` keys are `repository`, `storageSettingsContext`, `brandSuccessEvents`, and `startupDownloadHandoffToken`. None of them change for the life of the composition, and nothing disposes that composition and builds another manager before the lease is released.

| Path | What happens | Second in-process manager? |
|---|---|---|
| Account switch / logout | Same `DesktopRepository` from `Main.kt:124`. `sessionEpoch` is not a remember key. `DesktopDiscoveryStorageGuard.load` keeps the retained discovery object (`DesktopDiscoveryStorageGuard.kt:69`, `:87`), so `DesktopReadyApp` stays mounted. | No. Old manager kept. |
| Download-dir setting | `download_path` is read inside the destination lambda (`DesktopShell.kt:462-464`), not a remember key. The queue file is `defaultStateFile()` (`DesktopDownloadManager.kt:1041-1042`), not the download directory. | No. |
| Plugin reload | `jsExecutionRevision` is collected (`DesktopShell.kt:435`). `pluginRuntime` keys are `pluginStore`, `diagnosticLifecycle`, `dynamicCache`, `imageSaveLifetime` (`DesktopShell.kt:384`). `context` is one `val` (`DesktopPluginRuntime.kt:50`). | No. |
| Window | One `Window` (`Main.kt:175-272`). `hostWindow` is not a downloads key. | No. |
| In-process restart | There is no in-process restart. `closeApp` awaits `shutdown.get()` (`Main.kt:155-159`), and that lambda calls `downloads.shutdownForApplicationExit()` (`DesktopShell.kt:709`, `919eea486`) before `DesktopApplicationRestart.launch()` (`DesktopApplicationRestart.kt:8`), which is a new OS process. | No. Lease released first. |
| Backup restore | `beforeRestore` calls `closeDiscoveryStorage()` (`DesktopShell.kt:691`), which permanently closes the guard (`DesktopDiscoveryStorageGuard.kt:95`; `rootClosing` is never cleared). The shell unmounts and `downloads.close()` runs (`DesktopShell.kt:1421-1424`). `afterRestore` calls `onExit()` (`DesktopShell.kt:703`), which awaits the same manager's `shutdownForApplicationExit`. The guard cannot mount a replacement. | No. |
| Post-update | Same manager. `beforeLaunch` (`DesktopUpdater.kt:435`) calls `awaitUpdateHandoff` (`DesktopShell.kt:1644-1645`), which `lease.close()`s (`DesktopDownloadManager.kt:156`) before `launchInstallation` (`DesktopUpdater.kt:440`). Rollback reacquires on that same object (`DesktopDownloadManager.kt:162-188`, retry added in `4de46f98a`). Success calls `onExit()` on that same object (`DesktopShell.kt:1650`). | No. Child is a new process. |
| Composition dispose | `DisposableEffect` `close()` (`DesktopShell.kt:1424`) does not return a join handle. It runs only when this composition leaves, which is exit or the permanent discovery close above. | No successor is constructed. |

The busy-retry (`DesktopDownloadManager.kt:232-257`, `6da71260b`, 100 ms × 50) would adopt and `restore()` if the previous lease drops within 5 s. These tests never observe that emission. They call `tasks.value.single()` on the initial empty `StateFlow` before the first `delay(100)`. The normal production manager is not busy: `tryAcquire` runs at `DesktopDownloadManager.kt:70`, before line 222, so `tasks.value` is already `restore()`. UI collects the flow (`DownloadScreens.kt:39`, `DesktopOriginalDownloadListBindings.kt:22-24`). Direct `.value` reads (`DesktopShell.kt:627`, `:1324`, `:1592`, `:1929`, `DesktopDownloadNotifications.kt:142`) happen at event time on that single manager, not as a one-shot taken before adoption.

The in-module suspend API that waits for persist and unlock is `internal suspend fun shutdownForApplicationExit()` at `DesktopDownloadManager.kt:944-960`. `DesktopDownloadManagerTest.kt:75-78` already uses it before a cold successor. `close()` does not return that job. `PAUSED` and `COMPLETED` are not active (`DownloadQueuePolicy.kt:3-8`), so `markClosing` does not rewrite them and the createdAt byte assertion stays intact.

## 1. `DesktopDownloadBrandSuccessTest.restoredCompletedTaskDoesNotEmitAgain`

**Classification:** (i) stale test.

**Evidence:** `8b7226383` (2026-10-09) is the test's last edit, before `6da71260b`. `closeManager` (`DesktopDownloadBrandSuccessTest.kt:59-63`) calls `close()` and joins the worker `scope` by reflection. Lock release is after `scope.cancelAndJoin()` inside `shutdownForApplicationExit` (`DesktopDownloadManager.kt:948-957`). Line 143 then does `restored.tasks.value.single()` and throws `NoSuchElementException` on the busy empty list. Brand emission stays on the worker completion path (`DesktopDownloadManager.kt:674-680`), not on `restore()`, so the existing event assertion still applies once the task is actually restored.

**Test edit** (`desktop/src/test/kotlin/com/bilipai/desktop/download/DesktopDownloadBrandSuccessTest.kt`):

```diff
             withTimeout(2_000) { while (h.events.isEmpty()) delay(5) }
-            h.closeManager(manager); h.events.clear()
+            manager.shutdownForApplicationExit(); h.events.clear()
             val restored = h.manager(); assertEquals(DownloadStatus.COMPLETED, restored.tasks.value.single().status)
```

`h.close()` still closes both managers. No assertion is removed or relaxed. The existing `delay(30)` stays. No new sleep.

## 2. `DesktopDownloadRefreshQualityTest` cold restores

**Classification:** (i) stale test. Both `fallbackStreamUpdatesActualQualityWithoutMovingTaskOrOfflineFile` (`DesktopDownloadRefreshQualityTest.kt:80-83`) and `managerPersistenceExplicitlyStoresCreatedAtAndColdRestoresTheSameTaskIdentity` (`:682-686`).

**Evidence:** Last test edit is `c68825594` (2026-10-11), one day before `6da71260b` moved persist and unlock off `close()`. Each test calls `manager.close()` and constructs the next manager on the next line. `tryAcquire` is busy (`DesktopDownloadQueueAuthority.kt:177-178`), so line 222 stores `emptyList()` and `.single()` throws. The createdAt test already read the state file successfully at lines 677-680, before `close()`. `4de46f98a` only lengthens `rollbackUpdateHandoff`; it does not change `close()` or this init retry.

**Test edit** (`desktop/src/test/kotlin/com/bilipai/desktop/download/DesktopDownloadRefreshQualityTest.kt`):

```diff
             assertContentEquals("merged".toByteArray() + bytes, Files.readAllBytes(Path.of(manager.offlinePlayback(id).videoUrl)))
-        } finally { manager.close() }
+        } finally { manager.shutdownForApplicationExit() }
         val restored = DesktopDownloadManager(client, state, muxer, publication = lease())
```

```diff
             assertTrue(requests.none { it.url.queryParameter("token") == "fresh" })
-            manager.close()
+            manager.shutdownForApplicationExit()

             val coldManager = DesktopDownloadManager(client, state, muxer, publication = lease())
```

Every current assertion stays, including `assertContentEquals(persistedBytes, Files.readAllBytes(state))`. The finally-block `manager.close()` / `restored?.close()` cleanup stays after those assertions. No product change.
