package com.bilipai.desktop.danmaku

/** Inputs read by the whole original BAS filter policy. Rendering changes only
 * remeasure/paint; they must not replay stateful plugins over the raw script. */
internal fun DanmakuSettings.hasSameBasFilterPolicy(next: DanmakuSettings): Boolean =
    enabled == next.enabled && allowSpecial == next.allowSpecial && allowColorful == next.allowColorful &&
        weightFilterLevel == next.weightFilterLevel && blockedKeywords == next.blockedKeywords &&
        blockedRules == next.blockedRules
