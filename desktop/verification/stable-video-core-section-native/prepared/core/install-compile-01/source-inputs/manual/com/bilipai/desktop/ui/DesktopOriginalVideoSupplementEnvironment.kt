package com.bilipai.desktop.ui
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException

internal class DesktopOriginalVideoSupplementEnvironment(
    val scope:CoroutineScope,
    private val stillOwned:()->Boolean,
    private val commitIfCurrent:((()->Unit)->Boolean),
) {
    fun commit(block:()->Unit):Boolean {
        if (!stillOwned()) throw CancellationException("Original video supplement owner retired")
        return commitIfCurrent(block)
    }
}
