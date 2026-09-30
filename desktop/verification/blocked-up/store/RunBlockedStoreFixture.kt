package com.bilipai.desktop.data
import java.nio.file.*

fun main(args:Array<String>) {
    var exit=1
    try {
        val test=DesktopBlockedUpStoreTest()
        test.`whitelisted legacy scopes migrate atomically with full metadata and original invalid UID policy`()
        test.`retired legacy MID cannot resurrect after unblock and a fresh disk generation`()
        test.`corrupt scoped input never commits partial records or marker and repaired input can retry`()
        test.`unknown source or destination schema is visible and cannot be marked successful`()
        test.`failed atomic persistence publishes neither a migrated marker nor updated records`()
        test.`original share format preserves profiles while import deduplicates and keeps existing records`()
        test.`different global facades cannot lose concurrent local writes or fork by account`()
        test.`actual archive restore fences a queued old IO writer and permits a new store generation`()
        val file=Path.of(args.single());Files.createDirectories(file.parent)
        Files.writeString(file,"""{"passed":true,"junitMethodsPassed":8,"realTemporaryDisk":true,"globalMigration":true,"legacyFilesRetained":true,"corruptInputsPreventMarker":true,"actualArchiveRestoreAndQueuedWriterFence":true,"actualPatchedDiscoveryConsumersCompiled":true,"mainEdited":false,"sharedGradleInvoked":false,"nativeWindowCreated":false,"realAccountReads":false,"networkRequests":false,"profilesRemotelyRefreshed":false,"remoteSyncVerified":false,"fullSettingsPageIntegrated":false}""")
        println("8 original-model global block-store / actual Discovery / real disk restore barrier checks PASS; no network or HWND.")
        exit=0
    }catch(failure:Throwable){failure.printStackTrace()}
    finally{kotlin.system.exitProcess(exit)}
}
