package com.bilipai.desktop.ui

import kotlin.test.Test

/** Directly mounts the generated desktop TabletCinemaLayout and tests its rendered tab rail. */
class DesktopTabletCinemaPagerMotionTest {
    @Test fun fullLayoutSelectionAndIndicator() = runTabletLayoutMotion(cinema = true)
}
