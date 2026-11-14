package com.bilipai.desktop.settings

import org.junit.jupiter.api.Test
import kotlin.test.*

class DesktopSettingsPlacementTest {
    private val header = "# settingId\t上游标题\t上游分区\t桌面分区\t处理\t原因\n"

    @Test fun packagedTablesAreReadable() {
        assertTrue(desktopSettingsItemPlacement.isNotEmpty())
        assertTrue(desktopSettingsTargetPlacement.isNotEmpty())
    }

    @Test fun malformedTablesFailWithFileAndLine() {
        fun failure(contents: String, line: Int) {
            val error = assertFails { parseDesktopSettingsPlacement("fixture.tsv", contents, false) }
            assertTrue(error.message.orEmpty().contains("fixture.tsv:$line"), error.message)
        }
        failure("# settingId\n", 1)
        failure(header, 1)
        failure(header + "bad\tshort\n", 2)
        failure(header + "id\ttitle\tA\tB\tunknown\treason\n", 2)
        failure(header + "id\ttitle\tA\tB\tavailable\t\nid\ttitle\tA\tB\thidden\treason\n", 3)
        assertTrue(assertFails { loadDesktopSettingsPlacement("missing.tsv", false) }.message.orEmpty().contains("missing.tsv:1"))
    }
}
