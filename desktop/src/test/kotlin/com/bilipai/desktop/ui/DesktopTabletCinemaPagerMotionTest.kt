package com.bilipai.desktop.ui

import kotlin.test.Test

/** Direct composition coverage of the generated TabletCinemaLayout's tab rail.
 * The fixed upstream tree has no caller for this layout; this does not establish user reachability.
 */
class DesktopTabletCinemaPagerMotionTest {
    @Test fun fullLayoutSelectionAndIndicator() = runTabletLayoutMotion(cinema = true)
}
