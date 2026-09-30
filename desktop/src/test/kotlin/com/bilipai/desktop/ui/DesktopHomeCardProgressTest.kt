package com.bilipai.desktop.ui

import com.bilipai.desktop.DesktopLibrary
import com.bilipai.desktop.data.VideoCard
import java.nio.file.Files
import kotlin.test.*

class DesktopHomeCardProgressTest {
    @Test fun checkpointUsesTheMatchingPartAndSurvivesColdLibraryRestore() {
        val path=Files.createTempDirectory("bp-home-progress-")
        val library=DesktopLibrary(path){false}
        library.record(VideoCard("BVfixture","Fixture","","",0L,120))
        library.checkpoint("BVfixture",501L,0,21.5)
        val read=desktopHomeCardProgressReader(library)
        assertEquals(21_000L,read("BVfixture",501L))
        assertNull(read("BVfixture",502L));assertNull(read("BVfixture",0L));assertNull(read("BVmissing",501L))
        val cold=desktopHomeCardProgressReader(DesktopLibrary(path){false})
        assertEquals(21_000L,cold("BVfixture",501L))
        library.checkpoint("BVfixture",502L,1,7.9)
        assertNull(read("BVfixture",501L));assertEquals(7_000L,read("BVfixture",502L))
    }
    @Test fun privacyModeDoesNotCreateAnUnknownCardCheckpoint() {
        val library=DesktopLibrary(Files.createTempDirectory("bp-home-progress-private-")){true}
        library.record(VideoCard("BVprivate","Fixture","","",0L,120))
        library.checkpoint("BVprivate",501L,0,21.5)
        assertNull(desktopHomeCardProgressReader(library)("BVprivate",501L))
    }
}
