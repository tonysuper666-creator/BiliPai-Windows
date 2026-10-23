"""Slice H3 markers: the feed scrollbar files and the pure names they must keep. No JVM, no network.

The design (docs/ux-design-20261018.md, H3 and the file list at about line 624) names
DesktopLazyScrollbars.kt, DesktopFeedScrollbar.kt, DesktopFeedGridHost.kt and DesktopLazyScrollbarsTest.kt,
plus T/ux_feed_scrollbar.py. DesktopFeedGridHost.kt does not exist at the integration head: the Box that holds
FeedVerticalStaggeredGrid is DesktopHomeCardGrid.kt, and Root allowed that one file as the mount point
(tasks/T15.md round 2). This module only lists the paths and the textual markers the companion test pins; it
does not mirror any scroll estimate.
"""
from pathlib import Path

import ux_source as source

LAZY_KT = source.K / "ui/DesktopLazyScrollbars.kt"
FEED_KT = source.K / "ui/DesktopFeedScrollbar.kt"
MOUNT_KT = source.K / "ui/DesktopHomeCardGrid.kt"
TEST_KT = source.KT / "ui/DesktopLazyScrollbarsTest.kt"
# Spec 8.2 still lists this name; it is armed in PROGRAM_EXTRA_GLOBS but is not on disk (the mount is MOUNT_KT).
SPEC_HOST_NAME = "ui/DesktopFeedGridHost.kt"

# Pure names of DesktopLazyScrollbars.kt that the estimate and the paging policy expose.
LAZY_MARKERS = (
    "data class DesktopStaggeredScrollSample",
    "data class DesktopScrollEstimate",
    "data class DesktopScrollTarget",
    "fun estimateDesktopStaggeredScroll",
    "fun resolveDesktopStaggeredScrollTarget",
    "enum class DesktopFeedPageKey",
    "fun resolveDesktopFeedPageKey",
    "fun resolveDesktopFeedPageTarget",
)

# Compose side of DesktopFeedScrollbar.kt: the v2 adapter, the overlay and the key modifier.
FEED_MARKERS = (
    "class DesktopStaggeredGridScrollbarAdapter",
    "ScrollbarAdapter",
    "fun BoxScope.DesktopFeedScrollbar",
    "DesktopKitVerticalScrollbar",
    "fun Modifier.desktopFeedKeyboardPaging",
    "LocalDesktopKitReducedMotion",
    "scrollToItem",
)

# Mount point: the keyboard modifier and the overlay sibling of FeedVerticalStaggeredGrid.
MOUNT_MARKERS = (
    "FeedVerticalStaggeredGrid(",
    ".desktopFeedKeyboardPaging(state)",
    "DesktopFeedScrollbar(state)",
)

# The JVM test class name and the pure functions it exercises.
TEST_MARKERS = (
    "class DesktopLazyScrollbarsTest",
    "estimateDesktopStaggeredScroll",
    "resolveDesktopStaggeredScrollTarget",
    "resolveDesktopFeedPageKey",
    "resolveDesktopFeedPageTarget",
)


def read(path: Path) -> str:
    return source.read(path)
