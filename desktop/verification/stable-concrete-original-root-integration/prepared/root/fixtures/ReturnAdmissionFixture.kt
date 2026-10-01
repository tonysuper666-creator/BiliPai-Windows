package com.bilipai.desktop.ui

import androidx.compose.ui.geometry.Offset
import com.android.purebilibili.core.ui.transition.VideoCardTransitionClock
import com.android.purebilibili.navigation3.BiliPaiNavKey

/** Synthetic admission counters only; actual prepared owner and pinned original session/clock.
 * This proves order and non-commit on rejection, not actual Root/Window/Store locking. */
fun main() {
    var alive = true
    var accepted = true
    var storeDepth = 0
    var checkpoints = 0
    var commits = 0
    var effects = 0
    val owner = DesktopHomeReturnNavigationOwner(
        { alive },
        { block ->
            check(storeDepth == 0)
            commits++
            storeDepth++
            try { block(); true } finally { storeDepth-- }
        },
        { block ->
            check(storeDepth == 0) { "Checkpoint executed inside Store/entry admission" }
            checkpoints++
            if (accepted) { block(); true } else false
        },
        { Offset.Zero }, { 1000L }, VideoCardTransitionClock(),
        { false }, { false }, { false },
    )
    check(owner.enterVideo("BVA", "home", null, BiliPaiNavKey.Home, false, setOf("home")) { _, _ ->
        check(storeDepth == 1); effects++
    })
    check(checkpoints == 1 && commits == 1 && effects == 1)
    check(owner.returnFromVideo(BiliPaiNavKey.VideoDetail("BVA"), BiliPaiNavKey.Home, false) {
        check(storeDepth == 1); effects++
    })
    check(checkpoints == 2 && commits == 2 && effects == 2)
    println("PASS actual prepared enter and return: checkpoint before Store/entry mutation, single effect")
    val before = owner.session.value
    accepted = false
    check(!owner.enterVideo("BVB", "home", null, BiliPaiNavKey.Home, false, setOf("home")) { _, _ -> error("Rejected") })
    check(!owner.returnFromVideo(BiliPaiNavKey.VideoDetail("BVA"), BiliPaiNavKey.Home, false) { error("Rejected") })
    check(checkpoints == 4 && commits == 2 && effects == 2 && owner.session.value == before)
    alive = false
    check(!owner.enterVideo("BVC", "home", null, BiliPaiNavKey.Home, false, setOf("home")) { _, _ -> error("Retired") })
    check(!owner.returnFromVideo(BiliPaiNavKey.VideoDetail("BVA"), BiliPaiNavKey.Home, false) { error("Retired") })
    check(checkpoints == 4 && commits == 2 && effects == 2 && owner.session.value == before)
    println("PASS rejected checkpoint and retired owner never mutate or dispatch")
    println("ORIGIN owner=" + owner.javaClass.protectionDomain.codeSource.location)
    println("ORIGIN originalClock=" + VideoCardTransitionClock::class.java.protectionDomain.codeSource.location)
    println("RESULT prepared2 groups; actualWindow=false actualMainMount=false actualStoreLock=false")
}
