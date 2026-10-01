package com.bilipai.desktop.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive

/** The same retained detail-entry scope and atomic Store-to-entry gate.
 * It constructs no network, account, player, settings or draft persistence authority.
 * Root must close the original Composer domain before replacing this entry.
 */
internal class DesktopOriginalVideoComposerEnvironment(
    val scope: CoroutineScope,
    private val stillCurrent: () -> Boolean,
    private val commitIfCurrent: ((() -> Unit) -> Boolean),
) {
    fun isCurrent(): Boolean = scope.isActive && stillCurrent()
    fun assertCurrent() {
        if (!isCurrent()) throw CancellationException("Original video Composer entry retired")
    }
    fun commit(block: () -> Unit): Boolean = isCurrent() && commitIfCurrent {
        assertCurrent()
        block()
    }
}
