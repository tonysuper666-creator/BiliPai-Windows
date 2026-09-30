class BaselineRestoreCancellationTest {
    @Test fun realProductLocalArchiveCommitsButUiExitIsLost(): Unit = runBlocking {
        RestoreFixture().use { f ->
            val coordinator = f.coordinator(cancelPage = true)
            f.runPage { coordinator.importLocal(f.zip) }
            f.assertRestored()
            assertEquals(1, f.before.get()); assertEquals(0, f.exits.get()); assertEquals(1, f.cancellations.get())
            assertFalse(coordinator.state.value.busy)
        }
    }
    @Test fun realProductWebDavArchiveCommitsButUiExitIsLost(): Unit = runBlocking {
        RestoreFixture().use { f -> RestoreDav(f.bytes).use { dav ->
            f.store.save(DesktopBackupSnapshot(dav.config))
            val coordinator = f.coordinator(cancelPage = true)
            f.runPage { coordinator.restoreLatest() }
            f.assertRestored(); dav.assertExactReadOnlyRequests()
            assertEquals(1, f.before.get()); assertEquals(0, f.exits.get()); assertEquals(1, f.cancellations.get())
            assertFalse(coordinator.state.value.busy)
        } }
    }
}
